#!/usr/bin/env python3
"""Execute inline CI guards and check this workflow's bounded layout contract.

This extractor deliberately handles only the fixed indentation/block scalars in
ci.yml. It is not a YAML parser or an Actions execution engine; actionlint and
hosted job evidence remain separate requirements. No Docker command is executed.
"""
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
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[1]
WORKFLOW = ROOT / '.github/workflows/ci.yml'
JOBS = ('dispatcher', 'build', 'web', 'assistant', 'kafka', 'android')
FLAGS = ('dispatcher', 'build', 'android', 'web', 'assistant', 'kafka', 'sms',
         'baseline', 'bank_smoke', 'assistant_smoke', 'sharding', 'replication', 'sso')
MODULES = ('platform-runtime', 'app-gateway', 'auth-service', 'user-service',
           'wallet-service', 'payment-service', 'ledger-service', 'sms-sandbox')
BASH = (str(Path(os.environ.get('ProgramFiles', 'C:/Program Files'))/'Git/bin/bash.exe')
        if os.name == 'nt' else shutil.which('bash'))


def blocks(source, pattern):
    starts = list(re.finditer(pattern, source, re.M))
    if not starts:
        raise AssertionError('Expected fixed-layout blocks missing')
    return [(start.group(1), source[start.start():starts[index+1].start()
             if index+1 < len(starts) else len(source)]) for index, start in enumerate(starts)]


def jobs():
    source = WORKFLOW.read_text(encoding='utf-8')
    body = source.split('\njobs:\n', 1)[1]
    return dict(blocks(body, r'^  ([a-z_]+):\s*$'))


def steps(job):
    return blocks(job.split('\n    steps:\n', 1)[1], r'^      - (name: [^\n]+|uses: [^\n]+)$')


def run_body(step):
    marker = '\n        run: |\n'
    if marker in step:
        body = step.split(marker, 1)[1]
        lines = []
        for line in body.splitlines():
            if line and not line.startswith('          '):
                break
            lines.append(line[10:] if line else '')
        return '\n'.join(lines)+'\n'
    match = re.search(r'^        run: ([^|\n][^\n]*)$', step, re.M)
    if not match:
        raise AssertionError('Expected executable run body')
    return match.group(1)+'\n'


def valid_selection():
    return dict({flag:'false' for flag in FLAGS}, modules='[]')


def web_helper_consumer(body, helper=None):
    """Resolve only the fixed, read-only mounted helper actually invoked by Docker."""
    mount = 'type=bind,source=$PWD/scripts/run-web-ci.sh,target=/opt/run-web-ci.sh,readonly'
    commands = [shlex.split(line) for line in body.splitlines()
                if line.strip().startswith('docker run ')]
    if len(commands) != 1:
        return False
    command = commands[0]
    if (command[:3] != ['docker', 'run', '--rm'] or
            command[-2:] != ['bash', '/opt/run-web-ci.sh'] or
            sum(command[index:index + 2] == ['--mount', mount]
                for index in range(len(command) - 1)) != 1):
        return False
    if helper is None:
        path = ROOT / 'scripts/run-web-ci.sh'
        if not path.is_file() or path.is_symlink():
            return False
        helper = path.read_text(encoding='utf-8')
    expected = [
        ['INSTALL', 'timeout', '--signal=TERM', '--kill-after=10s', '600s',
         'npm', 'ci', '--no-audit', '--loglevel=http'],
        ['AUDIT', 'npm', 'audit', '--audit-level=high'],
        ['BUILD', 'npm', 'run', 'build'], ['UNIT', 'npm', 'test'],
        ['BROWSER_INSTALL', 'npx', 'playwright', 'install', '--with-deps', 'chromium'],
        ['E2E', 'npm', 'run', 'e2e'],
    ]
    actual = [shlex.split(line)[1:] for line in helper.splitlines()
              if line.startswith('run_stage ')]
    return (actual == expected and '\nset -euo pipefail\n' in helper and
            '\nrun_stage() {\n' in helper and
            re.search(r'^  "\$@"$', helper, re.M) is not None)


class WorkflowGuards(unittest.TestCase):
    def setUp(self):
        self.jobs = jobs()

    def gates(self):
        for name in JOBS:
            first = steps(self.jobs[name])[0]
            self.assertEqual(first[0], 'name: Require valid component selection')
            self.assertNotIn('\n        if:', first[1])
            self.assertNotIn('continue-on-error', first[1])
            yield name, run_body(first[1])

    def shell(self, body, environment, cwd=ROOT):
        self.assertTrue(BASH and Path(BASH).exists(), 'Bash unavailable: check blocked, not skipped')
        interpreter = shlex.quote(Path(sys.executable).as_posix())
        prefix = 'python3() { '+interpreter+' -B "$@"; }\n'
        return subprocess.run([BASH, '-e', '-o', 'pipefail', '-c', prefix+body],
                              cwd=cwd, env=dict(os.environ, **environment),
                              capture_output=True, text=True, timeout=20)

    def test_required_jobs_run_after_impact_failure_but_stop_on_workflow_cancellation(self):
        self.assertEqual(set(self.jobs), {'impact', *JOBS})
        expected = ("always() && !cancelled() && github.actor == 'aarbabaev' && "
                    "(github.event_name != 'pull_request' || github.event.pull_request.head.repo.full_name == github.repository)")
        for name in JOBS:
            job = self.jobs[name]
            header = job.split('\n    steps:', 1)[0]
            self.assertIn('\n    needs: impact\n', header)
            condition = re.search(r'^    if: (.+)$',header,re.M)
            self.assertIsNotNone(condition)
            self.assertEqual(condition.group(1), expected, name)
            self.assertNotIn('continue-on-error', header)

    def test_literal_shell_gates_reject_failed_cancelled_skipped_or_missing_impact(self):
        for name, body in self.gates():
            for result in ('failure','cancelled','skipped',''):
                with self.subTest(job=name,result=result):
                    process = self.shell(body, {'IMPACT_RESULT':result,'SELECTION':json.dumps(valid_selection())})
                    self.assertNotEqual(process.returncode,0)
                    self.assertIn('Impact selection failed',process.stderr)

    def test_actual_inline_python_rejects_invalid_selection(self):
        invalid = [None, [], {}, 'invalid', dict(valid_selection(),build=True),
                   dict(valid_selection(),build='TRUE'), dict(valid_selection(),modules=[]),
                   dict(valid_selection(),modules='null'), dict(valid_selection(),modules='{}'),
                   dict(valid_selection(),modules='[1]'),
                   dict(valid_selection(),modules='["wallet-service","wallet-service"]'),
                   dict(valid_selection(),modules='[":clean","$(touch injected)"]'),
                   dict(valid_selection(),extra='false')]
        missing = valid_selection(); del missing['bank_smoke']; invalid.append(missing)
        for name, body in self.gates():
            self.assertIn("python3 - <<'PY'\n",body)
            program = body.split("python3 - <<'PY'\n",1)[1].rsplit('\nPY',1)[0]
            code = compile(program, '<workflow-'+name+'>','exec')
            for value in invalid:
                with self.subTest(job=name,value=value):
                    with patch.dict(os.environ, {'SELECTION':json.dumps(value)}):
                        with self.assertRaises((SystemExit, ValueError, TypeError, KeyError)):
                            exec(code, {})

    def test_literal_shell_gates_accept_valid_boolean_strings_and_allowlisted_modules(self):
        for name, body in self.gates():
            for selected in (False, True):
                value = dict({flag:str(selected).lower() for flag in FLAGS},modules=json.dumps(list(MODULES) if selected else []))
                process=self.shell(body,{'IMPACT_RESULT':'success','SELECTION':json.dumps(value)})
                self.assertEqual(process.returncode,0,(name,process.stderr))

    def test_actual_main_fixture_opt_in_is_scoped_after_successful_isolation(self):
        selected = steps(self.jobs['build'])
        guard_index = next(i for i, (_, step) in enumerate(selected)
                           if re.search(r'^        run:', step, re.M) and 'bash scripts/check-local-ci.sh' in run_body(step))
        gradle_index = next(i for i, (_, step) in enumerate(selected)
                            if re.search(r'^        run:', step, re.M) and 'scripts/run-affected-gradle.py' in run_body(step))
        guard, gradle = selected[guard_index][1], selected[gradle_index][1]
        self.assertLess(guard_index, gradle_index)
        self.assertIn("if: needs.impact.outputs.build == 'true'", guard)
        self.assertIn("if: needs.impact.outputs.build == 'true'", gradle)
        self.assertNotIn('continue-on-error:', guard)
        self.assertNotIn('always()', gradle)
        marker = '-Darmoney.test.isolated.user.fixture=true'
        self.assertIn('JAVA_TOOL_OPTIONS: -Dapi.version=1.44 ' + marker, gradle)
        self.assertEqual(WORKFLOW.read_text(encoding='utf-8').count(marker), 1)
        self.assertNotIn(marker, guard)

    def test_every_selected_flag_has_executable_consumer_not_only_summary(self):
        consumers = {}
        for name in JOBS:
            for _, step in steps(self.jobs[name]):
                condition=re.search(r'^        if: (.+)$',step,re.M)
                if not condition:
                    continue
                flag=re.fullmatch(r"needs\.impact\.outputs\.([a-z_]+) == 'true'",condition.group(1))
                if flag:
                    body=run_body(step)
                    self.assertNotIn('not affected;',body)
                    consumers.setdefault(flag.group(1),[]).append((name,body))
        self.assertEqual(set(consumers),set(FLAGS))
        expected = {'dispatcher':'infra.dispatcher.test_scheduler','build':'run-affected-gradle.py',
                    'android':'assembleDebug','web':'npm run e2e','assistant':'Assistant.Tests.csproj',
                    'kafka':'scripts/kafka-smoke.py','sms':'scripts/sms-sandbox-smoke.py',
                    'baseline':'scripts/test-backup-local-baseline.py','bank_smoke':'scripts/p2p-smoke.py',
                    'assistant_smoke':'scripts/assistant-smoke.py','sharding':'scripts/users-sharding-smoke.py',
                    'replication':'scripts/ledger-replication-smoke.py','sso':'scripts/sso-smoke.py'}
        for flag, command in expected.items():
            if flag == 'web':
                self.assertTrue(any(name == 'web' and web_helper_consumer(body)
                                    for name, body in consumers[flag]), (flag, command))
            else:
                self.assertTrue(any(command in body for _,body in consumers[flag]),(flag,command))

    def test_web_helper_requires_readonly_mount_invocation_and_all_mandatory_commands(self):
        step = next(step for name, step in steps(self.jobs['web'])
                    if name == 'name: Affected web build, unit and browser fixture checks')
        body = run_body(step)
        helper = (ROOT / 'scripts/run-web-ci.sh').read_text(encoding='utf-8')
        self.assertTrue(web_helper_consumer(body, helper))
        for changed in (
                body.replace(',readonly', ''),
                body.replace('source="$PWD/scripts/run-web-ci.sh"', 'source="$PWD/scripts/other.sh"'),
                body.replace('bash /opt/run-web-ci.sh', 'echo /opt/run-web-ci.sh'),
                body.replace('bash /opt/run-web-ci.sh', 'bash /opt/other.sh'),
                body.replace('docker run', '# docker run')):
            with self.subTest(body=changed):
                self.assertFalse(web_helper_consumer(changed, helper))
        for command in ('run_stage E2E npm run e2e',
                        'run_stage AUDIT npm audit --audit-level=high'):
            for changed in (helper.replace(command, '# ' + command),
                            helper.replace(command, command.replace('npm', 'echo', 1))):
                with self.subTest(command=command):
                    self.assertFalse(web_helper_consumer(body, changed))
        with patch.object(Path, 'is_file', return_value=False):
            self.assertFalse(web_helper_consumer(body))
        self.assertFalse(web_helper_consumer(body, helper.replace('  "$@"', '  :')))

    def test_inline_manual_full_selection_executes_without_base(self):
        step = next(step for name,step in steps(self.jobs['impact'])
                    if name=='name: Select affected components and dependent acceptance')
        body=run_body(step)
        with tempfile.TemporaryDirectory(prefix='armoney-workflow-qa-') as temporary:
            directory=Path(temporary)
            env=dict(EVENT_KIND='workflow_dispatch',FULL_CHECKS='true',BASE_SHA='',
                     HEAD_SHA=subprocess.check_output(['git','rev-parse','HEAD'],cwd=ROOT,text=True).strip(),
                     GITHUB_OUTPUT=str(directory/'outputs'),GITHUB_STEP_SUMMARY=str(directory/'summary'))
            result=self.shell(body,env)
            self.assertEqual(result.returncode,0,result.stderr)
            values=dict(line.split('=',1) for line in (directory/'outputs').read_text().splitlines())
            self.assertTrue(all(values[flag]=='true' for flag in FLAGS))
            self.assertEqual(set(json.loads(values['modules'])),set(MODULES))

    def test_web_absence_partial_package_and_execution_failure_are_distinguished(self):
        step = next(step for name,step in steps(self.jobs['web'])
                    if name=='name: Affected web build, unit and browser fixture checks')
        body=run_body(step)
        # Execute the unchanged web branch. Only its external Git metadata and
        # Docker command are intercepted; the latter always fails deliberately.
        prefix = ('git() { printf "%s" "$QA_WEB_TRACKED"; }\n'
                  'docker() { echo QA_DOCKER_COMMAND_REACHED; return 73; }\n')
        for tracked, package in (('',False),('web/src/App.tsx',False),('web/package.json',True)):
            with self.subTest(tracked=tracked,package=package):
                with tempfile.TemporaryDirectory(prefix='armoney-web-gate-') as temporary:
                    directory=Path(temporary)
                    summary=directory/'summary'
                    if package:
                        (directory/'web').mkdir()
                        (directory/'web/package.json').write_text('{}')
                    process=self.shell(prefix+body,{'QA_WEB_TRACKED':tracked,
                                                  'GITHUB_STEP_SUMMARY':str(summary)},cwd=directory)
                    if not tracked:
                        self.assertEqual(process.returncode,0,process.stderr)
                        self.assertIn('No web component exists',summary.read_text())
                        self.assertNotIn('QA_DOCKER_COMMAND_REACHED',process.stdout)
                    elif not package:
                        self.assertNotEqual(process.returncode,0)
                        self.assertIn('without an executable package',process.stderr)
                        self.assertNotIn('QA_DOCKER_COMMAND_REACHED',process.stdout)
                    else:
                        self.assertEqual(process.returncode,73)
                        self.assertIn('QA_DOCKER_COMMAND_REACHED',process.stdout)


if __name__ == '__main__':
    unittest.main(verbosity=2)
