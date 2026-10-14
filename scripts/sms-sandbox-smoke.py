"""Acceptance against an already-started, disposable SMS-only Compose project.

The caller starts and cleans up `-f compose.sms.yaml` with synthetic credentials.
This script only tests HTTP and restarts the exact validated container; it never
starts projects, removes volumes, or sends outbound SMS. UI assertions inspect
served assets and CSP, not browser execution. Tokens come exclusively from env.
"""
import argparse
import importlib.util
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid

sys.dont_write_bytecode = True
REPO = Path(__file__).resolve().parents[1]


class Failed(Exception):
    pass


def require(condition, message):
    if not condition:
        raise Failed(message)


def endpoint():
    if os.environ.get('DOCKER_HOST') == 'unix:///run/inner-docker/docker.sock':
        require(os.environ.get('ARMONEY_DISPOSABLE_CI') == 'true'
                and os.environ.get('GITHUB_WORKSPACE', '').startswith('/work/')
                and not (REPO / '.env').exists() and not (REPO / 'compose.override.yaml').exists()
                and not os.environ.get('DOCKER_CONTEXT'), 'Disposable CI guard rejected')
        return 'unix:///run/inner-docker/docker.sock'
    spec = importlib.util.spec_from_file_location('sms_fund_guard', REPO / 'scripts/fund-local-users.py')
    helper = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(helper)
    return helper.selected_endpoint()


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--project', required=True)
    parser.add_argument('--base-url', '--url', dest='url', required=True,
                        help='Exact http://127.0.0.1:<published port>')
    args = parser.parse_args()
    require(re.fullmatch(r'armoney-sms-test-[0-9a-f]{32}', args.project), 'Dedicated UUID test project required')
    parsed = urllib.parse.urlsplit(args.url)
    require(parsed.scheme == 'http' and parsed.hostname == '127.0.0.1'
            and parsed.port and 1 <= parsed.port <= 65535 and not parsed.username and not parsed.password
            and not parsed.path and not parsed.query and not parsed.fragment,
            'Explicit IPv4 loopback origin required')
    sender, reader = (os.environ.get(name, '') for name in ('SMS_SANDBOX_SEND_TOKEN', 'SMS_SANDBOX_READ_TOKEN'))
    require(re.fullmatch(r'[A-Za-z0-9_-]{32,128}', sender)
            and re.fullmatch(r'[A-Za-z0-9_-]{32,128}', reader) and sender != reader,
            'Distinct synthetic role tokens required')
    host = endpoint()
    environment = {k: v for k, v in os.environ.items() if not k.upper().startswith('DOCKER_')}

    def docker(*command):
        result = subprocess.run(['docker', '--host', host, *command], env=environment,
                                capture_output=True, timeout=90, check=False)
        require(result.returncode == 0, 'Docker operation failed; diagnostics withheld')
        return result.stdout.decode('utf-8').strip()

    def inventory(expected_id=None):
        ids = docker('ps', '-aq', '--no-trunc', '--filter',
                     'label=com.docker.compose.project=' + args.project).split()
        require(len(ids) == 1 and re.fullmatch('[0-9a-f]{64}', ids[0]), 'Expected one disposable SMS container')
        require(expected_id is None or ids[0] == expected_id, 'Disposable container changed')
        item = json.loads(docker('inspect', ids[0]))[0]
        labels = item['Config'].get('Labels') or {}
        require(labels.get('com.docker.compose.project') == args.project
                and labels.get('com.docker.compose.service') == 'sms-sandbox'
                and labels.get('com.docker.compose.oneoff', '').lower() == 'false'
                and item['Name'].lstrip('/') == args.project + '-sms-sandbox-1', 'Container identity mismatch')
        files = labels.get('com.docker.compose.project.config_files', '').split(',')
        require(len(files) == 1 and Path(files[0]).resolve() == REPO / 'compose.sms.yaml',
                'Only this checkout compose.sms.yaml may define the fixture')
        bindings = item['HostConfig'].get('PortBindings')
        require(bindings == {'8080/tcp': [{'HostIp': '127.0.0.1', 'HostPort': str(parsed.port)}]},
                'Published port does not match loopback test origin')
        mounts = item['Mounts']
        volume = args.project + '_sms-sandbox-data'
        require(len(mounts) == 1 and mounts[0].get('Type') == 'volume'
                and mounts[0].get('Name') == volume and mounts[0].get('Destination') == '/data',
                'Disposable data volume required')
        volume_labels = json.loads(docker('volume', 'inspect', volume))[0].get('Labels') or {}
        require(volume_labels.get('com.docker.compose.project') == args.project
                and volume_labels.get('com.docker.compose.volume') == 'sms-sandbox-data', 'Volume ownership mismatch')
        network = args.project + '_sms-sandbox'
        require(set(item['NetworkSettings']['Networks']) == {network}, 'Unexpected container network')
        net = json.loads(docker('network', 'inspect', network))[0]
        require(net.get('Internal') is False and net.get('Driver') == 'bridge'
                and (net.get('Labels') or {}).get('com.docker.compose.project') == args.project
                and set(net.get('Containers', {})) == {ids[0]}, 'Dedicated bridge network required')
        require(item['State']['Running'] and not item['HostConfig'].get('Privileged')
                and item['HostConfig'].get('NetworkMode') != 'host', 'Unsafe or stopped fixture container')
        return ids[0]

    container = inventory()
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect())

    def request(method, path, status=200, token=None, body=None, headers=None):
        fields = dict(headers or {})
        if token:
            fields['Authorization'] = 'Bearer ' + token
        if body is not None:
            fields['Content-Type'] = 'application/json'
        req = urllib.request.Request(args.url + path,
            data=None if body is None else json.dumps(body).encode(), headers=fields, method=method)
        try:
            response = opener.open(req, timeout=10)
        except urllib.error.HTTPError as error:
            response = error
        with response:
            raw = response.read(1048577)
            require(response.status == status and len(raw) <= 1048576, 'HTTP status or size contract failed')
            require(response.headers.get('Cache-Control') == 'no-store', 'Response must disable caching')
            return raw, response.headers

    def get_json(path='/api/messages'):
        return json.loads(request('GET', path, token=reader)[0])

    def ready():
        for _ in range(45):
            try:
                require(json.loads(request('GET', '/health/ready')[0]) == {'status': 'UP'}, 'Not ready')
                return
            except (Failed, OSError):
                time.sleep(1)
        raise Failed('SMS fixture readiness timed out')

    def restart():
        inventory(container)
        docker('restart', '--time', '15', container)
        inventory(container)
        ready()

    ready()
    require(get_json() == [], 'Fresh inbox required; refusing to clear pre-existing messages')
    key = 'smoke-' + uuid.uuid4().hex
    text = 'Synthetic code 654321 <img src=x onerror=alert(1)><script>alert(2)</script>'
    body = {'destination': '+971528000100', 'text': text, 'purpose': 'smoke_login'}
    key_header = {'Idempotency-Key': key}
    for method, token, value, headers in (
        ('GET', None, None, None), ('POST', None, body, key_header),
        ('GET', sender, None, None), ('POST', reader, body, key_header),
        ('DELETE', sender, None, {'X-Confirm-Clear': 'true'}),
        ('DELETE', None, None, {'X-Confirm-Clear': 'true'})):
        raw, _ = request(method, '/api/messages', 401, token, value, headers)
        require(b'654321' not in raw and body['destination'].encode() not in raw, 'Unauthorized content disclosure')
    first = json.loads(request('POST', '/api/messages', 201, sender, body, key_header)[0])
    require(set(first) == {'id', 'createdAt', 'replay'} and first['replay'] is False
            and str(uuid.UUID(first['id'])) == first['id'] and type(first['createdAt']) is int,
            'Submission metadata contract failed')

    def replay():
        actual = json.loads(request('POST', '/api/messages', 200, sender, body, key_header)[0])
        require(actual == dict(first, replay=True), 'Replay identity or timestamp changed')
        request('POST', '/api/messages', 409, sender, dict(body, text='different synthetic code'), key_header)

    expected = dict(body, id=first['id'], createdAt=first['createdAt'])
    replay()
    require(get_json() == [expected], 'Inbox content or duplicate count mismatch')
    require(get_json('/api/messages?q=SYNTHETIC&purpose=smoke_login') == [expected]
            and get_json('/api/messages?purpose=other') == [] and get_json('/api/messages?q=absent') == [],
            'Search or purpose filtering failed')
    shell, headers = request('GET', '/')
    require(b'654321' not in shell and body['destination'].encode() not in shell,
            'Public shell contains message data')
    csp = headers.get('Content-Security-Policy', '')
    require("script-src 'self'" in csp and "frame-ancestors 'none'" in csp
            and 'unsafe-inline' not in csp, 'UI CSP contract failed')
    script = request('GET', '/inbox.js')[0].decode()
    require('line.textContent = value' in script and 'innerHTML' not in script
            and 'localStorage' not in script and 'sessionStorage' not in script,
            'Served UI inert rendering/storage contract failed')
    restart()
    require(get_json() == [expected], 'Message failed container restart durability')
    replay()
    request('DELETE', '/api/messages', 400, reader)
    require(get_json() == [expected], 'Unconfirmed clear changed inbox')
    request('DELETE', '/api/messages', 204, reader, headers={'X-Confirm-Clear': 'true'})
    require(get_json() == [], 'Clear did not remove message contents')
    request('POST', '/api/messages', 410, sender, body, key_header)
    request('POST', '/api/messages', 409, sender, dict(body, text='different synthetic code'), key_header)
    restart()
    require(get_json() == [], 'Clear was not durable')
    request('POST', '/api/messages', 410, sender, body, key_header)
    request('POST', '/api/messages', 409, sender, dict(body, text='different synthetic code'), key_header)
    require(get_json() == [], 'Tombstone retry recreated message')
    print('SMS sandbox acceptance passed: isolated topology, readiness, role isolation, submission, '
          'replay/conflict, search, served UI/CSP contract, restart durability, clear and durable tombstones. '
          'Browser execution and external delivery were not exercised. Caller owns project cleanup.')


if __name__ == '__main__':
    try:
        main()
    except Failed as error:
        print('SMS acceptance blocked: ' + str(error), file=sys.stderr)
        sys.exit(1)
    except Exception:
        print('SMS acceptance blocked; sensitive diagnostics withheld.', file=sys.stderr)
        sys.exit(1)
