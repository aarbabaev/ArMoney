"""Synthetic orchestration regressions only; these never establish provider authority."""
import importlib.util
import json
from pathlib import Path
import threading
import time
import unittest
from unittest.mock import patch

SPEC = importlib.util.spec_from_file_location("concurrency_case", Path(__file__).with_name("test_production_provider_concurrency.py"))
M = importlib.util.module_from_spec(SPEC); SPEC.loader.exec_module(M)


class Harness(unittest.TestCase):
    def test_two_real_call_slots_overlap_without_swapping_forms_or_nonces(self):
        gate = threading.Barrier(2)
        calls = []
        attempts = [({"code": "private-code-" + str(i)}, "private-nonce-" + str(i)) for i in range(2)]
        wires = [object(), object()]

        def token(wire, form, nonce):
            index = wires.index(wire)
            self.assertIs(attempts[index][0], form)
            self.assertEqual(attempts[index][1], nonce)
            calls.append(index)
            gate.wait(timeout=1)
            return {"access_token": "private-token-" + str(index)}

        with patch.object(M.C, "token_response", side_effect=token):
            result = M.simultaneous(wires, attempts, time.monotonic() + 2)
        self.assertEqual({0, 1}, set(calls))
        self.assertEqual(2, len(result))

    def test_worker_error_and_expired_budget_refuse_without_exception_leakage(self):
        with patch.object(M.C, "token_response", side_effect=RuntimeError("SECRET-token-response")):
            with self.assertRaises(M.C.Rejected) as failure:
                M.simultaneous([object(), object()], [({}, "n1"), ({}, "n2")], time.monotonic() + 1)
        self.assertNotIn("SECRET", str(failure.exception))
        with self.assertRaises(M.C.Rejected):
            M.browser({}, time.monotonic() - 1, lambda _: type("Wire", (), {})())

    def test_cookie_jars_must_be_independent_and_receipt_never_contains_private_inputs(self):
        shared = object()
        class Wire:
            def __init__(self, data): self.cookies = shared
        with self.assertRaises(M.C.Rejected):
            M.execute({}, factory=Wire)
        for passed in (False, True):
            result = M.receipt(passed)
            self.assertEqual({"case", "status", "positive_confirmations", "positive_bank_sessions",
                              "negative_no_side_effects", "whole_migration_acceptance"}, set(result))
            self.assertFalse(result["whole_migration_acceptance"])
            self.assertEqual(2 if passed else 0, result["positive_bank_sessions"])
            self.assertNotIn("token", json.dumps(result))

    def scenario(self, *, duplicate_event=False, drift=False, negative_status=400, gateway_event_drift=None):
        state = {"sessions": 0, "events": [], "issued": 0, "requests": 0}
        wires = []
        class Wire:
            def __init__(self, data):
                self.data, self.cookies = data, object(); wires.append(self)
            def request(self, url, *, form):
                state["requests"] += 1
                self_outer.assertNotEqual(form["code"].split("-")[-1], form["code_verifier"].split("-")[-1])
                return negative_status, {}, b'{"error":"invalid_grant"}'
        self_outer = self
        def fresh(wire):
            index = state["issued"]; state["issued"] += 1
            return ({"code": "code-" + str(index), "code_verifier": "verifier-" + str(index)},
                    "nonce-" + str(index), "state-" + str(index), "callback-" + str(index))
        def outcomes(_):
            return {"registration_active": True, "projection_acknowledged": True,
                    "bank_session_count": state["sessions"], "confirmed_event_ids": sorted(state["events"])}
        def redeem(wires, attempts, deadline):
            state["events"] = ["event-0"] if duplicate_event else ["event-0", "event-1"]
            return [{"access_token": "token-0"}, {"access_token": "token-1"}]
        def introspect(_, token): return {"armoney_login_event_id": "event-0" if duplicate_event else token.replace("token", "event")}
        def gateway(wire, token, expected):
            state["sessions"] += 0 if drift else 1
            if gateway_event_drift == "extra": state["events"].append("event-extra")
            if gateway_event_drift == "lost": state["events"].pop()
        with patch.object(M, "callback_code", side_effect=fresh), patch.object(M, "simultaneous", side_effect=redeem), \
             patch.object(M.C, "outcomes", side_effect=outcomes), patch.object(M.C, "introspect", side_effect=introspect), \
             patch.object(M.C, "proof"), patch.object(M.C, "confirmation_ids", side_effect=set), \
             patch.object(M.C, "gateway", side_effect=gateway), patch.object(M.C, "callback", side_effect=M.C.Rejected("callback_state")):
            result = M.execute({"origin": "https://fixture.example"}, factory=Wire)
        self.assertEqual(5, len(wires))
        self.assertEqual(4, state["issued"])
        self.assertEqual(2, state["requests"])
        self.assertEqual(5, len({id(w.cookies) for w in wires}))
        self.assertEqual(1, len({w.deadline for w in wires}))
        return result

    def test_synthetic_shape_runs_positive_then_separate_negative_pairs(self):
        self.assertEqual("scoped_passed", self.scenario()["status"])

    def test_duplicate_events_wrong_bank_count_or_successful_cross_verifier_refuse(self):
        for kwargs in ({"duplicate_event": True}, {"drift": True}, {"negative_status": 200}):
            with self.subTest(kwargs=kwargs), self.assertRaises(M.C.Rejected):
                self.scenario(**kwargs)

    def test_extra_or_lost_confirmation_during_gateway_never_reports_two(self):
        for drift in ("extra", "lost"):
            with self.subTest(drift=drift), self.assertRaises(M.C.Rejected):
                self.scenario(gateway_event_drift=drift)


if __name__ == "__main__": unittest.main()
