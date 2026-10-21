"""Offline command-boundary tests; never runs npm, builds or Docker."""
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

SCRIPT = Path(__file__).with_name('run-web-ci.sh')
BASH = ('C:/Program Files/Git/bin/bash.exe' if os.name == 'nt' else shutil.which('bash'))
EXPECTED = ['npm ci --no-audit --loglevel=http', 'npm audit --audit-level=high', 'npm run build', 'npm test',
            'npx playwright install --with-deps chromium', 'npm run e2e']
STAGES = ['INSTALL', 'AUDIT', 'BUILD', 'UNIT', 'BROWSER_INSTALL', 'E2E']


class WebCi(unittest.TestCase):
    def run_fixture(self, failure='', code=17):
        with tempfile.TemporaryDirectory(prefix='armoney-web-ci-') as directory:
            root = Path(directory)
            log = root / 'calls'
            stub = '''#!/usr/bin/env bash
set -eu
command="$(basename "$0") $*"
printf '%s\\n' "$command" >> "$CALLS"
printf '%s\\n' 'synthetic-standard-tool-output' >&2
if [[ "$command" == "$FAIL_COMMAND" ]]; then exit "$FAIL_CODE"; fi
if [[ "$(basename "$0")" == timeout ]]; then
  [[ "$1" == --signal=TERM && "$2" == --kill-after=10s && "$3" == 600s ]]
  shift 3
  exec "$@"
fi
[[ "$npm_config_fetch_timeout" == 60000 && "$npm_config_fetch_retries" == 2 ]]
'''
            for name in ('npm', 'npx', 'timeout'):
                path = root / name
                path.write_text(stub, newline='\n')
                path.chmod(0o700)
            stub_bin = root.as_posix()
            if os.name == 'nt':
                stub_bin = '/' + stub_bin[0].lower() + stub_bin[2:]
            env = dict(os.environ, STUB_BIN=stub_bin, CI_SCRIPT=SCRIPT.as_posix(),
                       CALLS=log.as_posix(), FAIL_COMMAND=failure, FAIL_CODE=str(code),
                       SYNTHETIC_PRIVATE_ENV='private-env-value-never-print')
            result = subprocess.run([BASH, '-c', 'export PATH="$STUB_BIN:$PATH"; source "$CI_SCRIPT"'], env=env,
                                    capture_output=True, text=True, timeout=15)
            return result, log.read_text().splitlines() if log.exists() else []

    def test_exact_sequence_and_finite_diagnostics_preserve_tool_output(self):
        result, calls = self.run_fixture()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(calls, ['timeout --signal=TERM --kill-after=10s 600s npm ci --no-audit --loglevel=http', *EXPECTED])
        self.assertEqual(result.stderr.splitlines(), ['synthetic-standard-tool-output'] * 7)
        lines = result.stdout.splitlines()
        self.assertEqual(len(lines), 12)
        for index, stage in enumerate(STAGES):
            self.assertEqual(lines[index * 2], f'ARMONEY_WEB_CI stage={stage} event=start')
            self.assertRegex(lines[index * 2 + 1],
                             rf'^ARMONEY_WEB_CI stage={stage} event=end duration_seconds=\d+ exit=0$')
        self.assertNotIn('private-env-value-never-print', result.stdout + result.stderr)
        self.assertNotIn('SYNTHETIC_PRIVATE_ENV', result.stdout + result.stderr)

    def test_each_failure_preserves_exit_and_prevents_later_checks(self):
        for index, command in enumerate(EXPECTED):
            with self.subTest(command=command):
                result, calls = self.run_fixture(command)
                self.assertEqual(result.returncode, 17)
                self.assertEqual(calls[1:], EXPECTED[:index + 1])
                self.assertRegex(result.stdout.splitlines()[-1],
                    rf'^ARMONEY_WEB_CI stage={STAGES[index]} event=end duration_seconds=\d+ exit=17$')
                self.assertIn('synthetic-standard-tool-output', result.stderr)
                self.assertNotIn('private-env-value-never-print', result.stdout + result.stderr)

    def test_install_timeout_fails_closed(self):
        command = 'timeout --signal=TERM --kill-after=10s 600s npm ci --no-audit --loglevel=http'
        result, calls = self.run_fixture(command, 124)
        self.assertEqual(result.returncode, 124)
        self.assertEqual(calls, [command])
        self.assertRegex(result.stdout.splitlines()[-1],
                         r'^ARMONEY_WEB_CI stage=INSTALL event=end duration_seconds=\d+ exit=124$')


if __name__ == '__main__':
    unittest.main(verbosity=2)
