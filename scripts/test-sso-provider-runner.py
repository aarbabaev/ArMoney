"""Run the actual lifecycle shell with synthetic CLIs; never contact a Docker daemon."""
import json
import os
from pathlib import Path
import re
import shlex
import shutil
import subprocess
import sys
import tempfile
import unittest


TOOL = r'''
import json, os, sys
from pathlib import Path
args = sys.argv[1:]
trace = Path(os.environ['FIXTURE_TRACE'])
mode = os.environ['FIXTURE_MODE']
with trace.open('a') as out:
    out.write(json.dumps(args) + '\n')
if args[0] == 'info':
    print('offline-ci-daemon')
elif args[0] == 'ps':
    countfile = trace.with_suffix('.count')
    count = int(countfile.read_text()) + 1 if countfile.exists() else 1
    countfile.write_text(str(count))
    if mode == 'initial-ps-failure' and count == 1 or mode == 'final-ps-failure' and count == 2:
        sys.exit(23)
    if mode == 'occupied' and count == 1 or mode == 'remaining' and count == 2:
        print('synthetic-container')
elif args[0] == 'compose':
    if '--project-name' in args:
        project = args[args.index('--project-name') + 1]
        assert os.environ['OBSERVER_ACCEPTANCE_IMAGE'] == 'armoney-observer-acceptance:' + project
    if 'run' in args:
        keys = [os.environ[name] for name in ('OBSERVER_DB_PASSWORD', 'SSO_ADMIN_PASSWORD',
            'SSO_VERIFICATION_BRIDGE_KEY', 'ARMONEY_PROVIDER_BINDING_KEY',
            'AUTH_TO_SSO_STATUS_KEY', 'OBSERVER_FIXTURE_CONTROL_KEY')]
        assert len(set(keys)) == 6 and all(len(k) == 64 for k in keys)
        assert 'COMPOSE_FILE' not in os.environ
        if mode in ('run-failure', 'run-unknown', 'run-log-failure', 'run-log-oversize'):
            sys.exit(17)
    if 'logs' in args:
        assert args[-5:] == ['logs', '--no-color', '--tail', '80', 'sso-b']
        private = 'SYNTHETIC_PRIVATE_password@example.invalid_http://private.invalid/identifier'
        if mode == 'run-log-failure':
            print(private, file=sys.stderr)
            sys.exit(27)
        if mode == 'run-log-oversize':
            print(private * 2000)
        elif mode == 'run-unknown':
            print(private)
        else:
            print('java.lang.IllegalAccessError: ' + private)
            print('at com.arman.bank.sso.Bridge$Unavailable: ' + private)
    if 'down' in args and mode == 'down-failure':
        sys.exit(24)
elif args[0] == 'image':
    assert args == ['image', 'rm', os.environ['OBSERVER_ACCEPTANCE_IMAGE']]
    if mode == 'image-failure':
        sys.exit(25)
else:
    raise AssertionError('Unexpected fake Docker command')
'''


def shell_path(value):
    value = str(value).replace('\\', '/')
    return '/' + value[0].lower() + value[2:] if re.match(r'^[A-Za-z]:/', value) else value


class RunnerBoundaries(unittest.TestCase):
    def run_fixture(self, mode, **overrides):
        repository = Path(__file__).resolve().parents[1]
        bash = 'C:/Program Files/Git/bin/bash.exe' if os.name == 'nt' else shutil.which('bash')
        self.assertTrue(bash, 'Bash required for offline wrapper regression')
        with tempfile.TemporaryDirectory(prefix='armoney-observer-runner-offline-') as directory:
            root = Path(directory)
            (root / 'scripts').mkdir()
            for name in ('run-sso-provider-acceptance.sh', 'check-local-ci.sh',
                         'test-sso-provider-acceptance.py', 'sso-provider-acceptance.py'):
                # LF copies preserve shell semantics on Windows; source files remain untouched.
                (root / 'scripts' / name).write_text((repository / 'scripts' / name).read_text(), newline='\n')
            tools = root / 'fake-bin'
            tools.mkdir()
            tool = root / 'fake-docker.py'
            tool.write_text(TOOL)
            python = shlex.quote(str(Path(sys.executable)).replace('\\', '/'))
            scripts = {
                'docker': '#!/usr/bin/env bash\nexec ' + python + ' "$FIXTURE_TOOL" "$@"\n',
                'python3': '#!/usr/bin/env bash\nexec ' + python + ' "$@"\n',
                'java': '#!/usr/bin/env bash\necho "offline synthetic Java 21"\n',
            }
            for name, content in scripts.items():
                target = tools / name
                target.write_text(content, newline='\n')
                target.chmod(0o755)
            trace = root / 'trace.jsonl'
            environment = dict(os.environ, CI='true', GITHUB_ACTIONS='true',
                DOCKER_HOST='unix:///run/inner-docker/docker.sock', ARMONEY_DISPOSABLE_CI='true',
                GITHUB_WORKSPACE='/work/offline-fixture', DOCKER_CONTEXT='',
                COMPOSE_FILE='forbidden-unowned-compose.yaml', FIXTURE_MODE=mode,
                FIXTURE_TRACE=str(trace), FIXTURE_TOOL=str(tool), FAKE_BIN=shell_path(tools))
            environment.update(overrides)
            result = subprocess.run([bash, '--noprofile', '--norc', '-c',
                'export PATH="$FAKE_BIN:/usr/bin:/bin"; exec bash scripts/run-sso-provider-acceptance.sh'],
                cwd=root, env=environment, text=True, capture_output=True, timeout=30)
            calls = [json.loads(line) for line in trace.read_text().splitlines()] if trace.exists() else []
            return result, calls

    def test_initial_inspection_failure_prevents_all_compose_operations(self):
        result, calls = self.run_fixture('initial-ps-failure')
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('Cannot verify empty project', result.stderr)
        self.assertFalse(any('--project-name' in call for call in calls))
        self.assertFalse(any(call[0] == 'image' for call in calls))

    def test_existing_project_prevents_all_compose_operations(self):
        result, calls = self.run_fixture('occupied')
        self.assertNotEqual(result.returncode, 0)
        self.assertFalse(any('--project-name' in call for call in calls))
        self.assertFalse(any(call[0] == 'image' for call in calls))

    def test_final_inspection_failure_cannot_report_success(self):
        result, calls = self.run_fixture('final-ps-failure')
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('Cannot verify disposable cleanup', result.stderr)
        self.assertTrue(any('down' in call for call in calls))

    def test_remaining_containers_and_failed_down_are_not_clean_success(self):
        for mode in ('remaining', 'down-failure'):
            with self.subTest(mode=mode):
                result, calls = self.run_fixture(mode)
                self.assertNotEqual(result.returncode, 0)
                self.assertTrue(any('down' in call for call in calls))

    def test_runner_failure_retains_failure_and_performs_scoped_cleanup(self):
        result, calls = self.run_fixture('run-failure')
        self.assertEqual(result.returncode, 17)
        self.assertTrue(any('down' in call for call in calls))
        diagnostics = [call for call in calls if 'logs' in call]
        self.assertEqual(len(diagnostics),1)
        self.assertLess(calls.index(diagnostics[0]),next(i for i,call in enumerate(calls) if 'down' in call))
        self.assertIn('SERVER_FAILURE: OBSERVER69 ILLEGAL_ACCESS',result.stdout)
        self.assertIn('SERVER_FAILURE: OBSERVER69 PROVIDER_UNAVAILABLE',result.stdout)
        self.assertNotIn('SYNTHETIC_PRIVATE',result.stdout+result.stderr)
        self.assertIn('PASS: observer-owned project absent and unique image removed',result.stdout)

    def test_server_diagnostics_unknown_unavailable_and_oversize_are_private_and_never_replace_failure(self):
        for mode, expected in (('run-unknown','UNKNOWN'),('run-log-failure','LOG_UNAVAILABLE'),
                               ('run-log-oversize','LOG_TRUNCATED')):
            with self.subTest(mode=mode):
                result,calls = self.run_fixture(mode)
                self.assertEqual(result.returncode,17)
                self.assertIn('SERVER_FAILURE: OBSERVER69 '+expected,result.stdout)
                self.assertNotIn('SYNTHETIC_PRIVATE',result.stdout+result.stderr)
                self.assertNotIn('private.invalid',result.stdout+result.stderr)
                self.assertTrue(any('down' in call for call in calls))
                self.assertTrue(any(call[0]=='image' for call in calls))

    def test_image_cleanup_failure_cannot_report_success(self):
        result, calls = self.run_fixture('image-failure')
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('Disposable observer image cleanup failed', result.stderr)
        self.assertEqual(sum(call[:2] == ['image', 'rm'] for call in calls), 1)

    def test_context_override_and_non_ci_rejected_before_any_docker_command(self):
        for override in ({'DOCKER_CONTEXT': 'unowned-context'}, {'CI': 'false'},
                         {'DOCKER_HOST': 'unix:///var/run/docker.sock'}):
            with self.subTest(override=override):
                result, calls = self.run_fixture('success', **override)
                self.assertNotEqual(result.returncode, 0)
                self.assertEqual(calls, [])

    def test_success_uses_one_fresh_128_bit_project_and_fixed_compose_scoped_cleanup(self):
        result, calls = self.run_fixture('success')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertFalse(any('logs' in call for call in calls))
        operations = [call for call in calls if '--project-name' in call]
        self.assertEqual(len(operations), 4)
        projects = {call[call.index('--project-name') + 1] for call in operations}
        self.assertEqual(len(projects), 1)
        self.assertRegex(next(iter(projects)), r'^armoney-observer-ci-[0-9a-f]{32}$')
        for call in operations:
            self.assertEqual(call[call.index('--file') + 1], 'compose.sso-provider-acceptance.yaml')
        down = next(call for call in operations if 'down' in call)
        self.assertIn('--volumes', down)
        self.assertIn('--remove-orphans', down)
        image = next(call for call in calls if call[0] == 'image')
        self.assertEqual(image, ['image', 'rm', 'armoney-observer-acceptance:' + next(iter(projects))])
        self.assertLess(calls.index(down), calls.index(image))
        self.assertLess(max(index for index, call in enumerate(calls) if call[0] == 'ps'), calls.index(image))


if __name__ == '__main__':
    unittest.main(verbosity=2)
