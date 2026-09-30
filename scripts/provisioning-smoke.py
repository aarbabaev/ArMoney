"""CI-only wallet/ledger recovery acceptance against disposable Compose services."""
import concurrent.futures
import json
import os
import subprocess
import time
import urllib.error
import urllib.request
import uuid

if os.environ.get("CI") != "true":
    raise SystemExit("This test stops/restarts services and runs only in disposable CI.")

KEY = os.environ["INTERNAL_AUTH_KEY"]
BASE = "http://localhost:8080"


def request(method, path, body=None, token=None):
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = "Bearer " + token
    req = urllib.request.Request(BASE + path, method=method, headers=headers,
        data=None if body is None else json.dumps(body).encode())
    try:
        response = urllib.request.urlopen(req, timeout=10)
    except urllib.error.HTTPError as error:
        response = error
    with response:
        raw = response.read()
        return response.status, json.loads(raw) if raw else None


def expect(code, result):
    assert result[0] == code, f"Expected HTTP {code}, got {result[0]}"
    return result[1]


def identity():
    body = {"email": str(uuid.uuid4()) + "@example.test", "password": "synthetic provisioning password"}
    expect(202, request("POST", "/v1/auth/register", body))
    token = expect(200, request("POST", "/v1/auth/login", body))["access_token"]
    owner = expect(200, request("GET", "/v1/auth/me", token=token))["id"]
    return token, owner


def compose(*args):
    subprocess.run(["docker", "compose", *args], check=True, capture_output=True,
                   text=True, timeout=90)


def await_wallet(token, wallet_id, state, timeout=150):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        try:
            code, result = request("GET", "/v1/wallets", token=token)
            if code == 200:
                matches = [w for w in result["wallets"] if w["id"] == wallet_id]
                assert len(matches) <= 1
                if matches and matches[0]["provisioning_status"] == state:
                    return matches[0]
        except (urllib.error.URLError, TimeoutError):
            pass
        time.sleep(1)
    raise AssertionError("Wallet did not reach " + state + " within the recovery deadline")


def ledger_account(account_id, owner):
    account_id = str(uuid.UUID(account_id))
    config = ['url = "http://ledger-service:8080/v1/ledger/accounts/' + account_id + '"',
              "header = " + json.dumps("X-Service-Key: " + KEY),
              "header = " + json.dumps("X-Identity-Id: " + owner)]
    result = subprocess.run(["docker", "run", "--rm", "-i", "--network", "arman-bank_bank",
        "curlimages/curl:8.16.0", "--silent", "--show-error", "--max-time", "10",
        "--write-out", "\\n%{http_code}", "--config", "-"],
        input="\n".join(config), text=True, capture_output=True, check=True, timeout=45)
    body, status = result.stdout.rsplit("\n", 1)
    return int(status), json.loads(body)


alice, owner = identity()
bob, other_owner = identity()
try:
    compose("stop", "ledger-service")
    with concurrent.futures.ThreadPoolExecutor(max_workers=6) as executor:
        results = list(executor.map(lambda _: request("POST", "/v1/wallets", {"currency": "EUR"}, alice), range(12)))
    wallets = [expect(202, result) for result in results]
    assert len({w["id"] for w in wallets}) == 1
    wallet = wallets[0]
    assert wallet["owner_id"] == owner and wallet["status"] == "ACTIVE"
    assert all(w["provisioning_status"] == "PENDING" and w["ledger_account_id"] is None for w in wallets)
    # Give the worker time to observe the outage, then lose its in-memory state.
    time.sleep(3)
    compose("restart", "wallet-service")
    pending = await_wallet(alice, wallet["id"], "PENDING")
    assert pending["ledger_account_id"] is None
    assert expect(200, request("GET", "/v1/wallets", token=bob))["wallets"] == []
finally:
    compose("start", "ledger-service")

ready = await_wallet(alice, wallet["id"], "READY")
assert ready["id"] == wallet["id"] and ready["status"] == "ACTIVE"
account = expect(200, ledger_account(ready["ledger_account_id"], owner))
assert (account["wallet_id"], account["owner_id"], account["currency"], account["balance_minor"]) == (wallet["id"], owner, "EUR", 0)
expect(404, ledger_account(ready["ledger_account_id"], other_owner))
assert expect(200, request("POST", "/v1/wallets", {"currency": "EUR"}, alice)) == ready
# Locally generated/validated UUID only; inspect a disposable test DB, never an application cross-DB dependency.
wallet_id = str(uuid.UUID(wallet["id"]))
count = subprocess.run(["docker", "compose", "exec", "-T", "ledger-db", "psql", "-U", "bank", "-d", "bank",
    "-At", "-v", "ON_ERROR_STOP=1"], input=f"select count(*) from accounts where wallet_id = '{wallet_id}';\n",
    text=True, capture_output=True, check=True, timeout=30)
assert count.stdout.strip() == "1"
print("Wallet pending/outage, concurrent retries, restart recovery, unique zero-balance ledger mapping and owner isolation passed")
