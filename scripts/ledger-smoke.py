"""CI-only private ledger acceptance with explicit balanced synthetic funding."""
import json
import os
import subprocess
import uuid
import urllib.request
import urllib.error

if os.environ.get("CI") != "true":
    raise SystemExit("This test funds synthetic accounts and runs only in disposable CI databases.")
key = os.environ["INTERNAL_AUTH_KEY"]

def call(method, path, owner, body=None, authenticated=True):
    config = ['url = "http://ledger-service:8080' + path + '"', 'request = ' + json.dumps(method)]
    config.append("header = " + json.dumps("X-Identity-Id: " + owner))
    if authenticated:
        config.append("header = " + json.dumps("X-Service-Key: " + key))
    if body is not None:
        config.append('header = "Content-Type: application/json"')
        config.append("data = " + json.dumps(json.dumps(body)))
    result = subprocess.run(["docker", "run", "--rm", "-i", "--network", "arman-bank_bank",
        "curlimages/curl:8.16.0", "--silent", "--show-error", "--max-time", "10",
        "--write-out", "\\n%{http_code}", "--config", "-"],
        input="\n".join(config), capture_output=True, text=True, timeout=45, check=True)
    payload, status = result.stdout.rsplit("\n", 1)
    return int(status), json.loads(payload)

def expect(code, result):
    assert result[0] == code, f"Expected HTTP {code}, got {result[0]}"
    return result[1]

alice, bob = str(uuid.uuid4()), str(uuid.uuid4())
body = {"wallet_id": str(uuid.uuid4()), "currency": "EUR"}
expect(401, call("POST", "/v1/ledger/accounts", alice, body, False))
a = expect(200, call("POST", "/v1/ledger/accounts", alice, body))
assert expect(200, call("POST", "/v1/ledger/accounts", alice, body))["id"] == a["id"]
b = expect(200, call("POST", "/v1/ledger/accounts", bob, {"wallet_id": str(uuid.uuid4()), "currency": "EUR"}))
reserve, reserve_wallet, funding = (str(uuid.uuid4()) for _ in range(3))
# All interpolated fields below are locally generated UUIDs or validated UUID API results.
account_a = str(uuid.UUID(a["id"]))
sql = f"""
BEGIN;
INSERT INTO accounts(id,wallet_id,currency,account_kind) VALUES ('{reserve}','{reserve_wallet}','EUR','CLEARING');
INSERT INTO transfers(payment_id,debit_account_id,credit_account_id,currency,amount_minor)
VALUES ('{funding}','{reserve}','{account_a}','EUR',1000);
COMMIT;
"""
subprocess.run(["docker", "compose", "exec", "-T", "ledger-db", "psql", "-U", "bank", "-d", "bank",
    "-v", "ON_ERROR_STOP=1"], input=sql, text=True, capture_output=True, check=True, timeout=30)
command = {"payment_id":str(uuid.uuid4()),"debit_account_id":a["id"],"credit_account_id":b["id"],
           "currency":"EUR","amount_minor":250}
posted = expect(200, call("POST", "/v1/ledger/transfers", alice, command))
assert posted["outcome"] == "POSTED"
assert expect(200, call("POST", "/v1/ledger/transfers", alice, command)) == posted
assert expect(200, call("GET", "/v1/ledger/accounts/" + a["id"], alice))["balance_minor"] == 750
assert expect(200, call("GET", "/v1/ledger/accounts/" + b["id"], bob))["balance_minor"] == 250
expect(404, call("GET", "/v1/ledger/accounts/" + a["id"], bob))
assert expect(200, call("GET", "/v1/ledger/transfers/" + command["payment_id"], alice)) == posted
expect(404, call("GET", "/v1/ledger/transfers/" + command["payment_id"], bob))
expect(409, call("POST", "/v1/ledger/transfers", alice, dict(command, amount_minor=251)))
declined = expect(409, call("POST", "/v1/ledger/transfers", alice,
    dict(command, payment_id=str(uuid.uuid4()), amount_minor=1000)))
assert declined["outcome"] == "INSUFFICIENT_FUNDS"
try:
    urllib.request.urlopen("http://localhost:8080/v1/ledger/accounts/" + a["id"], timeout=10)
    raise AssertionError("Private ledger must not be routed by gateway")
except urllib.error.HTTPError as error:
    assert error.code == 404
print("Private ledger account, balanced posting, retry, overdraft rejection and ownership checks passed")
