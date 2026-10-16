"""Import-safe, explicitly owned disposable recovery transport. No Compose defaults."""
import json
import os
from pathlib import Path
import re
import subprocess
import time
import uuid

LABEL = 'io.armoney.application-recovery'
DAEMON_LABEL = 'io.armoney.disposable-recovery=true'


def child_environment():
    allowed = {'PATH', 'SYSTEMROOT', 'WINDIR', 'COMSPEC', 'PATHEXT', 'USERPROFILE', 'HOME',
               'LOCALAPPDATA', 'APPDATA', 'TEMP', 'TMP', 'LANG', 'PROGRAMFILES'}
    return {key: value for key, value in os.environ.items() if key.upper() in allowed}


class GuardError(RuntimeError):
    pass


def require(condition, message):
    if not condition:
        raise GuardError(message)


def validate_endpoint(endpoint):
    require(isinstance(endpoint, str) and bool(re.fullmatch(
        r'(unix:///[^\s]+|npipe:////\./pipe/[A-Za-z0-9_.-]+)', endpoint)),
        'Explicit local disposable daemon endpoint required')
    return endpoint


def validate_resource(kind, data, name, token, network=None, volumes=()):
    require(re.fullmatch(r'[0-9a-f]{32}', token) is not None, 'Invalid ownership token')
    require(name.startswith('armoney-recovery-' + token + '-'), 'Resource name is not owned')
    labels = data.get('Config', {}).get('Labels', {}) if kind == 'container' else data.get('Labels', {})
    require((labels or {}).get(LABEL) == token, 'Resource label is not owned')
    require(data.get('Name', '').lstrip('/') == name, 'Resource name differs')
    if kind == 'network':
        require(data.get('Internal') is True and data.get('Driver') == 'bridge', 'Network must be private bridge')
    if kind == 'volume':
        require(data.get('Driver') == 'local' and not data.get('Options'), 'Volume driver or host-path options rejected')
    if kind == 'container':
        host = data['HostConfig']
        require(not host.get('PortBindings') and not any((data['NetworkSettings'].get('Ports') or {}).values()),
                'Published ports rejected')
        require(host.get('NetworkMode') == network and
                set(data['NetworkSettings']['Networks']) == {network}, 'Foreign network rejected')
        require(not host.get('Privileged') and not host.get('Binds') and not host.get('Devices') and
                not host.get('CapAdd') and host.get('PidMode', '') == '' and
                host.get('IpcMode') in ('private', ''), 'Host access rejected')
        mounts = data.get('Mounts', [])
        require(all(m.get('Type') == 'volume' and m.get('Name') in volumes for m in mounts),
                'Unowned mount rejected')


class OwnedRuntime:
    def __init__(self, endpoint, token=None):
        self.endpoint = validate_endpoint(endpoint)
        self.token = token or uuid.uuid4().hex
        require(re.fullmatch('[0-9a-f]{32}', self.token) is not None, 'Invalid ownership token')
        self.prefix = 'armoney-recovery-' + self.token + '-'
        self.resources = []
        self.containers = {}
        self.env = child_environment()
        self.authorized = False
        self.daemon_id = None
        self.authorized_endpoint = None

    def docker(self, *args, input=None, timeout=120):
        require(self.authorized or args[:1] == ('info',) or args[:2] in (('volume', 'ls'), ('network', 'ls')), 'Daemon not validated')
        validate_endpoint(self.endpoint)
        if self.authorized:
            require(self.endpoint == self.authorized_endpoint and self.daemon_id,
                    'Authorized daemon endpoint changed')
        try:
            if self.authorized and args[:1] != ('info',):
                identity = subprocess.run(['docker', '--host', self.endpoint, 'info', '--format', '{{json .}}'],
                                          capture_output=True, env=self.env, timeout=30)
                require(identity.returncode == 0, 'Daemon identity unavailable')
                info = json.loads(identity.stdout)
                require(isinstance(info, dict) and info.get('ID') == self.daemon_id and
                        DAEMON_LABEL in (info.get('Labels') or []), 'Authorized daemon identity changed')
            result = subprocess.run(['docker', '--host', self.endpoint, *args], input=input,
                                    capture_output=True, env=self.env, timeout=timeout)
        except (OSError, subprocess.TimeoutExpired, ValueError, TypeError):
            raise GuardError('Docker unavailable or timed out; output withheld') from None
        require(result.returncode == 0, 'Docker operation failed; output withheld')
        return result.stdout

    def preflight(self):
        info = json.loads(self.docker('info', '--format', '{{json .}}'))
        require(isinstance(info.get('ID'), str) and bool(info['ID'].strip()), 'Disposable daemon identity unavailable')
        require(DAEMON_LABEL in (info.get('Labels') or []), 'Dedicated disposable daemon label missing')
        require(info.get('Containers') == 0, 'Disposable daemon must initially contain no containers')
        require(not self.docker('volume', 'ls', '--quiet').strip(), 'Disposable daemon must initially contain no volumes')
        networks = set(self.docker('network', 'ls', '--format', '{{.Name}}').decode().split())
        require(networks == {'bridge', 'host', 'none'}, 'Disposable daemon contains custom networks')
        self.daemon_id = info['ID']
        self.authorized_endpoint = self.endpoint
        self.authorized = True

    def inspect(self, kind, name):
        return json.loads(self.docker(kind, 'inspect', name))[0]

    def owned(self, kind, name):
        data = self.inspect(kind, name)
        network, volumes = self.containers.get(name, (None, ()))
        validate_resource(kind, data, name, self.token, network, volumes)
        if kind == 'container':
            validate_resource('network', self.inspect('network', network), network, self.token)
            for volume in volumes:
                validate_resource('volume', self.inspect('volume', volume), volume, self.token)
        return data

    def image(self, reference, revision=None):
        data = self.inspect('image', reference)
        require(re.fullmatch(r'sha256:[0-9a-f]{64}', data['Id']) is not None, 'Invalid local image ID')
        if revision:
            require((data['Config'].get('Labels') or {}).get('org.opencontainers.image.revision') == revision,
                    'Bank image revision label differs from source')
        return data['Id']  # No build, pull or mutable tag after inspection.

    def network(self, phase):
        name = self.prefix + phase + '-network'
        self.docker('network', 'create', '--internal', '--label', LABEL + '=' + self.token, name)
        self.resources.append(('network', name))
        self.owned('network', name)
        return name

    def create(self, phase, alias, image, network, environment=None, mounts=(), command=()):
        self.owned('network', network)
        require(re.fullmatch(r'sha256:[0-9a-f]{64}', image) is not None, 'Only inspected local image IDs accepted')
        require(re.fullmatch(r'[a-z0-9.-]+', phase) is not None and
                re.fullmatch(r'[a-z0-9.-]+', alias) is not None, 'Invalid synthetic resource suffix')
        require('CI' not in (environment or {}), 'Host CI bypass rejected')
        name = self.prefix + phase + '-' + alias
        volume_names = []
        args = ['create', '--name', name, '--label', LABEL + '=' + self.token,
                '--network', network, '--network-alias', alias, '--restart', 'no']
        for suffix, destination in mounts:
            volume = name + '-' + suffix
            self.docker('volume', 'create', '--label', LABEL + '=' + self.token, volume)
            self.resources.append(('volume', volume))
            self.owned('volume', volume)
            volume_names.append(volume)
            args += ['--mount', 'type=volume,source=' + volume + ',target=' + destination]
        for key, value in (environment or {}).items():
            require(key != 'CI', 'Host CI bypass rejected')
            args += ['-e', key + '=' + value]
        self.docker(*args, image, *command)
        self.containers[name] = (network, tuple(volume_names))
        self.resources.append(('container', name))
        self.owned('container', name)
        return name

    def action(self, name, action):
        self.owned('container', name)
        return self.docker(action, name)

    def exec(self, name, *args, input=None):
        self.owned('container', name)
        return self.docker('exec', '-i', name, *args, input=input)

    def copy_in(self, name, source, target):
        self.owned('container', name)
        self.docker('cp', str(source), name + ':' + target)

    def copy_out(self, name, source, target):
        self.owned('container', name)
        self.docker('cp', name + ':' + source, str(target))

    def sql(self, name, database, user, statement):
        return self.exec(name, 'psql', '-X', '-U', user, '-d', database, '-At',
                         '-v', 'ON_ERROR_STOP=1', input=statement.encode()).decode().strip()

    def cleanup_daemon(self):
        # Endpoint/daemon uncertainty is global, never a recoverable resource error.
        require(self.authorized and self.endpoint == self.authorized_endpoint and self.daemon_id,
                'Cleanup daemon authorization unavailable')
        validate_endpoint(self.endpoint)
        try:
            info = json.loads(self.docker('info', '--format', '{{json .}}'))
            require(isinstance(info, dict) and info.get('ID') == self.daemon_id and
                    DAEMON_LABEL in (info.get('Labels') or []), 'Cleanup daemon identity changed')
        except (GuardError, ValueError, TypeError):
            raise GuardError('Cleanup stopped: daemon integrity unavailable') from None

    def cleanup(self):
        # Only this creation inventory is eligible. No retries, discovery or fallback.
        self.cleanup_daemon()
        failures = []
        retained = {name for kind, name in self.resources if kind == 'container'}
        attempted = set()
        for kind in ('container', 'volume', 'network'):
            for resource_kind, name in reversed(self.resources):
                if resource_kind != kind or (kind, name) in attempted:
                    continue
                attempted.add((kind, name))
                self.cleanup_daemon()
                if kind != 'container':
                    # Unknown dependencies of a retained container protect everything.
                    protected = False
                    for container in retained:
                        dependency = self.containers.get(container)
                        if dependency is None:
                            protected = True
                            break
                        network, volumes = dependency
                        if (kind == 'network' and name == network) or (kind == 'volume' and name in volumes):
                            protected = True
                            break
                    if protected:
                        failures.append(kind + ':retained-container')
                        continue
                stage = 'ownership'
                try:
                    # This check remains immediately adjacent to every exact removal.
                    self.owned(kind, name)
                    stage = 'removal'
                    self.docker(kind, 'rm', *(['-f'] if kind == 'container' else []), name)
                except (GuardError, ValueError, KeyError, IndexError, TypeError):
                    failures.append(kind + ':' + stage)
                    # A transport failure may signal daemon loss; verify before continuing.
                    self.cleanup_daemon()
                    continue
                if kind == 'container':
                    retained.discard(name)
        if failures:
            raise GuardError('Cleanup incomplete: ' + str(len(failures)) +
                             ' resource failures (' + ', '.join(failures) + ')')


def until(probe, predicate, seconds=180):
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        value = probe()
        if predicate(value):
            require(time.monotonic() < deadline, 'Bounded readiness/acceptance wait expired')
            return value
        time.sleep(1)
    raise GuardError('Bounded readiness/acceptance wait expired')


def private_directory(path):
    """New synthetic-artifact directory; restrict only this owned new path."""
    path = Path(path)
    require(path.is_absolute() and path.parent.is_dir() and not path.exists() and not path.is_symlink(),
            'New private artifact directory required')
    path.mkdir(mode=0o700)
    if os.name == 'nt':
        script = ('$ErrorActionPreference="Stop"; $p=$env:RECOVERY_PRIVATE_PATH; '
                  '$a=New-Object System.Security.AccessControl.DirectorySecurity; '
                  '$a.SetAccessRuleProtection($true,$false); '
                  '$sid=[System.Security.Principal.WindowsIdentity]::GetCurrent().User; '
                  '$r=New-Object System.Security.AccessControl.FileSystemAccessRule($sid,"FullControl",'
                  '"ContainerInherit,ObjectInherit","None","Allow"); '
                  '$a.AddAccessRule($r); [System.IO.Directory]::SetAccessControl($p,$a)')
        try:
            subprocess.run(['powershell.exe', '-NoProfile', '-NonInteractive', '-Command', script],
                           env=dict(child_environment(), RECOVERY_PRIVATE_PATH=str(path)),
                           capture_output=True, check=True, timeout=30)
        except (OSError, subprocess.SubprocessError):
            raise GuardError('Private artifact permissions unavailable') from None
    return path


# Executed only inside a validated owned runner. Payloads/tokens travel on stdin.
HTTP_PROGRAM = r'''
import json, sys, ssl, urllib.request, urllib.error, urllib.parse
p=json.load(sys.stdin)
class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *args): return None
opener=urllib.request.build_opener(urllib.request.ProxyHandler({}),NoRedirect(), urllib.request.HTTPSHandler(context=ssl.create_default_context(cafile='/tmp/recovery-ca.crt')))
headers=p.get('headers', {})
body=p.get('body')
if body is not None:
    headers['Content-Type']='application/x-www-form-urlencoded' if p.get('form') else 'application/json'
    body=(urllib.parse.urlencode(body) if p.get('form') else json.dumps(body)).encode()
request=urllib.request.Request(p['url'], data=body, headers=headers, method=p['method'])
try:
    response=opener.open(request, timeout=15)
except urllib.error.HTTPError as e:
    response=e
with response:
    raw=response.read()
    print(json.dumps([response.status, json.loads(raw) if raw and 'application/json' in response.headers.get('Content-Type','') else None]))
'''


class BankFixture:
    def __init__(self, runtime, runner):
        self.runtime, self.runner = runtime, runner

    def request(self, method, path, body=None, token=None, key=None, form=False):
        require(isinstance(path, str) and path.startswith(('/v1/', '/sso/')) and
                '\\' not in path and not any(part == '..' for part in path.split('/')),
                'Fixture path rejected')
        headers = {}
        if token:
            headers['Authorization'] = 'Bearer ' + token
        if key:
            headers['Idempotency-Key'] = key
        payload = dict(method=method, url='https://recovery.test' + path, body=body,
                       headers=headers, form=form)
        return json.loads(self.runtime.exec(self.runner, 'python', '-c', HTTP_PROGRAM,
                                          input=json.dumps(payload).encode()))

    def expect(self, status, method, path, **kwargs):
        result = self.request(method, path, **kwargs)
        require(result[0] == status, 'Unexpected fixture HTTP status: ' + str(result[0]))
        return result[1]

    def balance(self, token, wallet):
        value = self.expect(200, 'GET', '/v1/wallets/' + str(uuid.UUID(wallet['id'])) + '/balance',
                            token=token)['balance_minor']
        require(type(value) is int and 0 <= value <= 2**63 - 1, 'Customer balance must be nonnegative integer fils')
        return value

    def ready(self, service):
        require(service in ('app-gateway', 'auth-service', 'user-service', 'wallet-service',
                            'payment-service', 'ledger-service'), 'Unknown fixture service')
        payload = dict(method='GET', url='http://' + service + ':8080/health/ready')
        return json.loads(self.runtime.exec(self.runner, 'python', '-c', HTTP_PROGRAM,
                                          input=json.dumps(payload).encode()))

    def terminal(self, token, payment_id):
        return until(lambda: self.expect(200, 'GET', '/v1/payments/' + str(uuid.UUID(payment_id)), token=token),
                     lambda p: p['status'] != 'PENDING', 90)
