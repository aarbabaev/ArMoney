"""Offline orchestration tests; these do not establish live SSO or money movement."""
import importlib.util
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location("seed", Path(__file__).with_name("seed-local-users.py"))
seed = importlib.util.module_from_spec(spec)
spec.loader.exec_module(seed)


class FakeHttp:
    def __init__(self, responses):
        self.responses = iter(responses)
        self.requests = []

    def json(self, method, address, data=None, **kwargs):
        self.requests.append((method, address, data))
        return next(self.responses)


class SeedTests(unittest.TestCase):
    def test_exact_reserved_fixture_set(self):
        fixtures = [seed.fixture(n) for n in range(100, 300)]
        self.assertEqual(200, len({u["username"] for u in fixtures}))
        self.assertEqual("+971528000100", fixtures[0]["username"])
        self.assertEqual("aarbabaev299@test.ae", fixtures[-1]["email"])
        self.assertEqual("Arbabaev 299", fixtures[-1]["lastName"])
        with self.assertRaises(seed.Blocked):
            seed.fixture(1000)

    def test_local_targets_only(self):
        for target in ("https://localhost:8443", "https://192.168.1.5:8443", "https://10.0.0.2"):
            self.assertEqual(target, seed.local_origin(target))
        for target in ("https://example.com", "https://8.8.8.8", "http://localhost", "https://user:pass@localhost", "https://169.254.169.254"):
            with self.assertRaises(seed.Blocked):
                seed.local_origin(target)
        with self.assertRaises(seed.Blocked):
            seed.local_origin("http://192.168.1.5", admin=True)

    def test_callback_validation(self):
        issuer = "https://localhost:8443/sso/realms/armoney"
        self.assertEqual("abc", seed.callback_code(seed.CALLBACK + "?code=abc&state=xyz", "xyz", issuer))
        for query in ("?code=abc&state=wrong", "?code=a&code=b&state=xyz", "?error=denied&state=xyz", "?code=a&state=xyz&iss=other"):
            with self.assertRaises(seed.Blocked):
                seed.callback_code(seed.CALLBACK + query, "xyz", issuer)
        with self.assertRaises(seed.Blocked):
            seed.callback_code("https://evil.invalid/?state=xyz&code=abc", "xyz", issuer)

    def test_provider_redirect_cannot_exfiltrate_credentials(self):
        issuer = "https://localhost:8443/sso/realms/armoney"
        for target in ("https://evil.invalid/login", "https://localhost:8443/sso/admin", "http://localhost:8443/sso/realms/armoney/login"):
            with self.assertRaises(seed.Blocked):
                seed.provider_location(target, issuer)

    def test_login_parser_hidden_fields_and_action(self):
        form = seed.LoginForm()
        form.feed('<form id="kc-form-login" method="post" action="https://localhost/x?a=1&amp;b=2"><input type="hidden" name="execution" value="test"></form>')
        self.assertEqual("https://localhost/x?a=1&b=2", form.action)
        self.assertEqual({"execution": "test"}, form.fields)

    def test_unknown_existing_user_is_never_adopted(self):
        user = dict(seed.fixture(100), id="00000000-0000-0000-0000-000000000001", enabled=True)
        http = FakeHttp([[user]])
        with self.assertRaises(seed.Blocked):
            seed.ensure_user(http, "http://localhost/sso", "synthetic", 100, "synthetic-password", None)
        self.assertEqual(["GET"], [r[0] for r in http.requests])

    def test_resume_does_not_reset_credentials(self):
        user = dict(seed.fixture(100), id="00000000-0000-0000-0000-000000000001", enabled=True)
        http = FakeHttp([[user]])
        self.assertEqual(user["id"], seed.ensure_user(http, "http://localhost/sso", "synthetic", 100,
            "different-synthetic-password", {"subject": user["id"]}))
        self.assertEqual(["GET"], [r[0] for r in http.requests])

    def test_existing_fields_and_subject_conflict(self):
        user = dict(seed.fixture(100), id="00000000-0000-0000-0000-000000000001", enabled=True)
        for previous, actual in (({"subject": "different"}, user), ({"subject": user["id"]}, dict(user, email="other@test.ae"))):
            with self.assertRaises(seed.Blocked):
                seed.ensure_user(FakeHttp([[actual]]), "http://localhost/sso", "synthetic", 100, "synthetic-password", previous)

    def test_creation_preserves_unverified_email(self):
        user = dict(seed.fixture(100), id="00000000-0000-0000-0000-000000000001", enabled=True)
        http = FakeHttp([[], None, [user]])
        seed.ensure_user(http, "http://localhost/sso", "synthetic", 100, "synthetic-password", None)
        payload = http.requests[1][2]
        self.assertIs(False, payload["emailVerified"])
        self.assertNotIn("requiredActions", payload)
        self.assertFalse(payload["credentials"][0]["temporary"])

    def test_missing_prior_identity_is_not_recreated(self):
        http = FakeHttp([[]])
        with self.assertRaises(seed.Blocked):
            seed.ensure_user(http, "http://localhost/sso", "synthetic", 100, "synthetic-password", {"subject": "old"})
        self.assertEqual(1, len(http.requests))

    def test_manifest_is_private_and_contains_no_credential(self):
        with tempfile.TemporaryDirectory() as directory:
            target = Path(directory) / "manifest.json"
            seed.save_manifest(target, {"namespace": seed.NAMESPACE, "users": {"100": {"subject": "synthetic-id"}}})
            self.assertNotIn("password", target.read_text())
            self.assertNotIn("access_token", target.read_text())
            if seed.os.name != "nt":
                self.assertEqual(0o600, target.stat().st_mode & 0o777)

    def test_native_login_uses_code_pkce_and_exact_callback(self):
        issuer = "https://localhost:8443/sso/realms/armoney"
        class LoginHttp(FakeHttp):
            def raw(self, method, address, data=None, **kwargs):
                self.requests.append((method, address, data))
                if method == "GET":
                    self.query = seed.url.parse_qs(seed.url.urlsplit(address).query)
                    return 200, {}, ('<form id="kc-form-login" method="post" action="' + issuer +
                        '/login-actions/authenticate"><input type="hidden" name="execution" value="synthetic"></form>').encode()
                return 302, {"Location": seed.CALLBACK + "?state=" + self.query["state"][0] + "&code=synthetic"}, b""
        http = LoginHttp([{"access_token": "synthetic"}])
        seed.login(http, issuer, seed.fixture(100)["username"], "synthetic-password")
        payload = http.requests[-1][2]
        self.assertEqual("authorization_code", payload["grant_type"])
        self.assertEqual(seed.CALLBACK, payload["redirect_uri"])
        challenge = seed.base64.urlsafe_b64encode(seed.hashlib.sha256(payload["code_verifier"].encode()).digest()).rstrip(b"=").decode()
        self.assertEqual([challenge], http.query["code_challenge"])
        self.assertEqual(["S256"], http.query["code_challenge_method"])
        self.assertNotIn("password", payload)

    def test_required_action_does_not_get_automatically_completed(self):
        class RequiredActionHttp(FakeHttp):
            def raw(self, *args, **kwargs):
                return 200, {}, b'<form id="kc-verify-email-form"></form>'
        with self.assertRaises(seed.Blocked):
            seed.login(RequiredActionHttp([]), "https://localhost/sso/realms/armoney", "synthetic", "synthetic-password")

    def test_wrong_bank_identity_stops_before_profile_mutation(self):
        http = FakeHttp([{"access_token": "synthetic"}, {"id": "00000000-0000-0000-0000-000000000001"}, None])
        with self.assertRaises(seed.Blocked):
            seed.provision(http, "https://localhost", {"access_token": "synthetic"}, 100,
                           {"identity_id": "00000000-0000-0000-0000-000000000002"})
        self.assertEqual(["POST", "GET", "POST"], [request[0] for request in http.requests])
        self.assertTrue(http.requests[-1][1].endswith("/v1/auth/logout"))

    def test_plan_has_no_network_or_credential_dependency(self):
        with patch.object(seed.sys, "argv", ["seed-local-users.py"]), patch.object(seed, "Http") as http:
            seed.main()
            http.assert_not_called()

    def test_count_bounds_and_default_plan(self):
        for count in (1, 200, 900):
            output = io.StringIO()
            with patch.object(seed.sys, "argv", ["seed", "--count", str(count)]), patch.object(seed.sys, "stdout", output):
                seed.main()
            self.assertIn(f"Plan: {count} synthetic", output.getvalue())
            self.assertIn(f"100..{99 + count}", output.getvalue())
        for count in (0, -1, 901):
            with patch.object(seed.sys, "argv", ["seed", "--count", str(count)]), patch.object(seed, "Http") as http:
                with self.assertRaises(seed.Blocked):
                    seed.main()
                http.assert_not_called()
        self.assertEqual("+971528000999", seed.fixture(999)["username"])

    def apply_args(self, path, count=200):
        return ["seed", "--apply", "--acknowledge-local-fixtures", "--ca-file", "synthetic-ca",
                "--manifest", str(path), "--count", str(count)]

    def manifest(self, count=200):
        return {"namespace": seed.NAMESPACE, "issuer": "https://localhost:8443/sso/realms/armoney",
                "gateway": "https://localhost:8443", "count": count, "users": {}}

    def test_interleaved_predecessor_checkpoint_is_read_after_lock(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "manifest.json"
            initial = self.manifest()
            seed.save_manifest(path, initial)
            checkpoint = dict(initial, users={"100": {"subject": "00000000-0000-0000-0000-000000000001", "status": "identity-created"}})
            real_open = seed.os.open
            def finish_predecessor_then_acquire(target, flags, mode=0o777):
                if str(target).endswith("manifest.json.lock"):
                    # Previous invocation commits after this invocation starts,
                    # but immediately before it acquires the released lock.
                    seed.save_manifest(path, checkpoint)
                return real_open(target, flags, mode)
            client = {"publicClient": True, "standardFlowEnabled": True, "directAccessGrantsEnabled": False,
                      "implicitFlowEnabled": False, "redirectUris": [seed.CALLBACK],
                      "attributes": {"pkce.code.challenge.method": "S256"}}
            http = FakeHttp([{"issuer": initial["issuer"]}, [client]])
            def inspect_checkpoint(*args):
                self.assertEqual(checkpoint["users"]["100"], args[-1])
                raise seed.Blocked("Synthetic stop before provisioning")
            with patch.object(seed.sys, "argv", self.apply_args(path)), patch.dict(seed.os.environ,
                    {"SEED_TEST_USER_PASSWORD": "synthetic-password", "KEYCLOAK_ADMIN_TOKEN": "synthetic"}), \
                    patch.object(seed.os, "open", side_effect=finish_predecessor_then_acquire), \
                    patch.object(seed, "AdminHttp", return_value=http), patch.object(seed, "ensure_user", side_effect=inspect_checkpoint):
                with self.assertRaisesRegex(seed.Blocked, "Synthetic stop"):
                    seed.main()
            self.assertEqual(checkpoint, json.loads(path.read_text()))
            self.assertFalse(path.with_name(path.name + ".lock").exists())

    def test_contender_cannot_read_or_chmod_locked_manifest(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "manifest.json"
            seed.save_manifest(path, self.manifest())
            with seed.manifest_lock(path), patch.object(seed.sys, "argv", self.apply_args(path)), \
                    patch.dict(seed.os.environ, {"SEED_TEST_USER_PASSWORD": "synthetic-password", "KEYCLOAK_ADMIN_TOKEN": "synthetic"}), \
                    patch.object(Path, "read_text") as read, patch.object(seed, "owner_only") as chmod:
                with self.assertRaisesRegex(seed.Blocked, "locked"):
                    seed.main()
                read.assert_not_called()
                chmod.assert_not_called()

    def test_manifest_count_change_is_rejected_without_writes_or_network(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "manifest.json"
            seed.save_manifest(path, self.manifest())
            with patch.object(seed.sys, "argv", self.apply_args(path, count=1)), patch.dict(seed.os.environ,
                    {"SEED_TEST_USER_PASSWORD": "synthetic-password", "KEYCLOAK_ADMIN_TOKEN": "synthetic"}), \
                    patch.object(seed, "save_manifest") as save, patch.object(seed, "Http") as http:
                with self.assertRaisesRegex(seed.Blocked, "Manifest target mismatch"):
                    seed.main()
                save.assert_not_called()
                http.assert_not_called()
            self.assertFalse(path.with_name(path.name + ".lock").exists())

    def admin_http(self, refresh="synthetic-refresh"):
        with patch.object(seed.Http, "__init__", return_value=None):
            return seed.AdminHttp("synthetic-ca", "https://localhost:9443/sso", "synthetic-access", refresh)

    def test_expired_admin_token_refreshes_once_and_keeps_rotation_in_memory(self):
        http = self.admin_http()
        endpoint = http.admin + "/admin/realms/armoney/users"
        tokens = json.dumps({"access_token": "synthetic-next", "refresh_token": "synthetic-rotated"}).encode()
        with patch.object(seed.Http, "raw", side_effect=[(401, {}, b""), (200, {}, tokens), (201, {}, b""), (200, {}, b"[]")]) as raw:
            http.json("POST", endpoint, {"synthetic": True}, token="synthetic-access", statuses=(201,))
            http.json("GET", endpoint, token="synthetic-access")
        self.assertEqual(4, raw.call_count)
        self.assertEqual("synthetic-next", raw.call_args_list[2].args[3])
        self.assertEqual("synthetic-next", raw.call_args_list[3].args[3])
        self.assertEqual("synthetic-rotated", http.refresh_token)
        self.assertEqual({"client_id": "admin-cli", "grant_type": "refresh_token", "refresh_token": "synthetic-refresh"}, raw.call_args_list[1].args[2])

    def test_admin_refresh_rejection_never_replays_creation(self):
        http = self.admin_http()
        with patch.object(seed.Http, "raw", side_effect=[(401, {}, b""), (400, {}, b"sensitive remote error")]) as raw:
            with self.assertRaisesRegex(seed.Blocked, r"Admin token refresh rejected \(HTTP 400\)"):
                http.json("POST", http.admin + "/admin/realms/armoney/users", {}, token="synthetic-access", statuses=(201,))
        self.assertEqual(2, raw.call_count)

    def test_admin_retry_cannot_loop_on_second_401(self):
        http = self.admin_http()
        tokens = json.dumps({"access_token": "synthetic-next"}).encode()
        with patch.object(seed.Http, "raw", side_effect=[(401, {}, b""), (200, {}, tokens), (401, {}, b"" )]) as raw:
            with self.assertRaisesRegex(seed.Blocked, "API request rejected"):
                http.json("GET", http.admin + "/admin/realms/armoney/users", token="synthetic-access")
        self.assertEqual(3, raw.call_count)

    def test_admin_access_only_and_uncertain_writes_are_not_retried(self):
        for refresh, outcome in ((None, (401, {}, b"")), ("synthetic-refresh", (503, {}, b"")),
                                 ("synthetic-refresh", TimeoutError("synthetic timeout"))):
            http = self.admin_http(refresh)
            with patch.object(seed.Http, "raw", side_effect=[outcome]) as raw:
                with self.assertRaises((seed.Blocked, TimeoutError)):
                    http.json("POST", http.admin + "/admin/realms/armoney/users", {}, token="synthetic-access", statuses=(201,))
            self.assertEqual(1, raw.call_count)

    def test_apply_requires_acknowledgment_before_requests(self):
        with patch.object(seed.sys, "argv", ["seed-local-users.py", "--apply"]), patch.object(seed, "Http") as http:
            with self.assertRaises(seed.Blocked):
                seed.main()
            http.assert_not_called()


if __name__ == "__main__":
    unittest.main()
