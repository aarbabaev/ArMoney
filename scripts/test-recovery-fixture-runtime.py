"""Offline isolation guard tests. Run from an outside-checkout temporary copy."""
import importlib.util
import json
from pathlib import Path
import unittest
import tempfile
from unittest.mock import patch

from recovery_fixture_runtime import (DAEMON_LABEL, LABEL, GuardError, OwnedRuntime,
                                      validate_endpoint, validate_resource, private_directory, BankFixture, until)

TOKEN = 'a' * 32
PREFIX = 'armoney-recovery-' + TOKEN + '-'
NETWORK = PREFIX + 'source-network'
VOLUME = PREFIX + 'source-db-data'
NAME = PREFIX + 'source-db'


def container():
    return {'Name': '/' + NAME, 'Config': {'Labels': {LABEL: TOKEN}},
            'HostConfig': {'NetworkMode': NETWORK, 'PortBindings': {}, 'IpcMode': 'private'},
            'NetworkSettings': {'Networks': {NETWORK: {}}, 'Ports': {'5432/tcp': None}},
            'Mounts': [{'Type': 'volume', 'Name': VOLUME}]}


class ResourceGuards(unittest.TestCase):
    def test_probe_success_after_deadline_is_rejected(self):
        clock = [0.0]
        def late_probe():
            clock[0] = 91.0
            return 'ready'
        with (patch('recovery_fixture_runtime.time.monotonic', side_effect=lambda: clock[0]),
              patch('recovery_fixture_runtime.time.sleep', side_effect=AssertionError('Unexpected sleep'))):
            with self.assertRaises(GuardError):
                until(late_probe, lambda value: value == 'ready', seconds=90)

    def test_probe_success_at_deadline_is_rejected(self):
        clock = [0.0]
        def late_probe():
            clock[0] = 90.0
            return 'ready'
        with (patch('recovery_fixture_runtime.time.monotonic', side_effect=lambda: clock[0]),
              patch('recovery_fixture_runtime.time.sleep', side_effect=AssertionError('Unexpected sleep'))):
            with self.assertRaises(GuardError):
                until(late_probe, lambda value: value == 'ready', seconds=90)

    def test_owned_container(self):
        validate_resource('container', container(), NAME, TOKEN, NETWORK, (VOLUME,))

    def test_foreign_labels_and_name(self):
        for change in ('label', 'name'):
            with self.subTest(change=change):
                data = container()
                if change == 'label':
                    data['Config']['Labels'][LABEL] = 'b' * 32
                else:
                    data['Name'] = '/arman-bank-auth-db-1'
                with self.assertRaises(GuardError):
                    validate_resource('container', data, NAME, TOKEN, NETWORK, (VOLUME,))

    def test_host_and_foreign_mount_access_rejected(self):
        for key, value in [('PortBindings', {'5432/tcp': [{'HostPort': '5432'}]}),
                           ('NetworkMode', 'host'), ('Privileged', True),
                           ('Binds', ['/var/run/docker.sock:/var/run/docker.sock']),
                           ('Devices', [{'PathOnHost': '/dev/sda'}]), ('CapAdd', ['SYS_ADMIN']),
                           ('PidMode', 'host'), ('IpcMode', 'host')]:
            with self.subTest(key=key):
                data = container()
                data['HostConfig'][key] = value
                with self.assertRaises(GuardError):
                    validate_resource('container', data, NAME, TOKEN, NETWORK, (VOLUME,))
        for mount in [{'Type': 'bind', 'Source': '/customer'}, {'Type': 'volume', 'Name': 'arman-bank_auth-db'}]:
            data = container()
            data['Mounts'] = [mount]
            with self.assertRaises(GuardError):
                validate_resource('container', data, NAME, TOKEN, NETWORK, (VOLUME,))

    def test_second_network_and_published_port_rejected(self):
        data = container()
        data['NetworkSettings']['Networks']['arman-bank_bank'] = {}
        with self.assertRaises(GuardError):
            validate_resource('container', data, NAME, TOKEN, NETWORK, (VOLUME,))
        data = container()
        data['NetworkSettings']['Ports']['5432/tcp'] = [{'HostPort': '5432'}]
        with self.assertRaises(GuardError):
            validate_resource('container', data, NAME, TOKEN, NETWORK, (VOLUME,))

    def test_network_and_volume_ownership(self):
        data = dict(Name=NETWORK, Labels={LABEL: TOKEN}, Internal=True, Driver='bridge')
        validate_resource('network', data, NETWORK, TOKEN)
        for field, value in [('Internal', False), ('Driver', 'host'), ('Labels', {})]:
            invalid = dict(data, **{field: value})
            with self.assertRaises(GuardError):
                validate_resource('network', invalid, NETWORK, TOKEN)
        with self.assertRaises(GuardError):
            validate_resource('volume', dict(Name=VOLUME, Labels={}), VOLUME, TOKEN)
        volume = dict(Name=VOLUME, Labels={LABEL: TOKEN}, Driver='local', Options=None)
        validate_resource('volume', volume, VOLUME, TOKEN)
        for change in ({'Driver': 'foreign'}, {'Options': {'type': 'none', 'o': 'bind', 'device': '/customer'}}):
            with self.assertRaises(GuardError):
                validate_resource('volume', dict(volume, **change), VOLUME, TOKEN)

    def test_remote_default_and_malformed_endpoints_rejected(self):
        for endpoint in (None, '', 'tcp://localhost:2375', 'ssh://test', 'default',
                         'unix:///tmp/a\n--host', 'npipe://default'):
            with self.subTest(endpoint=endpoint), self.assertRaises(GuardError):
                validate_endpoint(endpoint)
        self.assertEqual(validate_endpoint('unix:///tmp/recovery.sock'), 'unix:///tmp/recovery.sock')
        validate_endpoint('npipe:////./pipe/armoney-recovery')

    def test_import_and_constructor_do_not_execute(self):
        with patch('subprocess.run', side_effect=AssertionError('Unexpected process')):
            spec = importlib.util.spec_from_file_location('isolated_runtime', Path(__file__).with_name('recovery_fixture_runtime.py'))
            module = importlib.util.module_from_spec(spec)
            spec.loader.exec_module(module)
            module.OwnedRuntime('unix:///tmp/recovery.sock', TOKEN)

    def test_ambient_ci_and_docker_overrides_removed(self):
        with patch.dict('os.environ', {'CI': 'true', 'DOCKER_HOST': 'tcp://customer',
                                       'DOCKER_CONTEXT': 'customer', 'COMPOSE_PROJECT_NAME': 'arman-bank',
                                       'GITHUB_PAT_TOKEN': 'private', 'OPENAI_API_KEY': 'private'}):
            runtime = OwnedRuntime('unix:///tmp/recovery.sock', TOKEN)
        self.assertFalse(any(k.upper().startswith(('DOCKER_', 'COMPOSE_')) or k.upper() == 'CI'
                             for k in runtime.env))
        self.assertNotIn('GITHUB_PAT_TOKEN', runtime.env)
        self.assertNotIn('OPENAI_API_KEY', runtime.env)

    def test_daemon_label_and_empty_inventory_required(self):
        for info in ({'Labels': [], 'Containers': 0}, {'Labels': [DAEMON_LABEL], 'Containers': 1}):
            runtime = OwnedRuntime('unix:///tmp/recovery.sock', TOKEN)
            with patch.object(runtime, 'docker', return_value=json.dumps(info).encode()), self.assertRaises(GuardError):
                runtime.preflight()
            self.assertFalse(runtime.authorized)
        runtime = OwnedRuntime('unix:///tmp/recovery.sock', TOKEN)
        def empty(*args):
            if args[:1] == ('info',):
                return json.dumps({'ID': 'synthetic-daemon', 'Labels': [DAEMON_LABEL], 'Containers': 0}).encode()
            return b'' if args[:2] == ('volume', 'ls') else b'bridge\nhost\nnone\n'
        with patch.object(runtime, 'docker', side_effect=empty):
            runtime.preflight()
        self.assertTrue(runtime.authorized)

    def test_leftover_volume_or_custom_network_prevents_authorization(self):
        for volumes, networks in ((b'old-bank-volume\n', b'bridge\nhost\nnone\n'),
                                  (b'', b'bridge\nhost\nnone\nold-network\n')):
            runtime = OwnedRuntime('unix:///tmp/recovery.sock', TOKEN)
            def inventory(*args):
                if args[:1] == ('info',):
                    return json.dumps({'ID': 'synthetic-daemon', 'Labels': [DAEMON_LABEL], 'Containers': 0}).encode()
                return volumes if args[:2] == ('volume', 'ls') else networks
            with patch.object(runtime, 'docker', side_effect=inventory), self.assertRaises(GuardError):
                runtime.preflight()
            self.assertFalse(runtime.authorized)

    def test_private_artifacts_require_new_directory(self):
        with tempfile.TemporaryDirectory() as temporary:
            created = private_directory(Path(temporary) / 'artifacts')
            self.assertTrue(created.is_dir())
            with self.assertRaises(GuardError):
                private_directory(created)

    def test_unvalidated_daemon_cannot_mutate(self):
        runtime = OwnedRuntime('unix:///tmp/recovery.sock', TOKEN)
        with patch('subprocess.run', side_effect=AssertionError('Unexpected process')), self.assertRaises(GuardError):
            runtime.docker('volume', 'create', VOLUME)

    def test_ownership_rechecked_before_exec_stop_copy_and_cleanup(self):
        runtime = OwnedRuntime('unix:///tmp/recovery.sock', TOKEN)
        runtime.authorized = True
        runtime.resources = [('container', NAME)]
        for operation in (lambda: runtime.exec(NAME, 'psql'), lambda: runtime.action(NAME, 'stop'),
                          lambda: runtime.copy_in(NAME, Path('x'), '/tmp/x'), runtime.cleanup):
            with (patch.object(runtime, 'owned', side_effect=GuardError('Ownership changed')),
                  patch.object(runtime, 'docker', side_effect=AssertionError('Unexpected mutation'))):
                with self.assertRaises(GuardError):
                    operation()

    def test_foreign_network_rejected_before_container_or_volume_creation(self):
        runtime = OwnedRuntime('unix:///tmp/recovery.sock', TOKEN)
        runtime.authorized = True
        with (patch.object(runtime, 'owned', side_effect=GuardError('Foreign network')),
              patch.object(runtime, 'docker', side_effect=AssertionError('Unexpected mutation'))):
            with self.assertRaises(GuardError):
                runtime.create('source', 'db', 'sha256:' + 'a' * 64, 'arman-bank_bank',
                               mounts=(('data', '/var/lib/postgresql/data'),))

    def test_ci_environment_and_mutable_image_rejected_before_creation(self):
        runtime = OwnedRuntime('unix:///tmp/recovery.sock', TOKEN)
        for image, environment in [('postgres:latest', {}), ('sha256:' + 'a' * 64, {'CI': 'true'})]:
            with (patch.object(runtime, 'owned'),
                  patch.object(runtime, 'docker', side_effect=AssertionError('Unexpected mutation'))):
                with self.assertRaises(GuardError):
                    runtime.create('source', 'db', image, NETWORK, environment,
                                   mounts=(('data', '/var/lib/postgresql/data'),))

    def test_bank_image_revision_mismatch_rejected(self):
        runtime = OwnedRuntime('unix:///tmp/recovery.sock', TOKEN)
        data = {'Id': 'sha256:' + 'a' * 64, 'Config': {'Labels': {'org.opencontainers.image.revision': 'b' * 40}}}
        with patch.object(runtime, 'inspect', return_value=data), self.assertRaises(GuardError):
            runtime.image(data['Id'], 'c' * 40)

    def test_balance_parser_rejects_float_boolean_negative_and_overflow(self):
        # Negative parsing cases only; no posted transfer success is simulated.
        fixture = BankFixture(None, None)
        for invalid in (750.0, True, -1, 2**63):
            with patch.object(fixture, 'expect', return_value={'balance_minor': invalid}), self.assertRaises(GuardError):
                fixture.balance('synthetic-token', {'id': '00000000-0000-0000-0000-000000000001'})

    def cleanup_fixture(self):
        runtime = OwnedRuntime('unix:///tmp/recovery.sock', TOKEN)
        runtime.authorized = True
        runtime.daemon_id = 'synthetic-daemon'
        runtime.authorized_endpoint = runtime.endpoint
        runtime.containers = {NAME:(NETWORK,(VOLUME,)), PREFIX+'other':(PREFIX+'other-net',())}
        runtime.resources = [('network',NETWORK),('volume',VOLUME),('container',PREFIX+'other'),('container',NAME)]
        return runtime

    def test_cleanup_rejection_retains_dependencies_but_attempts_unrelated_container(self):
        runtime = self.cleanup_fixture()
        calls = []
        def owned(kind, name):
            calls.append(('owned',kind,name))
            if name == NAME:
                raise GuardError('private inspection detail')
        def docker(*args):
            calls.append(args)
            return json.dumps({'ID':'synthetic-daemon','Labels':[DAEMON_LABEL]}).encode()
        with patch.object(runtime,'owned',side_effect=owned), patch.object(runtime,'docker',side_effect=docker):
            with self.assertRaises(GuardError) as failure:
                runtime.cleanup()
        self.assertIn(('container','rm','-f',PREFIX+'other'),calls)
        self.assertNotIn(('container','rm','-f',NAME),calls)
        self.assertFalse(any(c[:2] in (('volume','rm'),('network','rm')) for c in calls))
        self.assertIn('3 resource failures',str(failure.exception))
        self.assertNotIn('private inspection detail',str(failure.exception))

    def test_cleanup_removal_failure_continues_without_retry_or_force_dependencies(self):
        runtime = self.cleanup_fixture()
        calls = []
        def docker(*args):
            calls.append(args)
            if args == ('container','rm','-f',NAME):
                raise GuardError('sanitized removal failure')
            return json.dumps({'ID':'synthetic-daemon','Labels':[DAEMON_LABEL]}).encode()
        with patch.object(runtime,'owned') as owned, patch.object(runtime,'docker',side_effect=docker):
            with self.assertRaises(GuardError):
                runtime.cleanup()
        self.assertEqual(1,calls.count(('container','rm','-f',NAME)))
        self.assertIn(('container','rm','-f',PREFIX+'other'),calls)
        self.assertNotIn(('volume','rm',VOLUME),calls)
        self.assertNotIn(('network','rm',NETWORK),calls)
        self.assertEqual([('container',NAME),('container',PREFIX+'other')], [c.args for c in owned.call_args_list])

    def test_cleanup_daemon_identity_uncertainty_stops_all_removals(self):
        runtime = self.cleanup_fixture()
        with patch.object(runtime,'docker',return_value=json.dumps({'ID':'replacement','Labels':[DAEMON_LABEL]}).encode()) as docker:
            with self.assertRaises(GuardError):
                runtime.cleanup()
        self.assertEqual([('info','--format','{{json .}}')],[c.args for c in docker.call_args_list])

    def test_cleanup_success_orders_resources_and_rechecks_each_removal(self):
        runtime = self.cleanup_fixture()
        calls=[]
        def docker(*args):
            calls.append(args)
            return json.dumps({'ID':'synthetic-daemon','Labels':[DAEMON_LABEL]}).encode()
        with patch.object(runtime,'owned',side_effect=lambda kind,name:calls.append(('owned',kind,name))), patch.object(runtime,'docker',side_effect=docker):
            runtime.cleanup()
        removals=[c for c in calls if len(c)>1 and c[1]=='rm']
        self.assertEqual(['container','container','volume','network'],[c[0] for c in removals])
        for removal in removals:
            self.assertEqual(('owned',removal[0],removal[-1]),calls[calls.index(removal)-1])
        self.assertEqual(('volume','rm',VOLUME),removals[2])
        self.assertEqual(('network','rm',NETWORK),removals[3])

    def test_cleanup_daemon_loss_after_failed_removal_aborts_unrelated_resources(self):
        runtime = self.cleanup_fixture()
        calls=[]
        lost=False
        def docker(*args):
            nonlocal lost
            calls.append(args)
            if args == ('container','rm','-f',NAME):
                lost=True
                raise GuardError('operation failed')
            if lost:
                raise GuardError('daemon disconnected')
            return json.dumps({'ID':'synthetic-daemon','Labels':[DAEMON_LABEL]}).encode()
        with patch.object(runtime,'owned'), patch.object(runtime,'docker',side_effect=docker):
            with self.assertRaisesRegex(GuardError,'daemon integrity unavailable'):
                runtime.cleanup()
        self.assertNotIn(('container','rm','-f',PREFIX+'other'),calls)
        self.assertFalse(any(c[:2] in (('volume','rm'),('network','rm')) for c in calls))

    def test_cleanup_malformed_inspection_is_sanitized_and_unrelated_resources_continue(self):
        runtime = self.cleanup_fixture()
        calls=[]
        def owned(kind,name):
            if name == NAME:
                raise KeyError('private payload')
        def docker(*args):
            calls.append(args)
            return json.dumps({'ID':'synthetic-daemon','Labels':[DAEMON_LABEL]}).encode()
        with patch.object(runtime,'owned',side_effect=owned), patch.object(runtime,'docker',side_effect=docker):
            with self.assertRaises(GuardError) as failure:
                runtime.cleanup()
        self.assertIn(('container','rm','-f',PREFIX+'other'),calls)
        self.assertNotIn('private payload',str(failure.exception))

    def test_preflight_requires_daemon_identity_before_authorization(self):
        runtime = OwnedRuntime('unix:///tmp/recovery.sock',TOKEN)
        with patch.object(runtime,'docker',return_value=json.dumps({'Labels':[DAEMON_LABEL],'Containers':0}).encode()):
            with self.assertRaises(GuardError):
                runtime.preflight()
        self.assertFalse(runtime.authorized)

    def test_cleanup_endpoint_change_stops_before_any_process(self):
        runtime = self.cleanup_fixture()
        runtime.endpoint = 'unix:///tmp/another-daemon.sock'
        with patch.object(runtime, 'docker', side_effect=AssertionError('Unexpected process')):
            with self.assertRaises(GuardError):
                runtime.cleanup()

    def test_cleanup_removed_daemon_label_stops_all_removals(self):
        runtime = self.cleanup_fixture()
        with patch.object(runtime, 'docker', return_value=json.dumps(
                {'ID': 'synthetic-daemon', 'Labels': []}).encode()) as docker:
            with self.assertRaises(GuardError):
                runtime.cleanup()
        self.assertEqual([('info', '--format', '{{json .}}')],
                         [call.args for call in docker.call_args_list])

    def test_unknown_retained_container_protects_all_dependency_resources(self):
        runtime = self.cleanup_fixture()
        del runtime.containers[NAME]
        calls = []
        def owned(kind, name):
            if name == NAME:
                raise GuardError('Unknown container dependencies')
        def docker(*args):
            calls.append(args)
            return json.dumps({'ID': 'synthetic-daemon', 'Labels': [DAEMON_LABEL]}).encode()
        with patch.object(runtime, 'owned', side_effect=owned), patch.object(runtime, 'docker', side_effect=docker):
            with self.assertRaises(GuardError):
                runtime.cleanup()
        self.assertIn(('container', 'rm', '-f', PREFIX + 'other'), calls)
        self.assertFalse(any(c[:2] in (('volume', 'rm'), ('network', 'rm')) for c in calls))


if __name__ == '__main__':
    unittest.main()
