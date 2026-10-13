"""Operator acceptance for the 200 synthetic local fixtures; offline plan by default.

Apply logs in through native PKCE and checks gateway ownership and funded balances.
Optional --transfer-and-return sends 100 fils from fixtures 100 to 101 and back,
using stable keys across interruptions. Preserve the manifest and rerun that option
after an uncertain response. No funding, Docker, SQL, restart or restore is performed.
This is public API acceptance, not journal reconciliation or restored-stack proof.
"""
import argparse
from contextlib import contextmanager, ExitStack
import importlib.util
import json
import os
from pathlib import Path
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid

spec = importlib.util.spec_from_file_location("baseline_seed", Path(__file__).with_name("seed-local-users.py"))
seed = importlib.util.module_from_spec(spec)
spec.loader.exec_module(seed)
require = seed.require
COUNT = 200


def amount_fils(ordinal):
    return 100000 + ((int(ordinal) - 100) * 7919 % 400001)


def validate(manifest, gateway, issuer):
    require(isinstance(manifest, dict) and manifest.get("namespace") == seed.NAMESPACE
            and manifest.get("gateway") == gateway and manifest.get("issuer") == issuer
            and type(manifest.get("count")) is int and manifest["count"] == COUNT,
            "Manifest target/count/namespace mismatch")
    users = manifest.get("users")
    require(isinstance(users, dict) and set(users) == {str(n) for n in range(100, 300)},
            "Manifest must contain exactly the 200 reserved fixtures")
    seen = {key: set() for key in ("subject", "identity_id", "profile_id", "wallet_id", "ledger_account_id")}
    namespace = uuid.uuid5(uuid.NAMESPACE_URL, seed.NAMESPACE)
    for ordinal, entry in users.items():
        require(isinstance(entry, dict) and entry.get("status") == "ready", "Fixture is not ready")
        for key, identifiers in seen.items():
            value = seed.identifier(entry.get(key))
            require(value not in identifiers, "Duplicate fixture identifier")
            identifiers.add(value)
        funding = entry.get("funding")
        expected_id = str(uuid.uuid5(namespace, f"{ordinal}:{entry['ledger_account_id']}"))
        require(isinstance(funding, dict) and funding.get("status") == "POSTED"
                and funding.get("funding_id") == expected_id
                and type(funding.get("amount_fils")) is int
                and funding["amount_fils"] == amount_fils(ordinal), "Funding checkpoint mismatch")
    return users


def targets(gateway, issuer):
    require(gateway and issuer, "Apply requires explicit --gateway and --issuer")
    gateway, issuer = seed.local_origin(gateway), seed.local_origin(issuer)
    g, i = urllib.parse.urlsplit(gateway), urllib.parse.urlsplit(issuer)
    require(g.path == "" and i.path == "/sso/realms/armoney"
            and (g.scheme, g.netloc) == (i.scheme, i.netloc), "Gateway/issuer origin or path mismatch")
    return gateway, issuer


def manifest_path(value):
    require(value is not None, "Apply requires --manifest")
    path = value.parent.resolve() / value.name
    require(not path.is_symlink() and path.is_file()
            and not path.resolve().is_relative_to(Path(__file__).resolve().parents[1]),
            "Manifest must be a regular file outside checkout")
    return path


@contextmanager
def session(ca, issuer, gateway, ordinal, entry, password):
    http = seed.Http(ca)
    oidc = seed.login(http, issuer, seed.fixture(ordinal)["username"], password)
    token = None
    try:
        info = http.json("GET", issuer + "/protocol/openid-connect/userinfo", token=oidc["access_token"])
        require(info.get("sub") == entry["subject"]
                and info.get("email") == seed.fixture(ordinal)["email"], "Provider subject/contact mismatch")
        token = http.json("POST", gateway + "/v1/auth/sso", {"access_token": oidc["access_token"]})["access_token"]
        yield http, token
    finally:
        try:
            if token:
                http.json("POST", gateway + "/v1/auth/logout", token=token, statuses=(204,))
        finally:
            http.json("POST", issuer + "/protocol/openid-connect/logout",
                      {"client_id": seed.CLIENT, "refresh_token": oidc["refresh_token"]}, form=True, statuses=(204,))


def balance(http, gateway, token, entry):
    result = http.json("GET", gateway + "/v1/wallets/" + entry["wallet_id"] + "/balance", token=token)
    require(result.get("wallet_id") == entry["wallet_id"] and result.get("currency") == "AED"
            and type(result.get("balance_minor")) is int and result["balance_minor"] >= 0,
            "Balance contract mismatch")
    return result["balance_minor"]


def verify_user(http, gateway, token, ordinal, entry):
    expected = seed.fixture(ordinal)
    identity = http.json("GET", gateway + "/v1/auth/me", token=token)
    # Native SSO identities have no legacy password-login email; provider contact
    # is checked through authenticated userinfo above, not this nullable field.
    require(identity.get("id") == entry["identity_id"] and identity.get("email") is None,
            "Bank identity mismatch")
    profile = http.json("GET", gateway + "/v1/users/me", token=token)
    require(profile.get("id") == entry["profile_id"] and profile.get("identity_id") == entry["identity_id"]
            and profile.get("display_name") == expected["firstName"] + " " + expected["lastName"]
            and profile.get("phone_number") == expected["username"] and profile.get("phone_verified") is False,
            "Profile/phone ownership mismatch")
    wallets = http.json("GET", gateway + "/v1/wallets", token=token).get("wallets")
    require(isinstance(wallets, list) and len(wallets) == 1, "Expected exactly one fixture wallet")
    wallet = wallets[0]
    require(all(wallet.get(key) == value for key, value in {
        "id": entry["wallet_id"], "owner_id": entry["identity_id"], "ledger_account_id": entry["ledger_account_id"],
        "currency": "AED", "status": "ACTIVE", "provisioning_status": "READY"}.items()), "Wallet contract mismatch")
    return balance(http, gateway, token, entry)


def command(source, destination, destination_ordinal):
    return {"source_wallet_id": source["wallet_id"], "recipient_id": destination["identity_id"],
            "recipient_phone": seed.fixture(destination_ordinal)["username"], "currency": "AED", "amount_minor": 100}


def transfer_key(source, destination):
    return str(uuid.uuid5(uuid.NAMESPACE_URL,
        f"{seed.NAMESPACE}:baseline-p2p:{source['wallet_id']}:{destination['wallet_id']}"))


def save_checkpoint(path, manifest):
    """Use private atomic replacement, then flush before a payment dispatch."""
    seed.save_manifest(path, manifest)
    with path.open("r+b") as checkpoint:
        os.fsync(checkpoint.fileno())
    if os.name != "nt":
        descriptor = os.open(path.parent, os.O_RDONLY)
        try:
            os.fsync(descriptor)
        finally:
            os.close(descriptor)


def post_payment(http, gateway, token, body, key, statuses=(200, 202)):
    request = urllib.request.Request(gateway + "/v1/payments", json.dumps(body).encode(),
        {"Content-Type": "application/json", "Authorization": "Bearer " + token, "Idempotency-Key": key}, method="POST")
    try:
        response = http.opener.open(request, timeout=30)
    except urllib.error.HTTPError as error:
        response = error
    with response:
        raw = response.read(1048577)
        require(len(raw) <= 1048576 and response.status in statuses, "Payment request rejected")
        return json.loads(raw) if raw else None


def completed(http, gateway, token, payment, body, source, destination):
    payment_id = seed.identifier(payment.get("id"))
    for _ in range(90):
        require(payment.get("id") == payment_id and all(payment.get(k) == v for k, v in body.items())
                and payment.get("requester_id") == source["identity_id"]
                and payment.get("destination_wallet_id") == destination["wallet_id"], "Payment contract mismatch")
        if payment.get("status") == "COMPLETED":
            require(payment.get("rejection_reason") is None, "Completed payment has rejection reason")
            return payment_id
        require(payment.get("status") == "PENDING", "Payment rejected or invalid; preserve keys for investigation")
        time.sleep(1)
        payment = http.json("GET", gateway + "/v1/payments/" + payment_id, token=token)
    raise seed.Blocked("Payment outcome unresolved; rerun with the same manifest and transfer option")


def transfer_and_return(pair, gateway, users, manifest, save):
    """Caller holds manifest lock; save must durably persist before dispatch."""
    a, b = users["100"], users["101"]
    ha, ta = pair[0]
    hb, tb = pair[1]
    original = (amount_fils(100), amount_fils(101))
    shifted = (original[0] - 100, original[1] + 100)
    forward, reverse = command(a, b, 101), command(b, a, 100)
    fk, rk = transfer_key(a, b), transfer_key(b, a)

    def balances():
        return balance(ha, gateway, ta, a), balance(hb, gateway, tb, b)

    checkpoint = manifest.get("acceptance_transfer")
    if checkpoint is None:
        require(balances() == original, "Unowned balance shift; no payment will be sent")
        checkpoint = {"version": 1, "invocation_id": str(uuid.uuid4()), "phase": "FORWARD_INTENT",
                      "forward_key": fk, "return_key": rk, "forward_body": forward, "return_body": reverse,
                      "forward_id": None, "return_id": None}
        manifest["acceptance_transfer"] = checkpoint
        save()
    require(isinstance(checkpoint, dict) and type(checkpoint.get("version")) is int
            and checkpoint["version"] == 1 and checkpoint.get("forward_key") == fk
            and checkpoint.get("return_key") == rk and checkpoint.get("forward_body") == forward
            and checkpoint.get("return_body") == reverse, "Acceptance checkpoint contract mismatch")
    seed.identifier(checkpoint.get("invocation_id"))
    phase = checkpoint.get("phase")
    require(phase in ("FORWARD_INTENT", "FORWARD_CONFIRMED", "RETURN_INTENT", "DONE"), "Invalid acceptance phase")
    if phase == "FORWARD_INTENT":
        require(checkpoint.get("forward_id") is None and checkpoint.get("return_id") is None, "Invalid forward intent")
    else:
        seed.identifier(checkpoint.get("forward_id"))
        if phase == "DONE":
            seed.identifier(checkpoint.get("return_id"))
        else:
            require(checkpoint.get("return_id") is None, "Unexpected return checkpoint")

    def advance(phase, **fields):
        checkpoint.update(fields, phase=phase)
        save()

    def send(http, token, body, key, source, destination):
        return completed(http, gateway, token, post_payment(http, gateway, token, body, key), body, source, destination)

    def lookup(http, token, pid, body, source, destination):
        actual = completed(http, gateway, token,
            http.json("GET", gateway + "/v1/payments/" + pid, token=token), body, source, destination)
        require(actual == pid, "Durable payment identity changed")

    def replay(http, token, body, key, source, destination, pid, expected):
        require(balances() == expected, "Unexpected balances before replay")
        require(send(http, token, body, key, source, destination) == pid and balances() == expected,
                "Replay changed payment identity or balances")
        post_payment(http, gateway, token, dict(body, amount_minor=101), key, statuses=(409,))
        require(balances() == expected, "Conflicting payload changed balances")

    if phase == "FORWARD_INTENT":
        require(balances() in (original, shifted), "Forward intent has unexpected balances")
        pid = send(ha, ta, forward, fk, a, b)
        # No return was dispatched under this intent. COMPLETED alone never
        # proves money movement, and original balances cannot pass this phase.
        require(balances() == shifted, "Forward did not move exactly 100 fils")
        advance("FORWARD_CONFIRMED", forward_id=pid)
        phase = "FORWARD_CONFIRMED"
    if phase == "FORWARD_CONFIRMED":
        lookup(ha, ta, checkpoint["forward_id"], forward, a, b)
        replay(ha, ta, forward, fk, a, b, checkpoint["forward_id"], shifted)
        advance("RETURN_INTENT")
        phase = "RETURN_INTENT"
    if phase == "RETURN_INTENT":
        lookup(ha, ta, checkpoint["forward_id"], forward, a, b)
        require(balances() in (shifted, original), "Return intent has unexpected balances")
        pid = send(hb, tb, reverse, rk, b, a)
        require(balances() == original, "Return did not restore exact original balances")
        advance("DONE", return_id=pid)
    # Only a durable return checkpoint allows forward replay at original balances.
    lookup(ha, ta, checkpoint["forward_id"], forward, a, b)
    lookup(hb, tb, checkpoint["return_id"], reverse, b, a)
    replay(ha, ta, forward, fk, a, b, checkpoint["forward_id"], original)
    replay(hb, tb, reverse, rk, b, a, checkpoint["return_id"], original)
    require(balances() == original, "Return did not restore baseline balances")


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apply", action="store_true")
    parser.add_argument("--acknowledge-local-fixtures", action="store_true")
    parser.add_argument("--transfer-and-return", action="store_true")
    parser.add_argument("--gateway")
    parser.add_argument("--issuer")
    parser.add_argument("--ca-file")
    parser.add_argument("--manifest", type=Path)
    args = parser.parse_args(argv)
    if not args.apply:
        print("Plan: verify 200 synthetic identities, profiles, phones, ready AED wallets and deterministic funded balances. "
              + ("Also transfer/replay/return 100 fils between two fixtures. " if args.transfer_and_return else "")
              + "No requests sent.")
        return
    require(args.acknowledge_local_fixtures, "Apply requires --acknowledge-local-fixtures")
    gateway, issuer = targets(args.gateway, args.issuer)
    require(args.ca_file and Path(args.ca_file).is_file(), "Apply requires existing --ca-file")
    path = manifest_path(args.manifest)
    password = os.environ.get("SEED_TEST_USER_PASSWORD")
    require(password and len(password) >= 15, "Fixture credential environment is missing/invalid")
    with seed.manifest_lock(path):
        require(not path.is_symlink(), "Manifest must not be a symlink")
        seed.owner_only(path)
        manifest = json.loads(path.read_text(encoding="utf-8"))
        users = validate(manifest, gateway, issuer)
        if not args.transfer_and_return:
            checkpoint = manifest.get("acceptance_transfer")
            require(checkpoint is None or isinstance(checkpoint, dict) and checkpoint.get("phase") == "DONE",
                    "Acceptance transfer unresolved; rerun with --transfer-and-return")
        # Validate the two participants first; retain fresh sessions only for the
        # optional transfer, then check every balance after recovery/return.
        if args.transfer_and_return:
            with ExitStack() as stack:
                pair = [stack.enter_context(session(args.ca_file, issuer, gateway, n, users[str(n)], password)) for n in (100, 101)]
                for n, (http, token) in zip((100, 101), pair):
                    verify_user(http, gateway, token, n, users[str(n)])
                ha, ta = pair[0]
                hb, tb = pair[1]
                for http, token, foreign in ((ha, ta, users["101"]), (hb, tb, users["100"])):
                    http.json("GET", gateway + "/v1/wallets/" + foreign["wallet_id"] + "/balance", token=token, statuses=(404,))
                ha.json("GET", gateway + "/v1/wallets", statuses=(401,))
                transfer_and_return(pair, gateway, users, manifest, lambda: save_checkpoint(path, manifest))
        for n in range(100, 300):
            with session(args.ca_file, issuer, gateway, n, users[str(n)], password) as (http, token):
                require(verify_user(http, gateway, token, n, users[str(n)]) == amount_fils(n), "Funded balance mismatch")
        print("Verified: 200 identities, 200 profiles/phones, 200 ready AED wallets, 200 expected balances."
              + (" Transfer/return: 2 completed; replay and conflict checks passed; original balances restored." if args.transfer_and_return else ""))


if __name__ == "__main__":
    try:
        main()
    except seed.Blocked as error:
        print("Blocked: " + str(error), file=sys.stderr)
        sys.exit(1)
    except Exception:
        print("Blocked: acceptance failed; sensitive details suppressed. Preserve manifest and stable transfer keys before retrying.", file=sys.stderr)
        sys.exit(1)
