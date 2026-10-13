"""Quiesced local ArMoney logical backup; default is an offline plan.

Use --apply --output <new outside-repository directory>. Every archive is restored
only to a fresh, network-isolated PostgreSQL container. No live restore exists.
Archives contain sensitive database data: retain only in private local storage.
"""
import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import time
import uuid

PROJECT = 'arman-bank'
DOCKER_ENDPOINT = None
DOCKER_ENV = None
REPO = Path(__file__).resolve().parents[1]
DBS = {name + '-db': ('bank', 'bank') for name in ('auth', 'user', 'wallet', 'payment', 'ledger')}
DBS['sso-db'] = ('keycloak', 'keycloak')
WRITERS = {'edge', 'app-gateway', 'auth-service', 'user-service', 'wallet-service', 'payment-service', 'ledger-service', 'sso-service'}
AUX = {'ledger-db-replica1', 'ledger-db-replica2', 'ledger-replication-setup'}
LABEL = 'io.armoney.baseline-verification'
RELATIONS = """SELECT json_build_object('tables',count(*) FILTER (WHERE relkind IN ('r','p')),
'indexes',count(*) FILTER (WHERE relkind IN ('i','I')),
'sequences',count(*) FILTER (WHERE relkind='S'))
FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
WHERE n.nspname NOT IN ('pg_catalog','information_schema') AND n.nspname NOT LIKE 'pg_toast%';"""


class Blocked(Exception):
    pass


def require(ok, message):
    if not ok:
        raise Blocked(message)


def docker(*args, stdin=None, stdout=None, timeout=300):
    # Never forward Docker/SQL stderr: it can contain data or credentials.
    require(DOCKER_ENDPOINT is not None, 'Docker endpoint has not been pinned')
    try:
        result = subprocess.run(['docker', '--host', DOCKER_ENDPOINT, *args], env=DOCKER_ENV, stdin=stdin, stdout=stdout or subprocess.PIPE,
                                stderr=subprocess.PIPE, timeout=timeout, check=False)
    except (OSError, subprocess.TimeoutExpired):
        raise Blocked('Docker unavailable or operation timed out') from None
    require(result.returncode == 0, 'Docker operation failed; output withheld')
    return result.stdout.decode('utf-8').strip() if stdout is None else None


def local_daemon():
    global DOCKER_ENDPOINT, DOCKER_ENV
    DOCKER_ENDPOINT = None
    original = dict(os.environ)
    # Resolve once, then remove every ambient Docker override from every operation.
    clean = {key: value for key, value in original.items() if not key.upper().startswith('DOCKER_')}
    host = original.get('DOCKER_HOST')
    context_name = original.get('DOCKER_CONTEXT')
    if context_name or not host:
        args = ['docker', 'context', 'inspect'] + ([context_name] if context_name else [])
        try:
            result = subprocess.run(args, env=clean, capture_output=True, timeout=30, check=False)
        except (OSError, subprocess.TimeoutExpired):
            raise Blocked('Cannot resolve Docker context') from None
        require(result.returncode == 0, 'Cannot resolve Docker context')
        context = json.loads(result.stdout)
        require(len(context) == 1, 'Ambiguous Docker context')
        host = context[0]['Endpoints']['docker']['Host']
    require(isinstance(host, str) and (
        re.fullmatch(r'unix:///[^\s]+', host) or
        re.fullmatch(r'npipe:/{2,4}\./pipe/[A-Za-z0-9_.-]+', host)),
        'Only an absolute local Unix socket or local-dot named pipe is permitted')
    DOCKER_ENDPOINT, DOCKER_ENV = host, clean


def db_spec(service):
    if service in DBS:
        return DBS[service]
    if re.fullmatch(r'user-shard-s[1-9][0-9]*', service):
        return ('bank', 'bank')
    return None


def validate_inventory(items):
    found = {}
    for item in items:
        labels = item['Config'].get('Labels') or {}
        service = labels.get('com.docker.compose.service', '')
        require(labels.get('com.docker.compose.project') == PROJECT, 'Unexpected Compose project')
        require(labels.get('com.docker.compose.oneoff', '').lower() == 'false', 'One-off container rejected')
        require(service not in found, 'Duplicate service container rejected')
        require(service in WRITERS or service in AUX or db_spec(service), 'Unexpected Compose service')
        require(re.fullmatch(r'[0-9a-f]{64}', item['Id']) is not None, 'Invalid container ID')
        require(not item['State'].get('Paused') and not item['State'].get('Restarting'), 'Unstable container state')
        if db_spec(service) or service in AUX:
            require(item['Config']['Image'] == 'postgres:17.6-alpine', 'Unexpected PostgreSQL image')
        if db_spec(service):
            require(item['State']['Running'], 'All discovered primary databases must be running')
            database, user = db_spec(service)
            env = dict(value.split('=', 1) for value in item['Config'].get('Env', []) if '=' in value)
            require(env.get('POSTGRES_DB') == database and env.get('POSTGRES_USER') == user,
                    'Unexpected database identity')
        found[service] = item
    require(DBS.keys() <= found.keys(), 'Missing core database (including Keycloak)')
    require(WRITERS <= found.keys(), 'Missing application/SSO/edge service')
    return found


def inventory():
    ids = docker('ps', '-aq', '--filter', 'label=com.docker.compose.project=' + PROJECT).split()
    require(ids, 'Local Compose project not found')
    return validate_inventory(json.loads(docker('inspect', *ids)))


def shard_coverage(found):
    """Check both runtime configuration and historical placement while writers are stopped."""
    env = dict(value.split('=', 1) for value in found['user-service']['Config'].get('Env', []) if '=' in value)
    raw = env.get('USER_SHARD_IDS', '')
    configured = raw.split(',') if raw.strip() else []
    require(len(configured) == len(set(configured)) and all(re.fullmatch(r's[1-9][0-9]*', shard) for shard in configured),
            'Unsupported or duplicate configured user shard')
    available = {service.removeprefix('user-shard-') for service in found if service.startswith('user-shard-')}
    require(set(configured) <= available, 'Configured user shard database is missing')
    for shard in configured:
        require(env.get('USER_SHARD_' + shard.upper() + '_DB_URL') ==
                'jdbc:postgresql://user-shard-' + shard + ':5432/bank',
                'Configured user shard endpoint is outside the supported local topology')
    used = json.loads(sql(found['user-db']['Id'], 'bank', 'bank',
        "SELECT coalesce(json_agg(shard_id ORDER BY shard_id),'[]'::json) "
        "FROM (SELECT DISTINCT shard_id FROM profile_directory) placements"))
    require(isinstance(used, list) and all(isinstance(shard, str) and
            (shard == 'primary' or re.fullmatch(r's[1-9][0-9]*', shard)) for shard in used),
            'Unsupported historical user shard placement')
    require(set(used) <= available | {'primary'}, 'Historical user shard database is missing')
    return {'configured': sorted(configured), 'directory': sorted(used), 'included': sorted(available)}


def outside_repo(path):
    path = path.expanduser().resolve()
    require(not path.is_relative_to(REPO), 'Backup output must be outside the repository')
    # Also reject another checkout, including a primary checkout beside this worktree.
    require(not any((p / '.git').exists() for p in (path, *path.parents)), 'Backup output is inside a Git checkout')
    return path


def private_directory(path):
    path = outside_repo(path)
    require(path.parent.is_dir() and not path.exists(), 'Output must be a new directory with an existing parent')
    path.mkdir(mode=0o700)
    if os.name == 'nt':
        script = ('$ErrorActionPreference="Stop"; $p=$env:BASELINE_ACL_PATH; '
                  '$a=New-Object System.Security.AccessControl.DirectorySecurity; '
                  '$a.SetAccessRuleProtection($true,$false); '
                  '$sid=[System.Security.Principal.WindowsIdentity]::GetCurrent().User; '
                  '$r=New-Object System.Security.AccessControl.FileSystemAccessRule($sid,"FullControl",'
                  '"ContainerInherit,ObjectInherit","None","Allow"); '
                  '$a.AddAccessRule($r); [System.IO.Directory]::SetAccessControl($p,$a)')
        try:
            subprocess.run(['powershell', '-NoProfile', '-NonInteractive', '-Command', script],
                           env=dict(os.environ, BASELINE_ACL_PATH=str(path)), check=True, capture_output=True)
        except (OSError, subprocess.CalledProcessError):
            raise Blocked('Unable to restrict backup directory permissions') from None
    return path


def sql(container, database, user, query):
    return docker('exec', container, 'psql', '-X', '-A', '-t', '-v', 'ON_ERROR_STOP=1',
                  '-U', user, '-d', database, '-c', query)


def source_revision():
    result = subprocess.run(['git', '-C', str(REPO), 'rev-parse', 'HEAD'], capture_output=True, check=False)
    revision = result.stdout.decode().strip()
    require(result.returncode == 0 and re.fullmatch(r'[0-9a-f]{40,64}', revision), 'Cannot establish source revision')
    state = subprocess.run(['git', '-C', str(REPO), 'status', '--porcelain'], capture_output=True, check=False)
    require(state.returncode == 0, 'Cannot establish source worktree state')
    return {'git_head': revision, 'worktree_dirty': bool(state.stdout.strip())}


def snapshot_metrics(container, database, user, service, expected_fixture_count=200):
    tables = json.loads(sql(container, database, user, """SELECT coalesce(json_agg(json_build_array(schemaname,tablename)
    ORDER BY schemaname,tablename),'[]'::json) FROM pg_tables
    WHERE schemaname NOT IN ('pg_catalog','information_schema') AND schemaname NOT LIKE 'pg_toast%';"""))
    result = {'table_rows': {}, 'flyway': None}
    for schema, table in tables:
        # Identifiers come from PostgreSQL catalogs and are still quoted correctly.
        qualified = '.'.join('"' + name.replace('"', '""') + '"' for name in (schema, table))
        result['table_rows'][schema + '.' + table] = int(sql(container, database, user, 'SELECT count(*) FROM ' + qualified))
        if table == 'flyway_schema_history':
            result['flyway'] = json.loads(sql(container, database, user,
                "SELECT coalesce(json_agg(json_build_object('version',version,'checksum',checksum,'success',success) "
                "ORDER BY installed_rank),'[]'::json) FROM " + qualified))
    if service == 'auth-db':
        result['fixture_count'] = int(sql(container, database, user,
            "SELECT count(*) FROM identities i JOIN external_identities e ON e.identity_id=i.id "
            "WHERE i.registration_phone IN (SELECT '+971528000' || n::text FROM generate_series(100,"
            + str(99 + expected_fixture_count) + ") n)"))
        require(result['fixture_count'] == expected_fixture_count, 'Expected SSO fixture identities are missing')
    if service == 'wallet-db':
        result['owner_count'] = int(sql(container, database, user, 'SELECT count(DISTINCT owner_id) FROM wallets'))
    if service == 'ledger-db':
        result['financial'] = json.loads(sql(container, database, user, """SELECT json_build_object(
          'accounts',(SELECT count(*) FROM accounts),
          'owners',(SELECT count(DISTINCT owner_id) FROM accounts WHERE account_kind='CUSTOMER'),
          'balance_sum_fils',(SELECT coalesce(sum(balance_minor),0) FROM accounts),
          'postings',(SELECT count(*) FROM postings),
          'posting_sum_fils',(SELECT coalesce(sum(amount_minor),0) FROM postings),
          'unbalanced_payments',(SELECT count(*) FROM (SELECT payment_id FROM postings GROUP BY payment_id HAVING sum(amount_minor)<>0 OR count(*)<>2) p),
          'projection_mismatches',(SELECT count(*) FROM accounts a WHERE balance_minor <> coalesce((SELECT sum(amount_minor) FROM postings p WHERE p.account_id=a.id),0)),
          'negative_customer_balances',(SELECT count(*) FROM accounts WHERE account_kind <> 'CLEARING' AND balance_minor<0),
          'non_aed_accounts',(SELECT count(*) FROM accounts WHERE currency<>'AED'))"""))
        financial = result['financial']
        require(all(financial[key] == 0 for key in ('balance_sum_fils', 'posting_sum_fils', 'unbalanced_payments',
                'projection_mismatches', 'negative_customer_balances', 'non_aed_accounts')),
                'Ledger reconciliation failed')
    return result


def checksum(path):
    digest = hashlib.sha256()
    with path.open('rb') as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''):
            digest.update(block)
    return digest.hexdigest()


def owned_resource(kind, name, token):
    require(name.startswith('armoney-verify-' + token + '-'), 'Verification resource name rejected')
    item = json.loads(docker(kind, 'inspect', name))[0]
    labels = (item.get('Config', {}).get('Labels') if kind == 'container' else item.get('Labels')) or {}
    require(labels.get(LABEL) == token, 'Verification resource ownership mismatch')
    return item


def restore_verify(archive, metadata):
    token = uuid.uuid4().hex
    name = 'armoney-verify-' + token + '-postgres'
    volume = 'armoney-verify-' + token + '-data'
    container_created = volume_created = False
    database, user = metadata['database'], metadata['user']
    # Pin to the actual already-local source image, never run a manifest-provided tag.
    image = metadata['image_id']
    require(re.fullmatch(r'sha256:[0-9a-f]{64}', image), 'Invalid source image ID')
    require(checksum(archive) == metadata['sha256'], 'Archive checksum mismatch')
    try:
        docker('volume', 'create', '--label', LABEL + '=' + token, volume)
        volume_created = True
        owned_resource('volume', volume, token)
        docker('create', '--name', name, '--label', LABEL + '=' + token,
               '--network', 'none', '--restart', 'no', '--mount',
               'type=volume,source=' + volume + ',target=/var/lib/postgresql/data',
               '-e', 'POSTGRES_HOST_AUTH_METHOD=trust', '-e', 'POSTGRES_USER=' + user,
               '-e', 'POSTGRES_DB=' + database, image)
        container_created = True
        target = owned_resource('container', name, token)
        require(target['HostConfig']['NetworkMode'] == 'none' and not target['HostConfig'].get('PortBindings'),
                'Verification isolation check failed')
        require(len(target['Mounts']) == 1 and target['Mounts'][0].get('Name') == volume,
                'Verification mount rejected')
        docker('start', name)
        for attempt in range(60):
            try:
                # TCP excludes the temporary init server, which accepts Unix sockets only.
                docker('exec', name, 'pg_isready', '-h', '127.0.0.1', '-U', user, '-d', database)
                break
            except Blocked:
                if attempt == 59:
                    raise Blocked('Verification database did not become ready') from None
                time.sleep(1)
        with archive.open('rb') as stream:
            docker('exec', '-i', name, 'pg_restore', '--exit-on-error', '--no-owner', '--no-privileges',
                   '-U', user, '-d', database, stdin=stream, timeout=1800)
        counts = json.loads(sql(name, database, user, RELATIONS))
        require(counts == metadata['relations'], 'Restored relation counts differ')
        metrics = snapshot_metrics(name, database, user, metadata['service'], metadata['expected_fixture_count'])
        require(metrics == metadata['metrics'], 'Restored schema/data metrics differ')
        return {'status': 'database_restore_verified', 'relations': counts, 'metrics_match': True}
    finally:
        # No compose down, pruning, or removal of a source resource is supported.
        if container_created:
            owned_resource('container', name, token)
            docker('container', 'rm', '-f', name)
        if volume_created:
            owned_resource('volume', volume, token)
            docker('volume', 'rm', volume)


def backup(output, expected_fixture_count=200):
    require(type(expected_fixture_count) is int and 1 <= expected_fixture_count <= 900, 'Expected fixture count must be 1..900')
    local_daemon()
    revision = source_revision()
    found = inventory()
    output = private_directory(output)
    running = [item['Id'] for service, item in found.items() if service in WRITERS and item['State']['Running']]
    manifest = {'format': 1, 'project': PROJECT, 'created_at': datetime.now(timezone.utc).isoformat(),
                'status': 'incomplete', 'source': revision, 'databases': []}
    manifest_path = output / 'manifest.json'
    def save():
        manifest_path.write_text(json.dumps(manifest, indent=2) + '\n', encoding='utf-8')
    save()
    try:
        if running:
            docker('stop', '--time', '30', *running)
        current = inventory()
        require({k: v['Id'] for k, v in current.items()} == {k: v['Id'] for k, v in found.items()},
                'Compose inventory changed during backup')
        require(not any(item['State']['Running'] for service, item in current.items() if service in WRITERS),
                'Application writers are still running')
        manifest['user_shard_coverage'] = shard_coverage(current)
        save()
        for service, item in sorted(found.items()):
            if not db_spec(service):
                continue
            database, user = db_spec(service)
            cid = item['Id']
            require(sql(cid, database, user, 'SELECT pg_is_in_recovery();') == 'f', 'Primary database required')
            archive = output / (service + '.dump')
            metadata = {'service': service, 'database': database, 'user': user, 'archive': archive.name,
                        'image': item['Config']['Image'], 'image_id': item['Image'],
                        'server_version': sql(cid, database, user, 'SHOW server_version;'),
                        'pg_dump_version': docker('exec', cid, 'pg_dump', '--version'),
                        'relations': json.loads(sql(cid, database, user, RELATIONS)),
                        'expected_fixture_count': expected_fixture_count,
                        'metrics': snapshot_metrics(cid, database, user, service, expected_fixture_count)}
            with archive.open('xb') as stream:
                docker('exec', cid, 'pg_dump', '-Fc', '-U', user, '-d', database, stdout=stream, timeout=1800)
            metadata['sha256'] = checksum(archive)
            metadata['bytes'] = archive.stat().st_size
            manifest['databases'].append(metadata)
            save()
    finally:
        failures = []
        for cid in running:
            try:
                docker('start', cid)
            except Blocked:
                failures.append(cid)
        require(not failures, 'One or more previously running services could not be restarted; inspect local stack')
    # Restore checks run after writers restart, minimizing the local outage.
    for metadata in manifest['databases']:
        metadata['restore_verification'] = restore_verify(output / metadata['archive'], metadata)
        save()
    manifest['fixture_count'] = next(m['metrics']['fixture_count'] for m in manifest['databases'] if m['service'] == 'auth-db')
    manifest['status'] = 'database_restore_verified'
    save()
    print('Backup and isolated restore verification completed for ' + str(len(manifest['databases'])) + ' databases.')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--apply', action='store_true', help='Stop local writers, dump databases, restart writers, verify isolated restores')
    parser.add_argument('--output', type=Path, help='New restricted directory outside all Git checkouts')
    parser.add_argument('--expected-fixture-count', type=int, default=200, help='Expected contiguous SSO fixtures starting at ordinal 100 (default 200)')
    args = parser.parse_args()
    if not args.apply:
        print('PLAN ONLY (no Docker access): require local arman-bank; stop previously running application/SSO/edge;')
        print('dump auth/user/wallet/payment/ledger/Keycloak and discovered user shards; restart writers;')
        print('verify each dump in a fresh network-isolated PostgreSQL container and remove only owned verification resources.')
        return
    require(args.output is not None, '--apply requires --output')
    backup(args.output, args.expected_fixture_count)


if __name__ == '__main__':
    try:
        main()
    except (Blocked, OSError, ValueError, KeyError, TypeError) as error:
        print(str(error) if isinstance(error, Blocked) else 'Backup blocked; diagnostic details withheld', file=sys.stderr)
        sys.exit(1)
