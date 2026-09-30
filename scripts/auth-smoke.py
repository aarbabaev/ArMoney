"""End-to-end auth smoke test for the disposable CI Compose environment."""
import json
import urllib.request
import urllib.error
import uuid

origin = "http://localhost:8080"
email = f"smoke-{uuid.uuid4()}@example.test"
password = "smoke-only-long-password"

def request(method, path, body=None, token=None):
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = "Bearer " + token
    req = urllib.request.Request(origin + path, data=None if body is None else json.dumps(body).encode(),
                                 headers=headers, method=method)
    try:
        response = urllib.request.urlopen(req, timeout=10)
    except urllib.error.HTTPError as error:
        response = error
    with response:
        raw = response.read()
        assert response.headers.get("Cache-Control") == "no-store"
        return response.status, json.loads(raw) if raw else None

credentials = {"email": email, "password": password}
assert request("POST", "/v1/auth/register", credentials)[0] == 202
assert request("POST", "/v1/auth/register", credentials)[0] == 202
assert request("POST", "/v1/auth/login", {"email": email, "password": "incorrect-long-password"})[0] == 401
status, session = request("POST", "/v1/auth/login", credentials)
assert status == 200
token = session["access_token"]
assert session["expires_in"] == 1800
status, identity = request("GET", "/v1/auth/me", token=token)
assert status == 200 and identity["email"] == email
assert request("GET", "/v1/auth/me")[0] == 401
assert request("POST", "/v1/auth/logout", token=token)[0] == 204
assert request("GET", "/v1/auth/me", token=token)[0] == 401
print("Registration, login, protected identity and logout passed through gateway")
