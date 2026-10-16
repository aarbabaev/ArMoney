"""Synthetic diagnostics checks only; no provider, network, browser or Docker."""
import contextlib
import importlib.util
import io
from pathlib import Path
import threading
import unittest
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


if __name__ == "__main__":
    unittest.main()
