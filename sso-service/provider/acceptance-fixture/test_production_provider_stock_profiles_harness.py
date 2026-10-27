"""Synthetic stock-phase contract tests; no network or provider authority."""
import base64
import importlib.util
import json
from pathlib import Path
import time
import unittest
from unittest.mock import patch
import urllib.parse

SPEC = importlib.util.spec_from_file_location("stock_case", Path(__file__).with_name("test_production_provider_stock_profiles.py"))
M = importlib.util.module_from_spec(SPEC); SPEC.loader.exec_module(M)


class StockHarness(unittest.TestCase):
    def master(self, *, active=True, wrong_subject=False, injected_claim=False):
        master = {"username": "synthetic-admin", "password": "p" * 32, "subject": "00000000-0000-0000-0000-000000000001", "client_secret": "s" * 32}
        issuer = M.ORIGIN + "/sso/realms/master"
        calls = []
        state = {}
        class Wire:
            data = {"origin": M.ORIGIN}
            def request(self, url, **kwargs):
                calls.append((url, kwargs))
                if "/auth?" in url:
                    state.update({k: v[0] for k, v in urllib.parse.parse_qs(urllib.parse.urlsplit(url).query).items()})
                    return 200, {}, b"synthetic-form"
                if url.endswith("/token/introspect"):
                    facts = {"active": active, "iss": issuer, "client_id": M.MASTER_CLIENT,
                             "sub": "wrong" if wrong_subject else master["subject"]}
                    if injected_claim: facts[next(iter(M.C.CLAIMS))] = "not-authority"
                    return 200, {}, json.dumps(facts).encode()
                return 200, {}, b'{"access_token":"access","id_token":"identity","refresh_token":"refresh","token_type":"Bearer"}'
        def submit(*args):
            return 302, {"Location": M.MASTER_CALLBACK + "?code=synthetic-code&state=" + state["state"]}, b"", M.ORIGIN + "/sso/login"
        def inspect(token):
            if token == "identity": return {"nonce": state["nonce"], "iss": issuer, "aud": M.MASTER_CLIENT}
            return {}
        with patch.object(M.C, "submit", side_effect=submit), patch.object(M.C, "inspect_jwt", side_effect=inspect):
            M.master_browser(Wire(), master)
        self.assertEqual(3, len(calls))
        self.assertEqual("S256", state["code_challenge_method"])
        self.assertEqual("openid", state["scope"])
        self.assertEqual(M.MASTER_CALLBACK, state["redirect_uri"])
        for url, kwargs in calls[1:]:
            self.assertTrue(url.startswith(issuer + "/protocol/openid-connect/token"))
            expected = "Basic " + base64.b64encode((M.MASTER_CLIENT + ":" + master["client_secret"]).encode()).decode()
            self.assertEqual(expected, kwargs["headers"]["Authorization"])
        self.assertNotIn("client_secret", calls[1][1]["form"])
        self.assertIn("code_verifier", calls[1][1]["form"])

    def test_master_same_issuer_client_basic_pkce_and_introspection(self): self.master()

    def test_master_inactive_wrong_subject_or_any_banking_claim_denies(self):
        for fault in ({"active": False}, {"wrong_subject": True}, {"injected_claim": True}):
            with self.subTest(fault=fault), self.assertRaises(M.C.Rejected): self.master(**fault)

    def test_receipts_never_promote_browser_evidence_to_whole_case_or_db_evidence(self):
        for mode in ("master", "off"):
            result = M.receipt(mode, True)
            self.assertEqual("browser_verified", result["status"])
            self.assertFalse(result["db_no_mutations_verified"])
            self.assertFalse(result["whole_case_verified"])
            self.assertEqual({"case", "status", "browser_verified", "db_no_mutations_verified", "whole_case_verified"}, set(result))

    def test_off_requires_verified_owner_and_preserves_inventory_across_gateway_denial(self):
        data = {"origin": M.ORIGIN}
        class Wire:
            def __init__(self, data): self.data = data
        before = {"registration_active": True, "projection_acknowledged": True, "confirmed_event_ids": [], "bank_session_count": 3}
        with patch.object(M.C, "outcomes", return_value=before), patch.object(M.C, "fresh_code", return_value=({}, "nonce")), \
             patch.object(M.C, "token_response", return_value={"access_token": "private"}), patch.object(M, "absent"), \
             patch.object(M.C, "no_proof"), patch.object(M.C, "gateway") as gateway:
            self.assertTrue(M.execute({"mode": "off"}, data, factory=Wire)["browser_verified"])
            gateway.assert_called_once()
            self.assertEqual(401, gateway.call_args.args[2])
        for after in ({**before, "bank_session_count": 4}, {**before, "confirmed_event_ids": ["unexpected"]}):
            with patch.object(M.C, "outcomes", side_effect=[before, after]), patch.object(M, "master_browser"):
                with self.assertRaises(M.C.Rejected): M.execute({"mode": "master", "master": {}}, data, factory=Wire)

    def test_expired_budget_and_foreign_master_redirect_deny(self):
        with self.assertRaises(M.C.Rejected): M.execute({"mode": "off"}, {}, deadline=time.monotonic()-1)
        with self.assertRaises(M.C.Rejected):
            M.follow_master(object(), (302, {"Location": "https://untrusted.invalid/"}, b"", M.ORIGIN))

    def test_off_config_requires_exact_typed_false_flags(self):
        data = {k: M.ORIGIN for k in ("origin", "gateway_origin", "controller_origin")}
        profile = {"browser_flow": "browser", "registration_flow": "registration", "proof_enabled": False,
                   "verification_enabled": False, "observation_enabled": False}
        with patch.object(M.C, "configuration", return_value=data):
            M.configuration(json.dumps({"mode": "off", "acceptance": {}, "profile": profile}).encode())
            for key, value in (("proof_enabled", 0), ("verification_enabled", True), ("browser_flow", "custom")):
                with self.subTest(key=key), self.assertRaises(M.C.Rejected):
                    M.configuration(json.dumps({"mode": "off", "acceptance": {}, "profile": {**profile, key: value}}).encode())


if __name__ == "__main__": unittest.main()
