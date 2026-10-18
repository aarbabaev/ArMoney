"""Pure synthetic stock setup contract tests; no actual HTTP or provider operations."""
import copy
import importlib.util
import json
from pathlib import Path
import unittest

SPEC = importlib.util.spec_from_file_location("stock_setup", Path(__file__).with_name("provision-production-provider-stock-fixture.py"))
M = importlib.util.module_from_spec(SPEC); SPEC.loader.exec_module(M)
P = M.P


def original():
    origin = "https://fixture.local:8443"; issuer = origin + "/sso/realms/armoney"
    entries = [("armoney-ios", "com.armoney.ios:/oauth/callback", "BANK_NATIVE"),
        ("armoney-android", "com.armoney.android:/oauth/callback", "BANK_NATIVE"),
        ("armoney-web", origin + "/oauth/callback", "BANK_NATIVE"),
        ("armoney-android", origin + "/migration/managed/callback", "MIGRATION_MANAGED"),
        (P.ACCOUNT, origin + "/migration/legacy/account-oidc/callback", "MIGRATION_ACCOUNT")]
    return {"CI": "true", "CODE_SESSION_ACCEPTANCE_ISOLATED": "true", "fresh_realm": True, "expected_issuer": issuer,
        "admin_username": "synthetic-admin", "admin_password": "p" * 32, "introspection_secret": "i" * 32,
        "acceptance_admin_secret": "a" * 32, "purpose_manifest": [dict(issuer=issuer, client_id=c, redirect_uri=r, purpose=p) for c, r, p in entries]}


def snapshot():
    return {"version": 1, "configuration_hash": M.digest(original()), "realm_id": "00000000-0000-0000-0000-000000000001",
        "realm": {"browserFlow": P.BROWSER, "registrationFlow": P.REGISTRATION},
        "actions": [{"alias": x, "providerId": x, "name": x, "enabled": True, "defaultAction": False, "priority": 51+i, "config": {}}
                    for i, x in enumerate((P.EMAIL, P.PHONE))], "policies": M.original_policy()}


class Transport:
    def __init__(self):
        self.s = snapshot(); self.calls = []; self.client = None; self.secret = "s" * 32
    def request(self, method, path, body=None, token=None, form=False):
        self.calls.append((method, path, copy.deepcopy(body), token, form))
        if path.endswith("/protocol/openid-connect/token"):
            assert method == "POST" and form and body["client_id"] == "admin-cli"
            value = {"access_token": "synthetic-private-token"}
        elif path == P.REALM:
            if method == "PUT": self.s["realm"] = copy.deepcopy(body); return 204, b""
            value = {"id": self.s["realm_id"], "realm": "armoney", **self.s["realm"]}
        elif "/authentication/required-actions/" in path:
            alias = path.rsplit("/", 1)[1]; index = (P.EMAIL, P.PHONE).index(alias)
            if method == "PUT": self.s["actions"][index] = copy.deepcopy(body); return 204, b""
            value = self.s["actions"][index]
        elif path.endswith("/client-policies/profiles"):
            value = {"profiles": [{"name": P.PROFILE, "executors": [{"executor": P.EXECUTOR, "configuration": {}}]}]}
        elif path.endswith("/client-policies/policies"):
            if method == "PUT": self.s["policies"] = copy.deepcopy(body); return 204, b""
            value = self.s["policies"]
        elif path == M.MASTER + "/clients":
            assert method == "POST"; self.client = {**copy.deepcopy(body), "id": "00000000-0000-0000-0000-000000000002"}
            return 201, b""
        elif path == M.MASTER + "/clients?clientId=" + M.CLIENT:
            value = [] if self.client is None else [self.client]
        elif path.endswith("/client-secret"): value = {"value": self.secret}
        elif path.endswith("/scope-mappings"): value = {}
        elif path.endswith("/roles"): value = []
        elif path.startswith(M.MASTER + "/clients/"): value = self.client
        elif path.startswith(M.MASTER + "/users?"):
            value = [{"id": "00000000-0000-0000-0000-000000000003", "username": "synthetic-admin"}]
        elif path.startswith(M.MASTER + "/users/"):
            value = {"id": "00000000-0000-0000-0000-000000000003", "username": "synthetic-admin", "enabled": True}
        else: raise AssertionError("unexpected fixed path")
        return 200, json.dumps(value).encode()


class StockProvisioning(unittest.TestCase):
    def test_master_exact_one_confidential_client_and_existing_user_only(self):
        transport = Transport(); setup = M.Setup(transport)
        v = {"mode": "master", "provisioning": original(), "client_secret": transport.secret}
        v, c = M.configuration(json.dumps(v).encode())
        result = setup.execute(v, c, lambda _: self.fail("no stock snapshot for master"))
        self.assertEqual({"username", "password", "subject", "client_secret"}, set(result["master"]))
        posts = [(path, body) for method, path, body, _, _ in transport.calls if method == "POST"]
        self.assertEqual(2, len(posts))
        self.assertEqual(M.MASTER + "/clients", posts[1][0])
        self.assertEqual(M.master_client(transport.secret), posts[1][1])
        self.assertFalse(any(method == "PUT" or "/users" in path and method != "GET" for method, path, *_ in transport.calls))
        self.assertFalse(result["runtime_acceptance"])

    def test_master_existing_client_is_refused_before_any_write(self):
        transport = Transport(); transport.client = {"id": "existing", "clientId": M.CLIENT}
        with self.assertRaises(P.Refused): M.Setup(transport).execute({"mode": "master", "client_secret": transport.secret}, original(), lambda _: None)
        self.assertEqual(1, sum(method == "POST" for method, *_ in transport.calls))

    def test_master_readback_rejects_callback_scope_grants_mapper_and_boolean_drift(self):
        wanted = M.master_client("s" * 32); valid = {**wanted, "id": "00000000-0000-0000-0000-000000000002"}
        M.check_master(valid, wanted)
        for key, value in (("redirectUris", ["https://other.invalid/"]), ("optionalClientScopes", ["offline_access"]),
                           ("publicClient", True), ("standardFlowEnabled", 1), ("fullScopeAllowed", True), ("protocolMappers", [])):
            with self.subTest(key=key), self.assertRaises(P.Refused): M.check_master({**valid, key: value}, wanted)
        altered = copy.deepcopy(valid); altered["protocolMappers"][0]["config"]["included.custom.audience"] = "armoney-api"
        with self.assertRaises(P.Refused): M.check_master(altered, wanted)

    def test_off_snapshot_is_persisted_before_writes_and_exact_restore(self):
        transport = Transport(); saved = []
        def persist(value):
            self.assertFalse(any(method == "PUT" for method, *_ in transport.calls))
            saved.append(copy.deepcopy(value))
        off = M.Setup(transport).execute({"mode": "off_prepare"}, original(), persist)
        self.assertEqual(1, len(saved)); self.assertEqual("snapshot_saved", saved[0]["status"])
        self.assertEqual(M.Setup.off(off["snapshot"]), (transport.s["realm_id"], transport.s["realm"], transport.s["actions"], transport.s["policies"]))
        restored = M.Setup(transport).execute({"mode": "restore", "snapshot": off["snapshot"]}, original(), lambda _: self.fail("restore cannot replace original snapshot"))
        self.assertEqual("configured", restored["status"])
        self.assertEqual(snapshot(), transport.s)
        puts = [body for method, _, body, *_ in transport.calls if method == "PUT"]
        self.assertEqual(8, len(puts))
        self.assertFalse(any("users" in path or "client-policies/profiles" in path and method != "GET" for method, path, *_ in transport.calls))

    def test_restore_drift_or_wrong_fixture_denies_before_write(self):
        for field in ("realm", "actions", "policies", "realm_id"):
            transport = Transport(); s = snapshot()
            _, transport.s["realm"], transport.s["actions"], transport.s["policies"] = M.Setup.off(s)
            if field == "realm": transport.s["realm"]["browserFlow"] = "drift"
            elif field == "actions": transport.s["actions"][0]["priority"] += 1
            elif field == "policies": transport.s["policies"] = M.original_policy()
            else: transport.s["realm_id"] = "00000000-0000-0000-0000-000000000009"
            with self.subTest(field=field), self.assertRaises(P.Refused):
                M.Setup(transport).execute({"mode": "restore", "snapshot": s}, original(), lambda _: None)
            self.assertFalse(any(method == "PUT" for method, *_ in transport.calls))

    def test_private_config_roles_aliases_extra_fields_and_snapshot_tampering_refuse(self):
        base = {"mode": "master", "provisioning": original(), "client_secret": "s" * 32}
        for value in ({**base, "client_secret": "p" * 32}, {**base, "redirect": "https://other.invalid"}, {**base, "mode": "arbitrary"}):
            with self.assertRaises(P.Refused): M.configuration(json.dumps(value).encode())
        for field, value in (("configuration_hash", "x" * 64), ("realm", {"browserFlow": "browser", "registrationFlow": "registration"}), ("version", True)):
            s = snapshot(); s[field] = value
            with self.assertRaises(P.Refused): M.validate_snapshot(s, original())


if __name__ == "__main__": unittest.main()
