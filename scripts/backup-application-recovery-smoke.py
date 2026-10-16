"""SOURCE acceptance tool: offline plan by default; heavy acceptance is opt-in.

Only a dedicated, labelled, initially empty daemon is accepted. No Compose,
host ports, host CI override, provider credentials, builds or image pulls.
"""
import argparse
from datetime import datetime, timezone
import hashlib
import importlib.util
import ipaddress
import json
from pathlib import Path
import re
import secrets
import subprocess
import sys
import tempfile
import time
import uuid

from recovery_fixture_runtime import BankFixture, GuardError, OwnedRuntime, require, until, private_directory

REPO = Path(__file__).resolve().parents[1]
SERVICES = ('app-gateway', 'auth-service', 'user-service', 'wallet-service', 'payment-service', 'ledger-service')
STORES = {s + '-db': ('bank', 'bank') for s in ('auth', 'user', 'wallet', 'payment', 'ledger')}
STORES.update({'sso-db': ('keycloak', 'keycloak'), 'user-shard-s1': ('bank', 'bank'),
               'user-shard-s2': ('bank', 'bank'), 'assistant-db': ('assistant', 'assistant_operator')})
PINNED = {'postgres': 'postgres:17.6-alpine', 'keycloak': 'quay.io/keycloak/keycloak:26.7.4',
          'edge': 'caddy:2.11.4-alpine', 'runner': 'python:3.13.7-alpine',
          'assistant': 'pgvector/pgvector:pg17@sha256:ac08538c6f8b9904c33c8224c5e5706dbe760aca29db1d096972b4052c22a75d'}
ISSUER = 'https://recovery.test/sso/realms/armoney'
CALLBACK = 'com.armoney.android:/oauth/callback'
_loader = importlib.util.spec_from_file_location('recovery_stage', Path(__file__).with_name('stage-application-recovery-ci.py'))
_staging = importlib.util.module_from_spec(_loader)
_loader.loader.exec_module(_staging)

# Minimal real Keycloak login-form/authorization-code/S256 flow, no password grant
# for native clients. The custom-scheme redirect is captured without opening it.
PKCE_PROGRAM = r'''
import base64, hashlib, html.parser, http.cookiejar, json, secrets, ssl, sys
import urllib.request, urllib.parse, urllib.error
p=json.load(sys.stdin)
callback='com.armoney.android:/oauth/callback'
class Redirect(urllib.request.HTTPRedirectHandler):
    def http_error_302(self, req, fp, code, msg, headers):
        url=headers.get('Location','')
        if url.startswith(callback+'?'):
            self.callback=url
            raise urllib.error.HTTPError(req.full_url,code,msg,headers,fp)
        return super().http_error_302(req,fp,code,msg,headers)
    http_error_301=http_error_303=http_error_307=http_error_308=http_error_302
    def redirect_request(self, req, fp, code, msg, headers, url):
        if url.startswith(callback+'?'):
            self.callback=url
            return None
        parsed=urllib.parse.urlsplit(url)
        if parsed.scheme!='https' or parsed.netloc!='recovery.test':
            raise RuntimeError('foreign redirect')
        return super().redirect_request(req,fp,code,msg,headers,url)
class Form(html.parser.HTMLParser):
    action=None
    fields={}
    def handle_starttag(self, tag, attrs):
        a=dict(attrs)
        if tag=='form' and a.get('id')=='kc-form-login': self.action=a['action']
        if tag=='input' and a.get('type')=='hidden' and a.get('name'): self.fields[a['name']]=a.get('value','')
redirect=Redirect()
opener=urllib.request.build_opener(urllib.request.ProxyHandler({}),redirect,urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()),
    urllib.request.HTTPSHandler(context=ssl.create_default_context(cafile='/tmp/recovery-ca.crt')))
verifier=secrets.token_urlsafe(32); state=secrets.token_urlsafe(32)
query=dict(client_id='armoney-android',response_type='code',scope='openid',redirect_uri=callback,state=state,
    nonce=secrets.token_urlsafe(32),code_challenge_method='S256',code_challenge=base64.urlsafe_b64encode(hashlib.sha256(verifier.encode()).digest()).rstrip(b'=').decode())
with opener.open('https://recovery.test/sso/realms/armoney/protocol/openid-connect/auth?'+urllib.parse.urlencode(query),timeout=15) as response:
    form=Form(); form.feed(response.read().decode())
parsed=urllib.parse.urlsplit(form.action or '')
if parsed.scheme!='https' or parsed.netloc!='recovery.test': raise RuntimeError('login form rejected')
form.fields.update(username=p['username'],password=p['password'])
try:
    opener.open(urllib.request.Request(form.action,data=urllib.parse.urlencode(form.fields).encode()),timeout=15).close()
except urllib.error.HTTPError as error:
    error.close()
received=urllib.parse.parse_qs(urllib.parse.urlsplit(getattr(redirect,'callback','')).query)
if received.get('state')!=[state] or 'code' not in received: raise RuntimeError('authorization failed')
payload=dict(client_id='armoney-android',grant_type='authorization_code',redirect_uri=callback,
    code=received['code'][0],code_verifier=verifier)
with opener.open(urllib.request.Request('https://recovery.test/sso/realms/armoney/protocol/openid-connect/token',
    data=urllib.parse.urlencode(payload).encode()),timeout=15) as response:
    print(response.read().decode())
'''


def revision():
    result = subprocess.run(['git', '-c', 'safe.directory=' + str(REPO), 'rev-parse', 'HEAD'],
                            cwd=REPO, capture_output=True, text=True, timeout=10)
    require(result.returncode == 0 and re.fullmatch('[0-9a-f]{40}', result.stdout.strip()) is not None,
            'Source revision unavailable')
    return result.stdout.strip()


def load_images(path, head):
    path = Path(path)
    require(path.is_file() and not path.is_symlink() and path.stat().st_size <= 65536, 'Image manifest file rejected')
    data = json.loads(path.read_text(encoding='utf-8'))
    require(isinstance(data, dict) and set(data) == {'revision', 'images'} and data['revision'] == head, 'Image manifest revision differs')
    require(isinstance(data['images'], dict) and set(data['images']) == set(SERVICES), 'Exact six bank images required')
    require(all(isinstance(v, str) and re.fullmatch('sha256:[0-9a-f]{64}', v)
                for v in data['images'].values()), 'Bank images must be immutable local IDs')
    return data['images']


def wait_database(runtime, name, database, user):
    def probe():
        try:
            runtime.exec(name, 'pg_isready', '-h', '127.0.0.1', '-U', user, '-d', database)
            return True
        except GuardError:
            return False
    until(probe, bool, 90)


def build_databases(runtime, phase, images, password):
    network = runtime.network(phase)
    names = {}
    for alias, (database, user) in STORES.items():
        image = images['assistant' if alias == 'assistant-db' else 'postgres']
        name = runtime.create(phase, alias, image, network,
                              {'POSTGRES_DB': database, 'POSTGRES_USER': user, 'POSTGRES_PASSWORD': password},
                              mounts=(('data', '/var/lib/postgresql/data'),))
        names[alias] = name
        runtime.action(name, 'start')
        wait_database(runtime, name, database, user)
    return network, names


def start_app(runtime, phase, network, images, credentials, directory, import_realm):
    apps = {}
    for service in SERVICES:
        environment = {'INTERNAL_AUTH_KEY': credentials['internal']}
        if service != 'app-gateway':
            environment.update(DB_URL='jdbc:postgresql://' + service.replace('-service', '-db') + ':5432/bank',
                               DB_USER='bank', DB_PASSWORD=credentials['db'])
        for target in ('auth', 'user', 'wallet', 'payment', 'ledger'):
            environment[target.upper() + '_BASE_URL'] = 'http://' + target + '-service:8080'
        if service == 'auth-service':
            environment.update(SSO_ISSUER=ISSUER,
                               SSO_INTROSPECTION_URL='http://sso-service:8080/sso/realms/armoney/protocol/openid-connect/token/introspect',
                               SSO_CLIENT_ID='armoney-auth', SSO_CLIENT_SECRET=credentials['client'])
        if service == 'user-service':
            environment.update(USER_SHARD_IDS='s1,s2', USER_SHARD_DEFAULT='s1',
                               USER_SHARD_PREFIX_MAP='r1:s2,r2:primary')
            for shard in ('s1', 's2'):
                environment.update({f'USER_SHARD_{shard.upper()}_DB_URL': f'jdbc:postgresql://user-shard-{shard}:5432/bank',
                                    f'USER_SHARD_{shard.upper()}_DB_USER': 'bank',
                                    f'USER_SHARD_{shard.upper()}_DB_PASSWORD': credentials['db']})
        if service == 'payment-service':
            environment['EMAIL_DELIVERY_ENABLED'] = 'false'
        apps[service] = runtime.create(phase, service, images[service], network, environment)
    kc_env = dict(KC_DB='postgres', KC_DB_URL='jdbc:postgresql://sso-db:5432/keycloak',
                  KC_DB_USERNAME='keycloak', KC_DB_PASSWORD=credentials['db'], KC_HTTP_ENABLED='true',
                  KC_HTTP_RELATIVE_PATH='/sso', KC_HOSTNAME='https://recovery.test/sso',
                  KC_PROXY_HEADERS='xforwarded', KC_BOOTSTRAP_ADMIN_USERNAME='admin',
                  KC_BOOTSTRAP_ADMIN_PASSWORD=credentials['admin'], SSO_CLIENT_SECRET=credentials['client'])
    apps['sso-service'] = runtime.create(phase, 'sso-service', images['keycloak'], network, kc_env,
                                       command=('start', '--import-realm') if import_realm else ('start',))
    if import_realm:
        realm_directory = directory / phase / 'import'
        realm_directory.mkdir(parents=True)
        (realm_directory / 'armoney-realm.json').write_bytes((REPO / 'sso-service/armoney-realm.json').read_bytes())
        runtime.copy_in(apps['sso-service'], realm_directory, '/opt/keycloak/data/')
    config = directory / (phase + '-Caddyfile')
    config.write_text('recovery.test {\n tls internal\n handle /sso/* {\n reverse_proxy sso-service:8080\n }\n'
                      ' handle {\n reverse_proxy app-gateway:8080\n }\n}\n', encoding='utf-8')
    apps['edge'] = runtime.create(phase, 'recovery.test', images['edge'], network,
                                 mounts=(('data', '/data'), ('config', '/config')))
    runtime.copy_in(apps['edge'], config, '/etc/caddy/Caddyfile')
    apps['runner'] = runtime.create(phase, 'runner', images['runner'], network, command=('sleep', '86400'))
    for name in apps.values():
        runtime.action(name, 'start')
    ca = directory / (phase + '-ca.crt')
    def copy_ca():
        try:
            runtime.copy_out(apps['edge'], '/data/caddy/pki/authorities/local/root.crt', ca)
            return True
        except GuardError:
            return False
    until(copy_ca, bool)
    runtime.copy_in(apps['runner'], ca, '/tmp/recovery-ca.crt')
    fixture = BankFixture(runtime, apps['runner'])
    for service in SERVICES:
        def service_ready():
            try:
                response = fixture.ready(service)
                return response[0] == 200 and 'UP' in json.dumps(response[1])
            except GuardError:
                return False
        until(service_ready, bool)
    def discovery():
        try:
            return fixture.request('GET', '/sso/realms/armoney/.well-known/openid-configuration')
        except GuardError:
            return [0, None]
    result = until(discovery, lambda r: r[0] == 200)
    require(result[1]['issuer'] == ISSUER, 'Recovered issuer differs')
    def ready():
        try:
            return fixture.request('GET', '/v1/auth/me')[0] == 401
        except GuardError:
            return False
    until(ready, bool)
    return apps, fixture


def oidc(runtime, runner, username, password):
    return json.loads(runtime.exec(runner, 'python', '-c', PKCE_PROGRAM,
                                  input=json.dumps(dict(username=username, password=password)).encode()))


def synthetic_users(runtime, apps, fixture, admin_password):
    admin = fixture.expect(200, 'POST', '/sso/realms/master/protocol/openid-connect/token', form=True,
                           body=dict(client_id='admin-cli', grant_type='password', username='admin', password=admin_password))['access_token']
    users = []
    # Reserved synthetic fixture phone numbers, used only in a fresh realm.
    for index in range(3):
        phone = '+97150999200' + str(index + 1)
        password = secrets.token_urlsafe(24)
        fixture.expect(201, 'POST', '/sso/admin/realms/armoney/users', token=admin,
                       body=dict(username=phone, email=f'recovery-{index}@example.test', emailVerified=False,
                                 firstName='Synthetic', lastName='Recovery', enabled=True,
                                 credentials=[dict(type='password', value=password, temporary=False)]))
        tokens = oidc(runtime, apps['runner'], phone, password)
        token = fixture.expect(200, 'POST', '/v1/auth/sso', body={'access_token': tokens['access_token']})['access_token']
        owner = str(uuid.UUID(fixture.expect(200, 'GET', '/v1/auth/me', token=token)['id']))
        profile = fixture.expect(200, 'PUT', '/v1/users/me', token=token, body={'display_name': 'Synthetic Recovery ' + str(index)})
        status, wallet = fixture.request('POST', '/v1/wallets', token=token, body={'currency': 'AED'})
        require(status in (200, 202), 'Wallet creation failed')
        def ready_wallet():
            rows = fixture.expect(200, 'GET', '/v1/wallets', token=token)['wallets']
            return rows[0] if rows else None
        wallet = until(ready_wallet, lambda w: w is not None and w['provisioning_status'] == 'READY', 90)
        require(wallet['owner_id'] == owner and wallet['currency'] == 'AED', 'Wallet owner/currency differs')
        users.append(dict(phone=phone, password=password, oidc=tokens, token=token,
                          owner=owner, wallet=wallet, profile=profile))
    revoked = fixture.expect(200, 'POST', '/v1/auth/sso', body={'access_token': users[0]['oidc']['access_token']})['access_token']
    fixture.expect(204, 'POST', '/v1/auth/logout', token=revoked)
    fixture.expect(401, 'GET', '/v1/auth/me', token=revoked)
    return users, revoked


def synthetic_legacy_users(fixture):
    users = []
    # Legacy auth email (rather than optional OIDC notification email) is the
    # trusted placement input. Cover primary and s2 in addition to OIDC's s1.
    for index in (1, 2):
        email = f'r{index}-legacy@example.test'
        password = secrets.token_urlsafe(24)
        fixture.expect(202, 'POST', '/v1/auth/register',
                       body=dict(email=email, password=password, phone_number='+97150999201' + str(index)))
        token = fixture.expect(200, 'POST', '/v1/auth/login', body=dict(email=email, password=password))['access_token']
        owner = fixture.expect(200, 'GET', '/v1/auth/me', token=token)['id']
        profile = fixture.expect(200, 'PUT', '/v1/users/me', token=token,
                                 body={'display_name': 'Synthetic Legacy Recovery ' + str(index)})
        users.append(dict(email=email, password=password, token=token, owner=owner, profile=profile))
    return users


def fingerprint(runtime, names, service):
    database, user = STORES[service]
    # Hash full row contents, not only counts; table names come from catalog,
    # quoted as identifiers. No caller SQL/target input is accepted.
    tables = json.loads(runtime.sql(names[service], database, user,
        "SELECT coalesce(json_agg(tablename ORDER BY tablename),'[]') FROM pg_tables WHERE schemaname='public';"))
    result = {}
    for table in tables:
        identifier = '"' + table.replace('"', '""') + '"'
        result[table] = runtime.sql(
                names[service], database, user,
                "SELECT count(*)::text || ':' || md5(coalesce(string_agg(row_to_json(t)::text,E'\\n' ORDER BY row_to_json(t)::text),'')) FROM public." + identifier + ' t;')
    require(bool(result), 'Empty store is not recovery evidence')
    return result


def financial_state(runtime, names, payment_id):
    pid = str(uuid.UUID(payment_id))
    sql = lambda text: runtime.sql(names['ledger-db'], 'bank', 'bank', text)
    require(sql("SELECT count(*) FROM accounts a WHERE balance_minor <> coalesce((SELECT sum(amount_minor) FROM postings p WHERE p.account_id=a.id),0);") == '0', 'Ledger projection differs')
    require(sql("SELECT count(*) FROM accounts WHERE currency<>'AED' OR (account_kind='CUSTOMER' AND balance_minor<0);") == '0', 'Invalid customer balance')
    require(sql("SELECT coalesce(sum(balance_minor::numeric),0) FROM accounts;") == '0', 'Ledger total differs')
    require(sql('SELECT count(*) FROM (SELECT payment_id FROM postings GROUP BY payment_id HAVING count(*)<>2 OR sum(amount_minor)<>0) x;') == '0', 'Unbalanced postings')
    require(sql(f"SELECT count(*) FROM transfers WHERE payment_id='{pid}';") == '1', 'Original payment journal count differs')
    require(sql(f"SELECT outcome FROM transfer_requests WHERE payment_id='{pid}';") == 'POSTED', 'Original ledger result differs')
    return fingerprint(runtime, names, 'ledger-db')


def immutable_checks(runtime, names, payment_id):
    pid = str(uuid.UUID(payment_id))
    # Catch exactly the real trigger messages; missing rows/permission failures
    # cannot be mistaken for successful immutability. The DO block fails if any
    # attempted mutation succeeds. Same DB role as these application containers.
    for statement, message in ((f"UPDATE transfers SET amount_minor=amount_minor+1 WHERE payment_id='{pid}'", 'Ledger transfers are immutable; post a compensating transfer'),
                               (f"DELETE FROM transfers WHERE payment_id='{pid}'", 'Ledger transfers are immutable; post a compensating transfer'),
                               (f"UPDATE transfer_requests SET outcome=outcome WHERE payment_id='{pid}'", 'Transfer request identity is immutable'),
                               (f"DELETE FROM transfer_requests WHERE payment_id='{pid}'", 'Transfer results are immutable')):
        runtime.sql(names['ledger-db'], 'bank', 'bank',
                    "DO $$ BEGIN BEGIN " + statement + "; RAISE EXCEPTION 'mutation unexpectedly succeeded'; "
                    "EXCEPTION WHEN raise_exception THEN IF SQLERRM <> '" + message + "' THEN RAISE; END IF; END; END $$;")


STANDBY_PROGRAM = r'''#!/bin/sh
set -eu
: "${PGDATA:?}" "${RECOVERY_REPLICATION_PASSWORD:?}"
mkdir -p "$PGDATA"
chown postgres:postgres "$PGDATA"
chmod 700 "$PGDATA"
if [ "$(id -u)" = 0 ]; then
    exec gosu postgres /bin/sh "$0"
fi
umask 077
printf 'ledger-db:5432:replication:recovery_replication:%s\n' "$RECOVERY_REPLICATION_PASSWORD" > /var/lib/postgresql/.pgpass
unset RECOVERY_REPLICATION_PASSWORD
export PGPASSFILE=/var/lib/postgresql/.pgpass
if [ ! -s "$PGDATA/PG_VERSION" ]; then
    test -z "$(find "$PGDATA" -mindepth 1 -maxdepth 1 -print -quit)"
    pg_basebackup -d 'host=ledger-db port=5432 user=recovery_replication application_name=recovery_standby passfile=/var/lib/postgresql/.pgpass' -D "$PGDATA" -R -X stream --checkpoint=fast --no-password
fi
test -f "$PGDATA/standby.signal"
exec postgres -D "$PGDATA" -c hot_standby=on
'''


def replica_recovery(runtime, network, images, names, directory, payment_id, expected):
    """Restart and fresh-volume rebuild on the restored primary; never promote."""
    primary = names['ledger-db']
    password = secrets.token_hex(32)
    runtime.sql(primary, 'bank', 'bank',
                "CREATE ROLE recovery_replication LOGIN REPLICATION PASSWORD '" + password + "';")
    # Official image defaults permit replication only on loopback. Authorize
    # this named role on this owned private fixture network explicitly.
    config = runtime.owned('network', network).get('IPAM', {}).get('Config', [])
    require(isinstance(config, list) and len(config) == 1 and isinstance(config[0], dict),
            'Single owned fixture subnet required')
    subnet = ipaddress.ip_network(config[0].get('Subnet', ''), strict=True)
    require(subnet.version == 4 and subnet.is_private and not subnet.is_loopback and not subnet.is_link_local,
            'Private IPv4 fixture subnet required')
    rule = 'host replication recovery_replication ' + str(subnet) + ' scram-sha-256'
    runtime.exec(primary, '/bin/sh', '-c', 'printf \'%s\\n\' "$1" >> "$PGDATA/pg_hba.conf"',
                 'recovery-hba', rule)
    require(runtime.sql(primary, 'bank', 'bank', 'SELECT pg_reload_conf();') == 't',
            'Fixture replication authentication reload failed')
    require(runtime.sql(primary, 'bank', 'bank',
            "SELECT count(*) FROM pg_hba_file_rules WHERE type='host' AND database=ARRAY['replication']::text[] "
            "AND user_name=ARRAY['recovery_replication']::text[] AND address='" + str(subnet.network_address) + "' "
            "AND netmask='" + str(subnet.netmask) + "' "
            "AND auth_method='scram-sha-256' AND error IS NULL;") == '1',
            'Authenticated physical replication configuration unavailable')
    boundary = runtime.sql(primary, 'bank', 'bank', 'SELECT pg_current_wal_flush_lsn();')
    require(re.fullmatch('[0-9A-F]+/[0-9A-F]+', boundary) is not None, 'Invalid replica replay boundary')
    script = directory / 'standby.sh'
    script.write_text(STANDBY_PROGRAM, encoding='utf-8', newline='\n')

    def verify(name):
        nonlocal boundary
        wait_database(runtime, name, 'bank', 'bank')
        # A checkpoint after startup creates a new WAL record: a clone containing
        # old rows alone cannot satisfy this streaming recovery assertion.
        runtime.sql(primary, 'bank', 'bank', 'CHECKPOINT;')
        boundary = runtime.sql(primary, 'bank', 'bank', 'SELECT pg_current_wal_flush_lsn();')
        require(re.fullmatch('[0-9A-F]+/[0-9A-F]+', boundary) is not None, 'Invalid replica replay boundary')
        until(lambda: runtime.sql(name, 'bank', 'bank',
              "SELECT pg_is_in_recovery() AND current_setting('transaction_read_only')='on' "
              "AND EXISTS(SELECT 1 FROM pg_stat_wal_receiver WHERE status='streaming') "
              "AND coalesce(pg_last_wal_replay_lsn()>='" + boundary + "'::pg_lsn,false);"),
              lambda value: value == 't', 90)
        require(financial_state(runtime, {'ledger-db': name}, payment_id) == expected,
                'Recovered standby ledger contents differ')

    replicas = []
    for generation in ('initial', 'rebuilt'):
        standby = runtime.create('target', 'ledger-standby-' + generation, images['postgres'], network,
                                 {'PGDATA': '/var/lib/postgresql/data',
                                  'RECOVERY_REPLICATION_PASSWORD': password},
                                 mounts=(('data', '/var/lib/postgresql/data'),),
                                 command=('/bin/sh', '/tmp/recovery-standby.sh'))
        runtime.copy_in(standby, script, '/tmp/recovery-standby.sh')
        runtime.action(standby, 'start')
        verify(standby)
        replicas.append(standby)
        if generation == 'initial':
            runtime.action(standby, 'stop')
            require(not runtime.owned('container', standby)['State']['Running'], 'Standby not stopped')
            runtime.action(standby, 'start')
            verify(standby)
            runtime.action(standby, 'stop')
    first = set(runtime.containers[replicas[0]][1])
    require(first.isdisjoint(runtime.containers[replicas[1]][1]), 'Replica rebuild reused original volume')
    return {'status': 'restart_and_fresh_volume_rebuild_verified', 'replay_boundary': boundary,
            'promotion': False, 'scope': 'restored synthetic ledger primary only'}


def apply(endpoint, manifest, dependency_manifest):
    head = revision()
    bank_images = load_images(manifest, head)
    dependencies = _staging.load_dependencies(dependency_manifest, head)
    runtime = OwnedRuntime(endpoint)
    runtime.preflight()
    images = {key: runtime.image(dependencies['images'][reference]['config_id']) for key, reference in PINNED.items()}
    for key in PINNED:
        metadata = runtime.inspect('image', images[key])
        require(metadata.get('Os') == 'linux' and metadata.get('Architecture') == 'amd64', 'Dependency platform differs')
    images.update({service: runtime.image(image, head) for service, image in bank_images.items()})
    credentials = {key: secrets.token_urlsafe(36) for key in ('db', 'internal', 'admin', 'client')}
    report = {'revision': head, 'scope': 'synthetic application recovery', 'stores': list(STORES), 'image_ids': images,
              'acceptance_tool_sha256': {name: hashlib.sha256((Path(__file__).parent / name).read_bytes()).hexdigest()
                  for name in ('backup-application-recovery-smoke.py', 'recovery_fixture_runtime.py', 'stage-application-recovery-ci.py')},
              'dependency_provenance': dependencies}
    def phase(name):
        print('RECOVERY_PHASE:' + name, file=sys.stderr, flush=True)
    try:
        with tempfile.TemporaryDirectory(prefix='armoney-application-recovery-') as temporary:
            parent = Path(temporary).resolve()
            require(not parent.is_relative_to(REPO) and not any((p / '.git').exists() for p in (parent, *parent.parents)),
                    'Temporary archives must be outside all checkouts')
            directory = private_directory(parent / 'artifacts')
            phase('source-databases')
            network, source = build_databases(runtime, 'source', images, credentials['db'])
            # Optional assistant is schema continuity only; no provider or assistant writer.
            for migration in sorted((REPO / 'ai-assistant-service/migrations').glob('V*.sql')):
                runtime.sql(source['assistant-db'], 'assistant', 'assistant_operator', migration.read_text(encoding='utf-8'))
            runtime.sql(source['assistant-db'], 'assistant', 'assistant_operator',
                        "INSERT INTO active_index(singleton,index_version) VALUES(true,'synthetic-recovery-21'); "
                        "INSERT INTO published_indexes(index_version) VALUES('synthetic-recovery-21');")
            apps, fixture = start_app(runtime, 'source', network, images, credentials, directory, True)
            phase('source-authenticated-acceptance')
            users, revoked = synthetic_users(runtime, apps, fixture, credentials['admin'])
            legacy_users = synthetic_legacy_users(fixture)
            for shard, store in (('primary', 'user-db'), ('s1', 'user-shard-s1'), ('s2', 'user-shard-s2')):
                count = '3' if shard == 's1' else '1'
                require(runtime.sql(source['user-db'], 'bank', 'bank',
                        "SELECT count(*) FROM profile_directory WHERE initialized AND shard_id='" + shard + "';") == count,
                        'Synthetic profile directory does not cover every shard')
                require(runtime.sql(source[store], 'bank', 'bank', 'SELECT count(*) FROM profiles;') == count,
                        'Synthetic profile data does not cover every shard')
            alice, bob, outsider = users
            account = str(uuid.UUID(alice['wallet']['ledger_account_id']))
            reserve, reserve_wallet, funding = (str(uuid.uuid4()) for _ in range(3))
            # Real existing CI funding pattern, authorized here only by owned
            # disposable daemon/container/volume guards; never a host CI bypass.
            runtime.sql(source['ledger-db'], 'bank', 'bank', f"BEGIN; INSERT INTO accounts(id,wallet_id,currency,account_kind) VALUES('{reserve}','{reserve_wallet}','AED','CLEARING'); INSERT INTO transfers(payment_id,debit_account_id,credit_account_id,currency,amount_minor) VALUES('{funding}','{reserve}','{account}','AED',1000); COMMIT;")
            command = dict(source_wallet_id=alice['wallet']['id'], recipient_id=bob['owner'],
                           recipient_phone=bob['phone'], currency='AED', amount_minor=250)
            key = 'recovery-' + uuid.uuid4().hex
            status, payment = fixture.request('POST', '/v1/payments', token=alice['token'], body=command, key=key)
            require(status in (200, 202), 'Original payment not acknowledged')
            pid = str(uuid.UUID(payment['id']))
            original = fixture.terminal(alice['token'], pid)
            require(original['status'] == 'COMPLETED', 'Original payment not ledger-confirmed')
            require([fixture.balance(u['token'], u['wallet']) for u in users] == [750, 250, 0], 'Original balances differ')
            report['final_acknowledged_payment_id'] = pid
            report['final_acknowledgement_utc'] = datetime.now(timezone.utc).isoformat()
            outage = time.monotonic()
            phase('fenced-capture')
            # Fence every writer before any dump. Runner is read-only transport.
            for alias, name in apps.items():
                if alias != 'runner':
                    runtime.action(name, 'stop')
            report['capture_boundary_utc'] = datetime.now(timezone.utc).isoformat()
            report['capture_wal_positions'] = {
                store: runtime.sql(source[store], database, user, 'SELECT pg_current_wal_lsn();')
                for store, (database, user) in STORES.items()}
            fingerprints = {store: fingerprint(runtime, source, store) for store in STORES}
            before = financial_state(runtime, source, pid)
            archives = {}
            for store, (database, user) in STORES.items():
                archive = directory / (store + '.dump')
                data = runtime.exec(source[store], 'pg_dump', '-Fc', '-U', user, '-d', database)
                archive.write_bytes(data)
                archives[store] = (archive, hashlib.sha256(data).hexdigest())
            for name in [*apps.values(), *source.values()]:
                if runtime.owned('container', name)['State']['Running']:
                    runtime.action(name, 'stop')
            require(all(not runtime.owned('container', n)['State']['Running'] for n in [*apps.values(), *source.values()]), 'Source not fenced')
            report['capture_seconds'] = round(time.monotonic() - outage, 3)
            # Validate every archive before allocating any restore target.
            require(all(p.is_file() and p.stat().st_size > 0 and hashlib.sha256(p.read_bytes()).hexdigest() == digest
                        for p, digest in archives.values()), 'Archive integrity failed')
            target_network, target = build_databases(runtime, 'target', images, credentials['db'])
            phase('fresh-target-restore')
            require(set(source.values()).isdisjoint(target.values()), 'Restore target reused')
            for store, (database, user) in STORES.items():
                if store == 'assistant-db':
                    runtime.sql(target[store], database, user, 'CREATE ROLE assistant_reader NOLOGIN;')
                runtime.exec(target[store], 'pg_restore', '--exit-on-error', '--no-owner', '-U', user, '-d', database,
                             input=archives[store][0].read_bytes())
                require(fingerprint(runtime, target, store) == fingerprints[store], 'Restored store contents differ: ' + store)
            recovered_apps, recovered = start_app(runtime, 'target', target_network, images, credentials, directory, False)
            phase('recovered-authenticated-acceptance')
            recovered.expect(401, 'GET', '/v1/auth/me', token=revoked)
            recovered.expect(401, 'GET', '/v1/auth/me')
            recovered.expect(401, 'POST', '/v1/auth/sso', body={'access_token': 'invalid'})
            for user in legacy_users:
                require(recovered.expect(200, 'GET', '/v1/auth/me', token=user['token'])['id'] == user['owner'],
                        'Recovered legacy session owner differs')
                session = recovered.expect(200, 'POST', '/v1/auth/login',
                                           body=dict(email=user['email'], password=user['password']))['access_token']
                require(recovered.expect(200, 'GET', '/v1/auth/me', token=session)['id'] == user['owner'],
                        'Recovered legacy password owner differs')
                require(recovered.expect(200, 'GET', '/v1/users/me', token=session) == user['profile'],
                        'Recovered legacy profile placement differs')
                recovered.expect(401, 'POST', '/v1/auth/login',
                                 body=dict(email=user['email'], password='synthetic-wrong-password'))
            for user in users:
                require(recovered.expect(200, 'GET', '/v1/auth/me', token=user['token'])['id'] == user['owner'], 'Recovered bank session owner differs')
                require(recovered.expect(200, 'GET', '/v1/users/me', token=user['token']) == user['profile'], 'Recovered profile mapping differs')
                wallets = recovered.expect(200, 'GET', '/v1/wallets', token=user['token'])['wallets']
                require(wallets == [user['wallet']], 'Recovered wallet mapping differs')
                refreshed = recovered.expect(200, 'POST', '/sso/realms/armoney/protocol/openid-connect/token', form=True,
                                             body=dict(client_id='armoney-android', grant_type='refresh_token', refresh_token=user['oidc']['refresh_token']))
                session = recovered.expect(200, 'POST', '/v1/auth/sso', body={'access_token': refreshed['access_token']})['access_token']
                require(recovered.expect(200, 'GET', '/v1/auth/me', token=session)['id'] == user['owner'], 'Recovered provider session mapping differs')
                login = oidc(runtime, recovered_apps['runner'], user['phone'], user['password'])
                new_session = recovered.expect(200, 'POST', '/v1/auth/sso', body={'access_token': login['access_token']})['access_token']
                require(recovered.expect(200, 'GET', '/v1/auth/me', token=new_session)['id'] == user['owner'], 'Recovered credentials/subject mapping differs')
            recovered.expect(404, 'GET', '/v1/wallets/' + alice['wallet']['id'] + '/balance', token=bob['token'])
            recovered.expect(404, 'GET', '/v1/payments/' + pid, token=outsider['token'])
            status, replay = recovered.request('POST', '/v1/payments', token=alice['token'], body=command, key=key)
            require(status in (200, 202) and replay['id'] == pid, 'Original idempotency mapping differs')
            require(recovered.terminal(alice['token'], pid) == original, 'Original durable payment result differs')
            recovered.expect(409, 'POST', '/v1/payments', token=alice['token'], body=dict(command, amount_minor=251), key=key)
            # Same key for a different requester must never disclose/replay Alice's payment.
            result = recovered.request('POST', '/v1/payments', token=outsider['token'], body=command, key=key)
            require(result[0] in (404, 409), 'Foreign requester accepted original source wallet')
            require([recovered.balance(u['token'], u['wallet']) for u in users] == [750, 250, 0], 'Replay moved money')
            require(financial_state(runtime, target, pid) == before, 'Replay changed immutable ledger contents')
            immutable_checks(runtime, target, pid)
            require(financial_state(runtime, target, pid) == before, 'Mutation checks changed ledger')
            phase('restored-ledger-replica-recovery')
            report['replica_recovery'] = replica_recovery(runtime, target_network, images, target, directory, pid, before)
            report.update(status='application_recovery_verified', missing_acknowledged_payments=0,
                          outage_to_acceptance_seconds=round(time.monotonic() - outage, 3),
                          limits='Quiesced synthetic logical recovery; assistant schema only; fresh fixture TLS roots per phase, not production TLS backup; no PITR, production RTO/RPO or HA claim')
    finally:
        phase('owned-cleanup')
        runtime.cleanup()
    print(json.dumps(report, indent=2))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--apply', action='store_true')
    parser.add_argument('--daemon')
    parser.add_argument('--bank-images', type=Path)
    parser.add_argument('--dependency-images', type=Path)
    args = parser.parse_args()
    if not args.apply:
        print('PLAN ONLY: real pinned Keycloak, nine synthetic stores, fenced fresh restore, HTTPS bank session/owner/payment replay; no Docker access')
        return
    require(args.daemon is not None and args.bank_images is not None and args.dependency_images is not None,
            'Apply requires explicit disposable daemon, bank images and dependency provenance')
    apply(args.daemon, args.bank_images, args.dependency_images)


if __name__ == '__main__':
    try:
        main()
    except (GuardError, OSError, ValueError, KeyError, TypeError):
        # No external command output, HTTP bodies, credentials or dumps in logs.
        raise SystemExit('Application recovery did not pass; sensitive diagnostics withheld') from None
