"""Offline plan by default. Stage clean source/images into an owned disposable DinD.

Apply belongs to the orchestrator's reserved heavy-test lease. No image pulls,
host socket mounts, ambient Docker context or persistent application targets.
"""
import argparse
import hashlib
import io
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tarfile
import tempfile
import threading
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


def strict_json(raw):
    def pairs(items):
        result = {}
        for key, value in items:
            require(key not in result, 'Duplicate JSON key rejected')
            result[key] = value
        return result
    def constant(value):
        raise GuardError('Non-finite JSON value rejected')
    try:
        return json.loads(raw, object_pairs_hook=pairs, parse_constant=constant)
    except (ValueError, TypeError, RecursionError):
        raise GuardError('Invalid bounded JSON rejected') from None


class ArchiveTee:
    def __init__(self, source, destination, max_bytes, deadline=None):
        self.source, self.destination = source, destination
        self.max_bytes, self.deadline, self.bytes = max_bytes, deadline, 0

    def read(self, size=65536):
        require(self.deadline is None or time.monotonic() < self.deadline, 'Image stream deadline expired')
        data = self.source.read(min(size, 65536) if size >= 0 else 65536)
        self.bytes += len(data)
        require(self.bytes <= self.max_bytes, 'Image stream size limit exceeded')
        require(self.deadline is None or time.monotonic() < self.deadline, 'Image stream deadline expired')
        if data:
            self.destination.write(data)
        return data


def observe_archive(source, destination, expected, max_bytes=8 * 1024**3, deadline=None):
    """Observe a Docker save tar while forwarding unchanged bytes; never extract."""
    tee = ArchiveTee(source, destination, max_bytes, deadline)
    metadata, members, files, metadata_bytes = {}, set(), set(), 0
    try:
        def exact(size):
            result = bytearray()
            while len(result) < size:
                block = tee.read(size - len(result))
                require(bool(block), 'Truncated image archive')
                result.extend(block)
            return bytes(result)

        while True:
            header = exact(512)
            if header == bytes(512):
                require(exact(512) == bytes(512), 'Invalid image archive terminator')
                break
            # Parse only a fixed-size header. Reject extended/sparse headers so
            # a hostile PAX length cannot make tarfile allocate unbounded memory.
            member = tarfile.TarInfo.frombuf(header, 'utf-8', 'strict')
            name = member.name
            require(not name.startswith('/') and '\\' not in name and '..' not in name.split('/') and
                    name not in members and len(members) < 4096, 'Malformed image archive member')
            require(member.type in (tarfile.REGTYPE, tarfile.AREGTYPE, tarfile.DIRTYPE) and
                    0 <= member.size <= max_bytes, 'Image archive special member rejected')
            members.add(name)
            if member.isfile():
                files.add(name)
            wanted = member.isfile() and member.size <= 1048576 and (name == 'manifest.json' or
                     name.endswith('.json') or re.fullmatch('blobs/sha256/[0-9a-f]{64}', name))
            raw = bytearray()
            remaining = member.size
            while remaining:
                block = exact(min(65536, remaining))
                remaining -= len(block)
                if wanted:
                    raw.extend(block)
            if member.size % 512:
                exact(512 - member.size % 512)
            if wanted and (name == 'manifest.json' or raw[:1] in (b'{', b'[')):
                metadata_bytes += len(raw)
                require(len(raw) <= 1048576 and metadata_bytes <= 8 * 1048576, 'Image metadata limit exceeded')
                metadata[name] = bytes(raw)
        while True:
            tail = tee.read(65536)
            if not tail:
                break
            require(not tail.strip(b'\0'), 'Unexpected image archive trailing content')
        manifest = strict_json(metadata['manifest.json'])
        require(isinstance(manifest, list) and len(manifest) == 1 and isinstance(manifest[0], dict),
                'One selected platform image required')
        entry = manifest[0]
        config_name, layers = entry['Config'], entry['Layers']
        require(isinstance(config_name, str) and config_name in metadata and isinstance(layers, list) and
                all(isinstance(layer, str) and layer in files for layer in layers) and
                len(layers) == len(set(layers)), 'Image config or layers unavailable')
        raw = metadata[config_name]
        config = strict_json(raw)
        require(isinstance(config, dict) and isinstance(config.get('config'), dict) and
                config.get('os') == 'linux' and config.get('architecture') == 'amd64',
                'Image archive platform differs')
        rootfs = config.get('rootfs')
        require(isinstance(rootfs, dict) and rootfs.get('type') == 'layers' and
                isinstance(rootfs.get('diff_ids'), list) and len(rootfs['diff_ids']) == len(layers) and
                all(isinstance(value, str) and re.fullmatch('sha256:[0-9a-f]{64}', value) for value in rootfs['diff_ids']),
                'Image root filesystem metadata invalid')
        require(expected.get('Os') == config['os'] and expected.get('Architecture') == config['architecture'] and
                expected.get('Config') == config.get('config') and
                expected.get('RootFS', {}).get('Type') == rootfs['type'] and
                expected.get('RootFS', {}).get('Layers') == rootfs['diff_ids'], 'Exported source platform/config/layers differ')
        return {'config_id': 'sha256:' + hashlib.sha256(raw).hexdigest(), 'os': config['os'],
                'architecture': config['architecture'], 'config': config['config'], 'rootfs': rootfs,
                'stream_bytes': tee.bytes}
    except (tarfile.TarError, ValueError, KeyError, TypeError, AttributeError):
        raise GuardError('Image archive verification failed; details withheld') from None


def verify_loaded_image(rows, provenance):
    require(isinstance(rows, list) and len(rows) == 1 and isinstance(rows[0], dict), 'Imported image unavailable')
    row = rows[0]
    require(row.get('Id') == provenance['config_id'] and row.get('Os') == provenance['os'] and
            row.get('Architecture') == provenance['architecture'] and row.get('Config') == provenance['config'] and
            isinstance(row.get('RootFS'), dict) and
            row.get('RootFS', {}).get('Type') == provenance['rootfs']['type'] and
            row.get('RootFS', {}).get('Layers') == provenance['rootfs']['diff_ids'],
            'Imported platform/config digest/layers differ')
    return row['Id']


def load_dependencies(path, revision):
    path = Path(path)
    require(path.is_absolute() and path.is_file() and not path.is_symlink() and
            0 < path.stat().st_size <= 65536, 'Private dependency manifest required')
    require(os.name != 'posix' or path.stat().st_mode & 0o077 == 0, 'Dependency manifest must be private')
    data = strict_json(path.read_text(encoding='utf-8'))
    require(isinstance(data, dict) and set(data) == {'revision', 'platform', 'images'} and
            data['revision'] == revision and data['platform'] == 'linux/amd64' and
            isinstance(data['images'], dict) and set(data['images']) == set(IMAGES[2:]),
            'Dependency manifest revision/platform/inventory differs')
    for item in data['images'].values():
        require(isinstance(item, dict) and set(item) == {'outer_id', 'config_id'} and
                all(isinstance(value, str) and re.fullmatch('sha256:[0-9a-f]{64}', value)
                    for value in item.values()), 'Dependency provenance identity invalid')
    return data


def dependency_archive(payload):
    require(isinstance(payload, bytes) and 0 < len(payload) <= 65536, 'Bounded dependency payload required')
    output = io.BytesIO()
    with tarfile.open(fileobj=output, mode='w', format=tarfile.USTAR_FORMAT) as archive:
        entry = tarfile.TarInfo('recovery-dependencies.json')
        entry.mode, entry.uid, entry.gid, entry.size = 0o600, 0, 0, len(payload)
        archive.addfile(entry, io.BytesIO(payload))
    return output.getvalue()


def verify_dependency_copy(archive_bytes, payload):
    require(isinstance(archive_bytes, bytes) and len(archive_bytes) <= 131072, 'Dependency copy archive exceeds bound')
    try:
        require(len(archive_bytes) >= 1536 and len(archive_bytes) % 512 == 0, 'Invalid dependency archive length')
        header = archive_bytes[:512]
        require(header[257:263] == b'ustar\0' and header[263:265] == b'00', 'Only plain USTAR dependency copy accepted')
        entry = tarfile.TarInfo.frombuf(header, 'utf-8', 'strict')
        require(entry.name == 'recovery-dependencies.json' and entry.type == tarfile.REGTYPE and not entry.linkname
                and entry.mode == 0o600 and entry.uid == 0 and entry.gid == 0 and entry.size == len(payload),
                'Dependency copy path/type/ownership/mode differs')
        end = 512 + entry.size
        padded_end = end + (-entry.size % 512)
        require(archive_bytes[512:end] == payload and not archive_bytes[end:padded_end].strip(b'\0'),
                'Dependency copy contents/padding differ')
        tail = archive_bytes[padded_end:]
        require(len(tail) >= 1024 and not tail.strip(b'\0'), 'Dependency copy extra members or trailer rejected')
    except (tarfile.TarError, OSError, ValueError, TypeError, AttributeError):
        raise GuardError('Dependency copy verification failed; details withheld') from None


def read_bounded_process(argv, environment, cwd, limit=131072, timeout=30):
    """Read a finite private stream; kill/reap the producer on overflow or stall."""
    require(type(limit) is int and 0 < limit <= 131072 and type(timeout) in (int, float) and 0 < timeout <= 30,
            'Bounded copy reader limits required')
    process = None
    expired = threading.Event()
    deadline = time.monotonic() + timeout
    def stop():
        expired.set()
        try:
            if process is not None and process.poll() is None:
                process.kill()
        except OSError:
            pass
    watchdog = threading.Timer(timeout, stop)
    watchdog.daemon = True
    watchdog.start()
    try:
        process = subprocess.Popen(argv, cwd=cwd, env=environment, stdin=subprocess.DEVNULL,
                                   stdout=subprocess.PIPE, stderr=subprocess.DEVNULL)
        result = bytearray()
        while True:
            block = process.stdout.read(min(65536, limit + 1 - len(result)))
            require(not expired.is_set() and time.monotonic() < deadline, 'Dependency copy stream expired')
            require(len(result) + len(block) <= limit, 'Dependency copy stream exceeds bound')
            if not block:
                break
            result.extend(block)
        require(process.wait(timeout=5) == 0 and not expired.is_set() and time.monotonic() < deadline,
                'Dependency copy command failed or expired')
        return bytes(result)
    finally:
        failed = sys.exc_info()[0] is not None
        watchdog.cancel()
        cleanup_failed = False
        if process is not None:
            try:
                if process.poll() is None:
                    process.kill()
            except OSError:
                cleanup_failed = True
            try:
                process.wait(timeout=5)
            except (OSError, subprocess.SubprocessError):
                cleanup_failed = True
            finally:
                try:
                    process.stdout.close()
                except OSError:
                    cleanup_failed = True
        if cleanup_failed and not failed:
            raise GuardError('Dependency copy process cleanup incomplete')


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

    def copy_dependencies(self, path):
        manifest = load_dependencies(path, self.revision)
        payload = Path(path).read_bytes()
        require(strict_json(payload) == manifest, 'Dependency manifest changed before copy')
        runner = self.owned('runner')
        require(runner.get('State', {}).get('Running') is False and
                runner.get('Config', {}).get('User', '') in ('', '0', '0:0', 'root', 'root:root'),
                'Stopped root runner required for dependency manifest')
        self.docker('cp', '-a', '-', self.names['runner'] + ':/work', input=dependency_archive(payload), timeout=30)
        runner = self.owned('runner')
        require(runner.get('State', {}).get('Running') is False, 'Dependency runner started before verification')
        copied = self.read_dependency_copy()
        verify_dependency_copy(copied, payload)

    def read_dependency_copy(self):
        runner = self.owned('runner')
        require(runner.get('State', {}).get('Running') is False, 'Stopped owned runner required for bounded copy')
        return read_bounded_process(['docker', '--host', self.endpoint, 'cp',
                                     self.names['runner'] + ':/work/recovery-dependencies.json', '-'],
                                    self.env, self.source)

    def transfer_image(self, reference, outer_id, timeout=600):
        require(type(timeout) in (int, float) and 0 < timeout <= 600, 'Bounded image transfer timeout required')
        require(reference in IMAGES[2:] and self.image(reference) == outer_id, 'Dependency source identity changed')
        rows = json.loads(self.docker('image', 'inspect', '--platform', 'linux/amd64', reference))
        require(isinstance(rows, list) and len(rows) == 1 and isinstance(rows[0], dict), 'Source platform unavailable')
        expected = rows[0]
        require(expected.get('Os') == 'linux' and expected.get('Architecture') == 'amd64', 'Source platform differs')
        self.owned('daemon')
        self.inner('info', '--format', '{{json .}}')
        deadline = time.monotonic() + timeout
        producer = consumer = None
        expired = threading.Event()

        def stop():
            expired.set()
            for process in (producer, consumer):
                try:
                    if process is not None and process.poll() is None:
                        process.kill()
                except OSError:
                    pass

        watchdog = threading.Timer(timeout, stop)
        watchdog.daemon = True
        watchdog.start()
        try:
            producer = subprocess.Popen(['docker', '--host', self.endpoint, 'image', 'save',
                                         '--platform', 'linux/amd64', reference], cwd=self.source, env=self.env,
                                        stdout=subprocess.PIPE, stderr=subprocess.DEVNULL)
            self.owned('daemon')
            self.inner('info', '--format', '{{json .}}')
            consumer = subprocess.Popen(['docker', '--host', self.endpoint, 'exec', '-i', self.names['daemon'],
                                         'docker', '--host', ENDPOINT, 'image', 'load', '--platform', 'linux/amd64'],
                                        cwd=self.source, env=self.env, stdin=subprocess.PIPE,
                                        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            provenance = observe_archive(producer.stdout, consumer.stdin, expected, deadline=deadline)
            consumer.stdin.close()
            producer.stdout.close()
            require(producer.wait(timeout=30) == 0 and consumer.wait(timeout=30) == 0 and
                    not expired.is_set() and time.monotonic() < deadline, 'Image stream failed or expired')
        finally:
            failed = sys.exc_info()[0] is not None
            watchdog.cancel()
            process_cleanup_failed = False
            # Kill both endpoints before waiting for either, so a broken pipe
            # cannot strand the peer when parsing or a command fails.
            for process in (producer, consumer):
                if process is not None:
                    try:
                        if process.poll() is None:
                            process.kill()
                    except OSError:
                        process_cleanup_failed = True
            for process in (producer, consumer):
                if process is not None:
                    try:
                        process.wait(timeout=30)
                    except (OSError, subprocess.SubprocessError):
                        process_cleanup_failed = True
                    for stream in (process.stdin, process.stdout):
                        if stream is not None:
                            try:
                                stream.close()
                            except OSError:
                                process_cleanup_failed = True
            if process_cleanup_failed and not failed:
                raise GuardError('Image stream process cleanup incomplete')
        require(self.image(reference) == outer_id, 'Dependency source identity changed during export')
        loaded = json.loads(self.inner('image', 'inspect', provenance['config_id']))
        config_id = verify_loaded_image(loaded, provenance)
        if reference in ('eclipse-temurin:21-jdk', 'eclipse-temurin:21-jre'):
            require(json.loads(self.inner('image', 'inspect', reference))[0]['Id'] == config_id,
                    'Imported build tag identity differs')
        return {'outer_id': outer_id, 'config_id': config_id}

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
                dependencies = {'revision': self.revision, 'platform': 'linux/amd64', 'images': {}}
                for reference in IMAGES[2:]:
                    dependencies['images'][reference] = self.transfer_image(reference, images[reference])
                dependency_manifest = directory / 'recovery-dependencies.json'
                dependency_manifest.write_text(json.dumps(dependencies), encoding='utf-8')
                dependency_manifest.chmod(0o600)
                self.copy_dependencies(dependency_manifest)
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
                              'dependency_provenance': dependencies,
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
