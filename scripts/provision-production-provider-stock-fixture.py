#!/usr/bin/env python3
"""Owned disposable stock setup only; never alters provider flags, users or authority."""
import argparse
import copy
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import stat
import urllib.parse

SPEC = importlib.util.spec_from_file_location("stock_base", Path(__file__).with_name("provision-production-provider-fixture.py"))
P = importlib.util.module_from_spec(SPEC); SPEC.loader.exec_module(P)
MASTER = P.BASE + "/admin/realms/master"
CLIENT = "armoney-production-stock-master"
CALLBACK = "https://fixture.local:8443/oauth/stock-master/callback"
RA_KEYS = {"alias", "providerId", "name", "enabled", "defaultAction", "priority", "config"}


def digest(c): return hashlib.sha256(json.dumps(c, sort_keys=True, separators=(",", ":")).encode()).hexdigest()


def configuration(raw):
    v = P.strict_json(raw)
    P.require(type(v) is dict and v.get("mode") in {"master", "off_prepare", "restore"})
    expected = {"mode", "provisioning"} | ({"client_secret"} if v["mode"] == "master" else {"snapshot"} if v["mode"] == "restore" else set())
    P.require(set(v) == expected)
    c = P.configuration(json.dumps(v["provisioning"]).encode())
    P.require(c["expected_issuer"] == "https://fixture.local:8443/sso/realms/armoney")
    if v["mode"] == "master":
        P.text(v["client_secret"], 256, 32)
        P.require(v["client_secret"] not in {c[x] for x in ("admin_password", "introspection_secret", "acceptance_admin_secret")})
    if v["mode"] == "restore": validate_snapshot(v["snapshot"], c)
    return v, c


def original_policy():
    return {"policies": [{"name": P.POLICY, "enabled": True, "conditions": [{"condition": "client-attributes", "configuration": {
        "attributes": json.dumps([{"key": P.ATTRIBUTE, "value": "true"}], separators=(",", ":"))}}], "profiles": [P.PROFILE]}]}


def ra(value, alias):
    P.require(type(value) is dict and set(value) == RA_KEYS and value.get("alias") == alias and value.get("providerId") == alias)
    P.text(value["name"], 128)
    P.require(type(value["enabled"]) is bool and type(value["defaultAction"]) is bool
              and type(value["priority"]) is int and 0 <= value["priority"] <= 10000 and value["config"] == {})
    return copy.deepcopy(value)


def validate_snapshot(s, c):
    P.require(type(s) is dict and set(s) == {"version", "configuration_hash", "realm_id", "realm", "actions", "policies"})
    P.require(type(s["version"]) is int and s["version"] == 1 and s["configuration_hash"] == digest(c))
    P.canonical_id(s["realm_id"])
    P.require(s["realm"] == {"browserFlow": P.BROWSER, "registrationFlow": P.REGISTRATION}
              and type(s["actions"]) is list and len(s["actions"]) == 2 and s["policies"] == original_policy())
    for value, alias in zip(s["actions"], (P.EMAIL, P.PHONE)):
        ra(value, alias); P.require(value["enabled"] is True and value["defaultAction"] is False)
    P.require(s["policies"]["policies"][0]["enabled"] is True)


def master_client(secret):
    return {"clientId": CLIENT, "enabled": True, "protocol": "openid-connect", "publicClient": False,
        "clientAuthenticatorType": "client-secret", "secret": secret, "standardFlowEnabled": True,
        "implicitFlowEnabled": False, "directAccessGrantsEnabled": False, "serviceAccountsEnabled": False,
        "fullScopeAllowed": False, "consentRequired": False, "redirectUris": [CALLBACK], "webOrigins": [],
        "defaultClientScopes": ["basic", "profile"], "optionalClientScopes": [],
        "attributes": {"pkce.code.challenge.method": "S256", "post.logout.redirect.uris": "-"},
        "protocolMappers": [{"name": "stock-master-self-audience", "protocol": "openid-connect", "protocolMapper": "oidc-audience-mapper",
            "consentRequired": False, "config": {"included.custom.audience": CLIENT, "access.token.claim": "true",
            "introspection.token.claim": "true", "id.token.claim": "false", "userinfo.token.claim": "false"}}]}


def check_master(actual, wanted):
    P.require(type(actual) is dict)
    P.canonical_id(actual.get("id"))
    for key, value in wanted.items():
        if key in {"secret", "protocolMappers"}: continue
        if key == "attributes": P.require(type(actual.get(key)) is dict and all(actual[key].get(k) == v for k, v in value.items()))
        elif key == "defaultClientScopes": P.require(type(actual.get(key)) is list and sorted(actual[key]) == sorted(value))
        else: P.require(type(actual.get(key)) is type(value) and actual[key] == value)
    mappers = actual.get("protocolMappers")
    P.require(type(mappers) is list and len(mappers) == 1)
    P.require(all(mappers[0].get(k) == v for k, v in wanted["protocolMappers"][0].items()))
    if "secret" in actual: P.require(actual["secret"] == wanted["secret"])


class Setup(P.Provisioner):
    def authenticate(self, c):
        self.stage = "token"
        body = self.call("POST", P.BASE + "/realms/master/protocol/openid-connect/token", 200,
            {"client_id": "admin-cli", "grant_type": "password", "username": c["admin_username"], "password": c["admin_password"]}, form=True)
        return P.text(body.get("access_token"), 32768)

    def master(self, c, secret, token):
        self.stage = "master"
        path = MASTER + "/clients?clientId=" + CLIENT
        P.require(self.call("GET", path, 200, token=token) == [])
        wanted = master_client(secret)
        self.call("POST", MASTER + "/clients", 201, wanted, token, document=False)
        listed = self.call("GET", path, 200, token=token)
        P.require(type(listed) is list and len(listed) == 1 and listed[0].get("clientId") == CLIENT)
        identifier = P.canonical_id(listed[0].get("id"))
        check_master(self.call("GET", MASTER + "/clients/" + identifier, 200, token=token), wanted)
        saved_secret = self.call("GET", MASTER + "/clients/" + identifier + "/client-secret", 200, token=token)
        P.require(saved_secret.get("value") == secret)
        P.require(self.call("GET", MASTER + "/clients/" + identifier + "/roles", 200, token=token) == [])
        mappings = self.call("GET", MASTER + "/clients/" + identifier + "/scope-mappings", 200, token=token)
        P.require(type(mappings) is dict and not mappings.get("realmMappings") and not mappings.get("clientMappings"))
        users = self.call("GET", MASTER + "/users?username=" + urllib.parse.quote(c["admin_username"], safe="") + "&exact=true", 200, token=token)
        P.require(type(users) is list and len(users) == 1 and users[0].get("username") == c["admin_username"])
        subject = P.canonical_id(users[0].get("id"))
        user = self.call("GET", MASTER + "/users/" + subject, 200, token=token)
        P.require(user.get("id") == subject and user.get("username") == c["admin_username"] and user.get("enabled") is True)
        return {"username": c["admin_username"], "password": c["admin_password"], "subject": subject, "client_secret": secret}

    def state(self, token):
        realm = self.call("GET", P.REALM, 200, token=token)
        P.require(type(realm) is dict and realm.get("realm") == "armoney")
        identifier = P.canonical_id(realm.get("id"))
        actions = [ra(self.call("GET", P.REALM + "/authentication/required-actions/" + alias, 200, token=token), alias) for alias in (P.EMAIL, P.PHONE)]
        policies = self.call("GET", P.REALM + "/client-policies/policies", 200, token=token)
        P.require(type(policies) is dict and type(policies.get("policies")) is list)
        return identifier, {k: realm.get(k) for k in ("browserFlow", "registrationFlow")}, actions, {"policies": policies["policies"]}

    def require_profile(self, token):
        profiles = self.call("GET", P.REALM + "/client-policies/profiles", 200, token=token)
        P.require(profiles.get("profiles") == [{"name": P.PROFILE, "executors": [{"executor": P.EXECUTOR, "configuration": {}}]}])

    def write(self, realm, actions, policies, token):
        self.call("PUT", P.REALM, 204, realm, token, document=False)
        for action in actions:
            self.call("PUT", P.REALM + "/authentication/required-actions/" + action["alias"], 204, action, token, document=False)
        self.call("PUT", P.REALM + "/client-policies/policies", 204, policies, token, document=False)

    @staticmethod
    def off(s):
        actions = [{**a, "enabled": False, "defaultAction": False} for a in s["actions"]]
        return s["realm_id"], {"browserFlow": "browser", "registrationFlow": "registration"}, actions, {"policies": []}

    def execute(self, v, c, persist):
        token = self.authenticate(c)
        if v["mode"] == "master":
            return {"mode": "master", "status": "configured", "master": self.master(c, v["client_secret"], token), "runtime_acceptance": False}
        self.stage = "snapshot"
        self.require_profile(token)
        current = self.state(token)
        if v["mode"] == "off_prepare":
            s = {"version": 1, "configuration_hash": digest(c), "realm_id": current[0], "realm": current[1], "actions": current[2], "policies": current[3]}
            validate_snapshot(s, c)
            persist({"mode": "off_prepare", "status": "snapshot_saved", "snapshot": s, "runtime_acceptance": False})
            target = self.off(s)
        else:
            s = v["snapshot"]; validate_snapshot(s, c)
            P.require(current == self.off(s))  # all original OFF fields checked BEFORE any restore write
            target = (s["realm_id"], s["realm"], s["actions"], s["policies"])
        self.stage = "write"
        self.write(target[1], target[2], target[3], token)
        self.stage = "readback"
        P.require(self.state(token) == target)
        self.require_profile(token)
        return {"mode": v["mode"], "status": "configured", "snapshot": s, "runtime_acceptance": False}


def private_read(path):
    P.require(os.name == "posix")
    fd = os.open(path, os.O_RDONLY | os.O_NOFOLLOW)
    try:
        info = os.fstat(fd)
        P.require(stat.S_ISREG(info.st_mode) and info.st_uid == os.getuid() and info.st_mode & 0o077 == 0)
        raw = os.read(fd, P.LIMIT + 1); P.require(len(raw) <= P.LIMIT)
        return raw
    finally: os.close(fd)


def main():
    class PrivateParser(argparse.ArgumentParser):
        def error(self, message):
            print("STOCK_SETUP_UNAVAILABLE")
            raise SystemExit(1) from None
    parser = PrivateParser(description=__doc__)
    parser.add_argument("--input", required=True); parser.add_argument("--result", required=True)
    args = parser.parse_args(); fd = None; result = {"status": "failed", "checkpoint": "config", "runtime_acceptance": False}
    setup = Setup(P.Transport())
    def persist(value):
        nonlocal result
        result = value
        os.lseek(fd, 0, os.SEEK_SET); os.ftruncate(fd, 0)
        os.write(fd, json.dumps(value, separators=(",", ":")).encode()); os.fsync(fd)
    try:
        v, c = configuration(private_read(args.input))
        fd = os.open(args.result, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
        persist(result)
        persist(setup.execute(v, c, persist))
    except Exception:
        result = {**result, "status": "failed", "checkpoint": setup.stage if setup.stage in {"config", "token", "master", "snapshot", "write", "readback"} else "config"}
        if fd is not None:
            try: persist(result)
            except Exception: pass
    finally:
        if fd is not None: os.close(fd)
    ok = result["status"] == "configured"
    print("STOCK_SETUP_CONFIGURED" if ok else "STOCK_SETUP_UNAVAILABLE")
    return 0 if ok else 1


if __name__ == "__main__": raise SystemExit(main())
