"""Private disposable configuration only; no network, provisioning or activation.

LOCAL SMTP TEST uses the companion's injected peer, never actual Mailtrap delivery.
POSIX only. init --output NEW --phone NONSECRET --email NONSECRET --name NONSECRET
creates auth.env, authority.env, user.env, sms.env, three *-db.env, sso-off.env,
provision.json and inventory.json. on --output SAME --subject PERSISTED_UUID
creates sso-on.env once; the caller must establish actual provider persistence.
Optional --local-materials reads a private canonical-Base64 JSON map of additional
provider-owned material. Completeness means all generated known roles plus those
explicit extras, never proof about unseen environment or external inventories.
No Auth retention/AEAD ring enters SSO. No network, accounts, tokens or hooks are
operated; selecting the generated ON configuration belongs to the isolated runner.
"""
import argparse
import base64
import json
import os
from pathlib import Path
import re
import secrets
import stat
import sys
import uuid

ISSUER = "https://sso-edge:8443/sso/realms/armoney"
CLIENT = "armoney-android"
LIMIT = 131072
DIRECTIONAL = ("stage", "lookup", "confirm", "status", "binding", "controller", "provider", "peer")
ROLES = DIRECTIONAL + ("bridge", "internal", "projection", "sms-send", "sms-read", "smtp-user", "smtp-password",
    "introspection", "auth-db", "user-db", "sso-db", "admin", "password",
    "authority-retention", "otp", "delivery", "projection-retention", "proof-retention", "intent-retention", "aead")
BINARY = {"authority-retention", "otp", "delivery", "projection-retention", "proof-retention", "intent-retention", "aead"}


def reject():
    raise ValueError("Disposable configuration unavailable")


def require_posix():
    if os.name != "posix":
        reject()


def strict_json(data):
    def unique(pairs):
        result = {}
        for key, value in pairs:
            if key in result:
                reject()
            result[key] = value
        return result
    if len(data) > LIMIT:
        reject()
    return json.loads(data.decode("utf-8"), object_pairs_hook=unique)


def read_private(path):
    path = Path(path)
    if path.is_symlink() or not path.is_file() or path.stat().st_size > LIMIT:
        reject()
    if os.name != "nt" and path.stat().st_mode & 0o077:
        reject()
    return strict_json(path.read_bytes())


def decode(value):
    try:
        raw = base64.b64decode(value, validate=True)
        if base64.b64encode(raw).decode() != value:
            reject()
        return raw
    except (ValueError, TypeError):
        reject()


def aliases(raw):
    result = {raw}
    try:
        decoded = base64.b64decode(raw, validate=True)
        if len(decoded) == 32 and base64.b64encode(decoded) == raw:
            result.add(decoded)
    except ValueError:
        pass
    return result


def validate_inventory(roles, material):
    if set(roles) != set(ROLES) or not isinstance(material, dict) or not 1 <= len(material) <= 64:
        reject()
    if len(packed(material)) > 16384:
        reject()
    seen = set()
    for value in roles.values():
        if not isinstance(value, str) or not re.fullmatch(r"[A-Za-z0-9_+/=-]{32,256}", value):
            reject()
        forms = aliases(value.encode("ascii"))
        if seen & forms:
            reject()
        seen |= forms
    for role in BINARY:
        if len(decode(roles[role])) != 32:
            reject()
    for name, value in material.items():
        if not re.fullmatch(r"[A-Za-z0-9._-]{1,64}", name) or not isinstance(value, str):
            reject()
        raw = decode(value)
        if not 1 <= len(raw) <= 4096:
            reject()
        forms = aliases(raw)
        if seen & forms:
            reject()
        seen |= forms
    if "code-v1" not in material or len(decode(material["code-v1"])) != 32:
        reject()


def encode_env(values):
    lines = []
    for key, value in sorted(values.items()):
        if not re.fullmatch(r"[A-Z][A-Z0-9_]*", key) or not isinstance(value, str) or any(c in value for c in "\r\n\0'"):
            reject()
        lines.append(key + "='" + value + "'\n")
    return "".join(lines).encode()


def write_exclusive(root, name, data):
    require_posix()
    if len(data) > LIMIT or Path(name).name != name or root.is_symlink():
        reject()
    if os.name == "posix":
        directory = os.open(root, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
        try:
            fd = os.open(name, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600, dir_fd=directory)
            with os.fdopen(fd, "wb") as handle:
                os.fchmod(handle.fileno(), 0o600)
                handle.write(data)
        finally:
            os.close(directory)
    else:
        # Only reached by explicitly mocked internal Windows unit tests.
        # Every public write entrypoint above still enforces require_posix().
        fd = os.open(root / name, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(fd, "wb") as handle:
            handle.write(data)


def packed(value):
    return json.dumps(value, separators=(",", ":"), ensure_ascii=True).encode()


def sso(roles, material, subject=None):
    result = {"KC_DB_USERNAME": "keycloak", "KC_DB_PASSWORD": roles["sso-db"],
        "KC_BOOTSTRAP_ADMIN_USERNAME": "admin", "KC_BOOTSTRAP_ADMIN_PASSWORD": roles["admin"],
        "ARMONEY_CODE_INTENT_FIXTURE_ENABLED": "false"}
    if subject is None:
        return result
    result.update({"CI": "true", "CODE_SESSION_ACCEPTANCE_ISOLATED": "true",
        "ARMONEY_CODE_INTENT_FIXTURE_ENABLED": "true", "ARMONEY_LOGIN_PROOF_ENABLED": "true",
        "ARMONEY_VERIFICATION_ENABLED": "true", "ARMONEY_CODE_SESSION_OBSERVATION_ENABLED": "true", "SSO_ISSUER": ISSUER,
        "SSO_VERIFICATION_CLIENT_IDS": CLIENT, "ARMONEY_AUTH_BRIDGE_URL": "http://auth-service:8080",
        "ARMONEY_SSO_MANAGED_PAIRS": packed([{"issuer": ISSUER, "client_id": CLIENT}]).decode(),
        "ARMONEY_SSO_LEGACY_PAIRS": "[]", "SSO_LOGIN_PROOF_WRITER_ID": "sso-provider",
        "SSO_CODE_INTENT_ACTORS": "sso-provider", "SSO_CODE_INTENT_CODE_KEY_IDS": "code-v1",
        "SSO_CODE_INTENT_ISSUERS": ISSUER, "SSO_CODE_INTENT_CLIENTS": CLIENT,
        "SSO_LOGIN_PROOF_WRITE_KEY": roles["stage"], "SSO_CODE_INTENT_LOOKUP_KEY": roles["lookup"],
        "SSO_LOGIN_PROOF_CONFIRM_KEY": roles["confirm"], "AUTH_TO_SSO_STATUS_KEY": roles["status"],
        "SSO_VERIFICATION_BRIDGE_KEY": roles["bridge"], "ARMONEY_PROVIDER_BINDING_KEY": roles["binding"],
        "ARMONEY_CODE_INTENT_FIXTURE_CONTROL_KEY": roles["controller"],
        "AUTH_CODE_INTENT_FIXTURE_PROVIDER_KEY": roles["provider"], "ARMONEY_FIXTURE_PEER_KEY": roles["peer"],
        "ARMONEY_CODE_INTENT_FIXTURE_SUBJECT": subject,
        "ARMONEY_CODE_INTENT_FIXTURE_LOCAL_MATERIAL": packed(material).decode()})
    return result


def initialize(output, phone, email, name, local_material=None):
    require_posix()
    if not re.fullmatch(r"\+9715[024568][0-9]{7}", phone) or len(email) > 254 or not email.endswith((".test", ".invalid")) or not re.fullmatch(r"[a-z0-9.!#$%&*+/=?^_`{|}~-]+@[a-z0-9](?:[a-z0-9-]*[a-z0-9])?(?:\.[a-z0-9](?:[a-z0-9-]*[a-z0-9])?)+", email):
        reject()
    if not isinstance(name, str) or not 1 <= len(name) <= 100 or not name.strip() or any(not 32 <= ord(c) < 127 for c in name):
        reject()
    roles = {role: base64.b64encode(secrets.token_bytes(32)).decode() if role in BINARY else secrets.token_urlsafe(32) for role in ROLES}
    if local_material is not None and not isinstance(local_material, dict):
        reject()
    material = dict(local_material or {})
    if any(not isinstance(k, str) or k == "code-v1" or k in ROLES or k.startswith(("SSO_", "AUTH_", "ARMONEY_", "VERIFICATION_", "KC_")) for k in material):
        reject()
    material["code-v1"] = base64.b64encode(secrets.token_bytes(32)).decode()
    if len(packed(material)) > 16384:
        reject()
    validate_inventory(roles, material)
    auth = {key: "true" for key in ("AUTH_VERIFICATION_AUTHORITY_BRIDGE_ENABLED", "AUTH_REGISTRATION_AUTHORITY_COMPLETION_ENABLED",
        "AUTH_REGISTRATION_ELIGIBILITY_GRANTS_ENABLED", "AUTH_VERIFICATION_PROJECTION_ENABLED", "AUTH_SSO_CODE_INTENT_STAGE_ENABLED", "AUTH_SSO_CODE_INTENT_CONFIRMATION_ENABLED")}
    auth.update({"AUTH_SSO_LOGIN_PROOF_ENABLED": "false", "AUTH_VERIFICATION_BRIDGE_ENABLED": "false", "AUTH_VERIFICATION_DELIVERY_ENABLED": "false",
        "SSO_ISSUER": ISSUER, "SSO_VERIFICATION_CLIENT_IDS": CLIENT, "SSO_ENROLLMENT_STATUS_ORIGIN": "http://sso-service:8080",
        "ARMONEY_SSO_MANAGED_PAIRS": packed([{"issuer": ISSUER, "client_id": CLIENT}]).decode(), "ARMONEY_SSO_LEGACY_PAIRS": "[]",
        "SSO_CODE_INTENT_ACTORS": "sso-provider", "SSO_LOGIN_PROOF_WRITER_ID": "sso-provider", "SSO_CODE_INTENT_CODE_KEY_IDS": "code-v1",
        "SSO_LOGIN_PROOF_WRITE_KEY": roles["stage"], "SSO_CODE_INTENT_LOOKUP_KEY": roles["lookup"], "SSO_LOGIN_PROOF_CONFIRM_KEY": roles["confirm"],
        "SSO_VERIFICATION_BRIDGE_KEY": roles["bridge"], "AUTH_TO_SSO_STATUS_KEY": roles["status"], "ARMONEY_PROVIDER_BINDING_KEY": roles["binding"],
        "VERIFICATION_KEY_ID": "fixture-v1", "VERIFICATION_OTP_HMAC_KEY": roles["otp"], "VERIFICATION_DELIVERY_ENCRYPTION_KEY": roles["delivery"],
        "AUTH_VERIFICATION_AUTHORITY_RETENTION_KEY": roles["authority-retention"], "AUTH_VERIFICATION_PROJECTION_WRITER_ID": "auth-projection",
        "AUTH_TO_USER_VERIFICATION_PROJECTION_KEY": roles["projection"], "AUTH_VERIFICATION_PROJECTION_RETENTION_KEY": roles["projection-retention"],
        "SSO_LOGIN_PROOF_RETENTION_KEY": roles["proof-retention"], "SSO_LOGIN_PROOF_RETENTION_HMAC_KEY_ID": "stage-v1",
        "SSO_LOGIN_PROOF_RETENTION_HMAC_KEY": roles["intent-retention"], "SSO_CODE_INTENT_DETAIL_ENCRYPTION_KEY_ID": "aead-v1",
        "SSO_CODE_INTENT_DETAIL_ENCRYPTION_KEY": roles["aead"], "DB_URL": "jdbc:postgresql://auth-db:5432/auth", "DB_USER": "auth", "DB_PASSWORD": roles["auth-db"],
        "INTERNAL_AUTH_KEY": roles["internal"], "USER_BASE_URL": "http://user-service:8080", "PORT": "8080",
        "SSO_CLIENT_ID": CLIENT, "SSO_CLIENT_SECRET": roles["introspection"],
        "SSO_INTROSPECTION_URL": "http://sso-service:8080/sso/realms/armoney/protocol/openid-connect/token/introspect"})
    authority = dict(auth, CI="true", CODE_SESSION_ACCEPTANCE_ISOLATED="true", AUTH_CODE_INTENT_FIXTURE_PROVIDER_KEY=roles["provider"],
        VERIFICATION_SMS_BASE_URL="http://sms-sandbox:8080", VERIFICATION_SMS_SEND_TOKEN=roles["sms-send"], SMS_SANDBOX_READ_TOKEN=roles["sms-read"],
        MAILTRAP_SMTP_USERNAME=roles["smtp-user"], MAILTRAP_SMTP_PASSWORD=roles["smtp-password"], MAILTRAP_FROM_EMAIL="fixture@example.test")
    user = {"DB_URL": "jdbc:postgresql://user-db:5432/users", "DB_USER": "users", "DB_PASSWORD": roles["user-db"], "PORT": "8080",
        "INTERNAL_AUTH_KEY": roles["internal"], "USER_VERIFICATION_PROJECTION_ENABLED": "true", "USER_VERIFICATION_PROJECTION_WRITER_ID": "auth-projection",
        "AUTH_TO_USER_VERIFICATION_PROJECTION_KEY": roles["projection"]}
    files = {"auth.env": encode_env(auth), "authority.env": encode_env(authority), "user.env": encode_env(user),
        "sms.env": encode_env({"SMS_SANDBOX_SEND_TOKEN": roles["sms-send"], "SMS_SANDBOX_READ_TOKEN": roles["sms-read"], "SMS_SANDBOX_DATA_DIR": "/tmp/sms-inbox", "PORT": "8080"}),
        "sso-off.env": encode_env(sso(roles, material)), "provision.json": packed({"CI": "true", "CODE_SESSION_ACCEPTANCE_ISOLATED": "true",
            "ARMONEY_CODE_INTENT_FIXTURE_ENABLED": "false", "expected_issuer": ISSUER, "admin_username": "admin", "admin_password": roles["admin"],
            "synthetic_user": {"username": phone, "email": email, "firstName": name, "lastName": "Fixture", "password": roles["password"]}}),
        "inventory.json": packed({"format": 1, "roles": roles, "material": material})}
    for role in ("auth", "user", "sso"):
        files[role + "-db.env"] = encode_env({"POSTGRES_PASSWORD": roles[role + "-db"]})
    if sum(map(len, files.values())) > LIMIT:
        reject()
    root = Path(output).absolute()
    if root.exists() or root.is_symlink() or any(p.is_symlink() for p in root.parents):
        reject()
    root.mkdir(mode=0o700)
    os.chmod(root, 0o700)
    for filename, data in files.items():
        write_exclusive(root, filename, data)


def enable(output, subject):
    require_posix()
    try:
        if str(uuid.UUID(subject)) != subject:
            reject()
    except (ValueError, TypeError):
        reject()
    root = Path(output).absolute()
    if root.is_symlink() or any(p.is_symlink() for p in root.parents) or not root.is_dir():
        reject()
    inventory = read_private(root / "inventory.json")
    if set(inventory) != {"format", "roles", "material"} or inventory["format"] != 1:
        reject()
    validate_inventory(inventory["roles"], inventory["material"])
    write_exclusive(root, "sso-on.env", encode_env(sso(inventory["roles"], inventory["material"], subject)))


class SafeParser(argparse.ArgumentParser):
    def error(self, message):
        self.exit(2, "Disposable configuration unavailable\n")


def main(argv=None):
    parser = SafeParser(description=__doc__)
    parser.add_argument("mode", choices=("init", "on"))
    parser.add_argument("--output", required=True)
    parser.add_argument("--phone")
    parser.add_argument("--email")
    parser.add_argument("--name")
    parser.add_argument("--local-materials")
    parser.add_argument("--subject")
    args = parser.parse_args(argv)
    try:
        # chmod cannot establish private NTFS ACLs. Do not imply it can.
        require_posix()
        if args.mode == "init":
            if args.subject or not all((args.phone, args.email, args.name)):
                reject()
            local = read_private(args.local_materials) if args.local_materials else {}
            initialize(args.output, args.phone, args.email, args.name, local)
        else:
            if any((args.phone, args.email, args.name, args.local_materials)):
                reject()
            enable(args.output, args.subject)
        return 0
    except (ValueError, TypeError, OSError, KeyError):
        print("Disposable configuration unavailable", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
