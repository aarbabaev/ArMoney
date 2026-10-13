"""Offline control-flow tests; synthetic probes do not establish runtime readiness."""
import importlib.util
from pathlib import Path
import subprocess
import unittest
from types import SimpleNamespace

spec = importlib.util.spec_from_file_location('ready', Path(__file__).with_name('assistant-ready.py'))
ready = importlib.util.module_from_spec(spec)
spec.loader.exec_module(ready)


class Clock:
    def __init__(self): self.now = 0
    def read(self): return self.now
    def sleep(self, amount): self.now += amount


class Readiness(unittest.TestCase):
    def test_startup_waits_until_every_service_ready(self):
        clock = Clock()
        observed = set()
        def check(project, service, path, expected, remaining):
            observed.add(service)
            return service != 'auth-service' or clock.now >= 2
        ready.wait_ready('assistant-qa-ci', 5, clock=clock.read, sleep=clock.sleep, check=check)
        self.assertEqual(clock.now, 2)
        self.assertEqual(observed, set(ready.BANK_SERVICES) | {'ai-assistant-service'})

    def test_failure_is_bounded_and_sanitized(self):
        clock = Clock()
        with self.assertRaisesRegex(RuntimeError, 'Readiness deadline exceeded:'):
            ready.wait_ready('assistant-qa-ci', 2.5, clock=clock.read, sleep=clock.sleep, check=lambda *args: False)
        self.assertEqual(clock.now, 2.5)

    def test_project_rejects_live_and_command_injection(self):
        for project in ('arman-bank', 'assistant-qa-', 'assistant-qa-ci;evil', 'assistant-qa-CI', '../assistant-qa-ci'):
            with self.assertRaises(ValueError): ready.validate_project(project)
        self.assertEqual(ready.validate_project('assistant-qa-ci'), 'assistant-qa-ci')

    def test_configured_assistant_and_banks_require_ready200(self):
        calls = []
        ready.wait_ready('assistant-qa-ci', check=lambda *args: calls.append(args) or True)
        self.assertEqual(len(calls), 7)
        self.assertTrue(all(call[2:4] == ('/health/ready', 200) for call in calls))

    def test_probe_is_get_only_and_timeout_is_remaining_budget(self):
        captured = []
        def runner(command, **kwargs):
            captured.append((command, kwargs))
            return SimpleNamespace(returncode=0, stdout='200', stderr='secret diagnostic must not print')
        self.assertTrue(ready.probe('assistant-qa-ci', 'auth-service', '/health/ready', 200, 0.5, runner))
        command, options = captured[0]
        self.assertEqual(options['timeout'], 0.5)
        self.assertIn('assistant-qa-ci_bank', command)
        self.assertNotIn('--request', command)
        self.assertNotIn('--data', command)
        self.assertNotIn('--location', command)

    def test_probe_errors_are_retryable_without_diagnostics(self):
        def runner(*args, **kwargs): raise subprocess.TimeoutExpired('sensitive', 1)
        self.assertFalse(ready.probe('assistant-qa-ci', 'auth-service', '/health/ready', 200, 1, runner))


if __name__ == '__main__': unittest.main()
