"""Offline metrics deployment/acceptance guards; never executes Docker."""
import copy
import importlib.util
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[1]
loader = importlib.util.spec_from_file_location('metrics_smoke', ROOT / 'scripts/metrics-smoke.py')
smoke = importlib.util.module_from_spec(loader)
loader.loader.exec_module(smoke)
PROJECT = 'armoney-metrics-test-' + 'a' * 32


def inventory():
    result = []
    for index, service in enumerate(smoke.SERVICES + ('payment-db', 'metrics-collector', 'edge')):
        result.append({'Id': format(index + 1, '064x'), 'State': {'Running': True},
            'Config': {'User': '10001:10001', 'Labels': {
                'com.docker.compose.project': PROJECT, 'com.docker.compose.service': service,
                'com.armoney.metrics.disposable': 'true'}},
            'HostConfig': {'PortBindings': {}, 'Privileged': False, 'ReadonlyRootfs': True},
            'NetworkSettings': {'Networks': {PROJECT + '_bank': {}}}, 'Mounts': []})
    result[-1]['HostConfig']['PortBindings'] = {'443/tcp': [{'HostIp': '127.0.0.1', 'HostPort': '18443'}]}
    return result


class MetricsGuards(unittest.TestCase):
    def test_exact_isolated_guard_accepts_fixture_but_rejects_context_override(self):
        environment = {'DOCKER_HOST': 'unix:///run/inner-docker/docker.sock',
                       'ARMONEY_DISPOSABLE_CI': 'true', 'GITHUB_WORKSPACE': '/work/fixture'}
        with patch.object(Path, 'exists', return_value=False):
            smoke.guard(PROJECT, environment, Path('/work/fixture'))
            for name in ('DOCKER_CONTEXT', 'DOCKER_TLS_VERIFY', 'DOCKER_CERT_PATH'):
                with self.subTest(name=name), self.assertRaises(ValueError):
                    smoke.guard(PROJECT, {**environment, name: 'untrusted'}, Path('/work/fixture'))

    def test_explicit_ci_marker_and_exact_daemon_workspace_required(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            environment = {'DOCKER_HOST': 'unix:///run/inner-docker/docker.sock',
                           'ARMONEY_DISPOSABLE_CI': 'true', 'GITHUB_WORKSPACE': '/work/fixture'}
            # Windows/local paths cannot impersonate the isolated workspace.
            with self.assertRaises(ValueError):
                smoke.guard(PROJECT, environment, root)
            for missing in environment:
                with self.subTest(missing=missing), self.assertRaises(ValueError):
                    smoke.guard(PROJECT, {key: value for key, value in environment.items() if key != missing}, root)

    def test_persistent_and_ambiguous_projects_fail_before_docker(self):
        for project in ('arman-bank', 'armoney-metrics-test-ci', PROJECT + '-extra', ''):
            with self.subTest(project=project), self.assertRaises(ValueError):
                smoke.guard(project, {'CI': 'true'})

    def test_owned_running_inventory_is_accepted(self):
        self.assertEqual(set(smoke.validate_inventory(inventory(), PROJECT)),
                         set(smoke.SERVICES + ('payment-db', 'metrics-collector', 'edge')))

    def test_foreign_missing_duplicate_and_stopped_containers_fail(self):
        variants = []
        foreign = inventory(); foreign[0]['Config']['Labels']['com.docker.compose.project'] = 'arman-bank'; variants.append(foreign)
        variants.append(inventory()[:-1])
        variants.append(inventory() + [copy.deepcopy(inventory()[0])])
        stopped = inventory(); stopped[0]['State']['Running'] = False; variants.append(stopped)
        for items in variants:
            with self.assertRaises(ValueError):
                smoke.validate_inventory(items, PROJECT)

    def test_collector_must_be_private_unprivileged_readonly_and_marked(self):
        for field, value in (('PortBindings', {'8080/tcp': [{'HostIp': '0.0.0.0', 'HostPort': '8080'}]}),
                             ('Privileged', True), ('ReadonlyRootfs', False)):
            items = inventory(); items[-2]['HostConfig'][field] = value
            with self.subTest(field=field), self.assertRaises(ValueError):
                smoke.validate_inventory(items, PROJECT)
        items = inventory(); items[-2]['Config']['Labels']['com.armoney.metrics.disposable'] = 'false'
        with self.assertRaises(ValueError):
            smoke.validate_inventory(items, PROJECT)

    def test_service_listener_bindings_cannot_be_public(self):
        for index in (0, 1):
            items = inventory()
            items[index]['HostConfig']['PortBindings'] = {'8080/tcp': [{'HostIp': '0.0.0.0', 'HostPort': '8080'}]}
            with self.assertRaises(ValueError):
                smoke.validate_inventory(items, PROJECT)

    def test_edge_requires_owned_loopback_https_binding(self):
        edge = inventory()[-1]
        self.assertEqual(smoke.edge_target('https://localhost:18443', edge), 'https://localhost:18443/internal/metrics')
        for url in ('http://localhost:18443', 'https://example.com:18443', 'https://localhost:8443',
                    'https://localhost:18443?secret=x', 'https://user@localhost:18443', 'https://localhost:18443/other'):
            with self.subTest(url=url), self.assertRaises(ValueError):
                smoke.edge_target(url, edge)

    def test_command_is_bounded_and_failure_does_not_echo_diagnostics(self):
        acceptance = smoke.Acceptance(PROJECT)
        with patch.object(smoke.subprocess, 'run') as run:
            run.return_value.returncode = 1
            run.return_value.stdout = 'synthetic-sensitive-diagnostic'
            with self.assertRaises(ValueError) as failure:
                acceptance.run('inspect', 'fixture')
            self.assertNotIn('sensitive', str(failure.exception))
            self.assertLessEqual(run.call_args.kwargs['timeout'], 40)

    def test_probe_programs_compile_and_keep_credentials_out_of_arguments(self):
        acceptance = smoke.Acceptance(PROJECT)
        for failure, auth in ((False, False), (False, True), (True, False)):
            with patch.object(acceptance, 'run', return_value='PASS') as run:
                acceptance.probe('a' * 64, failure=failure, auth=auth)
                compile(run.call_args.kwargs['source'], '<metrics-probe>', 'exec')
                self.assertNotIn('http:', str(run.call_args.args))
                self.assertNotIn('INTERNAL_METRICS_KEY', str(run.call_args.args))

    def test_restore_remains_bounded_after_acceptance_deadline(self):
        acceptance = smoke.Acceptance(PROJECT)
        acceptance.deadline = 0
        with patch.object(smoke.subprocess, 'run') as run:
            run.return_value.returncode = 0
            acceptance.restore('a' * 64)
            self.assertEqual(run.call_args.args[0], ['docker', 'start', 'a' * 64])
            self.assertEqual(run.call_args.kwargs['timeout'], 40)

    def test_optin_configuration_keeps_secrets_out_of_command_and_ports_absent(self):
        text = (ROOT / 'compose.metrics.yaml').read_text()
        self.assertNotIn('ports:', text)
        self.assertNotIn('INTERNAL_AUTH_KEY', text)
        self.assertNotIn('INTERNAL_METRICS_KEY', next(line for line in text.splitlines() if 'command:' in line))
        self.assertEqual(text.count('INTERNAL_METRICS_KEY: ${INTERNAL_METRICS_KEY:?'), 7)
        for expected in ('read_only: true', 'create_host_path: false', 'cap_drop: [ALL]',
                         'no-new-privileges:true', 'user: "10001:10001"', 'METRICS_CONFIG_PATH:?', 'METRICS_STATUS_DIR:?'):
            self.assertIn(expected, text)
        self.assertNotIn('metrics-collector:', (ROOT / 'compose.yaml').read_text())


if __name__ == '__main__':
    unittest.main()
