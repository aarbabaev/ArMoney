"""Offline staging/cleanup policy checks; no Docker or privileged command runs."""
import json
from pathlib import Path
import types
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[1]
source = (ROOT / 'scripts/run-metrics-ci.sh').read_text().split("<<'PY'\n", 1)[1].rsplit('\nPY', 1)[0]
staging = types.ModuleType('metrics_ci_staging')
exec(compile(source, '<metrics-ci-staging>', 'exec'), staging.__dict__)
PROJECT = 'armoney-metrics-test-' + 'a' * 32


class StagingGuards(unittest.TestCase):
    def test_command_failure_distinguishes_nonzero_and_output_bound_without_leaks(self):
        private = 'https://private.invalid/token /private/path key=private'
        for stdout, stderr, code, category, reason in (
            ('', 'manifest unknown ' + private, 1, 'command_nonzero', 'image_unavailable'),
            ('', 'invalid mount config ' + private, 1, 'command_nonzero', 'mount_failure'),
            ('', 'unhealthy ' + private, 1, 'command_nonzero', 'container_unhealthy'),
            ('', 'exited ' + private, 1, 'command_nonzero', 'container_exited'),
            ('x' * 1048577, private, 0, 'output_bound', 'unknown'),
            ('', 'x' * 1048577, 1, 'output_bound', 'unknown')):
            with self.subTest(category=category, reason=reason):
                result = types.SimpleNamespace(stdout=stdout, stderr=stderr, returncode=code)
                with patch.object(staging.subprocess, 'run', return_value=result):
                    with self.assertRaises(staging.CommandFailure) as failure:
                        staging.command(['private-command'], {})
                self.assertEqual((failure.exception.category, failure.exception.reason), (category, reason))
                self.assertNotIn(private, str(failure.exception))

    def snapshot_fixture(self):
        return {'Id': 'a' * 64, 'Name': '/' + PROJECT + '-edge-1',
                'Config': {'Labels': {'com.docker.compose.project': PROJECT, 'com.docker.compose.service': 'edge'},
                           'Env': ['PRIVATE_KEY=secret']},
                'State': {'Status': 'exited', 'ExitCode': 1, 'Error': '/private/path',
                          'Health': {'Status': 'unhealthy', 'Log': [{'Output': 'private URL and key'}]}}}

    def test_create_snapshot_contains_only_finite_state_and_never_health_logs(self):
        item = self.snapshot_fixture()
        responses = [types.SimpleNamespace(stdout='a' * 12), types.SimpleNamespace(stdout=json.dumps([item]))]
        with patch.object(staging, 'command', side_effect=responses):
            result = staging.create_failure_snapshot(PROJECT, {}, staging.CommandFailure('command_nonzero', 'container_exited'))
        self.assertEqual(result['inspection'], 'checked')
        self.assertEqual(result['services'], [{'service': 'edge', 'state': 'exited', 'health': 'unhealthy', 'exit': 'nonzero'}])
        text = json.dumps(result)
        for private in ('PRIVATE_KEY', 'secret', '/private', 'private URL', PROJECT, 'a' * 12):
            self.assertNotIn(private, text)

    def test_snapshot_rejects_foreign_labels_names_services_and_identifiers(self):
        for field in ('label', 'name', 'service', 'id'):
            item = self.snapshot_fixture()
            if field == 'label': item['Config']['Labels']['com.docker.compose.project'] = 'arman-bank'
            if field == 'name': item['Name'] = '/private-foreign-container'
            if field == 'service': item['Config']['Labels']['com.docker.compose.service'] = 'private-foreign-service'
            if field == 'id': item['Id'] = 'b' * 64
            responses = [types.SimpleNamespace(stdout='a' * 12), types.SimpleNamespace(stdout=json.dumps([item]))]
            with self.subTest(field=field), patch.object(staging, 'command', side_effect=responses) as run:
                result = staging.create_failure_snapshot(PROJECT, {}, ValueError('private'))
            self.assertEqual(result['inspection'], 'rejected')
            self.assertEqual(result['services'], [])
            self.assertFalse(any('logs' in call.args[0] for call in run.call_args_list))

    def test_diagnostic_values_are_finite_and_exception_text_is_redacted(self):
        private = 'https://private.invalid/secret /private/token INTERNAL_METRICS_KEY=secret'
        for error, category in ((ValueError(private), 'prerequisite'), (OSError(private), 'transport'),
                                (AssertionError(private), 'assertion'), (RuntimeError(private), 'internal'),
                                (staging.subprocess.TimeoutExpired(private, 3), 'timeout')):
            item = staging.smoke.diagnostic(private, error, private)
            self.assertEqual(item['phase'], 'guard')
            self.assertEqual(item['category'], category)
            self.assertEqual(item['cleanup'], 'failed')
            self.assertNotIn(private, json.dumps(item))

    def test_cleanup_failure_preserves_original_failure_and_phase(self):
        private = 'private-command-key-url'
        def command(arguments, environment, **kwargs):
            if 'inspect' in arguments:
                return types.SimpleNamespace(stdout=json.dumps([{'Id': 'sha256:' + 'a' * 64}]))
            if 'up' in arguments:
                raise ValueError(private)
            return types.SimpleNamespace(stdout='', stderr='', returncode=0)
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / 'infra/observability').mkdir(parents=True)
            (root / 'infra/observability/collector.example.json').write_text('{}')
            with (patch.object(staging, 'ROOT', root), patch.object(staging.smoke, 'guard'),
                  patch.object(staging, 'inventory_empty'), patch.object(staging, 'change_owner'),
                  patch.object(staging, 'command', side_effect=command),
                  patch.object(staging, 'cleanup_owned', side_effect=OSError('private cleanup path'))):
                with self.assertRaisesRegex(ValueError, private):
                    staging.main()
            self.assertEqual(staging.STATE['phase'], 'create')
            self.assertEqual(staging.STATE['cleanup'], 'failed')

    def test_fixture_uses_exact_built_image_ids_and_removes_gateway_port(self):
        images = {service: 'sha256:' + 'a' * 64 for service in staging.smoke.SERVICES}
        fixture = staging.fixture_text(images, 18443, ROOT)
        self.assertEqual(fixture.count('image: sha256:'), 6)
        self.assertIn('ports: !override []', fixture)
        self.assertIn('127.0.0.1:18443:443', fixture)
        self.assertIn('infra/edge/Caddyfile', fixture)
        self.assertNotIn('SSO_CLIENT_SECRET', fixture)
        self.assertNotIn('INTERNAL_METRICS_KEY', fixture)

    def test_malformed_image_or_incomplete_services_fail(self):
        for images in ({}, {service: 'untrusted:latest' for service in staging.smoke.SERVICES}):
            with self.assertRaises(ValueError):
                staging.fixture_text(images, 18443, ROOT)

    def test_existing_project_inventory_blocks_creation(self):
        result = types.SimpleNamespace(stdout='existing-owned-resource')
        with patch.object(staging, 'command', return_value=result) as run:
            with self.assertRaises(ValueError):
                staging.inventory_empty(PROJECT, {})
            self.assertEqual(run.call_count, 1)

    def test_foreign_cleanup_is_rejected_before_down(self):
        calls = []
        def command(arguments, environment, **kwargs):
            calls.append(arguments)
            if 'inspect' in arguments:
                return types.SimpleNamespace(stdout=json.dumps([{'Config': {'Labels': {'com.docker.compose.project': 'arman-bank'}}, 'Name': '/arman-bank-payment-db-1'}]))
            return types.SimpleNamespace(stdout='a' * 64)
        with patch.object(staging, 'command', side_effect=command):
            with self.assertRaises(ValueError):
                staging.cleanup_owned(PROJECT, {}, ['docker', 'compose', '-p', PROJECT])
        self.assertFalse(any('down' in call for call in calls))

    def test_cleanup_only_removes_exact_owned_project(self):
        calls = []
        def command(arguments, environment, **kwargs):
            calls.append(arguments)
            return types.SimpleNamespace(stdout='')
        with patch.object(staging, 'command', side_effect=command):
            staging.cleanup_owned(PROJECT, {}, ['docker', 'compose', '-p', PROJECT])
        self.assertEqual(calls[-1], ['docker', 'compose', '-p', PROJECT, 'down', '--volumes', '--remove-orphans', '--timeout', '10'])

    def test_runner_guards_precede_docker_and_sensitive_output_is_suppressed(self):
        self.assertLess(source.index('smoke.guard(project'), source.index('inventory_empty(project, environment)\n    images'))
        self.assertIn("'up', '--no-build'", source)
        self.assertNotIn("'--build'", source)
        self.assertIn('cleanup_owned(project, environment, compose)', source)
        self.assertNotIn('CI=true', source)
        self.assertIn('capture_output=True', source)


if __name__ == '__main__':
    unittest.main()
