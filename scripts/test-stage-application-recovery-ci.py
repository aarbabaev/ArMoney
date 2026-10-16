"""Offline staging rejection cases; never contacts Docker or simulates money."""
import importlib.util
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('stage_recovery', Path(__file__).with_name('stage-application-recovery-ci.py'))
stage = importlib.util.module_from_spec(spec)
spec.loader.exec_module(stage)


class StageGuards(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix='stage-offline-')
        self.addCleanup(self.temporary.cleanup)
        self.source = Path(self.temporary.name) / 'repo'
        self.source.mkdir()
        (self.source / '.git').mkdir()
        self.subject = stage.Stage('unix:///tmp/synthetic-outer.sock', self.source, 'a' * 40)

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
                'Mounts': [{'Type': 'volume', 'Name': self.subject.project + '_' + volume} for volume in volumes]}

    def inspection(self, runner=None):
        def inspect(*args):
            kind, operation, name = args
            self.assertEqual(operation, 'inspect')
            if kind == 'container':
                data = (runner if runner is not None else self.container()) if name == self.subject.names['runner'] else self.container('daemon')
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
