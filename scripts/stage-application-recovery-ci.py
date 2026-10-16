"""Offline plan by default. Stage clean source/images into an owned disposable DinD.

Apply belongs to the orchestrator's reserved heavy-test lease. No image pulls,
host socket mounts, ambient Docker context or persistent application targets.
"""
import argparse
import json
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import time
import uuid

from recovery_fixture_runtime import GuardError, child_environment, private_directory, require, validate_endpoint

ROOT = Path(__file__).resolve().parents[1]
ENDPOINT = 'unix:///run/recovery-docker/docker.sock'
DAEMON_LABEL = 'io.armoney.disposable-recovery=true'
PHASES = frozenset(('source', 'imagepreflight', 'create', 'copy', 'daemonready',
                    'import', 'build', 'application', 'cleanup', 'validatedreport'))
IMAGES = ('docker:29.8.1-dind', 'armoney-local-runner:2.338.0',
          'eclipse-temurin:21-jdk', 'eclipse-temurin:21-jre', 'postgres:17.6-alpine',
          'quay.io/keycloak/keycloak:26.7.4', 'caddy:2.11.4-alpine', 'python:3.13.7-alpine',
          'pgvector/pgvector:pg17@sha256:ac08538c6f8b9904c33c8224c5e5706dbe760aca29db1d096972b4052c22a75d')


class Stage:
    def __init__(self, endpoint, source, revision):
        self.endpoint = validate_endpoint(endpoint)
        self.source = Path(source).resolve(strict=True)
        require(self.source.is_dir() and (self.source / '.git').exists(), 'Explicit Git checkout required')
        require(re.fullmatch('[0-9a-f]{40}', revision) is not None, 'Exact source revision required')
        self.revision = revision
        self.project = 'armoney-recovery-stage-' + uuid.uuid4().hex
        self.env = dict(child_environment(), RECOVERY_PROJECT=self.project, RECOVERY_REVISION=revision)
        self.daemon_id = None
        self.inner_id = None
        self.phase = 'source'
        self.cleanup_failed = False
        self.cleanup_started = False
        self.names = {'daemon': self.project + '-daemon-1', 'runner': self.project + '-runner-1'}

    def run(self, argv, timeout=120, input=None):
        result = subprocess.run(argv, cwd=self.source, env=self.env, capture_output=True, input=input, timeout=timeout)
        require(result.returncode == 0, 'Staging command failed; output withheld')
        return result.stdout

    def raw(self, *args, **kwargs):
        return self.run(['docker', '--host', self.endpoint, *args], **kwargs)

    def docker(self, *args, **kwargs):
        info = json.loads(self.raw('info', '--format', '{{json .}}'))
        require(self.daemon_id and info.get('ID') == self.daemon_id, 'Outer daemon identity changed')
        return self.raw(*args, **kwargs)

    def source_check(self):
        git = ['git', '-c', 'safe.directory=' + str(self.source), '-C', str(self.source)]
        require(self.run([*git, 'rev-parse', '--verify', 'HEAD']).decode().strip() == self.revision,
                'Source revision differs')
        require(not self.run([*git, 'status', '--porcelain=v1', '--untracked-files=all']).strip(),
                'Clean exact source required')
        return git

    def owned(self, service):
        name = self.names[service]
        rows = json.loads(self.docker('container', 'inspect', name))
        require(isinstance(rows, list) and len(rows) == 1, 'Owned container unavailable')
        data = rows[0]
        labels = data.get('Config', {}).get('Labels') or {}
        require(data.get('Name') == '/' + name and labels.get('com.docker.compose.project') == self.project
                and labels.get('com.docker.compose.service') == service, 'Container ownership differs')
        expected = {'daemon': {'recovery-data': '/var/lib/docker', 'recovery-socket': '/run/recovery-docker'},
                    'runner': {'recovery-socket': '/run/recovery-docker', 'recovery-workspace': '/work'}}[service]
        expected_mounts = {self.project + '_' + volume: target for volume, target in expected.items()}
        binds = data['HostConfig'].get('Binds')
        # Compose can serialize named volumes as Binds. Accept only these exact
        # named-volume/target/rw tuples, then verify effective mounts and labels.
        require(binds is None or binds == [] or (isinstance(binds, list) and len(binds) == len(expected_mounts) and
                all(isinstance(value, str) for value in binds) and
                set(binds) == {volume + ':' + target + ':rw' for volume, target in expected_mounts.items()}),
                'Foreign bind or mount options rejected')
        require(not data['HostConfig'].get('PortBindings'), 'Host exposure rejected')
        require(not data['HostConfig'].get('Devices') and not data['HostConfig'].get('CapAdd') and
                data['HostConfig'].get('PidMode', '') == '' and
                bool(data['HostConfig'].get('Privileged')) == (service == 'daemon'), 'Unexpected host privileges')
        network = self.project + '_default'
        mode = data['HostConfig'].get('NetworkMode')
        if service == 'daemon':
            require(mode == network and set(data['NetworkSettings']['Networks']) == {network},
                    'Foreign daemon network rejected')
        else:
            daemon = self.owned('daemon')
            require(mode == 'container:' + daemon['Id'] and
                    set(data['NetworkSettings']['Networks']) <= {network}, 'Runner network namespace differs')
        network_data = json.loads(self.docker('network', 'inspect', network))[0]
        require(network_data.get('Name') == network and network_data.get('Driver') == 'bridge' and
                (network_data.get('Labels') or {}).get('com.docker.compose.project') == self.project,
                'Project network ownership differs')
        mounts = data.get('Mounts', [])
        require(isinstance(mounts, list) and len(mounts) == len(expected_mounts), 'Unexpected mount count')
        require(all(m.get('Type') == 'volume' and m.get('Name') in
                    expected_mounts and m.get('Destination') == expected_mounts[m['Name']] and m.get('RW') is True
                    for m in mounts), 'Foreign mount or target/options drift rejected')
        require({m.get('Name') for m in mounts} == set(expected_mounts),
                'Missing project mounts')
        for mount in data.get('Mounts', []):
            volume = json.loads(self.docker('volume', 'inspect', mount['Name']))[0]
            require(volume.get('Name') == mount['Name'] and volume.get('Driver') == 'local' and not volume.get('Options')
                    and (volume.get('Labels') or {}).get('com.docker.compose.project') == self.project,
                    'Container volume ownership differs')
        return data

    def inner(self, *args, **kwargs):
        self.owned('daemon')
        if self.inner_id:
            info = json.loads(self.docker('exec', self.names['daemon'], 'docker', '--host', ENDPOINT,
                                          'info', '--format', '{{json .}}'))
            require(info.get('ID') == self.inner_id and DAEMON_LABEL in (info.get('Labels') or []),
                    'Inner daemon identity changed')
        return self.docker('exec', '-i', self.names['daemon'], 'docker', '--host', ENDPOINT, *args, **kwargs)

    def image(self, reference):
        rows = json.loads(self.docker('image', 'inspect', reference))
        require(isinstance(rows, list) and len(rows) == 1 and
                re.fullmatch('sha256:[0-9a-f]{64}', rows[0].get('Id', '')) is not None, 'Local image unavailable')
        return rows[0]['Id']

    def cleanup(self):
        # Exact project names only. Missing resources are allowed after partial
        # creation; ownership/daemon uncertainty stops cleanup, never broad down.
        for service in ('runner', 'daemon'):
            name = self.names[service]
            found = self.docker('container', 'ls', '-a', '--filter', 'name=^/' + name + '$', '--format', '{{.Names}}').decode().split()
            if found:
                require(found == [name], 'Unexpected cleanup inventory')
                self.owned(service)
                self.docker('container', 'rm', '-f', name)
        for kind, names in (('volume', [self.project + '_' + n for n in
                                      ('recovery-data', 'recovery-socket', 'recovery-workspace')]),
                            ('network', [self.project + '_default'])):
            for name in names:
                available = self.docker(kind, 'ls', '--filter', 'label=com.docker.compose.project=' + self.project,
                                        '--format', '{{.Name}}').decode().split()
                if name not in available:
                    continue
                data = json.loads(self.docker(kind, 'inspect', name))[0]
                require(data.get('Name') == name and
                        (data.get('Labels') or {}).get('com.docker.compose.project') == self.project,
                        'Cleanup resource ownership differs')
                if kind == 'volume':
                    require(data.get('Driver') == 'local' and not data.get('Options'), 'Foreign cleanup volume')
                self.docker(kind, 'rm', name)

    def apply(self):
        git = self.source_check()
        self.phase = 'imagepreflight'
        info = json.loads(self.raw('info', '--format', '{{json .}}'))
        require(isinstance(info.get('ID'), str) and bool(info['ID']), 'Outer daemon identity unavailable')
        self.daemon_id = info['ID']
        images = {reference: self.image(reference) for reference in IMAGES}
        # UUID project plus a strict empty inventory prevents takeover of old resources.
        for kind in ('container', 'volume', 'network'):
            require(not self.docker(kind, 'ls', *(['-a'] if kind == 'container' else []), '--filter',
                                    'label=com.docker.compose.project=' + self.project, '--quiet').strip(),
                    'Recovery project already exists')
        with tempfile.TemporaryDirectory(prefix='armoney-stage-') as temporary:
            directory = private_directory(Path(temporary).resolve() / 'private')
            require(not directory.is_relative_to(self.source), 'Staging artifacts must be outside source')
            bundle = directory / 'source.bundle'
            self.run([*git, 'bundle', 'create', str(bundle), 'HEAD'])
            self.source_check()
            empty = directory / 'empty.env'
            empty.write_text('', encoding='utf-8')
            # Freeze outer service image IDs too, while retaining project-only volumes.
            config = json.loads((self.source / 'infra/local-ci/compose.recovery.yaml').read_text(encoding='utf-8'))
            config['services']['daemon']['image'] = images[IMAGES[0]]
            config['services']['runner']['image'] = images[IMAGES[1]]
            compose = directory / 'compose.json'
            compose.write_text(json.dumps(config), encoding='utf-8')
            args = ('compose', '--env-file', str(empty), '--project-directory', str(directory),
                    '--project-name', self.project, '--file', str(compose))
            try:
                self.phase = 'create'
                self.docker(*args, 'create', '--pull', 'never')
                self.phase = 'copy'
                self.owned('runner')
                self.docker('cp', str(bundle), self.names['runner'] + ':/work/recovery-source.bundle')
                self.owned('daemon')
                self.phase = 'daemonready'
                self.docker('start', self.names['daemon'])
                deadline = time.monotonic() + 150
                while True:
                    state = self.owned('daemon')['State']
                    if state.get('Health', {}).get('Status') == 'healthy':
                        break
                    require(state.get('Running') and time.monotonic() < deadline, 'Dedicated daemon readiness expired')
                    time.sleep(1)
                inner = json.loads(self.inner('info', '--format', '{{json .}}'))
                require(isinstance(inner.get('ID'), str) and inner['ID'] and
                        DAEMON_LABEL in (inner.get('Labels') or []) and inner.get('Containers') == 0,
                        'Fresh labelled inner daemon required')
                self.inner_id = inner['ID']
                self.phase = 'import'
                for reference in IMAGES[2:]:
                    require(self.image(reference) == images[reference], 'Dependency image changed before staging')
                    # Stream directly; no large image archive or host socket in runner.
                    self.owned('daemon')
                    self.inner('info', '--format', '{{json .}}')
                    with tempfile.TemporaryFile() as errors:
                        producer = subprocess.Popen(['docker', '--host', self.endpoint, 'image', 'save', reference],
                                                    cwd=self.source, env=self.env, stdout=subprocess.PIPE, stderr=errors)
                        try:
                            result = subprocess.run(['docker', '--host', self.endpoint, 'exec', '-i', self.names['daemon'],
                                                     'docker', '--host', ENDPOINT, 'image', 'load'],
                                                    stdin=producer.stdout, capture_output=True, env=self.env, timeout=600)
                            producer.stdout.close()
                            require(producer.wait(timeout=30) == 0 and result.returncode == 0, 'Image stream failed')
                        finally:
                            if producer.poll() is None:
                                producer.kill()
                                producer.wait()
                    loaded = json.loads(self.inner('image', 'inspect', reference))
                    require(loaded[0]['Id'] == images[reference], 'Imported immutable image identity differs')
                self.source_check()
                self.phase = 'build'
                self.owned('runner')
                self.docker('start', self.names['runner'])
                deadline = time.monotonic() + 10800
                while self.owned('runner')['State'].get('Running'):
                    require(time.monotonic() < deadline, 'Recovery acceptance deadline expired')
                    time.sleep(5)
                self.owned('runner')
                raw = self.docker('logs', '--tail', '1000', self.names['runner']).decode('utf-8')
                require(len(raw) <= 262144, 'Acceptance report too large')
                markers = re.findall(r'^RECOVERY_STAGE:(build|application)$', raw, re.MULTILINE)
                if markers:
                    self.phase = markers[-1]
                require(self.owned('runner')['State'].get('ExitCode') == 0, 'Recovery acceptance failed; private logs withheld')
                self.phase = 'validatedreport'
                report = None
                decoder = json.JSONDecoder()
                for offset in re.finditer(r'^\{', raw, re.MULTILINE):
                    try:
                        candidate, _ = decoder.raw_decode(raw[offset.start():])
                    except ValueError:
                        continue
                    if isinstance(candidate, dict) and candidate.get('status') == 'application_recovery_verified':
                        report = candidate
                require(report is not None and report.get('revision') == self.revision and
                        report.get('missing_acknowledged_payments') == 0, 'Exact revision acceptance report unavailable')
            finally:
                failed = sys.exc_info()[0] is not None
                failure_phase = self.phase
                self.phase = 'cleanup'
                self.cleanup_started = True
                try:
                    self.cleanup()
                except (GuardError, OSError, ValueError, KeyError, TypeError, subprocess.SubprocessError):
                    self.cleanup_failed = True
                    if not failed:
                        raise
                finally:
                    if failed:
                        self.phase = failure_phase
            print(json.dumps({'status': 'application_recovery_verified', 'revision': self.revision,
                              'dependency_image_ids': images, 'project': self.project,
                              'acceptance': report}))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--apply', action='store_true')
    parser.add_argument('--outer-daemon')
    parser.add_argument('--source', type=Path)
    parser.add_argument('--revision')
    args = parser.parse_args()
    if not args.apply:
        print('PLAN ONLY: clean exact source bundle and immutable local images into owned disposable DinD; no Docker access')
        return
    require(args.outer_daemon is not None and args.source is not None and args.revision is not None,
            'Apply requires explicit local outer endpoint, source and exact revision')
    stage = Stage(args.outer_daemon, args.source, args.revision)
    try:
        stage.apply()
    except (GuardError, OSError, ValueError, KeyError, TypeError, subprocess.SubprocessError):
        phase = stage.phase if stage.phase in PHASES else 'source'
        cleanup = 'failed' if stage.cleanup_failed else ('checked' if stage.cleanup_started else 'not_started')
        raise SystemExit('Recovery staging did not pass; phase=' + phase + '; cleanup=' + cleanup) from None


if __name__ == '__main__':
    try:
        main()
    except (GuardError, OSError, ValueError, KeyError, TypeError, subprocess.SubprocessError):
        raise SystemExit('Recovery staging did not pass; phase=source; private diagnostics withheld') from None
