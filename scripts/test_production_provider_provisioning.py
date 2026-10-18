"""Pure synthetic validation/request-shape tests. No Keycloak/network execution."""
import copy
import importlib.util
import json
from pathlib import Path
import unittest

SPEC = importlib.util.spec_from_file_location("production_provisioning", Path(__file__).with_name("provision-production-provider-fixture.py"))
P = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(P)


def config():
    issuer = "https://fixture.example/sso/realms/armoney"
    entries = [("armoney-ios", "com.armoney.ios:/oauth/callback", "BANK_NATIVE"),
               ("armoney-android", "com.armoney.android:/oauth/callback", "BANK_NATIVE"),
               ("armoney-web", "https://fixture.example/oauth/callback", "BANK_NATIVE"),
               ("armoney-android", "https://fixture.example/migration/managed/callback", "MIGRATION_MANAGED"),
               (P.ACCOUNT, "https://fixture.example/migration/legacy/account-oidc/callback", "MIGRATION_ACCOUNT")]
    return {"CI": "true", "CODE_SESSION_ACCEPTANCE_ISOLATED": "true", "fresh_realm": True,
            "expected_issuer": issuer, "admin_username": "synthetic-admin", "admin_password": "a" * 32,
            "introspection_secret": "i" * 40, "acceptance_admin_secret": "s" * 40,
            "purpose_manifest": [dict(issuer=issuer, client_id=client, redirect_uri=redirect, purpose=purpose) for client, redirect, purpose in entries]}


def encoded(value):
    return json.dumps(value).encode()


class Validation(unittest.TestCase):
    def test_exact_four_clients_with_five_purposes_and_no_banking_mapper_on_account(self):
        parsed = P.configuration(encoded(config()))
        self.assertEqual(5, len(parsed["purpose_manifest"]))
        self.assertEqual(P.PUBLIC, {x["client_id"] for x in parsed["purpose_manifest"]})
        account = P.public_client(parsed, P.ACCOUNT)
        self.assertEqual(["https://fixture.example/migration/legacy/account-oidc/callback"], account["redirectUris"])
        self.assertNotIn("protocolMappers", account)
        self.assertEqual("true", account["attributes"][P.ATTRIBUTE])
        self.assertEqual(3, len(P.public_client(parsed, "armoney-android")["protocolMappers"]))
        self.assertEqual(["offline_access"], P.public_client(parsed, "armoney-android")["optionalClientScopes"])
        for client in ("armoney-ios", "armoney-web", P.ACCOUNT):
            self.assertEqual([], P.public_client(parsed, client)["optionalClientScopes"])

    def test_gates_schema_roles_and_secret_aliases_fail_closed(self):
        for field, value in [("CI", "false"), ("fresh_realm", 1), ("fresh_realm", "true"),
                             ("introspection_secret", "short"), ("acceptance_admin_secret", "i" * 40),
                             ("expected_issuer", "http://fixture.example/sso/realms/armoney")]:
            c = config()
            c[field] = value
            with self.subTest(field=field), self.assertRaises(P.Refused):
                P.configuration(encoded(c))
        c = config()
        c["destination"] = "http://untrusted/"
        with self.assertRaises(P.Refused):
            P.configuration(encoded(c))

    def test_redirect_and_purpose_matrix_rejects_expansion_and_missing_managed_proof(self):
        for field, value in [("purpose", "BANK_NATIVE"), ("purpose", "LEGACY_STOCK"),
                             ("redirect_uri", "https://fixture.example/migration/legacy/account-oidc/callback/"),
                             ("redirect_uri", "https://other.example/migration/legacy/account-oidc/callback"),
                             ("client_id", "untrusted"), ("client_id", [])]:
            c = config()
            c["purpose_manifest"][-1][field] = value
            with self.subTest(field=field), self.assertRaises(P.Refused):
                P.configuration(encoded(c))
        c = config()
        c["purpose_manifest"].pop(3)
        with self.assertRaises(P.Refused):
            P.configuration(encoded(c))
        c = config()
        c["purpose_manifest"].append(copy.deepcopy(c["purpose_manifest"][0]))
        with self.assertRaises(P.Refused):
            P.configuration(encoded(c))

    def test_strict_json_duplicate_utf8_trailing_and_diagnostics(self):
        for raw in (b'{"key":1,"key":2}', b'{} {}', b'\xef\xbb\xbf{}', b'"\xff"', b'{"x":"\\ud800"}', b'{"x":NaN}'):
            with self.assertRaises(P.Refused) as failure:
                P.strict_json(raw)
            self.assertIsNone(failure.exception.__cause__)
            self.assertEqual("", str(failure.exception))

    def test_existing_realm_refusal_does_not_send_mutations_or_return_response_content(self):
        calls = []
        class ExistingRealm:
            def request(self, method, path, body=None, token=None, form=False):
                calls.append((method, path))
                if path.endswith("/token"):
                    return 200, b'{"access_token":"synthetic-private-token"}'
                return 200, b'{"private":"never serialize this body"}'
        provisioner = P.Provisioner(ExistingRealm())
        with self.assertRaises(P.Refused) as failure:
            provisioner.run(config())
        self.assertEqual("absent", provisioner.stage)
        self.assertEqual(["POST", "GET"], [x[0] for x in calls])
        self.assertEqual("", str(failure.exception))

    def test_public_readback_rejects_grant_redirect_mapper_and_boolean_integer_drift(self):
        expected = P.public_client(config(), "armoney-android")
        P.check_public(copy.deepcopy(expected), expected)
        for field, value in [("publicClient", 1), ("implicitFlowEnabled", True), ("serviceAccountsEnabled", True),
                             ("redirectUris", ["https://untrusted/callback"]), ("protocolMappers", []), ("secret", "private-response")]:
            actual = copy.deepcopy(expected)
            actual[field] = value
            with self.subTest(field=field), self.assertRaises(P.Refused):
                P.check_public(actual, expected)

    def test_nested_profile_pattern_preserves_valid_uae_and_rejects_other_country(self):
        import re
        pattern = P.user_profile()["attributes"][0]["validations"]["pattern"]["pattern"]
        roundtrip = json.loads(json.loads(json.dumps({"config": json.dumps(P.user_profile())}))["config"])
        self.assertEqual(pattern, roundtrip["attributes"][0]["validations"]["pattern"]["pattern"])
        self.assertIsNotNone(re.fullmatch(pattern, "+971501234567"))
        for value in ("971501234567", "+972501234567", "+971511234567"):
            self.assertIsNone(re.fullmatch(pattern, value))

    def test_exact_leaf_cardinality_and_authoritative_models_reject_cookie_alternative_or_id_drift(self):
        identity = "00000000-0000-0000-0000-000000000001"
        listing = [{"id": identity, "providerId": "armoney-password-proof", "requirement": "REQUIRED"}]
        models = {identity: {"id": identity, "authenticator": "armoney-password-proof", "authenticatorFlow": False, "requirement": "REQUIRED", "priority": 10}}
        P.check_leaf_flow(listing, models, {"armoney-password-proof": 10})
        bad = copy.deepcopy(listing)
        bad.append({"id": "00000000-0000-0000-0000-000000000002", "providerId": "auth-cookie", "requirement": "ALTERNATIVE"})
        with self.assertRaises(P.Refused):
            P.check_leaf_flow(bad, models, {"armoney-password-proof": 10})
        for field, value in [("id", "other"), ("authenticator", "auth-cookie"), ("authenticatorFlow", True),
                             ("authenticatorFlow", 0), ("requirement", "ALTERNATIVE"), ("priority", True)]:
            altered = copy.deepcopy(models)
            altered[identity][field] = value
            with self.subTest(field=field), self.assertRaises(P.Refused):
                P.check_leaf_flow(listing, altered, {"armoney-password-proof": 10})

    def test_offline_access_uses_only_builtin_scope_and_default_composite_not_user_roles(self):
        default_id, offline_id, scope_id, android_id = ["00000000-0000-0000-0000-00000000000" + str(x) for x in range(1, 5)]
        offline = {"id": offline_id, "name": "offline_access", "clientRole": False, "containerId": "realm-id"}
        for initially_present in (True, False):
            calls, added = [], []
            def invoke(method, path, expected, body=None, token=None, form=False, document=True):
                calls.append((method, path))
                if path == P.REALM:
                    return {"id": "realm-id", "defaultRole": {"id": default_id, "name": "default-roles-armoney"}}
                if path.endswith("/roles-by-id/" + default_id):
                    return {"id": default_id, "name": "default-roles-armoney", "clientRole": False, "containerId": "realm-id"}
                if path.endswith("/roles/offline_access"):
                    return offline
                if path.endswith("/client-scopes") or path.endswith("/optional-client-scopes"):
                    return [{"id": scope_id, "name": "offline_access", "protocol": "openid-connect"}]
                if path.endswith("/scope-mappings/realm"):
                    return [offline]
                if path.endswith("/composites/realm"):
                    return [offline] if initially_present or added else []
                if path.endswith("/composites"):
                    self.assertEqual("POST", method)
                    self.assertEqual([offline], body)
                    added.append(True)
                    return None
                self.fail("Unexpected synthetic provisioning request")
            p = P.Provisioner(None)
            p.call = invoke
            p.offline_access({"id": android_id}, "synthetic-private-token")
            self.assertEqual(0 if initially_present else 1, len(added))
            self.assertFalse(any("/users/" in path for _, path in calls))


if __name__ == "__main__":
    unittest.main()
