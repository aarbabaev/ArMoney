"""Synthetic diagnostics regression only; no network, browser, Docker or provider."""
import contextlib
import importlib.util
import io
from pathlib import Path
import unittest
from unittest.mock import patch, Mock
import urllib.error
import threading
from types import SimpleNamespace

SPEC = importlib.util.spec_from_file_location("observer_diagnostics", Path(__file__).with_name("sso-provider-acceptance.py"))
HARNESS = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(HARNESS)
PRIVATE = "SYNTHETIC_SECRET_contact@example.invalid_provider_exception_identifier"


class DiagnosticsTest(unittest.TestCase):
    def test_real_callback_listener_records_only_strict_bound_denial_and_no_inputs(self):
        labels, expected = [], {"state": "original-state"}
        listener = HARNESS.ObserverCallbackListener(labels, expected, "http://sso-a:8080/realms/armoney")
        output = io.StringIO()
        with contextlib.redirect_stdout(output):
            self.assertEqual("BOUND_DENIAL", listener.record("/observer-callback?error=access_denied&state=original-state"))
            for path in ("/observer-callback?code=" + PRIVATE + "&state=original-state", "/other?error=access_denied&state=original-state",
                         "/observer-callback?error=access_denied&state=other", "/observer-callback?error=access_denied&state=original-state&access_token=" + PRIVATE):
                self.assertEqual("INVALID", listener.record(path))
            expected["state"] = None
            self.assertEqual("INVALID", listener.record("/observer-callback?error=access_denied&state=original-state"))
        self.assertEqual(["BOUND_DENIAL"] + ["INVALID"]*5, labels)
        self.assertNotIn(PRIVATE, output.getvalue())
        self.assertNotIn("original-state", output.getvalue())

    def test_callback_listener_binds_only_container_loopback_and_reaps_server_thread(self):
        instances = []
        class Server:
            def __init__(self, address, handler):
                self.address, self.handler, self.stop, self.closed = address, handler, threading.Event(), False
                instances.append(self)
            def serve_forever(self, poll_interval):
                self.poll_interval = poll_interval
                self.stop.wait(2)
            def shutdown(self): self.stop.set()
            def server_close(self): self.closed = True
        listener = HARNESS.ObserverCallbackListener([], {"state": None}, "http://sso-a:8080/realms/armoney")
        with patch.object(HARNESS.http.server, "HTTPServer", Server):
            listener.start()
            thread = listener.thread
            listener.close()
        self.assertEqual(("127.0.0.1", 18766), instances[0].address)
        self.assertTrue(instances[0].closed)
        self.assertFalse(thread.is_alive())
        self.assertIsNone(listener.server)

    def test_unmarked_existing_user_has_complete_stock_profile_but_no_verification_authority(self):
        user = HARNESS.unmarked_user("+971501234567", "synthetic@example.invalid", "private-synthetic-password")
        self.assertEqual("Synthetic", user["firstName"])
        self.assertEqual("Observer", user["lastName"])
        self.assertIs(user["emailVerified"], False)
        self.assertEqual(["armoney-verify-email-code"], user["requiredActions"])
        self.assertEqual({"username", "email", "firstName", "lastName", "enabled", "emailVerified", "requiredActions", "credentials"}, set(user))
        self.assertEqual([{"type": "password", "value": "private-synthetic-password", "temporary": False}], user["credentials"])

    def test_negative_dom_observation_emits_only_closed_categories_and_bounded_counts(self):
        class Page:
            url = "http://sso-a:8080/realms/armoney/login-actions/authenticate?secret=" + PRIVATE
            def locator(self, selector):
                return SimpleNamespace(count=lambda: 99 if selector == "#input-error" else 0)
        facts = HARNESS.negative_dom_diagnostic(Page())
        self.assertEqual("NODE_A", facts["origin"])
        self.assertEqual("AUTHENTICATE", facts["path"])
        self.assertEqual(16, facts["input_error"])
        self.assertFalse(facts["inspection_failed"])
        self.assertNotIn(PRIVATE, HARNESS.json.dumps(facts))
        self.assertNotIn("secret", HARNESS.json.dumps(facts))
        class Other(Page): url = "https://" + PRIVATE + "/private"
        facts = HARNESS.negative_dom_diagnostic(Other())
        self.assertEqual("OTHER", facts["origin"]); self.assertEqual("OTHER", facts["path"])
        class Broken(Page):
            def locator(self, selector): raise RuntimeError(PRIVATE)
        self.assertTrue(HARNESS.negative_dom_diagnostic(Broken())["inspection_failed"])
        for url, category in (("about:blank", "ABOUT_BLANK"), ("chrome-error://chromewebdata/", "CHROME_ERROR")):
            page = Page(); page.url = url
            self.assertEqual(category, HARNESS.negative_dom_diagnostic(page)["browser_page"])

    def test_navigation_failure_labels_never_copy_browser_error_or_url_text(self):
        self.assertEqual("ERR_BLOCKED_BY_LOCAL_NETWORK_ACCESS", HARNESS.negative_network_failure("net::ERR_BLOCKED_BY_LOCAL_NETWORK_ACCESS"))
        self.assertEqual("OTHER", HARNESS.negative_network_failure("net::ERR_FAILED " + PRIVATE))
        self.assertEqual("OTHER", HARNESS.negative_network_failure({"private": PRIVATE}))
        facts = HARNESS.negative_navigation_diagnostic("http://127.0.0.1:18766/observer-callback?state=" + PRIVATE)
        self.assertEqual({"origin": "CALLBACK", "path": "CALLBACK"}, facts)
        self.assertNotIn(PRIVATE, HARNESS.json.dumps(facts))

    def test_unmarked_denial_accepts_only_original_bound_access_denied_callback(self):
        base = "http://127.0.0.1:18766/observer-callback"
        issuer = "http://sso-a:8080/realms/armoney"
        state = "synthetic-original-state"
        query = "?error=access_denied&state=" + state
        self.assertEqual("BOUND_DENIAL", HARNESS.denied_callback_label(base + query, state, issuer))
        self.assertEqual("BOUND_DENIAL", HARNESS.denied_callback_label(base + query + "&iss=" + HARNESS.urllib.parse.quote(issuer, safe=""), state, issuer))
        for url in (base + "?code=secret-code&state=" + state, base + query + "&access_token=private",
                    base + query + "&id_token=private", base + query + "&refresh_token=private", base + query + "&error=access_denied",
                    base + query + "&state=" + state, base + query + "&iss=http://wrong.invalid", base + query + "#fragment",
                    base + query + "%XX", base + query.replace(state, "wrong"), base + "?error=server_error&state=" + state,
                    base.replace("127.0.0.1", "foreign.invalid") + query, base + "/extra" + query):
            with self.subTest(kind="invalid_callback"):
                self.assertEqual("INVALID", HARNESS.denied_callback_label(url, state, issuer))
        self.assertEqual("INVALID", HARNESS.denied_callback_label(base + query, None, issuer))

    def test_negative_callback_diagnostics_never_reveal_fields_values_or_state(self):
        url = "http://127.0.0.1:18766/observer-callback?error=access_denied&state=original&error_description=" + PRIVATE + "&session_state=private&unknownprivate=secret"
        facts = HARNESS.denied_callback_diagnostic(url, "original", "http://sso-a:8080/realms/armoney")
        self.assertEqual("FIELDS", facts["reason"])
        self.assertTrue(facts["error_description_present"])
        self.assertTrue(facts["session_state_present"])
        self.assertTrue(facts["access_denied"])
        self.assertTrue(facts["state_matches"])
        self.assertEqual(1, facts["other_key_count"])
        for key, value in facts.items():
            if key not in {"reason", "other_key_count"}: self.assertIs(type(value), bool)
        serialized = HARNESS.json.dumps(facts)
        for private in (PRIVATE, "original", "unknownprivate", "secret"):
            self.assertNotIn(private, serialized)
        for suffix, reason in (("&state=other", "DUPLICATE"), ("&code=private", "FIELDS"), ("%XX", "SYNTAX")):
            observed = HARNESS.denied_callback_diagnostic("http://127.0.0.1:18766/observer-callback?error=access_denied&state=original" + suffix, "original", "issuer")
            self.assertEqual(reason, observed["reason"])
        self.assertTrue(HARNESS.denied_callback_diagnostic("http://127.0.0.1:18766/observer-callback?code=private&state=original", "original", "issuer")["code_or_token_present"])

    def test_registration_flow_uses_real_creation_then_password_and_no_default_action(self):
        calls = []
        rows = [{"id": "00000000-0000-0000-0000-000000000001", "providerId": "registration-page-form"},
                {"id": "00000000-0000-0000-0000-000000000002", "providerId": "armoney-registration-creation"},
                {"id": "00000000-0000-0000-0000-000000000003", "providerId": "registration-password-action"}]
        def request(method, url, body=None, headers=None):
            calls.append((method, url, body))
            if method == "GET":
                return 200, [dict(x) for x in (rows[1:] if "registration-form/executions" in url else rows)], {}
            return (201 if method == "POST" else 204), None, {}
        with patch.object(HARNESS, "request", side_effect=request):
            HARNESS.provision_registration_flow("http://sso-a:8080/admin/realms/armoney", {})
        leaf_posts = [body["provider"] for method, url, body in calls if method == "POST" and url.endswith("/executions/execution")]
        self.assertEqual(["armoney-registration-creation", "registration-password-action"], leaf_posts)
        self.assertEqual(3, sum(method == "PUT" and url.endswith("/executions") and body["requirement"] == "REQUIRED" for method, url, body in calls))
        self.assertEqual({"registrationFlow": "observer-genuine-registration"}, calls[-1][2])

    def test_canonical_persisted_user_readback_rejects_other_username_or_noncanonical_id(self):
        phone = "+971501234567"
        valid = {"username": phone, "id": "00000000-0000-0000-0000-000000000001"}
        with patch.object(HARNESS, "request", return_value=(200, [valid], {})):
            self.assertEqual(valid["id"], HARNESS.canonical_user("http://sso-a:8080/users", {}, phone))
        for rows in ([], [valid, valid], [{**valid, "username": "other"}], [{**valid, "id": "private-not-uuid"}]):
            with self.subTest(rows=len(rows)), patch.object(HARNESS, "request", return_value=(200, rows, {})):
                with self.assertRaises((AssertionError, ValueError)):
                    HARNESS.canonical_user("http://sso-a:8080/users", {}, phone)

    def test_browser_submission_waits_for_complete_valid_http_response_before_baseline(self):
        for status, content_type in ((200, "text/html; charset=utf-8"), (503, "text/html"), (200, "application/json")):
            with self.subTest(status=status, content_type=content_type):
                submitted, release, done = threading.Event(), threading.Event(), threading.Event()
                frame = object()
                request = SimpleNamespace(method="POST", url="http://sso-a:8080/realms/armoney/login-actions/required-action?private=" + PRIVATE,
                    frame=frame, is_navigation_request=lambda: True,
                    response=lambda: SimpleNamespace(status=status, headers={"content-type":content_type}))
                class Finished:
                    value = request
                    def __enter__(self):
                        return self
                    def __exit__(self, *unused):
                        if not release.wait(2):
                            raise AssertionError("synthetic browser completion timeout")
                class Page:
                    main_frame = frame
                    def expect_event(inner, name, predicate, timeout):
                        self.assertEqual((name, timeout), ("requestfinished", 12000))
                        self.assertTrue(predicate(request))
                        self.assertFalse(predicate(SimpleNamespace(**{**request.__dict__, "method":"GET"})))
                        self.assertFalse(predicate(SimpleNamespace(**{**request.__dict__, "frame":object()})))
                        self.assertFalse(predicate(SimpleNamespace(**{**request.__dict__, "url":"http://foreign.invalid/realms/armoney/login-actions/required-action"})))
                        return Finished()
                    def locator(inner, selector):
                        return SimpleNamespace(click=lambda **kw: submitted.set(), wait_for=lambda **kw: None)
                    def get_by_text(inner, text, exact):
                        self.assertEqual("Verification is unavailable. Please try again.", text)
                        self.assertIs(exact, True)
                        return SimpleNamespace(wait_for=lambda **kw: None)
                failures, output = [], io.StringIO()
                baseline = Mock()
                def client():
                    try:
                        HARNESS.finish_browser_submission(Page(), "http://sso-a:8080")
                        baseline()
                    except Exception as error:
                        failures.append(type(error))
                    finally:
                        done.set()
                with contextlib.redirect_stdout(output):
                    worker = threading.Thread(target=client)
                    worker.start()
                    try:
                        self.assertTrue(submitted.wait(1))
                        self.assertFalse(done.wait(0.03))
                        baseline.assert_not_called()
                    finally:
                        release.set()
                        worker.join(2)
                self.assertFalse(worker.is_alive())
                self.assertNotIn(PRIVATE, output.getvalue())
                valid = status == 200 and content_type.startswith("text/html")
                self.assertEqual([] if valid else [AssertionError], failures)
                self.assertEqual(1 if valid else 0, baseline.call_count)

    def test_committed_baseline_rebases_only_expiry_and_later_observer_mutation_still_fails(self):
        flow = {key: "synthetic" for key in HARNESS.FLOW}
        captured = dict(flow, username=PRIVATE, email=PRIVATE, phone_number=PRIVATE,
                        enabled=True, authentication_session_active=True,
                        authentication_session_expires_at="2026-10-16T00:00:30Z")
        committed = dict(captured, authentication_session_expires_at="2026-10-16T00:00:31Z")
        output = io.StringIO()
        with contextlib.redirect_stdout(output), patch.object(HARNESS,"observer",return_value=(200,committed,{})):
            self.assertEqual(committed,HARNESS.committed_observation("node-b",flow,captured))
        self.assertIn("FIELDS_SAME EXPIRY_LATER",output.getvalue())
        self.assertNotIn(PRIVATE,output.getvalue())
        mutated = dict(committed, authentication_session_expires_at="2026-10-16T00:00:32Z")
        with contextlib.redirect_stdout(io.StringIO()), patch.object(HARNESS,"observer",return_value=(200,mutated,{})):
            with self.assertRaises(AssertionError):
                HARNESS.read_only_checks("node-b",flow,committed,{"root_timestamp":42})
        with contextlib.redirect_stdout(io.StringIO()), patch.object(HARNESS,"observer",return_value=(200,dict(committed,email="changed"),{})):
            with self.assertRaises(AssertionError):
                HARNESS.committed_observation("node-b",flow,captured)

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
                    "USER_SETUP", "BROWSER_RENDER", "NEGATIVE_AUTH_PAGE", "NEGATIVE_FORM_SUBMIT", "NEGATIVE_DENIAL_RESULT",
                    "NEGATIVE_NO_SEND", "NEGATIVE_NO_RPC", "SEND_RPC", "CROSS_NODE", "READ_ONLY",
                    "READ_ONLY_RPC", "READ_ONLY_EXPIRY", "READ_ONLY_DTO", "READ_ONLY_INSPECT",
                    "READ_ONLY_TIMESTAMP", "READ_ONLY_HMAC_DENIAL", "READ_ONLY_SUBJECT_DENIAL", "READ_ONLY_KEY_DENIAL",
                    "EXPIRY", "TAB_REMOVAL", "ROOT_REMOVAL", "DISABLE_DELETE", "CLEANUP", "COMPLETE",
                    "TAB_DELETE_RPC", "TAB_INSPECT_RPC", "TAB_ABSENCE", "TAB_OBSERVER_DENIAL",
                    "ROOT_DELETE_RPC", "ROOT_INSPECT_RPC", "ROOT_ABSENCE", "ROOT_OBSERVER_DENIAL"}
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
        with patch.object(HARNESS, "request", side_effect=transport), \
                patch.object(HARNESS, "provision_registration_flow") as registration_flow, contextlib.redirect_stdout(io.StringIO()):
            HARNESS.provision_client_and_action("http://fixture.invalid", {"Authorization": "synthetic-private"})
        self.assertEqual(len(sequence), len(calls))
        registration_flow.assert_called_once_with("http://fixture.invalid/admin/realms/armoney", {"Authorization": "synthetic-private"})
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

    def test_removal_subphases_preserve_actual_delete_inspect_absence_and_denial_checks(self):
        for kind in ("tab","root"):
            prefix=kind.upper()
            for fault in (None,"DELETE_RPC","INSPECT_RPC","ABSENCE","OBSERVER_DENIAL"):
                with self.subTest(kind=kind,fault=fault):
                    calls=[]
                    def control(node,operation,flow):
                        calls.append((node,operation))
                        if operation.startswith("delete-") and fault=="DELETE_RPC":
                            raise RuntimeError(PRIVATE)
                        if operation=="inspect":
                            if fault=="INSPECT_RPC":
                                raise RuntimeError(PRIVATE)
                            return {"tab_present":fault=="ABSENCE","root_present":fault=="ABSENCE"}
                    def observe(node,flow,diagnostic=None):
                        calls.append((node,"observer"))
                        return (200 if fault=="OBSERVER_DENIAL" else 503),None,{}
                    with patch.object(HARNESS,"controls",side_effect=control), patch.object(HARNESS,"observer",side_effect=observe):
                        if fault is None:
                            with contextlib.redirect_stdout(io.StringIO()):
                                HARNESS.removal_checks("node-a","node-b",{},kind)
                            self.assertEqual([("node-a","delete-"+kind),("node-b","inspect"),("node-b","observer")],calls)
                        else:
                            message,_ = self.invoke(lambda:HARNESS.removal_checks("node-a","node-b",{},kind))
                            self.assertEqual("FAIL: OBSERVER69 "+prefix+"_"+fault,message)

    def test_disabled_observation_requires_same_exact_negative_evidence_and_future_unchanged_deadline(self):
        flow = {key: "synthetic-" + key for key in HARNESS.FLOW}
        first = dict(flow, username="+971501234567", email="synthetic@example.invalid",
                     phone_number="+971501234567", enabled=True, authentication_session_active=True,
                     authentication_session_expires_at="2026-10-16T00:00:30Z")
        now = HARNESS.datetime.datetime.fromisoformat("2026-10-16T00:00:00+00:00").timestamp()
        for fault in (None, "unavailable", "missing", "extra", "binding", "contacts", "enabled",
                      "flag_type", "inactive", "renewed", "expired"):
            with self.subTest(fault=fault):
                current = dict(first, enabled=False)
                status = 503 if fault == "unavailable" else 200
                if fault == "missing": current.pop("email")
                if fault == "extra": current["authority"] = "ACTIVE"
                if fault == "binding": current["subject"] = "other-subject"
                if fault == "contacts": current["email"] = "other@example.invalid"
                if fault == "enabled": current["enabled"] = True
                if fault == "flag_type": current["enabled"] = 0
                if fault == "inactive": current["authentication_session_active"] = False
                if fault == "renewed": current["authentication_session_expires_at"] = "2026-10-16T00:00:31Z"
                server_time = now + 30 if fault == "expired" else now
                with patch.object(HARNESS, "observer", return_value=(status, current, {})) as observed, \
                        patch.object(HARNESS, "controls", return_value={"server_time": server_time}) as inspected:
                    if fault is None:
                        HARNESS.disabled_observation_checks("node-b", flow, first)
                        self.assertEqual(("node-b", flow), observed.call_args.args)
                        inspected.assert_called_once_with("node-b", "inspect", flow)
                    else:
                        with self.assertRaises(AssertionError):
                            HARNESS.disabled_observation_checks("node-b", flow, first)

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
