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
                    "ACTION_SETUP", "ACTION_LIST", "ACTION_AVAILABLE", "ACTION_REGISTER", "ACTION_CONFIG",
                    "USER_SETUP", "BROWSER_RENDER", "SEND_RPC", "CROSS_NODE", "READ_ONLY",
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

    def action_transport(self, registration_status=204, available=None):
        # Faithful pinned26.7.4 boundary: unregistered metadata is a name/providerId
        # map, registerRequiredAction(Map) returns void/204, registered entries
        # use RequiredActionProviderRepresentation with alias/providerId.
        alias = "armoney-verify-email-code"
        registered = [{"alias": "UPDATE_PASSWORD", "providerId": "UPDATE_PASSWORD", "enabled": True, "defaultAction": True},
                      {"alias": alias, "providerId": alias, "enabled": True, "defaultAction": False}]
        root = "http://fixture.invalid/admin/realms/armoney"
        calls = []
        sequence = [
            ("POST", root + "/clients", 201, None),
            ("GET", root + "/authentication/required-actions", 200, registered[:1]),
            ("GET", root + "/authentication/unregistered-required-actions", 200,
             available if available is not None else [{"name": "ArMoney email code", "providerId": alias}]),
            ("POST", root + "/authentication/register-required-action", registration_status, None),
            ("GET", root + "/authentication/required-actions", 200, registered),
            ("PUT", root + "/authentication/required-actions/UPDATE_PASSWORD", 204, None),
            ("PUT", root + "/authentication/required-actions/" + alias, 204, None)]

        def transport(method, target, data=None, headers=None, form=False):
            self.assertLess(len(calls), len(sequence))
            expected_method, expected_url, status, body = sequence[len(calls)]
            self.assertEqual((expected_method, expected_url), (method, target))
            if target.endswith("/register-required-action"):
                self.assertEqual({"providerId": alias, "name": "Disposable email verification"}, data)
            if method == "PUT":
                self.assertIs(data["defaultAction"], False)
                self.assertIs(data["enabled"], data["alias"] == alias)
            calls.append((method, target))
            return status, body, {}
        return transport, calls, sequence

    def test_pinned_admin_contract_registers_then_enables_only_installed_email_action(self):
        transport, calls, sequence = self.action_transport()
        with patch.object(HARNESS, "request", side_effect=transport), contextlib.redirect_stdout(io.StringIO()):
            HARNESS.provision_client_and_action("http://fixture.invalid", {"Authorization": "synthetic-private"})
        self.assertEqual(len(sequence), len(calls))
        self.assertEqual("ACTION_CONFIG", HARNESS.current_phase)

    def test_registration_rejects_unexpected_creation_status_without_configuration(self):
        transport, calls, _ = self.action_transport(registration_status=201)
        with patch.object(HARNESS, "request", side_effect=transport), contextlib.redirect_stdout(io.StringIO()):
            with self.assertRaises(AssertionError):
                HARNESS.provision_client_and_action("http://fixture.invalid", {})
        self.assertEqual(4, len(calls))
        self.assertEqual("ACTION_REGISTER", HARNESS.current_phase)

    def test_missing_installed_provider_never_attempts_registration(self):
        transport, calls, _ = self.action_transport(available=[{"name": "Other", "providerId": "other"}])
        with patch.object(HARNESS, "request", side_effect=transport), contextlib.redirect_stdout(io.StringIO()):
            with self.assertRaises(AssertionError):
                HARNESS.provision_client_and_action("http://fixture.invalid", {})
        self.assertEqual(3, len(calls))
        self.assertEqual("ACTION_AVAILABLE", HARNESS.current_phase)


if __name__ == "__main__":
    unittest.main()
