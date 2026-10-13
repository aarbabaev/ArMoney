"""Local synthetic fixtures: offline plan by default; no funding or database writes.

Apply needs SEED_TEST_USER_PASSWORD, KEYCLOAK_ADMIN_TOKEN, --ca-file and an
outside-checkout --manifest path. Optional KEYCLOAK_ADMIN_REFRESH_TOKEN enables
one in-memory admin-cli refresh/retry on HTTP 401; tokens are never persisted.
Existing identities require a prior manifest;
unknown collisions stop without adopting users or changing their credentials.
Native login uses Keycloak's HTML form/cookies and authorization code with PKCE.
Required actions (including email verification) stop provisioning unchanged.
"""
import argparse
from contextlib import contextmanager
import base64
import hashlib
import http.cookiejar
from html.parser import HTMLParser
import ipaddress
import json
import os
from pathlib import Path
import secrets
import ssl
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.parse as url
import urllib.request
import uuid

NAMESPACE = "armoney-local-demo-v1"
CLIENT = "armoney-android"
CALLBACK = "com.armoney.android:/oauth/callback"


class Blocked(Exception):
    """Safe diagnostic, never include remote bodies, URLs with codes or secrets."""


def require(condition, message):
    if not condition:
        raise Blocked(message)


def fixture(n):
    require(100 <= n <= 999, "Fixture ordinal outside reserved range")
    return {"username": f"+971528000{n}", "email": f"aarbabaev{n}@test.ae",
            "firstName": "Arman", "lastName": f"Arbabaev {n}"}


def local_origin(value, admin=False):
    p = url.urlsplit(value)
    require(not p.username and not p.password and not p.query and not p.fragment,
            "Origin must not contain credentials, query or fragment")
    require(p.scheme in (("http", "https") if admin else ("https",)), "HTTPS required")
    host = p.hostname
    allowed = host == "localhost"
    if not allowed:
        try:
            ip = ipaddress.ip_address(host or "")
            allowed = ip.is_loopback if admin else (ip.is_loopback or any(ip in network for network in
                [ipaddress.ip_network("10.0.0.0/8"), ipaddress.ip_network("172.16.0.0/12"),
                 ipaddress.ip_network("192.168.0.0/16")] if ip.version == network.version))
        except ValueError:
            pass
    require(allowed, "Only explicit local addresses are allowed (admin must be loopback)")
    return value.rstrip("/")


def provider_location(value, issuer):
    p, expected = url.urlsplit(value), url.urlsplit(issuer)
    require((p.scheme, p.netloc) == (expected.scheme, expected.netloc)
            and p.path.startswith(expected.path + "/") and not p.fragment,
            "Unexpected provider destination")
    return value


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


class Http:
    def __init__(self, ca_file):
        self.opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect(),
            urllib.request.HTTPSHandler(context=ssl.create_default_context(cafile=ca_file)),
            urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))

    def raw(self, method, address, data=None, token=None, form=False):
        headers = {}
        if token:
            headers["Authorization"] = "Bearer " + token
        if data is not None:
            headers["Content-Type"] = "application/x-www-form-urlencoded" if form else "application/json"
            data = (url.urlencode(data) if form else json.dumps(data)).encode()
        try:
            response = self.opener.open(urllib.request.Request(address, data, headers, method=method), timeout=30)
        except urllib.error.HTTPError as error:
            response = error
        with response:
            body = response.read(1048577)
            require(len(body) <= 1048576, "Response too large")
            return response.status, response.headers, body

    def json(self, method, address, data=None, token=None, statuses=(200,), form=False):
        status, _, body = self.raw(method, address, data, token, form)
        require(status in statuses, f"API request rejected (HTTP {status})")
        return json.loads(body) if body else None


class AdminHttp(Http):
    """Retry only a known unauthorized response; never replay uncertain writes."""
    def __init__(self, ca_file, admin, access_token, refresh_token=None):
        super().__init__(ca_file)
        self.admin = admin
        self.access_token = access_token
        self.refresh_token = refresh_token

    def raw(self, method, address, data=None, token=None, form=False):
        if token is None:
            return super().raw(method, address, data, form=form)
        require(address.startswith(self.admin + "/admin/realms/armoney/"),
                "Unexpected authenticated admin destination")
        response = super().raw(method, address, data, self.access_token, form)
        if response[0] != 401 or not self.refresh_token:
            return response
        status, _, body = super().raw("POST", self.admin + "/realms/master/protocol/openid-connect/token",
            {"client_id": "admin-cli", "grant_type": "refresh_token", "refresh_token": self.refresh_token}, form=True)
        require(status == 200, f"Admin token refresh rejected (HTTP {status})")
        refreshed = json.loads(body)
        access = refreshed.get("access_token")
        rotated = refreshed.get("refresh_token", self.refresh_token)
        require(isinstance(access, str) and bool(access) and isinstance(rotated, str) and bool(rotated),
                "Admin token refresh response invalid")
        self.access_token, self.refresh_token = access, rotated
        return super().raw(method, address, data, self.access_token, form)


class LoginForm(HTMLParser):
    def __init__(self):
        super().__init__()
        self.action = None
        self.inside = False
        self.fields = {}
        self.count = 0

    def handle_starttag(self, tag, attrs):
        attrs = dict(attrs)
        if tag == "form":
            self.inside = attrs.get("id") == "kc-form-login"
            if self.inside:
                self.count += 1
                require(attrs.get("method", "get").lower() == "post", "Unexpected login form method")
                self.action = attrs.get("action")
        elif tag == "input" and self.inside and attrs.get("type") == "hidden" and attrs.get("name"):
            self.fields[attrs["name"]] = attrs.get("value", "")

    def handle_endtag(self, tag):
        if tag == "form":
            self.inside = False


def callback_code(location, state, issuer):
    p = url.urlsplit(location)
    require(url.urlunsplit((p.scheme, p.netloc, p.path, "", "")) == CALLBACK and not p.fragment,
            "Unexpected authorization callback")
    query = url.parse_qs(p.query)
    require(query.get("state") == [state] and "error" not in query
            and len(query.get("code", [])) == 1, "Authorization state/code rejected")
    require("iss" not in query or query["iss"] == [issuer], "Authorization issuer mismatch")
    return query["code"][0]


def login(http, issuer, username, password):
    verifier, state, nonce = (secrets.token_urlsafe(32) for _ in range(3))
    params = {"client_id": CLIENT, "redirect_uri": CALLBACK, "response_type": "code", "scope": "openid",
              "state": state, "nonce": nonce, "code_challenge_method": "S256",
              "code_challenge": base64.urlsafe_b64encode(hashlib.sha256(verifier.encode()).digest()).rstrip(b"=").decode()}
    address = issuer + "/protocol/openid-connect/auth?" + url.urlencode(params)
    status, headers, body = http.raw("GET", address)
    for _ in range(5):
        if status not in (302, 303):
            break
        address = provider_location(url.urljoin(address, headers.get("Location", "")), issuer)
        status, headers, body = http.raw("GET", address)
    require(status == 200, "Provider login page unavailable")
    form = LoginForm()
    form.feed(body.decode("utf-8"))
    require(form.count == 1 and form.action, "Unsupported login form or required action")
    action = provider_location(url.urljoin(address, form.action), issuer)
    form.fields.update(username=username, password=password)
    status, headers, _ = http.raw("POST", action, form.fields, form=True)
    require(status in (302, 303), "Login blocked: credentials or required action; no reset attempted")
    code = callback_code(headers.get("Location", ""), state, issuer)
    return http.json("POST", issuer + "/protocol/openid-connect/token", {
        "client_id": CLIENT, "grant_type": "authorization_code", "redirect_uri": CALLBACK,
        "code": code, "code_verifier": verifier}, form=True)


def identifier(value):
    require(isinstance(value, str), "Missing identifier")
    require(str(uuid.UUID(value)) == value, "Noncanonical identifier")
    return value


def owner_only(path):
    if os.name == "nt":
        # Set an owner-only DACL before placing any manifest data in this file.
        script = ('$ErrorActionPreference="Stop"; $p=$env:SEED_MANIFEST_ACL_PATH; $a=New-Object System.Security.AccessControl.FileSecurity; '
                  '$a.SetAccessRuleProtection($true,$false); '
                  '$sid=[System.Security.Principal.WindowsIdentity]::GetCurrent().User; '
                  '$r=New-Object System.Security.AccessControl.FileSystemAccessRule($sid,"FullControl","Allow"); '
                  '$a.AddAccessRule($r); [System.IO.File]::SetAccessControl($p,$a)')
        subprocess.run(["powershell", "-NoProfile", "-NonInteractive", "-Command", script],
                       env=dict(os.environ, SEED_MANIFEST_ACL_PATH=str(path)), check=True, capture_output=True)
    else:
        os.chmod(path, 0o600)


def save_manifest(path, manifest):
    fd, temporary = tempfile.mkstemp(prefix=".seed-", dir=path.parent)
    os.close(fd)
    try:
        owner_only(temporary)
        Path(temporary).write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
        os.replace(temporary, path)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)


@contextmanager
def manifest_lock(path):
    """Serialize every manifest read, validation and write, including initial load."""
    lock = path.with_name(path.name + ".lock")
    try:
        descriptor = os.open(lock, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
    except FileExistsError:
        raise Blocked("Manifest is locked; confirm no seeder is running before removing stale lock")
    os.close(descriptor)
    try:
        yield
    finally:
        lock.unlink()


def ensure_user(http, admin, admin_token, n, password, previous):
    expected = fixture(n)
    endpoint = admin + "/admin/realms/armoney/users"
    lookup = endpoint + "?" + url.urlencode({"username": expected["username"], "exact": "true"})
    users = http.json("GET", lookup, token=admin_token)
    require(isinstance(users, list) and len(users) <= 1, "Ambiguous provider user")
    if users:
        user = users[0]
        require(previous is not None and previous.get("subject") == user.get("id"),
                "Existing identity lacks matching manifest; refusing adoption")
        require(all(user.get(key) == value for key, value in expected.items()) and user.get("enabled") is True,
                "Existing identity conflicts with fixture; refusing changes")
    else:
        require(previous is None, "Manifest identity missing; refusing recreation")
        http.json("POST", endpoint, dict(expected, enabled=True, emailVerified=False,
            credentials=[{"type": "password", "value": password, "temporary": False}]),
            token=admin_token, statuses=(201,))
        users = http.json("GET", lookup, token=admin_token)
        require(len(users) == 1, "Created user could not be resolved; manual reconciliation required")
        user = users[0]
    return identifier(user["id"])


def provision(http, gateway, oidc, n, previous):
    session = http.json("POST", gateway + "/v1/auth/sso", {"access_token": oidc["access_token"]})["access_token"]
    try:
        identity = identifier(http.json("GET", gateway + "/v1/auth/me", token=session)["id"])
        require(not previous.get("identity_id") or previous["identity_id"] == identity, "Bank identity changed")
        expected = fixture(n)
        profile = http.json("PUT", gateway + "/v1/users/me",
            {"display_name": expected["firstName"] + " " + expected["lastName"]}, token=session)
        require(profile["identity_id"] == identity and profile["phone_number"] == expected["username"],
                "Profile ownership or phone mismatch")
        wallet = http.json("POST", gateway + "/v1/wallets", {"currency": "AED"}, token=session, statuses=(200, 202))
        wallet_id = identifier(wallet["id"])
        require(not previous.get("wallet_id") or previous["wallet_id"] == wallet_id, "Wallet identity changed")
        for _ in range(30):
            require(wallet["owner_id"] == identity and wallet["currency"] == "AED" and wallet["status"] == "ACTIVE",
                    "Wallet ownership/currency/status mismatch")
            if wallet["provisioning_status"] == "READY":
                return dict(previous, identity_id=identity, profile_id=identifier(profile["id"]), wallet_id=wallet_id,
                            ledger_account_id=identifier(wallet["ledger_account_id"]), status="ready")
            time.sleep(1)
            wallets = http.json("GET", gateway + "/v1/wallets", token=session)["wallets"]
            matches = [item for item in wallets if item["id"] == wallet_id]
            require(len(matches) == 1, "Provisioning wallet disappeared")
            wallet = matches[0]
        raise Blocked("Wallet provisioning still pending; safe to rerun")
    finally:
        http.json("POST", gateway + "/v1/auth/logout", token=session, statuses=(204,))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apply", action="store_true")
    parser.add_argument("--count", type=int, default=200, help="Fixture count, 1..900 (default: 200)")
    parser.add_argument("--acknowledge-local-fixtures", action="store_true")
    parser.add_argument("--gateway", default="https://localhost:8443")
    parser.add_argument("--issuer")
    parser.add_argument("--admin-base", default="http://127.0.0.1:9090/sso")
    parser.add_argument("--ca-file")
    parser.add_argument("--manifest", type=Path)
    args = parser.parse_args()
    require(1 <= args.count <= 900, "Count must be between 1 and 900")
    if not args.apply:
        print(f"Plan: {args.count} synthetic Keycloak identities (ordinals 100..{99 + args.count}), complete profiles and AED wallets; no funding. No requests sent.")
        return
    require(args.acknowledge_local_fixtures, "Apply requires --acknowledge-local-fixtures")
    gateway = local_origin(args.gateway)
    issuer = local_origin(args.issuer or gateway + "/sso/realms/armoney")
    require(url.urlsplit(gateway).path == "" and url.urlsplit(issuer).path == "/sso/realms/armoney", "Unexpected gateway/realm path")
    admin = local_origin(args.admin_base, admin=True)
    require(url.urlsplit(admin).path == "/sso", "Unexpected admin path")
    require(args.ca_file and args.manifest, "Apply requires --ca-file and --manifest")
    path = args.manifest.parent.resolve() / args.manifest.name
    checkout = Path(__file__).resolve().parents[1]
    require(not path.is_relative_to(checkout) and path.parent.is_dir(), "Manifest must be outside checkout in an existing directory")
    password, admin_token = os.environ.get("SEED_TEST_USER_PASSWORD"), os.environ.get("KEYCLOAK_ADMIN_TOKEN")
    require(password and len(password) >= 15 and admin_token, "Required credential environment is missing/invalid")
    with manifest_lock(path):
        require(not path.is_symlink(), "Manifest must not be a symlink")
        manifest = {"namespace": NAMESPACE, "issuer": issuer, "gateway": gateway, "count": args.count, "users": {}}
        if path.exists():
            require(not path.is_symlink(), "Manifest must not be a symlink")
            owner_only(path)
            manifest = json.loads(path.read_text(encoding="utf-8"))
            require(manifest.get("namespace") == NAMESPACE and manifest.get("issuer") == issuer
                    and manifest.get("gateway") == gateway and manifest.get("count") == args.count
                    and isinstance(manifest.get("users"), dict), "Manifest target mismatch")
        save_manifest(path, manifest)
        admin_http = AdminHttp(args.ca_file, admin, admin_token, os.environ.get("KEYCLOAK_ADMIN_REFRESH_TOKEN"))
        # Verify issuer and frozen native client before creating any identities.
        discovery = admin_http.json("GET", issuer + "/.well-known/openid-configuration")
        require(discovery["issuer"] == issuer, "Issuer mismatch")
        clients = admin_http.json("GET", admin + "/admin/realms/armoney/clients?clientId=" + CLIENT, token=admin_token)
        require(len(clients) == 1, "Native client missing or ambiguous")
        client = clients[0]
        require(client.get("publicClient") is True and client.get("standardFlowEnabled") is True
                and client.get("directAccessGrantsEnabled") is False and client.get("implicitFlowEnabled") is False
                and client.get("redirectUris") == [CALLBACK]
                and client.get("attributes", {}).get("pkce.code.challenge.method") == "S256", "Native client contract changed")
        for n in range(100, 100 + args.count):
            previous = manifest["users"].get(str(n))
            subject = ensure_user(admin_http, admin, admin_token, n, password, previous)
            entry = previous or {"subject": subject, "status": "identity-created"}
            manifest["users"][str(n)] = entry
            save_manifest(path, manifest)
            http = Http(args.ca_file)  # Never reuse another fixture's login cookies.
            oidc = login(http, issuer, fixture(n)["username"], password)
            try:
                userinfo = http.json("GET", issuer + "/protocol/openid-connect/userinfo", token=oidc["access_token"])
                require(userinfo.get("sub") == subject, "Provider session subject mismatch")
                manifest["users"][str(n)] = provision(http, gateway, oidc, n, entry)
                save_manifest(path, manifest)
            finally:
                http.json("POST", issuer + "/protocol/openid-connect/logout", {
                    "client_id": CLIENT, "refresh_token": oidc["refresh_token"]}, form=True, statuses=(204,))
            print(f"Ready: {n - 99}/{args.count}")
        print(f"Provisioning complete: {args.count} profiles and ready AED wallets. No funds posted.")


if __name__ == "__main__":
    try:
        main()
    except Blocked as error:
        print("Blocked: " + str(error), file=sys.stderr)
        sys.exit(1)
    except Exception:
        print("Blocked: local provisioning failed; sensitive error details suppressed. Inspect service health and rerun with the same manifest.", file=sys.stderr)
        sys.exit(1)
