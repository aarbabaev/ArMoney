"""Disposable synthetic identities; complete onboarding flow through the gateway."""
import json
import urllib.request
import urllib.error
import uuid

BASE = "http://localhost:8080"
def request(method, path, body=None, token=None, extra=None):
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = "Bearer " + token
    headers.update(extra or {})
    req = urllib.request.Request(BASE + path, data=None if body is None else json.dumps(body).encode(),
                                 headers=headers, method=method)
    try:
        response = urllib.request.urlopen(req, timeout=15)
    except urllib.error.HTTPError as error:
        response = error
    with response:
        raw = response.read()
        return response.status, json.loads(raw) if raw else None

def expect(status, result):
    assert result[0] == status, f"Expected {status}, got {result[0]}"
    return result[1]

def wallet_result(result):
    assert result[0] in (200, 202), f"Expected wallet status, got {result[0]}"
    wallet = result[1]
    assert wallet["provisioning_status"] in ("PENDING", "READY")
    assert (wallet["ledger_account_id"] is not None) == (wallet["provisioning_status"] == "READY")
    return wallet

def identity():
    body = {"email": str(uuid.uuid4()) + "@example.test", "password": "synthetic onboarding password"}
    expect(202, request("POST", "/v1/auth/register", dict(body, phone_number="+97154" + str(uuid.uuid4().int % 10**7).zfill(7))))
    token = expect(200, request("POST", "/v1/auth/login", body))["access_token"]
    owner = expect(200, request("GET", "/v1/auth/me", token=token))["id"]
    return token, owner

a, owner_a = identity()
b, owner_b = identity()
expect(401, request("GET", "/v1/wallets"))
expect(404, request("GET", "/v1/users/me", token=a))
profile = expect(200, request("PUT", "/v1/users/me", {"display_name": "Alice"}, a))
updated = expect(200, request("PUT", "/v1/users/me", {"display_name": "Alice Updated"}, a))
assert profile["id"] == updated["id"]
assert updated["identity_id"] == owner_a
expect(404, request("GET", "/v1/users/me", token=b))
wallet = wallet_result(request("POST", "/v1/wallets", {"currency": "AED"}, a,
    {"X-Identity-Id": owner_b, "X-User-Id": owner_b, "X-Service-Key": "forged"}))
assert wallet["owner_id"] == owner_a
retry = wallet_result(request("POST", "/v1/wallets", {"currency": "AED"}, a))
assert retry["id"] == wallet["id"]
assert len(expect(200, request("GET", "/v1/wallets", token=a))["wallets"]) == 1
assert expect(200, request("GET", "/v1/wallets", token=b))["wallets"] == []
expect(400, request("POST", "/v1/wallets", {"currency": "AED", "owner_id": owner_b}, a))
for unsupported in ("USD", "EUR", "GBP", "XYZ", "aed"):
    expect(400, request("POST", "/v1/wallets", {"currency": unsupported}, a))
assert wallet["currency"] == "AED"
expect(204, request("POST", "/v1/auth/logout", token=a))
expect(401, request("GET", "/v1/users/me", token=a))
expect(401, request("GET", "/v1/wallets", token=a))
expect(401, request("POST", "/v1/wallets", {"currency": "AED"}, a))
assert expect(200, request("GET", "/v1/wallets", token=b))["wallets"] == []
expect(204, request("POST", "/v1/auth/logout", token=b))
print("Profile update, wallet retries, owner isolation and session revocation passed through gateway")
