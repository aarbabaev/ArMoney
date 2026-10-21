#!/usr/bin/env python3
"""Opt-in real production-provider concurrency case; requires an already verified isolated owner.

Sibling acceptance helpers own the fixed private configuration and HTTP contract.
Thread deadlines bound reporting, not a claim of physical DNS/socket termination;
the root-owned isolated runner must reap the entire process on failure.
"""
import argparse
import importlib.util
import json
import os
from pathlib import Path
import threading
import time

_SPEC = importlib.util.spec_from_file_location("production_concurrency_core", Path(__file__).with_name("test_production_provider_acceptance.py"))
C = importlib.util.module_from_spec(_SPEC)
_SPEC.loader.exec_module(C)
CASE = "concurrent_same_client_callbacks"


def receipt(passed=False):
    return {"case": CASE, "status": "scoped_passed" if passed else "not_verified",
            "positive_confirmations": 2 if passed else 0, "positive_bank_sessions": 2 if passed else 0,
            "negative_no_side_effects": passed, "whole_migration_acceptance": False}


def browser(data, deadline, factory):
    wire = factory(data)
    C.require(time.monotonic() < deadline, "deadline")
    wire.deadline = deadline
    wire.control_pacer = C.ControlPacer(deadline)
    return wire


def callback_code(wire):
    page, verifier, state, nonce = C.authorize(wire)  # ordinary openid: never offline_access
    page = C.follow(wire, C.submit(wire, page, "kc-form-login", {
        "username": wire.data["username"], "password": wire.data["password"]}))
    C.expected_status(page[0], 302)
    location = page[1].get("Location", "")
    code = C.callback(location, C.CALLBACK, state, wire.data["origin"] + "/sso/realms/armoney")
    return ({"grant_type": "authorization_code", "client_id": C.CLIENT, "redirect_uri": C.CALLBACK,
             "code": code, "code_verifier": verifier}, nonce, state, location)


def simultaneous(wires, attempts, deadline):
    gate = threading.Barrier(3)
    results, failures = [None, None], [False, False]

    def redeem(index):
        try:
            gate.wait(timeout=max(.001, deadline - time.monotonic()))
            results[index] = C.token_response(wires[index], attempts[index][0], attempts[index][1])
        except Exception:
            failures[index] = True  # never retain/log exception or provider response

    threads = [threading.Thread(target=redeem, args=(i,), daemon=True) for i in range(2)]
    for thread in threads:
        thread.start()
    try:
        gate.wait(timeout=max(.001, deadline - time.monotonic()))
    except threading.BrokenBarrierError:
        gate.abort()
    for thread in threads:
        thread.join(timeout=max(0, deadline - time.monotonic()))
    C.require(not any(t.is_alive() for t in threads) and not any(failures)
              and all(type(x) is dict for x in results) and time.monotonic() < deadline, "concurrent_exchange")
    return results


def execute(data, *, factory=C.Wire, deadline=None):
    deadline = time.monotonic() + 360 if deadline is None else deadline
    control = browser(data, deadline, factory)
    wires = [browser(data, deadline, factory) for _ in range(2)]
    for wire in wires:
        wire.control_pacer = control.control_pacer  # one shared controller budget; token workers never call control
    C.require(wires[0].cookies is not wires[1].cookies, "cookie_isolation")
    before = C.outcomes(control)
    C.require(before["registration_active"] and before["projection_acknowledged"], "verified_owner")
    attempts = [callback_code(w) for w in wires]
    for index in (0, 1, 2):  # verifier, nonce, state must all differ
        slot = [attempts[i][0]["code_verifier"] if index == 0 else attempts[i][index] for i in range(2)]
        C.require(slot[0] != slot[1], "distinct_attempts")
    C.require(attempts[0][0]["code"] != attempts[1][0]["code"], "distinct_attempts")
    tokens = simultaneous(wires, attempts, deadline)
    events = set()
    for wire, token in zip(wires, tokens):
        facts = C.introspect(wire, token["access_token"])
        C.proof(facts, True)
        events.add(facts["armoney_login_event_id"])
    C.require(len(events) == 2, "distinct_events")
    confirmed = C.outcomes(control)
    C.require(C.confirmation_ids(confirmed["confirmed_event_ids"]) - C.confirmation_ids(before["confirmed_event_ids"]) == events
              and C.confirmation_ids(before["confirmed_event_ids"]) <= C.confirmation_ids(confirmed["confirmed_event_ids"]), "confirmation_delta")
    for wire, token in zip(wires, tokens):
        C.gateway(wire, token["access_token"], 200)
        C.require(C.outcomes(control)["confirmed_event_ids"] == confirmed["confirmed_event_ids"], "gateway_confirmation_drift")
    after = C.outcomes(control)
    C.require(after["bank_session_count"] == before["bank_session_count"] + 2
              and after["confirmed_event_ids"] == confirmed["confirmed_event_ids"], "session_delta")
    # Separate fresh pairs: failed PKCE can burn a code and must not corrupt the positive case.
    negatives = [browser(data, deadline, factory) for _ in range(2)]
    for wire in negatives:
        wire.control_pacer = control.control_pacer
    pair = [callback_code(w) for w in negatives]
    C.require(pair[0][0]["code_verifier"] != pair[1][0]["code_verifier"] and pair[0][2] != pair[1][2], "distinct_attempts")
    for i in range(2):
        rejected = False
        try:
            C.callback(pair[i][3], C.CALLBACK, pair[1-i][2], data["origin"] + "/sso/realms/armoney")
        except C.Rejected:
            rejected = True
        C.require(rejected, "cross_state")
        form = dict(pair[i][0]); form["code_verifier"] = pair[1-i][0]["code_verifier"]
        status, _, raw = negatives[i].request(data["origin"] + "/sso/realms/armoney/protocol/openid-connect/token", form=form)
        C.expected_status(status, 400)
        error = C.parse_json(raw)
        C.require(type(error) is dict and error.get("error") == "invalid_grant" and "access_token" not in error, "cross_verifier")
    final = C.outcomes(control)
    C.require(final["bank_session_count"] == after["bank_session_count"]
              and final["confirmed_event_ids"] == confirmed["confirmed_event_ids"], "negative_side_effects")
    return receipt(True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", required=True); parser.add_argument("--result", required=True)
    args = parser.parse_args()
    result, fd = receipt(), None
    try:
        data = C.configuration(C.read_private(args.input))
        fd = os.open(args.result, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
        os.write(fd, json.dumps(result).encode()); os.fsync(fd)
        result = execute(data)
    except Exception:
        pass  # fixed safe failed receipt only
    finally:
        if fd is not None:
            os.lseek(fd, 0, os.SEEK_SET); os.ftruncate(fd, 0)
            os.write(fd, json.dumps(result, separators=(",", ":")).encode()); os.fsync(fd); os.close(fd)
    return 0 if result["status"] == "scoped_passed" else 1


if __name__ == "__main__":
    raise SystemExit(main())
