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
import http.client
import http.server
import json
import os
import re
import secrets
import socket
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
                    "ACTION_CONFIG", "USER_SETUP", "BROWSER_RENDER", "NEGATIVE_AUTH_PAGE", "NEGATIVE_FORM_SUBMIT",
                    "NEGATIVE_DENIAL_RESULT", "NEGATIVE_NO_SEND", "NEGATIVE_NO_RPC", "SEND_RPC",
                    "CROSS_NODE", "READ_ONLY", "READ_ONLY_RPC", "READ_ONLY_EXPIRY", "READ_ONLY_DTO",
                    "READ_ONLY_INSPECT", "READ_ONLY_TIMESTAMP", "READ_ONLY_HMAC_DENIAL",
                    "READ_ONLY_SUBJECT_DENIAL", "READ_ONLY_KEY_DENIAL", "EXPIRY", "TAB_REMOVAL", "ROOT_REMOVAL",
                    "TAB_DELETE_RPC", "TAB_INSPECT_RPC", "TAB_ABSENCE", "TAB_OBSERVER_DENIAL",
                    "ROOT_DELETE_RPC", "ROOT_INSPECT_RPC", "ROOT_ABSENCE", "ROOT_OBSERVER_DENIAL",
                    "DISABLE_DELETE", "CLEANUP", "COMPLETE"})
BRIDGE_ERRORS = frozenset({"BRIDGE_BODY", "BRIDGE_OBSERVER", "BRIDGE_DTO", "BRIDGE_CONTACTS", "BRIDGE_UNKNOWN"})
HTTP_CODES = frozenset({200, 400, 401, 403, 404, 405, 500, 503})
OBSERVER_BOUNDARIES = frozenset({"HTTP_" + str(code) for code in HTTP_CODES} | {
    "HTTP_OTHER", "TRANSPORT_TIMEOUT", "TRANSPORT_IO", "INVALID_JSON", "CACHE_POLICY", "ERROR_BODY", "UNKNOWN"})
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


def observer_boundary(label):
    label = label if isinstance(label, str) and label in OBSERVER_BOUNDARIES else "UNKNOWN"
    print("OBSERVER_BOUNDARY: OBSERVER69 " + label, flush=True)


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


def request(method, target, data=None, headers=None, form=False, diagnostic=None):
    payload = None if data is None else (urllib.parse.urlencode(data) if form else json.dumps(data, separators=(",", ":"))).encode()
    context = ssl.create_default_context(cafile=os.environ.get("SSO_CA_FILE"))
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect(),
                                        urllib.request.HTTPSHandler(context=context))
    req = urllib.request.Request(target, data=payload, method=method,
                                 headers={"Content-Type": "application/x-www-form-urlencoded" if form else "application/json", **(headers or {})})
    try:
        try:
            response = opener.open(req, timeout=8)
        except urllib.error.HTTPError as error:
            response = error
        with response:
            if diagnostic is not None:
                diagnostic("HTTP_" + str(response.status) if type(response.status) is int and response.status in HTTP_CODES else "HTTP_OTHER")
            body = response.read(16385)
            require(len(body) <= 16384, "bounded HTTP response")
            try:
                parsed = json.loads(body) if body else None
            except (ValueError, UnicodeError):
                if diagnostic is not None:
                    diagnostic("INVALID_JSON")
                raise
            return response.status, parsed, response.headers
    except (OSError, http.client.HTTPException) as error:
        if diagnostic is not None:
            timeout = isinstance(error, TimeoutError) or isinstance(getattr(error, "reason", None), TimeoutError)
            diagnostic("TRANSPORT_TIMEOUT" if timeout else "TRANSPORT_IO")
        raise


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
        "realm": "armoney", "enabled": True, "registrationAllowed": True, "loginTheme": "armoney",
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
    provision_registration_flow(target, authorization)


def provision_registration_flow(target, authorization):
    alias, form = "observer-genuine-registration", "observer-genuine-registration-form"
    expect(201, request("POST", target + "/authentication/flows", {"alias": alias, "providerId": "basic-flow",
        "topLevel": True, "builtIn": False}, authorization), "create genuine registration flow")
    expect(201, request("POST", target + "/authentication/flows/" + alias + "/executions/flow", {
        "alias": form, "type": "form-flow", "provider": "registration-page-form"}, authorization), "registration form flow")
    for provider in ("armoney-registration-creation", "registration-password-action"):
        expect(201, request("POST", target + "/authentication/flows/" + form + "/executions/execution", {
            "provider": provider}, authorization), "genuine registration leaf")
    rows = expect(200, request("GET", target + "/authentication/flows/" + alias + "/executions", headers=authorization), "registration readback")
    require(type(rows) is list and len(rows) == 3, "exact registration cardinality")
    providers = {"registration-page-form": 10, "armoney-registration-creation": 20, "registration-password-action": 30}
    require({x.get("providerId") for x in rows} == set(providers), "exact registration providers")
    seen = set()
    for row in rows:
        identifier = row.get("id")
        require(type(identifier) is str and str(uuid.UUID(identifier)) == identifier and identifier not in seen, "canonical execution")
        seen.add(identifier)
        row["requirement"] = "REQUIRED"
        expect(204, request("PUT", target + "/authentication/flows/" + alias + "/executions", row, authorization), "require genuine registration execution")
    # Creation MUST run before password persistence; use the same proven ordering API as the production fixture.
    leaves = expect(200, request("GET", target + "/authentication/flows/" + form + "/executions", headers=authorization), "registration leaf readback")
    require([x.get("providerId") for x in leaves] == ["armoney-registration-creation", "registration-password-action"], "creation then password")
    expect(204, request("PUT", target, {"registrationFlow": alias}, authorization), "bind genuine registration")


def canonical_user(users, authorization, phone):
    listed = expect(200, request("GET", users + "?username=" + urllib.parse.quote(phone), headers=authorization), "synthetic user lookup")
    require(type(listed) is list and len(listed) == 1 and listed[0].get("username") == phone, "unique canonical synthetic user")
    identifier = listed[0].get("id")
    require(type(identifier) is str and str(uuid.UUID(identifier)) == identifier, "canonical persisted user")
    return identifier


def unmarked_user(phone, email, password):
    # Complete stock user-profile data; this never creates a registration marker.
    return {"username": phone, "email": email, "firstName": "Synthetic", "lastName": "Observer", "enabled": True,
            "emailVerified": False, "requiredActions": ["armoney-verify-email-code"],
            "credentials": [{"type": "password", "value": password, "temporary": False}]}


def denied_callback_diagnostic(url, state, issuer):
    facts = {"reason": "SYNTAX", "target_matches": False, "duplicate_keys": False,
             "error_description_present": False, "session_state_present": False, "access_denied": False,
             "state_matches": False, "issuer_matches": False, "code_or_token_present": False, "other_key_count": 0}
    try:
        if type(url) is not str or len(url) > 8192 or not all(32 < ord(c) < 127 for c in url): return facts
        parsed = urllib.parse.urlsplit(url)
        facts["target_matches"] = parsed.scheme == "http" and parsed.netloc == "127.0.0.1:18766" and parsed.path == "/observer-callback" and not parsed.fragment
        if re.search(r"%(?![0-9A-Fa-f]{2})", parsed.query): return facts
        pairs = urllib.parse.parse_qsl(parsed.query, keep_blank_values=True, strict_parsing=True, max_num_fields=16, encoding="utf-8", errors="strict")
        values = dict(pairs)
        facts.update(duplicate_keys=len(values) != len(pairs), error_description_present="error_description" in values,
            session_state_present="session_state" in values, access_denied=values.get("error") == "access_denied",
            state_matches=type(state) is str and type(values.get("state")) is str and hmac.compare_digest(values["state"], state),
            issuer_matches="iss" not in values or values["iss"] == issuer,
            code_or_token_present=bool(set(values) & {"code", "access_token", "id_token", "refresh_token"}),
            other_key_count=min(16, len(set(values) - {"error", "state", "iss", "error_description", "session_state", "code", "access_token", "id_token", "refresh_token"})))
        facts["reason"] = ("TARGET" if not facts["target_matches"] else "DUPLICATE" if facts["duplicate_keys"] else
            "FIELDS" if set(values) not in ({"error", "state"}, {"error", "state", "iss"}) else
            "ERROR" if not facts["access_denied"] else "STATE" if not facts["state_matches"] else
            "ISSUER" if not facts["issuer_matches"] else "BOUND_DENIAL")
    except Exception:
        facts["reason"] = "SYNTAX"
    return facts


def negative_dom_diagnostic(page):
    selectors = {"login_form": "#kc-form-login", "registration_form": "#kc-register-form,#kc-form-register",
        "required_action_form": 'form[action*="/login-actions/required-action"]', "consent_form": "#kc-oauth",
        "error_page": "#kc-error-message", "input_error": "#input-error", "email_error": "#input-error-email",
        "username_error": "#input-error-username", "password_error": "#input-error-password",
        "alert_error": ".alert-error,.alert-danger", "feedback_error": ".kc-feedback-text",
        "login_submit": "#kc-login", "registration_submit": "#kc-register", "verification_send": 'button[name="operation"][value="send"]'}
    facts = {key: 0 for key in selectors}
    facts.update(origin="OTHER", path="OTHER", scheme="OTHER", browser_page="OTHER", inspection_failed=False)
    try:
        parsed = urllib.parse.urlsplit(page.url)
        facts["scheme"] = {"http": "HTTP", "https": "HTTPS", "about": "ABOUT", "chrome-error": "CHROME_ERROR", "chrome": "CHROME", "data": "DATA"}.get(parsed.scheme, "OTHER")
        facts["browser_page"] = ("ABOUT_BLANK" if page.url == "about:blank" else
            "CHROME_ERROR" if parsed.scheme == "chrome-error" else "NETWORK" if parsed.scheme in {"http", "https"} else "OTHER")
        origin = parsed.scheme + "://" + parsed.netloc
        facts["origin"] = {"http://sso-a:8080": "NODE_A", "http://sso-b:8080": "NODE_B", "http://127.0.0.1:18766": "CALLBACK"}.get(origin, "OTHER")
        facts["path"] = {"/realms/armoney/protocol/openid-connect/auth": "AUTHORIZATION",
            "/realms/armoney/login-actions/authenticate": "AUTHENTICATE", "/realms/armoney/login-actions/registration": "REGISTRATION",
            "/realms/armoney/login-actions/required-action": "REQUIRED_ACTION", "/realms/armoney/login-actions/consent": "CONSENT",
            "/observer-callback": "CALLBACK", "/": "ROOT"}.get(parsed.path, "OTHER")
        for key, selector in selectors.items():
            count = page.locator(selector).count()
            require(type(count) is int and count >= 0, "DOM count")
            facts[key] = min(count, 16)
    except Exception:
        facts["inspection_failed"] = True
    return facts


def negative_navigation_diagnostic(url):
    try:
        parsed = urllib.parse.urlsplit(url)
        origin = {"http://sso-a:8080": "NODE_A", "http://sso-b:8080": "NODE_B", "http://127.0.0.1:18766": "CALLBACK"}.get(parsed.scheme + "://" + parsed.netloc, "OTHER")
        path = ("CALLBACK" if parsed.path == "/observer-callback" else "AUTHORIZATION" if parsed.path == "/realms/armoney/protocol/openid-connect/auth"
                else "AUTHENTICATE" if parsed.path == "/realms/armoney/login-actions/authenticate" else "OTHER")
        return {"origin": origin, "path": path}
    except Exception:
        return {"origin": "OTHER", "path": "OTHER"}


def negative_network_failure(value):
    codes = {"ERR_ABORTED", "ERR_FAILED", "ERR_CONNECTION_REFUSED", "ERR_NAME_NOT_RESOLVED", "ERR_BLOCKED_BY_CLIENT",
        "ERR_BLOCKED_BY_LOCAL_NETWORK_ACCESS", "ERR_BLOCKED_BY_PRIVATE_NETWORK_ACCESS_CHECKS", "ERR_BLOCKED_BY_RESPONSE",
        "ERR_UNSAFE_REDIRECT", "ERR_INVALID_URL", "ERR_ADDRESS_UNREACHABLE", "ERR_CONNECTION_RESET"}
    if type(value) is str:
        for code in codes:
            if value == "net::" + code or value == code: return code
    return "OTHER"


def denied_callback_label(url, state, issuer):
    """Closed private metadata only; never retain or print callback inputs."""
    try:
        require(type(url) is str and len(url) <= 8192 and all(32 < ord(c) < 127 for c in url)
                and type(state) is str and state, "denial callback")
        parsed = urllib.parse.urlsplit(url)
        require(parsed.scheme == "http" and parsed.netloc == "127.0.0.1:18766"
                and parsed.path == "/observer-callback" and not parsed.fragment
                and not re.search(r"%(?![0-9A-Fa-f]{2})", parsed.query), "denial callback target")
        pairs = urllib.parse.parse_qsl(parsed.query, keep_blank_values=True, strict_parsing=True, max_num_fields=4, encoding="utf-8", errors="strict")
        values = dict(pairs)
        require(len(values) == len(pairs) and set(values) in ({"error", "state"}, {"error", "state", "iss"}), "denial callback fields")
        require(values["error"] == "access_denied" and hmac.compare_digest(values["state"], state)
                and ("iss" not in values or values["iss"] == issuer), "bound denial callback")
        return "BOUND_DENIAL"
    except Exception:
        return "INVALID"


class ObserverCallbackListener:
    """Actual container-loopback callback; never exposed on a host/network interface."""
    def __init__(self, labels, expectation, issuer):
        self.labels, self.expectation, self.issuer = labels, expectation, issuer
        self.server = None; self.thread = None

    def record(self, path):
        url = "http://127.0.0.1:18766" + path if type(path) is str and path.startswith("/") and len(path) <= 8192 else ""
        label = denied_callback_label(url, self.expectation["state"], self.issuer)
        if len(self.labels) < 16: self.labels.append(label)
        else: self.labels[-1] = "INVALID"
        print("NEGATIVE_CALLBACK: OBSERVER69 " + json.dumps(denied_callback_diagnostic(url, self.expectation["state"], self.issuer), sort_keys=True, separators=(",", ":")), flush=True)
        return label

    def start(self):
        owner = self
        class Handler(http.server.BaseHTTPRequestHandler):
            def do_GET(self):
                label = owner.record(self.path)
                body = b"<!doctype html><html><body>Authorization denied.</body></html>"
                self.send_response(200 if label == "BOUND_DENIAL" else 400)
                self.send_header("Content-Type", "text/html; charset=utf-8")
                self.send_header("Cache-Control", "no-store")
                self.send_header("Content-Length", str(len(body)))
                self.send_header("Connection", "close")
                self.end_headers(); self.wfile.write(body); self.close_connection = True
            def do_POST(self):
                owner.record(""); self.send_error(405)
            def log_message(self, *args): pass
            def log_error(self, *args): pass
            def send_error(self, code, message=None, explain=None):
                # Never reflect request path, method, parser messages or payloads.
                body = b"Callback rejected."
                self.send_response(code); self.send_header("Content-Type", "text/plain")
                self.send_header("Content-Length", str(len(body))); self.send_header("Connection", "close")
                self.end_headers(); self.wfile.write(body); self.close_connection = True
        class Server(http.server.HTTPServer):
            allow_reuse_address = False
            def __init__(self):
                self.requests = {}; self.lock = threading.Lock()
                super().__init__(("127.0.0.1", 18766), Handler)
            def get_request(self):
                connection, address = super().get_request()
                connection.settimeout(2)
                def terminate():
                    try: connection.shutdown(socket.SHUT_RDWR)
                    except OSError: pass
                    connection.close()
                timer = threading.Timer(2, terminate); timer.daemon = True
                with self.lock: self.requests[connection] = timer
                timer.start()
                return connection, address
            def close_request(self, connection):
                with self.lock: timer = self.requests.pop(connection, None)
                if timer is not None: timer.cancel(); timer.join(timeout=1)
                super().close_request(connection)
            def handle_error(self, request, client_address): owner.record("")
        self.server = Server()
        self.thread = threading.Thread(target=lambda: self.server.serve_forever(poll_interval=.1), daemon=True)
        self.thread.start()

    def close(self):
        if self.server is None: return
        self.server.shutdown(); self.server.server_close()
        self.thread.join(timeout=4)
        require(not self.thread.is_alive(), "callback listener reaped")
        self.server = None


def observer(node, flow, key=None, diagnostic=None):
    response = request("POST", node + "/realms/armoney/armoney-status/enrollment", flow,
                   {"X-ArMoney-Auth-Status-Key": key or env("AUTH_TO_SSO_STATUS_KEY")}, diagnostic=diagnostic)
    if response[2].get("Cache-Control") != "no-store" and diagnostic is not None:
        diagnostic("CACHE_POLICY")
    require(response[2].get("Cache-Control") == "no-store", "observer responses cannot be cached")
    if response[0] != 200:
        if response[1] is not None and diagnostic is not None:
            diagnostic("ERROR_BODY")
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
    state = {"count": 0, "flow": None, "observation": None, "error": False, "error_code": None,
             "observer_http": None, "observer_boundary": None}
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
            diagnostics = {"observer_http": None, "observer_boundary": None}
            def record_boundary(code):
                require(code in OBSERVER_BOUNDARIES, "finite observer boundary")
                if code.startswith("HTTP_"):
                    diagnostics["observer_http"] = code
                diagnostics["observer_boundary"] = code
            try:
                length = int(self.headers.get("Content-Length", "0"))
                require(0 < length <= 12288, "bounded bridge body")
                body = json.loads(self.rfile.read(length))
                require(set(body) == FLOW | {"command_id", "phone_number", "email"}, "exact enrollment request")
                flow = {key: body[key] for key in FLOW}
                bridge_phase = "BRIDGE_OBSERVER"
                observed = expect(200, observer(url("OBSERVER_NODE_B_URL"), flow, diagnostic=record_boundary), "bridge observes node B")
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
                    state.update(diagnostics)
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


def expiry_direction(previous, current):
    # Only a finite direction is emitted, never either private timestamp.
    try:
        left = datetime.datetime.fromisoformat(previous.replace("Z", "+00:00"))
        right = datetime.datetime.fromisoformat(current.replace("Z", "+00:00"))
        return "SAME" if left == right else "LATER" if right > left else "EARLIER"
    except (AttributeError, TypeError, ValueError):
        return "INVALID"


def finish_browser_submission(page, node):
    origin = urllib.parse.urlsplit(node)
    def expected(request):
        parsed = urllib.parse.urlsplit(request.url)
        return (request.method == "POST" and request.is_navigation_request()
                and request.frame == page.main_frame
                and (parsed.scheme, parsed.netloc) == (origin.scheme, origin.netloc)
                and parsed.path == "/realms/armoney/login-actions/required-action")
    # Bridge capture precedes the enclosing Keycloak transaction's commit. A
    # requestfinished event waits for its complete response, unlike capture count.
    with page.expect_event("requestfinished", predicate=expected, timeout=12000) as finished:
        page.locator('button[name="operation"][value="send"]').click(timeout=12000)
    response = finished.value.response()
    valid = (response is not None and response.status == 200
             and response.headers.get("content-type", "").split(";", 1)[0].strip() == "text/html")
    print("BROWSER_POST: OBSERVER69 " + ("COMPLETE_VALID" if valid else "COMPLETE_INVALID"), flush=True)
    require(valid, "completed expected browser response")
    page.get_by_text("Verification is unavailable. Please try again.", exact=True).wait_for(timeout=12000)
    page.locator('button[name="operation"][value="send"]').wait_for(timeout=12000)


def committed_observation(node, flow, captured):
    current = expect(200, observer(node, flow, diagnostic=observer_boundary), "committed node B observation")
    require(set(current) == OBSERVATION and all(current[key] == flow[key] for key in FLOW),
            "committed exact observation binding")
    unchanged = all(current[key] == captured[key] for key in OBSERVATION - {"authentication_session_expires_at"})
    direction = expiry_direction(captured["authentication_session_expires_at"], current["authentication_session_expires_at"])
    print("BASELINE: OBSERVER69 " + ("FIELDS_SAME" if unchanged else "FIELDS_CHANGED") + " EXPIRY_" + direction, flush=True)
    require(unchanged and direction != "INVALID", "committed baseline preserves non-expiry observation")
    return current


def read_only_checks(node, flow, first, initial_inspection):
    for _ in range(3):
        phase("READ_ONLY_RPC")
        current = expect(200, observer(node, flow, diagnostic=observer_boundary), "read-only observation")
        phase("READ_ONLY_EXPIRY")
        print("EXPIRY_CHECK: OBSERVER69 " + expiry_direction(first["authentication_session_expires_at"],
              current["authentication_session_expires_at"]), flush=True)
        require(current["authentication_session_expires_at"] == first["authentication_session_expires_at"],
                "observer cannot refresh expiry")
        phase("READ_ONLY_DTO")
        require(current == first, "observer cannot change exact observation")
        phase("READ_ONLY_INSPECT")
        inspection = controls(node, "inspect", flow)
        phase("READ_ONLY_TIMESTAMP")
        require(inspection["root_timestamp"] == initial_inspection["root_timestamp"],
                "observer cannot refresh root timestamp")
    for field, replacement, label in (
            ("authentication_session_id", "A" * 43, "READ_ONLY_HMAC_DENIAL"),
            ("subject", str(uuid.uuid4()), "READ_ONLY_SUBJECT_DENIAL")):
        phase(label)
        changed = dict(flow, **{field: replacement})
        expect(503, observer(node, changed, diagnostic=observer_boundary), "binding mismatch denial")
    phase("READ_ONLY_KEY_DENIAL")
    expect(401, observer(node, flow, "invalid-fixture-status-key", diagnostic=observer_boundary),
           "private credential denial")


def removal_checks(a, b, flow, kind):
    require(kind in ("tab", "root"), "fixed removal kind")
    phase("TAB_DELETE_RPC" if kind == "tab" else "ROOT_DELETE_RPC")
    controls(a, "delete-" + kind, flow)
    phase("TAB_INSPECT_RPC" if kind == "tab" else "ROOT_INSPECT_RPC")
    inspected = controls(b, "inspect", flow)
    phase("TAB_ABSENCE" if kind == "tab" else "ROOT_ABSENCE")
    require(not inspected["tab_present" if kind == "tab" else "root_present"],
            "committed removal visible on B")
    phase("TAB_OBSERVER_DENIAL" if kind == "tab" else "ROOT_OBSERVER_DENIAL")
    expect(503, observer(b, flow, diagnostic=observer_boundary), "removed session denial")


def disabled_observation_checks(node, flow, first):
    # Disabling a user is negative evidence, never enrollment/login authority.
    # The same trusted tuple must still resolve; do not substitute a new flow.
    current = expect(200, observer(node, flow, diagnostic=observer_boundary), "disabled user observation")
    require(set(current) == OBSERVATION, "exact disabled observation DTO")
    require(all(current[key] == flow[key] for key in FLOW), "same disabled observation binding")
    require(current["enabled"] is False and current["authentication_session_active"] is True,
            "literal disabled user and live session flags")
    expected = dict(first, enabled=False)
    require(current == expected, "disabled observation preserves contacts and original deadline")
    deadline = datetime.datetime.fromisoformat(current["authentication_session_expires_at"].replace("Z", "+00:00")).timestamp()
    require(deadline > controls(node, "inspect", flow)["server_time"], "disabled observation deadline remains future")


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
    callback_listener = None
    try:
        provision_client_and_action(admin, authorization)
        for node in (a, b):
            discovery = expect(200, request("GET", node + "/realms/armoney/.well-known/openid-configuration"),
                               "new realm visible on direct node")
            require(discovery["issuer"] == issuer, "both nodes expose common fixed issuer")
        phase("USER_SETUP")
        expect(201, request("POST", users, unmarked_user(phone, email, password), authorization), "synthetic user")
        identifier = canonical_user(users, authorization, phone)

        phase("BROWSER_RENDER")
        with sync_playwright() as p:
            browser = p.chromium.launch()
            context = browser.new_context()  # No ignore_https_errors or certificate bypass.
            callback_count = []
            callback_expectation = {"state": None}
            callback_listener = ObserverCallbackListener(callback_count, callback_expectation, issuer)
            callback_listener.start()

            # An admin-created persistent action is intentionally not registration authority.
            denied = context.new_page()
            navigation_events = {"count": 0}
            def failed_navigation(request):
                if request.is_navigation_request() and navigation_events["count"] < 16:
                    navigation_events["count"] += 1
                    facts = negative_navigation_diagnostic(request.url)
                    facts["failure"] = negative_network_failure(request.failure)
                    print("NEGATIVE_REQUEST_FAILED: OBSERVER69 " + json.dumps(facts, sort_keys=True, separators=(",", ":")), flush=True)
            def navigation_response(response):
                if response.request.is_navigation_request() and navigation_events["count"] < 16:
                    navigation_events["count"] += 1
                    facts = negative_navigation_diagnostic(response.url)
                    facts["status"] = response.status if type(response.status) is int and 100 <= response.status <= 599 else 0
                    print("NEGATIVE_RESPONSE: OBSERVER69 " + json.dumps(facts, sort_keys=True, separators=(",", ":")), flush=True)
            denied.on("requestfailed", failed_navigation)
            denied.on("response", navigation_response)
            before_denied = capture()["count"]
            denial_verifier = secrets.token_urlsafe(48)
            denial_challenge = base64.urlsafe_b64encode(hashlib.sha256(denial_verifier.encode()).digest()).rstrip(b"=").decode()
            denial_state = secrets.token_urlsafe(24)
            callback_expectation["state"] = denial_state
            denial_query = urllib.parse.urlencode({"client_id": "armoney-ios", "response_type": "code", "scope": "openid",
                "redirect_uri": "http://127.0.0.1:18766/observer-callback", "state": denial_state,
                "nonce": secrets.token_urlsafe(24), "code_challenge": denial_challenge, "code_challenge_method": "S256", "prompt": "login"})
            phase("NEGATIVE_AUTH_PAGE")
            denied.goto(a + "/realms/armoney/protocol/openid-connect/auth?" + denial_query)
            denied.locator("#username").fill(phone); denied.locator("#password").fill(password)
            phase("NEGATIVE_FORM_SUBMIT")
            denied.locator("#kc-login").click(no_wait_after=True)
            phase("NEGATIVE_DENIAL_RESULT")
            denial_deadline = time.monotonic() + 12
            while not callback_count and denied.locator("#kc-error-message").count() == 0:
                if time.monotonic() >= denial_deadline:
                    print('NEGATIVE_RESULT: OBSERVER69 {"callback_present":false,"error_page_present":false,"one_bound_denial":false}', flush=True)
                    print("NEGATIVE_DOM: OBSERVER69 " + json.dumps(negative_dom_diagnostic(denied), sort_keys=True, separators=(",", ":")), flush=True)
                require(time.monotonic() < denial_deadline, "bounded unmarked denial")
                denied.wait_for_timeout(50)
            print("NEGATIVE_RESULT: OBSERVER69 " + json.dumps({"callback_present": bool(callback_count),
                "error_page_present": denied.locator("#kc-error-message").count() == 1,
                "one_bound_denial": callback_count == ["BOUND_DENIAL"]}, sort_keys=True, separators=(",", ":")), flush=True)
            print("NEGATIVE_DOM: OBSERVER69 " + json.dumps(negative_dom_diagnostic(denied), sort_keys=True, separators=(",", ":")), flush=True)
            require(callback_count == ["BOUND_DENIAL"] or not callback_count and denied.locator("#kc-error-message").count() == 1,
                    "only actual error page or bound access denied callback")
            phase("NEGATIVE_NO_SEND")
            require(denied.locator('button[name="operation"][value="send"]').count() == 0, "unmarked persistent action has no Send")
            phase("NEGATIVE_NO_RPC")
            require(capture()["count"] == before_denied, "unmarked persistent action denied without RPC")
            denied.close()
            callback_count.clear(); callback_expectation["state"] = None
            expect(204, request("DELETE", users + "/" + identifier, headers=authorization), "delete unmarked synthetic user")
            identifier = None

            def login():
                nonlocal phone, email, password, identifier
                phase("BROWSER_RENDER")
                before = capture()["count"]
                phone = "+97150" + str(secrets.randbelow(10**7)).zfill(7)
                email = "observer-" + uuid.uuid4().hex + "@example.invalid"
                password = secrets.token_urlsafe(24)
                page = context.new_page()
                verifier = secrets.token_urlsafe(48)
                challenge = base64.urlsafe_b64encode(hashlib.sha256(verifier.encode()).digest()).rstrip(b"=").decode()
                query = urllib.parse.urlencode({"client_id": "armoney-ios", "response_type": "code",
                          "redirect_uri": "http://127.0.0.1:18766/observer-callback", "scope": "openid",
                          "state": secrets.token_urlsafe(24), "nonce": secrets.token_urlsafe(24),
                          "code_challenge": challenge, "code_challenge_method": "S256", "prompt": "login"})
                page.goto(a + "/realms/armoney/protocol/openid-connect/registrations?" + query)
                page.locator("#username").fill(phone)
                page.locator("#email").fill(email)
                for selector, value in (("#firstName", "Synthetic"), ("#lastName", "Observer")):
                    if page.locator(selector).count(): page.locator(selector).fill(value)
                page.locator("#password").fill(password)
                page.locator("#password-confirm").fill(password)
                page.locator('input[type="submit"],button[type="submit"]').click()
                page.locator('button[name="operation"][value="send"]').wait_for()
                identifier = canonical_user(users, authorization, phone)
                require(capture()["count"] == before and not callback_count, "initial render zero RPC/callback")
                phase("SEND_RPC")
                finish_browser_submission(page, a)
                deadline = time.monotonic() + 12
                while time.monotonic() < deadline:
                    item = capture()
                    if item["error"]:
                        bridge_error(item.get("error_code"))
                        observer_boundary(item.get("observer_http"))
                        observer_boundary(item.get("observer_boundary"))
                    require(not item["error"], "bridge observation failed")
                    if item["count"] == before + 1:
                        require(not callback_count, "denied bridge cannot complete login")
                        committed = committed_observation(b, item["flow"], item["observation"])
                        return page, item["flow"], committed
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
            read_only_checks(b, flow, first, right)
            phase("TAB_REMOVAL")
            removal_checks(a, b, flow, "tab")
            page.close()
            page, flow, first = login()
            phase("ROOT_REMOVAL")
            removal_checks(a, b, flow, "root")
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
            disabled_observation_checks(b, flow, first)
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
            if callback_listener is not None: callback_listener.close()
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
