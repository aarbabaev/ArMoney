#!/usr/bin/env python3
"""Isolated local-CI guard regressions; Python stdlib and POSIX Bash only.

Run inside the approved disposable Linux validation image:
    python3 scripts/test-local-ci-guards.py

No real Docker command, runner registration, network request or job is performed.
Entrypoint paths are relocated into a temporary fixture; its control flow is
unchanged. The sleep stub accelerates polling, not the credential-erasure gate.
Workflow checks intentionally support only the small condition grammar used here
and fail closed if a future workflow uses an unrecognized expression.
"""

import os
from pathlib import Path
import re
import shlex
import shutil
import subprocess
import tempfile
import time
import unittest


ROOT = Path(__file__).resolve().parents[1]
BASH = shutil.which("bash")


def workflow_steps():
    """Read named steps from the repository's fixed-indentation workflow."""
    source = (ROOT / ".github/workflows/ci.yml").read_text()
    starts = list(re.finditer(r"^      - (?:name:|uses:).*$", source, re.M))
    result = []
    for index, start in enumerate(starts):
        end = starts[index + 1].start() if index + 1 < len(starts) else len(source)
        block = source[start.start():end]
        name = re.match(r"      - name: (.+)", block)
        if not name:
            continue
        condition = re.search(r"^        if: (.+)$", block, re.M)
        run = re.search(r"^        run: (.+)$", block, re.M)
        body = ""
        if run:
            if run.group(1) == "|":
                lines = block[run.end():].splitlines()[1:]
                for line in lines:
                    if line and not line.startswith("          "):
                        break
                    body += line[10:] + "\n"
            else:
                body = run.group(1)
        result.append((name.group(1), condition.group(1) if condition else None, body))
    return result


def evaluate_cleanup_condition(condition, isolation, failure, sms_project=""):
    if condition is None:
        raise AssertionError("Docker finalizer must explicitly gate isolation")
    expression = condition.removeprefix("${{").removesuffix("}}").strip()
    match = re.fullmatch(
        r"(always|failure)\(\)\s*&&\s*steps\.isolation\.outcome\s*==\s*'success'"
        r"(\s*&&\s*env\.SMS_TEST_PROJECT\s*!=\s*'')?",
        expression,
    )
    if not match:
        raise AssertionError(f"Unrecognized or unsafe cleanup gate: {condition}")
    return (isolation == "success" and (match.group(1) == "always" or failure)
            and (match.group(2) is None or sms_project != ""))


class LocalCiGuards(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="armoney-ci-guards-")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.bin = self.root / "bin"
        self.bin.mkdir()
        self.calls = self.root / "docker-calls"
        self.env = {
            "PATH": str(self.bin) + ":/usr/bin:/bin",
            "HOME": str(self.root),
            "DOCKER_CALLS": str(self.calls),
            "DOCKER_HOST": "unix:///run/inner-docker/docker.sock",
            "ARMONEY_DISPOSABLE_CI": "true",
            "GITHUB_WORKSPACE": "/work/synthetic/repo",
            "LOCAL_DB_PASSWORD": "synthetic-ci-only",
            "SMS_TEST_PROJECT": "armoney-sms-test-" + "a" * 32,
        }
        self.stub("docker", 'printf "%s\\n" "$*" >> "$DOCKER_CALLS"\n')
        self.stub("java", "exit 0\n")
        self.stub("python3", "exit 0\n")

    def stub(self, name, body, directory=None):
        target = (directory or self.bin) / name
        target.write_text("#!/usr/bin/env bash\nset -eu\n" + body)
        target.chmod(0o700)
        return target

    def run_shell(self, script):
        return subprocess.run(
            [BASH, "-e", "-o", "pipefail", "-c", script], cwd=self.root,
            env=self.env, capture_output=True, text=True, timeout=10,
        )

    def docker_calls(self):
        return self.calls.read_text().splitlines() if self.calls.exists() else []

    def finalizers(self):
        steps = [step for step in workflow_steps() if "docker compose" in step[2]
                 and ("logs " in step[2] or re.search(r"\bdown(?:\s|$)", step[2]))]
        self.assertEqual(
            {name for name, _, _ in steps},
            {"Container diagnostics", "Sharding diagnostics and cleanup",
             "SSO diagnostics and cleanup", "Stop containers",
             "Assistant Compose cleanup", "Assistant disposable database cleanup",
             "Clean isolated SMS sandbox"},
            "Workflow finalizers changed; update coverage explicitly",
        )
        return steps

    def run_finalizers(self, isolation, failure):
        for _, condition, body in self.finalizers():
            if evaluate_cleanup_condition(condition, isolation, failure,
                                          self.env.get("SMS_TEST_PROJECT", "")):
                result = self.run_shell(body)
                self.assertEqual(result.returncode, 0, result.stderr)

    def test_rejected_guard_has_zero_docker_calls_even_during_cleanup(self):
        cases = [
            ("host daemon", {"DOCKER_HOST": "unix:///var/run/docker.sock"}, None),
            ("missing marker", {"ARMONEY_DISPOSABLE_CI": ""}, None),
            ("host workspace", {"GITHUB_WORKSPACE": "/home/user/repo"}, None),
            ("local env", {}, ".env"),
            ("compose override", {}, "compose.override.yaml"),
        ]
        guard = shlex.quote(str(ROOT / "scripts/check-local-ci.sh"))
        baseline = self.env.copy()
        for label, changes, forbidden in cases:
            with self.subTest(label=label):
                self.env = {**baseline, **changes}
                if forbidden:
                    (self.root / forbidden).touch()
                result = self.run_shell("bash " + guard)
                self.assertNotEqual(result.returncode, 0)
                self.run_finalizers("failure", True)
                self.assertEqual(self.docker_calls(), [])
                if forbidden:
                    (self.root / forbidden).unlink()
        self.run_finalizers("skipped", True)
        self.run_finalizers("cancelled", True)
        self.assertEqual(self.docker_calls(), [])

    def test_successful_guard_then_failed_test_still_cleans_up(self):
        result = self.run_shell("bash " + shlex.quote(str(ROOT / "scripts/check-local-ci.sh")))
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertTrue(self.docker_calls(), "Successful guard should inspect its stub daemon")
        self.calls.unlink()
        self.assertNotEqual(self.run_shell("exit 42").returncode, 0)
        self.run_finalizers("success", True)
        calls = self.docker_calls()
        self.assertEqual(sum("down --volumes" in call for call in calls), 5, calls)
        self.assertEqual(sum(call.endswith(" down") for call in calls), 1, calls)
        self.assertTrue(any("-p assistant-qa-ci " in call and "down --volumes" in call for call in calls), calls)
        self.assertTrue(any("-p armoney-assistant-check " in call and call.endswith(" down") for call in calls), calls)
        self.assertTrue(any("logs --no-color" in call for call in calls), calls)
        self.assertIn(f"compose -p {self.env['SMS_TEST_PROJECT']} -f compose.sms.yaml down --volumes", calls)

    def sms_finalizer(self):
        steps = [step for step in self.finalizers() if step[0] == "Clean isolated SMS sandbox"]
        self.assertEqual(len(steps), 1)
        return steps[0][1:]

    def test_sms_cleanup_requires_successful_isolation_and_created_project(self):
        condition, body = self.sms_finalizer()
        project = self.env["SMS_TEST_PROJECT"]
        for isolation in ("success", "failure", "skipped", "cancelled"):
            for failure in (False, True):
                for sms_project in ("", project):
                    with self.subTest(isolation=isolation, failure=failure, project=sms_project):
                        self.env["SMS_TEST_PROJECT"] = sms_project
                        if self.calls.exists():
                            self.calls.unlink()
                        allowed = evaluate_cleanup_condition(condition, isolation, failure, sms_project)
                        self.assertEqual(allowed, isolation == "success" and sms_project != "")
                        if allowed:
                            result = self.run_shell(body)
                            self.assertEqual(result.returncode, 0, result.stderr)
                        expected = [f"compose -p {project} -f compose.sms.yaml down --volumes"] if allowed else []
                        self.assertEqual(self.docker_calls(), expected)

    def test_sms_cleanup_rejects_non_sandbox_project_before_docker(self):
        condition, body = self.sms_finalizer()
        for project in ("arman-bank", "armoney-ci-buildmachine", "armoney-sms-test-123", "armoney-sms-test-" + "a" * 32 + ";echo unsafe"):
            with self.subTest(project=project):
                self.env["SMS_TEST_PROJECT"] = project
                self.assertTrue(evaluate_cleanup_condition(condition, "success", True, project))
                result = self.run_shell(body)
                self.assertNotEqual(result.returncode, 0)
                self.assertEqual(self.docker_calls(), [])

    def test_unknown_or_unsafe_cleanup_conditions_fail_closed(self):
        for condition in (None, "always()", "always() && env.SMS_TEST_PROJECT != ''",
                          "always() || steps.isolation.outcome == 'success'",
                          "always() && steps.isolation.outcome == 'success' || env.SMS_TEST_PROJECT != ''"):
            with self.subTest(condition=condition):
                with self.assertRaises(AssertionError):
                    evaluate_cleanup_condition(condition, "success", True, self.env["SMS_TEST_PROJECT"])

    def prepare_entrypoint(self):
        runner = self.root / "runner"
        runner.mkdir()
        work = self.root / "work"
        work.mkdir()
        token = self.root / "registration-token"
        token.write_text("SYNTHETIC_REGISTRATION_TOKEN")
        self.env["FIXTURE_TOKEN"] = str(token)
        self.env["FIXTURE_STARTED"] = str(self.root / "started")
        self.env["FIXTURE_JOB_FILE"] = str(work / "checkout-file.sh")
        self.stub("config.sh", 'printf "synthetic credential\\n" > .credentials\ntouch .runner\n', runner)
        self.stub("run.sh", '[ ! -s "$FIXTURE_TOKEN" ] || exit 97\n'
                  'printf "synthetic checkout file\\n" > "$FIXTURE_JOB_FILE"\n'
                  'printf "start\\n" >> "$FIXTURE_STARTED"\n', runner)
        # No actual sleep duration from production is changed. Substitute only
        # the external clock command to keep a never-erased-token test bounded.
        self.stub("sleep", "/bin/sleep 0.02\n")
        source = (ROOT / "infra/local-ci/entrypoint.sh").read_text()
        replacements = {
            "/opt/runner": str(runner),
            "/run/secrets/registration-token": str(token),
            "/work": str(work),
        }
        for old, new in replacements.items():
            self.assertIn(old, source, f"Entrypoint fixture path contract changed: {old}")
            source = source.replace(old, new)
        entrypoint = self.root / "entrypoint.sh"
        entrypoint.write_text(source)
        return runner, token, entrypoint

    def start_entrypoint(self, entrypoint):
        process = subprocess.Popen(
            [BASH, str(entrypoint)], cwd=self.root, env=self.env,
            stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True,
        )
        def stop():
            if process.poll() is None:
                process.kill()
            process.communicate(timeout=5)
        self.addCleanup(stop)
        return process

    def wait_for_registration(self, process, runner):
        deadline = time.monotonic() + 3
        while not (runner / ".runner").exists() and time.monotonic() < deadline:
            self.assertIsNone(process.poll(), "Entrypoint exited before registration")
            time.sleep(0.005)
        self.assertTrue((runner / ".runner").exists(), "Stub registration did not complete")

    def test_nonempty_token_blocks_start_then_truncation_starts_once(self):
        runner, token, entrypoint = self.prepare_entrypoint()
        process = self.start_entrypoint(entrypoint)
        self.wait_for_registration(process, runner)
        time.sleep(0.08)
        self.assertIsNone(process.poll(), "Runner must wait while registration token is nonempty")
        started = self.root / "started"
        self.assertFalse(started.exists(), "Job started while token remained readable")
        credential = runner / ".credentials"
        job_file = Path(self.env["FIXTURE_JOB_FILE"])
        self.assertEqual(credential.stat().st_mode & 0o777, 0o600,
                         "Registration must create private credentials")
        self.assertFalse(job_file.exists(), "Job file created before credential erasure")
        # Truncate the mounted inode, matching the host controller handshake.
        token.write_text("")
        _, stderr = process.communicate(timeout=5)
        self.assertEqual(process.returncode, 0, stderr)
        self.assertEqual(started.read_text().splitlines(), ["start"])
        self.assertEqual(credential.stat().st_mode & 0o777, 0o600,
                         "Starting the job must preserve private credential permissions")
        self.assertEqual(job_file.stat().st_mode & 0o777, 0o644,
                         "Job files must be readable by non-root bind-mount consumers")

    def test_token_erasure_timeout_fails_without_start(self):
        runner, token, entrypoint = self.prepare_entrypoint()
        process = self.start_entrypoint(entrypoint)
        self.wait_for_registration(process, runner)
        _, stderr = process.communicate(timeout=10)
        self.assertNotEqual(process.returncode, 0, stderr)
        self.assertTrue(token.stat().st_size)
        self.assertFalse((self.root / "started").exists())
        self.assertRegex(stderr.lower(), r"(token|credential).*(timed out|timeout|eras|remov)")

    def test_missing_token_file_is_not_erasure_confirmation(self):
        runner, token, entrypoint = self.prepare_entrypoint()
        process = self.start_entrypoint(entrypoint)
        self.wait_for_registration(process, runner)
        token.unlink()
        _, stderr = process.communicate(timeout=10)
        self.assertNotEqual(process.returncode, 0, stderr)
        self.assertFalse((self.root / "started").exists())


if __name__ == "__main__":
    if os.name != "posix" or BASH is None:
        raise SystemExit("BLOCKED: run in the approved disposable Linux image with Bash; no tests skipped.")
    unittest.main(verbosity=2)

