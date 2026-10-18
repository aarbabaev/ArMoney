"""Actual shipped opt-in Caddy TLS fixture; root's fresh disposable DinD only.

No host ports/binds/sockets, no credentials/provider/account data. Upstreams alone
are projected to loopback. HTTPS site, TLS and original-URI matchers are retained.
Run only with a serialized root lease; this script does not grant that lease.
"""
import argparse
import hashlib
import http.client
import http.server
import json
import os
import pathlib
import re
import socket
import ssl
import subprocess
import sys
import tempfile
import threading
import time
import uuid

ROOT = pathlib.Path(__file__).resolve().parents[1]
CADDY = 'sha256:6aeddd44c3078b0f9a35206472a11420648a79c184603ef95957d0a20044cb2b'
PYTHON = 'sha256:9ba6d8cbebf0fb6546ae71f2a1c14f6ffd2fdab83af7fa5669734ef30ad48844'
KEY = 'synthetic-edge-fixture-key-' + 'a' * 43
ORIGIN = 'https://fixture.local:8443'
GET = ('/migration/legacy', '/migration/legacy/account-oidc/callback', '/migration/managed/callback', '/migration/legacy/verify/status')
POST = ('/migration/legacy/password', '/migration/legacy/email', '/migration/legacy/account-oidc/start', '/migration/managed/start', '/migration/legacy/verify/stage', '/migration/legacy/otp/issue', '/migration/legacy/otp/consume', '/migration/legacy/verify/decision', '/migration/legacy/verify/publication', '/migration/legacy/verify/final-command', '/migration/legacy/verify/final')
LABEL = 'io.armoney.migration-edge-fixture'
INNER_STAGE = 'initialization'
REQUEST_PHASE = 'idle'
INJECT = {path: 'umask 077; cat > ' + path for path in
          ('/tmp/Caddyfile', '/tmp/test-migration-edge.py', '/tmp/fixture-ca.crt')}
WAITING = 'n=0; until test -f /tmp/waiting; do n=$((n+1)); test "$n" -le 50 || exit 1; sleep 0.1; done'
READY = 'umask 077; touch /tmp/ready'


def waiter(command):
    return 'umask 077; touch /tmp/waiting; n=0; until test -f /tmp/ready; do n=$((n+1)); test "$n" -le 90 || exit 1; sleep 1; done; exec ' + command


def require(value, check):
    if not value:
        raise RuntimeError(check)


def failure_code(error):
    if type(error) is RuntimeError and len(error.args) == 1 and type(error.args[0]) is str:
        if re.fullmatch(r'[a-z0-9-]{1,80}', error.args[0]):
            return error.args[0]
    # Fixed categories only: exception text can contain request/response material.
    for kind, category in ((ssl.SSLCertVerificationError, 'certificate'),
                           (ssl.SSLError, 'ssl'), (TimeoutError, 'timeout'),
                           (http.client.IncompleteRead, 'incomplete-read'),
                           (http.client.HTTPException, 'http'),
                           (ConnectionError, 'connection'), (OSError, 'os'),
                           (ValueError, 'value'), (TypeError, 'type'), (KeyError, 'key')):
        if isinstance(error, kind):
            return 'inner-' + category + '-' + INNER_STAGE + '-' + REQUEST_PHASE
    return 'tls-fixture-unknown'


def child_failure(output):
    try:
        receipt = json.loads(output)
        if (type(receipt) is dict and set(receipt) == {'status', 'variant', 'check'}
                and receipt['status'] == 'not_verified' and receipt['variant'] in ('web', 'plain')
                and type(receipt['check']) is str and re.fullmatch(r'[a-z0-9-]{1,80}', receipt['check'])):
            return receipt['check']
    except (ValueError, TypeError):
        pass
    return 'bounded-docker-exit'


def docker_phase(args):
    if args[:2] == ('image', 'inspect'):
        return 'image-inspect'
    return args[0] if args and args[0] in ('info', 'create', 'cp', 'start', 'exec', 'inspect', 'rm', 'logs', 'wait') else 'unknown'


def docker_error(phase, stderr):
    text = stderr.decode('utf-8', errors='replace').lower()
    for fragments, category in (
        (('no such image', 'unable to find image'), 'no-such-image'),
        (('read-only file system', 'read-only filesystem'), 'read-only-fs'),
        (('is not running', 'container not running'), 'container-not-running'),
        (('permission denied', 'operation not permitted'), 'permission-denied'),
        (('adapting config', 'adapting configuration', 'caddyfile input'), 'caddy-adapt'),
        (('validating config', 'validation failed'), 'caddy-validate'),
    ):
        if any(fragment in text for fragment in fragments):
            return phase + '-' + category
    return phase + '-exit'


def projection(source):
    configured_site = 'https://{$ARMONEY_HOST}:443 {\n    tls internal\n'
    denial_site = 'https://:443 {\n    tls internal\n    respond 404\n}\n'
    require(source.count(configured_site) == 1 and source.count(denial_site) == 1
            and source.count('    tls internal') == 2
            and source.count('https://{$ARMONEY_HOST}:443 {') == 1
            and source.count('https://:443 {') == 1, 'tls-source')
    for before, after in [('sso-service:8080', '127.0.0.1:18081'), ('app-gateway:8080', '127.0.0.1:18082'), ('web:8080', '127.0.0.1:18083')]:
        expected = 2 if before == 'app-gateway:8080' else (1 if before != 'web:8080' or 'web:8080' in source else 0)
        require(source.count(before) == expected, 'upstream-source-count')
        source = source.replace(before, after)
    return source


def inner(variant, ca):
    global INNER_STAGE
    records, lock = [], threading.Lock()
    mode = {'value': 'normal'}

    class Peer(http.server.BaseHTTPRequestHandler):
        protocol_version = 'HTTP/1.1'

        def log_message(self, *_):
            pass

        def do_GET(self):
            self.answer()

        def do_POST(self):
            self.answer()

        def answer(self):
            role = {18081: 'oidc', 18082: 'api', 18083: 'web'}[self.server.server_port]
            length = int(self.headers.get('Content-Length', '0'))
            require(0 <= length <= 8192, 'synthetic-body-bound')
            if length:
                self.rfile.read(length)
            with lock:
                records.append({'role': role, 'method': self.command, 'path': self.path, 'headers': list(self.headers.raw_items())})
            selected = mode['value']
            if selected == 'header-stall':
                time.sleep(8)
            body = ('synthetic-' + role).encode()
            try:
                if selected in ('body-stall', 'body-drip'):
                    body = b'1234567890123'
                self.send_response(200)
                self.send_header('Content-Type', 'text/plain')
                self.send_header('Content-Length', str(len(body)))
                self.end_headers()
                if selected == 'body-stall':
                    self.wfile.write(body[:1]); self.wfile.flush(); time.sleep(8)
                    self.wfile.write(body[1:])
                elif selected == 'body-drip':
                    for byte in body:
                        self.wfile.write(bytes([byte])); self.wfile.flush(); time.sleep(0.5)
                else:
                    self.wfile.write(body)
            except (OSError, ssl.SSLError):
                pass

    servers = []
    for port in (18081, 18082, 18083):
        server = http.server.ThreadingHTTPServer(('127.0.0.1', port), Peer)
        server.daemon_threads = True
        threading.Thread(target=server.serve_forever, daemon=True).start()
        servers.append(server)
    context = ssl.create_default_context(cafile=ca)
    require(context.verify_mode == ssl.CERT_REQUIRED and context.check_hostname, 'tls-verification')

    def request(path, method='GET', headers=(), host='fixture.local:8443', sni='fixture.local', timeout=8):
        global REQUEST_PHASE
        REQUEST_PHASE = 'connect'
        raw = socket.create_connection(('127.0.0.1', 443), timeout=timeout)
        connection = None
        try:
            REQUEST_PHASE = 'tls'
            connection = context.wrap_socket(raw, server_hostname=sni)
            connection.settimeout(timeout)
            data = f'{method} {path} HTTP/1.1\r\nHost: {host}\r\nConnection: close\r\n'.encode()
            for key, value in headers:
                data += f'{key}: {value}\r\n'.encode()
            data += b'\r\n'
            REQUEST_PHASE = 'send'
            connection.sendall(data)
            REQUEST_PHASE = 'headers'
            response = http.client.HTTPResponse(connection); response.begin()
            REQUEST_PHASE = 'body-status-' + str(response.status)
            body = response.read(65537)
            require(len(body) <= 65536, 'fixture-response-bound')
            return response.status, dict(response.getheaders()), body
        finally:
            if connection is not None:
                connection.close()
            else:
                raw.close()

    cases = 0

    def no_forward(path, method='GET', host='fixture.local:8443'):
        global INNER_STAGE
        nonlocal cases
        INNER_STAGE = 'denial-case-' + str(cases + 1)
        with lock:
            before = len(records)
        status, headers, _ = request(path, method, host=host)
        # Only the numbered synthetic case and status leave the inner fixture.
        # Never serialize request headers, response bodies or redirect values.
        require(status == 404 and 'Location' not in headers,
                'edge-local-denial-case-' + str(cases + 1) + '-status-' + str(status)
                + ('-redirect' if 'Location' in headers else ''))
        with lock:
            require(len(records) == before, 'denial-forwarded')
        cases += 1

    try:
        INNER_STAGE = 'allowed-routes'
        for method, paths in [('GET', GET), ('POST', POST)]:
            for path in paths:
                query = '?state=a%2Bb&code=escaped%2Fvalue' if path.endswith('/callback') else ''
                headers = [('Origin', ORIGIN), ('Cookie', 'synthetic=one; synthetic=two')]
                if method == 'POST':
                    headers += [('Content-Type', 'application/x-www-form-urlencoded'), ('Content-Length', '0')]
                status, _, _ = request(path + query, method, headers)
                require(status == 200, 'allowed-route')
                with lock:
                    observed = records[-1]
                require(observed['role'] == 'api' and observed['path'] == path + query and observed['method'] == method, 'raw-forwarding')
                cases += 1
        INNER_STAGE = 'header-forwarding'
        forged = [('x-ArMoney-Migration-Edge-Key', 'forged-one'), ('X-ARMONEY-MIGRATION-EDGE-KEY', 'forged-two'),
                  ('X-ArMoney-Migration-Origin', 'https://forged.invalid'), ('X-ArMoney-Migration-Auth-Key', 'forged'),
                  ('X-ArMoney-Migration-Unknown', 'forged'), ('Forwarded', 'proto=https'), ('X-Forwarded-For', 'forged'),
                  ('X-Forwarded-Proto', 'forged'), ('X-Forwarded-Host', 'forged'), ('X-Identity-Email', 'forged'),
                  ('X-Identity-Unknown', 'forged'), ('X-Service-Key', 'forged'), ('Authorization', 'Bearer forged'),
                  ('X-User-Id', 'forged'), ('X-Authenticated-User', 'forged'), ('Origin', 'https://foreign.invalid'),
                  ('Origin', ORIGIN), ('Cookie', 'synthetic=one'), ('Cookie', 'synthetic=two')]
        require(request('/migration/legacy', headers=forged)[0] == 200, 'header-forwarding')
        with lock:
            observed = records[-1]
        headers = observed['headers']
        values = lambda name: [v for k, v in headers if k.lower() == name]
        require(values('x-armoney-migration-edge-key') == [KEY] and values('x-armoney-migration-origin') == [ORIGIN], 'singleton-trust')
        forbidden = lambda k: k == 'forwarded' or k.startswith(('x-forwarded-', 'x-identity-')) or k in ('x-service-key', 'authorization', 'x-user-id', 'x-authenticated-user') or (k.startswith('x-armoney-migration-') and k not in ('x-armoney-migration-edge-key', 'x-armoney-migration-origin'))
        require(not any(forbidden(k.lower()) for k, _ in headers), 'assertion-stripping')
        require(values('origin') == ['https://foreign.invalid', ORIGIN], 'browser-origin-preservation')
        require('; '.join(values('cookie')) == 'synthetic=one; synthetic=two', 'cookie-preservation')
        cases += 1
        for path in POST:
            no_forward(path, 'GET')
        for path in GET:
            no_forward(path, 'PUT')
        for path in ('/migration', '/migration/', '/migration/unknown', '/migration/legacy/', '/migration/legacy/extra',
                     '/m%69gration/legacy', '/migration%2flegacy', '/migration/leg%61cy', '/migration//legacy',
                     '/migration/./legacy', '/migration/other/../legacy', '/migration/legacy%2fextra',
                     '/migration/legacy;extra', '/migration/legacy?unexpected=1', '/migration/legacy/verify/status?x=1'):
            no_forward(path)
        no_forward('/migration/legacy', host='other.invalid:8443')
        # A catch-all authority denial must cover every upstream, including the
        # browser fallback; it must not turn the configured-host site into a wildcard.
        for path in ('/sso/realms/armoney/protocol/openid-connect/auth', '/v1/auth/me',
                     '/health/ready', '/home'):
            no_forward(path, host='other.invalid:8443')
        with lock:
            before = len(records)
        INNER_STAGE = 'conflicting-sni'
        try:
            result = request('/migration/legacy', sni='other.invalid')
            require(result[0] == 404 and 'Location' not in result[1], 'conflicting-sni')
        except (ssl.SSLError, ConnectionError, OSError):
            pass  # TLS rejection is valid and still must not reach any upstream.
        with lock:
            require(len(records) == before, 'sni-forwarded')
        cases += 1
        INNER_STAGE = 'plaintext'
        with socket.create_connection(('127.0.0.1', 443), timeout=3) as plain:
            plain.settimeout(3)
            plain.sendall(b'GET /migration/legacy HTTP/1.1\r\nHost: fixture.local:8443\r\nForwarded: proto=https\r\nConnection: close\r\n\r\n')
            try:
                data = plain.recv(4096)
            except ConnectionError:
                data = b''
            require(b'Location:' not in data and b' 301 ' not in data and b' 302 ' not in data and b' 308 ' not in data, 'plaintext-redirect')
        with lock:
            require(len(records) == before, 'plaintext-forwarded')
        cases += 1
        INNER_STAGE = 'existing-routes'
        for path, role in [('/sso/realms/armoney/protocol/openid-connect/auth', 'oidc'), ('/v1/auth/me', 'api'), ('/health/ready', 'api')]:
            require(request(path)[2] == ('synthetic-' + role).encode(), 'existing-route')
            cases += 1
        INNER_STAGE = 'existing-fallback'
        status, headers, body = request('/home')
        if variant == 'web':
            require(status == 200 and body == b'synthetic-web' and headers.get('Cache-Control') == 'no-store' and headers.get('Referrer-Policy') == 'no-referrer' and "default-src 'self'" in headers.get('Content-Security-Policy', ''), 'existing-web-security')
        else:
            require(status == 404, 'existing-fallback')
        cases += 1
        phase = {}
        for selected in ('header-stall', 'body-stall'):
            INNER_STAGE = selected
            mode['value'] = selected
            with lock:
                before = len(records)
            started = time.monotonic()
            try:
                status, _, body = request('/migration/legacy', timeout=8)
                require(status >= 500 or body != b'1234567890123', 'stalled-phase-accepted')
            except (http.client.IncompleteRead, ConnectionError, ssl.SSLError):
                pass
            except TimeoutError:
                # Fixed synthetic counts distinguish one stalled phase from an
                # upstream retry; never serialize captured requests or headers.
                with lock:
                    attempts = len(records) - before
                raise RuntimeError('phase-timeout-' + selected + '-' + REQUEST_PHASE
                                   + '-attempts-' + str(attempts)) from None
            elapsed = time.monotonic() - started
            with lock:
                attempts = len(records) - before
            require(attempts == 1, 'upstream-phase-retried')
            require(elapsed < 7.5, 'upstream-phase-unbounded')
            phase[selected] = {'bounded': True, 'attempts': attempts, 'elapsed_seconds': round(elapsed, 3)}
        INNER_STAGE = 'body-drip'
        mode['value'] = 'body-drip'
        started = time.monotonic()
        status, _, body = request('/migration/legacy', timeout=9)
        elapsed = time.monotonic() - started
        require(status == 200 and body == b'1234567890123' and elapsed > 5, 'inactivity-not-absolute')
        return {'status': 'passed', 'variant': variant, 'cases': cases, 'upstream_phase_bounds': phase,
                'continuous_drip_exceeds_five_seconds': True, 'absolute_edge_deadline_proven': False,
                'gateway_body_origin_callback_semantics_proven': False}
    finally:
        for server in servers:
            server.shutdown(); server.server_close()


class Driver:
    def __init__(self, host, daemon, evidence):
        self.host, self.daemon = host, daemon
        self.evidence = evidence
        self.diagnostic_sequence = 0
        self.caddies = []
        self.nonce = uuid.uuid4().hex
        self.owned = []
        self.until = time.monotonic() + 240

    def call(self, *args, cleanup=False, input_bytes=None):
        phase = docker_phase(args)
        if input_bytes is not None:
            require(type(input_bytes) is bytes and len(input_bytes) <= 131072, 'stdin-bound')
            require(len(args) == 6 and args[:2] == ('exec', '-i') and args[2] in self.owned
                    and args[3:5] == ('/bin/sh', '-c') and args[5] in INJECT.values(), 'fixed-stdin-target')
            self.inspect(args[2])
        remaining = 20 if cleanup else min(100, self.until - time.monotonic())
        require(remaining > 0, 'original-fixture-deadline')
        process = subprocess.Popen(['docker', '--host', self.host, *args], stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                                   stdin=subprocess.PIPE if input_bytes is not None else subprocess.DEVNULL)
        streams, exceeded = [bytearray(), bytearray()], threading.Event()
        input_failed = threading.Event()
        def drain(stream, target):
            while True:
                chunk = stream.read(4096)
                if not chunk:
                    return
                if len(target) + len(chunk) > 1024 * 1024:
                    exceeded.set(); process.kill(); return
                target.extend(chunk)
        threads = [threading.Thread(target=drain, args=(stream, target), daemon=True) for stream, target in zip((process.stdout, process.stderr), streams)]
        if input_bytes is not None:
            def write_input():
                try:
                    with process.stdin:
                        process.stdin.write(input_bytes)
                        process.stdin.flush()
                except (OSError, ValueError):
                    input_failed.set()
            threads.append(threading.Thread(target=write_input, daemon=True))
        for thread in threads:
            thread.start()
        try:
            code = process.wait(timeout=remaining)
        except subprocess.TimeoutExpired:
            process.kill(); process.wait(); raise RuntimeError(phase + '-timeout') from None
        finally:
            for thread in threads:
                thread.join(2)
        require(not exceeded.is_set() and not any(t.is_alive() for t in threads), 'bounded-docker-output')
        if code != 0:
            self.private_diagnostic(phase, bytes(streams[1]))
            if len(args) == 3 and args[:2] == ('start', '--attach'):
                child = child_failure(streams[0].decode('utf-8', errors='replace'))
                if child != 'bounded-docker-exit':
                    raise RuntimeError(child)
            raise RuntimeError(docker_error(phase, streams[1]))
        require(not input_failed.is_set(), 'bounded-stdin-write')
        if phase == 'logs':
            self.private_diagnostic(phase, bytes(streams[0]) + bytes(streams[1]))
        return streams[0].decode('utf-8').strip()

    def private_diagnostic(self, phase, data):
        # Only fixture-owned Docker/Caddy output, synthetic configuration and no app credentials.
        require(len(data) <= 2 * 1024 * 1024, 'diagnostic-bound')
        self.diagnostic_sequence += 1
        target = self.evidence / ('docker-' + str(self.diagnostic_sequence) + '-' + phase + '.private.log')
        flags = os.O_WRONLY | os.O_CREAT | os.O_EXCL | getattr(os, 'O_NOFOLLOW', 0)
        with os.fdopen(os.open(target, flags, 0o600), 'wb') as stream:
            stream.write(data)

    def inject(self, container, path, data):
        require(path in INJECT, 'fixed-injection-path')
        self.call('exec', '-i', container, '/bin/sh', '-c', INJECT[path], input_bytes=data)

    def signal(self, container):
        self.inspect(container)
        self.call('exec', container, '/bin/sh', '-c', READY)

    def capture_startup(self):
        for container in self.caddies:
            try:
                self.inspect(container, cleanup=True)
                self.call('logs', '--tail', '80', container, cleanup=True)
            except Exception:
                pass  # Preserve original fixed failure; never substitute raw log text.

    def inspect(self, container, cleanup=False):
        value = json.loads(self.call('inspect', container, cleanup=cleanup))[0]
        require(value['Id'] == container and value['Config']['Labels'].get(LABEL) == self.nonce, 'owned-container-identity')
        require(not value['HostConfig']['PortBindings'] and not any(m['Type'] in ('bind', 'volume') for m in value['Mounts']), 'fixture-no-host-resources')
        return value

    def create(self, image, network, *args, command=()):
        name = 'armoney-edge-tls-' + uuid.uuid4().hex
        container = self.call('create', '--name', name, '--label', LABEL + '=' + self.nonce,
            '--network', network, '--read-only', '--cap-drop', 'ALL', '--security-opt', 'no-new-privileges',
            '--tmpfs', '/tmp:rw,nosuid,nodev,size=16m', *args, image, *command)
        require(re.fullmatch('[0-9a-f]{64}', container), 'created-id')
        self.owned.append(container)
        if image == CADDY:
            self.caddies.append(container)
        info = self.inspect(container)
        require(info['HostConfig']['NetworkMode'] == network and info['HostConfig']['ReadonlyRootfs'], 'fixture-network-mode')
        require(not info['HostConfig']['Privileged'] and 'ALL' in info['HostConfig']['CapDrop']
                and any(value.startswith('no-new-privileges') for value in info['HostConfig']['SecurityOpt']), 'fixture-security')
        return container

    def cleanup(self):
        failed = False
        for container in reversed(self.owned):
            try:
                self.inspect(container, cleanup=True)
                self.call('rm', '--force', container, cleanup=True)
            except Exception:
                failed = True
        require(not failed, 'owned-cleanup-unverified')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--daemon-id')
    parser.add_argument('--evidence', type=pathlib.Path)
    parser.add_argument('--inner', choices=('web', 'plain'), help=argparse.SUPPRESS)
    parser.add_argument('--ca', help=argparse.SUPPRESS)
    args = parser.parse_args()
    if args.inner:
        require(os.environ.get('ARMONEY_EDGE_INNER') == 'true' and args.ca == '/tmp/fixture-ca.crt', 'owned-inner-entry')
        try:
            receipt = inner(args.inner, args.ca)
        except Exception as failure:
            print(json.dumps({'status': 'not_verified', 'variant': args.inner, 'check': failure_code(failure)}))
            return 1
        print(json.dumps(receipt)); return 0
    require(os.environ.get('ARMONEY_DISPOSABLE_CI') in ('1', 'true'), 'root-disposable-guard')
    host = os.environ.get('DOCKER_HOST', '')
    require(host and not host.startswith('npipe:') and host != 'unix:///var/run/docker.sock', 'explicit-inner-daemon')
    require(args.daemon_id and args.evidence and re.fullmatch('[A-Za-z0-9:_-]{8,128}', args.daemon_id), 'root-daemon-and-evidence')
    args.evidence.mkdir(parents=True, exist_ok=False)
    driver = Driver(host, args.daemon_id, args.evidence)
    require(json.loads(driver.call('info', '--format', '{{json .ID}}')) == args.daemon_id, 'daemon-identity')
    require('image: caddy:2.11.4-alpine' in (ROOT / 'compose.sso.yaml').read_text(), 'shipped-caddy-version')
    for image in (CADDY, PYTHON):
        image_info = json.loads(driver.call('image', 'inspect', image))[0]  # Local only. Never pull/build.
        covered = {'/tmp', '/data', '/config'} if image == CADDY else {'/tmp'}
        require(set(image_info['Config'].get('Volumes') or {}).issubset(covered), 'no-implicit-image-volumes')
    receipts = []
    try:
        with tempfile.TemporaryDirectory(prefix='armoney-edge-tls-source-') as temporary:
            temp = pathlib.Path(temporary)
            for variant, filename in [('plain', 'Caddyfile.migration'), ('web', 'Caddyfile.web-migration')]:
                source = (ROOT / 'infra/edge' / filename).read_text()
                config = temp / 'Caddyfile'
                config.write_text(projection(source))
                caddy = driver.create(CADDY, 'none', '--user', '1000:1000', '--cap-add', 'NET_BIND_SERVICE',
                    '--tmpfs', '/data:rw,nosuid,nodev,uid=1000,gid=1000,size=16m', '--tmpfs', '/config:rw,nosuid,nodev,uid=1000,gid=1000,size=16m',
                    '--env', 'ARMONEY_HOST=fixture.local', '--env', 'MIGRATION_PUBLIC_ORIGIN=' + ORIGIN,
                    '--env', 'MIGRATION_EDGE_TO_GATEWAY_KEY=' + KEY, '--entrypoint', '/bin/sh',
                    command=('-c', waiter('caddy run --config /tmp/Caddyfile --adapter caddyfile')))
                driver.call('start', caddy)
                driver.call('exec', caddy, '/bin/sh', '-c', WAITING)
                driver.inject(caddy, '/tmp/Caddyfile', config.read_bytes())
                driver.signal(caddy)
                require(re.match(r'^v2\.11\.4(?:\s|$)', driver.call('exec', caddy, 'caddy', 'version')), 'actual-caddy-version')
                ca = args.evidence / (variant + '-fixture-root.crt')
                for attempt in range(40):
                    try:
                        certificate = driver.call('exec', caddy, 'cat', '/data/caddy/pki/authorities/local/root.crt')
                        require(len(certificate) < 16384 and 'BEGIN CERTIFICATE' in certificate, 'public-ca-copy')
                        ca.write_text(certificate + '\n')
                        break
                    except RuntimeError:
                        require(attempt < 39, 'fixture-ca-unavailable'); time.sleep(0.1)
                require(ca.is_file() and ca.stat().st_size < 16384 and b'BEGIN CERTIFICATE' in ca.read_bytes(), 'public-ca-copy')
                ca.chmod(0o644)
                python = driver.create(PYTHON, 'container:' + caddy, '--entrypoint', '/bin/sh', '--env', 'ARMONEY_EDGE_INNER=true',
                    command=('-c', waiter('python3 /tmp/test-migration-edge.py --inner ' + variant + ' --ca /tmp/fixture-ca.crt')))
                driver.call('start', python)
                driver.call('exec', python, '/bin/sh', '-c', WAITING)
                driver.inject(python, '/tmp/test-migration-edge.py', pathlib.Path(__file__).resolve().read_bytes())
                driver.inject(python, '/tmp/fixture-ca.crt', ca.read_bytes())
                driver.signal(python)
                exit_code = driver.call('wait', python)
                output = driver.call('logs', python)
                if exit_code != '0':
                    raise RuntimeError(child_failure(output))
                receipt = json.loads(output)
                if receipt.get('status') == 'not_verified':
                    raise RuntimeError(child_failure(output))
                require(receipt.get('status') == 'passed' and receipt.get('variant') == variant, 'inner-receipt')
                receipt['source_sha256'] = hashlib.sha256(source.encode()).hexdigest()
                receipt['fixture_ca_sha256'] = hashlib.sha256(ca.read_bytes()).hexdigest()
                receipts.append(receipt)
    except Exception:
        driver.capture_startup()
        raise
    finally:
        driver.cleanup()
    (args.evidence / 'receipt.json').write_text(json.dumps({'status': 'passed', 'variants': receipts, 'cleanup_verified': True}, indent=2))
    print('Actual Caddy TLS fixture passed both opt-in variants; no Gateway/Auth/business acceptance claimed')
    return 0


if __name__ == '__main__':
    try:
        raise SystemExit(main())
    except Exception as failure:
        print('Migration edge fixture not_verified; check=' + failure_code(failure) + '; owned cleanup attempted', file=sys.stderr)
        raise SystemExit(1)
