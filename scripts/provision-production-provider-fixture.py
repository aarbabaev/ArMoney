#!/usr/bin/env python3
"""Create only an ABSENT realm in a caller-owned disposable production-provider fixture.

Private JSON input and a NEW private result path are mandatory. No environment
scanning, redirects, retries, user creation, import of a repository realm, or
existing-realm updates. On any failure root must dispose of the owned topology.
Input assertions cannot establish actual isolation. Never print response bodies.
"""
import argparse
import http.client
import json
from pathlib import Path
import re
import socket
import sys
import threading
import time
import urllib.parse
import uuid

HOST, PORT, BASE = "sso-service", 8080, "/sso"
REALM = BASE + "/admin/realms/armoney"
LIMIT, TIMEOUT = 262144, 4.0
BANK = ("armoney-ios", "armoney-android", "armoney-web")
ACCOUNT = "armoney-migration-account"
PUBLIC = frozenset((*BANK, ACCOUNT))
BROWSER, REGISTRATION, FORM = "armoney-native-password-proof", "armoney-native-registration", "armoney-native-registration-form"
EMAIL, PHONE = "armoney-verify-email-code", "armoney-verify-phone-code"
PROFILE, POLICY, EXECUTOR = "armoney-code-intent-profile", "armoney-code-intent-bank-clients", "armoney-code-intent-confirmation"
ATTRIBUTE = "armoney.code-intent-policy"
STAGES = frozenset({"config", "token", "absent", "create", "clients", "roles", "flows", "actions", "profiles", "policies", "activate", "readback"})


class Refused(Exception):
    pass


CLIENT_FIELDS = ("clientId", "enabled", "protocol", "publicClient", "standardFlowEnabled", "implicitFlowEnabled",
                 "directAccessGrantsEnabled", "serviceAccountsEnabled", "fullScopeAllowed", "redirectUris", "webOrigins")
INSPECTION_KEYS = frozenset({"included.custom.audience", "id.token.claim", "access.token.claim", "introspection.token.claim", "userinfo.token.claim"})
STANDARD_DIAGNOSTIC_KEYS = frozenset({"lightweight.claim", "access.tokenResponse.claim"})
CLIENT_PREDICATES = frozenset({"shape", "attributes", "scopes", "mapper-count", "inspection-config-shape",
    "inspection-config-keyset", "inspection-config-value", "mapper-contract", "secret-absent", *CLIENT_FIELDS})


def client_check(ok, predicate, config_key=None, present=None, matches=None):
    if ok:
        return
    failure = Refused()
    if predicate in CLIENT_PREDICATES:
        diagnostic = {"predicate": predicate}
        if config_key in INSPECTION_KEYS and type(present) is bool and type(matches) is bool:
            diagnostic.update(config_key=config_key, present=present, matches=matches)
        failure.diagnostic = diagnostic
    raise failure from None


def safe_diagnostic(failure):
    if type(failure) is not Refused:
        return None
    value = getattr(failure, "diagnostic", None)
    if type(value) is not dict or value.get("predicate") not in CLIENT_PREDICATES:
        return None
    if set(value) == {"predicate"}:
        return {"predicate": value["predicate"]}
    if (set(value) == {"predicate", "expected_keys", "standard_keys", "client_audience_present", "client_audience_empty", "unexpected_key_count", "unexpected_all_empty"}
            and value["predicate"] == "inspection-config-keyset"
            and type(value["expected_keys"]) is dict and set(value["expected_keys"]) == INSPECTION_KEYS
            and all(type(v) is dict and set(v) == {"present", "matches"}
                    and type(v["present"]) is bool and type(v["matches"]) is bool for v in value["expected_keys"].values())
            and type(value["standard_keys"]) is dict and set(value["standard_keys"]) == STANDARD_DIAGNOSTIC_KEYS
            and all(type(v) is dict and set(v) == {"present", "string_true", "string_false", "empty", "other"}
                    and all(type(b) is bool for b in v.values()) for v in value["standard_keys"].values())
            and type(value["client_audience_present"]) is bool and type(value["client_audience_empty"]) is bool
            and type(value["unexpected_key_count"]) is int and 0 <= value["unexpected_key_count"] <= 16
            and type(value["unexpected_all_empty"]) is bool):
        return {**value, "expected_keys": {k: dict(v) for k, v in value["expected_keys"].items()},
                "standard_keys": {k: dict(v) for k, v in value["standard_keys"].items()}}
    if (set(value) == {"predicate", "config_key", "present", "matches"}
            and value["predicate"] == "inspection-config-value" and value["config_key"] in INSPECTION_KEYS
            and type(value["present"]) is bool and type(value["matches"]) is bool):
        return dict(value)
    return None


def require(ok):
    if not ok:
        raise Refused() from None


def strict_json(raw):
    def pairs(items):
        out = {}
        for key, value in items:
            require(key not in out)
            out[key] = value
        return out
    try:
        require(type(raw) is bytes and len(raw) <= LIMIT and not raw.startswith(b"\xef\xbb\xbf"))
        out = json.loads(raw.decode("utf-8", "strict"), object_pairs_hook=pairs,
                         parse_constant=lambda _: require(False))
        json.dumps(out, ensure_ascii=False).encode("utf-8", "strict")
        return out
    except Exception:
        raise Refused() from None


def text(value, maximum=256, minimum=1):
    require(type(value) is str and minimum <= len(value) <= maximum and all(33 <= ord(c) <= 126 for c in value))
    return value


def canonical_id(value):
    try:
        require(type(value) is str and str(uuid.UUID(value)) == value and uuid.UUID(value).int != 0)
        return value
    except Exception:
        raise Refused() from None


def https(value, path):
    text(value, 2048)
    try:
        uri = urllib.parse.urlsplit(value)
        require(uri.scheme == "https" and uri.hostname and uri.path == path and not uri.query and not uri.fragment
                and not uri.username and not uri.password and not any(c in value for c in ("%", "*", "\\")))
        require(re.fullmatch(r"[a-z0-9]+(?:[.-][a-z0-9]+)*", uri.hostname) is not None)
        port = uri.port
        require(port is None or 1 <= port <= 65535)
        origin = "https://" + uri.hostname + ((":" + str(port)) if port else "")
        require(value == origin + path)
        return origin
    except Exception:
        raise Refused() from None


def configuration(raw):
    try:
        return _configuration(raw)
    except Exception:
        raise Refused() from None


def _configuration(raw):
    c = strict_json(raw)
    require(type(c) is dict and set(c) == {"CI", "CODE_SESSION_ACCEPTANCE_ISOLATED", "fresh_realm", "expected_issuer",
            "admin_username", "admin_password", "introspection_secret", "acceptance_admin_secret", "purpose_manifest"})
    require(c["CI"] == "true" and c["CODE_SESSION_ACCEPTANCE_ISOLATED"] == "true" and c["fresh_realm"] is True)
    origin = https(c["expected_issuer"], "/sso/realms/armoney")
    text(c["admin_username"], 128)
    for field in ("admin_password", "introspection_secret", "acceptance_admin_secret"):
        text(c[field], 256, 15 if field == "admin_password" else 32)
    require(len({c[name] for name in ("admin_password", "introspection_secret", "acceptance_admin_secret")}) == 3)
    manifest = c["purpose_manifest"]
    require(type(manifest) is list and 5 <= len(manifest) <= 16)
    seen, clients, required = set(), set(), set()
    for item in manifest:
        require(type(item) is dict and set(item) == {"issuer", "client_id", "redirect_uri", "purpose"})
        require(item["issuer"] == c["expected_issuer"] and item["client_id"] in PUBLIC)
        client, redirect, purpose = item["client_id"], text(item["redirect_uri"], 2048), item["purpose"]
        require(purpose in {"BANK_NATIVE", "MIGRATION_MANAGED", "MIGRATION_ACCOUNT"})
        key = (client, redirect)
        require(key not in seen)
        seen.add(key)
        clients.add(client)
        if purpose == "BANK_NATIVE":
            require(client in BANK)
            if client == "armoney-ios":
                require(redirect == "com.armoney.ios:/oauth/callback")
            elif client == "armoney-android":
                require(redirect == "com.armoney.android:/oauth/callback")
            else:
                require(https(redirect, "/oauth/callback") == origin)
            required.add((client, purpose))
        elif purpose == "MIGRATION_MANAGED":
            require(client == "armoney-android" and redirect == origin + "/migration/managed/callback")
            required.add((client, purpose))
        else:
            require(client == ACCOUNT and redirect == origin + "/migration/legacy/account-oidc/callback")
            required.add((client, purpose))
    require(clients == PUBLIC and required == {(client, "BANK_NATIVE") for client in BANK}
            | {("armoney-android", "MIGRATION_MANAGED"), (ACCOUNT, "MIGRATION_ACCOUNT")})
    return c


class Transport:
    """One fixed destination and a whole-request deadline, including slow bodies."""
    def request(self, method, path, body=None, token=None, form=False):
        require(method in {"GET", "POST", "PUT"} and path.startswith(BASE + "/") and "\r" not in path and "\n" not in path)
        connection = http.client.HTTPConnection(HOST, PORT, timeout=TIMEOUT)
        deadline, owned_socket = time.monotonic() + TIMEOUT, []
        def abort():
            for current in owned_socket:
                try:
                    current.shutdown(socket.SHUT_RDWR)
                except OSError:
                    pass
            connection.close()
        timer = threading.Timer(TIMEOUT, abort)
        timer.daemon = True
        payload = None if body is None else (urllib.parse.urlencode(body).encode("ascii") if form else json.dumps(body, separators=(",", ":"), allow_nan=False).encode("utf-8"))
        require(payload is None or len(payload) <= LIMIT)
        headers = {"Accept": "application/json", "Connection": "close"}
        if payload is not None:
            headers["Content-Type"] = "application/x-www-form-urlencoded" if form else "application/json"
        if token is not None:
            headers["Authorization"] = "Bearer " + text(token, 32768)
        try:
            timer.start()
            connection.request(method, path, body=payload, headers=headers)
            if connection.sock is not None:
                owned_socket.append(connection.sock)
            require(time.monotonic() < deadline)
            response = connection.getresponse()
            status = response.status
            raw = response.read(LIMIT + 1)
            require(time.monotonic() < deadline and len(raw) <= LIMIT and 100 <= status <= 599)
            return status, raw
        except Exception:
            raise Refused() from None
        finally:
            timer.cancel()
            connection.close()


def public_client(c, client):
    value = {"clientId": client, "enabled": True, "protocol": "openid-connect", "publicClient": True,
            "standardFlowEnabled": True, "implicitFlowEnabled": False, "directAccessGrantsEnabled": False,
            "serviceAccountsEnabled": False, "fullScopeAllowed": False, "consentRequired": False,
            "redirectUris": [x["redirect_uri"] for x in c["purpose_manifest"] if x["client_id"] == client],
            "webOrigins": [], "attributes": {"pkce.code.challenge.method": "S256", "post.logout.redirect.uris": "-",
                "oauth2.device.authorization.grant.enabled": "false", "oidc.ciba.grant.enabled": "false", ATTRIBUTE: "true"},
            "defaultClientScopes": ["basic", "profile", "email"], "optionalClientScopes": ["offline_access"] if client == "armoney-android" else []}
    if client in BANK:
        value["protocolMappers"] = [{"name": name, "protocol": "openid-connect", "protocolMapper": "oidc-audience-mapper", "consentRequired": False,
            "config": {"included.custom.audience": audience, "id.token.claim": "false", "access.token.claim": "true", "introspection.token.claim": "true"}}
            for name, audience in (("armoney-api-audience", "armoney-api"), ("armoney-auth-introspection-audience", "armoney-auth"))]
        value["protocolMappers"].append({"name": "armoney-phone", "protocol": "openid-connect", "protocolMapper": "oidc-usermodel-property-mapper", "consentRequired": False,
            "config": {"claim.name": "phone_number", "user.attribute": "username", "jsonType.label": "String", "id.token.claim": "false",
                       "access.token.claim": "true", "introspection.token.claim": "true", "userinfo.token.claim": "false"}})
    elif client == ACCOUNT:
        value["protocolMappers"] = [{"name": "armoney-auth-introspection-audience", "protocol": "openid-connect",
            "protocolMapper": "oidc-audience-mapper", "consentRequired": False,
            "config": {"included.custom.audience": "armoney-auth", "id.token.claim": "false",
                       "access.token.claim": "true", "introspection.token.claim": "true", "userinfo.token.claim": "false"}}]
    return value


def confidential(client, secret, service):
    return {"clientId": client, "enabled": True, "protocol": "openid-connect", "publicClient": False,
            "clientAuthenticatorType": "client-secret", "secret": secret, "standardFlowEnabled": False,
            "implicitFlowEnabled": False, "directAccessGrantsEnabled": False, "serviceAccountsEnabled": service,
            "fullScopeAllowed": False, "redirectUris": [], "webOrigins": [],
            "defaultClientScopes": ["roles"] if service else [], "optionalClientScopes": []}


def user_profile():
    attrs = []
    for name in ("username", "email", "firstName", "lastName"):
        validators = {"length": {"max": 255}}
        if name == "username":
            validators = {"length": {"min": 13, "max": 13}, "pattern": {"pattern": r"^\+9715[024568][0-9]{7}$"},
                          "username-prohibited-characters": {}, "up-username-not-idn-homograph": {}}
        elif name == "email":
            validators["email"] = {}
        else:
            validators["person-name-prohibited-characters"] = {}
        entry = {"name": name, "permissions": {"view": ["admin", "user"], "edit": ["admin", "user"]}, "validations": validators}
        if name != "username":
            entry["required"] = {"roles": ["user"]}
        attrs.append(entry)
    return {"attributes": attrs, "groups": []}


class Provisioner:
    def __init__(self, transport):
        self.transport, self.stage = transport, "config"

    def call(self, method, path, expected, body=None, token=None, form=False, document=True):
        status, raw = self.transport.request(method, path, body, token, form)
        require(status == expected)
        return strict_json(raw) if document else None

    def client(self, name, token):
        items = self.call("GET", REALM + "/clients?clientId=" + name, 200, token=token)
        require(type(items) is list and len(items) == 1 and type(items[0]) is dict and items[0].get("clientId") == name)
        client_id = canonical_id(items[0].get("id"))
        actual = self.call("GET", REALM + "/clients/" + client_id, 200, token=token)
        require(type(actual) is dict and actual.get("id") == client_id and actual.get("clientId") == name)
        return actual

    def execution(self, flow, provider, token, priority):
        self.call("POST", REALM + "/authentication/flows/" + flow + "/executions/execution", 201,
                  {"provider": provider, "priority": priority}, token, document=False)
        listing = self.call("GET", REALM + "/authentication/flows/" + flow + "/executions", 200, token=token)
        found = [x for x in listing if type(x) is dict and x.get("providerId") == provider]
        require(len(found) == 1)
        row = found[0]
        canonical_id(row.get("id"))
        self.call("PUT", REALM + "/authentication/flows/" + flow + "/executions", 204,
                  {"id": row["id"], "requirement": "REQUIRED", "priority": priority}, token, document=False)
        model = self.call("GET", REALM + "/authentication/executions/" + row["id"], 200, token=token)
        require(model.get("id") == row["id"] and model.get("authenticator") == provider
                and model.get("authenticatorFlow") is False and model.get("requirement") == "REQUIRED")

    def offline_access(self, android, token):
        """Only a built-in optional scope and fresh realm default-role composite; no user role writes."""
        realm = self.call("GET", REALM, 200, token=token)
        default = realm.get("defaultRole")
        require(type(default) is dict and default.get("name") == "default-roles-armoney")
        default_id = canonical_id(default.get("id"))
        saved_default = self.call("GET", REALM + "/roles-by-id/" + default_id, 200, token=token)
        require(saved_default.get("id") == default_id and saved_default.get("name") == default["name"]
                and saved_default.get("clientRole") is False and saved_default.get("containerId") == realm.get("id"))
        offline = self.call("GET", REALM + "/roles/offline_access", 200, token=token)
        offline_id = canonical_id(offline.get("id"))
        require(offline.get("name") == "offline_access" and offline.get("clientRole") is False
                and offline.get("containerId") == realm.get("id"))
        scopes = self.call("GET", REALM + "/client-scopes", 200, token=token)
        require(type(scopes) is list and len(scopes) <= 128)
        candidates = [x for x in scopes if x.get("name") == "offline_access"]
        require(len(candidates) == 1 and candidates[0].get("protocol") == "openid-connect")
        scope_id = canonical_id(candidates[0].get("id"))
        assigned = self.call("GET", REALM + "/clients/" + android["id"] + "/optional-client-scopes", 200, token=token)
        require(type(assigned) is list and len(assigned) == 1 and assigned[0].get("id") == scope_id and assigned[0].get("name") == "offline_access")
        scope_roles = self.call("GET", REALM + "/client-scopes/" + scope_id + "/scope-mappings/realm", 200, token=token)
        require(type(scope_roles) is list and len(scope_roles) == 1 and scope_roles[0].get("id") == offline_id)
        composites_path = REALM + "/roles-by-id/" + default_id + "/composites/realm"
        composites = self.call("GET", composites_path, 200, token=token)
        require(type(composites) is list and len(composites) <= 128)
        if not any(x.get("id") == offline_id for x in composites):
            self.call("POST", REALM + "/roles-by-id/" + default_id + "/composites", 204, [offline], token, document=False)
            composites = self.call("GET", composites_path, 200, token=token)
        require(type(composites) is list and sum(x.get("id") == offline_id and x.get("name") == "offline_access" for x in composites) == 1)

    def run(self, c):
        self.stage = "token"
        result = self.call("POST", BASE + "/realms/master/protocol/openid-connect/token", 200,
                           {"client_id": "admin-cli", "grant_type": "password", "username": c["admin_username"], "password": c["admin_password"]}, form=True)
        token = text(result.get("access_token"), 32768)
        self.stage = "absent"
        self.call("GET", REALM, 404, token=token, document=False)
        self.stage = "create"
        clients = [public_client(c, name) for name in BANK] + [public_client(c, ACCOUNT),
                   confidential("armoney-auth", c["introspection_secret"], False),
                   confidential("armoney-production-acceptance-admin", c["acceptance_admin_secret"], True)]
        seed = {"realm": "armoney", "enabled": True, "sslRequired": "external", "registrationAllowed": False,
                "registrationEmailAsUsername": False, "loginWithEmailAllowed": False, "duplicateEmailsAllowed": False,
                "editUsernameAllowed": False, "resetPasswordAllowed": False, "rememberMe": False, "bruteForceProtected": True,
                "passwordPolicy": "length(15)", "accessTokenLifespan": 300, "accessCodeLifespan": 60, "loginTheme": "armoney",
                "clients": clients, "components": {"org.keycloak.userprofile.UserProfileProvider": [{"name": "declarative-user-profile",
                    "providerId": "declarative-user-profile", "config": {"kc.user.profile.config": [json.dumps(user_profile(), separators=(",", ":"))]}}]}}
        self.call("POST", BASE + "/admin/realms", 201, seed, token, document=False)
        self.stage = "clients"
        saved = {name: self.client(name, token) for name in (*BANK, ACCOUNT, "armoney-auth", "armoney-production-acceptance-admin")}
        inventory = self.call("GET", REALM + "/clients?max=100", 200, token=token)
        builtin = {"account", "account-console", "admin-cli", "broker", "realm-management", "security-admin-console"}
        require(type(inventory) is list and len(inventory) < 100 and {x.get("clientId") for x in inventory} - builtin == set(saved))
        for name in PUBLIC:
            check_public(saved[name], public_client(c, name))
        for name, secret, service in (("armoney-auth", c["introspection_secret"], False), ("armoney-production-acceptance-admin", c["acceptance_admin_secret"], True)):
            expected = confidential(name, secret, service)
            require(all(type(saved[name].get(key)) is type(expected[key]) and saved[name][key] == expected[key]
                        for key in ("enabled", "protocol", "publicClient", "clientAuthenticatorType", "standardFlowEnabled", "implicitFlowEnabled",
                                    "directAccessGrantsEnabled", "serviceAccountsEnabled", "fullScopeAllowed", "redirectUris", "webOrigins")))
            stored_secret = self.call("GET", REALM + "/clients/" + saved[name]["id"] + "/client-secret", 200, token=token)
            require(stored_secret.get("type") == "secret" and stored_secret.get("value") == secret)
        self.stage = "roles"
        self.offline_access(saved["armoney-android"], token)
        management = self.client("realm-management", token)
        role = self.call("GET", REALM + "/clients/" + management["id"] + "/roles/manage-realm", 200, token=token)
        canonical_id(role.get("id"))
        require(role.get("name") == "manage-realm" and role.get("clientRole") is True and role.get("containerId") == management["id"])
        admin = saved["armoney-production-acceptance-admin"]
        service = self.call("GET", REALM + "/clients/" + admin["id"] + "/service-account-user", 200, token=token)
        service_id = canonical_id(service.get("id"))
        suffix = "/clients/" + management["id"]
        self.call("POST", REALM + "/users/" + service_id + "/role-mappings" + suffix, 204, [role], token, document=False)
        self.call("POST", REALM + "/clients/" + admin["id"] + "/scope-mappings" + suffix, 204, [role], token, document=False)
        for path in (REALM + "/users/" + service_id + "/role-mappings" + suffix, REALM + "/clients/" + admin["id"] + "/scope-mappings" + suffix):
            mapped = self.call("GET", path, 200, token=token)
            require(type(mapped) is list and len(mapped) == 1 and mapped[0].get("id") == role["id"])
        self.stage = "flows"
        for alias in (BROWSER, REGISTRATION):
            self.call("POST", REALM + "/authentication/flows", 201,
                      {"alias": alias, "providerId": "basic-flow", "topLevel": True, "builtIn": False}, token, document=False)
        self.execution(BROWSER, "armoney-password-proof", token, 10)
        self.call("POST", REALM + "/authentication/flows/" + REGISTRATION + "/executions/flow", 201,
                  {"alias": FORM, "type": "form-flow", "provider": "registration-page-form", "priority": 10, "description": "Isolated genuine registration"}, token, document=False)
        parent = self.call("GET", REALM + "/authentication/flows/" + REGISTRATION + "/executions", 200, token=token)
        forms = [x for x in parent if x.get("displayName") == FORM and x.get("authenticationFlow") is True]
        require(len(forms) == 1)
        form_id = canonical_id(forms[0].get("id"))
        self.call("PUT", REALM + "/authentication/flows/" + REGISTRATION + "/executions", 204,
                  {"id": form_id, "requirement": "REQUIRED", "priority": 10}, token, document=False)
        self.execution(FORM, "armoney-registration-creation", token, 10)
        self.execution(FORM, "registration-password-action", token, 20)
        self.stage = "actions"
        actions = self.call("GET", REALM + "/authentication/required-actions", 200, token=token)
        update = [x for x in actions if x.get("providerId") == "UPDATE_PASSWORD"]
        require(len(update) == 1 and type(update[0].get("priority")) is int and 0 <= update[0]["priority"] <= 10000)
        require(type(actions) is list and len(actions) <= 100 and all(type(x) is dict and type(x.get("priority")) is int and 0 <= x["priority"] <= 10000 for x in actions))
        priority = max([x["priority"] for x in actions] + [100]) + 10
        for alias, offset in ((EMAIL, 0), (PHONE, 10)):
            require(not any(x.get("alias") == alias for x in actions))
            self.call("POST", REALM + "/authentication/register-required-action", 204, {"providerId": alias, "name": alias}, token, document=False)
            value = {"alias": alias, "providerId": alias, "name": alias, "enabled": True, "defaultAction": False, "priority": priority + offset, "config": {}}
            self.call("PUT", REALM + "/authentication/required-actions/" + alias, 204, value, token, document=False)
            actual = self.call("GET", REALM + "/authentication/required-actions/" + alias, 200, token=token)
            require(all(actual.get(k) == v and type(actual.get(k)) is type(v) for k, v in value.items()))
        self.stage = "profiles"
        profiles = self.call("GET", REALM + "/client-policies/profiles", 200, token=token).get("profiles", [])
        require(type(profiles) is list and len(profiles) <= 32 and not any(x.get("name") == PROFILE for x in profiles))
        profiles.append({"name": PROFILE, "executors": [{"executor": EXECUTOR, "configuration": {}}]})
        self.call("PUT", REALM + "/client-policies/profiles", 204, {"profiles": profiles}, token, document=False)
        require(self.call("GET", REALM + "/client-policies/profiles", 200, token=token).get("profiles") == profiles)
        self.stage = "policies"
        policies = self.call("GET", REALM + "/client-policies/policies", 200, token=token).get("policies", [])
        require(type(policies) is list and len(policies) <= 32 and not any(x.get("name") == POLICY for x in policies))
        policies.append({"name": POLICY, "enabled": True, "conditions": [{"condition": "client-attributes", "configuration": {
            "attributes": json.dumps([{"key": ATTRIBUTE, "value": "true"}], separators=(",", ":"))}}], "profiles": [PROFILE]})
        self.call("PUT", REALM + "/client-policies/policies", 204, {"policies": policies}, token, document=False)
        require(self.call("GET", REALM + "/client-policies/policies", 200, token=token).get("policies") == policies)
        self.stage = "activate"
        self.call("PUT", REALM, 204, {"registrationAllowed": True, "browserFlow": BROWSER, "registrationFlow": REGISTRATION}, token, document=False)
        self.stage = "readback"
        actual = self.call("GET", REALM, 200, token=token)
        require(actual.get("realm") == "armoney" and actual.get("registrationAllowed") is True
                and actual.get("browserFlow") == BROWSER and actual.get("registrationFlow") == REGISTRATION and actual.get("loginTheme") == "armoney")
        for alias, providers in ((BROWSER, {"armoney-password-proof": 10}), (FORM, {"armoney-registration-creation": 10, "registration-password-action": 20})):
            listing = self.call("GET", REALM + "/authentication/flows/" + alias + "/executions", 200, token=token)
            require(type(listing) is list and len(listing) == len(providers))
            models = {canonical_id(x.get("id")): self.call("GET", REALM + "/authentication/executions/" + canonical_id(x.get("id")), 200, token=token) for x in listing}
            check_leaf_flow(listing, models, providers)
        registration = self.call("GET", REALM + "/authentication/flows/" + REGISTRATION + "/executions", 200, token=token)
        require(type(registration) is list and len(registration) == 3)
        forms = [x for x in registration if x.get("authenticationFlow") is True and x.get("displayName") == FORM]
        require(len(forms) == 1 and forms[0].get("id") == form_id and forms[0].get("requirement") == "REQUIRED")
        form_model = self.call("GET", REALM + "/authentication/executions/" + form_id, 200, token=token)
        require(form_model.get("id") == form_id and form_model.get("authenticator") == "registration-page-form"
                and form_model.get("authenticatorFlow") is True and form_model.get("requirement") == "REQUIRED")
        nested_id = canonical_id(form_model.get("flowId"))
        nested = self.call("GET", REALM + "/authentication/flows/" + nested_id, 200, token=token)
        require(nested.get("id") == nested_id and nested.get("alias") == FORM and nested.get("providerId") == "form-flow"
                and nested.get("topLevel") is False and nested.get("builtIn") is False)
        leaves = [x for x in registration if x.get("id") != form_id]
        leaf_models = {canonical_id(x.get("id")): self.call("GET", REALM + "/authentication/executions/" + canonical_id(x.get("id")), 200, token=token) for x in leaves}
        check_leaf_flow(leaves, leaf_models, {"armoney-registration-creation": 10, "registration-password-action": 20})
        final_actions = self.call("GET", REALM + "/authentication/required-actions", 200, token=token)
        by_alias = {x["alias"]: x for x in final_actions}
        require(by_alias[EMAIL]["defaultAction"] is False and by_alias[PHONE]["defaultAction"] is False
                and by_alias[EMAIL]["enabled"] is True and by_alias[PHONE]["enabled"] is True
                and by_alias["UPDATE_PASSWORD"]["priority"] < by_alias[EMAIL]["priority"] < by_alias[PHONE]["priority"])
        discovery = self.call("GET", BASE + "/realms/armoney/.well-known/openid-configuration", 200)
        require(discovery.get("issuer") == c["expected_issuer"])
        return {"phase": "production-provider-provisioning", "status": "configured", "runtime_acceptance": False}


def check_public(actual, expected):
    client_check(type(actual) is dict, "shape")
    for key in CLIENT_FIELDS:
        client_check(type(actual.get(key)) is type(expected[key]) and (set(actual[key]) == set(expected[key]) if key == "redirectUris" else actual[key] == expected[key]), key)
    client_check(type(actual.get("attributes")) is dict and all(actual["attributes"].get(k) == v for k, v in expected["attributes"].items()), "attributes")
    client_check(set(actual.get("defaultClientScopes", [])) == set(expected["defaultClientScopes"]) and actual.get("optionalClientScopes") == expected["optionalClientScopes"], "scopes")
    mappers = actual.get("protocolMappers", [])
    client_check(type(mappers) is list and len(mappers) == len(expected.get("protocolMappers", [])), "mapper-count")
    if expected.get("clientId") == ACCOUNT:
        config = mappers[0].get("config")
        client_check(type(config) is dict, "inspection-config-shape")
        if set(config) != set(expected["protocolMappers"][0]["config"]):
            wanted = expected["protocolMappers"][0]["config"]
            unexpected = set(config) - INSPECTION_KEYS - {"included.client.audience"} - STANDARD_DIAGNOSTIC_KEYS
            failure = Refused()
            failure.diagnostic = {"predicate": "inspection-config-keyset",
                "expected_keys": {k: {"present": k in config, "matches": config.get(k) == v} for k, v in wanted.items()},
                "standard_keys": {k: {"present": k in config, "string_true": k in config and config[k] == "true",
                    "string_false": k in config and config[k] == "false", "empty": k in config and config[k] == "",
                    "other": k in config and config[k] not in ("true", "false", "")} for k in STANDARD_DIAGNOSTIC_KEYS},
                "client_audience_present": "included.client.audience" in config,
                "client_audience_empty": "included.client.audience" in config and config["included.client.audience"] == "",
                "unexpected_key_count": min(len(unexpected), 16),
                "unexpected_all_empty": all(config[k] == "" for k in unexpected)}
            raise failure from None
        for key, value in expected["protocolMappers"][0]["config"].items():
            client_check(config.get(key) == value, "inspection-config-value", key, key in config, config.get(key) == value)
    for required_mapper in expected.get("protocolMappers", []):
        selected = [x for x in mappers if x.get("name") == required_mapper["name"]]
        client_check(len(selected) == 1 and all(selected[0].get(k) == v for k, v in required_mapper.items() if k != "config")
                and type(selected[0].get("config")) is dict and all(selected[0]["config"].get(k) == v for k, v in required_mapper["config"].items()), "mapper-contract")
    client_check(not actual.get("secret"), "secret-absent")


def check_leaf_flow(listing, models, providers):
    require(type(listing) is list and len(listing) == len(providers) and type(models) is dict and len(models) == len(providers))
    seen_ids, seen_providers = set(), set()
    for row in listing:
        require(type(row) is dict)
        execution_id = canonical_id(row.get("id"))
        provider = row.get("providerId")
        require(execution_id not in seen_ids and provider in providers and provider not in seen_providers
                and row.get("requirement") == "REQUIRED" and (row.get("authenticationFlow") is None or row.get("authenticationFlow") is False))
        seen_ids.add(execution_id)
        seen_providers.add(provider)
        detail = models.get(execution_id)
        require(type(detail) is dict and detail.get("id") == execution_id and detail.get("authenticator") == provider
                and detail.get("authenticatorFlow") is False and detail.get("requirement") == "REQUIRED"
                and type(detail.get("priority")) is int and detail["priority"] == providers[provider])
    require(seen_providers == set(providers) and seen_ids == set(models))


def main():
    parser = argparse.ArgumentParser(description="Provision one fresh isolated provider fixture; private input/results only.")
    parser.add_argument("--input", required=True)
    parser.add_argument("--result", required=True)
    args = parser.parse_args()
    provisioner = Provisioner(Transport())
    result = {"phase": "production-provider-provisioning", "status": "failed", "checkpoint": "config"}
    try:
        require(not Path(args.result).exists())
        if args.input == "-":
            raw = sys.stdin.buffer.read(LIMIT + 1)
        else:
            with Path(args.input).open("rb") as incoming:
                raw = incoming.read(LIMIT + 1)
        require(len(raw) <= LIMIT)
        c = configuration(raw)
        result = provisioner.run(c)
    except Exception as failure:
        result["checkpoint"] = provisioner.stage if provisioner.stage in STAGES else "config"
        diagnostic = safe_diagnostic(failure)
        if diagnostic is not None:
            result["diagnostic"] = diagnostic
    try:
        with Path(args.result).open("x", encoding="utf-8") as out:
            json.dump(result, out, separators=(",", ":"))
            out.write("\n")
    except Exception:
        print("Provisioning unavailable", file=sys.stderr)
        return 1
    if result["status"] != "configured":
        print("Provisioning unavailable", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
