"""Synthetic diagnostics regression only; no network, browser, Docker or provider."""
import contextlib
import importlib.util
import io
from pathlib import Path
import unittest
from unittest.mock import patch

SPEC = importlib.util.spec_from_file_location("observer_diagnostics", Path(__file__).with_name("sso-provider-acceptance.py"))
HARNESS = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(HARNESS)
PRIVATE = "SYNTHETIC_SECRET_contact@example.invalid_provider_exception_identifier"


class DiagnosticsTest(unittest.TestCase):
    def invoke(self, behavior, argv=None):
        output, errors = io.StringIO(), io.StringIO()
        with contextlib.redirect_stdout(output), contextlib.redirect_stderr(errors), \
                patch.object(HARNESS.sys, "argv", argv or ["harness", "acceptance"]), \
                patch.dict(HARNESS.os.environ, {"CI": "true", "OBSERVER_ACCEPTANCE_ISOLATED": "true"}, clear=True), \
                patch.object(HARNESS, "validate_targets"), patch.object(HARNESS, "validate_keys"), \
                patch.object(HARNESS, "acceptance", side_effect=behavior):
            with self.assertRaises(SystemExit) as failure:
                HARNESS.main()
        self.assertNotIn(PRIVATE, output.getvalue() + errors.getvalue() + str(failure.exception))
        self.assertEqual("", errors.getvalue())
        return str(failure.exception), output.getvalue()

    def test_each_failure_reports_only_the_frozen_phase(self):
        expected = {"START", "KEY_VALIDATION", "READINESS", "ADMIN_LOGIN", "REALM_SETUP", "CLIENT_SETUP",
                    "ACTION_SETUP", "USER_SETUP", "BROWSER_RENDER", "SEND_RPC", "CROSS_NODE", "READ_ONLY",
                    "EXPIRY", "TAB_REMOVAL", "ROOT_REMOVAL", "DISABLE_DELETE", "CLEANUP", "COMPLETE"}
        self.assertEqual(expected, HARNESS.PHASES)
        for label in sorted(expected):
            with self.subTest(phase=label):
                def failed():
                    HARNESS.phase(label)
                    raise RuntimeError(PRIVATE)
                message, output = self.invoke(failed)
                self.assertEqual("FAIL: OBSERVER69 " + label, message)
                self.assertIn("PHASE: OBSERVER69 " + label, output)
                self.assertNotIn("PASS:", output)

    def test_cleanup_does_not_replace_first_failure_or_emit_success(self):
        def failed():
            HARNESS.phase("SEND_RPC")
            HARNESS.remember_failure()
            HARNESS.phase("CLEANUP")
            raise RuntimeError(PRIVATE)
        message, output = self.invoke(failed)
        self.assertEqual("FAIL: OBSERVER69 SEND_RPC", message)
        self.assertNotIn("PASS:", output)

    def test_argument_errors_are_private(self):
        message, _ = self.invoke(None, ["harness", PRIVATE])
        self.assertEqual("FAIL: OBSERVER69 START", message)

    def test_bridge_capture_diagnostics_never_stringify_untrusted_values(self):
        class PrivateObject:
            def __str__(self):
                raise AssertionError("Do not stringify private exception objects")
        output = io.StringIO()
        with contextlib.redirect_stdout(output):
            for value in (PRIVATE, None, {}, PrivateObject()):
                HARNESS.bridge_error(value)
            for value in sorted(HARNESS.BRIDGE_ERRORS):
                HARNESS.bridge_error(value)
        self.assertNotIn(PRIVATE, output.getvalue())
        self.assertTrue(all(line in {"BRIDGE_ERROR: OBSERVER69 " + code for code in HARNESS.BRIDGE_ERRORS}
                            for line in output.getvalue().splitlines()))


if __name__ == "__main__":
    unittest.main()
