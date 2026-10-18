#!/usr/bin/env python3
"""Browser-only stock-profile checks. Root owns profile setup and independent DB evidence."""
import argparse
import base64
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import secrets
import time
import urllib.parse
import uuid

SPEC = importlib.util.spec_from_file_location("stock_core", Path(__file__).with_name("test_production_provider_acceptance.py"))
C = importlib.util.module_from_spec(SPEC); SPEC.loader.exec_module(C)
ORIGIN = "https://fixture.local:8443"
MASTER_CLIENT = "armoney-production-stock-master"
MASTER_CALLBACK = ORIGIN + "/oauth/stock-master/callback"


def configuration(raw):
    value = C.parse_json(raw)
    C.require(type(value) is dict and value.get("mode") in {"master", "off"}, "stock_config")
    mode = value["mode"]
    C.require(set(value) == {"mode", "acceptance", "master" if mode == "master" else "profile"}, "stock_config")
    data = C.configuration(json.dumps(value["acceptance"]).encode())
    C.require(all(data[k] == ORIGIN for k in ("origin", "gateway_origin", "controller_origin")), "stock_origin")
    if mode == "master":
        master = value["master"]
        C.require(type(master) is dict and set(master) == {"username", "password", "subject", "client_secret"}, "master_config")
        C.require(type(master["username"]) is str and 1 <= len(master["username"]) <= 128
                  and all(33 <= ord(c) < 127 for c in master["username"]), "master_config")
        C.secret(master["password"]); C.secret(master["client_secret"])
        C.require(len({master["password"], master["client_secret"], data["password"], data["controller_key"], data["introspection_secret"]}) == 5, "credential_alias")
        C.require(type(master["subject"]) is str and str(uuid.UUID(master["subject"])) == master["subject"], "master_subject")
    else:
        C.require(value["profile"] == {"browser_flow": "browser", "registration_flow": "registration",
            "proof_enabled": False, "verification_enabled": False, "observation_enabled": False}, "off_profile")
        C.require(all(type(value["profile"][k]) is bool for k in ("proof_enabled", "verification_enabled", "observation_enabled")), "off_profile")
    return value, data


def receipt(mode, passed=False):
    return {"case": "master_stock" if mode == "master" else "disabled_stock", "status": "browser_verified" if passed else "not_verified",
            "browser_verified": passed, "db_no_mutations_verified": False, "whole_case_verified": False}


def absent(tokens):
    C.require(type(tokens) is dict and type(tokens.get("access_token")) is str, "token_schema")
    for name in ("access_token", "id_token", "refresh_token"):
        if name in tokens:
            C.require(not C.CLAIMS.intersection(C.inspect_jwt(tokens[name])), "stock_proof")


def follow_master(wire, page):
    for _ in range(8):
        status, headers, raw, url = page
        if status not in (302, 303): return page
        target = urllib.parse.urljoin(url, headers.get("Location", ""))
        if target.partition("?")[0] == MASTER_CALLBACK: return page
        C.require(target.startswith(ORIGIN + "/sso/"), "master_redirect")
        status, headers, raw = wire.request(target)
        page = status, headers, raw, target
    raise C.Rejected("master_redirect_bound")


def master_browser(wire, master):
    issuer = ORIGIN + "/sso/realms/master"
    verifier, state, nonce = [secrets.token_urlsafe(32) for _ in range(3)]
    challenge = base64.urlsafe_b64encode(hashlib.sha256(verifier.encode()).digest()).decode().rstrip("=")
    url = issuer + "/protocol/openid-connect/auth?" + urllib.parse.urlencode({"client_id": MASTER_CLIENT,
        "redirect_uri": MASTER_CALLBACK, "response_type": "code", "scope": "openid", "state": state,
        "nonce": nonce, "code_challenge": challenge, "code_challenge_method": "S256", "prompt": "login", "max_age": "0"})
    status, headers, raw = wire.request(url)
    page = follow_master(wire, (status, headers, raw, url))
    page = follow_master(wire, C.submit(wire, page, "kc-form-login", {"username": master["username"], "password": master["password"]}))
    C.expected_status(page[0], 302)
    code = C.callback(page[1].get("Location", ""), MASTER_CALLBACK, state, issuer)
    basic = "Basic " + base64.b64encode((MASTER_CLIENT + ":" + master["client_secret"]).encode()).decode()
    status, _, raw = wire.request(issuer + "/protocol/openid-connect/token", headers={"Authorization": basic}, form={
        "grant_type": "authorization_code", "redirect_uri": MASTER_CALLBACK, "code": code, "code_verifier": verifier})
    C.expected_status(status, 200); tokens = C.parse_json(raw)
    absent(tokens); C.require(tokens.get("token_type", "").lower() == "bearer", "token_schema")
    C.check_nonce(tokens, nonce, issuer, MASTER_CLIENT)
    status, _, raw = wire.request(issuer + "/protocol/openid-connect/token/introspect",
        headers={"Authorization": basic}, form={"token": tokens["access_token"]})
    C.expected_status(status, 200); facts = C.parse_json(raw)
    C.proof(facts, False)
    C.require(facts.get("iss") == issuer and facts.get("client_id") == MASTER_CLIENT
              and facts.get("sub") == master["subject"], "master_binding")
    # ID nonce/absence inspection is not a claim of independent ID-signature validation.


def execute(value, data, *, factory=C.Wire, deadline=None):
    deadline = time.monotonic() + 180 if deadline is None else deadline
    C.require(time.monotonic() < deadline, "deadline")
    wire = factory(data); wire.deadline = deadline; wire.control_pacer = C.ControlPacer(deadline)
    before = C.outcomes(wire)
    if value["mode"] == "master":
        master_browser(wire, value["master"])
    else:
        C.require(before["registration_active"] and before["projection_acknowledged"], "verified_owner")
        form, nonce = C.fresh_code(wire)  # ordinary openid; online session contract unchanged
        tokens = C.token_response(wire, form, nonce)
        absent(tokens); C.no_proof(wire, tokens)
        C.gateway(wire, tokens["access_token"], 401)
    after = C.outcomes(wire)
    C.require(after["confirmed_event_ids"] == before["confirmed_event_ids"]
              and after["bank_session_count"] == before["bank_session_count"], "stock_mutation")
    C.require(time.monotonic() < deadline, "deadline")
    return receipt(value["mode"], True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", required=True); parser.add_argument("--result", required=True)
    args = parser.parse_args(); result, fd = receipt("master"), None
    try:
        value, data = configuration(C.read_private(args.input))
        result = receipt(value["mode"])
        fd = os.open(args.result, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
        os.write(fd, json.dumps(result).encode()); os.fsync(fd)
        result = execute(value, data)
    except Exception: pass
    finally:
        if fd is not None:
            os.lseek(fd, 0, os.SEEK_SET); os.ftruncate(fd, 0)
            os.write(fd, json.dumps(result, separators=(",", ":")).encode()); os.fsync(fd); os.close(fd)
    return 0 if result["browser_verified"] else 1


if __name__ == "__main__": raise SystemExit(main())
