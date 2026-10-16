"""Disposable SSO-VIS-001 diagnostics; visibility never grants banking authority."""
import base64
import hashlib
import importlib.util
import os
from pathlib import Path
import re
import secrets
import sys
import threading
import time
import urllib.parse
import uuid

SPEC = importlib.util.spec_from_file_location("visibility_http", Path(__file__).with_name("sso-provider-acceptance.py"))
HTTP = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(HTTP)
TUPLE = frozenset({"realm_id", "subject", "user_session_id", "client_id", "client_uuid",
                   "expires_at", "nonce", "observation_token"})
REALM = "visibility-fixture"
CALLBACK = "http://127.0.0.1:18767/visibility-callback"
TARGETS = {"VISIBILITY_NODE_A_URL": "http://sso-a:8080", "VISIBILITY_NODE_B_URL": "http://sso-b:8080",
           "VISIBILITY_ADMIN_URL": "http://sso-a:8080"}

PHASES = frozenset({"START", "CONFIGURATION", "READINESS", "SETUP", "ARM", "HELD",
                    "PRE", "RELEASE", "BROWSER", "POST", "CLEANUP", "COMPLETE"})
OUTCOMES = frozenset({"PREVISIBLE", "NOT_VISIBLE", "ERROR"})
current_phase = "START"
failure_phase = None


def require(condition, message="fixture assertion"):
    if not condition:
        raise AssertionError(message)


def phase(value):
    global current_phase
    require(type(value) is str and value in PHASES)
    current_phase = value
    print("PHASE: SSO_VIS_001 " + value, flush=True)


def remember_failure():
    global failure_phase
    if failure_phase is None:
        failure_phase = current_phase


def observation(value):
    """Validate immutable facts; partial visibility is a finite diagnostic only."""
    require(type(value) is dict and set(value) == {"outcome", "user_session", "client_session"})
    require(type(value["outcome"]) is str and value["outcome"] in {"VISIBLE", "NOT_VISIBLE", "ERROR"})
    require(type(value["user_session"]) is bool and type(value["client_session"]) is bool)
    both = value["user_session"] and value["client_session"]
    require((value["outcome"] == "VISIBLE") == both)
    require(value["outcome"] != "ERROR" or (not value["user_session"] and not value["client_session"]))
    return "PREVISIBLE" if both else value["outcome"]


def emit_observation(label, value):
    require(label in {"PRE", "POST", "FAIL_POST"})
    result = observation(value)
    print("OBSERVATION: SSO_VIS_001 " + label + " " + result, flush=True)
    return result


def committed_observation(value):
    require(emit_observation("POST", value) == "PREVISIBLE", "both sessions required after response")


def validate_configuration():
    require(os.environ.get("CI") == "true" and os.environ.get("VISIBILITY_ACCEPTANCE_ISOLATED") == "true")
    require(all(os.environ.get(name) == target for name, target in TARGETS.items()))
    key = os.environ.get("ARMONEY_VISIBILITY_CONTROL_KEY", "")
    require(32 <= len(key.encode("utf-8")) <= 512 and all(33 <= ord(char) <= 126 for char in key))
    for other in ("AUTH_TO_SSO_STATUS_KEY", "SSO_VERIFICATION_BRIDGE_KEY",
                  "OBSERVER_FIXTURE_CONTROL_KEY", "ARMONEY_PROVIDER_BINDING_KEY", "SSO_ADMIN_PASSWORD"):
        require(key != os.environ.get(other, ""))
    require(bool(os.environ.get("SSO_ADMIN_PASSWORD")))


def control(node, operation, body):
    require(operation in {"arm", "state", "release", "cancel", "observe"})
    status, value, headers = HTTP.request("POST", node + "/realms/" + REALM +
        "/armoney-visibility-fixture/" + operation, body,
        {"X-ArMoney-Visibility-Key": os.environ["ARMONEY_VISIBILITY_CONTROL_KEY"]})
    require(headers.get("Cache-Control", "").lower() == "no-store")
    if operation == "observe":
        require(status in {200, 503})
        observation(value)
        require((status == 503) == (value["outcome"] == "ERROR"))
    else:
        require(status == 200)
        if operation in {"state", "release", "cancel"}:
            state_value(value)
    return value


def state_value(value):
    require(type(value) is dict and set(value) == {"state", "tuple"})
    require(value["state"] in {"ARMED", "HELD", "RELEASED", "CANCELLED"})
    facts = value["tuple"]
    if facts is not None:
        require(type(facts) is dict and set(facts) == TUPLE)
        require(type(facts["expires_at"]) is int)
        require(all(type(facts[key]) is str and 0 < len(facts[key]) <= 512 for key in TUPLE - {"expires_at"}))
        require(re.fullmatch(r"[A-Za-z0-9_-]{43}", facts["observation_token"]) is not None)
    require(value["state"] != "HELD" or facts is not None)
    return value


def wait_held(node, token):
    deadline = time.monotonic() + 10
    while time.monotonic() < deadline:
        value = state_value(control(node, "state", {"experiment_token": token}))
        if value["state"] == "HELD":
            return value["tuple"]
        require(value["state"] == "ARMED")
        time.sleep(0.05)
    raise AssertionError("bounded held state")


class BrowserOutcome:
    """Cross-thread communication contains completion flags, never Playwright models."""
    def __init__(self):
        self.done = threading.Event()
        self.response_complete = False
        self.callback_seen = False
        self.failed = False

    def finish(self, response_complete=False, callback_seen=False, failed=False):
        self.response_complete = response_complete is True
        self.callback_seen = callback_seen is True
        self.failed = failed is True
        self.done.set()

    def await_response(self, timeout=20):
        require(self.done.wait(timeout), "bounded browser completion")
        require(not self.failed and self.response_complete, "completed expected browser response")


def run_with_cleanup(action, cleanup):
    try:
        action()
    except Exception:
        remember_failure()
        raise
    finally:
        phase("CLEANUP")
        try:
            cleanup()
        except Exception:
            remember_failure()
            raise


def main():
    global current_phase, failure_phase
    current_phase, failure_phase = "START", None
    try:
        acceptance()
        phase("COMPLETE")
        print("PASS: SSO_VIS_001 COMPLETE", flush=True)
    except Exception:
        remember_failure()
        raise SystemExit("FAIL: SSO_VIS_001 " + failure_phase) from None


def browser_login(node, client, username, password, mode, callback_failure, outcome):
    # Every Playwright object stays in this thread. Only finite completion facts leave it.
    try:
        from playwright.sync_api import sync_playwright
        with sync_playwright() as playwright:
            browser = playwright.chromium.launch()
            try:
                context = browser.new_context()
                callback_seen = []
                def callback(route):
                    callback_seen.append(True)
                    if callback_failure:
                        route.abort()
                    else:
                        route.fulfill(status=200, content_type="text/plain", body="Disposable callback complete")
                context.route(CALLBACK + "**", callback)
                page = context.new_page()
                challenge = base64.urlsafe_b64encode(hashlib.sha256(secrets.token_urlsafe(48).encode()).digest()).rstrip(b"=").decode()
                query = urllib.parse.urlencode({"client_id": client, "response_type": "code", "redirect_uri": CALLBACK,
                    "scope": "openid", "state": secrets.token_urlsafe(24), "nonce": secrets.token_urlsafe(24),
                    "code_challenge": challenge, "code_challenge_method": "S256", "prompt": "login"})
                page.goto(node + "/realms/" + REALM + "/protocol/openid-connect/auth?" + query, timeout=20000)
                page.locator("#username").fill(username)
                page.locator("#password").fill(password)
                def expected(request):
                    parsed = urllib.parse.urlsplit(request.url)
                    return (request.method == "POST" and request.is_navigation_request() and request.frame == page.main_frame
                        and (parsed.scheme + "://" + parsed.netloc) == node
                        and parsed.path == "/realms/" + REALM + "/login-actions/authenticate")
                # A failed callback must not hide a completed redirect response from A.
                with page.expect_event("requestfinished", predicate=expected, timeout=25000) as finished:
                    try:
                        page.locator("#kc-login").click(timeout=25000, no_wait_after=True)
                    except Exception:
                        # Validate A's response below; never accept a click exception as success.
                        pass
                response = finished.value.response()
                complete = response is not None and response.status == (503 if mode == "FAIL" else 302)
                if mode == "COMPLETE":
                    deadline = time.monotonic() + 5
                    while not callback_seen and time.monotonic() < deadline:
                        page.wait_for_timeout(50)
                    complete = complete and bool(callback_seen)
                else:
                    complete = complete and not callback_seen
                outcome.finish(response_complete=complete, callback_seen=bool(callback_seen))
            finally:
                browser.close()
    except Exception:
        outcome.finish(failed=True)


def experiment(a, b, subject, client, username, password, mode="COMPLETE", callback_failure=False):
    phase("ARM")
    armed = control(a, "arm", {"subject": subject, "client_id": client, "mode": mode})
    require(type(armed) is dict and set(armed) == {"experiment_token"})
    token = armed["experiment_token"]
    require(type(token) is str and 16 <= len(token) <= 512)
    outcome = BrowserOutcome()
    worker = threading.Thread(target=browser_login,
        args=(a, client, username, password, mode, callback_failure, outcome), daemon=True)
    worker.start()
    try:
        phase("HELD")
        facts = wait_held(a, token)
        require(facts["subject"] == subject and facts["client_id"] == client)
        phase("PRE")
        emit_observation("PRE", control(b, "observe", facts))
        phase("RELEASE")
        control(a, "release", {"experiment_token": token})
        phase("BROWSER")
        outcome.await_response(20)
        phase("POST")
        observed = control(b, "observe", facts)
        if mode == "COMPLETE":
            committed_observation(observed)
        else:
            emit_observation("FAIL_POST", observed)
    except Exception:
        remember_failure()
        raise
    finally:
        try:
            control(a, "cancel", {"experiment_token": token})
        finally:
            worker.join(25)
            require(not worker.is_alive(), "bounded browser shutdown")


def acceptance():
    phase("CONFIGURATION")
    validate_configuration()
    a, b, admin = (os.environ[name] for name in ("VISIBILITY_NODE_A_URL", "VISIBILITY_NODE_B_URL", "VISIBILITY_ADMIN_URL"))
    phase("READINESS")
    HTTP.wait_ready(a)
    HTTP.wait_ready(b)
    login = HTTP.expect(200, HTTP.request("POST", admin + "/realms/master/protocol/openid-connect/token",
        {"client_id": "admin-cli", "grant_type": "password", "username": os.environ.get("VISIBILITY_ADMIN_USERNAME", "admin"),
         "password": os.environ["SSO_ADMIN_PASSWORD"]}, form=True), "synthetic admin")
    authorization = {"Authorization": "Bearer " + login["access_token"]}
    target = admin + "/admin/realms/" + REALM
    phase("SETUP")
    HTTP.expect(404, HTTP.request("GET", target, headers=authorization), "absent disposable realm")
    HTTP.expect(201, HTTP.request("POST", admin + "/admin/realms", {"realm": REALM, "enabled": True,
        "sslRequired": "none", "registrationAllowed": False, "loginWithEmailAllowed": False,
        "resetPasswordAllowed": False, "rememberMe": False}, authorization), "create disposable realm")
    def run():
        for node in (a, b):
            HTTP.expect(200, HTTP.request("GET", node + "/realms/" + REALM +
                "/.well-known/openid-configuration"), "disposable realm visible on both nodes")
        username = "visibility-" + uuid.uuid4().hex
        password = secrets.token_urlsafe(24)
        users = target + "/users"
        HTTP.expect(201, HTTP.request("POST", users, {"username": username, "enabled": True,
            "emailVerified": False, "credentials": [{"type": "password", "value": password, "temporary": False}]}, authorization), "synthetic user")
        found = HTTP.expect(200, HTTP.request("GET", users + "?username=" + urllib.parse.quote(username) + "&exact=true",
            headers=authorization), "synthetic user lookup")
        require(type(found) is list and len(found) == 1)
        subject = found[0]["id"]
        # No required actions, banking claims, bridge, mail, code redemption or token calls.
        for index, (mode, callback_failure) in enumerate((("COMPLETE", False), ("COMPLETE", False),
                                                        ("COMPLETE", True), ("FAIL", False))):
            client = "visibility-client-" + str(index)
            HTTP.expect(201, HTTP.request("POST", target + "/clients", {"clientId": client, "enabled": True,
                "protocol": "openid-connect", "publicClient": True, "standardFlowEnabled": True,
                "directAccessGrantsEnabled": False, "implicitFlowEnabled": False, "serviceAccountsEnabled": False,
                "redirectUris": [CALLBACK], "webOrigins": [], "attributes": {"pkce.code.challenge.method": "S256"}},
                authorization), "synthetic client")
            print("CASE: SSO_VIS_001 " + ("FRESH_USER" if index == 0 else "NEW_CLIENT" if index == 1 else
                  "CALLBACK_FAILURE" if index == 2 else "HOOK_FAILURE"), flush=True)
            experiment(a, b, subject, client, username, password, mode, callback_failure)
    run_with_cleanup(run, lambda: HTTP.expect(204, HTTP.request("DELETE", target, headers=authorization), "owned realm cleanup"))


if __name__ == "__main__":
    main()
