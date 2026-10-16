"""Offline source/CLI checks; no mock financial or runtime acceptance."""
import ast
import importlib.util
import io
import json
from pathlib import Path
import tempfile
import unittest
import urllib.error
import urllib.request
from email.message import Message
from unittest.mock import MagicMock, patch

from recovery_fixture_runtime import GuardError

spec = importlib.util.spec_from_file_location('application_recovery', Path(__file__).with_name('backup-application-recovery-smoke.py'))
app = importlib.util.module_from_spec(spec)
spec.loader.exec_module(app)


class ApplicationGuards(unittest.TestCase):
    def test_default_plan_never_calls_docker_or_creates_runtime(self):
        with (patch('sys.argv', ['recovery']), patch('subprocess.run', side_effect=AssertionError('Process invoked')),
              patch.object(app, 'OwnedRuntime', side_effect=AssertionError('Runtime created')),
              patch('sys.stdout', new_callable=io.StringIO) as output):
            app.main()
        self.assertIn('PLAN ONLY', output.getvalue())

    def test_apply_requires_explicit_arguments_before_docker(self):
        for args in (['--apply'], ['--apply', '--daemon', 'unix:///tmp/recovery.sock']):
            with patch('sys.argv', ['recovery', *args]), patch('subprocess.run', side_effect=AssertionError('Process invoked')):
                with self.assertRaises(GuardError):
                    app.main()

    def test_bank_images_require_exact_revision_inventory_and_ids(self):
        valid = {'revision': 'a' * 40, 'images': {s: 'sha256:' + 'b' * 64 for s in app.SERVICES}}
        with tempfile.TemporaryDirectory(prefix='recovery-offline-') as temporary:
            path = Path(temporary) / 'images.json'
            path.write_text(json.dumps(valid), encoding='utf-8')
            self.assertEqual(app.load_images(path, 'a' * 40), valid['images'])
            invalids = [dict(valid, revision='c' * 40), dict(valid, images={'auth-service': 'sha256:' + 'b' * 64}),
                        dict(valid, images={s: 'latest' for s in app.SERVICES}), dict(valid, daemon='default'),
                        [], None, dict(valid, images=list(app.SERVICES))]
            for invalid in invalids:
                path.write_text(json.dumps(invalid), encoding='utf-8')
                with self.assertRaises(GuardError):
                    app.load_images(path, 'a' * 40)

    def test_oversized_image_manifest_is_rejected_before_loading_content(self):
        with tempfile.TemporaryDirectory(prefix='recovery-offline-') as temporary:
            path = Path(temporary) / 'images.json'
            path.write_bytes(b'x' * 65537)
            with self.assertRaises(GuardError):
                app.load_images(path, 'a' * 40)

    def test_apply_rejects_wrong_revision_before_runtime_or_docker(self):
        with tempfile.TemporaryDirectory(prefix='recovery-offline-') as temporary:
            path = Path(temporary) / 'images.json'
            path.write_text(json.dumps({'revision': 'b' * 40,
                'images': {s: 'sha256:' + 'c' * 64 for s in app.SERVICES}}), encoding='utf-8')
            with (patch.object(app, 'revision', return_value='a' * 40),
                  patch.object(app, 'OwnedRuntime', side_effect=AssertionError('Runtime created')),
                  patch('subprocess.run', side_effect=AssertionError('Process invoked'))):
                with self.assertRaises(GuardError):
                    app.apply('unix:///tmp/recovery.sock', path)

    def test_image_manifest_rejects_non_string_ids_and_revision_types(self):
        valid = {'revision': 'a' * 40, 'images': {s: 'sha256:' + 'b' * 64 for s in app.SERVICES}}
        with tempfile.TemporaryDirectory(prefix='recovery-offline-') as temporary:
            path = Path(temporary) / 'images.json'
            for invalid in (dict(valid, revision=True), dict(valid, revision=None),
                            dict(valid, images={s: None for s in app.SERVICES}),
                            dict(valid, images={s: True for s in app.SERVICES})):
                with self.subTest(manifest=invalid):
                    path.write_text(json.dumps(invalid), encoding='utf-8')
                    with self.assertRaises(GuardError):
                        app.load_images(path, 'a' * 40)

    def test_embedded_runner_programs_compile_without_execution(self):
        from recovery_fixture_runtime import HTTP_PROGRAM
        compile(HTTP_PROGRAM, '<owned-http>', 'exec')
        compile(app.PKCE_PROGRAM, '<owned-pkce>', 'exec')

    def test_import_has_no_acceptance_side_effects(self):
        with (patch('subprocess.run', side_effect=AssertionError('Process invoked')),
              patch.object(app, 'OwnedRuntime', side_effect=AssertionError('Runtime created'))):
            spec.loader.exec_module(app)

    def test_pkce_custom_scheme_callback_is_captured_without_opening_it(self):
        # Exercise urllib's real redirect handler: custom schemes are rejected
        # before redirect_request unless http_error_302 captures them explicitly.
        tree = ast.parse(app.PKCE_PROGRAM)
        selected = [node for node in tree.body if isinstance(node, (ast.Import, ast.ImportFrom)) or
                    (isinstance(node, ast.ClassDef) and node.name == 'Redirect')]
        namespace = {'callback': app.CALLBACK}
        exec(compile(ast.Module(body=selected, type_ignores=[]), '<redirect-guard>', 'exec'), namespace)
        handler = namespace['Redirect']()
        headers = Message()
        callback = app.CALLBACK + '?code=synthetic-code&state=synthetic-state'
        headers['Location'] = callback
        with self.assertRaises(urllib.error.HTTPError):
            handler.http_error_302(urllib.request.Request('https://recovery.test/sso/login'),
                                   io.BytesIO(b''), 302, 'Found', headers)
        self.assertEqual(handler.callback, callback)
        with self.assertRaises(RuntimeError):
            handler.redirect_request(urllib.request.Request('https://recovery.test/sso/login'),
                                     io.BytesIO(b''), 302, 'Found', {}, 'https://provider.invalid/login')


class ReplicaGuards(unittest.TestCase):
    """Failure injection at transport boundaries, never successful money fixtures."""

    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix='replica-offline-')
        self.addCleanup(self.temporary.cleanup)
        self.directory = Path(self.temporary.name)
        self.runtime = MagicMock()
        self.runtime.sql.side_effect = self.primary_sql
        self.runtime.create.return_value = 'owned-standby'
        self.runtime.owned.return_value = {'IPAM': {'Config': [{'Subnet': '172.28.0.0/16'}]}}

    def primary_sql(self, name, database, user, statement):
        if 'pg_hba_file_rules' in statement:
            return '1'
        if 'pg_current_wal_flush_lsn' in statement:
            return '0/ABC123'
        if 'pg_reload_conf' in statement:
            return 't'
        return ''

    def invoke(self):
        return app.replica_recovery(self.runtime, 'owned-network',
                                    {'postgres': 'sha256:' + 'b' * 64},
                                    {'ledger-db': 'owned-primary'}, self.directory,
                                    '00000000-0000-0000-0000-000000000001', object())

    def test_replication_role_failure_never_allocates_standby(self):
        self.runtime.sql.side_effect = GuardError('Synthetic role creation rejection')
        with self.assertRaises(GuardError):
            self.invoke()
        self.runtime.create.assert_not_called()
        self.assertFalse((self.directory / 'standby.sh').exists())

    def test_missing_authenticated_replication_rule_never_clones(self):
        self.runtime.sql.side_effect = lambda *args: '0' if 'pg_hba_file_rules' in args[-1] else self.primary_sql(*args)
        with self.assertRaisesRegex(GuardError, 'Authenticated physical replication'):
            self.invoke()
        self.runtime.create.assert_not_called()

    def test_invalid_wal_boundary_never_allocates_or_copies_script(self):
        for boundary in ('', '0/abc123', "0/ABC';SELECT 1;--", '0/ABC\n'):
            with self.subTest(boundary=boundary):
                self.runtime.reset_mock()
                self.runtime.sql.side_effect = lambda *args: boundary if 'pg_current_wal_flush_lsn' in args[-1] else self.primary_sql(*args)
                with self.assertRaisesRegex(GuardError, 'Invalid replica replay boundary'):
                    self.invoke()
                self.runtime.create.assert_not_called()
                self.runtime.copy_in.assert_not_called()

    def test_ownership_rejection_prevents_copy_start_or_financial_checks(self):
        self.runtime.create.side_effect = GuardError('Synthetic ownership rejection')
        with patch.object(app, 'financial_state', side_effect=AssertionError('Financial check reached')):
            with self.assertRaises(GuardError):
                self.invoke()
        self.runtime.copy_in.assert_not_called()
        self.runtime.action.assert_not_called()

    def test_invalid_subnet_inventory_never_writes_hba_or_allocates_standby(self):
        for config in (None, [], [{}, {}], ['172.28.0.0/16'], [{'Subnet': '8.8.8.0/24'}],
                       [{'Subnet': '127.0.0.0/8'}], [{'Subnet': '169.254.0.0/16'}],
                       [{'Subnet': 'fd00::/64'}], [{'Subnet': '172.28.0.1/16'}],
                       [{'Subnet': "172.28.0.0/16; trust"}], [{'Subnet': None}]):
            with self.subTest(config=config):
                self.runtime.reset_mock()
                self.runtime.owned.return_value = {'IPAM': {'Config': config}}
                with self.assertRaises((GuardError, ValueError, TypeError)):
                    self.invoke()
                self.runtime.exec.assert_not_called()
                self.runtime.create.assert_not_called()

    def test_failed_hba_reload_never_checks_rule_or_allocates_standby(self):
        for result in ('f', '', 'true', '1'):
            with self.subTest(result=result):
                self.runtime.reset_mock()
                self.runtime.sql.side_effect = lambda *args: result if 'pg_reload_conf' in args[-1] else self.primary_sql(*args)
                with self.assertRaisesRegex(GuardError, 'authentication reload failed'):
                    self.invoke()
                self.runtime.create.assert_not_called()
                self.assertFalse(any('pg_hba_file_rules' in call.args[-1] for call in self.runtime.sql.call_args_list))

    def test_missing_duplicate_or_malformed_exact_hba_rule_never_allocates(self):
        for result in ('0', '2', '', 'true'):
            with self.subTest(result=result):
                self.runtime.reset_mock()
                self.runtime.sql.side_effect = lambda *args: result if 'pg_hba_file_rules' in args[-1] else self.primary_sql(*args)
                with self.assertRaisesRegex(GuardError, 'Authenticated physical replication'):
                    self.invoke()
                self.runtime.create.assert_not_called()

    def test_hba_uses_owned_subnet_role_scram_and_fixed_shell_argument(self):
        self.runtime.create.side_effect = GuardError('Stop before clone')
        with self.assertRaisesRegex(GuardError, 'Stop before clone'):
            self.invoke()
        args = self.runtime.exec.call_args.args
        self.assertEqual(args[:3], ('owned-primary', '/bin/sh', '-c'))
        self.assertEqual(args[-2:], ('recovery-hba',
                         'host replication recovery_replication 172.28.0.0/16 scram-sha-256'))
        self.assertNotIn('172.28.', args[3])
        self.assertNotIn('trust', args[-1])
        rule_check = next(call.args[-1] for call in self.runtime.sql.call_args_list if 'pg_hba_file_rules' in call.args[-1])
        for expected in ("database=ARRAY['replication']", "user_name=ARRAY['recovery_replication']",
                         "address='172.28.0.0'", "netmask='255.255.0.0'", "auth_method='scram-sha-256'", 'error IS NULL'):
            self.assertIn(expected, rule_check)

    def test_replay_timeout_never_claims_money_recovery_or_starts_rebuild(self):
        def reject_replay(probe, predicate, seconds):
            self.assertEqual(seconds, 90)
            self.assertFalse(predicate('f'))
            self.assertFalse(predicate(None))
            raise GuardError('Synthetic replay timeout')
        with (patch.object(app, 'wait_database'), patch.object(app, 'until', side_effect=reject_replay),
              patch.object(app, 'financial_state', side_effect=AssertionError('Financial check reached'))):
            with self.assertRaises(GuardError):
                self.invoke()
        self.assertEqual(self.runtime.create.call_count, 1)
        self.assertEqual(self.runtime.action.call_args_list[0].args, ('owned-standby', 'start'))
        self.assertEqual(self.runtime.action.call_count, 1)

    def test_ledger_verification_failure_stops_before_restart_or_rebuild(self):
        with (patch.object(app, 'wait_database'), patch.object(app, 'until'),
              patch.object(app, 'financial_state', side_effect=GuardError('Synthetic ledger mismatch'))):
            with self.assertRaisesRegex(GuardError, 'Synthetic ledger mismatch'):
                self.invoke()
        self.assertEqual(self.runtime.create.call_count, 1)
        self.assertEqual(self.runtime.action.call_count, 1)

    def test_standby_program_requires_private_passfile_empty_target_and_recovery_marker(self):
        # Source contract only; this does not establish PostgreSQL recovery.
        program = app.STANDBY_PROGRAM
        self.assertIn('set -eu', program)
        self.assertLess(program.index('umask 077'), program.index('> /var/lib/postgresql/.pgpass'))
        self.assertIn('unset RECOVERY_REPLICATION_PASSWORD', program)
        self.assertIn('passfile=/var/lib/postgresql/.pgpass', program)
        self.assertNotIn('PGPASSWORD=', program)
        self.assertIn('test -z "$(find "$PGDATA"', program)
        self.assertIn('-R -X stream', program)
        self.assertIn('--no-password', program)
        self.assertIn('test -f "$PGDATA/standby.signal"', program)
        self.assertNotIn('promote', program)


if __name__ == '__main__':
    unittest.main()
