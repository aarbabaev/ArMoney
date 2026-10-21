"""Synthetic parsing/orchestration regressions; never migration or identity authority."""
import http.cookiejar
import importlib.util
import json
from pathlib import Path
import time
import unittest
from unittest.mock import patch
import urllib.parse

SPEC = importlib.util.spec_from_file_location("prefinal", Path(__file__).with_name("test_production_provider_prefinal_migration.py"))
M = importlib.util.module_from_spec(SPEC); SPEC.loader.exec_module(M)
CSRF = "c" * 43
COMMAND = "00000000-0000-0000-0000-000000000001"


def form(path):
    return ('<form method="post" action="' + path + '"><input type="hidden" name="csrf" value="' + CSRF
        + '"><input type="hidden" name="command_id" value="' + COMMAND + '"></form>').encode()


def authorization(managed):
    return M.ORIGIN + "/sso/realms/armoney/protocol/openid-connect/auth?" + urllib.parse.urlencode({
        "client_id": M.C.CLIENT if managed else M.C.ACCOUNT_CLIENT,
        "redirect_uri": M.ORIGIN + (M.MANAGED_CALLBACK if managed else M.ACCOUNT_CALLBACK), "response_type": "code",
        "scope": "openid", "state": "s" * 43, "nonce": "n" * 43, "code_challenge": "p" * 43,
        "code_challenge_method": "S256", "prompt": "login", "max_age": "0"})


class Prefinal(unittest.TestCase):
    def test_forms_require_exact_server_actions_csrf_uuid_and_never_allow_final(self):
        self.assertEqual({M.STAGE: {"csrf": CSRF, "command_id": COMMAND}}, M.Forms(form(M.STAGE)).forms)
        for raw in (form("https://untrusted.invalid"), form("/migration/legacy/otp/issue"), form("/migration/legacy/verify/final"),
                    form(M.STAGE).replace(CSRF.encode(), b"bad"), form(M.STAGE) + form(M.STAGE),
                    form(M.STAGE).replace(b'name="csrf"', b'name="owner_id"')):
            with self.subTest(kind="malformed"), self.assertRaises(M.C.Rejected): M.Forms(raw)

    def test_authorization_requires_creator_pkce_and_exact_native_target_no_aliases(self):
        for managed in (False, True):
            url = authorization(managed)
            self.assertEqual("s" * 43, M.authorization(url, managed))
            for wrong in (url.replace("S256", "plain"), url.replace("prompt=login", "prompt=none"), url.replace("max_age=0", "max_age=30"),
                          url + "&purpose=BANK_NATIVE", url.replace(M.ORIGIN, "https://other.invalid"), url + "&state=other"):
                with self.assertRaises(M.C.Rejected): M.authorization(wrong, managed)

    def test_actual_callback_is_forwarded_untouched_with_original_cookies_not_redeemed(self):
        location = authorization(True)
        callback = M.ORIGIN + M.MANAGED_CALLBACK + "?code=private-code&state=" + "s" * 43 + "&iss=" + urllib.parse.quote(M.ORIGIN + "/sso/realms/armoney", safe="")
        calls = []
        class Wire:
            data = {"username": "+971501234567", "password": "synthetic-private-password"}
            def request(self, url):
                calls.append(url)
                if url == callback: return 200, {"Content-Type": "text/html"}, form(M.STAGE)
                return 200, {}, b"provider-login"
        expiry = time.time()+60
        def cookie(wire, name): return ("account" if name == M.COOKIE_ACCOUNT else "attempt", expiry)
        with patch.object(M, "cookie", side_effect=cookie), patch.object(M.C, "follow", side_effect=lambda w, p: p), \
             patch.object(M.C, "submit", return_value=(302, {"Location": callback}, b"", location)):
            result = M.provider_callback(Wire(), (303, {"Location": location}, b""), True, "account")
        self.assertEqual({M.STAGE}, set(result))
        self.assertEqual([location, callback], calls)
        self.assertFalse(any(url.endswith("/token") for url in calls))

    def test_wrong_original_account_cookie_prevents_any_provider_request(self):
        wire = type("Wire", (), {"request": lambda *_: self.fail("must not request")})()
        with patch.object(M, "cookie", return_value=("wrong", time.time()+60)):
            with self.assertRaises(M.C.Rejected): M.provider_callback(wire, (303, {"Location": authorization(True)}, b""), True, "original")

    def test_replaced_or_renewed_attempt_cookie_denies_before_actual_callback_request(self):
        location = authorization(False)
        callback = M.ORIGIN + M.ACCOUNT_CALLBACK + "?code=private-code&state=" + "s"*43
        for changed in (("replacement", 100), ("original", 101)):
            calls = []
            class Wire:
                data = {"username": "+971501234567", "password": "synthetic-password"}
                def request(self, url):
                    calls.append(url)
                    self_outer.assertNotEqual(callback, url)
                    return 200, {}, b"provider-form"
            self_outer = self
            with patch.object(M, "cookie", side_effect=[("original", 100), changed]), \
                 patch.object(M.C, "follow", side_effect=lambda w, p: p), \
                 patch.object(M.C, "submit", return_value=(302, {"Location": callback}, b"", location)):
                with self.subTest(changed=changed), self.assertRaises(M.C.Rejected):
                    M.provider_callback(Wire(), (303, {"Location": location}, b""), False)
            self.assertEqual([location], calls)

    def test_local_and_sso_stop_at_stage_without_otp_or_final(self):
        for mode in ("local", "sso"):
            calls, provider_modes = [], []
            class Wire:
                def __init__(self, data): self.data = data
                def request(self, url, **kwargs):
                    calls.append((url, kwargs))
                    if url == M.ORIGIN + M.FORM: return 200, {"Content-Type": "text/html"}, form(M.PASSWORD)+form(M.ACCOUNT_START)
                    if url == M.ORIGIN + M.PASSWORD: return 200, {"Content-Type": "text/html"}, form(M.MANAGED_START)
                    return 303, {}, b""
            def provider(wire, response, managed, account_value=None):
                provider_modes.append(managed)
                return {M.STAGE if managed else M.MANAGED_START: {"csrf": CSRF, "command_id": COMMAND}}
            with patch.object(M, "cookie", return_value=("account", time.time()+60)), patch.object(M, "provider_callback", side_effect=provider):
                result = M.execute({"mode": mode, "legacy_login": "legacy@example.invalid", "legacy_password": "synthetic-password"}, {}, factory=Wire)
            self.assertTrue(result["prefinal_browser_verified"])
            self.assertEqual([True] if mode == "local" else [False, True], provider_modes)
            self.assertFalse(any("/otp/" in url or url.endswith(M.STAGE) or "/verify/final" in url for url, _ in calls))
            for url, kwargs in calls:
                if "form" in kwargs: self.assertEqual(M.ORIGIN, kwargs["headers"]["Origin"])
            self.assertFalse(result["db_preservation_verified"]); self.assertFalse(result["whole_migration_verified"])

    def test_sso_email_candidate_uses_existing_email_original_cookie_and_no_extra_fields(self):
        for replace in (False, True):
            calls = []
            expiry = time.time()+60
            class Wire:
                def __init__(self, data): self.data = data
                def request(self, url, **kwargs):
                    calls.append((url, kwargs))
                    if url == M.ORIGIN + M.FORM: return 200, {"Content-Type": "text/html"}, form(M.PASSWORD)+form(M.ACCOUNT_START)
                    if url == M.ORIGIN + M.EMAIL_CANDIDATE:
                        self_outer.assertEqual({"csrf": CSRF, "command_id": COMMAND, "email": "synthetic@example.invalid"}, kwargs["form"])
                        return 200, {"Content-Type": "text/html"}, form(M.MANAGED_START)
                    return 303, {}, b""
            self_outer = self
            cookie_calls = []
            def cookie(wire, name):
                cookie_calls.append(name)
                return ("changed" if replace and len(cookie_calls) == 3 else "account", expiry)
            def provider(wire, response, managed, account_value=None):
                return {M.STAGE if managed else M.EMAIL_CANDIDATE: {"csrf": CSRF, "command_id": COMMAND}}
            with patch.object(M, "cookie", side_effect=cookie), patch.object(M, "provider_callback", side_effect=provider):
                if replace:
                    with self.assertRaises(M.C.Rejected): M.execute({"mode": "sso"}, {"email": "synthetic@example.invalid"}, factory=Wire)
                else:
                    self.assertTrue(M.execute({"mode": "sso"}, {"email": "synthetic@example.invalid"}, factory=Wire)["prefinal_browser_verified"])
            self.assertFalse(any("/otp/" in url or "/verify/final" in url for url, _ in calls))
        with self.assertRaises(M.C.Rejected):
            M.post(object(), M.EMAIL_CANDIDATE, {M.EMAIL_CANDIDATE: {"csrf": CSRF, "command_id": COMMAND}}, {"email": "synthetic@example.invalid", "owner_id": "forbidden"})

    def test_cookie_security_shape_and_remaining_original_expiry_are_required(self):
        jar = http.cookiejar.CookieJar()
        def make(**changes):
            values = dict(version=0, name=M.COOKIE_ACCOUNT, value="a"*43, port=None, port_specified=False,
                domain="fixture.local", domain_specified=False, domain_initial_dot=False, path="/migration/", path_specified=True,
                secure=True, expires=int(time.time())+60, discard=False, comment=None, comment_url=None,
                rest={"HttpOnly": None, "SameSite": "Lax"})
            values.update(changes); return http.cookiejar.Cookie(**values)
        wire = type("Wire", (), {"cookies": jar})()
        jar.set_cookie(make()); self.assertEqual("a"*43, M.cookie(wire, M.COOKIE_ACCOUNT)[0])
        for changes in ({"secure": False}, {"path": "/"}, {"domain": "other.invalid"}, {"expires": int(time.time())-1}, {"rest": {"SameSite": "Lax"}}):
            jar.clear(); jar.set_cookie(make(**changes))
            with self.assertRaises(M.C.Rejected): M.cookie(wire, M.COOKIE_ACCOUNT)

    def test_receipt_contains_only_bools_no_owner_session_or_proof_and_expired_budget_denies(self):
        result = M.receipt("sso", True)
        self.assertTrue(all(type(x) is bool for x in result.values()))
        self.assertFalse(result["otp_submitted"]); self.assertFalse(result["final_submitted"])
        with self.assertRaises(M.C.Rejected): M.execute({"mode": "local"}, {}, deadline=time.monotonic()-1)


if __name__ == "__main__": unittest.main()
