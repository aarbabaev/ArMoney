"""Offline configuration and administrator-helper regressions; synthetic data only."""
import contextlib
import copy
import importlib.util
import io
import json
import os
from pathlib import Path
import re
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parent
SPEC = importlib.util.spec_from_file_location("phone_apply", ROOT / "apply-phone-registration.py")
HELPER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(HELPER)


class Response:
    def __init__(self, value):
        self.body = b"" if value is None else json.dumps(value).encode()

    def __enter__(self):
        return self

    def __exit__(self, *args):
        return False

    def read(self, size):
        return self.body[:size]


class SyntheticAdmin:
    def __init__(self):
        self.realm = {"realm": "armoney", "registrationEmailAsUsername": False,
                      "editUsernameAllowed": True, "displayName": "Existing realm"}
        self.profile = json.loads((ROOT / "phone-user-profile.json").read_text())
        self.profile["attributes"][0]["displayName"] = "Existing username"
        self.profile["attributes"].append({"name": "custom", "permissions": {"view": ["admin"]}})
        self.profile["unmanagedAttributePolicy"] = "DISABLED"
        self.mappers = {client: [{"id": "aud", "name": "keep-audience", "config": {"included.custom.audience": "armoney-api"}}]
                        for client in ("armoney-ios", "armoney-android")}
        self.writes = []

    def open(self, request, timeout):
        method = request.method
        path = request.full_url.split("/admin/realms/armoney", 1)[1]
        body = json.loads(request.data) if request.data else None
        if method != "GET":
            self.writes.append((method, path, body))
        if not path:
            if method == "PUT":
                self.realm.update(body)
            return Response(self.realm if method == "GET" else None)
        if path == "/users/profile":
            if method == "PUT":
                self.profile = body
            return Response(self.profile if method == "GET" else None)
        if path.startswith("/clients?clientId="):
            client = path.split("=", 1)[1]
            return Response([{"id": client, "clientId": client}])
        if path.startswith("/clients/"):
            parts = path.split("/")
            mappers = self.mappers[parts[2]]
            if method == "POST":
                mappers.append(dict(body, id="phone"))
            if method == "PUT":
                mappers[:] = [body if item["id"] == parts[-1] else item for item in mappers]
            return Response(mappers if method == "GET" else None)
        raise AssertionError("Unexpected admin request")


class PhoneConfigurationTests(unittest.TestCase):
    def invoke(self, admin, apply=False):
        argv = ["apply-phone-registration.py", "--admin-base", "https://synthetic.invalid/sso"]
        if apply:
            argv.append("--apply")
        with patch.object(HELPER.urllib.request, "build_opener", return_value=admin), \
                patch.dict(os.environ, {"KEYCLOAK_ADMIN_TOKEN": "synthetic-token"}), \
                patch("sys.argv", argv), contextlib.redirect_stdout(io.StringIO()):
            HELPER.main()

    def test_import_and_readable_profile_match_and_validate_uae_only(self):
        realm = json.loads((ROOT / "armoney-realm.json").read_text())
        profile = json.loads((ROOT / "phone-user-profile.json").read_text())
        embedded = realm["components"]["org.keycloak.userprofile.UserProfileProvider"][0]
        self.assertEqual(profile, json.loads(embedded["config"]["kc.user.profile.config"][0]))
        self.assertFalse(realm["editUsernameAllowed"])
        regex = profile["attributes"][0]["validations"]["pattern"]["pattern"]
        for prefix in "024568":
            self.assertIsNotNone(re.fullmatch(regex, "+9715" + prefix + "1234567"))
        for value in ("", "0501234567", "+971511234567", "+12025550123", "+9715012345678", "+971５０１２３４５６７"):
            self.assertIsNone(re.fullmatch(regex, value))
        for client in realm["clients"]:
            if client["clientId"] in ("armoney-ios", "armoney-android"):
                mapper = next(m for m in client["protocolMappers"] if m["name"] == "armoney-phone")
                self.assertEqual("username", mapper["config"]["user.attribute"])
                self.assertEqual("true", mapper["config"]["introspection.token.claim"])

    def test_default_is_read_only(self):
        admin = SyntheticAdmin()
        self.invoke(admin)
        self.assertEqual([], admin.writes)

    def test_apply_preserves_unrelated_configuration_and_is_repeatable(self):
        admin = SyntheticAdmin()
        before = copy.deepcopy(admin.profile)
        self.invoke(admin, apply=True)
        self.invoke(admin, apply=True)
        self.assertEqual(before["attributes"][1:], admin.profile["attributes"][1:])
        self.assertEqual(before["groups"], admin.profile["groups"])
        self.assertEqual("DISABLED", admin.profile["unmanagedAttributePolicy"])
        self.assertEqual("Existing realm", admin.realm["displayName"])
        self.assertFalse(admin.realm["editUsernameAllowed"])
        for mappers in admin.mappers.values():
            self.assertEqual(2, len(mappers))
            self.assertEqual("keep-audience", mappers[0]["name"])
        self.assertTrue(all(path == "" or path == "/users/profile" or
            "/protocol-mappers/models" in path for _, path, _ in admin.writes))

    def test_conflicting_phone_mapper_blocks_all_writes(self):
        admin = SyntheticAdmin()
        admin.mappers["armoney-android"].append({"id": "conflict", "name": "other-phone", "config": {"claim.name": "phone_number"}})
        with self.assertRaises(SystemExit):
            self.invoke(admin, apply=True)
        self.assertEqual([], admin.writes)


if __name__ == "__main__":
    unittest.main()
