"""Disposable code-session HTTP acceptance; never redeems codes or grants authority."""
import datetime
import http.client
import importlib.util
import ipaddress
import json
import os
from pathlib import Path
import re
import secrets
import socket
import subprocess
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
STAGES = frozenset({"START", "SESSION_ARM", "SESSION_HELD", "SESSION_RELEASE", "SESSION_BROWSER", "SESSION_CANCEL",
    "SESSION_JOIN", "HTTP_DNS", "HTTP_SEND", "HTTP_HEADERS", "HTTP_BODY", "HTTP_JSON", "STATUS", "CACHE", "CONTENT_TYPE",
    "DTO_FIELDS", "UTF8", "BINDING", "USERNAME", "CONTACTS", "FLAGS", "TIMESTAMP", "TIME_DELTA", "BASELINE_CONTACT",
    "ADMIN_COMMAND", "ADMIN_CONTACT_CLEAR", "ADMIN_CONTACT_READBACK", "ADMIN_CONTACT_RESTORE", "NULL_CONTACT_VALUE",
    "POLICY_NEW_SESSION", "ERROR_BODY"})
stage = "START"
expected_status = "HTTP_NONE"
observed_status = "HTTP_NONE"
failure_diagnostic = None


def checkpoint(label):
    global stage
    V.require(type(label) is str and label in STAGES)
    stage = label


def status_label(status):
    return "HTTP_"+str(status) if type(status) is int and status in {200,400,401,403,404,429,500,503} else "HTTP_OTHER"


def error_kind(error):
    return ("ASSERT" if isinstance(error,AssertionError) else "JSON" if isinstance(error,(json.JSONDecodeError,UnicodeError))
        else "TIMEOUT" if isinstance(error,TimeoutError) else "TRANSPORT" if isinstance(error,(OSError,http.client.HTTPException)) else "OTHER")


def remember_error(error):
    global failure_diagnostic
    if failure_diagnostic is None:
        failure_diagnostic = (stage,expected_status,observed_status,error_kind(error))


def diagnostic(error):
    location, expected, observed, kind = failure_diagnostic or (stage,expected_status,observed_status,error_kind(error))
    print("BOUNDARY: CODE_SESSION "+location+" "+expected+" "+observed+" "+kind,flush=True)


def case(value):
    global current, case_stop, expected_status, observed_status
    V.require(value in CASES)
    V.require(stop is None or time.monotonic() < stop)
    V.require(case_stop is None or time.monotonic() < case_stop)
    current = value
    checkpoint("START")
    expected_status = observed_status = "HTTP_NONE"
    case_stop = time.monotonic() + (120 if value == "READINESS" else 30)
    print("CASE: CODE_SESSION " + value, flush=True)


def timestamp(value):
    checkpoint("TIMESTAMP")
    V.require(type(value) is str and value.endswith("Z") and "." not in value)
    parsed = datetime.datetime.fromisoformat(value.replace("Z", "+00:00"))
    V.require(parsed.isoformat().replace("+00:00", "Z") == value)
    return int(parsed.timestamp())


def validate(value, binding, enabled=True):
    checkpoint("DTO_FIELDS")
    V.require(type(value) is dict and set(value) == FIELDS)
    checkpoint("UTF8")
    for item in value.values():
        if type(item) is str: item.encode("utf-8", errors="strict")
    checkpoint("BINDING")
    V.require(all(type(value[k]) is str and value[k] == binding[k] for k in binding))
    checkpoint("USERNAME")
    V.require(type(value["username"]) is str and 0 < len(value["username"]) <= 255)
    V.require(not any(ord(c)<32 or ord(c)==127 for c in value["username"]))
    checkpoint("CONTACTS")
    V.require(all(value[k] is None or type(value[k]) is str for k in ("email", "phone_number")))
    email = value["email"]
    V.require(email is None or (0<len(email)<=254 and email==email.strip()==email.lower()
        and re.fullmatch(r"[a-z0-9.!#$%&'*+/=?^_`{|}~-]+@[a-z0-9](?:[a-z0-9-]*[a-z0-9])?(?:\.[a-z0-9](?:[a-z0-9-]*[a-z0-9])?)+", email) is not None))
    V.require(value["phone_number"] is None or re.fullmatch(r"\+9715[024568][0-9]{7}", value["phone_number"]) is not None)
    checkpoint("FLAGS")
    V.require(type(value["enabled"]) is bool and value["enabled"] is enabled)
    V.require(value["user_session_active"] is True and value["client_session_active"] is True)
    delta = timestamp(value["valid_until"]) - timestamp(value["observed_at"])
    checkpoint("TIME_DELTA")
    V.require(1 <= delta <= 5)
    return value


def strict_object(pairs):
    result = {}
    for key, value in pairs:
        V.require(key not in result)
        result[key] = value
    return result


RESOLVER = """import socket,json,sys
result=[]
for family,kind,protocol,name,address in socket.getaddrinfo(sys.argv[1],8080,type=socket.SOCK_STREAM):
 if family in (socket.AF_INET,socket.AF_INET6) and address[0] not in result: result.append(address[0])
 if len(result)==8: break
raw=json.dumps(result,separators=(',',':')).encode('ascii')
if len(raw)>1024: sys.exit(1)
sys.stdout.buffer.write(raw)
"""
FIXTURE_RANGES = tuple(ipaddress.ip_network(value) for value in
    ("10.0.0.0/8","172.16.0.0/12","192.168.0.0/16","fc00::/7"))


def fixture_address(address):
    if (address.is_loopback or address.is_unspecified or address.is_link_local
            or address.is_multicast or address.is_reserved):
        return False
    return any(address.version==network.version and address in network for network in FIXTURE_RANGES)


class Deadline:
    def __init__(self, end):
        self.end, self.physical, self.child = end, None, None
        self.lock = threading.Lock()
        self.expired = threading.Event()
        self.timer = threading.Timer(max(0,end-time.monotonic()),self.expire)
        self.timer.start()

    def remaining(self):
        left = self.end-time.monotonic()
        if self.expired.is_set() or left<=0: raise TimeoutError("bounded exchange")
        return left

    @staticmethod
    def dispose(physical):
        if physical is not None:
            try: physical.shutdown(socket.SHUT_RDWR)
            except OSError: pass
            physical.close()

    def expire(self):
        self.expired.set()
        with self.lock:
            physical, child = self.physical,self.child
        self.dispose(physical)
        if child is not None:
            try: child.kill()
            except OSError: pass

    def attach(self, physical):
        with self.lock:
            self.physical = physical
            expired = self.expired.is_set()
        if expired:
            self.dispose(physical)
            raise TimeoutError("bounded connect")

    def close(self):
        self.timer.cancel()
        self.dispose(self.physical)
        self.timer.join(1)
        V.require(not self.timer.is_alive())


def resolve(host, guard):
    V.require(host in {"sso-a","sso-b"})
    environment = {k:os.environ[k] for k in ("SystemRoot","WINDIR") if k in os.environ}
    child = subprocess.Popen([sys.executable,"-I","-S","-c",RESOLVER,host], stdin=subprocess.DEVNULL,
        stdout=subprocess.PIPE,stderr=subprocess.DEVNULL,env=environment)
    with guard.lock:
        guard.child = child
    try:
        try:
            raw,_ = child.communicate(timeout=guard.remaining())
        except subprocess.TimeoutExpired:
            raise TimeoutError("bounded resolver") from None
        guard.remaining()
        V.require(child.returncode==0 and len(raw)<=1024)
        values = json.loads(raw.decode("ascii"))
        V.require(type(values) is list and 1<=len(values)<=8)
        addresses = [ipaddress.ip_address(item) for item in values]
        # is_private also includes loopback, metadata and special-use ranges.
        # No topology subnet is frozen; permit only RFC1918 / IPv6 ULA here.
        V.require(all(fixture_address(address) for address in addresses))
        return next((address for address in addresses if address.version==4),addresses[0])
    finally:
        if child.poll() is None: child.kill()
        child.communicate(timeout=1)  # Kill and reap, including deadline/EOF failures.
        with guard.lock:
            guard.child = None


class OwnedConnection(http.client.HTTPConnection):
    def __init__(self, address, guard):
        self.address, self.guard = address,guard
        super().__init__(str(address),8080,timeout=guard.remaining())

    def connect(self):
        family = socket.AF_INET if self.address.version==4 else socket.AF_INET6
        physical = socket.socket(family,socket.SOCK_STREAM)
        self.guard.attach(physical)  # Capture before numeric TCP connect, no DNS here.
        self.sock = physical
        physical.settimeout(self.guard.remaining())
        physical.connect((str(self.address),self.port))
        self.guard.remaining()


def exchange(node, binding, key):
    global observed_status
    observed_status = "HTTP_NONE"
    checkpoint("HTTP_SEND")
    V.require(node in {"http://sso-a:8080","http://sso-b:8080"})
    origin = urllib.parse.urlsplit(node)
    deadline = min(time.monotonic()+4, case_stop or float("inf"), stop or float("inf"))
    V.require(time.monotonic() < deadline)
    guard = Deadline(deadline)
    connection = None
    try:
        checkpoint("HTTP_DNS")
        address = resolve(origin.hostname,guard)
        connection = OwnedConnection(address,guard)
        checkpoint("HTTP_SEND")
        payload = binding if type(binding) is bytes else json.dumps(binding, separators=(",", ":")).encode("utf-8")
        connection.request("POST", "/realms/"+REALM+"/armoney-status/code-session", payload,
            {"Content-Type":"application/json", "X-ArMoney-Auth-Status-Key":key})
        transport = guard.physical
        transport.settimeout(guard.remaining())
        checkpoint("HTTP_HEADERS")
        response = connection.getresponse()
        observed_status = status_label(response.status)
        checkpoint("HTTP_BODY")
        raw = bytearray()
        while True:
            guard.remaining()
            # read1 closes a Connection:close transport after the exact final
            # Content-Length byte. Do not touch that closed socket again.
            if getattr(response,"length",None) == 0:
                break
            transport.settimeout(guard.remaining())
            chunk = response.read1(min(4096, 12289-len(raw)))
            raw.extend(chunk)
            V.require(len(raw) <= 12288)
            if not chunk:
                V.require(getattr(response,"length",None) in (None,0))
                break
        guard.remaining()
        checkpoint("HTTP_JSON")
        value = json.loads(raw.decode("utf-8"), object_pairs_hook=strict_object) if raw else None
        guard.remaining()
        return response.status, value, response.headers
    except (OSError,http.client.HTTPException):
        if guard.expired.is_set(): raise TimeoutError("bounded exchange") from None
        raise
    finally:
        try:
            if connection is not None: connection.close()
        finally:
            guard.close()


def observe(node, binding, expected=200, enabled=True, key=None):
    global expected_status
    expected_status = status_label(expected)
    response = exchange(node, binding, os.environ["AUTH_TO_SSO_STATUS_KEY"] if key is None else key)
    checkpoint("STATUS")
    V.require(response[0] == expected)
    checkpoint("CACHE")
    V.require(response[2].get("Cache-Control", "").lower() == "no-store")
    if expected == 200:
        checkpoint("CONTENT_TYPE")
        V.require(response[2].get("Content-Type", "").split(";",1)[0].strip()=="application/json")
        return validate(response[1], binding, enabled)
    checkpoint("ERROR_BODY")
    V.require(response[1] is None)


def contact_readback(command, subject, username, email=None):
    checkpoint("ADMIN_CONTACT_READBACK")
    user = command("GET", "/users/"+subject, expected=200)
    V.require(type(user) is dict)
    missing = "email" not in user or user["email"] is None
    phone_source = user.get("username")
    phone = ("PHONE_SOURCE_CANONICAL" if type(phone_source) is str and re.fullmatch(r"\+9715[024568][0-9]{7}",phone_source)
        else "PHONE_SOURCE_NONCANONICAL" if type(phone_source) is str else "PHONE_SOURCE_UNKNOWN")
    print("CONTACT_READBACK: CODE_SESSION "+("EMAIL_NULL" if missing else "EMAIL_NON_NULL")+" "+phone,flush=True)
    V.require(user.get("id")==subject and phone_source==username and phone=="PHONE_SOURCE_NONCANONICAL")
    V.require(missing if email is None else user.get("email")==email)


def null_contacts(command, node, binding, username, original_email):
    subject = binding["subject"]
    try:
        checkpoint("ADMIN_CONTACT_CLEAR")
        # Pinned admin profile merge ignores null; explicit blank removes email.
        command("PUT", "/users/"+subject, {"email":""})
        contact_readback(command,subject,username)
        value = observe(node,binding)
        checkpoint("NULL_CONTACT_VALUE")
        V.require(value["email"] is None and value["phone_number"] is None)
    except Exception as error:
        remember_error(error)
        raise
    finally:
        checkpoint("ADMIN_CONTACT_RESTORE")
        command("PUT", "/users/"+subject, {"email":original_email})
        contact_readback(command,subject,username,original_email)


def session(a, subject, username, password):
    global failure_diagnostic
    token = None
    worker = None
    outcome = V.BrowserOutcome()
    try:
        checkpoint("SESSION_ARM")
        token = V.control(a, "arm", {"subject": subject, "client_id": "armoney-ios", "mode": "COMPLETE"})["experiment_token"]
        worker = threading.Thread(target=V.browser_login, args=(a, "armoney-ios", username, password, "COMPLETE", False, outcome), daemon=True)
        worker.start()
        checkpoint("SESSION_HELD")
        facts = V.wait_held(a, token, outcome)
        V.require(facts["subject"] == subject and facts["client_id"] == "armoney-ios")
        checkpoint("SESSION_RELEASE")
        V.control(a, "release", {"experiment_token": token})
        checkpoint("SESSION_BROWSER")
        outcome.await_response(15)
        return {"issuer": a + "/realms/" + REALM, "subject": subject,
                "client_id": "armoney-ios", "user_session_id": facts["user_session_id"]}
    except Exception as error:
        remember_error(error)
        raise
    finally:
        try:
            if token is not None:
                checkpoint("SESSION_CANCEL")
                V.control(a, "cancel", {"experiment_token": token})
        except Exception as error:
            remember_error(error)
            raise
        finally:
            if worker is not None:
                checkpoint("SESSION_JOIN")
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
    global current, failure, failure_diagnostic
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
        previous = stage
        checkpoint("ADMIN_COMMAND")
        # Contact substeps retain their more specific boundary on HTTP failures.
        if previous in {"ADMIN_CONTACT_CLEAR","ADMIN_CONTACT_READBACK","ADMIN_CONTACT_RESTORE"}: checkpoint(previous)
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
        checkpoint("BASELINE_CONTACT")
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
        null_contacts(command,b,binding,username,username+"@example.invalid")
        case("SESSION_DELETE")
        command("DELETE", "/sessions/"+binding["user_session_id"])
        observe(b, binding, 503)
        case("POLICY_EXPIRY")
        command("PUT", "", {"ssoSessionIdleTimeout":8,"ssoSessionMaxLifespan":8,"clientSessionIdleTimeout":8,"clientSessionMaxLifespan":8})
        fresh = session(a, subject, username, password)
        checkpoint("POLICY_NEW_SESSION")
        V.require(fresh["user_session_id"] != binding["user_session_id"])
        observe(b, fresh)
        wait_expired(b, fresh)
    finally:
        if sys.exc_info()[0] is not None:
            failure = current
            remember_error(sys.exc_info()[1])
        # Cleanup remains mandatory even when the overall evidence budget expires.
        current = "CLEANUP"
        print("CASE: CODE_SESSION CLEANUP", flush=True)
        command("DELETE", "")


def main():
    global stop, failure, current, case_stop, stage, expected_status, observed_status, failure_diagnostic
    failure, current, case_stop = None, "CONFIGURATION", None
    stage, expected_status, observed_status, failure_diagnostic = "START", "HTTP_NONE", "HTTP_NONE", None
    stop = time.monotonic() + 540
    try:
        case("CONFIGURATION")
        acceptance()
        case("COMPLETE")
        print("PASS: CODE_SESSION COMPLETE", flush=True)
    except Exception as error:
        diagnostic(error)
        raise SystemExit("FAIL: CODE_SESSION " + (failure or current)) from None


if __name__ == "__main__":
    main()
