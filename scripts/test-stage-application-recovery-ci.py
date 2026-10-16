"""Offline staging rejection cases; never contacts Docker or simulates money."""
import importlib.util
import hashlib
import io
import json
from pathlib import Path
import tempfile
import tarfile
import sys
import threading
import time
import unittest
from unittest.mock import patch, MagicMock

spec = importlib.util.spec_from_file_location('stage_recovery', Path(__file__).with_name('stage-application-recovery-ci.py'))
stage = importlib.util.module_from_spec(spec)
spec.loader.exec_module(stage)


class ArchiveGuards(unittest.TestCase):
    def test_dependency_copy_tar_has_fixed_private_root_regular_file_and_exact_bytes(self):
        payload = b'{"synthetic":"dependency-provenance"}'
        raw = stage.dependency_archive(payload)
        self.assertLessEqual(len(raw), 131072)
        with tarfile.open(fileobj=io.BytesIO(raw), mode='r:') as archive:
            entry, = archive.getmembers()
            self.assertEqual((entry.name, entry.mode, entry.uid, entry.gid, entry.type),
                             ('recovery-dependencies.json', 0o600, 0, 0, tarfile.REGTYPE))
            self.assertEqual(archive.extractfile(entry).read(), payload)
        stage.verify_dependency_copy(raw, payload)

    def test_dependency_copy_rejects_mode_owner_type_name_content_and_inventory_drift(self):
        payload = b'{"synthetic":true}'
        for change in ('mode', 'uid', 'gid', 'symlink', 'directory', 'name', 'extra', 'content'):
            output = io.BytesIO()
            with tarfile.open(fileobj=output, mode='w', format=tarfile.USTAR_FORMAT) as archive:
                entry = tarfile.TarInfo('foreign.json' if change == 'name' else 'recovery-dependencies.json')
                entry.mode, entry.uid, entry.gid = (0o755 if change == 'mode' else 0o600), (1000 if change == 'uid' else 0), (1000 if change == 'gid' else 0)
                data = b'{"different":true}' if change == 'content' else payload
                if change in ('symlink', 'directory'):
                    entry.type = tarfile.SYMTYPE if change == 'symlink' else tarfile.DIRTYPE
                    entry.linkname = '/private'
                    entry.size = 0
                    archive.addfile(entry)
                else:
                    entry.size = len(data)
                    archive.addfile(entry, io.BytesIO(data))
                if change == 'extra':
                    archive.addfile(tarfile.TarInfo('extra'))
            with self.subTest(change=change), self.assertRaises(stage.GuardError):
                stage.verify_dependency_copy(output.getvalue(), payload)

    def test_dependency_copy_rejects_hidden_extended_headers_sparse_checksum_and_trailing_data(self):
        payload = b'{"synthetic":true}'
        valid = stage.dependency_archive(payload)
        pax = io.BytesIO()
        with tarfile.open(fileobj=pax, mode='w', format=tarfile.PAX_FORMAT, pax_headers={'comment': 'synthetic'}) as archive:
            entry = tarfile.TarInfo('recovery-dependencies.json')
            entry.mode, entry.uid, entry.gid, entry.size = 0o600, 0, 0, len(payload)
            archive.addfile(entry, io.BytesIO(payload))
        bad_checksum = bytearray(valid)
        bad_checksum[0] ^= 1
        invalids = [pax.getvalue(), bytes(bad_checksum), valid + b'x' + bytes(511)]
        for kind in (tarfile.GNUTYPE_LONGNAME, tarfile.GNUTYPE_LONGLINK, tarfile.GNUTYPE_SPARSE, tarfile.SYMTYPE):
            entry = tarfile.TarInfo('recovery-dependencies.json')
            entry.type, entry.mode, entry.size = kind, 0o600, len(payload)
            invalids.append(entry.tobuf(format=tarfile.USTAR_FORMAT) + valid[512:])
        for raw in invalids:
            with self.subTest(header=raw[156:157]), self.assertRaises(stage.GuardError):
                stage.verify_dependency_copy(raw, payload)
    def test_json_duplicate_nonfinite_truncated_and_excessive_depth_fail_closed(self):
        for raw in ('{"revision":"a","revision":"b"}', '{"value":NaN}', '{', '[' * 10000 + '0' + ']' * 10000):
            with self.subTest(size=len(raw)), self.assertRaises(stage.GuardError):
                stage.strict_json(raw)
    def test_dependency_manifest_requires_exact_inventory_revision_platform_and_digest_schema(self):
        valid = {'revision': 'a' * 40, 'platform': 'linux/amd64',
                 'images': {ref: {'outer_id': 'sha256:' + 'b' * 64, 'config_id': 'sha256:' + 'c' * 64}
                            for ref in stage.IMAGES[2:]}}
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / 'dependencies.json'
            path.write_text(json.dumps(valid))
            path.chmod(0o600)
            self.assertEqual(stage.load_dependencies(path, 'a' * 40), valid)
            invalids = [dict(valid, revision='d' * 40), dict(valid, platform='linux/arm64'),
                        dict(valid, images={}), dict(valid, extra='private'),
                        dict(valid, images={**valid['images'], 'foreign:tag': {'outer_id': 'sha256:' + 'b' * 64, 'config_id': 'sha256:' + 'c' * 64}}),
                        dict(valid, images={ref: {'outer_id': 'tag', 'config_id': 'sha256:' + 'c' * 64} for ref in stage.IMAGES[2:]}),
                        dict(valid, images={ref: {'outer_id': 'sha256:' + 'b' * 64, 'config_id': 'sha256:' + 'c' * 64, 'extra': 'private'} for ref in stage.IMAGES[2:]})]
            for invalid in invalids:
                path.write_text(json.dumps(invalid))
                with self.assertRaises(stage.GuardError):
                    stage.load_dependencies(path, 'a' * 40)
            path.write_bytes(b'x' * 65537)
            with self.assertRaises(stage.GuardError):
                stage.load_dependencies(path, 'a' * 40)
            path.write_text(json.dumps(valid))
            with patch.object(Path, 'is_symlink', return_value=True), self.assertRaises(stage.GuardError):
                stage.load_dependencies(path, 'a' * 40)

    def fixture(self, config=None, manifest=None, extra=()):
        config = config or {'os': 'linux', 'architecture': 'amd64', 'config': {'Cmd': ['synthetic']},
                            'rootfs': {'type': 'layers', 'diff_ids': ['sha256:' + hashlib.sha256(b'layer').hexdigest()]}}
        raw = json.dumps(config).encode()
        config_name = hashlib.sha256(raw).hexdigest() + '.json'
        manifest = manifest if manifest is not None else [{'Config': config_name, 'RepoTags': ['synthetic:fixture'],
                                                          'Layers': ['layer/layer.tar']}]
        output = io.BytesIO()
        with tarfile.open(fileobj=output, mode='w', format=tarfile.USTAR_FORMAT) as archive:
            for name, data in ((config_name, raw), ('layer/layer.tar', b'layer'),
                               ('manifest.json', json.dumps(manifest).encode()), *extra):
                entry = tarfile.TarInfo(name)
                entry.size = len(data)
                archive.addfile(entry, io.BytesIO(data))
        expected = {'Os': config['os'], 'Architecture': config['architecture'], 'Config': config['config'],
                    'RootFS': {'Type': config['rootfs']['type'], 'Layers': config['rootfs']['diff_ids']}}
        return output.getvalue(), expected, 'sha256:' + hashlib.sha256(raw).hexdigest()

    def test_archive_is_forwarded_unchanged_and_config_digest_is_not_index_identity(self):
        archive, expected, digest = self.fixture()
        destination = io.BytesIO()
        result = stage.observe_archive(io.BytesIO(archive), destination, expected)
        self.assertEqual(destination.getvalue(), archive)
        self.assertEqual(result['config_id'], digest)
        loaded = dict(expected, Id=digest)
        self.assertEqual(stage.verify_loaded_image([loaded], result), digest)
        with self.assertRaises(stage.GuardError):
            stage.verify_loaded_image([dict(loaded, Id='sha256:' + 'b' * 64)], result)

    def test_wrong_platform_source_config_or_layers_are_rejected(self):
        archive, expected, _ = self.fixture()
        for field, value in (('Os', 'windows'), ('Architecture', 'arm64'), ('Config', {'Cmd': ['foreign']}),
                             ('RootFS', {'Type': 'layers', 'Layers': ['sha256:' + 'b' * 64]})):
            with self.subTest(field=field), self.assertRaises(stage.GuardError):
                stage.observe_archive(io.BytesIO(archive), io.BytesIO(), dict(expected, **{field: value}))
        expected['Architecture'] = 'arm64'
        with self.assertRaises(stage.GuardError):
            stage.observe_archive(io.BytesIO(self.fixture(config={'os': 'linux', 'architecture': 'arm64',
                'config': {}, 'rootfs': {'type': 'layers', 'diff_ids': ['sha256:' + 'a' * 64]}})[0]), io.BytesIO(), expected)

    def test_malformed_missing_duplicate_and_traversal_archive_members_are_rejected(self):
        archive, expected, _ = self.fixture()
        invalids = [b'not a tar archive', archive[:100], self.fixture(manifest=[])[0],
                    self.fixture(manifest=[{'Config': 'missing.json', 'Layers': []}])[0],
                    self.fixture(extra=(('manifest.json', b'[]'),))[0],
                    self.fixture(extra=(('../outside', b'synthetic'),))[0], archive + b'private-trailing-data']
        for data in invalids:
            with self.subTest(size=len(data)), self.assertRaises(stage.GuardError):
                stage.observe_archive(io.BytesIO(data), io.BytesIO(), expected)

    def test_archive_byte_budget_deadline_and_extended_header_fail_closed(self):
        archive, expected, _ = self.fixture()
        with self.assertRaises(stage.GuardError):
            stage.observe_archive(io.BytesIO(archive), io.BytesIO(), expected, max_bytes=511)
        with patch.object(stage.time, 'monotonic', return_value=20), self.assertRaises(stage.GuardError):
            stage.observe_archive(io.BytesIO(archive), io.BytesIO(), expected, deadline=10)
        entry = tarfile.TarInfo('unsafe-extended-header')
        entry.type, entry.size = tarfile.XHDTYPE, 2**32
        with self.assertRaises(stage.GuardError):
            stage.observe_archive(io.BytesIO(entry.tobuf()), io.BytesIO(), expected)


class StageGuards(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix='stage-offline-')
        self.addCleanup(self.temporary.cleanup)
        self.source = Path(self.temporary.name) / 'repo'
        self.source.mkdir()
        (self.source / '.git').mkdir()
        self.subject = stage.Stage('unix:///tmp/synthetic-outer.sock', self.source, 'a' * 40)

    def test_bounded_copy_reader_kills_and_reaps_real_flooding_and_stalled_processes(self):
        real_popen = stage.subprocess.Popen
        for mode in ('flood', 'stall'):
            processes, failures, results = [], [], []
            script = ('import sys,threading; sys.stdout.buffer.write(bytes(1024**2)); sys.stdout.buffer.flush(); '
                      'threading.Event().wait()' if mode == 'flood' else
                      'import threading; threading.Event().wait()')
            def launch(*args, **kwargs):
                process = real_popen(*args, **kwargs)
                processes.append(process)
                return process
            def read():
                try:
                    results.append(stage.read_bounded_process([sys.executable, '-u', '-c', script],
                                                              self.subject.env, self.source, timeout=1))
                except BaseException as error:
                    failures.append(error)
            with self.subTest(mode=mode), patch.object(stage.subprocess, 'Popen', side_effect=launch):
                started = time.monotonic()
                worker = threading.Thread(target=read, daemon=True)
                worker.start()
                worker.join(timeout=5)
                rescue_required = worker.is_alive()
                if rescue_required:
                    for process in processes:
                        if process.poll() is None:
                            process.kill()
                    worker.join(timeout=2)
                for process in processes:
                    if process.poll() is None:
                        process.kill()
                    process.wait(timeout=2)
                elapsed = time.monotonic() - started
                self.assertFalse(rescue_required, 'Copy reader did not stop within its byte/deadline bounds')
                self.assertFalse(worker.is_alive())
                self.assertEqual(len(processes), 1)
                self.assertTrue(failures)
                self.assertEqual(results, [])
                self.assertIsNotNone(processes[0].returncode)
                self.assertTrue(processes[0].stdout.closed)
                self.assertLess(elapsed, 5)
                if mode == 'stall':
                    self.assertGreaterEqual(elapsed, 0.8, 'Synthetic stall failed before the watchdog deadline')

    def test_dependency_copy_uses_tar_stdin_and_checks_mode_before_runner_start(self):
        manifest = {'revision': 'a' * 40, 'platform': 'linux/amd64', 'images':
                    {ref: {'outer_id': 'sha256:' + 'b' * 64, 'config_id': 'sha256:' + 'c' * 64} for ref in stage.IMAGES[2:]}}
        path = self.source.parent / 'dependencies.json'
        path.write_text(json.dumps(manifest))
        path.chmod(0o600)
        payload = path.read_bytes()
        runner = {'Config': {'User': ''}, 'State': {'Running': False}}
        with (patch.object(self.subject, 'owned', return_value=runner),
              patch.object(self.subject, 'docker', return_value=b'') as docker,
              patch.object(self.subject, 'read_dependency_copy', return_value=stage.dependency_archive(payload)) as readback):
            self.subject.copy_dependencies(path)
        self.assertEqual(docker.call_args_list[0].args, ('cp', '-a', '-', self.subject.names['runner'] + ':/work'))
        stage.verify_dependency_copy(docker.call_args_list[0].kwargs['input'], payload)
        self.assertEqual(docker.call_count, 1)
        readback.assert_called_once_with()
        with (patch.object(self.subject, 'owned', return_value=runner),
              patch.object(stage, 'read_bounded_process', return_value=b'synthetic') as bounded):
            self.subject.read_dependency_copy()
        self.assertEqual(bounded.call_args.args, (['docker', '--host', self.subject.endpoint, 'cp',
                         self.subject.names['runner'] + ':/work/recovery-dependencies.json', '-'],
                         self.subject.env, self.source))
        for state, user in ((True, ''), (False, '10001:10001')):
            with (self.subTest(running=state, user=user),
                  patch.object(self.subject, 'owned', return_value={'Config': {'User': user}, 'State': {'Running': state}}),
                  patch.object(self.subject, 'docker', side_effect=AssertionError('Copy must not run'))):
                with self.assertRaises(stage.GuardError):
                    self.subject.copy_dependencies(path)

    def test_stream_transfer_binds_frozen_outer_identity_to_exact_imported_config_and_cancels_watchdog(self):
        archive, source, digest = ArchiveGuards().fixture()
        outer = 'sha256:' + 'b' * 64
        for failure in (None, 'source_changed', 'loaded_changed'):
            producer, consumer = MagicMock(), MagicMock()
            producer.stdout, producer.stdin = io.BytesIO(archive), None
            consumer.stdout, consumer.stdin = None, io.BytesIO()
            for process in (producer, consumer):
                process.wait.return_value = 0
                process.poll.return_value = 0
            loaded = dict(source, Id=digest if failure != 'loaded_changed' else 'sha256:' + 'd' * 64)
            def inner(*args):
                return json.dumps([loaded] if args[:2] == ('image', 'inspect') else {}).encode()
            with (self.subTest(failure=failure), patch.object(self.subject, 'owned'),
                  patch.object(self.subject, 'image', side_effect=[outer, outer if failure != 'source_changed' else 'sha256:' + 'e' * 64]),
                  patch.object(self.subject, 'docker', return_value=json.dumps([source]).encode()),
                  patch.object(self.subject, 'inner', side_effect=inner),
                  patch.object(stage.subprocess, 'Popen', side_effect=[producer, consumer]) as processes,
                  patch.object(stage.threading, 'Timer') as timer):
                if failure:
                    with self.assertRaises(stage.GuardError):
                        self.subject.transfer_image('postgres:17.6-alpine', outer)
                else:
                    self.assertEqual(self.subject.transfer_image('postgres:17.6-alpine', outer),
                                     {'outer_id': outer, 'config_id': digest})
                timer.return_value.start.assert_called_once()
                timer.return_value.cancel.assert_called_once()
                self.assertIn('--platform', processes.call_args_list[0].args[0])
                self.assertIn('--platform', processes.call_args_list[1].args[0])
                self.assertTrue(consumer.stdin.closed)

    def test_malformed_stream_terminates_both_processes_and_closes_pipes(self):
        _, source, _ = ArchiveGuards().fixture()
        producer, consumer = MagicMock(), MagicMock()
        producer.stdout, producer.stdin = io.BytesIO(b'invalid'), None
        consumer.stdout, consumer.stdin = None, io.BytesIO()
        for process in (producer, consumer):
            process.wait.return_value = 0
            process.poll.return_value = None
        outer = 'sha256:' + 'b' * 64
        with (patch.object(self.subject, 'owned'), patch.object(self.subject, 'image', return_value=outer),
              patch.object(self.subject, 'docker', return_value=json.dumps([source]).encode()),
              patch.object(self.subject, 'inner', return_value=b'{}'),
              patch.object(stage.subprocess, 'Popen', side_effect=[producer, consumer]),
              patch.object(stage.threading, 'Timer') as timer):
            with self.assertRaises(stage.GuardError):
                self.subject.transfer_image('postgres:17.6-alpine', outer)
        for process in (producer, consumer):
            process.kill.assert_called_once()
            process.wait.assert_called_once_with(timeout=30)
        self.assertTrue(producer.stdout.closed)
        self.assertTrue(consumer.stdin.closed)
        timer.return_value.cancel.assert_called_once()

    def test_real_watchdog_unblocks_stalled_read_and_write_and_reaps_both_local_processes(self):
        # Real OS pipes, real subprocesses and a real Timer; only Docker/ownership
        # boundaries are replaced. No container, network or financial fixture.
        real_popen = stage.subprocess.Popen
        _, source, _ = ArchiveGuards().fixture()
        header = tarfile.TarInfo('layer/layer.tar')
        header.size = 64 * 1024**2
        for mode in ('read', 'write'):
            processes, failures = [], []
            producer_script = ('import threading; threading.Event().wait()' if mode == 'read' else
                'import sys; sys.stdout.buffer.write(bytes.fromhex(sys.argv[1])); sys.stdout.buffer.flush(); '
                'block=bytes(65536)\nwhile True: sys.stdout.buffer.write(block); sys.stdout.buffer.flush()')
            consumer_script = 'import threading; threading.Event().wait()'
            def local_process(arguments, **kwargs):
                script = producer_script if not processes else consumer_script
                command = [sys.executable, '-u', '-c', script]
                if not processes and mode == 'write':
                    command.append(header.tobuf().hex())
                process = real_popen(command, stdin=kwargs.get('stdin'), stdout=kwargs.get('stdout'),
                                     stderr=kwargs.get('stderr'), cwd=self.source)
                processes.append(process)
                return process
            def transfer():
                try:
                    self.subject.transfer_image('postgres:17.6-alpine', 'sha256:' + 'b' * 64, timeout=1)
                except BaseException as error:
                    failures.append(error)
            with (self.subTest(mode=mode), patch.object(self.subject, 'owned'),
                  patch.object(self.subject, 'image', return_value='sha256:' + 'b' * 64),
                  patch.object(self.subject, 'docker', return_value=json.dumps([source]).encode()),
                  patch.object(self.subject, 'inner', return_value=b'{}'),
                  patch.object(stage.subprocess, 'Popen', side_effect=local_process)):
                started = time.monotonic()
                worker = threading.Thread(target=transfer, daemon=True)
                worker.start()
                worker.join(timeout=5)
                watchdog_failed = worker.is_alive()
                # Independent rescue makes a broken watchdog fail within a bound
                # instead of hanging this offline test or retaining child processes.
                if watchdog_failed:
                    for process in processes:
                        if process.poll() is None:
                            process.kill()
                    worker.join(timeout=2)
                for process in processes:
                    if process.poll() is None:
                        process.kill()
                    process.wait(timeout=2)
                self.assertFalse(watchdog_failed, 'Real watchdog did not unblock stalled image I/O')
                self.assertFalse(worker.is_alive())
                elapsed = time.monotonic() - started
                self.assertGreaterEqual(elapsed, 0.8, 'Synthetic child failed before the real watchdog deadline')
                self.assertLess(elapsed, 5)
                self.assertEqual(len(processes), 2)
                self.assertTrue(failures, 'Stalled stream must fail, never report a successful import')
                self.assertTrue(all(process.returncode is not None for process in processes))
                self.assertTrue(processes[0].stdout.closed)
                self.assertTrue(processes[1].stdin.closed)

    def container(self, service='runner'):
        network = self.subject.project + '_default'
        volumes = {'runner': ('recovery-socket', 'recovery-workspace'),
                   'daemon': ('recovery-data', 'recovery-socket')}[service]
        return {'Name': '/' + self.subject.names[service], 'Id': service + '-synthetic-id',
                'Config': {'Labels': {'com.docker.compose.project': self.subject.project,
                                     'com.docker.compose.service': service}},
                'HostConfig': {'Binds': [], 'PortBindings': {}, 'Privileged': service == 'daemon',
                               'NetworkMode': network if service == 'daemon' else 'container:daemon-synthetic-id'},
                'NetworkSettings': {'Networks': {network: {}}},
                'Mounts': [{'Type': 'volume', 'Name': self.subject.project + '_' + volume,
                            'Destination': {'recovery-data': '/var/lib/docker', 'recovery-socket': '/run/recovery-docker',
                                            'recovery-workspace': '/work'}[volume], 'RW': True} for volume in volumes]}

    def inspection(self, runner=None, daemon=None):
        def inspect(*args):
            kind, operation, name = args
            self.assertEqual(operation, 'inspect')
            if kind == 'container':
                data = (runner if runner is not None else self.container()) if name == self.subject.names['runner'] else (daemon if daemon is not None else self.container('daemon'))
            else:
                data = {'Name': name, 'Driver': 'bridge' if kind == 'network' else 'local',
                        'Labels': {'com.docker.compose.project': self.subject.project}, 'Options': None}
            return json.dumps([data]).encode()
        return inspect

    def test_owned_topology_inspections_pass_without_starting_process(self):
        with patch.object(self.subject, 'docker', side_effect=self.inspection()), \
                patch('subprocess.run', side_effect=AssertionError('Process invoked')):
            self.assertEqual(self.subject.owned('runner')['Id'], 'runner-synthetic-id')
            self.assertEqual(self.subject.owned('daemon')['Id'], 'daemon-synthetic-id')

    def test_compose_named_volume_binds_pass_for_both_services(self):
        runner, daemon = self.container(), self.container('daemon')
        for item in (runner, daemon):
            item['HostConfig']['Binds'] = [m['Name'] + ':' + m['Destination'] + ':rw' for m in item['Mounts']]
        with patch.object(self.subject, 'docker', side_effect=self.inspection(runner, daemon)), \
                patch('subprocess.run', side_effect=AssertionError('Process invoked')):
            self.subject.owned('runner')
            self.subject.owned('daemon')

    def test_named_volume_bind_normalization_rejects_foreign_sources_targets_options_and_duplicates(self):
        valid = [m['Name'] + ':' + m['Destination'] + ':rw' for m in self.container()['Mounts']]
        invalids = [valid[:1], valid + [valid[0]], [valid[0], valid[0]],
                    [valid[0].replace(':rw', ':ro'), valid[1]],
                    [valid[0].replace(':rw', ':rw,z'), valid[1]],
                    [valid[0].replace('/run/recovery-docker', '/var/run/docker.sock'), valid[1]],
                    ['/var/run/docker.sock:/run/recovery-docker:rw', valid[1]],
                    ['C:/private:/run/recovery-docker:rw', valid[1]],
                    ['arman-bank_socket:/run/recovery-docker:rw', valid[1]], '', {}, 'private']
        for binds in invalids:
            data = self.container()
            data['HostConfig']['Binds'] = binds
            with self.subTest(binds=binds), patch.object(self.subject, 'docker', side_effect=self.inspection(data)):
                with self.assertRaises(stage.GuardError):
                    self.subject.owned('runner')

    def test_effective_mounts_reject_drift_even_with_valid_compose_binds(self):
        for change in ('target', 'readonly', 'type', 'duplicate', 'extra', 'label'):
            data = self.container()
            data['HostConfig']['Binds'] = [m['Name'] + ':' + m['Destination'] + ':rw' for m in data['Mounts']]
            if change == 'target': data['Mounts'][0]['Destination'] = '/private'
            if change == 'readonly': data['Mounts'][0]['RW'] = False
            if change == 'type': data['Mounts'][0]['Type'] = 'bind'
            if change == 'duplicate': data['Mounts'][1] = dict(data['Mounts'][0])
            if change == 'extra': data['Mounts'].append(dict(data['Mounts'][0]))
            original = self.inspection(data)
            def inspect(*args):
                result = json.loads(original(*args))
                if change == 'label' and args[0] == 'volume':
                    result[0]['Labels']['com.docker.compose.project'] = 'arman-bank'
                return json.dumps(result).encode()
            with self.subTest(change=change), patch.object(self.subject, 'docker', side_effect=inspect):
                with self.assertRaises(stage.GuardError):
                    self.subject.owned('runner')

    def test_default_plan_does_not_construct_stage_or_start_process(self):
        with (patch('sys.argv', ['stage']), patch.object(stage, 'Stage', side_effect=AssertionError('Stage created')),
              patch('subprocess.run', side_effect=AssertionError('Process invoked')),
              patch('sys.stdout', new_callable=io.StringIO) as output):
            stage.main()
        self.assertIn('PLAN ONLY', output.getvalue())

    def test_failure_phase_allowlist_is_finite_and_exact(self):
        self.assertEqual(stage.PHASES, frozenset(('source', 'imagepreflight', 'create', 'copy',
                         'daemonready', 'import', 'build', 'application', 'cleanup', 'validatedreport')))

    def invoke_rejected_main(self):
        with (patch('sys.argv', ['stage', '--apply', '--outer-daemon', 'unix:///tmp/synthetic-outer.sock',
                                '--source', str(self.source), '--revision', 'a' * 40]),
              patch.object(stage, 'Stage', return_value=self.subject),
              patch.object(self.subject, 'apply', side_effect=stage.GuardError('synthetic-private-password-and-token')),
              patch('subprocess.run', side_effect=AssertionError('Process invoked'))):
            with self.assertRaises(SystemExit) as failure:
                stage.main()
        return str(failure.exception)

    def test_each_allowlisted_phase_is_reported_without_private_diagnostics(self):
        for phase in stage.PHASES:
            with self.subTest(phase=phase):
                self.subject.phase = phase
                self.assertEqual(self.invoke_rejected_main(),
                                 'Recovery staging did not pass; phase=' + phase + '; cleanup=not_started')

    def test_unknown_phase_is_redacted_and_cleanup_status_is_finite(self):
        self.subject.phase = 'synthetic-private-token\nRECOVERY_STAGE:success'
        for started, failed, expected in ((False, False, 'not_started'), (True, False, 'checked'), (True, True, 'failed')):
            with self.subTest(started=started, failed=failed):
                self.subject.cleanup_started = started
                self.subject.cleanup_failed = failed
                message = self.invoke_rejected_main()
                self.assertEqual(message, 'Recovery staging did not pass; phase=source; cleanup=' + expected)
                self.assertNotIn('private', message)
                self.assertNotIn('\n', message)

    def test_apply_missing_explicit_arguments_never_starts_process(self):
        for args in (['--apply'], ['--apply', '--outer-daemon', 'unix:///tmp/outer.sock']):
            with patch('sys.argv', ['stage', *args]), patch('subprocess.run', side_effect=AssertionError('Process invoked')):
                with self.assertRaises(stage.GuardError):
                    stage.main()

    def test_remote_or_implicit_daemon_rejected_before_process(self):
        for endpoint in ('default', 'ssh://host', 'tcp://localhost:2375', ''):
            with self.subTest(endpoint=endpoint), patch('subprocess.run', side_effect=AssertionError('Process invoked')):
                with self.assertRaises(stage.GuardError):
                    stage.Stage(endpoint, self.source, 'a' * 40)

    def test_nonexact_revision_rejected_before_process(self):
        for revision in ('main', 'a' * 39, 'A' * 40, 'a' * 40 + '\n'):
            with self.subTest(revision=revision), patch('subprocess.run', side_effect=AssertionError('Process invoked')):
                with self.assertRaises(stage.GuardError):
                    stage.Stage('unix:///tmp/outer.sock', self.source, revision)

    def test_dirty_source_and_wrong_revision_stop_before_docker(self):
        for outputs in ([b'b' * 40], [b'a' * 40, b'?? untracked-secret.env\n']):
            with self.subTest(outputs=outputs), patch.object(self.subject, 'run', side_effect=outputs), \
                    patch.object(self.subject, 'raw', side_effect=AssertionError('Docker reached')):
                with self.assertRaises(stage.GuardError):
                    self.subject.apply()

    def test_outer_daemon_identity_change_stops_mutation(self):
        self.subject.daemon_id = 'original'
        with patch.object(self.subject, 'raw', return_value=json.dumps({'ID': 'replacement'}).encode()) as raw:
            with self.assertRaises(stage.GuardError):
                self.subject.docker('container', 'rm', '-f', 'any')
        self.assertEqual([('info', '--format', '{{json .}}')], [call.args for call in raw.call_args_list])

    def test_unowned_container_name_or_labels_are_rejected(self):
        for field in ('Name', 'project', 'service'):
            data = self.container()
            if field == 'Name':
                data['Name'] = '/arman-bank-auth-db-1'
            else:
                data['Config']['Labels']['com.docker.compose.' + field] = 'foreign'
            with self.subTest(field=field), patch.object(self.subject, 'docker', side_effect=self.inspection(data)):
                with self.assertRaises(stage.GuardError):
                    self.subject.owned('runner')

    def test_foreign_mount_bind_and_host_port_are_rejected(self):
        for alteration in ('mount', 'bind', 'port'):
            data = self.container()
            if alteration == 'mount':
                data['Mounts'][0]['Name'] = 'arman-bank_auth-db'
            elif alteration == 'bind':
                data['HostConfig']['Binds'] = ['/var/run/docker.sock:/var/run/docker.sock']
            else:
                data['HostConfig']['PortBindings'] = {'8080/tcp': [{'HostPort': '8080'}]}
            with self.subTest(alteration=alteration), patch.object(self.subject, 'docker', side_effect=self.inspection(data)):
                with self.assertRaises(stage.GuardError):
                    self.subject.owned('runner')

    def test_runner_privilege_devices_caps_and_foreign_namespace_rejected(self):
        for key, value in (('Privileged', True), ('Devices', [{'PathOnHost': '/dev/sda'}]),
                           ('CapAdd', ['SYS_ADMIN']), ('PidMode', 'host'), ('NetworkMode', 'host'),
                           ('NetworkMode', 'container:foreign-id')):
            data = self.container()
            data['HostConfig'][key] = value
            with self.subTest(key=key, value=value), patch.object(self.subject, 'docker', side_effect=self.inspection(data)):
                with self.assertRaises(stage.GuardError):
                    self.subject.owned('runner')

    def test_existing_project_resources_prevent_any_compose_or_bundle_creation(self):
        for occupied in ('container', 'volume', 'network'):
            def docker(*args):
                self.assertIn(args[0], ('container', 'volume', 'network'))
                self.assertEqual(args[1], 'ls')
                return b'occupied' if args[0] == occupied else b''
            with (self.subTest(occupied=occupied), patch.object(self.subject, 'source_check', return_value=['git']),
                  patch.object(self.subject, 'raw', return_value=b'{"ID":"synthetic-outer"}'),
                  patch.object(self.subject, 'image', return_value='sha256:' + 'b' * 64),
                  patch.object(self.subject, 'docker', side_effect=docker),
                  patch.object(self.subject, 'run', side_effect=AssertionError('Bundle attempted'))):
                with self.assertRaisesRegex(stage.GuardError, 'project already exists'):
                    self.subject.apply()

    def test_inner_daemon_id_or_label_change_prevents_image_load(self):
        self.subject.inner_id = 'original'
        for info in ({'ID': 'replacement', 'Labels': [stage.DAEMON_LABEL]}, {'ID': 'original', 'Labels': []}):
            with patch.object(self.subject, 'owned'), patch.object(self.subject, 'docker', return_value=json.dumps(info).encode()) as docker:
                with self.assertRaises(stage.GuardError):
                    self.subject.inner('image', 'load')
            self.assertEqual(1, docker.call_count)
            self.assertNotIn('load', docker.call_args.args)

    def test_cleanup_rejects_foreign_container_before_any_removal(self):
        runner = self.subject.names['runner']
        with (patch.object(self.subject, 'docker', return_value=(runner + '\n').encode()) as docker,
              patch.object(self.subject, 'owned', side_effect=stage.GuardError('Foreign ownership'))):
            with self.assertRaises(stage.GuardError):
                self.subject.cleanup()
        self.assertFalse(any('rm' in call.args for call in docker.call_args_list))

    def test_mutable_or_malformed_image_id_rejected(self):
        for rows in ([], [{}], [{'Id': 'postgres:latest'}], [{'Id': 'sha256:' + 'A' * 64}]):
            with patch.object(self.subject, 'docker', return_value=json.dumps(rows).encode()):
                with self.assertRaises(stage.GuardError):
                    self.subject.image('postgres:17.6-alpine')


if __name__ == '__main__':
    unittest.main(verbosity=2)
