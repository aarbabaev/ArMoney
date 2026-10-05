"""Synthetic profile acceptance on an explicitly isolated Compose project; no funding."""
import argparse
import json
import re
import subprocess
import time
import urllib.error
import urllib.request
import uuid

parser = argparse.ArgumentParser()
parser.add_argument('--project', required=True)
parser.add_argument('--base-url', default='http://127.0.0.1:18080')
args = parser.parse_args()
if not re.fullmatch(r'armoney-shards-test-[a-z0-9-]+', args.project):
    raise SystemExit('Use a dedicated armoney-shards-test-* project, never the persistent stack.')
if not re.fullmatch(r'http://(?:127\.0\.0\.1|localhost):[0-9]+', args.base_url):
    raise SystemExit('Acceptance targets must be local.')


def compose(*parts, input=None):
    command = ['docker', 'compose', '-p', args.project, '-f', 'compose.yaml',
               '-f', 'compose.users-sharding.yaml', '-f', 'compose.users-sharding-smoke.yaml', *parts]
    return subprocess.run(command, input=input, text=True, capture_output=True,
                          check=True, timeout=120).stdout.strip()


def sql(service, statement):
    return compose('exec', '-T', service, 'psql', '-U', 'bank', '-d', 'bank', '-At',
                   '-v', 'ON_ERROR_STOP=1', input=statement)


def call(method, path, token=None, body=None, headers=None):
    fields = {'Content-Type': 'application/json', **(headers or {})}
    if token:
        fields['Authorization'] = 'Bearer ' + token
    request = urllib.request.Request(args.base_url + path, method=method, headers=fields,
        data=None if body is None else json.dumps(body).encode())
    try:
        response = urllib.request.urlopen(request, timeout=20)
    except urllib.error.HTTPError as error:
        response = error
    with response:
        raw = response.read()
        return response.status, json.loads(raw) if raw else None


def expect(status, response):
    assert response[0] == status, f'Expected HTTP {status}, got {response[0]}'
    return response[1]


def ready():
    deadline = time.monotonic() + 180
    while time.monotonic() < deadline:
        try:
            if call('GET', '/health/ready')[0] == 200:
                # Gateway itself has no database: probe every internal service too.
                result = subprocess.run(['docker', 'run', '--rm', '--network', args.project + '_bank',
                    'curlimages/curl:8.16.0', '--fail', '--silent', '--max-time', '3',
                    'http://user-service:8080/health/ready'], capture_output=True, timeout=20)
                if result.returncode == 0:
                    return
        except (OSError, urllib.error.URLError, subprocess.TimeoutExpired):
            pass
        time.sleep(2)
    raise AssertionError('Bounded readiness wait expired')


def user(prefix, expected_shard, physical):
    creds = {'email': prefix + str(uuid.uuid4()) + '@example.test',
             'password': 'synthetic-sharding-smoke-password'}
    expect(202, call('POST', '/v1/auth/register', body=creds))
    token = expect(200, call('POST', '/v1/auth/login', body=creds))['access_token']
    owner = str(uuid.UUID(expect(200, call('GET', '/v1/auth/me', token)['id']))
    first = expect(200, call('PUT', '/v1/users/me', token, {'display_name': prefix + ' Synthetic'},
                             {'X-Identity-Email': 'bo-forged@example.test', 'X-Shard-Id': 's2'}))
    assert sql('user-db', f"select shard_id from profile_directory where identity_id = '{owner}';") == expected_shard
    assert sql(physical, f"select id from profiles where identity_id = '{owner}';") == first['id']
    updated = expect(200, call('PUT', '/v1/users/me', token, {'display_name': prefix + ' Updated'}))
    assert updated['id'] == first['id']
    assert updated['identity_id'] == owner
    return token, owner, first['id']


ready()
alice = user('al', 's1', 'user-shard-s1')
bob = user('bo', 's2', 'user-shard-s2')
fallback = user('zz', 'primary', 'user-db')
assert sql('user-db', f"select count(*) from profiles where identity_id in ('{alice[1]}','{bob[1]}');") == '0'
assert sql('user-shard-s1', f"select count(*) from profiles where identity_id = '{bob[1]}';") == '0'

# Operator verification remains central, including for physically sharded profiles.
phone = '+1555' + str(uuid.uuid4().int % 10**7).zfill(7)
expect(200, call('PUT', '/v1/users/me/phone', alice[0], {'phone_number': phone}))
compose('exec', '-T', 'user-service', 'java', '-cp', '/opt/service/lib/*',
        'com.arman.bank.userservice.VerifyPhoneMain', alice[1], phone,
        'smoke-operator', 'sharding-smoke', '--confirm-out-of-band')
recipient = expect(200, call('POST', '/v1/recipients/resolve', bob[0], {'phone_number': phone}))
assert recipient['identity_id'] == alice[1]
assert expect(200, call('GET', '/v1/users/me', bob[0]))['id'] == bob[2]

# Process restart preserves pins and global phone state.
compose('restart', 'user-service')
ready()
assert expect(200, call('GET', '/v1/users/me', alice[0]))['id'] == alice[2]
assert expect(200, call('GET', '/v1/users/me', alice[0]))['phone_verified']

# An unavailable pinned shard cannot fall back to primary or another shard.
compose('stop', 'user-shard-s1')
try:
    expect(503, call('GET', '/v1/users/me', alice[0]))
    expect(503, call('PUT', '/v1/users/me', alice[0], {'display_name': 'No fallback'}))
    assert sql('user-db', f"select shard_id from profile_directory where identity_id = '{alice[1]}';") == 's1'
    assert sql('user-db', f"select count(*) from profiles where identity_id = '{alice[1]}';") == '0'
    assert expect(200, call('GET', '/v1/users/me', bob[0]))['id'] == bob[2]
finally:
    compose('start', 'user-shard-s1')
ready()
assert expect(200, call('GET', '/v1/users/me', alice[0]))['id'] == alice[2]
print('Physical placement, spoof rejection, central phone lookup, restart and shard outage passed')
