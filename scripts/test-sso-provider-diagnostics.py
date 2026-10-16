"""Synthetic diagnostics regression only; no network, browser, Docker or provider."""
import contextlib
import importlib.util
import io
from pathlib import Path
import unittest
from unittest.mock import patch, Mock
import urllib.error

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
                    "READ_ONLY_RPC", "READ_ONLY_EXPIRY", "READ_ONLY_DTO", "READ_ONLY_INSPECT",
                    "READ_ONLY_TIMESTAMP", "READ_ONLY_HMAC_DENIAL", "READ_ONLY_SUBJECT_DENIAL", "READ_ONLY_KEY_DENIAL",
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


    def run_read_only_fixture(self, fault=None):
        flow = {"subject":"original-subject","authentication_session_id":"original-binding"}
        first = {"authentication_session_expires_at":"2026-10-16T00:00:30Z","private":PRIVATE}
        def observe(node, received, key=None, diagnostic=None):
            if received["authentication_session_id"] != flow["authentication_session_id"]:
                return (200 if fault == "READ_ONLY_HMAC_DENIAL" else 503), None, {}
            if received["subject"] != flow["subject"]:
                return (200 if fault == "READ_ONLY_SUBJECT_DENIAL" else 503), None, {}
            if key is not None:
                return (200 if fault == "READ_ONLY_KEY_DENIAL" else 401), None, {}
            if fault == "READ_ONLY_RPC":
                return 503, None, {}
            changed = dict(first)
            if fault == "READ_ONLY_EXPIRY":
                changed["authentication_session_expires_at"]="2026-10-16T00:00:31Z"
            if fault == "READ_ONLY_DTO":
                changed["private"]="different"
            return 200, changed, {}
        def inspect(*args):
            if fault == "READ_ONLY_INSPECT":
                raise RuntimeError(PRIVATE)
            return {"root_timestamp":43 if fault == "READ_ONLY_TIMESTAMP" else 42}
        with patch.object(HARNESS,"observer",side_effect=observe) as observer, \
                patch.object(HARNESS,"controls",side_effect=inspect) as controls:
            if fault is None:
                with contextlib.redirect_stdout(io.StringIO()):
                    HARNESS.read_only_checks("http://sso-b:8080",flow,first,{"root_timestamp":42})
                self.assertEqual(6,observer.call_count)
                self.assertEqual(3,controls.call_count)
            else:
                message, output = self.invoke(lambda:HARNESS.read_only_checks(
                    "http://sso-b:8080",flow,first,{"root_timestamp":42}))
                self.assertEqual("FAIL: OBSERVER69 "+fault,message)
                self.assertNotIn(PRIVATE,output)

    def test_read_only_subphases_preserve_every_repetition_and_negative_denial(self):
        self.run_read_only_fixture()
        for fault in ("READ_ONLY_RPC","READ_ONLY_EXPIRY","READ_ONLY_DTO","READ_ONLY_INSPECT",
                      "READ_ONLY_TIMESTAMP","READ_ONLY_HMAC_DENIAL","READ_ONLY_SUBJECT_DENIAL","READ_ONLY_KEY_DENIAL"):
            with self.subTest(phase=fault):
                self.run_read_only_fixture(fault)

    def boundary_exchange(self, status=200, payload=b"{}", headers=None, open_error=None, read_error=None):
        codes, output = [], io.StringIO()
        class Response:
            def __enter__(self):
                return self
            def __exit__(self, *args):
                pass
            def read(self, limit):
                self.limit = limit
                if read_error is not None:
                    raise read_error
                return payload
        response = Response()
        response.status = status
        response.headers = {"Cache-Control": "no-store"} if headers is None else headers
        opener = Mock()
        opener.open.side_effect = open_error
        opener.open.return_value = response
        failure = None
        with patch.object(HARNESS.urllib.request, "build_opener", return_value=opener), \
                patch.object(HARNESS.ssl, "create_default_context", return_value=object()), \
                contextlib.redirect_stdout(output), contextlib.redirect_stderr(output):
            try:
                HARNESS.expect(200, HARNESS.observer("http://sso-b:8080", {}, key="synthetic-only", diagnostic=codes.append), "observer")
            except Exception as error:
                failure = error
            for code in codes:
                HARNESS.observer_boundary(code)
        self.assertEqual(1, opener.open.call_count, "Diagnostic path must never retry")
        self.assertEqual(8, opener.open.call_args.kwargs["timeout"])
        self.assertNotIn(PRIVATE, output.getvalue())
        self.assertTrue(set(codes) <= HARNESS.OBSERVER_BOUNDARIES)
        self.assertTrue(all(line in {"OBSERVER_BOUNDARY: OBSERVER69 " + code for code in HARNESS.OBSERVER_BOUNDARIES}
                            for line in output.getvalue().splitlines()))
        return codes, failure, response

    def test_http_status_precedes_json_cache_and_error_body_validation(self):
        for kwargs, expected in [
                ({"status":503,"payload":b""}, ["HTTP_503"]),
                ({"status":404,"payload":PRIVATE.encode()}, ["HTTP_404","INVALID_JSON"]),
                ({"status":503,"payload":b"","headers":{}}, ["HTTP_503","CACHE_POLICY"]),
                ({"status":401,"payload":HARNESS.json.dumps({"private":PRIVATE}).encode()}, ["HTTP_401","ERROR_BODY"]),
                ({"status":418,"payload":b""}, ["HTTP_OTHER"])]:
            with self.subTest(expected=expected):
                codes, failure, response = self.boundary_exchange(**kwargs)
                self.assertEqual(expected, codes)
                self.assertIsNotNone(failure, "Existing denial assertions must remain effective")
                self.assertEqual(16385,response.limit)

    def test_http_error_response_uses_status_not_reason_or_body_text(self):
        error = urllib.error.HTTPError("http://sso-b:8080/private", 405, PRIVATE,
                                       {"Cache-Control":"no-store"}, io.BytesIO(b""))
        codes, failure, _ = self.boundary_exchange(open_error=error)
        self.assertEqual(["HTTP_405"],codes)
        self.assertIsInstance(failure,AssertionError)

    def test_transport_failures_are_finite_and_preserve_header_status_if_available(self):
        for kwargs, expected in [
                ({"open_error":TimeoutError(PRIVATE)}, ["TRANSPORT_TIMEOUT"]),
                ({"open_error":urllib.error.URLError(TimeoutError(PRIVATE))}, ["TRANSPORT_TIMEOUT"]),
                ({"open_error":urllib.error.URLError(PRIVATE)}, ["TRANSPORT_IO"]),
                ({"read_error":TimeoutError(PRIVATE)}, ["HTTP_200","TRANSPORT_TIMEOUT"]),
                ({"read_error":HARNESS.http.client.IncompleteRead(b"private",10)}, ["HTTP_200","TRANSPORT_IO"])]:
            with self.subTest(expected=expected):
                codes, failure, _ = self.boundary_exchange(**kwargs)
                self.assertEqual(expected,codes)
                self.assertIsNotNone(failure)

    def test_diagnostics_preserve_success_and_response_size_guard(self):
        codes, failure, response = self.boundary_exchange(payload=b"{}")
        self.assertEqual(["HTTP_200"],codes)
        self.assertIsNone(failure)
        self.assertEqual(16385,response.limit)
        codes, failure, _ = self.boundary_exchange(payload=b"x"*16385)
        self.assertEqual(["HTTP_200"],codes)
        self.assertIsInstance(failure,AssertionError)
        output=io.StringIO()
        with contextlib.redirect_stdout(output):
            HARNESS.observer_boundary(PRIVATE)
            HARNESS.observer_boundary({"private":PRIVATE})
        self.assertEqual("OBSERVER_BOUNDARY: OBSERVER69 UNKNOWN\n"*2,output.getvalue())


if __name__ == "__main__":
    unittest.main()
