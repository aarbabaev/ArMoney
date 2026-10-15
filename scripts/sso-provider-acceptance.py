"""Disposable two-node provider observer acceptance; never grants enrollment success.

Run mode bridge on the private auth-service alias; mode acceptance in the browser
runner. The orchestrator owns image/cluster provisioning and bounded cleanup.
All URLs are explicit: no persistent-stack defaults and no Docker subprocesses.
"""
import argparse
import base64
import datetime
import hashlib
import hmac
import http.server
import json
import os
import secrets
import ssl
import sys
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid

FLOW = {"issuer", "subject", "client_id", "provider_root_session_id",
        "provider_tab_id", "authentication_session_id"}
OBSERVATION = FLOW | {"username", "email", "phone_number", "enabled",
                      "authentication_session_active", "authentication_session_expires_at"}
INSPECT = {"root_present", "tab_present", "root_timestamp", "action",
           "authenticated_user_present", "server_time"}
TARGETS = {
    "OBSERVER_NODE_A_URL": "http://sso-a:8080",
    "OBSERVER_NODE_B_URL": "http://sso-b:8080",
    "OBSERVER_ADMIN_URL": "http://sso-a:8080",
    "OBSERVER_BRIDGE_URL": "http://auth-service:8090",
    "OBSERVER_ISSUER": "http://sso-a:8080/realms/armoney",
}
PHASES = frozenset({"START", "KEY_VALIDATION", "READINESS", "ADMIN_LOGIN", "REALM_SETUP",
                    "CLIENT_SETUP", "ACTION_SETUP", "ACTION_LIST", "ACTION_AVAILABLE", "ACTION_REGISTER",
                    "ACTION_CONFIG", "USER_SETUP", "BROWSER_RENDER", "SEND_RPC",
                    "CROSS_NODE", "READ_ONLY", "EXPIRY", "TAB_REMOVAL", "ROOT_REMOVAL",
                    "DISABLE_DELETE", "CLEANUP", "COMPLETE"})
BRIDGE_ERRORS = frozenset({"BRIDGE_BODY", "BRIDGE_OBSERVER", "BRIDGE_DTO", "BRIDGE_CONTACTS", "BRIDGE_UNKNOWN"})
current_phase = "START"
failure_phase = None


def phase(label):
    global current_phase
    if label not in PHASES:
        raise ValueError("invalid diagnostic phase")
    current_phase = label
    print("PHASE: OBSERVER69 " + label, flush=True)


def remember_failure():
    global failure_phase
    if failure_phase is None:
        failure_phase = current_phase


def bridge_error(label):
    # Never stringify a response, exception, tuple or unvalidated capture value.
    label = label if isinstance(label, str) and label in BRIDGE_ERRORS else "BRIDGE_UNKNOWN"
    print("BRIDGE_ERROR: OBSERVER69 " + label, flush=True)


def require(condition, label):
    if not condition:
        raise AssertionError(label)


def env(name):
    value = os.environ.get(name, "")
    require(bool(value), "missing environment: " + name)
    return value


def validate_keys():
    names = ("AUTH_TO_SSO_STATUS_KEY", "SSO_VERIFICATION_BRIDGE_KEY",
             "OBSERVER_FIXTURE_CONTROL_KEY", "ARMONEY_PROVIDER_BINDING_KEY")
    keys = [env(name) for name in names]
    require(all(32 <= len(key) <= 512 and all(33 <= ord(c) <= 126 for c in key)
                for key in keys), "fixture keys must be printable bounded values")
    require(len(set(keys)) == len(keys), "all four fixture keys must be independent")


def validate_targets():
    # These names exist only inside the runner's freshly owned Compose network.
    # Do not generalize this harness into an arbitrary Keycloak admin client.
    require(all(os.environ.get(name) == expected for name, expected in TARGETS.items()),
            "only fixed disposable fixture targets are permitted")
    require(os.environ.get("OBSERVER_BRIDGE_BIND") == "0.0.0.0"
            and os.environ.get("OBSERVER_BRIDGE_PORT") == "8090",
            "only fixed private fixture listener is permitted")


def url(name):
    value = env(name).rstrip("/")
    parsed = urllib.parse.urlsplit(value)
    require(parsed.scheme in ("http", "https") and parsed.hostname
            and not parsed.username and not parsed.password
            and not parsed.query and not parsed.fragment, "invalid explicit URL: " + name)
    return value


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *args, **kwargs):
        return None


def request(method, target, data=None, headers=None, form=False):
    payload = None if data is None else (urllib.parse.urlencode(data) if form else json.dumps(data, separators=(",", ":"))).encode()
    context = ssl.create_default_context(cafile=os.environ.get("SSO_CA_FILE"))
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect(),
                                        urllib.request.HTTPSHandler(context=context))
    req = urllib.request.Request(target, data=payload, method=method,
                                 headers={"Content-Type": "application/x-www-form-urlencoded" if form else "application/json", **(headers or {})})
    try:
        response = opener.open(req, timeout=8)
    except urllib.error.HTTPError as error:
        response = error
    with response:
        body = response.read(16385)
        require(len(body) <= 16384, "bounded HTTP response")
        return response.status, json.loads(body) if body else None, response.headers


def expect(status, response, label):
    require(response[0] == status, label + " status")
    return response[1]


def wait_ready(node):
    deadline = time.monotonic() + 120
    while time.monotonic() < deadline:
        try:
            if request("GET", node + "/realms/master/.well-known/openid-configuration")[0] == 200:
                return
        except (OSError, ValueError):
            pass
        time.sleep(1)
    raise AssertionError("bounded node readiness")


def provision_realm(admin, authorization):
    target = admin + "/admin/realms/armoney"
    expect(404, request("GET", target, headers=authorization), "fixture requires absent realm")
    expect(201, request("POST", admin + "/admin/realms", {
        "realm": "armoney", "enabled": True, "registrationAllowed": False, "loginTheme": "armoney",
        "sslRequired": "none", "accessCodeLifespanUserAction": 30,
        "accessCodeLifespanLogin": 300, "accessCodeLifespan": 60,
        "ssoSessionIdleTimeout": 300, "ssoSessionMaxLifespan": 600,
        "loginWithEmailAllowed": False, "resetPasswordAllowed": False,
        "rememberMe": False}, authorization), "create owned disposable realm")


def provision_client_and_action(admin, authorization):
    phase("CLIENT_SETUP")
    target = admin + "/admin/realms/armoney"
    expect(201, request("POST", target + "/clients", {
        "clientId": "armoney-ios", "enabled": True, "protocol": "openid-connect",
        "publicClient": True, "standardFlowEnabled": True,
        "directAccessGrantsEnabled": False, "implicitFlowEnabled": False,
        "serviceAccountsEnabled": False,
        "redirectUris": ["http://127.0.0.1:18766/observer-callback"],
        "webOrigins": [], "attributes": {"pkce.code.challenge.method": "S256"}},
        authorization), "exact synthetic PKCE client")
    phase("ACTION_SETUP")
    actions_url = target + "/authentication/required-actions"
    phase("ACTION_LIST")
    actions = expect(200, request("GET", actions_url, headers=authorization), "registered required actions")
    alias = "armoney-verify-email-code"
    if not any(action["alias"] == alias for action in actions):
        phase("ACTION_AVAILABLE")
        available = expect(200, request("GET", target + "/authentication/unregistered-required-actions",
                                       headers=authorization), "available required action providers")
        require(any(action["providerId"] == alias for action in available), "actual email action provider installed")
        phase("ACTION_REGISTER")
        # Pinned 26.7.4 AuthenticationManagementResource.registerRequiredAction
        # returns void (204), unlike client creation (201). Available metadata
        # uses providerId/name; it is not the generic provider metadata id DTO.
        expect(204, request("POST", target + "/authentication/register-required-action",
                            {"providerId": alias, "name": "Disposable email verification"}, authorization),
               "register actual email action")
        phase("ACTION_LIST")
        actions = expect(200, request("GET", actions_url, headers=authorization), "registered email action")
    require(any(action["alias"] == alias for action in actions), "email action is registered")
    phase("ACTION_CONFIG")
    for action in actions:
        action["enabled"] = action["alias"] == alias
        action["defaultAction"] = False
        expect(204, request("PUT", actions_url + "/" + urllib.parse.quote(action["alias"], safe=""),
                            action, authorization), "enable only email action")


def observer(node, flow, key=None):
    response = request("POST", node + "/realms/armoney/armoney-status/enrollment", flow,
                   {"X-ArMoney-Auth-Status-Key": key or env("AUTH_TO_SSO_STATUS_KEY")})
    require(response[2].get("Cache-Control") == "no-store", "observer responses cannot be cached")
    if response[0] != 200:
        require(response[1] is None, "observer errors have no contact or binding body")
    return response


def controls(node, operation, flow):
    body = {key: flow[key] for key in
            ("client_id", "provider_root_session_id", "provider_tab_id")}
    response = request("POST", node + "/realms/armoney/armoney-observer-fixture/" + operation,
                       body, {"X-ArMoney-Observer-Fixture-Key": env("OBSERVER_FIXTURE_CONTROL_KEY")})
    value = expect(200 if operation == "inspect" else 204, response, "committed fixture " + operation)
    if operation == "inspect":
        require(set(value) == INSPECT and type(value["server_time"]) is int
                and all(type(value[k]) is bool for k in
                        ("root_present", "tab_present", "authenticated_user_present")), "exact inspect DTO")
    return value


def serve_bridge():
    """Capture only genuine provider requests; observe B, then ALWAYS deny provider."""
    state = {"count": 0, "flow": None, "observation": None, "error": False, "error_code": None}
    lock = threading.Lock()
    control = env("OBSERVER_FIXTURE_CONTROL_KEY")
    bridge_key = env("SSO_VERIFICATION_BRIDGE_KEY")
    require(control != bridge_key and control != env("AUTH_TO_SSO_STATUS_KEY"), "independent fixture key")

    class Handler(http.server.BaseHTTPRequestHandler):
        def log_message(self, *args):
            pass  # No URLs, tuples, contacts or credentials in diagnostics.

        def reply(self, status, body=None):
            raw = b"" if body is None else json.dumps(body).encode()
            self.send_response(status)
            self.send_header("Cache-Control", "no-store")
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(raw)))
            self.end_headers()
            self.wfile.write(raw)

        def do_GET(self):
            if self.path != "/capture" or not hmac.compare_digest(
                    self.headers.get("X-ArMoney-Observer-Fixture-Key", ""), control):
                self.reply(404)
                return
            with lock:
                self.reply(200, dict(state))

        def do_POST(self):
            if self.path != "/internal/enrollments" or not hmac.compare_digest(
                    self.headers.get("X-ArMoney-SSO-Bridge-Key", ""), bridge_key):
                self.reply(503)
                return
            bridge_phase = "BRIDGE_BODY"
            try:
                length = int(self.headers.get("Content-Length", "0"))
                require(0 < length <= 12288, "bounded bridge body")
                body = json.loads(self.rfile.read(length))
                require(set(body) == FLOW | {"command_id", "phone_number", "email"}, "exact enrollment request")
                flow = {key: body[key] for key in FLOW}
                bridge_phase = "BRIDGE_OBSERVER"
                observed = expect(200, observer(url("OBSERVER_NODE_B_URL"), flow), "bridge observes node B")
                bridge_phase = "BRIDGE_DTO"
                require(set(observed) == OBSERVATION, "exact twelve-field observation")
                require(all(observed[k] == flow[k] for k in FLOW), "exact observed binding")
                require(observed["enabled"] is True and observed["authentication_session_active"] is True,
                        "literal current flags")
                bridge_phase = "BRIDGE_CONTACTS"
                require(observed["username"] == body["phone_number"] == observed["phone_number"]
                        and observed["email"] == body["email"], "current canonical contacts")
                with lock:
                    state.update(count=state["count"] + 1, flow=flow, observation=observed)
            except Exception:
                with lock:
                    state["error"] = True
                    state["error_code"] = bridge_phase
            self.reply(503)  # Never fabricated receipts, ACTIVE, tokens or banking success.

    class PrivateServer(http.server.ThreadingHTTPServer):
        def handle_error(self, request, client_address):
            # BaseServer normally prints a traceback (including private values).
            with lock:
                state["error"] = True
                state["error_code"] = "BRIDGE_UNKNOWN"
            bridge_error("BRIDGE_UNKNOWN")

    server = PrivateServer((env("OBSERVER_BRIDGE_BIND"), int(env("OBSERVER_BRIDGE_PORT"))), Handler)
    server.daemon_threads = True
    server.serve_forever(poll_interval=0.2)


def acceptance():
    phase("READINESS")
    from playwright.sync_api import sync_playwright
    a, b = url("OBSERVER_NODE_A_URL"), url("OBSERVER_NODE_B_URL")
    require(urllib.parse.urlsplit(a).netloc != urllib.parse.urlsplit(b).netloc, "distinct direct node addresses")
    issuer, bridge = url("OBSERVER_ISSUER"), url("OBSERVER_BRIDGE_URL")
    admin = url("OBSERVER_ADMIN_URL")
    wait_ready(a)
    wait_ready(b)
    capture_headers = {"X-ArMoney-Observer-Fixture-Key": env("OBSERVER_FIXTURE_CONTROL_KEY")}

    def capture():
        return expect(200, request("GET", bridge + "/capture", headers=capture_headers), "private capture")

    # Only the explicitly isolated fixture's bootstrap admin is used.
    phase("ADMIN_LOGIN")
    token = expect(200, request("POST", admin + "/realms/master/protocol/openid-connect/token",
                   {"client_id": "admin-cli", "grant_type": "password",
                    "username": os.environ.get("OBSERVER_ADMIN_USERNAME", "admin"),
                    "password": env("SSO_ADMIN_PASSWORD")}, form=True), "fixture admin authentication")
    authorization = {"Authorization": "Bearer " + token["access_token"]}
    phase("REALM_SETUP")
    provision_realm(admin, authorization)
    users = admin + "/admin/realms/armoney/users"
    phone = "+97150" + str(secrets.randbelow(10**7)).zfill(7)
    password = secrets.token_urlsafe(24)
    email = "observer-" + uuid.uuid4().hex + "@example.invalid"
    identifier = None
    try:
        provision_client_and_action(admin, authorization)
        for node in (a, b):
            discovery = expect(200, request("GET", node + "/realms/armoney/.well-known/openid-configuration"),
                               "new realm visible on direct node")
            require(discovery["issuer"] == issuer, "both nodes expose common fixed issuer")
        phase("USER_SETUP")
        expect(201, request("POST", users, {"username": phone, "email": email, "enabled": True,
                   "emailVerified": False, "requiredActions": ["armoney-verify-email-code"],
                   "credentials": [{"type": "password", "value": password, "temporary": False}]}, authorization), "synthetic user")
        listed = expect(200, request("GET", users + "?username=" + urllib.parse.quote(phone), headers=authorization), "synthetic user lookup")
        require(len(listed) == 1, "unique synthetic user")
        identifier = listed[0]["id"]

        phase("BROWSER_RENDER")
        with sync_playwright() as p:
            browser = p.chromium.launch()
            context = browser.new_context()  # No ignore_https_errors or certificate bypass.
            callback_count = []
            context.route("http://127.0.0.1:18766/observer-callback**",
                          lambda route: (callback_count.append(True), route.abort()))

            def login():
                phase("BROWSER_RENDER")
                before = capture()["count"]
                page = context.new_page()
                verifier = secrets.token_urlsafe(48)
                challenge = base64.urlsafe_b64encode(hashlib.sha256(verifier.encode()).digest()).rstrip(b"=").decode()
                query = urllib.parse.urlencode({"client_id": "armoney-ios", "response_type": "code",
                          "redirect_uri": "http://127.0.0.1:18766/observer-callback", "scope": "openid",
                          "state": secrets.token_urlsafe(24), "nonce": secrets.token_urlsafe(24),
                          "code_challenge": challenge, "code_challenge_method": "S256", "prompt": "login"})
                page.goto(a + "/realms/armoney/protocol/openid-connect/auth?" + query)
                page.locator("#username").fill(phone)
                page.locator("#password").fill(password)
                page.locator("#kc-login").click()
                page.locator('button[name="operation"][value="send"]').wait_for()
                require(capture()["count"] == before and not callback_count, "initial render zero RPC/callback")
                phase("SEND_RPC")
                page.locator('button[name="operation"][value="send"]').click()
                deadline = time.monotonic() + 12
                while time.monotonic() < deadline:
                    item = capture()
                    if item["error"]:
                        bridge_error(item.get("error_code"))
                    require(not item["error"], "bridge observation failed")
                    if item["count"] == before + 1:
                        require(not callback_count, "denied bridge cannot complete login")
                        return page, item["flow"], item["observation"]
                    time.sleep(0.1)
                raise AssertionError("bounded genuine bridge request missing")

            page, flow, first = login()
            phase("CROSS_NODE")
            require(flow["issuer"] == issuer and flow["subject"] == identifier, "issuer and synthetic subject")
            left, right = controls(a, "inspect", flow), controls(b, "inspect", flow)
            require(left["root_present"] and left["tab_present"] and right["root_present"]
                    and right["tab_present"] and left["root_timestamp"] == right["root_timestamp"]
                    and left["action"] == right["action"] == "REQUIRED_ACTIONS"
                    and left["authenticated_user_present"] and right["authenticated_user_present"], "committed cross-node cluster proof")
            phase("READ_ONLY")
            for _ in range(3):
                current = expect(200, observer(b, flow), "read-only observation")
                require(current == first and controls(b, "inspect", flow)["root_timestamp"] == right["root_timestamp"], "observer cannot refresh expiry or root timestamp")
            for field, replacement in (("authentication_session_id", "A" * 43), ("subject", str(uuid.uuid4()))):
                changed = dict(flow, **{field: replacement})
                expect(503, observer(b, changed), "binding mismatch denial")
            expect(401, observer(b, flow, "invalid-fixture-status-key"), "private credential denial")
            phase("TAB_REMOVAL")
            controls(a, "delete-tab", flow)
            require(not controls(b, "inspect", flow)["tab_present"], "committed tab removal visible on B")
            expect(503, observer(b, flow), "removed tab denial")
            page.close()
            page, flow, first = login()
            phase("ROOT_REMOVAL")
            controls(a, "delete-root", flow)
            require(not controls(b, "inspect", flow)["root_present"], "committed root removal visible on B")
            expect(503, observer(b, flow), "removed root denial")
            page.close()
            page, flow, first = login()
            phase("EXPIRY")
            deadline = datetime.datetime.fromisoformat(first["authentication_session_expires_at"].replace("Z", "+00:00")).timestamp()
            remaining = deadline - controls(b, "inspect", flow)["server_time"]
            require(0 < remaining <= 45, "fixture USER lifespan must permit bounded real expiry")
            stop = time.monotonic() + remaining + 4
            while controls(b, "inspect", flow)["server_time"] < deadline:
                require(time.monotonic() < stop, "bounded provider clock expiry")
                time.sleep(0.2)
            expect(503, observer(b, flow), "actual USER expiry denial")
            page.close()
            page, flow, first = login()
            phase("DISABLE_DELETE")
            expect(204, request("PUT", users + "/" + identifier, {"enabled": False}, authorization), "disable synthetic user")
            expect(503, observer(b, flow), "disabled user denial")
            expect(204, request("DELETE", users + "/" + identifier, headers=authorization), "delete synthetic user")
            identifier = None
            expect(503, observer(b, flow), "deleted user denial")
            context.close()
            browser.close()
    finally:
        if sys.exc_info()[0] is not None:
            remember_failure()
        phase("CLEANUP")
        try:
            if identifier is not None:
                expect(204, request("DELETE", users + "/" + identifier, headers=authorization), "synthetic user cleanup")
        finally:
            expect(204, request("DELETE", admin + "/admin/realms/armoney", headers=authorization),
                   "owned disposable realm cleanup")


def main():
    global current_phase, failure_phase
    current_phase, failure_phase = "START", None
    try:
        class PrivateArgumentParser(argparse.ArgumentParser):
            def error(self, message):
                raise ValueError("invalid private harness arguments")
        parser = PrivateArgumentParser(description=__doc__)
        parser.add_argument("mode", choices=("bridge", "acceptance"))
        args = parser.parse_args()
        phase("KEY_VALIDATION")
        require(os.environ.get("CI") == "true" and os.environ.get("OBSERVER_ACCEPTANCE_ISOLATED") == "true",
                "explicit disposable acceptance guard")
        validate_targets()
        validate_keys()
        serve_bridge() if args.mode == "bridge" else acceptance()
        phase("COMPLETE")
        print("PASS: OBSERVER69 COMPLETE", flush=True)
    except Exception:
        remember_failure()
        raise SystemExit("FAIL: OBSERVER69 " + failure_phase) from None


if __name__ == "__main__":
    main()
