"""Synthetic transport tests only; no Keycloak/runtime acceptance is implied."""
import contextlib
import copy
import importlib.util
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

SPEC = importlib.util.spec_from_file_location("provision_fixture", Path(__file__).with_name("provision-code-intent-realm.py"))
p = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(p)
EXECUTION = "727d4f44-234b-4e2e-89a4-247db759f9cb"
SUBJECT = "d14d7e29-80f7-41ed-a5b5-99d82bbba4df"


def config():
    return {"CI": "true", "CODE_SESSION_ACCEPTANCE_ISOLATED": "true",
            "ARMONEY_CODE_INTENT_FIXTURE_ENABLED": "false", "expected_issuer": "https://fixture-edge/sso/realms/armoney",
            "admin_username": "disposable-admin", "admin_password": "synthetic-admin-password",
            "synthetic_user": {"username": "+971501234567", "email": "synthetic@example.test",
                               "firstName": "Synthetic", "lastName": "Fixture", "password": "synthetic-permanent-password"}}


class FakeTransport:
    def __init__(self):
        self.calls = []
        self.realm = p.realm_source()
        self.existing = set()
        self.imported = False
        self.created = None
        self.client = copy.deepcopy(self.realm["clients"][0])
        self.subject = SUBJECT
        self.off_status = 503
        self.issuer = config()["expected_issuer"]
        self.execution = {"id": EXECUTION, "providerId": "armoney-password-proof", "requirement": "REQUIRED", "authenticationFlow": False}
        self.execution_model = {"id": EXECUTION, "authenticator": "armoney-password-proof",
                                "requirement": "REQUIRED", "authenticatorFlow": False}
        self.saved_override = {}

    def __call__(self, node, method, path, token=None, payload=None, form=None):
        self.calls.append((node, method, path, token, copy.deepcopy(payload), copy.deepcopy(form)))
        if path.endswith("/realms/master/protocol/openid-connect/token"):
            return 200, b'{"access_token":"synthetic-admin-token","token_type":"Bearer"}'
        if path == p.REALM:
            return (200, json.dumps(self.realm).encode()) if self.imported or node in self.existing else (404, b"")
        if path == p.BASE + "/admin/realms" and method == "POST":
            self.imported = True
            return 201, b""
        if path.endswith("/.well-known/openid-configuration"):
            return 200, json.dumps({"issuer": self.issuer}).encode()
        if path == p.REALM + "/clients?clientId=armoney-android":
            return 200, json.dumps([self.client]).encode()
        if path == p.REALM + "/authentication/executions/" + EXECUTION:
            return 200, json.dumps(self.execution_model).encode()
        if path.endswith("/executions"):
            return 200, json.dumps([self.execution]).encode()
        if path.endswith("/authentication/required-actions"):
            return 200, json.dumps(self.realm["requiredActions"]).encode()
        if path.endswith("/armoney-code-intent-fixture/state"):
            return self.off_status, b""
        if path == p.REALM + "/users" and method == "POST":
            self.created = payload
            return 201, b""
        if path.startswith(p.REALM + "/users?"):
            return 200, json.dumps([{"id": self.subject}]).encode()
        if path == p.REALM + "/users/" + self.subject:
            saved = {k: v for k, v in self.created.items() if k != "credentials"}
            saved.update({"id": self.subject, **self.saved_override})
            return 200, json.dumps(saved).encode()
        raise AssertionError("Unexpected synthetic request")


class ProvisionTests(unittest.TestCase):
    def test_success_imports_exact_source_and_creates_one_permanent_password_user(self):
        transport = FakeTransport()
        result = p.provision(config(), transport)
        self.assertEqual({"phase": "PHASE1", "status": "BOTH_NODES_STOP_AND_ENABLED_RESTART_REQUIRED", "subject": SUBJECT}, result)
        imports = [c for c in transport.calls if c[1:3] == ("POST", p.BASE + "/admin/realms")]
        self.assertEqual(1, len(imports))
        self.assertEqual(p.realm_source(), imports[0][4])
        creates = [c for c in transport.calls if c[1:3] == ("POST", p.REALM + "/users")]
        self.assertEqual(1, len(creates))
        self.assertEqual([{"type": "password", "value": config()["synthetic_user"]["password"], "temporary": False}], creates[0][4]["credentials"])
        self.assertEqual([p.ACTION], creates[0][4]["requiredActions"])
        token_calls = [c for c in transport.calls if c[2].endswith("/token")]
        self.assertEqual(1, len(token_calls))
        self.assertEqual("admin-cli", token_calls[0][5]["client_id"])
        self.assertEqual("disposable-admin", token_calls[0][5]["username"])
        self.assertTrue(all(c[0] in p.NODES for c in transport.calls))
        self.assertEqual(4, sum(c[2].endswith("/armoney-code-intent-fixture/state") for c in transport.calls))

    def test_existing_realm_on_either_node_refuses_before_mutation(self):
        for node in p.NODES:
            with self.subTest(node=node):
                transport = FakeTransport()
                transport.existing.add(node)
                with self.assertRaises(p.Refused):
                    p.provision(config(), transport)
                self.assertFalse(transport.imported)
                self.assertFalse(any(c[1] == "POST" and c[2].startswith(p.BASE + "/admin/") for c in transport.calls))

    def test_callback_grants_pkce_and_flow_mismatches_refuse_before_user_creation(self):
        changes = [{"redirectUris": ["https://attacker.invalid/callback"]}, {"implicitFlowEnabled": True},
                   {"directAccessGrantsEnabled": True}, {"serviceAccountsEnabled": True}, {"publicClient": False},
                   {"attributes": {"pkce.code.challenge.method": "plain"}}]
        for change in changes:
            with self.subTest(change=change):
                transport = FakeTransport()
                transport.client.update(change)
                with self.assertRaises(p.Refused):
                    p.provision(config(), transport)
                self.assertIsNone(transport.created)
        transport = FakeTransport()
        transport.execution["providerId"] = "auth-cookie"
        with self.assertRaises(p.Refused):
            p.provision(config(), transport)
        self.assertIsNone(transport.created)

    def test_noncanonical_or_missing_persisted_subject_is_rejected(self):
        for subject in (SUBJECT.upper(), SUBJECT.replace("-", ""), "not-a-uuid", "00000000-0000-0000-0000-000000000000", None):
            with self.subTest(subject=subject):
                transport = FakeTransport()
                transport.subject = subject
                with self.assertRaises(p.Refused):
                    p.provision(config(), transport)

    def test_profile_or_action_readback_mismatch_refuses(self):
        for change in ({"username": "+971521234567"}, {"email": "different@example.test"},
                       {"firstName": "Other"}, {"lastName": "Other"}, {"requiredActions": []},
                       {"emailVerified": True}, {"enabled": False}):
            transport = FakeTransport()
            transport.saved_override = change
            with self.subTest(field=next(iter(change))), self.assertRaises(p.Refused):
                p.provision(config(), transport)

    def test_phase_off_or_external_issuer_mismatch_refuses(self):
        for status in (200, 401, 404):
            transport = FakeTransport()
            transport.off_status = status
            with self.subTest(status=status), self.assertRaises(p.Refused):
                p.provision(config(), transport)
            self.assertIsNone(transport.created)
        transport = FakeTransport()
        transport.issuer = "http://sso-a:8080/sso/realms/armoney"
        with self.assertRaises(p.Refused):
            p.provision(config(), transport)

    def test_gates_strict_json_and_input_validation_happen_before_network(self):
        for key, value in (("CI", "false"), ("CODE_SESSION_ACCEPTANCE_ISOLATED", "false"),
                           ("ARMONEY_CODE_INTENT_FIXTURE_ENABLED", "true"), ("CI", True),
                           ("expected_issuer", "https://fixture-edge/wrong"),
                           ("expected_issuer", "https://fixture-edge/sso/realms/armoney?x=1")):
            candidate = config()
            candidate[key] = value
            transport = FakeTransport()
            with self.subTest(key=key, value=value), self.assertRaises(p.Refused):
                p.provision(candidate, transport)
            self.assertEqual([], transport.calls)
        for raw in (b'{"x":1,"x":2}', b'{"x":NaN}', b'\xef\xbb\xbf{}', b'{"x":"\xff"}',
                    b'{"x":"\\ud800"}', b'{} trailing', b' ' * (p.LIMIT + 1)):
            with self.subTest(raw_kind=len(raw)), self.assertRaises(p.Refused):
                p.strict_json(raw)
        for field, value in (("username", "+972501234567"), ("email", "real@example.com"), ("password", "short")):
            candidate = config()
            candidate["synthetic_user"][field] = value
            with self.subTest(field=field), self.assertRaises(p.Refused):
                p.configuration(json.dumps(candidate).encode())

    def test_cli_writes_only_private_result_and_sanitizes_failures(self):
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / "input.json"
            source.write_text(json.dumps(config()), encoding="utf-8")
            target = Path(directory) / "result.json"
            stdout, stderr = io.StringIO(), io.StringIO()
            with patch.object(p, "provision", return_value={"phase": "PHASE1", "status": "READY", "subject": SUBJECT}), contextlib.redirect_stdout(stdout), contextlib.redirect_stderr(stderr):
                self.assertEqual(0, p.main(["--input", str(source), "--result", str(target)]))
            self.assertEqual("", stdout.getvalue() + stderr.getvalue())
            self.assertNotIn("password", target.read_text())
            self.assertEqual({"phase", "status", "subject"}, set(json.loads(target.read_text())))
            original = target.read_bytes()
            with contextlib.redirect_stderr(stderr):
                self.assertEqual(1, p.main(["--input", str(source), "--result", str(target)]))
            self.assertEqual(original, target.read_bytes())
            target.unlink()
            with patch.object(p, "provision", side_effect=RuntimeError("sensitive-response-body")), contextlib.redirect_stdout(stdout), contextlib.redirect_stderr(stderr):
                self.assertEqual(1, p.main(["--input", str(source), "--result", str(target)]))
            self.assertNotIn("sensitive-response-body", stdout.getvalue() + stderr.getvalue() + target.read_text())
            self.assertEqual({"phase": "PHASE1", "status": "FAILED_NO_AUTOMATIC_RETRY",
                              "diagnostic": {"checkpoint": "config"}}, json.loads(target.read_text()))

    def test_progress_has_exact_enums_and_never_retains_arbitrary_values(self):
        progress = p.Progress()
        for args in (("secret-response-body",), ("token", "secret-identity"),
                     ("token", "A", "secret-token"), ("token", "A", 99),
                     ("token", "A", 600), ("token", "A", True), ("token", "A", 200.0)):
            with self.subTest(argument_types=[type(a).__name__ for a in args]), self.assertRaises(p.Refused):
                progress.mark(*args)
            self.assertEqual({"checkpoint": "config"}, progress.snapshot())
        progress.mark("token", "A", 200)
        progress.mark("discovery", "B")
        self.assertEqual({"checkpoint": "discovery", "node": "B"}, progress.snapshot())
        self.assertEqual({"checkpoint": "config"}, p.Progress().snapshot())

    def test_success_callback_covers_every_stage_but_does_not_change_success_schema(self):
        observed = []
        progress = p.Progress()
        def callback(*args):
            progress.mark(*args)
            observed.append(progress.snapshot())
        result = p.provision(config(), FakeTransport(), progress=callback)
        self.assertEqual({"phase", "status", "subject"}, set(result))
        self.assertEqual(p.CHECKPOINTS, {event["checkpoint"] for event in observed})
        self.assertEqual({"checkpoint": "off-control-after", "node": "B", "http_status": 503}, observed[-1])

    def test_cli_http_mismatch_keeps_only_fixed_checkpoint_node_and_status(self):
        transport = FakeTransport()
        def failure(node, method, path, **kwargs):
            if method == "POST" and path == p.BASE + "/admin/realms":
                transport.calls.append((node, method, path))
                return 400, b'{"secret":"secret-response-body-and-token"}'
            return transport(node, method, path, **kwargs)
        result = self.cli_failure(failure)
        self.assertEqual({"checkpoint": "import", "node": "A", "http_status": 400}, result["diagnostic"])
        self.assertFalse(transport.imported)
        self.assertIsNone(transport.created)
        self.assertEqual(4, len(transport.calls))  # No automatic repeat of the refused import.

    def test_cli_schema_failure_and_transport_exception_never_serialize_private_data(self):
        transport = FakeTransport()
        transport.execution["providerId"] = "secret-response-body-and-token"
        result = self.cli_failure(transport)
        self.assertEqual({"checkpoint": "flow", "node": "A", "http_status": 200, "predicate": "flow-provider"}, result["diagnostic"])
        transport = FakeTransport()
        def failure(node, method, path, **kwargs):
            if node == "sso-b" and path.endswith("/.well-known/openid-configuration"):
                raise RuntimeError("secret-response-body-and-token " + SUBJECT)
            return transport(node, method, path, **kwargs)
        result = self.cli_failure(failure)
        self.assertEqual({"checkpoint": "discovery", "node": "B"}, result["diagnostic"])

    def test_cli_invalid_input_stays_at_config_without_network(self):
        transport = FakeTransport()
        candidate = config()
        candidate["CI"] = "false"
        result = self.cli_failure(transport, candidate)
        self.assertEqual({"checkpoint": "config"}, result["diagnostic"])
        self.assertEqual([], transport.calls)

    def test_client_predicates_cover_each_rejection_without_observed_values(self):
        secret = "secret-response-body-and-token"
        fields = {"clientId": "client-id", "enabled": "enabled", "protocol": "protocol",
                  "publicClient": "public-client", "standardFlowEnabled": "standard-flow",
                  "implicitFlowEnabled": "implicit-flow", "directAccessGrantsEnabled": "direct-access-grants",
                  "serviceAccountsEnabled": "service-accounts", "fullScopeAllowed": "full-scope",
                  "redirectUris": "redirect-uris", "webOrigins": "web-origins", "secret": "secret-absent",
                  "authenticationFlowBindingOverrides": "flow-overrides"}
        attrs = {"pkce.code.challenge.method": "pkce-s256",
                 "oauth2.device.authorization.grant.enabled": "device-grant",
                 "oidc.ciba.grant.enabled": "ciba-grant", "post.logout.redirect.uris": "logout-redirects"}
        for field, predicate in fields.items():
            transport = FakeTransport()
            transport.client[field] = secret
            with self.subTest(field=field):
                self.assert_client_diagnostic(transport, predicate)
        for field, predicate in attrs.items():
            transport = FakeTransport()
            transport.client["attributes"][field] = secret
            with self.subTest(attribute=field):
                self.assert_client_diagnostic(transport, predicate)
        for value in (None, [], secret):
            transport = FakeTransport()
            transport.client = value
            with self.subTest(client_type=type(value).__name__):
                self.assert_client_diagnostic(transport, "client-object")
            transport = FakeTransport()
            transport.client["attributes"] = value
            with self.subTest(attributes_type=type(value).__name__):
                self.assert_client_diagnostic(transport, "attributes-object")
        transport = FakeTransport()
        def wrong_list(node, method, path, **kwargs):
            if path.endswith("/clients?clientId=armoney-android"):
                return 200, json.dumps({"untrusted": secret}).encode()
            return transport(node, method, path, **kwargs)
        self.assert_client_diagnostic(wrong_list, "client-list")

    def test_logout_redirects_require_exact_explicit_deny_sentinel(self):
        transport = FakeTransport()
        self.assertEqual("-", transport.client["attributes"]["post.logout.redirect.uris"])
        self.assertEqual(SUBJECT, p.provision(config(), transport)["subject"])
        for value in (None, "", "+", "*", "https://fixture-client/callback", " -", "- ",
                      "\t-", "-\n", [], ["-"], True, False, 0, 1):
            transport = FakeTransport()
            transport.client["attributes"]["post.logout.redirect.uris"] = value
            with self.subTest(value=value):
                self.assert_client_diagnostic(transport, "logout-redirects")
        transport = FakeTransport()
        del transport.client["attributes"]["post.logout.redirect.uris"]
        self.assert_client_diagnostic(transport, "logout-redirects")

    def test_leaf_listing_null_or_omitted_requires_authoritative_model_proof(self):
        for omitted in (True, False):
            transport = FakeTransport()
            if omitted:
                del transport.execution["authenticationFlow"]
            else:
                transport.execution["authenticationFlow"] = None
            self.assertEqual(SUBJECT, p.provision(config(), transport)["subject"])
            reads = [call for call in transport.calls if call[2].endswith("/authentication/executions/" + EXECUTION)]
            self.assertEqual(2, len(reads))
            self.assertTrue(all(call[1] == "GET" and call[3] == "synthetic-admin-token" for call in reads))

    def test_malicious_execution_ids_refuse_before_authoritative_request(self):
        for value in (None, "../users", "https://attacker.invalid/", EXECUTION.upper(), 1):
            transport = FakeTransport()
            transport.execution["id"] = value
            result = self.cli_failure(transport)
            self.assertEqual("flow-id", result["diagnostic"]["predicate"])
            self.assertFalse(any("/authentication/executions/" in call[2] for call in transport.calls))

    def test_remaining_readback_predicates_are_fixed_and_redacted(self):
        secret = "secret-response-body-and-token"
        cases = []
        for value, predicate in (([], "flow-list"), ([secret], "flow-object"),
                                 ([{"id": EXECUTION, "providerId": secret}], "flow-provider"),
                                 ([{"id": EXECUTION, "providerId": "armoney-password-proof", "requirement": secret}], "flow-requirement")):
            cases.append(("flow", "/executions", value, predicate))
        cases.append(("flow", "/authentication/executions/" + EXECUTION, secret, "execution-object"))
        for field, predicate in (("id", "execution-id"), ("authenticator", "execution-authenticator"),
                                 ("requirement", "execution-requirement"), ("authenticatorFlow", "execution-not-flow")):
            model = FakeTransport().execution_model
            model[field] = secret
            cases.append(("flow", "/authentication/executions/" + EXECUTION, model, predicate))
        for value, predicate in (({}, "action-list"), ([secret], "action-object"), ([], "action-selection")):
            cases.append(("action", "/authentication/required-actions", value, predicate))
        for field, predicate in (("alias", "action-alias"), ("providerId", "action-provider")):
            action = copy.deepcopy(p.realm_source()["requiredActions"][0])
            action[field] = secret
            cases.append(("action", "/authentication/required-actions", [action], predicate))
        for value, predicate in (({}, "user-list"), ([secret], "user-object"), ([{"id": secret}], "user-id")):
            cases.append(("query-user", "/users?", value, predicate))
        cases.append(("user-readback", "/users/" + SUBJECT, secret, "saved-object"))
        for field, predicate in (("id", "saved-id"), ("enabled", "saved-enabled"),
                                 ("emailVerified", "saved-email-verified"), ("requiredActions", "saved-actions"),
                                 ("username", "saved-username"), ("email", "saved-email"),
                                 ("firstName", "saved-first-name"), ("lastName", "saved-last-name")):
            transport = FakeTransport()
            transport.saved_override[field] = secret
            result = self.cli_failure(transport)
            self.assertEqual(predicate, result["diagnostic"]["predicate"])
        for checkpoint, suffix, value, predicate in cases:
            transport = FakeTransport()
            def altered(node, method, path, **kwargs):
                if path.endswith(suffix) or suffix == "/users?" and "/users?" in path:
                    return 200, json.dumps(value).encode()
                return transport(node, method, path, **kwargs)
            with self.subTest(predicate=predicate):
                result = self.cli_failure(altered)
                self.assertEqual({"checkpoint": checkpoint, "node": "A", "http_status": 200,
                                  "predicate": predicate}, result["diagnostic"])

    def test_listing_rejects_contradictory_or_malformed_flow_kind(self):
        for value in (True, 0, 1, "false", [], {}):
            transport = FakeTransport()
            transport.execution["authenticationFlow"] = value
            with self.subTest(value=value):
                self.assertEqual("flow-kind", self.cli_failure(transport)["diagnostic"]["predicate"])
                self.assertFalse(any("/authentication/executions/" in call[2] for call in transport.calls))

    def test_authoritative_flow_boolean_must_be_explicit_false(self):
        for value in (None, True, 0, 1, "false", [], {}):
            transport = FakeTransport()
            transport.execution_model["authenticatorFlow"] = value
            with self.subTest(value=value):
                self.assertEqual("execution-not-flow", self.cli_failure(transport)["diagnostic"]["predicate"])
        transport = FakeTransport()
        del transport.execution_model["authenticatorFlow"]
        self.assertEqual("execution-not-flow", self.cli_failure(transport)["diagnostic"]["predicate"])

    def test_predicates_cannot_cross_checkpoint_boundaries(self):
        progress = p.Progress()
        for checkpoint, predicates in p.PREDICATES.items():
            for predicate in predicates:
                progress.mark(checkpoint, "A", 200, predicate)
                self.assertEqual(predicate, progress.snapshot()["predicate"])
                wrong = "action" if checkpoint != "action" else "flow"
                with self.assertRaises(p.Refused):
                    progress.mark(wrong, "A", 200, predicate)

    def assert_client_diagnostic(self, transport, predicate):
        result = self.cli_failure(transport)
        self.assertEqual({"checkpoint": "client", "node": "A", "http_status": 200,
                          "predicate": predicate}, result["diagnostic"])
        if isinstance(transport, FakeTransport):
            self.assertIsNone(transport.created)

    def test_client_security_booleans_reject_integer_and_other_nonboolean_values(self):
        fields = {"enabled": "enabled", "publicClient": "public-client",
                  "standardFlowEnabled": "standard-flow", "implicitFlowEnabled": "implicit-flow",
                  "directAccessGrantsEnabled": "direct-access-grants",
                  "serviceAccountsEnabled": "service-accounts", "fullScopeAllowed": "full-scope"}
        for field, predicate in fields.items():
            for value in (0, 1, None, "true", "false"):
                transport = FakeTransport()
                transport.client[field] = value
                with self.subTest(field=field, value=value):
                    self.assert_client_diagnostic(transport, predicate)

    def test_predicate_allowlist_is_client_only_and_invalid_marks_do_not_replace_progress(self):
        progress = p.Progress()
        progress.mark("client", "B", 200, "pkce-s256")
        expected = progress.snapshot()
        for checkpoint, predicate in (("client", "secret-response-body-and-token"),
                                      ("client", True), ("client", 1),
                                      ("flow", "pkce-s256"), ("config", "client-id")):
            with self.subTest(checkpoint=checkpoint, predicate=predicate), self.assertRaises(p.Refused):
                progress.mark(checkpoint, "A", 200, predicate)
            self.assertEqual(expected, progress.snapshot())
        progress.mark("flow", "A", 200)
        self.assertNotIn("predicate", progress.snapshot())

    def cli_failure(self, transport, candidate=None):
        actual_provision = p.provision
        def selected(configured, progress=None):
            return actual_provision(configured, transport, progress=progress)
        with tempfile.TemporaryDirectory() as directory:
            source, target = Path(directory) / "input.json", Path(directory) / "result.json"
            source.write_text(json.dumps(config() if candidate is None else candidate), encoding="utf-8")
            stdout, stderr = io.StringIO(), io.StringIO()
            with patch.object(p, "provision", side_effect=selected), contextlib.redirect_stdout(stdout), contextlib.redirect_stderr(stderr):
                self.assertEqual(1, p.main(["--input", str(source), "--result", str(target)]))
            raw = target.read_text()
            self.assertEqual("", stdout.getvalue())
            self.assertEqual("Provisioning unavailable.\n", stderr.getvalue())
            for forbidden in ("secret-response-body-and-token", SUBJECT, config()["admin_password"],
                              config()["synthetic_user"]["password"], config()["synthetic_user"]["username"],
                              config()["synthetic_user"]["email"], config()["synthetic_user"]["firstName"],
                              config()["synthetic_user"]["lastName"], config()["expected_issuer"]):
                self.assertNotIn(forbidden, raw)
            result = json.loads(raw)
            self.assertEqual({"phase", "status", "diagnostic"}, set(result))
            self.assertEqual("FAILED_NO_AUTOMATIC_RETRY", result["status"])
            self.assertTrue(set(result["diagnostic"]) <= {"checkpoint", "node", "http_status", "predicate"})
            return result

    def test_transport_rejects_arbitrary_nodes_redirects_and_oversized_responses(self):
        with self.assertRaises(p.Refused):
            p.request("attacker.invalid", "GET", "/sso/path")
        class Response:
            status = 302
            def read(self, limit):
                return b"x" * limit
        class Connection:
            sock = None
            def __init__(self, *args, **kwargs): pass
            def request(self, *args, **kwargs): pass
            def getresponse(self): return Response()
            def close(self): pass
        with patch.object(p.http.client, "HTTPConnection", Connection):
            with self.assertRaises(p.Refused):
                p.request("sso-a", "GET", "/sso/path")
            Response.status = 200
            with self.assertRaises(p.Refused):
                p.request("sso-a", "GET", "/sso/path")


if __name__ == "__main__":
    unittest.main()
