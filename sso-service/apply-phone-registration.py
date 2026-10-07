"""Explicit administrator tool for an existing realm; defaults to a read-only plan.

Supply a short-lived KEYCLOAK_ADMIN_TOKEN through the environment. Never run this
against the persistent stack as part of a build, smoke test, or startup import.
"""
import argparse
import copy
import json
import os
from pathlib import Path
import urllib.error
import urllib.parse
import urllib.request


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--admin-base", required=True, help="Trusted Keycloak base URL including /sso")
    parser.add_argument("--apply", action="store_true", help="Apply and verify the narrowly scoped update")
    args = parser.parse_args()
    base = args.admin_base.rstrip("/")
    parsed = urllib.parse.urlsplit(base)
    if (parsed.username or parsed.password or parsed.query or parsed.fragment or not parsed.hostname
            or (parsed.scheme != "https" and not
                (parsed.scheme == "http" and parsed.hostname in ("127.0.0.1", "localhost", "::1")))):
        parser.error("Use a trusted HTTPS admin base, or HTTP on loopback, without credentials/query/fragment")
    token = os.environ.get("KEYCLOAK_ADMIN_TOKEN")
    if not token:
        parser.error("KEYCLOAK_ADMIN_TOKEN must contain a short-lived administrator token")
    opener = urllib.request.build_opener(NoRedirect())

    def request(method, path, data=None):
        body = None if data is None else json.dumps(data).encode()
        req = urllib.request.Request(base + "/admin/realms/armoney" + path, data=body,
            method=method, headers={"Authorization": "Bearer " + token, "Content-Type": "application/json"})
        try:
            with opener.open(req, timeout=15) as response:
                content = response.read(1024 * 1024 + 1)
                if len(content) > 1024 * 1024:
                    raise SystemExit("Admin response exceeded the size limit")
                return json.loads(content) if content else None
        except urllib.error.HTTPError as error:
            raise SystemExit(f"Admin request failed: {method} {path}, HTTP {error.code}") from None
        except (OSError, ValueError):
            raise SystemExit(f"Admin request failed: {method} {path}; check connectivity and configuration") from None

    directory = Path(__file__).resolve().parent
    desired = json.loads((directory / "armoney-realm.json").read_text(encoding="utf-8"))
    profile_template = json.loads((directory / "phone-user-profile.json").read_text(encoding="utf-8"))
    desired_username = next(a for a in profile_template["attributes"] if a["name"] == "username")
    realm = request("GET", "")
    if realm.get("realm") != "armoney":
        raise SystemExit("Unexpected realm; no changes applied")
    flags = {"registrationEmailAsUsername": False, "editUsernameAllowed": False}
    profile = request("GET", "/users/profile")
    updated_profile = copy.deepcopy(profile)
    usernames = [a for a in updated_profile["attributes"] if a["name"] == "username"]
    if len(usernames) != 1:
        raise SystemExit("Expected one username profile entry; no changes applied")
    # Preserve custom profile attributes, groups, policies and unrelated username
    # annotations/validators, but freeze the phone's rendering, bounds and permissions.
    username = usernames[0]
    username["displayName"] = desired_username["displayName"]
    username["permissions"] = desired_username["permissions"]
    username.setdefault("validations", {}).update(desired_username["validations"])
    username.setdefault("annotations", {}).update(desired_username["annotations"])
    username.pop("selector", None)
    username.pop("defaultValue", None)
    username["multivalued"] = False
    mapper_updates = []
    for client_id in ("armoney-ios", "armoney-android"):
        clients = request("GET", "/clients?" + urllib.parse.urlencode({"clientId": client_id}))
        if len(clients) != 1 or clients[0].get("clientId") != client_id:
            raise SystemExit("Both native clients must already exist; no changes applied")
        client = clients[0]
        configured = next(c for c in desired["clients"] if c["clientId"] == client_id)
        mapper = next(m for m in configured["protocolMappers"] if m["name"] == "armoney-phone")
        path = "/clients/" + urllib.parse.quote(client["id"], safe="") + "/protocol-mappers/models"
        existing = request("GET", path)
        matches = [m for m in existing if m.get("name") == mapper["name"]]
        if len(matches) > 1 or any(m.get("name") != mapper["name"] and
                m.get("config", {}).get("claim.name") == "phone_number" for m in existing):
            raise SystemExit("Conflicting phone mapper; resolve deliberately before applying")
        mapper_updates.append((path, mapper, matches[0].get("id") if matches else None))

    if not args.apply:
        print("Read-only plan: freeze username phone profile and username editing; upsert phone mapper on both native clients.")
        print("Users, credentials, issuer, client secrets, grants, redirects, audiences and unrelated profile fields are not updated.")
        print("Review the migration README, then rerun with --apply during an administrator-controlled change window.")
        return
    # Admin APIs apply separate resources, not one transaction. Stop on failure;
    # rerun the same idempotent operation after investigating partial application.
    request("PUT", "", flags)
    request("PUT", "/users/profile", updated_profile)
    for path, mapper, mapper_id in mapper_updates:
        if mapper_id:
            request("PUT", path + "/" + urllib.parse.quote(mapper_id, safe=""), dict(mapper, id=mapper_id))
        else:
            request("POST", path, mapper)
    actual_realm = request("GET", "")
    if any(actual_realm.get(key) != value for key, value in flags.items()):
        raise SystemExit("Realm flag verification failed")
    actual_profile = request("GET", "/users/profile")
    actual_username = next(a for a in actual_profile["attributes"] if a["name"] == "username")
    for field in ("displayName", "permissions", "validations", "annotations"):
        if actual_username.get(field) != username.get(field):
            raise SystemExit("Username profile verification failed")
    if [a for a in actual_profile["attributes"] if a["name"] != "username"] != [
            a for a in profile["attributes"] if a["name"] != "username"]:
        raise SystemExit("Unrelated profile attribute verification failed")
    for path, mapper, _ in mapper_updates:
        matches = [m for m in request("GET", path) if m.get("name") == mapper["name"]]
        if len(matches) != 1 or any(matches[0].get(k) != v for k, v in mapper.items()):
            raise SystemExit("Native client phone mapper verification failed")
    print("Phone registration settings applied and read back. Browser/migration acceptance remains required.")


if __name__ == "__main__":
    main()
