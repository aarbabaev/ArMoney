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
clients = expect(200, request("GET", ADMIN + "/admin/realms/armoney/clients?clientId=armoney-ios", token=admin))
client = clients[0]
callback = "http://127.0.0.1:18765/callback"
assert client["publicClient"] and client["standardFlowEnabled"] and not client["directAccessGrantsEnabled"]
# Exact loopback redirect is added only to this disposable realm, never to the shipped mobile client.
client["redirectUris"].append(callback)
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


def authorize(page, prompt=None, login=None):
    verifier = secrets.token_urlsafe(32)
    state = secrets.token_urlsafe(32)
    query = {"client_id": "armoney-ios", "response_type": "code", "scope": "openid",
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


def redeem(code, verifier):
    return request("POST", discovery["token_endpoint"], {"client_id": "armoney-ios",
        "grant_type": "authorization_code", "redirect_uri": callback,
        "code": code, "code_verifier": verifier}, form=True)


try:
    with sync_playwright() as playwright:
        browser = playwright.chromium.launch()
        # Only this disposable browser bypasses trust installation. HTTP assertions use the exported CA.
        context = browser.new_context(ignore_https_errors=True)
        page = context.new_page()
        code, verifier = authorize(page)
        expect(400, redeem(code, secrets.token_urlsafe(32)))
        code, verifier = authorize(page, prompt="none")
        oidc = expect(200, redeem(code, verifier))
        expect(400, request("POST", discovery["token_endpoint"], {
            "client_id": "armoney-ios", "grant_type": "password", "username": username,
            "password": password}, form=True))
        session = expect(200, request("POST", PUBLIC + "/v1/auth/sso", {"access_token": oidc["access_token"]}))
        token = session["access_token"]
        principal = expect(200, request("GET", PUBLIC + "/v1/auth/me", token=token))
        assert principal["email"] is None
        again = expect(200, request("POST", PUBLIC + "/v1/auth/sso", {"access_token": oidc["access_token"]}))
        assert expect(200, request("GET", PUBLIC + "/v1/auth/me", token=again["access_token"]))["id"] == principal["id"]
        expect(401, request("POST", PUBLIC + "/v1/auth/sso", {"access_token": "invalid"}))
        expect(200, request("PUT", PUBLIC + "/v1/users/me", {"display_name": "ArMoney CI"}, token))
        wallets = request("POST", PUBLIC + "/v1/wallets", {"currency": "EUR"}, token)
        assert wallets[0] in (200, 202) and wallets[1]["owner_id"] == principal["id"]
        second_name = "ci-" + secrets.token_hex(8)
        second_password = secrets.token_urlsafe(24)
        expect(201, request("POST", ADMIN + "/admin/realms/armoney/users", {
            "username": second_name, "email": second_name + "@example.test", "emailVerified": True,
            "firstName": "Second", "lastName": "Identity", "enabled": True,
            "credentials": [{"type": "password", "value": second_password, "temporary": False}]}, admin))
        second_context = browser.new_context(ignore_https_errors=True)
        second_page = second_context.new_page()
        second_code, second_verifier = authorize(second_page, login=(second_name, second_password))
        second_oidc = expect(200, redeem(second_code, second_verifier))
        second_session = expect(200, request("POST", PUBLIC + "/v1/auth/sso", {"access_token": second_oidc["access_token"]}))["access_token"]
        second_identity = expect(200, request("GET", PUBLIC + "/v1/auth/me", token=second_session))
        assert second_identity["id"] != principal["id"]
        assert expect(200, request("GET", PUBLIC + "/v1/wallets", token=second_session))["wallets"] == []
        second_context.close()
        expect(204, request("POST", PUBLIC + "/v1/auth/logout", token=token))
        expect(401, request("GET", PUBLIC + "/v1/auth/me", token=token))
        # Keycloak revokes the provider session on code replay. Run this destructive
        # negative case after valid-session acceptance, then assert fail-closed exchange.
        expect(400, redeem(code, verifier))
        expect(401, request("POST", PUBLIC + "/v1/auth/sso", {"access_token": oidc["access_token"]}))
        context.close()
        browser.close()
finally:
    server.shutdown()
    server.server_close()

print("Real Keycloak browser SSO, PKCE, replay rejection, identity mapping, profile/wallet and local logout passed")
