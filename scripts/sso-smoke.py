"""Real browser authorization-code/PKCE acceptance, disposable CI only."""
import base64
import hashlib
import http.server
import json
import os
import secrets
import ssl
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
username = "ci-" + secrets.token_hex(8)
password = secrets.token_urlsafe(24)
expect(201, request("POST", ADMIN + "/admin/realms/armoney/users", {
    "username": username, "email": username + "@example.test", "emailVerified": True,
    "firstName": "Synthetic", "lastName": "Identity", "enabled": True,
    "credentials": [{"type": "password", "value": password, "temporary": False}]}, admin))


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


def authorize(page, client_id, prompt=None, login=None):
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
    if page.locator("#username").count():
        assert prompt != "none", "SSO session was not reused"
        page.locator("#username").fill(login[0] if login else username)
        page.locator("#password").fill(login[1] if login else password)
        page.locator("#kc-login").click()
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
        # Only this disposable browser bypasses trust installation. HTTP assertions use the exported CA.
        context = browser.new_context(ignore_https_errors=True)
        page = context.new_page()
        sessions = {}
        principal_id = None
        for client_id in NATIVE_CLIENTS:
            # Android reuses the browser session established by iOS without another login.
            code, verifier = authorize(page, client_id, prompt="none" if sessions else None)
            expect(400, redeem(code, secrets.token_urlsafe(32), client_id))
            code, verifier = authorize(page, client_id, prompt="none")
            oidc = expect(200, redeem(code, verifier, client_id))
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
            wallets = request("POST", PUBLIC + "/v1/wallets", {"currency": "EUR"}, token)
            assert wallets[0] in (200, 202) and wallets[1]["owner_id"] == principal_id
            sessions[client_id] = (code, verifier, oidc, token)
        expect(401, request("POST", PUBLIC + "/v1/auth/sso", {"access_token": "invalid"}))

        second_name = "ci-" + secrets.token_hex(8)
        second_password = secrets.token_urlsafe(24)
        expect(201, request("POST", ADMIN + "/admin/realms/armoney/users", {
            "username": second_name, "email": second_name + "@example.test", "emailVerified": True,
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

print("Real Keycloak iOS/Android browser SSO, PKCE, client/redirect binding, replay rejection, shared identity, owner isolation and local logout passed")
