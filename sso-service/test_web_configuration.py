"""Offline browser OIDC contract regressions; no provider or database access."""
import json
from pathlib import Path
import unittest


class WebConfigurationTests(unittest.TestCase):
    def setUp(self):
        realm = json.loads((Path(__file__).parent / "armoney-realm.json").read_text())
        self.clients = {client["clientId"]: client for client in realm["clients"]}
        self.web = self.clients["armoney-web"]

    def test_browser_redirects_origins_logout_are_exact_https(self):
        origins = ["https://localhost:8443", "https://localhost:4200"]
        self.assertEqual(origins, self.web["webOrigins"])
        self.assertEqual([o + "/oauth/callback" for o in origins], self.web["redirectUris"])
        self.assertEqual([o + "/oauth/logout" for o in origins],
                         self.web["attributes"]["post.logout.redirect.uris"].split("##"))
        self.assertNotIn("secret", self.web)

    def test_public_code_only_s256_without_optional_scopes(self):
        for flag in ("enabled", "publicClient", "standardFlowEnabled"):
            self.assertTrue(self.web[flag])
        for flag in ("implicitFlowEnabled", "directAccessGrantsEnabled",
                     "serviceAccountsEnabled", "fullScopeAllowed"):
            self.assertFalse(self.web[flag])
        self.assertEqual("S256", self.web["attributes"]["pkce.code.challenge.method"])
        self.assertEqual("false", self.web["attributes"]["oauth2.device.authorization.grant.enabled"])
        self.assertEqual("false", self.web["attributes"]["oidc.ciba.grant.enabled"])
        self.assertEqual([], self.web["optionalClientScopes"])

    def test_access_only_audiences_and_phone_match_native_clients(self):
        self.assertEqual(self.clients["armoney-ios"]["protocolMappers"], self.web["protocolMappers"])
        self.assertEqual(self.clients["armoney-android"]["protocolMappers"], self.web["protocolMappers"])
        audiences = []
        for mapper in self.web["protocolMappers"]:
            config = mapper["config"]
            self.assertEqual("false", config["id.token.claim"])
            self.assertEqual("true", config["access.token.claim"])
            self.assertEqual("true", config["introspection.token.claim"])
            if mapper["protocolMapper"] == "oidc-audience-mapper":
                audiences.append(config["included.custom.audience"])
            else:
                self.assertEqual("phone_number", config["claim.name"])
                self.assertEqual("username", config["user.attribute"])
        self.assertEqual(["armoney-api", "armoney-auth"], audiences)

    def test_native_callbacks_remain_separate_and_confidential_client_has_no_grants(self):
        for platform in ("ios", "android"):
            client = self.clients["armoney-" + platform]
            self.assertEqual(["com.armoney." + platform + ":/oauth/callback"], client["redirectUris"])
            self.assertEqual([], client["webOrigins"])
        private = self.clients["armoney-auth"]
        self.assertFalse(private["publicClient"])
        self.assertFalse(private["standardFlowEnabled"])
        self.assertEqual([], private["redirectUris"])


if __name__ == "__main__":
    unittest.main()
