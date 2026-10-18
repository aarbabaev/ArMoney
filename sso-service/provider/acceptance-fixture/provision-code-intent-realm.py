#!/usr/bin/env python3
"""One-shot disposable phase-one provisioning. No deployment or activation.

Pass --input PRIVATE_JSON (or - for stdin) and --result NEW_PRIVATE_JSON.
The caller must own isolated DNS/network/storage and private file ACLs. Explicit
input gates are assertions, not proof of isolation or the running nodes' flags.
Only master/admin-cli obtains an admin token; the synthetic user never does.
Failures after import leave the owned realm in place: no retry, rollback/delete,
or modification of an existing realm is attempted. Root owns disposal/restart.
Private failure results add only the last fixed checkpoint, optional node A/B,
optional HTTP status 100..599, and an optional fixed CLIENT predicate.
Success remains exactly phase/status/subject.
"""
import argparse
import http.client
import json
import os
from pathlib import Path
import re
import socket
import sys
import threading
import time
import urllib.parse
import uuid

NODES = ("sso-a", "sso-b")
BASE = "/sso"
REALM = BASE + "/admin/realms/armoney"
ACTION = "armoney-code-intent-fixture-continuation"
FLOW = "armoney-code-intent-password-only"
LIMIT = 131072
TIMEOUT = 4.0
PHONE = re.compile(r"\+9715[024568][0-9]{7}", re.ASCII)
CHECKPOINTS = frozenset({"config", "token", "preexisting", "import", "realm", "discovery", "client",
                         "flow", "action", "off-control", "create-user", "query-user", "user-readback",
                         "off-control-after"})

CLIENT_PREDICATES = frozenset({"client-list", "client-object", "client-id", "enabled", "protocol",
                               "public-client", "standard-flow", "implicit-flow", "direct-access-grants",
                               "service-accounts", "full-scope", "redirect-uris", "web-origins", "secret-absent",
                               "attributes-object", "pkce-s256", "device-grant", "ciba-grant",
                               "logout-redirects", "flow-overrides"})


class Progress:
    """Per-invocation allowlist only; never retain request/response or exceptions."""
    def __init__(self):
        self.mark("config")

    def mark(self, checkpoint, node=None, http_status=None, predicate=None):
        require(type(checkpoint) is str and checkpoint in CHECKPOINTS)
        require(node is None or type(node) is str and node in {"A", "B"})
        require(http_status is None or type(http_status) is int and 100 <= http_status <= 599)
        require(predicate is None or checkpoint == "client" and type(predicate) is str
                and predicate in CLIENT_PREDICATES)
        value = {"checkpoint": checkpoint}
        if node is not None:
            value["node"] = node
        if http_status is not None:
            value["http_status"] = http_status
        if predicate is not None:
            value["predicate"] = predicate
        self._last = value

    def snapshot(self):
        return dict(self._last)


class Refused(Exception):
    """Deliberately carries no response, URL, identity or credential."""


def require(condition):
    if not condition:
        raise Refused() from None


def strict_json(raw):
    def pairs(items):
        result = {}
        for key, value in items:
            require(key not in result)
            result[key] = value
        return result
    try:
        require(isinstance(raw, bytes) and len(raw) <= LIMIT and not raw.startswith(b"\xef\xbb\xbf"))
        value = json.loads(raw.decode("utf-8", errors="strict"), object_pairs_hook=pairs,
                           parse_constant=lambda _: require(False))
        # Reject escaped unpaired surrogates as well as malformed UTF-8.
        json.dumps(value, ensure_ascii=False).encode("utf-8", errors="strict")
        return value
    except Exception:
        raise Refused() from None


def canonical_uuid(value):
    try:
        require(isinstance(value, str) and str(uuid.UUID(value)) == value and uuid.UUID(value).int != 0)
        return value
    except Exception:
        raise Refused() from None


def text(value, maximum=256):
    require(isinstance(value, str) and 0 < len(value) <= maximum
            and all(32 <= ord(c) < 127 for c in value))
    return value


def configuration(raw):
    config = strict_json(raw)
    require(isinstance(config, dict) and set(config) == {
        "CI", "CODE_SESSION_ACCEPTANCE_ISOLATED", "ARMONEY_CODE_INTENT_FIXTURE_ENABLED",
        "expected_issuer", "admin_username", "admin_password", "synthetic_user"})
    require(config["CI"] == "true" and config["CODE_SESSION_ACCEPTANCE_ISOLATED"] == "true"
            and config["ARMONEY_CODE_INTENT_FIXTURE_ENABLED"] == "false")
    issuer = text(config["expected_issuer"], 512)
    parsed = urllib.parse.urlsplit(issuer)
    require(parsed.scheme == "https" and parsed.path == "/sso/realms/armoney"
            and not parsed.query and not parsed.fragment and not parsed.username
            and not parsed.password and parsed.hostname is not None
            and re.fullmatch(r"[a-z0-9]+(?:[.-][a-z0-9]+)*", parsed.hostname) is not None)
    try:
        require(parsed.port is None or 1 <= parsed.port <= 65535)
        require(issuer == "https://" + parsed.hostname + (":" + str(parsed.port) if parsed.port else "")
                + "/sso/realms/armoney")
    except ValueError:
        raise Refused() from None
    text(config["admin_username"], 128)
    text(config["admin_password"])
    user = config["synthetic_user"]
    require(isinstance(user, dict) and set(user) == {"username", "email", "firstName", "lastName", "password"})
    require(PHONE.fullmatch(text(user["username"])) is not None)
    require(re.fullmatch(r"[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]+@(?:[A-Za-z0-9-]+\.)+(?:test|invalid)",
                         text(user["email"], 254)) is not None)
    for name in ("firstName", "lastName"):
        text(user[name])
    require(15 <= len(text(user["password"])) <= 256)
    return config


def request(node, method, path, token=None, payload=None, form=None):
    """Fixed HTTP origins; no proxy, redirect or caller-selected destinations.

    A four-second socket timeout and owned shutdown timer bound connected I/O.
    OS DNS resolution is not proven physically cancellable by this helper.
    """
    require(node in NODES and method in {"GET", "POST"} and path.startswith(BASE + "/")
            and not any(c in path for c in "\r\n#") and not (payload is not None and form is not None))
    headers = {"Accept": "application/json"}
    data = None
    if token is not None:
        headers["Authorization"] = "Bearer " + text(token, 16384)
    if payload is not None:
        data = json.dumps(payload, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
        headers["Content-Type"] = "application/json"
    if form is not None:
        data = urllib.parse.urlencode(form).encode("ascii")
        headers["Content-Type"] = "application/x-www-form-urlencoded"
    require(data is None or len(data) <= LIMIT)
    end = time.monotonic() + TIMEOUT
    class DeadlineConnection(http.client.HTTPConnection):
        def connect(self):
            super().connect()
            # DNS may finish after the timer: never send a late mutation then.
            require(time.monotonic() < end)
    conn = DeadlineConnection(node, 8080, timeout=TIMEOUT)
    connected = []
    def abort():
        sock = connected[0] if connected else conn.sock
        if sock is not None:
            try:
                sock.shutdown(socket.SHUT_RDWR)
            except OSError:
                pass
        conn.close()
    timer = threading.Timer(TIMEOUT, abort)
    timer.daemon = True
    timer.start()
    try:
        conn.request(method, path, body=data, headers=headers)
        if conn.sock is not None:
            connected.append(conn.sock)
        response = conn.getresponse()
        require(response.status < 300 or response.status >= 400)  # No redirect following.
        body = response.read(LIMIT + 1)
        require(len(body) <= LIMIT and time.monotonic() < end)
        return response.status, body
    except Exception:
        raise Refused() from None
    finally:
        timer.cancel()
        abort()
        timer.join()


def check_client(client, rejected=None):
    # Only fixed local labels reach the callback; observed values never do.
    def check(condition, predicate):
        if not condition:
            if rejected is not None:
                rejected(predicate)
            require(False)
    check(isinstance(client, dict), "client-object")
    check(client.get("clientId") == "armoney-android", "client-id")
    check(client.get("enabled") is True, "enabled")
    check(client.get("protocol") == "openid-connect", "protocol")
    check(client.get("publicClient") is True, "public-client")
    check(client.get("standardFlowEnabled") is True, "standard-flow")
    check(client.get("implicitFlowEnabled") is False, "implicit-flow")
    check(client.get("directAccessGrantsEnabled") is False, "direct-access-grants")
    check(client.get("serviceAccountsEnabled") is False, "service-accounts")
    check(client.get("fullScopeAllowed") is False, "full-scope")
    check(client.get("redirectUris") == ["https://fixture-client/callback"], "redirect-uris")
    check(client.get("webOrigins") == [], "web-origins")
    check(not client.get("secret"), "secret-absent")
    attrs = client.get("attributes", {})
    check(isinstance(attrs, dict), "attributes-object")
    check(attrs.get("pkce.code.challenge.method") == "S256", "pkce-s256")
    check(attrs.get("oauth2.device.authorization.grant.enabled") == "false", "device-grant")
    check(attrs.get("oidc.ciba.grant.enabled") == "false", "ciba-grant")
    check(not attrs.get("post.logout.redirect.uris"), "logout-redirects")
    check(not client.get("authenticationFlowBindingOverrides"), "flow-overrides")


def realm_source():
    raw = Path(__file__).with_name("code-intent-realm.json").read_bytes()
    realm = strict_json(raw)
    require(realm.get("realm") == "armoney" and realm.get("enabled") is True
            and realm.get("browserFlow") == FLOW and realm.get("loginTheme") == "armoney-code-intent-fixture"
            and realm.get("registrationAllowed") is False and "users" not in realm
            and len(realm.get("clients", [])) == 1)
    check_client(realm["clients"][0])
    require(realm.get("requiredActions") == [{"alias": ACTION, "name": "Disposable code-intent continuation",
            "providerId": ACTION, "enabled": True, "defaultAction": True, "priority": 10, "config": {}}])
    return realm


def provision(config, transport=request, progress=None):
    """Returns only phase/status/actual persisted UUID; never a token or profile."""
    mark = Progress().mark if progress is None else progress
    mark("config")
    config = configuration(json.dumps(config).encode("utf-8"))
    realm = realm_source()
    def call(checkpoint, node, method, path, expected, token=None, payload=None, form=None):
        node_label = {"sso-a": "A", "sso-b": "B"}[node]
        mark(checkpoint, node_label)
        status, raw = transport(node, method, path, token=token, payload=payload, form=form)
        mark(checkpoint, node_label, status if type(status) is int and 100 <= status <= 599 else None)
        require(status == expected and isinstance(raw, bytes) and len(raw) <= LIMIT)
        return raw
    auth = strict_json(call("token", "sso-a", "POST", BASE + "/realms/master/protocol/openid-connect/token", 200,
                           form={"client_id": "admin-cli", "grant_type": "password",
                                 "username": config["admin_username"], "password": config["admin_password"]}))
    require(isinstance(auth, dict) and auth.get("token_type", "").lower() == "bearer")
    token = text(auth.get("access_token"), 16384)
    # Check both nodes before the first mutating call. Never patch an existing realm.
    for node in NODES:
        call("preexisting", node, "GET", REALM, 404, token)
    call("import", "sso-a", "POST", BASE + "/admin/realms", 201, token, realm)
    for node in NODES:
        actual = strict_json(call("realm", node, "GET", REALM, 200, token))
        require(actual.get("realm") == "armoney" and actual.get("browserFlow") == FLOW
                and actual.get("loginTheme") == "armoney-code-intent-fixture")
        discovery = strict_json(call("discovery", node, "GET", BASE + "/realms/armoney/.well-known/openid-configuration", 200))
        require(discovery.get("issuer") == config["expected_issuer"])
        clients = strict_json(call("client", node, "GET", REALM + "/clients?clientId=armoney-android", 200, token))
        def rejected_client(predicate):
            mark("client", {"sso-a": "A", "sso-b": "B"}[node], 200, predicate)
        if not (isinstance(clients, list) and len(clients) == 1):
            rejected_client("client-list")
            require(False)
        check_client(clients[0], rejected_client)
        executions = strict_json(call("flow", node, "GET", REALM + "/authentication/flows/" + FLOW + "/executions", 200, token))
        require(isinstance(executions, list) and len(executions) == 1
                and executions[0].get("providerId") == "armoney-password-proof"
                and executions[0].get("requirement") == "REQUIRED"
                and executions[0].get("authenticationFlow") is False)
        actions = strict_json(call("action", node, "GET", REALM + "/authentication/required-actions", 200, token))
        require(isinstance(actions, list))
        selected = [a for a in actions if a.get("enabled") is True and a.get("defaultAction") is True]
        require(len(selected) == 1 and selected[0].get("alias") == ACTION and selected[0].get("providerId") == ACTION)
        call("off-control", node, "POST", BASE + "/realms/armoney/armoney-code-intent-fixture/state", 503, payload={})
    supplied = config["synthetic_user"]
    user = {k: supplied[k] for k in ("username", "email", "firstName", "lastName")}
    user.update({"enabled": True, "emailVerified": False, "requiredActions": [ACTION],
                 "credentials": [{"type": "password", "value": supplied["password"], "temporary": False}]})
    call("create-user", "sso-a", "POST", REALM + "/users", 201, token, user)
    query = urllib.parse.urlencode({"username": supplied["username"], "exact": "true"})
    users = strict_json(call("query-user", "sso-a", "GET", REALM + "/users?" + query, 200, token))
    require(isinstance(users, list) and len(users) == 1 and isinstance(users[0], dict))
    subject = canonical_uuid(users[0].get("id"))
    for node in NODES:
        saved = strict_json(call("user-readback", node, "GET", REALM + "/users/" + subject, 200, token))
        require(saved.get("id") == subject and saved.get("enabled") is True
                and saved.get("emailVerified") is False and saved.get("requiredActions") == [ACTION]
                and all(saved.get(k) == user[k] for k in ("username", "email", "firstName", "lastName")))
        call("off-control-after", node, "POST", BASE + "/realms/armoney/armoney-code-intent-fixture/state", 503, payload={})
    return {"phase": "PHASE1", "status": "BOTH_NODES_STOP_AND_ENABLED_RESTART_REQUIRED", "subject": subject}


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", required=True)
    parser.add_argument("--result", required=True)
    args = parser.parse_args(argv)
    output = None
    progress = Progress()
    try:
        # Exclusive creation prevents replacing prior evidence or following an existing symlink.
        fd = os.open(args.result, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        output = os.fdopen(fd, "w", encoding="utf-8")
        if args.input == "-":
            raw = sys.stdin.buffer.read(LIMIT + 1)
        else:
            with open(args.input, "rb") as source:
                raw = source.read(LIMIT + 1)
        result = provision(configuration(raw), progress=progress.mark)
        json.dump(result, output, separators=(",", ":"))
        output.write("\n")
        output.flush()
        os.fsync(output.fileno())
        return 0
    except Exception:
        if output is not None:
            try:
                output.seek(0)
                output.truncate()
                json.dump({"phase": "PHASE1", "status": "FAILED_NO_AUTOMATIC_RETRY",
                           "diagnostic": progress.snapshot()}, output)
                output.write("\n")
            except Exception:
                pass  # Storage failure must not emit a sensitive exception chain.
        print("Provisioning unavailable.", file=sys.stderr)
        return 1
    finally:
        if output is not None:
            try:
                output.close()
            except Exception:
                pass


if __name__ == "__main__":
    sys.exit(main())
