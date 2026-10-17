"""Disposable schema/data recovery experiment; no persistent Compose access.

Default is an offline plan. --apply requires the orchestrator's heavy lease.
This verifies database continuity, not application replay or production RTO/RPO.
"""
import argparse
from datetime import datetime, timezone
import importlib.util
import json
from pathlib import Path
import re
import tempfile
import time
import uuid

spec = importlib.util.spec_from_file_location('backup', Path(__file__).with_name('backup-local-baseline.py'))
b = importlib.util.module_from_spec(spec)
spec.loader.exec_module(b)
restore_spec = importlib.util.spec_from_file_location('restore', Path(__file__).with_name('restore-isolated-backup.py'))
r = importlib.util.module_from_spec(restore_spec)
restore_spec.loader.exec_module(r)


def migrations(service):
    if service == 'assistant-db':
        directory = b.REPO / 'ai-assistant-service/migrations'
    elif service == 'sso-db':
        return []  # Keycloak owns its schema; this fixture tests opaque store continuity.
    else:
        module = 'user' if service.startswith('user-shard-') else service.removesuffix('-db')
        directory = b.REPO / (module + '-service/src/main/resources/db/migration')
    files = sorted(directory.glob('V*.sql'), key=lambda p: int(re.match(r'V(\d+)', p.name)[1]))
    b.require(directory.is_dir() and bool(files), 'Fixture migrations unavailable')
    return files


def wait_ready(name, database, user):
    deadline = time.monotonic() + 60
    while True:
        try:
            b.docker('exec', name, 'pg_isready', '-h', '127.0.0.1', '-U', user, '-d', database)
            return
        except b.Blocked:
            if time.monotonic() >= deadline:
                raise
            time.sleep(1)


def seed(name, database, user, service):
    for migration in migrations(service):
        b.sql(name, database, user, migration.read_text(encoding='utf-8'))
    b.sql(name, database, user, "CREATE TABLE recovery_marker(id integer PRIMARY KEY, command_id uuid UNIQUE NOT NULL); "
          "INSERT INTO recovery_marker VALUES(1,'00000000-0000-0000-0000-000000000021');")
    if service == 'auth-db':
        b.sql(name, database, user, "INSERT INTO identities(id,registration_phone) VALUES"
              "('00000000-0000-0000-0000-000000000001','+971528000100'); "
              "INSERT INTO external_identities VALUES('https://synthetic.invalid','synthetic-1',"
              "'00000000-0000-0000-0000-000000000001');")
    if service == 'wallet-db':
        b.sql(name, database, user, "INSERT INTO wallets(id,owner_id,currency,status,provisioning_status,ledger_account_id) VALUES"
              "('00000000-0000-0000-0000-000000000012','00000000-0000-0000-0000-000000000001','AED','ACTIVE','READY','00000000-0000-0000-0000-000000000002');")
    if service == 'payment-db':
        # Preserve a durable pending intent, never simulate application completion.
        b.sql(name, database, user, "INSERT INTO payments(id,requester_id,idempotency_key,request_hash,source_wallet_id,destination_wallet_id,currency,amount_minor,status) VALUES"
              "('00000000-0000-0000-0000-000000000032','00000000-0000-0000-0000-000000000001','recovery-21',repeat('a',64),"
              "'00000000-0000-0000-0000-000000000012','00000000-0000-0000-0000-000000000013','AED',100,'PENDING');")
    if service == 'user-db' or service.startswith('user-shard-'):
        b.sql(name, database, user, "INSERT INTO profiles(id,identity_id,display_name) VALUES"
              "('00000000-0000-0000-0000-000000000041','00000000-0000-0000-0000-000000000001','Synthetic Recovery');"
              "INSERT INTO profile_directory(identity_id,profile_id,shard_id,initialized) VALUES"
              "('00000000-0000-0000-0000-000000000001','00000000-0000-0000-0000-000000000041','primary',true);")
    if service == 'ledger-db':
        # Balanced funding uses the real immutable transfer and balance triggers.
        b.sql(name, database, user, "BEGIN; INSERT INTO accounts(id,wallet_id,currency,owner_id,account_kind) VALUES"
              "('00000000-0000-0000-0000-000000000001','00000000-0000-0000-0000-000000000011','AED',NULL,'CLEARING'),"
              "('00000000-0000-0000-0000-000000000002','00000000-0000-0000-0000-000000000012','AED','00000000-0000-0000-0000-000000000001','CUSTOMER');"
              "INSERT INTO transfer_requests(payment_id,requester_id,debit_account_id,credit_account_id,currency,amount_minor,outcome) VALUES"
              "('00000000-0000-0000-0000-000000000021','00000000-0000-0000-0000-000000000001',"
              "'00000000-0000-0000-0000-000000000001','00000000-0000-0000-0000-000000000002','AED',100,'PENDING');"
              "INSERT INTO transfers(payment_id,debit_account_id,credit_account_id,currency,amount_minor) VALUES"
              "('00000000-0000-0000-0000-000000000021','00000000-0000-0000-0000-000000000001','00000000-0000-0000-0000-000000000002','AED',100);"
              "UPDATE transfer_requests SET outcome='POSTED'; COMMIT;")


def run():
    b.local_daemon()
    services = {**b.DBS, **b.OPTIONAL_DBS, 'user-shard-s1': ('bank', 'bank'), 'user-shard-s2': ('bank', 'bank')}
    report = {'source': b.source_revision(), 'captured_at': datetime.now(timezone.utc).isoformat(),
              'scope': 'isolated synthetic database continuity; not application replay', 'databases': []}
    with tempfile.TemporaryDirectory(prefix='armoney-recovery-private-') as temporary:
        directory = b.private_directory(Path(temporary) / 'archives')
        for service, (database, user) in services.items():
            token = uuid.uuid4().hex
            name = 'armoney-verify-' + token + '-source'
            volume = 'armoney-verify-' + token + '-source-data'
            image = b.ASSISTANT_IMAGE if service == 'assistant-db' else 'postgres:17.6-alpine'
            # Existing local pinned image only. No implicit pull or provider setup.
            image_id = json.loads(b.docker('image', 'inspect', image))[0]['Id']
            container_created = volume_created = False
            try:
                b.docker('volume', 'create', '--label', b.LABEL + '=' + token, volume)
                volume_created = True
                b.owned_resource('volume', volume, token)
                b.docker('create', '--name', name, '--label', b.LABEL + '=' + token, '--network', 'none',
                         '--restart', 'no', '--mount', 'type=volume,source=' + volume + ',target=/var/lib/postgresql/data',
                         '-e', 'POSTGRES_HOST_AUTH_METHOD=trust', '-e', 'POSTGRES_USER=' + user, '-e', 'POSTGRES_DB=' + database, image_id)
                container_created = True
                target = b.owned_resource('container', name, token)
                b.require(target['HostConfig']['NetworkMode'] == 'none' and not target['HostConfig'].get('PortBindings'), 'Fixture isolation failed')
                b.require(len(target['Mounts']) == 1 and target['Mounts'][0].get('Name') == volume, 'Fixture mount rejected')
                b.docker('start', name)
                wait_ready(name, database, user)
                seed(name, database, user, service)
                archive = directory / (service + '.dump')
                record = {'service': service, 'database': database, 'user': user, 'archive': archive.name,
                          'image_id': image_id, 'expected_fixture_count': 1,
                          'relations': json.loads(b.sql(name, database, user, b.RELATIONS)),
                          'metrics': b.snapshot_metrics(name, database, user, service, 1)}
                dump_started = time.monotonic()
                with archive.open('xb') as stream:
                    b.docker('exec', name, 'pg_dump', '-Fc', '-U', user, '-d', database, stdout=stream)
                record.update(bytes=archive.stat().st_size, sha256=b.checksum(archive))
                # Outage begins after final synthetic acknowledgement: the source
                # is stopped before restoration, so its data cannot satisfy checks.
                b.docker('stop', name)
                record['restore'] = b.restore_verify(archive, record)
                record['dump_and_database_restore_seconds'] = round(time.monotonic() - dump_started, 3)
                record['capture_point'] = 'one acknowledged marker command; zero missing marker/table fingerprints in this quiesced fixture'
                report['databases'].append(record)
            finally:
                if container_created:
                    b.owned_resource('container', name, token)
                    b.docker('container', 'rm', '-f', name)
                if volume_created:
                    b.owned_resource('volume', volume, token)
                    b.docker('volume', 'rm', volume)
        manifest = {'format': 1, 'project': b.PROJECT, 'status': 'database_restore_verified',
                    'optional_database_coverage': ['assistant-db'],
                    'user_shard_coverage': {'included': ['s1', 's2'], 'configured': ['s1'], 'directory': ['primary', 's2']},
                    'databases': report['databases']}
        manifest_file = directory / 'manifest.json'
        manifest_file.write_text(json.dumps(manifest), encoding='utf-8')
        r.preflight(directory)
        report['preflight'] = 'complete synthetic inventory passed'
    # Only aggregate metrics/fingerprints/timing leave the private temporary directory.
    print(json.dumps(report, indent=2))


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--apply', action='store_true')
    if parser.parse_args().apply:
        run()
    else:
        print('PLAN ONLY: isolated synthetic database dump/restore continuity; no Docker access')
