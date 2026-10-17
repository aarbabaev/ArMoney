"""Offline synthetic-tempdir tests; no Java, Docker, network or account operations."""
import base64
import contextlib
import importlib.util
import io
import json
import os
from pathlib import Path
import stat
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location("builder", Path(__file__).with_name("build-code-intent-env.py"))
b = importlib.util.module_from_spec(spec)
spec.loader.exec_module(b)
REAL_POSIX_GUARD = b.require_posix


def env(path):
    return {line.split("=", 1)[0]: line.split("=", 1)[1][1:-1] for line in path.read_text().splitlines()}


class EnvironmentTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name) / "fresh"
        # Windows units exercise synthetic composition/files only, never NTFS ACL proof.
        # The unpatched POSIX guard is verified independently below; no bypass exists in CLI.
        if os.name != "posix":
            guard = patch.object(b, "require_posix", return_value=None)
            guard.start()
            self.addCleanup(guard.stop)

    def generate(self, **kwargs):
        b.initialize(self.root, "+971501234567", "synthetic@example.test", "Synthetic Fixture", **kwargs)

    def test_complete_separate_roles_fixed_origins_and_disabled_legacy(self):
        self.generate()
        auth = env(self.root / "auth.env")
        authority = env(self.root / "authority.env")
        user = env(self.root / "user.env")
        sms = env(self.root / "sms.env")
        inventory = b.read_private(self.root / "inventory.json")
        b.validate_inventory(inventory["roles"], inventory["material"])
        self.assertEqual(len(b.DIRECTIONAL), 8)
        self.assertEqual(auth["SSO_ISSUER"], b.ISSUER)
        self.assertEqual(auth["SSO_CLIENT_ID"], b.CLIENT)
        self.assertEqual(auth["AUTH_VERIFICATION_DELIVERY_ENABLED"], "false")
        self.assertEqual(auth["AUTH_SSO_LOGIN_PROOF_ENABLED"], "false")
        self.assertEqual(authority["SSO_ENROLLMENT_STATUS_ORIGIN"], "http://sso-service:8080")
        self.assertTrue(auth["DB_PASSWORD"] == env(self.root / "auth-db.env")["POSTGRES_PASSWORD"])
        self.assertTrue(user["DB_PASSWORD"] == env(self.root / "user-db.env")["POSTGRES_PASSWORD"])
        self.assertTrue(authority["VERIFICATION_SMS_SEND_TOKEN"] == sms["SMS_SANDBOX_SEND_TOKEN"])
        self.assertTrue(authority["SMS_SANDBOX_READ_TOKEN"] == sms["SMS_SANDBOX_READ_TOKEN"])
        self.assertTrue(auth["AUTH_TO_USER_VERIFICATION_PROJECTION_KEY"] == user["AUTH_TO_USER_VERIFICATION_PROJECTION_KEY"])
        provision = b.read_private(self.root / "provision.json")
        self.assertEqual(set(provision), {"phone", "email", "name", "password"})
        self.assertTrue(len(provision["password"]) >= 32)
        self.assertFalse((self.root / "sso-on.env").exists())

    def test_on_requires_canonical_external_uuid_and_preserves_original_material(self):
        self.generate()
        before = (self.root / "inventory.json").read_bytes()
        subject = "01234567-89ab-4cde-8fab-0123456789ab"
        b.enable(self.root, subject)
        on = env(self.root / "sso-on.env")
        self.assertEqual(on["ARMONEY_CODE_INTENT_FIXTURE_SUBJECT"], subject)
        self.assertEqual(on["ARMONEY_CODE_INTENT_FIXTURE_ENABLED"], "true")
        self.assertEqual(on["ARMONEY_AUTH_BRIDGE_URL"], "http://auth-service:8080")
        self.assertTrue(before == (self.root / "inventory.json").read_bytes())
        self.assertFalse(any("RETENTION" in k or "ENCRYPTION" in k for k in on))
        self.assertEqual(set(json.loads(on["ARMONEY_CODE_INTENT_FIXTURE_LOCAL_MATERIAL"])), {"code-v1"})
        with self.assertRaises(FileExistsError):
            b.enable(self.root, subject)

    def test_on_rejects_missing_noncanonical_and_nonuuid_without_creating_file(self):
        self.generate()
        for subject in (None, "", "not-a-uuid", "0123456789ab4cde8fab0123456789ab", "01234567-89AB-4CDE-8FAB-0123456789AB"):
            with self.subTest(kind=type(subject).__name__):
                with self.assertRaises(ValueError):
                    b.enable(self.root, subject)
                self.assertFalse((self.root / "sso-on.env").exists())

    def test_existing_output_even_empty_is_never_reused(self):
        self.root.mkdir()
        with self.assertRaises(ValueError):
            self.generate()
        self.assertEqual(list(self.root.iterdir()), [])

    def test_private_modes_on_posix(self):
        self.generate()
        if os.name == "posix":
            self.assertEqual(stat.S_IMODE(self.root.stat().st_mode), 0o700)
            self.assertTrue(all(stat.S_IMODE(p.stat().st_mode) == 0o600 for p in self.root.iterdir()))
        else:
            # No private-permission claim is made for the mocked Windows tempdir.
            with self.assertRaises(ValueError):
                REAL_POSIX_GUARD()

    def test_extra_material_is_explicit_and_provider_only(self):
        extra = {"other-local": base64.b64encode(bytes(range(40))).decode()}
        self.generate(local_material=extra)
        self.assertEqual(set(extra), {"other-local"})
        material = b.read_private(self.root / "inventory.json")["material"]
        self.assertEqual(set(material), {"code-v1", "other-local"})

    def test_reserved_extra_override_is_rejected(self):
        for key in ("code-v1", "binding", "SSO_LOGIN_PROOF_WRITE_KEY", "AUTH_SECRET"):
            with self.assertRaises(ValueError):
                self.generate(local_material={key: base64.b64encode(b"x" * 32).decode()})
        self.assertFalse(self.root.exists())

    def test_raw_base64_alias_collision_is_rejected_both_directions(self):
        self.generate()
        original = b.read_private(self.root / "inventory.json")
        for reverse in (False, True):
            roles = dict(original["roles"])
            raw = "q" * 32
            encoded = base64.b64encode(raw.encode()).decode()
            roles["stage"], roles["confirm"] = (encoded, raw) if reverse else (raw, encoded)
            with self.assertRaises(ValueError):
                b.validate_inventory(roles, original["material"])
        with self.assertRaises(ValueError):
            b.validate_inventory(original["roles"], {**original["material"], "alias": base64.b64encode(original["roles"]["stage"].encode()).decode()})

    def test_bad_contact_and_name_inputs_never_allocate_output(self):
        for phone, email, name in (("123", "fixture@example.test", "Fixture"), ("+971501234567", "bad\nmail", "Fixture"),
                ("+971501234567", "fixture@example.test", "bad\nname"), ("+971501234567", "fixture@example.test", " ")):
            with self.assertRaises(ValueError):
                b.initialize(self.root, phone, email, name)
        self.assertFalse(self.root.exists())

    def test_duplicate_and_oversized_json_are_rejected(self):
        for value in (b'{"x":"a","x":"b"}', b" " * (b.LIMIT + 1)):
            with self.assertRaises(ValueError):
                b.strict_json(value)

    def test_no_output_values_on_cli_failure(self):
        out, err = io.StringIO(), io.StringIO()
        with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
            result = b.main(["on", "--output", str(self.root), "--subject", "CANARY_SECRET"])
        self.assertEqual(result, 2)
        self.assertEqual(out.getvalue(), "")
        self.assertNotIn("CANARY", err.getvalue())

    def test_fresh_generations_have_independent_passwords_and_roles(self):
        self.generate()
        first = b.read_private(self.root / "inventory.json")
        other = Path(self.temp.name) / "other"
        b.initialize(other, "+971501234568", "another@example.test", "Another Fixture")
        second = b.read_private(other / "inventory.json")
        self.assertTrue(set(first["roles"].values()).isdisjoint(second["roles"].values()))


if __name__ == "__main__":
    unittest.main()
