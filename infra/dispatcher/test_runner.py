"""Actual asyncio subprocess tests using a synthetic Codex CLI and temporary Git repos.

No model, connector, Docker or external repository calls. On Windows the launcher
adapter substitutes Python for the fixture executable only; arguments, pipes,
environment, result files and timeout/cancellation handling remain real.
"""
import asyncio
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

from . import runner


FAKE_CLI = r'''
import json, os, pathlib, subprocess, sys, time
root = pathlib.Path(__file__).parent
control = json.loads((root / 'control.json').read_text())
args = sys.argv[1:]
prompt = sys.stdin.read()
(root / 'capture.json').write_text(json.dumps({'args': args, 'prompt': prompt, 'env': dict(os.environ), 'pid': os.getpid()}))
print('SYNTHETIC_PRIVATE_RAW_STREAM', flush=True)
print('SYNTHETIC_PRIVATE_RAW_STREAM', file=sys.stderr, flush=True)
mode = control.get('mode', 'success')
if mode == 'barrier':
    (root / ('ready-' + str(os.getpid()))).write_text('ready')
    deadline = time.monotonic() + 3
    while len(list(root.glob('ready-*'))) < 2:
        if time.monotonic() >= deadline:
            sys.exit(9)
        time.sleep(0.01)
if mode == 'sleep':
    time.sleep(30)
    (root / 'late-parent').write_text('finished')
if mode == 'descendant':
    child = subprocess.Popen([sys.executable, '-c',
        "import pathlib,time;time.sleep(1.5);pathlib.Path(" + repr(str(root / 'late-child')) + ").write_text('finished')"],
        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    (root / 'child-pid').write_text(str(child.pid))
    time.sleep(30)
if mode == 'failure':
    sys.exit(7)
if mode == 'missing':
    sys.exit(0)
output = pathlib.Path(args[args.index('--output-last-message') + 1])
if mode == 'malformed':
    output.write_text('not-json SYNTHETIC_PRIVATE_RAW_STREAM')
elif mode == 'oversize':
    output.write_text('x' * 65537)
else:
    if mode == 'commit':
        cwd = pathlib.Path(args[args.index('--cd') + 1])
        subprocess.run(['git', '-C', str(cwd), '-c', 'core.hooksPath=' + str(root / 'no-hooks'),
                        '-c', 'user.name=Synthetic Runner', '-c', 'user.email=runner@example.test',
                        '-c', 'commit.gpgsign=false', 'commit', '--allow-empty', '-q', '-m', 'changed HEAD'], check=True)
    if mode == 'mutate':
        cwd = pathlib.Path(args[args.index('--cd') + 1])
        (cwd / 'unexpected-untracked.txt').write_text('synthetic change')
    output.write_text(json.dumps(control['result']))
'''


class RunnerTests(unittest.IsolatedAsyncioTestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='armoney-dispatch-runner-test-')
        self.root = Path(self.temp.name)
        self.repo = self.root / 'repo'
        self.fixture = self.root / 'fake-codex.py'
        self.fixture.write_text(FAKE_CLI)
        self.init_repo(self.repo)
        self.head = runner.revision(self.repo)
        self.processes = []
        self.launches = []
        original_launch = asyncio.create_subprocess_exec

        async def fake_launch(*args, **kwargs):
            self.launches.append((args, kwargs))
            if args[0] == str(self.fixture.resolve()):
                args = (sys.executable, str(self.fixture), *args[1:])
            else:
                self.assertEqual(args[0].lower(), 'taskkill', 'Unexpected subprocess launcher')
            process = await original_launch(*args, **kwargs)
            self.processes.append(process)
            return process

        self.launch_patch = patch.object(runner.asyncio, 'create_subprocess_exec', fake_launch)
        self.launch_patch.start()
        self.control()

    async def asyncSetUp(self):
        # Git startup on Windows is slower than asyncio's diagnostic default.
        asyncio.get_running_loop().slow_callback_duration = 1.0

    async def asyncTearDown(self):
        self.launch_patch.stop()
        for process in self.processes:
            if process.returncode is None:
                process.kill()
            await process.wait()
        self.temp.cleanup()

    def git(self, cwd, *args):
        return subprocess.check_output(['git', '-c', 'core.hooksPath=' + str(self.root / 'no-hooks'),
                                        '-C', str(cwd), *args], text=True, stderr=subprocess.DEVNULL, timeout=15).strip()

    def init_repo(self, target):
        target.mkdir()
        self.git(target, '-c', 'init.templateDir=', 'init', '-q')
        role = target / '.codex/agents/ledger_owner.toml'
        role.parent.mkdir(parents=True)
        role.write_text('name = "ledger_owner"\n')
        (target / 'AGENTS.md').write_text('Synthetic test instructions.\n')
        self.git(target, 'add', '.')
        self.git(target, '-c', 'user.name=Synthetic Runner', '-c', 'user.email=runner@example.test',
                 'commit', '-q', '-m', 'synthetic baseline')

    def task(self, **changes):
        task = {'task_id': 'synthetic-task', 'role': 'ledger_owner', 'stage': 'implement',
                'cwd': str(self.repo), 'paths': ['ledger-service/src/Test.java'],
                'prompt': 'Synthetic authorized change; no financial data.', 'heavy': False}
        task.update(changes)
        return task

    def result(self, **changes):
        return {'status': 'ready', 'summary': 'Synthetic fixture completed',
                'evidence': ['synthetic process result'], 'head_sha': self.head, **changes}

    def control(self, mode='success', result=None):
        (self.root / 'control.json').write_text(json.dumps({'mode': mode, 'result': self.result() if result is None else result}))

    def capture(self):
        return json.loads((self.root / 'capture.json').read_text())

    def instance(self, timeout=5):
        return runner.CodexRunner(self.fixture, self.repo, timeout=timeout)

    async def wait_started(self):
        deadline = asyncio.get_running_loop().time() + 3
        while not (self.root / 'capture.json').exists():
            if asyncio.get_running_loop().time() >= deadline:
                self.fail('Synthetic CLI failed to start')
            await asyncio.sleep(0.01)

    async def test_success_accepts_scheduler_task_and_uses_ephemeral_schema_stdin(self):
        result = await self.instance()(self.task())
        self.assertEqual(result['status'], 'ready')
        self.assertEqual(result['head_sha'], self.head)
        self.assertRegex(result['tree_sha'], r'^[0-9a-f]{64}$')
        captured = self.capture()
        args = captured['args']
        self.assertEqual(args[0], 'exec')
        for value in ('--ignore-user-config', '--ephemeral', '--json', '--output-schema'):
            self.assertIn(value, args)
        self.assertEqual(args[-1], '-')
        self.assertEqual(args[args.index('--sandbox') + 1], 'workspace-write')
        self.assertIn('synthetic-task', captured['prompt'])
        self.assertIn(self.head, captured['prompt'])
        self.assertIn('ledger-service/src/Test.java', captured['prompt'])
        self.assertIn('Heavy lease: False', captured['prompt'])
        self.assertIn('Do not launch Android emulators or emulator instrumentation, or add or connect external providers.', captured['prompt'])
        self.assertNotIn(self.task()['prompt'], args)
        self.assertFalse(Path(args[args.index('--output-last-message') + 1]).exists(), 'Temporary result must be removed')
        self.assertEqual(self.launches[0][1]['stdout'], asyncio.subprocess.DEVNULL)
        self.assertEqual(self.launches[0][1]['stderr'], asyncio.subprocess.DEVNULL)
        self.assertNotIn('SYNTHETIC_PRIVATE_RAW_STREAM', json.dumps(result))

    async def test_secret_environment_not_inherited_and_model_not_forced(self):
        sensitive = {key: 'synthetic-private-value' for key in (
            'GITHUB_PAT_TOKEN', 'GH_TOKEN', 'OPENAI_API_KEY', 'AWS_SECRET_ACCESS_KEY',
            'CODEX_HOME', 'OPENAI_MODEL', 'CODEX_MODEL', 'DISPATCHER_TOKEN')}
        with patch.dict(os.environ, {**sensitive, 'LANG': 'C.UTF-8'}):
            await self.instance()(self.task())
        captured = self.capture()
        self.assertTrue(set(sensitive).isdisjoint(captured['env']))
        self.assertEqual(captured['env']['LANG'], 'C.UTF-8')
        self.assertIn('PATH', {key.upper() for key in captured['env']})
        self.assertNotIn('--model', captured['args'])
        self.assertNotIn('-m', captured['args'])
        self.assertFalse(any(arg.startswith('model=') for arg in captured['args']))

    async def test_qa_and_review_use_read_only_and_independent_prompt(self):
        for stage in ('qa', 'review'):
            with self.subTest(stage=stage):
                await self.instance()(self.task(stage=stage))
                captured = self.capture()
                self.assertEqual(captured['args'][captured['args'].index('--sandbox') + 1], 'read-only')
                self.assertIn('Read-only independent validation; do not edit.', captured['prompt'])

    async def test_cli_failure_and_missing_result_are_blocked_not_success(self):
        for mode in ('failure', 'missing'):
            with self.subTest(mode=mode):
                self.control(mode)
                result = await self.instance()(self.task())
                self.assertEqual(result['status'], 'blocked')
                self.assertEqual(result['evidence'], [])
                self.assertNotIn('SYNTHETIC_PRIVATE_RAW_STREAM', json.dumps(result))

    async def test_malformed_and_oversized_files_are_rejected(self):
        for mode in ('malformed', 'oversize'):
            with self.subTest(mode=mode):
                self.control(mode)
                with self.assertRaises(ValueError) as caught:
                    await self.instance()(self.task())
                self.assertNotIn('SYNTHETIC_PRIVATE_RAW_STREAM', str(caught.exception))

    async def test_result_schema_violations_fail_closed(self):
        missing = self.result()
        del missing['evidence']
        cases = [missing, self.result(summary=12), self.result(evidence='not-an-array'),
                 self.result(evidence=[{'private': 'synthetic'}]), self.result(extra='unexpected'),
                 self.result(status='completed'), [], None]
        for value in cases:
            with self.subTest(result_type=type(value).__name__, value=value):
                (self.root / 'control.json').write_text(json.dumps({'mode': 'success', 'result': value}))
                with self.assertRaises(ValueError):
                    await self.instance()(self.task())

    async def test_model_revision_is_replaced_by_authoritative_checkout_head(self):
        self.control(result=self.result(head_sha='f' * 40))
        result = await self.instance()(self.task())
        self.assertEqual(result['status'], 'ready')
        self.assertEqual(result['head_sha'], self.head)

    async def test_actual_head_change_blocks_even_when_model_claims_initial_head(self):
        self.control(mode='commit')
        result = await self.instance()(self.task())
        actual_head = runner.revision(self.repo)
        self.assertNotEqual(actual_head, self.head)
        self.assertEqual(result['status'], 'blocked')
        self.assertEqual(result['head_sha'], actual_head)
        self.assertEqual(result['evidence'], [])

    async def test_implementation_can_explicitly_require_read_only(self):
        self.control(mode='mutate')
        result = await self.instance()(self.task(read_only=True, paths=[]))
        captured = self.capture()
        self.assertEqual(captured['args'][captured['args'].index('--sandbox') + 1], 'read-only')
        self.assertEqual(result['status'], 'blocked')

    async def test_read_only_modification_is_blocked_even_without_head_change(self):
        self.control(mode='mutate', result=self.result(status='approve'))
        result = await self.instance()(self.task(stage='review'))
        self.assertEqual(result['status'], 'blocked')
        self.assertEqual(result['head_sha'], self.head)
        self.assertTrue((self.repo / 'unexpected-untracked.txt').exists())

    def test_untracked_file_boundaries_cannot_collide_in_tree_fingerprint(self):
        # DISP-007: concatenating file bytes hides redistribution across files.
        first, second = self.repo / 'a', self.repo / 'b'
        first.write_bytes(b'x')
        second.write_bytes(b'yz')
        before = runner.tree_fingerprint(self.repo)
        first.write_bytes(b'xy')
        second.write_bytes(b'z')
        self.assertEqual(first.read_bytes() + second.read_bytes(), b'xyz')
        self.assertEqual(runner.revision(self.repo), self.head)
        self.assertNotEqual(before, runner.tree_fingerprint(self.repo))
        first.write_bytes(b'x')
        second.write_bytes(b'yz')
        self.assertEqual(before, runner.tree_fingerprint(self.repo))

    async def test_sibling_worktree_of_same_repository_is_accepted(self):
        sibling = self.root / 'sibling'
        self.git(self.repo, 'worktree', 'add', '--detach', str(sibling), 'HEAD')
        result = await self.instance()(self.task(cwd=str(sibling)))
        self.assertEqual(result['status'], 'ready')
        args = self.capture()['args']
        self.assertEqual(Path(args[args.index('--cd') + 1]), sibling)

    async def test_two_cli_processes_progress_concurrently_in_sibling_worktrees(self):
        sibling = self.root / 'concurrent-sibling'
        self.git(self.repo, 'worktree', 'add', '--detach', str(sibling), 'HEAD')
        self.control(mode='barrier')
        results = await asyncio.gather(
            self.instance()(self.task(task_id='first', stage='qa')),
            self.instance()(self.task(task_id='second', stage='qa', cwd=str(sibling))),
        )
        self.assertEqual([result['status'] for result in results], ['ready', 'ready'])
        self.assertEqual(len(list(self.root.glob('ready-*'))), 2)
        self.assertEqual(len(self.processes), 2)

    async def test_unrelated_repository_rejected_before_cli_launch(self):
        unrelated = self.root / 'unrelated'
        self.init_repo(unrelated)
        with self.assertRaises(runner.PreflightError) as error:
            await self.instance()(self.task(cwd=str(unrelated)))
        self.assertEqual('REPOSITORY_MISMATCH', error.exception.code)
        self.assertNotIn(str(unrelated), error.exception.result()['summary'])
        self.assertEqual(self.launches, [])

    def test_missing_cwd_preflight_has_safe_code_and_no_launch(self):
        with self.assertRaises(runner.PreflightError) as error:
            self.instance().preflight(self.task(cwd=str(self.root / 'SENSITIVE-unavailable-path')))
        self.assertEqual('ASSIGNMENT_CWD_UNAVAILABLE', error.exception.code)
        self.assertNotIn('SENSITIVE', json.dumps(error.exception.result()))
        self.assertEqual(self.launches, [])

    async def test_unborn_git_repository_is_blocked_before_claim_or_launch(self):
        from .store import Store
        from .scheduler import Scheduler
        unborn = self.root / 'unborn'
        unborn.mkdir()
        self.git(unborn, '-c', 'init.templateDir=', 'init', '-q')
        role = unborn / '.codex/agents/ledger_owner.toml'
        role.parent.mkdir(parents=True)
        role.write_text('name = "ledger_owner"\n')
        store = Store(self.root / 'unborn-queue.sqlite')
        store.enqueue(self.task(cwd=str(unborn), authorized=True, issue='unborn-proof', agent_id='unborn-owner'))
        instance = runner.CodexRunner(self.fixture, unborn)
        row = (await Scheduler(store, instance).run())[0]
        self.assertEqual(('blocked', 0), (row['state'], row['attempt']))
        self.assertEqual('REPOSITORY_UNAVAILABLE', row['result']['error_code'])
        self.assertEqual(self.launches, [])
        self.assertFalse((self.root / 'capture.json').exists())

    async def test_invalid_or_missing_role_rejected_before_cli_launch(self):
        for role in ('../ledger_owner', 'Unknown', 'missing_owner'):
            with self.subTest(role=role), self.assertRaises(ValueError):
                await self.instance()(self.task(role=role))
        self.assertEqual(self.launches, [])

    async def test_timeout_kills_and_reaps_running_process(self):
        self.control(mode='sleep')
        with self.assertRaises(asyncio.TimeoutError):
            await self.instance(timeout=0.5)(self.task())
        self.assertTrue(self.processes)
        self.assertTrue(all(process.returncode is not None for process in self.processes))
        self.assertFalse((self.root / 'late-parent').exists())

    async def test_cancellation_kills_and_reaps_running_process(self):
        self.control(mode='sleep')
        future = asyncio.create_task(self.instance()(self.task()))
        await self.wait_started()
        future.cancel()
        with self.assertRaises(asyncio.CancelledError):
            await future
        self.assertTrue(all(process.returncode is not None for process in self.processes))

    async def test_timeout_also_stops_cli_descendant(self):
        self.control(mode='descendant')
        with self.assertRaises(asyncio.TimeoutError):
            await self.instance(timeout=0.5)(self.task())
        self.assertTrue((self.root / 'child-pid').exists(), 'Descendant must actually start')
        await asyncio.sleep(1.7)
        self.assertFalse((self.root / 'late-child').exists(), 'Timed-out CLI left a descendant running')


if __name__ == '__main__':
    unittest.main(verbosity=2)
