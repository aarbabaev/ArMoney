"""Operator-only synthetic AED baseline. Default is an offline plan.

Apply requires the original seeder manifest, local TLS CA, fixture password in
SEED_TEST_USER_PASSWORD and explicit acknowledgement. Retry with the same
manifest and amount after any interruption; never delete funding checkpoints.
"""
import argparse
import importlib.util
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import uuid

spec = importlib.util.spec_from_file_location("seed_local_users", Path(__file__).with_name("seed-local-users.py"))
seed = importlib.util.module_from_spec(spec)
spec.loader.exec_module(seed)
require = seed.require


def funding_id(ordinal, account):
    namespace = uuid.uuid5(uuid.NAMESPACE_URL, seed.NAMESPACE)
    return str(uuid.uuid5(namespace, f"{ordinal}:{account}"))


def amount_fils(ordinal):
    return 100000 + ((int(ordinal) - 100) * 7919 % 400001)


def validate(manifest, count, gateway, issuer):
    require(isinstance(manifest, dict) and manifest.get("namespace") == seed.NAMESPACE
            and manifest.get("gateway") == gateway and manifest.get("issuer") == issuer
            and type(manifest.get("count")) is int and manifest["count"] == count,
            "Manifest target/count/namespace mismatch")
    users = manifest.get("users")
    require(isinstance(users, dict) and set(users) == {str(n) for n in range(100, 100 + count)},
            "Manifest must contain the complete reserved fixture set")
    seen = {key: set() for key in ("subject", "identity_id", "profile_id", "wallet_id", "ledger_account_id")}
    for ordinal, entry in users.items():
        require(isinstance(entry, dict) and entry.get("status") == "ready", "All fixtures must be ready")
        for key, identifiers in seen.items():
            value = seed.identifier(entry.get(key))
            require(value not in identifiers, "Duplicate fixture identifier")
            identifiers.add(value)
        expected = funding_id(ordinal, entry["ledger_account_id"])
        previous = entry.get("funding")
        if previous is not None:
            require(isinstance(previous, dict) and previous.get("funding_id") == expected
                    and type(previous.get("amount_fils")) is int and previous["amount_fils"] == amount_fils(ordinal)
                    and previous.get("status") in ("pending", "POSTED"), "Funding checkpoint conflicts; preserve original amount and manifest")


def local_endpoint(value):
    allowed = {"unix:///var/run/docker.sock", "npipe:////./pipe/docker_engine",
               "npipe:////./pipe/dockerDesktopLinuxEngine"}
    require(value in allowed, "Unsupported Docker endpoint; select the local Docker daemon")
    return value


def docker_process(args):
    # Capture every subprocess stream; never emit Docker diagnostics or DB config.
    environment = {key: value for key, value in os.environ.items()
                   if key.upper() not in {"DOCKER_HOST", "DOCKER_CONTEXT", "DOCKER_TLS", "DOCKER_TLS_VERIFY", "DOCKER_CERT_PATH"}}
    result = subprocess.run(["docker", *args], env=environment, capture_output=True, text=True, timeout=90)
    require(result.returncode == 0, "Docker operation failed; retry the same manifest after checking service health")
    return result.stdout


def selected_endpoint():
    override = os.environ.get("DOCKER_HOST")
    if override:
        local_endpoint(override)  # Reject remote overrides before even inspecting local config.
    context = os.environ.get("DOCKER_CONTEXT")
    if context:
        require(re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_.-]*", context), "Unsupported Docker context name")
    if override and not context:
        return override
    # This reads local Docker context metadata only; no daemon connection occurs.
    args = ["context", "inspect", "--format", "{{.Endpoints.docker.Host}}"]
    if context:
        args.append(context)
    return local_endpoint(docker_process(args).strip())


def docker(*args, endpoint):
    # Freeze the verified host for every daemon command, regardless of context changes.
    return docker_process(["--host", local_endpoint(endpoint), *args])


def ledger_container(endpoint):
    ids = docker("ps", "--no-trunc", "--filter", "label=com.docker.compose.project=arman-bank",
                 "--filter", "label=com.docker.compose.service=ledger-service", "--format", "{{.ID}}", endpoint=endpoint).split()
    require(len(ids) == 1 and re.fullmatch(r"[0-9a-f]{64}", ids[0]), "Expected exactly one local ledger container")
    # Inspect only necessary fields: never request the container's environment.
    state = json.loads(docker("inspect", "--format", '{{json .State.Running}}', ids[0], endpoint=endpoint))
    labels = json.loads(docker("inspect", "--format", '{{json .Config.Labels}}', ids[0], endpoint=endpoint))
    require(state is True and isinstance(labels, dict)
            and labels.get("com.docker.compose.project") == "arman-bank"
            and labels.get("com.docker.compose.service") == "ledger-service"
            and labels.get("com.docker.compose.oneoff", "False").lower() == "false",
            "Ledger container identity or running state mismatch")
    return ids[0]


def ready_wallet(http, gateway, issuer, ordinal, entry, password):
    oidc = seed.login(http, issuer, seed.fixture(ordinal)["username"], password)
    try:
        user = http.json("GET", issuer + "/protocol/openid-connect/userinfo", token=oidc["access_token"])
        require(user.get("sub") == entry["subject"], "Provider identity mismatch")
        session = http.json("POST", gateway + "/v1/auth/sso", {"access_token": oidc["access_token"]})["access_token"]
        try:
            identity = http.json("GET", gateway + "/v1/auth/me", token=session)
            require(identity.get("id") == entry["identity_id"], "Bank identity mismatch")
            wallets = http.json("GET", gateway + "/v1/wallets", token=session)["wallets"]
            matches = [w for w in wallets if w.get("id") == entry["wallet_id"]]
            require(len(matches) == 1, "Manifest wallet missing or ambiguous")
            wallet = matches[0]
            require(wallet.get("owner_id") == entry["identity_id"] and wallet.get("currency") == "AED"
                    and wallet.get("status") == "ACTIVE" and wallet.get("provisioning_status") == "READY"
                    and wallet.get("ledger_account_id") == entry["ledger_account_id"], "Wallet is not the expected ready AED account")
        finally:
            http.json("POST", gateway + "/v1/auth/logout", token=session, statuses=(204,))
    finally:
        http.json("POST", issuer + "/protocol/openid-connect/logout",
                  {"client_id": seed.CLIENT, "refresh_token": oidc["refresh_token"]}, form=True, statuses=(204,))


def post(container, entry, endpoint):
    funding = entry["funding"]
    output = docker("exec", "-e", "APP_ENV=local", "-e", "ARMONEY_LOCAL_DEMO_FUNDING=I_UNDERSTAND_SYNTHETIC_FUNDS",
                    container, "java", "-cp", "/opt/service/lib/*",
                    "com.arman.bank.ledgerservice.infrastructure.LocalDemoFundingMain",
                    funding["funding_id"], entry["identity_id"], entry["wallet_id"],
                    entry["ledger_account_id"], str(funding["amount_fils"]), endpoint=endpoint)
    # The JVM may print logging lines. Accept exactly one matching receipt.
    receipts = []
    for line in output.splitlines():
        try:
            value = json.loads(line)
        except json.JSONDecodeError:
            continue
        if isinstance(value, dict) and "funding_id" in value:
            receipts.append(value)
    require(receipts == [{"funding_id": funding["funding_id"], "account_id": entry["ledger_account_id"], "status": "POSTED"}],
            "Funding result uncertain; retry the same manifest and amount")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apply", action="store_true")
    parser.add_argument("--acknowledge-local-fixtures", action="store_true")
    parser.add_argument("--count", type=int, default=200)
    parser.add_argument("--gateway", default="https://localhost:8443")
    parser.add_argument("--issuer")
    parser.add_argument("--manifest", type=Path)
    parser.add_argument("--ca-file")
    args = parser.parse_args()
    require(1 <= args.count <= 900, "Count must be between 1 and 900")
    if not args.apply:
        print(f"Plan: {args.count} synthetic AED baselines of 100000..500000 fils; verify ready wallets and post balanced ledger entries. No requests sent.")
        return
    require(args.acknowledge_local_fixtures, "Apply requires --acknowledge-local-fixtures")
    require(args.manifest and args.ca_file, "Apply requires --manifest and --ca-file")
    gateway = seed.local_origin(args.gateway)
    issuer = seed.local_origin(args.issuer or gateway + "/sso/realms/armoney")
    require(seed.url.urlsplit(gateway).path == "" and seed.url.urlsplit(issuer).path == "/sso/realms/armoney", "Unexpected gateway/realm path")
    path = args.manifest.parent.resolve() / args.manifest.name
    require(not path.is_relative_to(Path(__file__).resolve().parents[1]) and path.parent.is_dir(),
            "Manifest must be outside checkout in an existing directory")
    password = os.environ.get("SEED_TEST_USER_PASSWORD")
    require(password and len(password) >= 15, "Required fixture password environment is missing/invalid")
    with seed.manifest_lock(path):
        require(not path.is_symlink() and path.is_file(), "Original regular seeder manifest required")
        seed.owner_only(path)
        manifest = json.loads(path.read_text(encoding="utf-8"))
        validate(manifest, args.count, gateway, issuer)
        endpoint = selected_endpoint()
        container = ledger_container(endpoint)
        discovery = seed.Http(args.ca_file).json("GET", issuer + "/.well-known/openid-configuration")
        require(discovery.get("issuer") == issuer, "Issuer mismatch")
        for ordinal in range(100, 100 + args.count):
            entry = manifest["users"][str(ordinal)]
            ready_wallet(seed.Http(args.ca_file), gateway, issuer, ordinal, entry, password)
            if "funding" not in entry:
                entry["funding"] = {"funding_id": funding_id(ordinal, entry["ledger_account_id"]),
                                    "amount_fils": amount_fils(ordinal), "status": "pending"}
            # Persist command before dispatch, including before a possible uncertain result.
            seed.save_manifest(path, manifest)
            post(container, entry, endpoint)
            entry["funding"]["status"] = "POSTED"
            seed.save_manifest(path, manifest)
            print(f"Confirmed: {ordinal - 99}/{args.count}")
        print(f"Funding complete: {args.count} synthetic AED baselines confirmed; reruns replay the original commands.")


if __name__ == "__main__":
    try:
        main()
    except seed.Blocked as error:
        print("Blocked: " + str(error), file=sys.stderr)
        sys.exit(1)
    except Exception:
        print("Blocked: funding failed; sensitive details suppressed. Retry the same manifest and amount after checking service health.", file=sys.stderr)
        sys.exit(1)
