"""Stdlib guard/acceptance-parser regressions. No Docker daemon is contacted."""
import importlib.util
import json
import os
import shutil
import tempfile
from pathlib import Path
import subprocess
import sys
import unittest
from unittest.mock import patch

sys.dont_write_bytecode = True
SPEC = importlib.util.spec_from_file_location('kafka_smoke', Path(__file__).with_name('kafka-smoke.py'))
kafka = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(kafka)
PROJECT = 'armoney-kafka-test-' + 'a' * 32
ROOT = Path('/work/synthetic/repo')
SECRETS = Path('/work/' + PROJECT + '-private/credentials')
ENV = {'CI': 'true', 'ARMONEY_DISPOSABLE_CI': 'true', 'DOCKER_HOST': kafka.HOST,
       'GITHUB_WORKSPACE': str(ROOT)}


def container(node, index):
    return {'Id': f'{index:064x}', 'Name': f'/{PROJECT}-{node}-1',
            'Config': {'Labels': {'com.docker.compose.project': PROJECT,
                                 'com.docker.compose.service': node,
                                 'com.docker.compose.oneoff': 'False',
                                 'com.docker.compose.project.config_files': str(ROOT / 'compose.kafka.yaml')}},
            'HostConfig': {'PortBindings': {}, 'Privileged': False, 'ReadonlyRootfs': True,
                           'NetworkMode': PROJECT + '_kafka'},
            'Mounts': [
                {'Type': 'bind', 'Source': str(ROOT / 'infra/kafka'), 'Destination': '/opt/armoney', 'RW': False},
                {'Type': 'bind', 'Source': str(SECRETS / node), 'Destination': '/run/kafka-secrets', 'RW': False},
                {'Type': 'volume', 'Name': f'{PROJECT}_{node}-data', 'Destination': '/var/lib/kafka/data', 'RW': True}],
            'NetworkSettings': {'Networks': {PROJECT + '_kafka': {}}}, 'State': {'Running': True}}


class Fixture:
    def __init__(self):
        self.nodes = {item['Id']: item for item in (container(node, n) for n, node in enumerate(kafka.NODES, 1))}
        self.volumes = {f'{PROJECT}_{node}-data': {'Driver': 'local', 'Options': None, 'Labels': {
            'com.docker.compose.project': PROJECT, 'com.docker.compose.volume': node + '-data'}}
            for node in kafka.NODES}
        self.networks = {'b' * 64: {'Name': PROJECT + '_kafka', 'Internal': True, 'Driver': 'bridge',
                                  'Labels': {'com.docker.compose.project': PROJECT},
                                  'Containers': {cid: {} for cid in self.nodes}}}
        self.mutations = []
        self.smoke = kafka.Smoke.__new__(kafka.Smoke)
        self.smoke.project, self.smoke.root, self.smoke.secrets = PROJECT, ROOT, SECRETS
        self.smoke.known = {node: f'{n:064x}' for n, node in enumerate(kafka.NODES, 1)}
        self.smoke.secret_parent = None
        self.smoke.owned = True
        self.smoke.docker = self.docker

    def docker(self, *args, **kwargs):
        if args[:2] == ('ps', '-aq'):
            value = '\n'.join(self.nodes)
        elif args[:2] == ('volume', 'ls'):
            value = '\n'.join(self.volumes)
        elif args[:2] == ('network', 'ls'):
            value = '\n'.join(self.networks)
        elif args[0] == 'inspect':
            value = json.dumps([self.nodes[args[1]]])
        elif args[:2] == ('volume', 'inspect'):
            value = json.dumps([self.volumes[args[2]]])
        elif args[:2] == ('network', 'inspect'):
            value = json.dumps([self.networks[args[2]]])
        else:
            self.mutations.append(args)
            value = ''
            if args[:2] == ('rm', '--force'):
                for cid in args[2:]:
                    del self.nodes[cid]
            elif args[:2] == ('volume', 'rm'):
                for volume in args[2:]:
                    del self.volumes[volume]
            elif args[:2] == ('network', 'rm'):
                for network in args[2:]:
                    del self.networks[network]
            else:
                raise AssertionError('Unexpected mutation')
        return subprocess.CompletedProcess(args, 0, value, '')


def topics(isr='101,102,103'):
    lines = []
    for topic in kafka.TOPICS:
        lines.append(f'Topic: {topic}\tPartitionCount: 3\tReplicationFactor: 3\tConfigs: '
                     'min.insync.replicas=2,retention.ms=604800000,cleanup.policy=delete,'
                     'unclean.leader.election.enable=false,max.message.bytes=32768')
        for partition in range(3):
            lines.append(f'Topic: {topic}\tPartition: {partition}\tLeader: {isr.split(",")[0]}\t'
                         f'Replicas: 101,102,103\tIsr: {isr}\tElr: N/A')
    return '\n'.join(lines)


class Guards(unittest.TestCase):
    def test_rejected_environment_never_calls_subprocess(self):
        cases = [('CI', 'false'), ('ARMONEY_DISPOSABLE_CI', ''), ('DOCKER_HOST', 'unix:///var/run/docker.sock'),
                 ('DOCKER_CONTEXT', 'default'), ('GITHUB_WORKSPACE', '/home/user/repo'),
                 ('GITHUB_WORKSPACE', '/work/other'), ('GITHUB_WORKSPACE', '/work/../home/repo')]
        for key, value in cases:
            with self.subTest(key=key, value=value), patch.object(kafka.subprocess, 'run') as run:
                with self.assertRaises(kafka.Failed):
                    kafka.Smoke(PROJECT, {**ENV, key: value}, ROOT)
                run.assert_not_called()

    def test_project_requires_exact_uuid_prefix(self):
        for project in ('armoney-kafka-local', PROJECT + 'x', 'armoney-kafka-test-' + 'G' * 32, '', '../' + PROJECT):
            with self.subTest(project=project), self.assertRaises(kafka.Failed):
                kafka.guard(project, ENV, ROOT)

    def test_local_overrides_and_symlink_resolution_are_checked(self):
        # POSIX path projection permits this stdlib test on Windows, without a real /work directory.
        with patch.object(Path, 'resolve', lambda p: p):
            for name in ('.env', 'compose.override.yaml', 'compose.override.yml', 'docker-compose.override.yml'):
                with self.subTest(name=name), patch.object(Path, 'exists', lambda p: p.name == name):
                    with self.assertRaises(kafka.Failed):
                        kafka.guard(PROJECT, ENV, ROOT)
            with patch.object(Path, 'exists', return_value=False):
                kafka.guard(PROJECT, ENV, ROOT)
        with patch.object(Path, 'resolve', return_value=Path('/outside')):
            with self.assertRaises(kafka.Failed):
                kafka.guard(PROJECT, ENV, ROOT)

    def test_docker_and_compose_inheritance_removed(self):
        env = {**ENV, 'DOCKER_CONFIG': '/foreign', 'COMPOSE_FILE': 'production.yaml',
               'COMPOSE_PROJECT_NAME': 'bank', 'KAFKA_SECRETS_DIR': '/production', 'PATH': '/synthetic'}
        cleaned = kafka.clean_environment(env)
        self.assertEqual(cleaned['PATH'], '/synthetic')
        self.assertFalse(any(key.startswith(('DOCKER_', 'COMPOSE_', 'KAFKA_')) for key in cleaned))

    def test_docker_command_pins_daemon_and_bounds_execution(self):
        smoke = kafka.Smoke.__new__(kafka.Smoke)
        smoke.env = {'PATH': '/synthetic'}
        with patch.object(kafka.subprocess, 'run', return_value=subprocess.CompletedProcess([], 0, '', '')) as run:
            smoke.docker('ps')
            self.assertEqual(run.call_args.args[0], ['docker', '--host', kafka.HOST, 'ps'])
            self.assertEqual(run.call_args.kwargs['timeout'], 90)
            self.assertEqual(run.call_args.kwargs['env'], smoke.env)

    def test_failed_docker_stage_is_diagnosable_without_rendering_sensitive_data(self):
        smoke = kafka.Smoke.__new__(kafka.Smoke)
        smoke.env = {'PATH': '/synthetic'}
        smoke.phase = 'storage-identity'
        secret = 'synthetic-secret-token-private-path-https-private-host'
        result = subprocess.CompletedProcess([], 1, secret, 'ConfigException: ' + secret)
        with patch.object(kafka.subprocess, 'run', return_value=result), self.assertRaises(kafka.Failed) as failed:
            smoke.docker('compose', '--env-file', '/private/' + secret, 'run',
                         'kafka-broker-3', secret, input=secret,
                         env={'KAFKA_CLIENT_PRINCIPAL': 'admin', 'PASSWORD': secret})
        message = str(failed.exception)
        for expected in ('phase=storage-identity', 'operation=compose', 'subcommand=run',
                         'principal=admin', 'node=kafka-broker-3', 'exit=1', 'errors=ConfigException'):
            self.assertIn(expected, message)
        self.assertNotIn(secret, message)
        self.assertNotIn('/private/', message)
        self.assertNotIn('PASSWORD', message)

    def test_diagnostics_reject_unrecognized_labels_and_unclassified_output(self):
        secret = 'synthetic-sensitive-label'
        result = subprocess.CompletedProcess([], secret, secret, 'UnknownError: ' + secret)
        message = kafka.safe_docker_failure((secret, '/private/' + secret), result, secret,
                                           {'KAFKA_CLIENT_PRINCIPAL': secret})
        self.assertEqual(message, 'Docker failure: phase=unknown operation=unknown subcommand=none '
                         'principal=none node=none exit=unknown errors=unclassified')
        smoke = kafka.Smoke.__new__(kafka.Smoke)
        with patch('builtins.print') as output, self.assertRaises(kafka.Failed):
            smoke.stage(secret)
        output.assert_not_called()
        with patch('builtins.print') as output:
            smoke.stage('cold-restore')
        output.assert_called_once_with('Kafka phase: cold-restore', flush=True)

    def test_timeout_diagnostics_do_not_expose_command_or_captured_output(self):
        smoke = kafka.Smoke.__new__(kafka.Smoke)
        smoke.env = {}
        smoke.phase = 'cold-restore'
        secret = 'synthetic-sensitive-timeout-command'
        error = subprocess.TimeoutExpired([secret], 1, output=secret, stderr=secret)
        with patch.object(kafka.subprocess, 'run', side_effect=error), self.assertRaises(kafka.Failed) as failed:
            smoke.docker('start', secret)
        self.assertIn('phase=cold-restore operation=start', str(failed.exception))
        self.assertIn('exit=-1 errors=TimeoutException', str(failed.exception))
        self.assertNotIn(secret, str(failed.exception))

    def test_snapshot_diagnostics_allow_only_fixed_stage_and_guard_reason(self):
        secret = 'synthetic-sensitive-path-and-payload'
        result = subprocess.CompletedProcess([], 1, secret,
                    'SNAPSHOT_FAILURE_SOURCE_INVENTORY\nSnapshot ownership mismatch\n' + secret)
        message = kafka.safe_snapshot_failure(result, 'archive', 'kafka-controller-1')
        self.assertIn('stage=SOURCE_INVENTORY reason=OWNERSHIP exit=1', message)
        self.assertNotIn(secret, message)
        result = subprocess.CompletedProcess([], 1, secret, 'SNAPSHOT_FAILURE_' + secret)
        message = kafka.safe_snapshot_failure(result, secret, secret)
        self.assertIn('mode=unknown node=unknown stage=UNKNOWN reason=UNCLASSIFIED', message)
        self.assertNotIn(secret, message)

    def test_freshness_rejects_existing_resources_without_cleanup(self):
        fixture = Fixture()
        fixture.smoke.owned = False
        with self.assertRaises(kafka.Failed):
            fixture.smoke.run()
        fixture.smoke.cleanup()
        self.assertEqual(fixture.mutations, [])

    def test_unlabelled_name_collision_is_rejected_before_creation(self):
        for kind in ('ps', 'volume', 'network'):
            fixture = Fixture()
            fixture.smoke.owned = False
            fixture.smoke.resources = lambda: ([], [], [])
            calls = []
            def named(*args, **kwargs):
                calls.append(args)
                return subprocess.CompletedProcess(args, 0, 'foreign' if args[0] == kind else '', '')
            fixture.smoke.docker = named
            with self.subTest(kind=kind), self.assertRaises(kafka.Failed):
                fixture.smoke.run()
            fixture.smoke.cleanup()
            self.assertFalse(fixture.smoke.owned)
            self.assertTrue(all(command[0] in ('ps', 'volume', 'network') for command in calls))

    def test_valid_inventory_and_exact_cleanup(self):
        fixture = Fixture()
        self.assertEqual(set(fixture.smoke.inventory()[3]), set(kafka.NODES))
        fixture.smoke.cleanup()
        self.assertEqual([call[:2] for call in fixture.mutations],
                         [('rm', '--force'), ('volume', 'rm'), ('network', 'rm')])
        self.assertFalse(fixture.smoke.owned)

    def test_all_ownership_failures_block_every_cleanup_mutation(self):
        changes = [
            lambda f: next(iter(f.nodes.values()))['Config']['Labels'].update({'com.docker.compose.project': 'bank'}),
            lambda f: next(iter(f.nodes.values()))['HostConfig'].update({'PortBindings': {'9092/tcp': []}}),
            lambda f: next(iter(f.nodes.values()))['HostConfig'].update({'Privileged': True}),
            lambda f: next(iter(f.nodes.values()))['HostConfig'].update({'NetworkMode': 'host'}),
            lambda f: next(iter(f.nodes.values()))['Mounts'][0].update({'RW': True}),
            lambda f: next(iter(f.nodes.values()))['Mounts'][1].update({'Source': '/production/credentials'}),
            lambda f: next(iter(f.nodes.values()))['Mounts'][2].update({'Name': 'bank_data'}),
            lambda f: next(iter(f.nodes.values()))['Config']['Labels'].update({'com.docker.compose.project.config_files': '/other/compose.yaml'}),
            lambda f: next(iter(f.volumes.values()))['Labels'].update({'com.docker.compose.project': 'bank'}),
            lambda f: next(iter(f.volumes.values())).update({'Options': {'device': '/host/path', 'o': 'bind'}}),
            lambda f: next(iter(f.networks.values())).update({'Internal': False}),
            lambda f: next(iter(f.networks.values()))['Containers'].update({'f' * 64: {}}),
            lambda f: f.smoke.known.update({kafka.NODES[0]: 'e' * 64}),
        ]
        for index, change in enumerate(changes):
            fixture = Fixture()
            change(fixture)
            with self.subTest(case=index), self.assertRaises(kafka.Failed):
                fixture.smoke.cleanup()
            self.assertEqual(fixture.mutations, [])

    def test_partial_startup_can_clean_only_validated_resources(self):
        fixture = Fixture()
        fixture.smoke.known = None
        fixture.nodes = dict(list(fixture.nodes.items())[:1])
        next(iter(fixture.networks.values()))['Containers'] = {cid: {} for cid in fixture.nodes}
        fixture.smoke.cleanup()
        self.assertEqual(len(fixture.mutations), 3)

    def test_cleanup_failure_stops_following_deletions(self):
        fixture = Fixture()
        original = fixture.smoke.docker
        def fail_remove(*args, **kwargs):
            if args[0] == 'rm':
                raise kafka.Failed('synthetic remove failure')
            return original(*args, **kwargs)
        fixture.smoke.docker = fail_remove
        with self.assertRaises(kafka.Failed):
            fixture.smoke.cleanup()
        self.assertTrue(fixture.volumes)
        self.assertTrue(fixture.networks)
        self.assertTrue(fixture.smoke.owned)

    def test_timeout_does_not_count_as_broker_rejection(self):
        for output in ('TimeoutException', 'Connection refused', 'command not found', ''):
            with self.subTest(output=output), self.assertRaises(kafka.Failed):
                kafka.rejected(subprocess.CompletedProcess([], 1, '', output), 'NotEnoughReplicas')
        with self.assertRaises(kafka.Failed):
            kafka.rejected(subprocess.CompletedProcess([], 0, '', 'NotEnoughReplicasException'), 'NotEnoughReplicas')
        kafka.rejected(subprocess.CompletedProcess([], 1, '', 'NotEnoughReplicasException'), 'NotEnoughReplicas')

    def test_minimum_isr_enum_requires_failed_send_and_explicit_broker_response(self):
        for enum in ('NOT_ENOUGH_REPLICAS', 'NOT_ENOUGH_REPLICAS_AFTER_APPEND'):
            output = 'WARN Error: ' + enum + '\nTimeoutException: delivery expired'
            kafka.rejected(subprocess.CompletedProcess([], 1, '', output), 'NotEnoughReplicas')
            with self.subTest(enum=enum), self.assertRaises(kafka.Failed):
                kafka.rejected(subprocess.CompletedProcess([], 0, '', output), 'NotEnoughReplicas')
        for output in ('TimeoutException', 'NOT_ENOUGH_REPLICAS_UNKNOWN', 'XNOT_ENOUGH_REPLICAS', ''):
            with self.subTest(output=output), self.assertRaises(kafka.Failed):
                kafka.rejected(subprocess.CompletedProcess([], 1, '', output), 'NotEnoughReplicas')

    def test_denied_consumer_accepts_zero_exit_only_with_explicit_denial_and_no_records(self):
        for code in (0, 1):
            for error in ('TopicAuthorizationException', 'GroupAuthorizationException'):
                kafka.denied_consumer(subprocess.CompletedProcess([], code, '', error))
        for code, stdout, stderr in (
                (0, 'payment-id:synthetic-record\n', 'TopicAuthorizationException'),
                (1, 'payment-id:synthetic-record\n', 'GroupAuthorizationException'),
                (0, '', 'TimeoutException'), (1, '', 'TimeoutException'),
                (0, '', ''), (1, '', 'Connection refused'),
                (0, 'TopicAuthorizationException', ''),
                (0, '', 'TopicAuthorizationExceptionUnexpected')):
            with self.subTest(code=code, stdout=stdout, stderr=stderr), self.assertRaises(kafka.Failed):
                kafka.denied_consumer(subprocess.CompletedProcess([], code, stdout, stderr))

    def test_rejection_diagnostics_identify_probe_without_exposing_output(self):
        result = subprocess.CompletedProcess([], 1, 'synthetic-sensitive-payload', 'synthetic-sensitive-error')
        with self.assertRaises(kafka.Failed) as failed:
            kafka.rejected(result, 'NotEnoughReplicas', 'minimum-ISR produce')
        self.assertIn('minimum-ISR produce', str(failed.exception))
        self.assertNotIn('synthetic-sensitive', str(failed.exception))
        with self.assertRaises(kafka.Failed) as failed:
            kafka.denied_consumer(result, 'payment cross-topic consume')
        self.assertIn('payment cross-topic consume', str(failed.exception))
        self.assertNotIn('synthetic-sensitive', str(failed.exception))

    def test_partition_states_require_exact_isr_and_topic_settings(self):
        for isr in ('101,102,103', '102,103', '103'):
            kafka.topic_state(topics(isr), set(isr.split(',')))
        for text in (topics().replace('min.insync.replicas=2', 'min.insync.replicas=1'),
                     topics().replace('ReplicationFactor: 3', 'ReplicationFactor: 1'),
                     topics().replace('Replicas: 101,102,103', 'Replicas: 101,102,103,103'),
                     topics().replace('Isr: 101,102,103', 'Isr: 101,102,103,103'),
                     topics().replace('Leader: 101', 'Leader: -1'), topics('103'),
                     topics().replace('Partition: 2', 'Partition: 1')):
            with self.assertRaises(kafka.Failed):
                kafka.topic_state(text, {'101', '102', '103'})

    def test_tls_requires_explicit_authentication_failure(self):
        for code, output in ((0, 'SSLHandshakeException'), (1, 'TimeoutException'), (1, 'Connection refused')):
            with self.subTest(code=code, output=output), self.assertRaises(kafka.Failed):
                kafka.require_tls_rejection(subprocess.CompletedProcess([], code, '', output))
        kafka.require_tls_rejection(subprocess.CompletedProcess([], 1, '', 'SslAuthenticationException'))

    def test_controller_failure_restores_node_when_publish_fails(self):
        fixture = Fixture()
        calls = []
        fixture.smoke.probe = lambda *a, **kw: subprocess.CompletedProcess([], 0, 'LeaderId: 2', '')
        fixture.smoke.change = lambda *args: calls.append(args)
        fixture.smoke.eventually = lambda action, label: action()
        fixture.smoke.quorum = lambda cluster, stopped=(): calls.append(('quorum', stopped))
        fixture.smoke.wait_isr = lambda ids: None
        def fail_publish(label):
            raise kafka.Failed('synthetic failed publish')
        with self.assertRaises(kafka.Failed):
            fixture.smoke.controller_failure('synthetic-cluster', fail_publish)
        self.assertEqual(calls, [('stop', 'kafka-controller-2'),
                                 ('quorum', ('kafka-controller-2',)), ('start', 'kafka-controller-2')])

    def test_wrong_ca_probe_requires_tls_denial_then_authorized_readiness(self):
        fixture = Fixture()
        calls = []
        def probe(*args, **kwargs):
            calls.append((args, kwargs))
            return subprocess.CompletedProcess([], 1, '', 'SSLHandshakeException')
        fixture.smoke.probe = probe
        fixture.smoke.invalid_tls()
        self.assertIn('ssl.truststore.location=/tmp/unrelated-trust.p12', calls[0][0][3])
        self.assertIn('-importcert', calls[0][0][3])
        self.assertTrue(calls[0][1]['raw'])
        self.assertFalse(calls[0][1]['check'])
        self.assertEqual(calls[1][0], ('admin', 'topics'))
        fixture.smoke.probe = lambda *a, **kw: subprocess.CompletedProcess([], 1, '', 'TimeoutException')
        with self.assertRaises(kafka.Failed):
            fixture.smoke.invalid_tls()

    def test_cold_copy_refuses_any_running_node(self):
        fixture = Fixture()
        for item in fixture.nodes.values():
            item['State']['Running'] = False
        fixture.smoke.stopped()
        for item in fixture.nodes.values():
            item['State']['Running'] = True
            with self.assertRaisesRegex(kafka.Failed, 'all six nodes stopped'):
                fixture.smoke.stopped()
            item['State']['Running'] = False

    def test_end_offsets_require_all_partitions_and_no_duplicates(self):
        fixture = Fixture()
        fixture.smoke.probe = lambda *a, **kw: subprocess.CompletedProcess(
            [], 0, '\n'.join(a[7] + ':' + str(n) + ':2' for n in range(3)), '')
        self.assertEqual(len(fixture.smoke.offsets()), 9)
        for output in ('foreign:0:2', kafka.TOPICS[0] + ':0:2\n' + kafka.TOPICS[0] + ':0:2', ''):
            fixture.smoke.probe = lambda *a, **kw: subprocess.CompletedProcess([], 0, output, '')
            with self.assertRaises(kafka.Failed):
                fixture.smoke.offsets()

    def test_snapshot_helper_exact_mounts_and_failure_cleanup(self):
        fixture = Fixture()
        for item in fixture.nodes.values():
            item['State']['Running'] = False
        archive = Path('/work/private/cold-snapshot')
        image = 'apache/kafka:4.3.1@sha256:' + 'c' * 64
        cid = 'd' * 64
        calls = []
        helper = {}
        foreign = False
        exit_code = 0
        confirmation = 'unconfirmed'
        missing_mask = False
        unexpected_mount = False
        def docker(*args, **kwargs):
            calls.append(args)
            if args[0] == 'create':
                helper.update({'Name': '/' + args[2], 'Config': {
                    'Image': image, 'User': '1000:1000', 'Entrypoint': ['bash'],
                    'Cmd': list(args[args.index(image) + 1:]),
                    'Labels': {'armoney.kafka.snapshot.project': PROJECT}},
                    'State': {'Running': False, 'ExitCode': exit_code},
                    'HostConfig': {'NetworkMode': 'none', 'ReadonlyRootfs': True,
                                   'CapDrop': ['ALL'], 'SecurityOpt': ['no-new-privileges']},
                    'Mounts': [
                        {'Type': 'bind', 'Source': str(ROOT / 'infra/kafka'), 'Destination': '/opt/armoney', 'RW': False},
                        {'Type': 'bind', 'Source': str(archive), 'Destination': '/archive', 'RW': True},
                        {'Type': 'volume', 'Name': PROJECT + '_kafka-broker-1-data', 'Destination': '/source', 'RW': False}]})
                # Model Docker's pinned-image VOLUME declarations: uncovered
                # destinations create anonymous persistent mounts automatically.
                tmpfs = {args[n + 1].split(':', 1)[0] for n, value in enumerate(args) if value == '--tmpfs'}
                declared = {'/etc/kafka/secrets', '/mnt/shared/config', '/var/lib/kafka/data'}
                self.assertTrue(declared <= tmpfs)
                self.assertIn('/var/lib/kafka/data:rw,size=1m,uid=1000,gid=1000,mode=0700', args)
                if missing_mask:
                    tmpfs.remove('/var/lib/kafka/data')
                for destination in declared - tmpfs:
                    helper['Mounts'].append({'Type': 'volume', 'Name': 'anonymous-image-volume',
                                             'Destination': destination, 'RW': True})
                if unexpected_mount:
                    helper['Mounts'].append({'Type': 'volume', 'Name': 'foreign-volume',
                                             'Destination': '/unexpected', 'RW': True})
                return subprocess.CompletedProcess(args, 0, cid, '')
            if args[0] == 'inspect' and args[1] == cid:
                if foreign:
                    helper['Mounts'][2]['Name'] = 'persistent-bank-volume'
                return subprocess.CompletedProcess(args, 0, json.dumps([helper]), '')
            if args[0] == 'start':
                return subprocess.CompletedProcess(args, 0, 'e' * 64 if exit_code else confirmation, '')
            if args[0] == 'rm':
                return subprocess.CompletedProcess(args, 0, '', '')
            return fixture.docker(*args, **kwargs)
        fixture.smoke.docker = docker
        with self.assertRaisesRegex(kafka.Failed, 'confirmation missing'):
            fixture.smoke.snapshot('archive', 'kafka-broker-1', 'synthetic-cluster', archive, image)
        self.assertIn(('rm', '--force', cid), calls)
        self.assertIn('--read-only', calls[[c[0] for c in calls].index('create')])
        foreign = True
        calls.clear()
        with self.assertRaisesRegex(kafka.Failed, 'Foreign snapshot helper mount'):
            fixture.smoke.snapshot('archive', 'kafka-broker-1', 'synthetic-cluster', archive, image)
        self.assertFalse(any(call[0] in ('start', 'rm') for call in calls))
        foreign = False
        exit_code = 1
        calls.clear()
        with self.assertRaisesRegex(kafka.Failed, 'did not complete successfully'):
            fixture.smoke.snapshot('archive', 'kafka-broker-1', 'synthetic-cluster', archive, image)
        self.assertIn(('rm', '--force', cid), calls)

        exit_code = 0
        confirmation = 'e' * 64
        calls.clear()
        self.assertEqual('e' * 64, fixture.smoke.snapshot('archive', 'kafka-broker-1',
                                                       'synthetic-cluster', archive, image))
        self.assertIn(('rm', '--force', cid), calls)
        for remove_mask in (True, False):
            missing_mask, unexpected_mount = remove_mask, not remove_mask
            calls.clear()
            with self.subTest(missing_mask=remove_mask), self.assertRaisesRegex(kafka.Failed, 'Foreign snapshot helper mount'):
                fixture.smoke.snapshot('archive', 'kafka-broker-1', 'synthetic-cluster', archive, image)
            self.assertFalse(any(call[0] in ('start', 'rm') for call in calls))

    def test_cleanup_failure_preserves_primary_exception_without_exposing_cleanup_details(self):
        primary = kafka.Failed('Foreign snapshot helper mount')
        secret = 'synthetic-secret-command-path-and-credentials'
        def cleanup():
            raise RuntimeError(secret)
        with patch('builtins.print') as output, self.assertRaises(kafka.Failed) as failure:
            with kafka.preserving_cleanup(cleanup):
                raise primary
        self.assertIs(primary, failure.exception)
        output.assert_called_once_with('Kafka cleanup also failed: category=unclassified; primary failure preserved',
                                       file=sys.stderr)
        self.assertNotIn(secret, str(output.call_args))

    def test_cleanup_failure_after_success_still_fails_and_success_cleans_once(self):
        failure = kafka.Failed('synthetic cleanup refusal')
        def cleanup():
            raise failure
        with self.assertRaises(kafka.Failed) as caught:
            with kafka.preserving_cleanup(cleanup):
                pass
        self.assertIs(failure, caught.exception)
        calls = []
        with kafka.preserving_cleanup(lambda: calls.append('cleanup')):
            calls.append('body')
        self.assertEqual(['body', 'cleanup'], calls)

    def test_storage_probe_refuses_live_volume_and_unconfirmed_restore(self):
        for running in (True, False):
            fixture = Fixture()
            calls = []
            node = fixture.nodes[fixture.smoke.known['kafka-broker-3']]
            node['State']['Running'] = running
            fixture.smoke.change = lambda *args: calls.append(args)
            fixture.smoke.compose = lambda *a, **kw: subprocess.CompletedProcess([], 0, 'unconfirmed', '')
            with self.subTest(running=running), self.assertRaises(kafka.Failed):
                fixture.smoke.storage_negative()
            self.assertEqual(calls, [('stop', 'kafka-broker-3')])

    def test_consumption_reconciles_records_not_process_success(self):
        fixture = Fixture()
        for output in ('', 'expected\nexpected\n', 'expected\nforeign\n'):
            fixture.smoke.probe = lambda *a, **kw: subprocess.CompletedProcess([], 0, output, '')
            with self.assertRaises(kafka.Failed):
                fixture.smoke.consume('ledger', kafka.TOPICS[0], 'ledger-transfer-v1', ['expected'])
        fixture.smoke.probe = lambda *a, **kw: subprocess.CompletedProcess([], 0, 'expected\nuncertain\n', '')
        actual = fixture.smoke.consume('ledger', kafka.TOPICS[0], 'ledger-transfer-v1', ['expected'], ['uncertain'])
        self.assertEqual(actual['uncertain'], 1)

    def test_consumer_authorization_error_cannot_be_hidden_by_timeout(self):
        fixture = Fixture()
        fixture.smoke.probe = lambda *a, **kw: subprocess.CompletedProcess(
            [], 1, 'expected\nTopicAuthorizationException', 'TimeoutException')
        with self.assertRaisesRegex(kafka.Failed, 'authorization failure'):
            fixture.smoke.consume('ledger', kafka.TOPICS[0], 'ledger-transfer-v1', ['expected'])


class StorageGate(unittest.TestCase):
    """Run the actual metadata gate and restoration shell against synthetic files."""
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='armoney-kafka-storage-guard-')
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.data = self.root / 'data'
        self.data.mkdir()
        self.original = 'cluster.id=synthetic-cluster\nnode.id=103\nversion=1\n'
        (self.data / 'meta.properties').write_text(self.original)
        (self.root / 'cluster.id').write_text('synthetic-cluster\n')
        (self.root / 'prepare.sh').write_text('exit 0\n')
        (self.root / 'server.sh').write_text('touch "' + (self.root / 'started').as_posix() + '"\n')
        source = (kafka.ROOT / 'infra/kafka/start.sh').read_text()
        for before, after in {
            '/opt/armoney/prepare-config.sh': (self.root / 'prepare.sh').as_posix(),
            '/var/lib/kafka/data': self.data.as_posix(),
            '/run/kafka-secrets/cluster.id': (self.root / 'cluster.id').as_posix(),
            '/opt/kafka/bin/kafka-server-start.sh': (self.root / 'server.sh').as_posix(),
        }.items():
            self.assertIn(before, source)
            source = source.replace(before, after)
        self.start = self.root / 'start.sh'
        self.start.write_text(source, newline='\n')
        self.script = kafka.storage_probe_script().replace('/var/lib/kafka/data', self.data.as_posix()).replace(
            '/opt/armoney/start.sh', self.start.as_posix())
        self.bash = os.environ.get('KAFKA_GUARD_BASH') or (
            'C:/Program Files/Git/bin/bash.exe' if os.name == 'nt' else shutil.which('bash'))
        self.assertTrue(self.bash and Path(self.bash).is_file(), 'Bash required for metadata gate regression')

    def run_probe(self, mode):
        return subprocess.run([self.bash, '-s', '--', mode], input=self.script, text=True,
                              capture_output=True, timeout=30, env=dict(os.environ, KAFKA_NODE_ID='103'))

    def assert_restored(self):
        self.assertEqual((self.data / 'meta.properties').read_text(), self.original)
        self.assertFalse((self.data / 'meta.properties.armoney-smoke-backup').exists())
        self.assertFalse((self.root / 'started').exists())

    def test_missing_and_mismatched_metadata_reject_and_restore_exact_bytes(self):
        for mode in ('missing', 'cluster', 'node'):
            with self.subTest(mode=mode):
                result = self.run_probe(mode)
                self.assertEqual(result.returncode, 0, result.stderr)
                self.assertEqual(result.stdout.strip(), 'STARTUP_REJECTED_AND_RESTORED_' + mode)
                self.assert_restored()

    def test_unexpected_success_and_timeout_do_not_pass_but_restore_metadata(self):
        for code in (0, 124, 137):
            with self.subTest(code=code):
                self.start.write_text('exit ' + str(code) + '\n')
                result = self.run_probe('missing')
                self.assertNotEqual(result.returncode, 0)
                self.assert_restored()

    def test_startup_silent_metadata_repair_is_rejected(self):
        self.start.write_text('printf repaired > "' + (self.data / 'meta.properties').as_posix() + '"\n'
                             "echo 'Kafka storage is not initialized; explicit format or recovery required'\nexit 1\n")
        result = self.run_probe('missing')
        self.assertNotEqual(result.returncode, 0)
        self.assert_restored()


class FormatDiagnostics(unittest.TestCase):
    def test_storage_tool_categories_are_finite_and_private_text_is_withheld(self):
        secret = 'synthetic-password=/private/config.properties?token=do-not-print'
        cases = {
            'java.lang.OutOfMemoryError: Java heap space': 'OOM',
            'java.io.IOException: No space left on device': 'DISK',
            'java.nio.file.AccessDeniedException:': 'PERMISSION',
            'error: unrecognized arguments: --unsupported': 'UNSUPPORTED_FLAG',
            'org.apache.kafka.common.config.ConfigException:': 'INVALID_CONFIG',
            'java.lang.NullPointerException:': 'JAVA_RUNTIME',
            'java.io.IOException:': 'IO',
            'org.apache.kafka.common.KafkaException:': 'KAFKA',
            secret: 'UNKNOWN',
        }
        for message, category in cases.items():
            with self.subTest(category=category):
                result = subprocess.CompletedProcess([], 17, secret,
                    'ARMONEY_KAFKA_FORMAT_STAGE=STORAGE_TOOL\n' + message + ' ' + secret)
                output = self.diagnostic(result)
                self.assertIn('storage_tool_category=' + category, output)
                self.assertIn('format_stage=STORAGE_TOOL format_reason=UNCLASSIFIED', output)
                self.assertIn('exit=17', output)
                self.assertNotIn(secret, output)
                self.assertNotIn('--unsupported', output)

    def test_storage_tool_categories_do_not_apply_outside_storage_tool(self):
        for phase, stage in (('baseline', 'STORAGE_TOOL'), ('format', 'CONFIG')):
            result = subprocess.CompletedProcess([], 17, '',
                'ARMONEY_KAFKA_FORMAT_STAGE=' + stage + '\njava.lang.OutOfMemoryError: private')
            output = self.diagnostic(result, phase)
            self.assertNotIn('storage_tool_category=', output)
            self.assertIn('exit=17', output)
            self.assertNotIn('private', output)

    def diagnostic(self, result, phase='format'):
        return kafka.safe_docker_failure(
            ['compose', 'run', 'kafka-controller-1'], result, phase,
            {'KAFKA_CLIENT_PRINCIPAL': 'denied'})

    def test_only_complete_allowlisted_stage_and_guard_messages_are_returned(self):
        secret = 'synthetic-private-password-and-client-properties'
        result = subprocess.CompletedProcess([], 1, secret,
            'ARMONEY_KAFKA_FORMAT_STAGE=CONFIG\n'
            'ARMONEY_KAFKA_FORMAT_STAGE=OWNERSHIP\n'
            'Storage preparation requires uid/gid 1000\n' + secret)
        output = self.diagnostic(result)
        self.assertIn('format_stage=OWNERSHIP format_reason=UID_GID', output)
        self.assertNotIn(secret, output)
        self.assertIn('exit=1', output)

    def test_unknown_or_decorated_labels_never_become_diagnostic_values(self):
        secret = 'unsafe-$(command)-password'
        for line in ('ARMONEY_KAFKA_FORMAT_STAGE=' + secret,
                     'ARMONEY_KAFKA_FORMAT_STAGE=CONFIG ' + secret,
                     'prefix ARMONEY_KAFKA_FORMAT_STAGE=CONFIG',
                     'Required node configuration missing ' + secret):
            with self.subTest(line=line):
                output = self.diagnostic(subprocess.CompletedProcess([], 1, '', line))
                self.assertIn('format_stage=UNKNOWN format_reason=UNCLASSIFIED', output)
                self.assertNotIn(secret, output)

    def test_format_labels_cannot_classify_an_unrelated_phase_or_override_failure(self):
        result = subprocess.CompletedProcess([], 17, '',
            'ARMONEY_KAFKA_FORMAT_STAGE=STORAGE_TOOL\nInvalid cluster identity')
        output = self.diagnostic(result, 'baseline')
        self.assertNotIn('format_stage=', output)
        self.assertNotIn('format_reason=', output)
        self.assertIn('exit=17', output)

    def test_actual_format_script_config_failure_is_finite_and_redacted(self):
        self.run_format_failure('CONFIG', 'UNCLASSIFIED', 'config')

    def test_actual_format_script_cluster_guard_keeps_its_failure(self):
        self.run_format_failure('CLUSTER_ID', 'CLUSTER_ID', 'cluster')

    def test_actual_format_script_nonempty_storage_guard_keeps_its_failure(self):
        self.run_format_failure('EMPTY_ROOT', 'NONEMPTY_STORAGE', 'nonempty')

    def test_actual_format_script_ownership_guard_keeps_its_failure(self):
        self.run_format_failure('OWNERSHIP', 'UID_GID', 'ownership')

    def test_actual_format_script_storage_tool_failure_is_not_reclassified_as_success(self):
        self.run_format_failure('STORAGE_TOOL', 'UNCLASSIFIED', 'tool')

    def run_format_failure(self, stage, reason, failure):
        secret = 'synthetic-private-format-output'
        bash = os.environ.get('KAFKA_GUARD_BASH') or (
            'C:/Program Files/Git/bin/bash.exe' if os.name == 'nt' else shutil.which('bash'))
        self.assertTrue(bash and Path(bash).is_file(), 'Bash required for actual format regression')
        with tempfile.TemporaryDirectory(prefix='armoney-kafka-format-') as directory:
            root = Path(directory)
            data = root / 'data'
            data.mkdir()
            (root / 'prepare.sh').write_text(
                "printf '%s\\n' '" + secret + "' >&2; exit 1\n" if failure == 'config' else 'exit 0\n', newline='\n')
            (root / 'cluster.id').write_text(secret if failure == 'cluster' else 'A' * 22, newline='\n')
            (root / 'empty.sh').write_text(
                "echo 'Storage preparation requires uid/gid 1000' >&2; exit 1\n" if failure == 'ownership' else 'exit 0\n', newline='\n')
            (root / 'tool.sh').write_text("#!/bin/bash\nprintf '%s\\n' '" + secret + "' >&2; exit 17\n", newline='\n')
            (root / 'tool.sh').chmod(0o700)
            if failure == 'nonempty':
                (data / 'unrecognized').write_text('preserved synthetic data')
            source = (kafka.ROOT / 'infra/kafka/format.sh').read_text()
            for before, after in {
                '/opt/armoney/prepare-config.sh': (root / 'prepare.sh').as_posix(),
                '/run/kafka-secrets/cluster.id': (root / 'cluster.id').as_posix(),
                '/var/lib/kafka/data': data.as_posix(),
                '/opt/armoney/prepare-empty-storage.sh': (root / 'empty.sh').as_posix(),
                '/opt/kafka/bin/kafka-storage.sh': (root / 'tool.sh').as_posix(),
            }.items():
                self.assertIn(before, source)
                source = source.replace(before, after)
            result = subprocess.run([bash, '-s'], input=source, capture_output=True, text=True,
                                    timeout=15, env=dict(os.environ, KAFKA_NODE_ROLE='broker'))
            self.assertEqual(result.returncode, 17 if failure == 'tool' else 1)
            output = self.diagnostic(result)
            self.assertIn('format_stage=' + stage + ' format_reason=' + reason, output)
            self.assertNotIn(secret, output)
            self.assertNotIn(str(root), output)
            if failure == 'nonempty':
                self.assertEqual((data / 'unrecognized').read_text(), 'preserved synthetic data')


if __name__ == '__main__':
    unittest.main(verbosity=2)
