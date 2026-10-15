"""Real Angular/Keycloak/gateway acceptance; disposable CI only, no funding."""
import json
import os
import secrets
import ssl
import time
import urllib.error
import urllib.parse
import urllib.request


def require(condition, message):
    if not condition:
        raise AssertionError(message)


# Mirror the runner's isolation boundary before any admin or banking request.
require(os.environ.get("CI") == "true", "Disposable CI required")
require(os.environ.get("ARMONEY_DISPOSABLE_CI") == "true", "Disposable CI marker required")
require(os.environ.get("DOCKER_HOST") == "unix:///run/inner-docker/docker.sock", "Dedicated CI daemon required")
require(os.environ.get("GITHUB_WORKSPACE", "").startswith("/work/"), "Isolated workspace required")
require(not os.path.exists(".env") and not os.path.exists("compose.override.yaml"), "Local overrides forbidden")

from playwright.sync_api import sync_playwright, expect

PUBLIC = "https://localhost:8443"
ADMIN = "http://127.0.0.1:9090/sso"
TLS_CA_FILE = os.path.abspath(os.environ["SSO_CA_FILE"])
TLS = ssl.create_default_context(cafile=TLS_CA_FILE)
# Chromium uses the installed OS CA, but route.fetch uses Playwright's Node
# driver. Trust the same disposable root before that driver is started; never
# disable certificate or hostname verification for lost-response injection.
os.environ["NODE_EXTRA_CA_CERTS"] = TLS_CA_FILE


def request(method, path, body=None, token=None, admin=False, form=False, key=None):
    headers = {"Accept": "application/json"}
    if token:
        headers["Authorization"] = "Bearer " + token
    if key:
        headers["Idempotency-Key"] = key
    if body is not None:
        headers["Content-Type"] = "application/x-www-form-urlencoded" if form else "application/json"
        body = (urllib.parse.urlencode(body) if form else json.dumps(body)).encode()
    req = urllib.request.Request((ADMIN if admin else PUBLIC) + path, data=body, headers=headers, method=method)
    try:
        result = urllib.request.urlopen(req, context=TLS, timeout=20)
    except urllib.error.HTTPError as error:
        result = error
    with result:
        raw = result.read()
        return result.status, json.loads(raw) if raw and "application/json" in result.headers.get("Content-Type", "") else None


def status(code, result):
    require(result[0] == code, f"Expected HTTP {code}; observed {result[0]}")
    return result[1]


admin_token = status(200, request("POST", "/realms/master/protocol/openid-connect/token", {
    "client_id": "admin-cli", "grant_type": "password", "username": "admin",
    "password": os.environ["SSO_ADMIN_PASSWORD"]}, admin=True, form=True))["access_token"]
users = []
for phone in ("+971509990003", "+971509990004"):
    password = secrets.token_urlsafe(24)
    status(201, request("POST", "/admin/realms/armoney/users", {
        "username": phone, "email": "web-" + secrets.token_hex(8) + "@example.test",
        "emailVerified": True, "firstName": "Web", "lastName": "Acceptance", "enabled": True,
        "credentials": [{"type": "password", "value": password, "temporary": False}]}, admin_token, admin=True))
    users.append((phone, password))


def records(page):
    return page.evaluate("""async () => {
      const db = await new Promise((resolve,reject) => {
        const r=indexedDB.open('armoney-browser-v1',1); r.onsuccess=()=>resolve(r.result); r.onerror=()=>reject(Error('IDB unavailable'));
      });
      try { return await new Promise((resolve,reject) => {
        const out={}, r=db.transaction('records').objectStore('records').openCursor();
        r.onsuccess=()=>{const c=r.result; if(c){out[c.key]=c.value;c.continue();}else resolve(out);};
        r.onerror=()=>reject(Error('IDB read failed'));
      }); } finally { db.close(); }
    }""")


with sync_playwright() as playwright:
    browser = playwright.chromium.launch()
    # TLS must succeed using the CA installed by the disposable runner.
    context = browser.new_context()
    page = context.new_page()
    page.set_default_timeout(30000)
    sessions = []  # In-memory only; never print credentials or request bodies.

    def capture(response):
        if response.url == PUBLIC + "/v1/auth/sso" and response.status == 200:
            sessions.append(response.json()["access_token"])
        elif response.url == PUBLIC + "/sso/realms/armoney/protocol/openid-connect/token" and response.status == 200:
            tokens = response.json()
            sessions.extend(tokens[field] for field in ("access_token", "id_token", "refresh_token") if field in tokens)

    context.on("response", capture)

    def signed_in(tab, user, clear_cookies=False):
        if clear_cookies:
            context.clear_cookies()
        tab.goto(PUBLIC + "/home")
        expect(tab.get_by_role("button", name="Sign in securely", exact=False)).to_be_visible()
        tab.get_by_role("button", name="Sign in securely", exact=False).click()
        tab.locator("#username").fill(user[0])
        tab.locator("#password").fill(user[1])
        with tab.expect_response(lambda response: response.url == PUBLIC + "/v1/users/me"
                                 and response.request.method == "GET") as refreshed:
            tab.locator("#kc-login").click()
        require(refreshed.value.status in (200, 404), "Sign-in profile refresh must succeed or confirm no profile")
        require(refreshed.value.finished() is None, "Sign-in refresh response must finish")
        expect(tab.get_by_role("navigation", name="Main navigation")).to_be_visible()
        expect(tab.get_by_role("button", name="Refresh", exact=True)).to_be_enabled()
        # The final captured credential is the exchanged bank session, not OIDC.
        return sessions[-1]

    def navigate(tab, label):
        link = tab.get_by_role("navigation").get_by_role("link", name=label, exact=True)
        link.click()
        # RouterLinkActive applies aria-current after completed navigation. A
        # click returning alone does not establish that the new form is ready.
        expect(link).to_have_attribute("aria-current", "page")

    def open_account(tab, token):
        navigate(tab, "Accounts")
        # Bank.openWallet awaits refresh; users/me is its final network stage.
        # Register before the action so an initially enabled button cannot stand
        # in for completion of the refresh that follows the command response.
        with tab.expect_response(lambda response: response.url == PUBLIC + "/v1/users/me"
                                 and response.request.method == "GET") as refreshed:
            with tab.expect_response(lambda response: response.url == PUBLIC + "/v1/wallets"
                                     and response.request.method == "POST") as opened:
                tab.get_by_role("button", name="Open AED account", exact=True).click()
        require(opened.value.status in (200, 202), "Wallet creation must be accepted")
        require(refreshed.value.status in (200, 404), "Account profile refresh must succeed or confirm no profile")
        require(refreshed.value.finished() is None, "Account refresh response must finish")
        expect(tab.get_by_role("button", name="Refresh", exact=True)).to_be_enabled()
        wallets = status(200, request("GET", "/v1/wallets", token=token))["wallets"]
        require(len(wallets) == 1, "Fresh synthetic owner should have one wallet")
        wallet = wallets[0]
        for _ in range(30):
            if wallet["status"] == "ACTIVE" and wallet["provisioning_status"] == "READY":
                break
            with tab.expect_response(lambda response: response.url == PUBLIC + "/v1/users/me"
                                     and response.request.method == "GET") as refreshed:
                tab.get_by_role("button", name="Refresh", exact=True).click()
            require(refreshed.value.status in (200, 404), "Wallet polling profile refresh must succeed or confirm no profile")
            require(refreshed.value.finished() is None, "Wallet polling refresh response must finish")
            expect(tab.get_by_role("button", name="Refresh", exact=True)).to_be_enabled()
            wallet = status(200, request("GET", "/v1/wallets", token=token))["wallets"][0]
        require(wallet["provisioning_status"] == "READY", "Wallet must converge to READY")
        require(status(200, request("GET", f"/v1/wallets/{wallet['id']}/balance", token=token))["balance_minor"] == 0, "No fixture funding permitted")
        return wallet

    token_b = signed_in(page, users[1])
    owner_b = status(200, request("GET", "/v1/auth/me", token=token_b))["id"]
    wallet_b = open_account(page, token_b)
    # SSO claims a phone; a wallet does not initialize the separate profile
    # directory. Recipient resolution requires an initialized public profile.
    status(404, request("GET", "/v1/users/me", token=token_b))
    status(404, request("POST", "/v1/recipients/resolve",
        {"phone_number": users[1][0]}, token=token_b))
    recipient_profile = status(200, request("PUT", "/v1/users/me",
        {"display_name": "Web acceptance recipient"}, token=token_b))
    require(recipient_profile["identity_id"] == owner_b and recipient_profile["phone_number"] == users[1][0],
            "Recipient fixture must initialize its own registered profile")
    require(status(200, request("GET", "/v1/users/me", token=token_b)) == recipient_profile,
            "Recipient profile must persist before lookup acceptance")
    token_a = signed_in(page, users[0], clear_cookies=True)
    owner_a = status(200, request("GET", "/v1/auth/me", token=token_a))["id"]
    require(owner_a != owner_b, "Synthetic owners must differ")
    wallet_a = open_account(page, token_a)
    foreign = request("GET", f"/v1/wallets/{wallet_b['id']}/balance", token=token_a)
    require(foreign[0] in (403, 404), "Foreign-owner balance must be rejected")
    status(401, request("GET", "/v1/wallets"))

    navigate(page, "Profile")
    page.get_by_label("Display name", exact=True).fill("Web acceptance owner")
    expect(page.get_by_label("Display name", exact=True)).to_have_value("Web acceptance owner")
    profile_events = []

    def profile_response(response):
        path = urllib.parse.urlsplit(response.url).path
        if path in ("/v1/auth/me", "/v1/users/me", "/v1/wallets"):
            profile_events.append((response.request.method, path, response.status))
            del profile_events[:-20]

    page.on("response", profile_response)
    try:
        with page.expect_response(lambda response: response.url == PUBLIC + "/v1/users/me"
                                  and response.request.method == "PUT") as saved:
            page.get_by_role("button", name="Save name", exact=True).click()
    except Exception:
        # Only technical state: never values, alert text, URLs with queries or bodies.
        print("Profile submit diagnostic:", json.dumps({
            "responses": profile_events,
            "save_disabled": page.get_by_role("button", name="Save name", exact=True).is_disabled(),
            "refresh_disabled": page.get_by_role("button", name="Refresh", exact=True).is_disabled(),
            "input_valid": page.get_by_label("Display name", exact=True).evaluate("element => element.validity.valid"),
            "input_length": len(page.get_by_label("Display name", exact=True).input_value()),
            "alerts": page.get_by_role("alert").count(),
        }))
        raise
    finally:
        page.remove_listener("response", profile_response)
    require(saved.value.status == 200, "Profile update must succeed")
    expect(page.get_by_role("button", name="Refresh", exact=True)).to_be_enabled()
    require(status(200, request("GET", "/v1/users/me", token=token_a))["display_name"] == "Web acceptance owner", "Profile update must persist")
    expect(page.get_by_text(users[0][0], exact=True)).to_be_visible()

    navigate(page, "Accounts")
    page.locator("a.account").click()
    expect(page.get_by_role("heading", name="Internal wallet identifier", exact=True)).to_be_visible()
    expect(page.get_by_text("This identifier is not an IBAN", exact=False)).to_be_visible()
    expect(page.get_by_role("link", name="Send from this account", exact=True)).to_be_visible()

    navigate(page, "Demo cards")
    page.get_by_role("button", name="Create demonstration card", exact=True).click()
    expect(page.get_by_text("VIRTUAL · ACTIVE", exact=True)).to_be_visible()
    page.get_by_role("button", name="Freeze", exact=True).click()
    expect(page.get_by_text("VIRTUAL · FROZEN", exact=True)).to_be_visible()
    page.get_by_role("button", name="Unfreeze", exact=True).click()
    expect(page.get_by_text("VIRTUAL · ACTIVE", exact=True)).to_be_visible()
    page.get_by_role("button", name="Close card", exact=True).click()
    page.get_by_role("button", name="Confirm closure", exact=True).click()
    expect(page.get_by_text("VIRTUAL · CLOSED", exact=True)).to_be_visible()
    expect(page.get_by_role("button", name="Unfreeze", exact=True)).to_have_count(0)

    # Drop only the response AFTER the real gateway has committed its outcome.
    committed = []
    submitted = []

    def lose_response(route):
        if route.request.method != "POST":
            route.continue_()
            return
        submitted.append((route.request.headers.get("idempotency-key"), json.loads(route.request.post_data)))
        response = route.fetch()
        require(response.status in (200, 202), "Actual transfer endpoint must accept durable command")
        committed.append(response.json())
        route.abort("failed")

    page.route(PUBLIC + "/v1/payments", lose_response)
    navigate(page, "Transfers")
    page.get_by_label("Recipient UAE mobile number", exact=True).fill(users[1][0])
    with page.expect_response(lambda response: response.url == PUBLIC + "/v1/recipients/resolve"
                              and response.request.method == "POST") as resolved:
        page.get_by_role("button", name="Find recipient", exact=True).click()
    require(resolved.value.status == 200, f"Initialized recipient lookup must succeed; observed HTTP {resolved.value.status}")
    require(resolved.value.finished() is None, "Recipient lookup response must finish")
    expect(page.locator(".recipient")).to_contain_text(users[1][0])
    page.get_by_label("Amount (AED)", exact=True).fill("0.01")
    page.get_by_role("button", name="Review transfer", exact=True).click()
    expect(page.get_by_role("heading", name="Review and confirm", exact=True)).to_be_visible()
    page.get_by_role("button", name="Confirm transfer", exact=True).click()
    expect(page.get_by_role("button", name="Retry saved transfer safely", exact=True)).to_be_enabled()
    require(len(committed) == 1, "Lost-response injection must reach real backend exactly once")
    outcome = committed[0]
    # Creation may return PENDING before the durable payment worker runs.
    for _ in range(100):
        if outcome["status"] != "PENDING":
            break
        time.sleep(0.2)
        outcome = status(200, request("GET", "/v1/payments/" + outcome["id"], token=token_a))
    require(outcome["status"] == "REJECTED" and outcome["rejection_reason"] == "INSUFFICIENT_FUNDS", "Zero balance must reject without financial success")
    pending_scope = PUBLIC + "|" + owner_a + "|pending"
    saved_raw = records(page)[pending_scope]
    saved = json.loads(saved_raw)
    require(saved["attempted"] is True and saved["key"] == submitted[0][0] and saved["command"] == submitted[0][1], "Attempt must be durable with identical key and command")
    page.unroute(PUBLIC + "/v1/payments", lose_response)

    # Reload loses authentication, preserves uncertain command, and requires verification.
    page.reload()
    expect(page.get_by_role("button", name="Sign in securely", exact=False)).to_be_visible()
    require(records(page)[pending_scope] == saved_raw, "Reload must preserve immutable recovery")
    token_b = signed_in(page, users[1], clear_cookies=True)
    navigate(page, "Transfers")
    expect(page.get_by_role("button", name="Retry saved transfer safely", exact=True)).to_have_count(0)
    navigate(page, "Demo cards")
    expect(page.get_by_text("VIRTUAL · CLOSED", exact=True)).to_have_count(0)
    require(records(page)[pending_scope] == saved_raw, "Different owner must preserve other owner's recovery")
    token_a = signed_in(page, users[0], clear_cookies=True)
    navigate(page, "Transfers")
    expect(page.get_by_role("button", name="Retry saved transfer safely", exact=True)).to_be_enabled()

    # Same origin tabs share IndexedDB and Web Locks, but have independent sessions.
    second = context.new_page()
    second.set_default_timeout(30000)
    second.goto(PUBLIC + "/home")
    second.get_by_role("button", name="Sign in securely", exact=False).click()
    expect(second.get_by_role("navigation", name="Main navigation")).to_be_visible()
    expect(second.get_by_role("button", name="Refresh", exact=True)).to_be_enabled()
    navigate(second, "Transfers")
    expect(second.get_by_role("button", name="Retry saved transfer safely", exact=True)).to_be_enabled()
    replay_keys = []
    context.on("request", lambda req: replay_keys.append(req.headers.get("idempotency-key")) if req.url == PUBLIC + "/v1/payments" and req.method == "POST" else None)
    # Dispatch both clicks without waiting for either network completion.
    page.get_by_role("button", name="Retry saved transfer safely", exact=True).evaluate("button => button.click()")
    second.get_by_role("button", name="Retry saved transfer safely", exact=True).evaluate("button => button.click()")
    expect(page.get_by_role("button", name="Refresh", exact=True)).to_be_enabled()
    expect(second.get_by_role("button", name="Refresh", exact=True)).to_be_enabled()
    require(replay_keys and all(key == saved["key"] for key in replay_keys), "Concurrent recovery must retain original key")
    require(pending_scope not in records(page), "Authoritative terminal recovery should clear saved command")
    replay = status(200, request("POST", "/v1/payments", saved["command"], token_a, key=saved["key"]))
    require(replay["id"] == outcome["id"] and replay["status"] == "REJECTED", "Replay must return same durable result")
    history = status(200, request("GET", "/v1/payments/page?limit=100", token=token_a))["payments"]
    require(len(history) == 1 and history[0]["id"] == outcome["id"], "Concurrent retries must create exactly one payment")
    for token, wallet in ((token_a, wallet_a), (token_b, wallet_b)):
        require(status(200, request("GET", f"/v1/wallets/{wallet['id']}/balance", token=token))["balance_minor"] == 0, "Rejected transfer must preserve both balances")
    foreign = request("GET", "/v1/payments/" + outcome["id"], token=token_b)
    require(foreign[0] in (403, 404), "Recipient must not see rejected sender transfer")

    page.get_by_role("button", name="Refresh", exact=True).click()
    expect(page.get_by_role("button", name="Refresh", exact=True)).to_be_enabled()
    navigate(page, "Inbox")
    expect(page.get_by_role("button", name="Mark read", exact=True)).to_be_visible()
    page.get_by_role("button", name="Mark read", exact=True).click()
    expect(page.locator("article.notice")).to_contain_text("Read")
    notice = status(200, request("GET", "/v1/notifications/page?limit=30", token=token_a))["notifications"][0]
    require(notice["payment_id"] == outcome["id"] and notice["read_at"] is not None, "Notification read must persist against real payment")

    navigate(page, "Assistant")
    page.get_by_label("Your question", exact=True).fill("What is my balance?")
    with page.expect_response(lambda response: response.url == PUBLIC + "/v1/assistant/messages" and response.request.method == "POST") as assistant_response:
        page.get_by_role("button", name="Send question", exact=True).click()
    require(assistant_response.value.status == 503, "Disabled real assistant gateway must return unavailable")
    expect(page.get_by_role("alert")).to_contain_text("Assistant unavailable. No answer or live data could be confirmed.")
    expect(page.get_by_role("region", name="Assistant answer", exact=True)).to_have_count(0)
    durable = page.evaluate("({local:Object.values(localStorage),session:Object.values(sessionStorage)})")
    all_durable = json.dumps(durable) + json.dumps(records(page))
    require(not any(token in all_durable for token in sessions), "Bank credentials must not persist in browser storage")
    require("access_token" not in all_durable and "refresh_token" not in all_durable, "Provider credentials must not persist")
    navigate(page, "Profile")
    page.get_by_role("main").get_by_role("button", name="Sign out", exact=True).click()
    expect(page.get_by_role("button", name="Sign in securely", exact=False)).to_be_visible()
    status(401, request("GET", "/v1/auth/me", token=token_a))
    require(PUBLIC + "|" + owner_a + "|cards" in records(page), "Sign-out must preserve owner-scoped demo records")
    context.close()
    browser.close()

print("Real Angular SSO, wallet/profile/account/demo/inbox flows, rejected transfer lost-response recovery, cross-owner isolation, multi-tab same-key replay, unchanged balances and logout passed; protected assistant unavailable response verified without fabricated answers")
