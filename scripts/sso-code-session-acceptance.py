"""Disposable code-session HTTP acceptance; never redeems codes or grants authority."""
import datetime
import http.client
import importlib.util
import json
import os
from pathlib import Path
import re
import secrets
import sys
import threading
import time
import urllib.parse
import uuid

SPEC = importlib.util.spec_from_file_location("visibility_fixture", Path(__file__).with_name("sso-session-visibility-acceptance.py"))
V = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(V)
HTTP = V.HTTP
REALM = "code-session-fixture"
FIELDS = frozenset({"issuer", "subject", "client_id", "user_session_id", "username", "email", "phone_number",
                    "enabled", "user_session_active", "client_session_active", "observed_at", "valid_until"})
CASES = frozenset({"CONFIGURATION", "READINESS", "SETUP", "BASELINE", "HTTP_BOUNDARY", "FOREIGN_BINDING",
                   "CONFIDENTIAL", "DISABLED", "NULL_CONTACT", "SESSION_DELETE", "POLICY_EXPIRY", "CLEANUP", "COMPLETE"})
current = "CONFIGURATION"
failure = None
stop = None
case_stop = None


def case(value):
    global current, case_stop
    V.require(value in CASES)
    V.require(stop is None or time.monotonic() < stop)
    V.require(case_stop is None or time.monotonic() < case_stop)
    current = value
    case_stop = time.monotonic() + (120 if value == "READINESS" else 30)
    print("CASE: CODE_SESSION " + value, flush=True)


def timestamp(value):
    V.require(type(value) is str and value.endswith("Z") and "." not in value)
    parsed = datetime.datetime.fromisoformat(value.replace("Z", "+00:00"))
    V.require(parsed.isoformat().replace("+00:00", "Z") == value)
    return int(parsed.timestamp())


def validate(value, binding, enabled=True):
    V.require(type(value) is dict and set(value) == FIELDS)
    for item in value.values():
        if type(item) is str: item.encode("utf-8", errors="strict")
    V.require(all(type(value[k]) is str and value[k] == binding[k] for k in binding))
    V.require(type(value["username"]) is str and 0 < len(value["username"]) <= 255)
    V.require(not any(ord(c)<32 or ord(c)==127 for c in value["username"]))
    V.require(all(value[k] is None or type(value[k]) is str for k in ("email", "phone_number")))
    email = value["email"]
    V.require(email is None or (0<len(email)<=254 and email==email.strip()==email.lower()
        and re.fullmatch(r"[a-z0-9.!#$%&'*+/=?^_`{|}~-]+@[a-z0-9](?:[a-z0-9-]*[a-z0-9])?(?:\.[a-z0-9](?:[a-z0-9-]*[a-z0-9])?)+", email) is not None))
    V.require(value["phone_number"] is None or re.fullmatch(r"\+9715[024568][0-9]{7}", value["phone_number"]) is not None)
    V.require(type(value["enabled"]) is bool and value["enabled"] is enabled)
    V.require(value["user_session_active"] is True and value["client_session_active"] is True)
    V.require(1 <= timestamp(value["valid_until"]) - timestamp(value["observed_at"]) <= 5)
    return value


def strict_object(pairs):
    result = {}
    for key, value in pairs:
        V.require(key not in result)
        result[key] = value
    return result


def exchange(node, binding, key):
    V.require(node in V.TARGETS.values())
    origin = urllib.parse.urlsplit(node)
    deadline = min(time.monotonic()+4, case_stop or float("inf"), stop or float("inf"))
    V.require(time.monotonic() < deadline)
    connection = http.client.HTTPConnection(origin.hostname, origin.port, timeout=4)
    try:
        payload = binding if type(binding) is bytes else json.dumps(binding, separators=(",", ":")).encode("utf-8")
        connection.request("POST", "/realms/"+REALM+"/armoney-status/code-session", payload,
            {"Content-Type":"application/json", "X-ArMoney-Auth-Status-Key":key})
        transport = connection.sock
        transport.settimeout(max(0.001, deadline-time.monotonic()))
        response = connection.getresponse()
        raw = bytearray()
        while True:
            V.require(time.monotonic() < deadline)
            transport.settimeout(max(0.001, deadline-time.monotonic()))
            chunk = response.read1(min(4096, 12289-len(raw)))
            raw.extend(chunk)
            V.require(len(raw) <= 12288)
            if not chunk: break
        V.require(time.monotonic() < deadline)
        value = json.loads(raw.decode("utf-8"), object_pairs_hook=strict_object) if raw else None
        return response.status, value, response.headers
    finally:
        connection.close()


def observe(node, binding, expected=200, enabled=True, key=None):
    response = exchange(node, binding, os.environ["AUTH_TO_SSO_STATUS_KEY"] if key is None else key)
    V.require(response[0] == expected and response[2].get("Cache-Control", "").lower() == "no-store")
    if expected == 200:
        V.require(response[2].get("Content-Type", "").split(";",1)[0].strip()=="application/json")
        return validate(response[1], binding, enabled)
    V.require(response[1] is None)


def session(a, subject, username, password):
    token = None
    worker = None
    outcome = V.BrowserOutcome()
    try:
        token = V.control(a, "arm", {"subject": subject, "client_id": "armoney-ios", "mode": "COMPLETE"})["experiment_token"]
        worker = threading.Thread(target=V.browser_login, args=(a, "armoney-ios", username, password, "COMPLETE", False, outcome), daemon=True)
        worker.start()
        facts = V.wait_held(a, token, outcome)
        V.require(facts["subject"] == subject and facts["client_id"] == "armoney-ios")
        V.control(a, "release", {"experiment_token": token})
        outcome.await_response(15)
        return {"issuer": a + "/realms/" + REALM, "subject": subject,
                "client_id": "armoney-ios", "user_session_id": facts["user_session_id"]}
    finally:
        try:
            if token is not None:
                V.control(a, "cancel", {"experiment_token": token})
        finally:
            if worker is not None:
                worker.join(25)
                V.require(not worker.is_alive())


def wait_expired(node, binding, timeout=12):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        response = exchange(node, binding, os.environ["AUTH_TO_SSO_STATUS_KEY"])
        V.require(response[2].get("Cache-Control", "").lower() == "no-store")
        if response[0] == 503:
            V.require(response[1] is None)
            return
        V.require(response[0] == 200)
        validate(response[1], binding)
        time.sleep(0.2)
    raise AssertionError("bounded policy expiry")


def acceptance():
    global current, failure
    V.require(os.environ.get("CODE_SESSION_ACCEPTANCE_ISOLATED") == "true")
    V.validate_configuration()
    V.REALM = REALM  # Only this private imported instance, after isolation guards.
    a, b, admin = (os.environ[k] for k in ("VISIBILITY_NODE_A_URL", "VISIBILITY_NODE_B_URL", "VISIBILITY_ADMIN_URL"))
    case("READINESS")
    deadline = time.monotonic() + 120
    pending = {a, b}
    while pending and time.monotonic() < deadline:
        for node in tuple(pending):
            try:
                if HTTP.request("GET", node + "/realms/master/.well-known/openid-configuration")[0] == 200:
                    pending.remove(node)
            except (OSError, ValueError):
                pass
        if pending: time.sleep(0.5)
    V.require(not pending)
    login = HTTP.expect(200, HTTP.request("POST", admin + "/realms/master/protocol/openid-connect/token",
        {"client_id":"admin-cli", "grant_type":"password", "username":os.environ.get("VISIBILITY_ADMIN_USERNAME","admin"),
         "password":os.environ["SSO_ADMIN_PASSWORD"]}, form=True), "owned admin")
    auth = {"Authorization":"Bearer " + login["access_token"]}
    target = admin + "/admin/realms/" + REALM
    def command(method, path, data=None, expected=204):
        return HTTP.expect(expected, HTTP.request(method, target + path, data, auth), "owned fixture command")
    case("SETUP")
    HTTP.expect(404, HTTP.request("GET", target, headers=auth), "absent owned realm")
    HTTP.expect(201, HTTP.request("POST", admin + "/admin/realms", {"realm":REALM,"enabled":True,"sslRequired":"none",
        "registrationAllowed":False,"loginWithEmailAllowed":False,"resetPasswordAllowed":False}, auth), "create owned realm")
    try:
        username, password = "code-session-" + uuid.uuid4().hex, secrets.token_urlsafe(24)
        command("POST", "/users", {"username":username,"email":username+"@example.invalid","firstName":"Synthetic",
            "lastName":"CodeSession","enabled":True,"credentials":[{"type":"password","value":password,"temporary":False}]}, 201)
        users = command("GET", "/users?" + urllib.parse.urlencode({"username":username,"exact":"true"}), expected=200)
        V.require(len(users) == 1)
        subject = users[0]["id"]
        clients = {}
        for name in ("armoney-ios", "armoney-android", "unknown-public-client"):
            command("POST", "/clients", {"clientId":name,"protocol":"openid-connect","enabled":True,"publicClient":True,
                "standardFlowEnabled":True,"directAccessGrantsEnabled":False,"implicitFlowEnabled":False,
                "redirectUris":[V.CALLBACK],"attributes":{"pkce.code.challenge.method":"S256"}}, 201)
            listed = command("GET", "/clients?clientId=" + name, expected=200)
            V.require(len(listed) == 1)
            clients[name] = listed[0]["id"]
        case("BASELINE")
        binding = session(a, subject, username, password)
        baseline = observe(b, binding)
        V.require(baseline["email"] == username+"@example.invalid" and baseline["phone_number"] is None)
        case("HTTP_BOUNDARY")
        observe(a, binding, 503)
        observe(a, b"not-json", 503)
        observe(a, {"invalid":True}, 401, key="invalid-synthetic-key")
        observe(b, binding, 401, key="invalid-synthetic-key")
        for malformed in ({**binding,"extra":True}, {**binding,"subject":None}, {k:v for k,v in binding.items() if k!="client_id"}):
            observe(b, malformed, 400)
        for malformed in (b"{"+b'"issuer":"a","issuer":"b"}', b"not-json", b"x"*12289):
            observe(b, malformed, 400)
        case("FOREIGN_BINDING")
        command("POST", "/users", {"username":"foreign-"+uuid.uuid4().hex,"enabled":True}, 201)
        others = command("GET", "/users?max=10", expected=200)
        foreign = next(user["id"] for user in others if user["id"] != subject)
        observe(b, {**binding,"subject":foreign}, 503)
        for name in ("armoney-android", "unknown-public-client"):
            observe(b, {**binding,"client_id":name}, 503)
        case("CONFIDENTIAL")
        command("PUT", "/clients/"+clients["armoney-ios"], {"publicClient":False})
        observe(b, binding, 503)
        command("PUT", "/clients/"+clients["armoney-ios"], {"publicClient":True})
        observe(b, binding)
        case("DISABLED")
        command("PUT", "/users/"+subject, {"enabled":False})
        observe(b, binding, enabled=False)
        command("PUT", "/users/"+subject, {"enabled":True})
        case("NULL_CONTACT")
        command("PUT", "/users/"+subject, {"email":None})
        value = observe(b, binding)
        V.require(value["email"] is None and value["phone_number"] is None)
        command("PUT", "/users/"+subject, {"email":username+"@example.invalid"})
        case("SESSION_DELETE")
        command("DELETE", "/sessions/"+binding["user_session_id"])
        observe(b, binding, 503)
        case("POLICY_EXPIRY")
        command("PUT", "", {"ssoSessionIdleTimeout":8,"ssoSessionMaxLifespan":8,"clientSessionIdleTimeout":8,"clientSessionMaxLifespan":8})
        fresh = session(a, subject, username, password)
        V.require(fresh["user_session_id"] != binding["user_session_id"])
        observe(b, fresh)
        wait_expired(b, fresh)
    finally:
        if sys.exc_info()[0] is not None: failure = current
        # Cleanup remains mandatory even when the overall evidence budget expires.
        current = "CLEANUP"
        print("CASE: CODE_SESSION CLEANUP", flush=True)
        command("DELETE", "")


def main():
    global stop, failure, current, case_stop
    failure, current, case_stop = None, "CONFIGURATION", None
    stop = time.monotonic() + 540
    try:
        case("CONFIGURATION")
        acceptance()
        case("COMPLETE")
        print("PASS: CODE_SESSION COMPLETE", flush=True)
    except Exception:
        raise SystemExit("FAIL: CODE_SESSION " + (failure or current)) from None


if __name__ == "__main__":
    main()
