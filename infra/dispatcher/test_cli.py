"""Real CLI/SQLite integration with no Codex process or network calls."""
from contextlib import redirect_stdout
import io
import json
import os
from pathlib import Path
import tempfile
import subprocess
import unittest
from unittest.mock import patch

from . import cli
from .store import Store


class CliTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.repo = self.root / 'repository'
        self.repo.mkdir()
        self.state = self.root / 'private-state'
        self.manifest = self.root / 'manifest.json'

    def tearDown(self):
        self.temp.cleanup()

    def spec(self, task_id='owner', **changes):
        value = dict(task_id=task_id, agent_id=task_id, role='ledger_owner', issue='local-proof',
                     stage='implement', cwd=str(self.repo), prompt='PRIVATE-PROMPT-SYNTHETIC-TOKEN',
                     paths=['src'], dependencies=[], heavy=False, authorized=True)
        value.update(changes)
        return value

    def invoke(self, *args):
        output = io.StringIO()
        with redirect_stdout(output):
            code = cli.main(['--state', str(self.state), *args])
        self.assertNotIn('PRIVATE-PROMPT', output.getvalue())
        self.assertNotIn('SYNTHETIC-TOKEN', output.getvalue())
        return code, output.getvalue()

    def run_args(self):
        return ['run', '--repository', str(self.repo), '--codex', 'unused-mocked', '--manifest', str(self.manifest)]

    def write(self, specs):
        self.manifest.write_text(json.dumps(specs), encoding='utf-8')

    def test_staged_manifest_refills_persistently_and_status_is_sanitized(self):
        self.write([self.spec(), self.spec('qa', stage='qa', role='qa_integration', paths=[], read_only=True, dependencies=['owner']),
                    self.spec('review', stage='review', role='bank_reviewer', paths=[], read_only=True, dependencies=['qa'])])
        order = []
        async def runner(task):
            order.append(task['stage'])
            return {'status': 'approve' if task['stage'] == 'review' else 'ready', 'head_sha': 'head', 'tree_sha': 'tree', 'evidence': ['mock validated']}
        with patch.object(cli, 'CodexRunner', return_value=runner):
            self.assertEqual(0, self.invoke(*self.run_args())[0])
        self.assertEqual(['implement', 'qa', 'review'], order)
        self.assertTrue(all(t['state'] == 'succeeded' for t in Store(self.state / 'queue.sqlite').snapshot()))
        output = self.invoke('status')[1]
        self.assertNotIn('done', output.lower())
        with patch.object(cli, 'CodexRunner', return_value=runner):
            self.invoke(*self.run_args())
        self.assertEqual(3, len(order))

    def test_stop_at_completion_leaves_next_dependency_queued(self):
        self.write([self.spec(), self.spec('second', dependencies=['owner'])])
        async def runner(task):
            (self.state / 'STOP').touch()
            return {'status': 'ready', 'head_sha': 'head', 'tree_sha': 'tree', 'evidence': ['mock validated']}
        with patch.object(cli, 'CodexRunner', return_value=runner):
            self.invoke(*self.run_args())
        tasks = {t['task_id']: t for t in Store(self.state / 'queue.sqlite').snapshot()}
        self.assertEqual('queued', tasks['second']['state'])
        with self.assertRaises(ValueError):
            self.invoke(*self.run_args())

    def test_private_state_and_invalid_manifest(self):
        self.write([self.spec(authorized=False)])
        with patch.object(cli, 'CodexRunner') as runner:
            with self.assertRaises(ValueError):
                self.invoke(*self.run_args())
            runner.assert_not_called()
        self.manifest.write_text('{}', encoding='utf-8')
        with self.assertRaises(ValueError):
            self.invoke(*self.run_args())
        self.state = self.repo / 'invalid-state'
        with self.assertRaises(ValueError):
            self.invoke(*self.run_args())
        self.assertFalse(self.state.exists())

    def test_reconcile_requires_confirmation_before_resume(self):
        self.state.mkdir()
        store = Store(self.state / 'queue.sqlite')
        store.enqueue(self.spec())
        store.claim_available()
        store.recover()
        with self.assertRaises(ValueError):
            self.invoke('resume', 'owner')
        self.invoke('reconcile', 'owner', '--orphan-stopped')
        self.invoke('resume', 'owner')
        self.assertEqual('queued', store.snapshot()[0]['state'])
        self.invoke('stop')
        self.assertTrue((self.state / 'STOP').is_file())

    def test_owner_verification_failure_never_dispatches(self):
        self.write([self.spec(issue='12')])
        class Sink:
            def verify_issue(self, number):
                raise ValueError('not open owner task')
        with patch.object(cli, 'GitHubSink', return_value=Sink()), patch.object(cli, 'CodexRunner') as runner:
            with self.assertRaises(ValueError):
                self.invoke(*self.run_args(), '--github')
            runner.assert_not_called()
        self.assertEqual([], Store(self.state / 'queue.sqlite').snapshot())

    def test_batch_later_invalid_has_no_effects(self):
        self.write([self.spec(), self.spec('bad', authorized=False)])
        with patch.object(cli, 'CodexRunner') as runner:
            with self.assertRaises(ValueError):
                self.invoke(*self.run_args())
            runner.assert_not_called()
        self.assertEqual([], Store(self.state / 'queue.sqlite').snapshot())

    def test_assignment_and_persisted_sibling_state_rejected(self):
        sibling = self.root / 'sibling-checkout'
        sibling.mkdir()
        self.state = sibling / 'state'
        self.write([self.spec(cwd=str(sibling))])
        with patch.object(cli, 'CodexRunner') as runner:
            with self.assertRaises(ValueError):
                self.invoke(*self.run_args())
            runner.assert_not_called()
        self.assertEqual([], Store(self.state / 'queue.sqlite').snapshot())
        Store(self.state / 'queue.sqlite').enqueue(self.spec(cwd=str(sibling)))
        with self.assertRaises(ValueError):
            self.invoke('status')

    def test_run_recovery_quarantines_previous_writer(self):
        self.state.mkdir()
        store = Store(self.state / 'queue.sqlite')
        store.enqueue(self.spec(priority=1))
        store.enqueue(self.spec('next'))
        store.claim_available()
        self.write([])
        async def runner(task):
            self.fail('quarantined cwd must not dispatch')
        with patch.object(cli, 'CodexRunner', return_value=runner):
            self.invoke(*self.run_args())
        states = {t['task_id']: t['state'] for t in store.snapshot()}
        self.assertEqual({'owner': 'interrupted', 'next': 'queued'}, states)

    def test_idle_run_flushes_reporting_outbox_and_records_failure(self):
        self.write([])
        from unittest.mock import Mock
        sink = Mock()
        sink.pending.return_value = 1
        sink.flush.side_effect = OSError('offline')
        with patch.object(cli, 'GitHubSink', return_value=sink), patch.object(cli, 'CodexRunner'):
            code, output = self.invoke(*self.run_args(), '--github', '--status-issue', '12', '--external-agents', '4')
        self.assertEqual(0, code)
        sink.flush.assert_called_once()
        self.assertEqual('idle; exiting', sink.summary.call_args.args[-1])
        self.assertEqual(4, sink.summary.call_args.args[2]['external_agents'])
        self.assertEqual('startup_reporting', Store(self.state / 'queue.sqlite').event_failures()[0]['event'])
        self.assertIn('"pending_github_deliveries": 1', output)

    @unittest.skipUnless(os.name == 'nt', 'Windows DACL acceptance test')
    def test_private_state_replaces_existing_everyone_grants(self):
        # Only this dedicated TemporaryDirectory child receives the test grant.
        self.state.mkdir()
        existing = self.state / 'existing.sqlite'
        existing.write_text('synthetic fixture', encoding='utf-8')
        env = dict(os.environ, DISPATCHER_ACL_FIXTURE=str(self.state))
        for fixture in (self.state, existing):
            subprocess.run(['icacls.exe', str(fixture), '/grant', '*S-1-1-0:R'], check=True, capture_output=True, timeout=20)
        cli.private_state(self.state)
        cli.private_state(self.state)
        inspect = '''$ErrorActionPreference='Stop'; $path=$env:DISPATCHER_ACL_FIXTURE;
        $owner=[System.Security.Principal.WindowsIdentity]::GetCurrent().User.Value;
        $rows=@(); foreach($target in @($path,(Join-Path $path 'existing.sqlite'))) {
          if([IO.Directory]::Exists($target)) {
            $acl=[IO.Directory]::GetAccessControl($target,[System.Security.AccessControl.AccessControlSections]::Access)
          } else {
            $acl=[IO.File]::GetAccessControl($target,[System.Security.AccessControl.AccessControlSections]::Access)
          };
          $sids=@($acl.Access | ForEach-Object { $_.IdentityReference.Translate([System.Security.Principal.SecurityIdentifier]).Value });
          $rows+=@{ protected=$acl.AreAccessRulesProtected; sids=$sids }
        }; @{owner=$owner; rows=$rows} | ConvertTo-Json -Depth 5 -Compress'''
        value = json.loads(subprocess.check_output(['powershell.exe', '-NoProfile', '-NonInteractive', '-Command', inspect], env=env, text=True, timeout=20))
        for row in value['rows']:
            self.assertTrue(row['protected'])
            self.assertEqual([value['owner']], row['sids'])


if __name__ == '__main__':
    unittest.main()
