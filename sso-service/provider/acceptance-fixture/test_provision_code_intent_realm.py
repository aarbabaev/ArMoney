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
        self.execution = {"providerId": "armoney-password-proof", "requirement": "REQUIRED", "authenticationFlow": False}
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
            original = target.read_bytes()
            with contextlib.redirect_stderr(stderr):
                self.assertEqual(1, p.main(["--input", str(source), "--result", str(target)]))
            self.assertEqual(original, target.read_bytes())
            target.unlink()
            with patch.object(p, "provision", side_effect=RuntimeError("sensitive-response-body")), contextlib.redirect_stdout(stdout), contextlib.redirect_stderr(stderr):
                self.assertEqual(1, p.main(["--input", str(source), "--result", str(target)]))
            self.assertNotIn("sensitive-response-body", stdout.getvalue() + stderr.getvalue() + target.read_text())
            self.assertEqual({"phase": "PHASE1", "status": "FAILED_NO_AUTOMATIC_RETRY"}, json.loads(target.read_text()))

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
