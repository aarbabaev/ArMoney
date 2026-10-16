"""Synthetic diagnostics checks only; no provider, network, browser or Docker."""
import contextlib
import importlib.util
import io
from pathlib import Path
import threading
import unittest
from types import SimpleNamespace
from unittest.mock import patch

SPEC = importlib.util.spec_from_file_location("visibility_harness", Path(__file__).with_name("sso-session-visibility-acceptance.py"))
HARNESS = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(HARNESS)
PRIVATE = "synthetic-private-token-contact@example.invalid"


class DiagnosticsTest(unittest.TestCase):
    def test_partial_visibility_never_satisfies_committed_assertion(self):
        for user, client in ((False, False), (True, False), (False, True), (True, True)):
            value = {"outcome": "VISIBLE" if user and client else "NOT_VISIBLE",
                     "user_session": user, "client_session": client}
            with contextlib.redirect_stdout(io.StringIO()):
                if user and client:
                    HARNESS.committed_observation(value)
                else:
                    with self.assertRaises(AssertionError):
                        HARNESS.committed_observation(value)

    def test_strict_observation_dto_rejects_coercion_and_extra_private_fields(self):
        base = {"outcome": "VISIBLE", "user_session": True, "client_session": True}
        for value in (dict(base, private=PRIVATE), dict(base, user_session=1),
                      dict(base, outcome=PRIVATE), dict(base, client_session=False), None):
            with self.assertRaises(AssertionError):
                HARNESS.observation(value)

    def test_browser_wait_blocks_before_response_and_rejects_failure(self):
        outcome = HARNESS.BrowserOutcome()
        with self.assertRaises(AssertionError):
            outcome.await_response(0.001)
        outcome.finish(response_complete=True)
        outcome.await_response(0.001)
        outcome.finish(response_complete=True, failed=True)
        with self.assertRaises(AssertionError):
            outcome.await_response(0.001)

    def test_first_failure_survives_cleanup_and_private_exception_is_not_printed(self):
        def action():
            HARNESS.phase("PRE")
            raise RuntimeError(PRIVATE)
        def cleanup():
            raise RuntimeError(PRIVATE)
        output = io.StringIO()
        with contextlib.redirect_stdout(output), patch.object(HARNESS, "acceptance",
                side_effect=lambda: HARNESS.run_with_cleanup(action, cleanup)):
            with self.assertRaises(SystemExit) as error:
                HARNESS.main()
        self.assertEqual("FAIL: SSO_VIS_001 PRE", str(error.exception))
        self.assertNotIn(PRIVATE, output.getvalue() + str(error.exception))
        self.assertNotIn("PASS:", output.getvalue())

    def test_experiment_waits_for_release_and_browser_before_committed_query(self):
        calls = []
        released = threading.Event()
        facts = {"subject": "synthetic-user", "client_id": "synthetic-client"}
        both = {"outcome": "VISIBLE", "user_session": True, "client_session": True}
        def control(node, operation, body):
            calls.append((node, operation))
            if operation == "arm":
                return {"experiment_token": "synthetic-experiment-token"}
            if operation == "release":
                released.set()
            if operation == "observe":
                return both
        def browser(*args):
            self.assertTrue(released.wait(1))
            args[-1].finish(response_complete=True)
        with patch.object(HARNESS, "control", side_effect=control), \
                patch.object(HARNESS, "wait_held", return_value=facts), \
                patch.object(HARNESS, "browser_login", side_effect=browser), \
                contextlib.redirect_stdout(io.StringIO()):
            HARNESS.experiment("A", "B", "synthetic-user", "synthetic-client", PRIVATE, PRIVATE)
        self.assertEqual([("A", "arm"), ("B", "observe"), ("A", "release"),
                          ("B", "observe"), ("A", "cancel")], calls)

    def test_successful_pre_visibility_cannot_mask_failed_post_visibility(self):
        both = {"outcome": "VISIBLE", "user_session": True, "client_session": True}
        missing = {"outcome": "NOT_VISIBLE", "user_session": True, "client_session": False}
        values = iter([both, missing])
        cancelled = []
        def control(node, operation, body):
            if operation == "arm":
                return {"experiment_token": "synthetic-experiment-token"}
            if operation == "observe":
                return next(values)
            if operation == "cancel":
                cancelled.append(True)
        with patch.object(HARNESS, "control", side_effect=control), \
                patch.object(HARNESS, "wait_held", return_value={"subject":"user", "client_id":"client"}), \
                patch.object(HARNESS, "browser_login", side_effect=lambda *args: args[-1].finish(response_complete=True)), \
                contextlib.redirect_stdout(io.StringIO()):
            with self.assertRaises(AssertionError):
                HARNESS.experiment("A", "B", "user", "client", PRIVATE, PRIVATE)
        self.assertEqual([True], cancelled)

    def test_hook_failure_post_visibility_is_diagnostic_without_rollback_assertion(self):
        values = {"outcome": "NOT_VISIBLE", "user_session": True, "client_session": False}
        def control(node, operation, body):
            if operation == "arm":
                return {"experiment_token": "synthetic-experiment-token"}
            return values if operation == "observe" else None
        output = io.StringIO()
        with patch.object(HARNESS, "control", side_effect=control), \
                patch.object(HARNESS, "wait_held", return_value={"subject":"user", "client_id":"client"}), \
                patch.object(HARNESS, "browser_login", side_effect=lambda *args: args[-1].finish(response_complete=True)), \
                contextlib.redirect_stdout(output):
            HARNESS.experiment("A", "B", "user", "client", PRIVATE, PRIVATE, mode="FAIL")
        self.assertIn("FAIL_POST NOT_VISIBLE", output.getvalue())
        self.assertNotIn(PRIVATE, output.getvalue())

    def test_failed_browser_before_held_fails_early_with_finite_diagnostics(self):
        outcome = HARNESS.BrowserOutcome()
        outcome.progress(stage="USERNAME")
        outcome.finish(failed=True)
        output = io.StringIO()
        state = {"state": "ARMED", "tuple": None, "hook_boundary": "NOT_ENTERED"}
        with patch.object(HARNESS, "control", return_value=state) as control, \
                contextlib.redirect_stdout(output):
            with self.assertRaises(AssertionError):
                HARNESS.wait_held("A", PRIVATE, outcome)
        self.assertEqual(1, control.call_count)
        self.assertIn("HOOK: SSO_VIS_001 NOT_ENTERED", output.getvalue())
        self.assertIn("BROWSER: SSO_VIS_001 USERNAME FAILED UNKNOWN", output.getvalue())
        self.assertNotIn(PRIVATE, output.getvalue())

    def test_pending_browser_timeout_reports_snapshot_without_accepting_visibility(self):
        outcome = HARNESS.BrowserOutcome()
        outcome.progress(stage="SUBMIT")
        output = io.StringIO()
        state = {"state": "ARMED", "tuple": None, "hook_boundary": "ENTERED"}
        with patch.object(HARNESS, "control", return_value=state), \
                patch.object(HARNESS.time, "monotonic", side_effect=[0, 0, 11]), \
                patch.object(HARNESS.time, "sleep"), contextlib.redirect_stdout(output):
            with self.assertRaises(AssertionError):
                HARNESS.wait_held("A", PRIVATE, outcome)
        self.assertIn("HOOK: SSO_VIS_001 ENTERED", output.getvalue())
        self.assertIn("BROWSER: SSO_VIS_001 SUBMIT PENDING UNKNOWN", output.getvalue())

    def test_hook_boundary_dto_rejects_arbitrary_or_missing_fields(self):
        base = {"state": "ARMED", "tuple": None, "hook_boundary": "NOT_ENTERED"}
        self.assertEqual(base, HARNESS.state_value(base))
        for value in (dict(base, hook_boundary=PRIVATE), {"state": "ARMED", "tuple": None},
                      dict(base, private=PRIVATE)):
            with self.assertRaises(AssertionError):
                HARNESS.state_value(value)

    def test_browser_diagnostics_reject_private_values(self):
        outcome = HARNESS.BrowserOutcome()
        for argument in ("stage", "result", "form"):
            with self.assertRaises(AssertionError):
                outcome.progress(**{argument: PRIVATE})
        output = io.StringIO()
        with contextlib.redirect_stdout(output):
            outcome.diagnose()
        self.assertEqual("BROWSER: SSO_VIS_001 START PENDING UNKNOWN\n", output.getvalue())

    def test_unexpected_profile_html_ends_browser_with_finite_facts(self):
        class ContextManager:
            def __init__(self, value): self.value = value
            def __enter__(self): return self.value
            def __exit__(self, *args): pass
        class Page:
            main_frame = object()
            def goto(self, *args, **kwargs): pass
            def locator(self, selector):
                return SimpleNamespace(fill=lambda *a: None, click=lambda **kw: None,
                    count=lambda: int("firstName" in selector))
            def expect_event(self, *args, **kwargs):
                return ContextManager(SimpleNamespace(value=SimpleNamespace(response=lambda:
                    SimpleNamespace(status=200))))
        page = Page()
        browser = SimpleNamespace(new_context=lambda: SimpleNamespace(route=lambda *a: None, new_page=lambda: page), close=lambda: None)
        playwright = SimpleNamespace(chromium=SimpleNamespace(launch=lambda: browser))
        module = SimpleNamespace(sync_playwright=lambda: ContextManager(playwright))
        outcome = HARNESS.BrowserOutcome()
        with patch.dict(HARNESS.sys.modules, {"playwright": SimpleNamespace(), "playwright.sync_api": module}):
            HARNESS.browser_login("A", "client", PRIVATE, PRIVATE, "COMPLETE", False, outcome)
        self.assertTrue(outcome.done.is_set())
        self.assertFalse(outcome.response_complete)
        output = io.StringIO()
        with contextlib.redirect_stdout(output): outcome.diagnose()
        self.assertEqual("BROWSER: SSO_VIS_001 RESPONSE HTTP_200 PROFILE\n", output.getvalue())

    def test_browser_exception_is_sanitized_at_launch_boundary(self):
        class ContextManager:
            def __enter__(self):
                return SimpleNamespace(chromium=SimpleNamespace(launch=lambda: (_ for _ in ()).throw(RuntimeError(PRIVATE))))
            def __exit__(self, *args): pass
        module = SimpleNamespace(sync_playwright=lambda: ContextManager())
        outcome = HARNESS.BrowserOutcome()
        output = io.StringIO()
        with patch.dict(HARNESS.sys.modules, {"playwright": SimpleNamespace(), "playwright.sync_api": module}), \
                contextlib.redirect_stdout(output), contextlib.redirect_stderr(output):
            HARNESS.browser_login("A", "client", PRIVATE, PRIVATE, "COMPLETE", False, outcome)
            outcome.diagnose()
        self.assertEqual("BROWSER: SSO_VIS_001 LAUNCH FAILED UNKNOWN\n", output.getvalue())
        self.assertTrue(outcome.failed)


if __name__ == "__main__":
    unittest.main()
