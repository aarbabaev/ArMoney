"""Real browser authorization-code/PKCE acceptance, disposable CI only."""
import base64
import hashlib
import http.server
import json
import os
import secrets
import ssl
import subprocess
import sys
import uuid
import threading
import time
import urllib.error
import urllib.parse
import urllib.request

if os.environ.get("CI") != "true":
    raise SystemExit("SSO acceptance requires disposable CI, never the persistent stack")

from playwright.sync_api import sync_playwright

PUBLIC = "https://localhost:8443"
ADMIN = "http://127.0.0.1:9090/sso"
REALM = PUBLIC + "/sso/realms/armoney"
TLS = ssl.create_default_context(cafile=os.environ["SSO_CA_FILE"])


def request(method, url, data=None, token=None, form=False, extra_headers=None):
    headers = dict(extra_headers or {})
    if token:
        headers["Authorization"] = "Bearer " + token
    if data is not None:
        headers["Content-Type"] = "application/x-www-form-urlencoded" if form else "application/json"
        data = (urllib.parse.urlencode(data) if form else json.dumps(data)).encode()
    req = urllib.request.Request(url, data=data, headers=headers, method=method)
    try:
        result = urllib.request.urlopen(req, context=TLS, timeout=15)
    except urllib.error.HTTPError as error:
        result = error
    with result:
        body = result.read()
        return result.status, json.loads(body) if body and "application/json" in result.headers.get("Content-Type", "") else None


def expect(status, response):
    assert response[0] == status, f"Expected {status}, got {response[0]}"
    return response[1]


for attempt in range(120):
    try:
        discovery = expect(200, request("GET", REALM + "/.well-known/openid-configuration"))
        break
    except (AssertionError, OSError):
        time.sleep(2)
else:
    raise AssertionError("SSO did not start")
assert discovery["issuer"] == REALM, "Provider issuer does not match the configured public realm URL"
spoofed = expect(200, request("GET", REALM + "/.well-known/openid-configuration", extra_headers={
    "X-Forwarded-Host": "attacker.invalid", "X-Forwarded-Proto": "http", "X-Forwarded-Port": "80"}))
assert spoofed["issuer"] == REALM and spoofed["authorization_endpoint"].startswith(REALM + "/")
expect(404, request("GET", PUBLIC + "/sso/admin/"))
expect(404, request("GET", PUBLIC + "/sso/realms/master/.well-known/openid-configuration"))

# Bootstrap admin credential is for disposable realm setup, never the mobile grant.
admin = expect(200, request("POST", ADMIN + "/realms/master/protocol/openid-connect/token", {
    "client_id": "admin-cli", "grant_type": "password", "username": "admin",
    "password": os.environ["SSO_ADMIN_PASSWORD"]}, form=True))["access_token"]
NATIVE_CLIENTS = ("armoney-ios", "armoney-android")
callbacks = {client_id: "http://127.0.0.1:18765/callback/" + client_id for client_id in NATIVE_CLIENTS}
for client_id in NATIVE_CLIENTS:
    clients = expect(200, request("GET", ADMIN + "/admin/realms/armoney/clients?clientId=" + client_id, token=admin))
    assert len(clients) == 1
    client = clients[0]
    platform = client_id.removeprefix("armoney-")
    assert client["publicClient"] and client["standardFlowEnabled"] and not client["directAccessGrantsEnabled"]
    assert not client["implicitFlowEnabled"] and not client["serviceAccountsEnabled"]
    assert client["redirectUris"] == ["com.armoney." + platform + ":/oauth/callback"]
    assert client["attributes"]["post.logout.redirect.uris"] == "com.armoney." + platform + ":/oauth/logout"
    assert client["attributes"]["pkce.code.challenge.method"] == "S256"
    # Exact loopback redirects exist only in this disposable realm, not the shipped native clients.
    client["redirectUris"].append(callbacks[client_id])
    expect(204, request("PUT", ADMIN + "/admin/realms/armoney/clients/" + client["id"], client, admin))
# Reserved disposable SSO fixture range; other smoke scripts use different numbers.
username = "+971509990001"
password = secrets.token_urlsafe(24)

# Disposable upgrade fixture: a mapped pre-phone user must keep its bank UUID.
# Temporarily restore only the old username validation to create that historical user.
subprocess.run([sys.executable, "-B", "sso-service/test_phone_configuration.py"], check=True)
old_profile = expect(200, request("GET", ADMIN + "/admin/realms/armoney/users/profile", token=admin))
old_username_attribute = next(a for a in old_profile["attributes"] if a["name"] == "username")
old_username_attribute["validations"].pop("pattern", None)
old_username_attribute["validations"]["length"] = {"min": 3, "max": 255}
expect(200, request("PUT", ADMIN + "/admin/realms/armoney/users/profile", old_profile, admin))
old_username = "legacy-" + secrets.token_hex(8)
old_password = secrets.token_urlsafe(24)
expect(201, request("POST", ADMIN + "/admin/realms/armoney/users", {
    "username": old_username, "email": old_username + "@example.test", "emailVerified": True,
    "firstName": "Existing", "lastName": "Identity", "enabled": True,
    "requiredActions": ["VERIFY_PROFILE"],
    "credentials": [{"type": "password", "value": old_password, "temporary": False}]}, admin))
old_users = expect(200, request("GET", ADMIN + "/admin/realms/armoney/users?" +
    urllib.parse.urlencode({"username": old_username, "exact": "true"}), token=admin))
assert len(old_users) == 1
old_subject = str(uuid.UUID(old_users[0]["id"]))
old_bank_id = str(uuid.uuid4())
# All interpolated values are UUIDs or a fixed synthetic issuer, never customer input.
subprocess.run(["docker", "compose", "exec", "-T", "auth-db", "psql", "-U", "bank", "-d", "bank",
    "-v", "ON_ERROR_STOP=1"], input=(
        f"BEGIN; INSERT INTO identities(id) VALUES ('{old_bank_id}'); "
        f"INSERT INTO external_identities(issuer,subject,identity_id) VALUES ('{REALM}','{old_subject}','{old_bank_id}'); COMMIT;"),
    text=True, capture_output=True, check=True, timeout=30)
admin_environment = dict(os.environ, KEYCLOAK_ADMIN_TOKEN=admin)
for _ in range(2):
    subprocess.run([sys.executable, "-B", "sso-service/apply-phone-registration.py", "--admin-base", ADMIN, "--apply"],
        env=admin_environment, check=True, timeout=60)


class Callback(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        self.server.callback_query = urllib.parse.parse_qs(urllib.parse.urlsplit(self.path).query)
        self.send_response(200)
        self.end_headers()
        self.wfile.write(b"Authorization received")

    def log_message(self, *args):
        pass  # Never log authorization codes.


server = http.server.ThreadingHTTPServer(("127.0.0.1", 18765), Callback)
threading.Thread(target=server.serve_forever, daemon=True).start()


def authorize(page, client_id, prompt=None, login=None, registration=None, reject_registration=False):
    callback = callbacks[client_id]
    verifier = secrets.token_urlsafe(32)
    state = secrets.token_urlsafe(32)
    query = {"client_id": client_id, "response_type": "code", "scope": "openid",
             "redirect_uri": callback, "state": state, "nonce": secrets.token_urlsafe(32),
             "code_challenge_method": "S256", "code_challenge": base64.urlsafe_b64encode(
                 hashlib.sha256(verifier.encode()).digest()).rstrip(b"=").decode()}
    if prompt:
        query["prompt"] = prompt
    server.callback_query = None
    page.goto(discovery["authorization_endpoint"] + "?" + urllib.parse.urlencode(query))
    if registration is not None:
        page.locator("#kc-registration a").click()
        assert page.get_by_label("UAE mobile number (+971)", exact=False).count() == 1
        assert page.locator("select[name=country], select[name=countryCode]").count() == 0
        page.locator("#username").fill(registration)
        page.locator("#email").fill("ci-" + secrets.token_hex(8) + "@example.test")
        page.locator("#firstName").fill("Synthetic")
        page.locator("#lastName").fill("Identity")
        page.locator("#password").fill(password)
        page.locator("#password-confirm").fill(password)
        # Bypass browser required/pattern validation to exercise Keycloak itself.
        page.locator("#kc-register-form").evaluate("form => form.noValidate = true")
        page.locator("#kc-register-form input[type=submit], #kc-register-form button[type=submit]").click()
        if reject_registration:
            page.locator("#input-error-username").wait_for(state="visible")
            assert server.callback_query is None, "Invalid registration must not authorize a user"
            return None
    elif page.locator("#username").count():
        assert prompt != "none", "SSO session was not reused"
        page.locator("#username").fill(login[0] if login else username)
        page.locator("#password").fill(login[1] if login else password)
        page.locator("#kc-login").click()
    if page.locator("#kc-update-profile-form").count():
        page.locator("#kc-update-profile-form input[type=submit], #kc-update-profile-form button[type=submit]").click()
    page.wait_for_url(callback + "**", timeout=30000)
    received = server.callback_query
    assert received and received.get("state") == [state] and "error" not in received
    return received["code"][0], verifier


def redeem(code, verifier, client_id, redirect_uri=None):
    return request("POST", discovery["token_endpoint"], {"client_id": client_id,
        "grant_type": "authorization_code", "redirect_uri": redirect_uri or callbacks[client_id],
        "code": code, "code_verifier": verifier}, form=True)


try:
    with sync_playwright() as playwright:
        browser = playwright.chromium.launch()
        legacy_context = browser.new_context(ignore_https_errors=True)
        legacy_code, legacy_verifier = authorize(legacy_context.new_page(), "armoney-android", login=(old_username, old_password))
        legacy_oidc = expect(200, redeem(legacy_code, legacy_verifier, "armoney-android"))
        legacy_token = expect(200, request("POST", PUBLIC + "/v1/auth/sso", {"access_token": legacy_oidc["access_token"]}))["access_token"]
        assert expect(200, request("GET", PUBLIC + "/v1/auth/me", token=legacy_token))["id"] == old_bank_id
        expect(204, request("POST", PUBLIC + "/v1/auth/logout", token=legacy_token))
        legacy_context.close()
        # Only this disposable browser bypasses trust installation. HTTP assertions use the exported CA.
        context = browser.new_context(ignore_https_errors=True)
        page = context.new_page()
        for invalid_phone in ("", "+971511234567", "+12025550123", "0501234567", "+97150123456"):
            authorize(page, "armoney-ios", registration=invalid_phone, reject_registration=True)
        registration_code, registration_verifier = authorize(page, "armoney-ios", registration=username)
        expect(200, redeem(registration_code, registration_verifier, "armoney-ios"))
        duplicates = browser.new_context(ignore_https_errors=True)
        authorize(duplicates.new_page(), "armoney-ios", registration=username, reject_registration=True)
        duplicates.close()
        registered = expect(200, request("GET", ADMIN + "/admin/realms/armoney/users?" +
            urllib.parse.urlencode({"username": username, "exact": "true"}), token=admin))
        assert len(registered) == 1, "Duplicate phone registration must not create another provider identity"
        # auth-smoke reserved this number in legacy auth. A provider registration
        # cannot link to or create a second active bank identity for that phone.
        collision_context = browser.new_context(ignore_https_errors=True)
        collision_code, collision_verifier = authorize(collision_context.new_page(), "armoney-android", registration="+971580000001")
        collision_oidc = expect(200, redeem(collision_code, collision_verifier, "armoney-android"))
        expect(409, request("POST", PUBLIC + "/v1/auth/sso", {"access_token": collision_oidc["access_token"]}))
        collision_context.close()
        sessions = {}
        principal_id = None
        for client_id in NATIVE_CLIENTS:
            # Android reuses the browser session established by iOS without another login.
            code, verifier = authorize(page, client_id, prompt="none" if sessions else None)
            expect(400, redeem(code, secrets.token_urlsafe(32), client_id))
            code, verifier = authorize(page, client_id, prompt="none")
            oidc = expect(200, redeem(code, verifier, client_id))
            introspection = expect(200, request("POST", ADMIN + "/realms/armoney/protocol/openid-connect/token/introspect", {
                "client_id": "armoney-auth", "client_secret": os.environ["SSO_CLIENT_SECRET"],
                "token": oidc["access_token"], "token_type_hint": "access_token"}, form=True))
            assert introspection["active"] and introspection["phone_number"] == username
            assert introspection.get("phone_number_verified") is not True
            expect(400, request("POST", discovery["token_endpoint"], {
                "client_id": client_id, "grant_type": "password", "username": username,
                "password": password}, form=True))
            session = expect(200, request("POST", PUBLIC + "/v1/auth/sso", {"access_token": oidc["access_token"]}))
            token = session["access_token"]
            principal = expect(200, request("GET", PUBLIC + "/v1/auth/me", token=token))
            assert principal["email"] is None
            if principal_id is None:
                principal_id = principal["id"]
            assert principal["id"] == principal_id, "Native clients must map one provider subject to one bank identity"
            again = expect(200, request("POST", PUBLIC + "/v1/auth/sso", {"access_token": oidc["access_token"]}))
            assert expect(200, request("GET", PUBLIC + "/v1/auth/me", token=again["access_token"]))["id"] == principal_id
            expect(401, request("POST", PUBLIC + "/v1/auth/sso", {"access_token": oidc["id_token"]}))
            expect(200, request("PUT", PUBLIC + "/v1/users/me", {"display_name": "ArMoney CI"}, token))
            wallets = request("POST", PUBLIC + "/v1/wallets", {"currency": "AED"}, token)
            assert wallets[0] in (200, 202) and wallets[1]["owner_id"] == principal_id
            sessions[client_id] = (code, verifier, oidc, token)
        expect(401, request("POST", PUBLIC + "/v1/auth/sso", {"access_token": "invalid"}))

        second_name = "+971509990002"
        second_password = secrets.token_urlsafe(24)
        expect(201, request("POST", ADMIN + "/admin/realms/armoney/users", {
            "username": second_name, "email": "ci-" + secrets.token_hex(8) + "@example.test", "emailVerified": True,
            "firstName": "Second", "lastName": "Identity", "enabled": True,
            "credentials": [{"type": "password", "value": second_password, "temporary": False}]}, admin))
        second_context = browser.new_context(ignore_https_errors=True)
        second_page = second_context.new_page()
        second_id = None
        for client_id in NATIVE_CLIENTS:
            second_code, second_verifier = authorize(second_page, client_id,
                prompt="none" if second_id else None, login=(second_name, second_password))
            second_oidc = expect(200, redeem(second_code, second_verifier, client_id))
            second_session = expect(200, request("POST", PUBLIC + "/v1/auth/sso", {"access_token": second_oidc["access_token"]}))["access_token"]
            second_identity = expect(200, request("GET", PUBLIC + "/v1/auth/me", token=second_session))
            assert second_identity["id"] != principal_id
            if second_id is None:
                second_id = second_identity["id"]
            assert second_identity["id"] == second_id
            assert expect(200, request("GET", PUBLIC + "/v1/wallets", token=second_session))["wallets"] == []
        second_context.close()

        for client_id, (code, verifier, oidc, token) in sessions.items():
            expect(204, request("POST", PUBLIC + "/v1/auth/logout", token=token))
            expect(401, request("GET", PUBLIC + "/v1/auth/me", token=token))
            # Replay may revoke the shared provider session, so destructive cases follow both clients' acceptance.
            expect(400, redeem(code, verifier, client_id))
            expect(401, request("POST", PUBLIC + "/v1/auth/sso", {"access_token": oidc["access_token"]}))

        for client_id in NATIVE_CLIENTS:
            other_client = next(candidate for candidate in NATIVE_CLIENTS if candidate != client_id)
            code, verifier = authorize(page, client_id)
            # Even the correct verifier/redirect cannot redeem a code under another public client.
            expect(400, redeem(code, verifier, other_client, callbacks[client_id]))
            code, verifier = authorize(page, client_id)
            expect(400, redeem(code, verifier, client_id, callbacks[other_client]))
        context.close()
        browser.close()
finally:
    server.shutdown()
    server.server_close()

print("Real Keycloak required/invalid/duplicate UAE phone registration, openid-only phone introspection, iOS/Android SSO, PKCE, client/redirect binding, replay rejection, shared identity, owner isolation and local logout passed")
