"""Offline guard/contract tests only; no simulated money movement is evidence."""
import copy
import importlib.util
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import uuid

spec = importlib.util.spec_from_file_location("verify", Path(__file__).with_name("verify-local-baseline.py"))
verify = importlib.util.module_from_spec(spec)
spec.loader.exec_module(verify)
GATEWAY = "https://localhost:8443"
ISSUER = GATEWAY + "/sso/realms/armoney"


def manifest():
    users = {}
    namespace = uuid.uuid5(uuid.NAMESPACE_URL, verify.seed.NAMESPACE)
    for n in range(100, 300):
        entry = {key: str(uuid.uuid5(uuid.NAMESPACE_URL, f"synthetic:{n}:{key}"))
                 for key in ("subject", "identity_id", "profile_id", "wallet_id", "ledger_account_id")}
        entry["status"] = "ready"
        entry["funding"] = {"status": "POSTED", "amount_fils": verify.amount_fils(n),
                            "funding_id": str(uuid.uuid5(namespace, f"{n}:{entry['ledger_account_id']}"))}
        users[str(n)] = entry
    return {"namespace": verify.seed.NAMESPACE, "count": 200, "gateway": GATEWAY, "issuer": ISSUER, "users": users}


class VerifyTests(unittest.TestCase):
    def test_real_checkpoint_save_roundtrip(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "manifest.json"
            expected = manifest()
            with verify.seed.manifest_lock(path):
                verify.save_checkpoint(path, expected)
                self.assertEqual(expected, json.loads(path.read_text(encoding="utf-8")))
                expected["acceptance_transfer"] = {"phase": "FORWARD_INTENT"}
                verify.save_checkpoint(path, expected)
                self.assertEqual(expected, json.loads(path.read_text(encoding="utf-8")))

    def test_offline_plan_never_constructs_http_or_reads_manifest(self):
        with patch.object(verify.seed, "Http", side_effect=AssertionError("network")), patch.object(verify, "manifest_path", side_effect=AssertionError("file")), patch("sys.stdout", new_callable=io.StringIO) as out:
            verify.main(["--transfer-and-return"])
        self.assertIn("No requests sent", out.getvalue())

    def test_apply_requires_acknowledgment_before_network(self):
        with patch.object(verify.seed, "Http", side_effect=AssertionError("network")):
            with self.assertRaises(verify.seed.Blocked):
                verify.main(["--apply"])

    def test_local_explicit_same_origin_targets(self):
        self.assertEqual((GATEWAY, ISSUER), verify.targets(GATEWAY, ISSUER))
        for gateway, issuer in ((None, ISSUER), (GATEWAY, None), ("https://example.com", ISSUER),
                                (GATEWAY, "https://127.0.0.1:8443/sso/realms/armoney"),
                                (GATEWAY + "/extra", ISSUER), ("http://localhost", ISSUER)):
            with self.assertRaises(verify.seed.Blocked):
                verify.targets(gateway, issuer)

    def test_manifest_requires_outside_regular_file(self):
        with self.assertRaises(verify.seed.Blocked):
            verify.manifest_path(Path(__file__))
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "manifest.json"
            with self.assertRaises(verify.seed.Blocked):
                verify.manifest_path(path)
            path.write_text("{}")
            self.assertEqual(path.resolve(), verify.manifest_path(path))

    def test_complete_manifest_and_algorithm(self):
        users = verify.validate(manifest(), GATEWAY, ISSUER)
        self.assertEqual(200, len(users))
        self.assertEqual(100000, verify.amount_fils(100))
        self.assertEqual(107919, verify.amount_fils(101))

    def test_manifest_rejects_incomplete_foreign_duplicate_or_pending(self):
        original = manifest()
        mutations = [lambda m: m.update(count=199), lambda m: m.update(namespace="other"),
                     lambda m: m.update(gateway="https://127.0.0.1:8443"),
                     lambda m: m["users"].pop("299"),
                     lambda m: m["users"]["101"].update(wallet_id=m["users"]["100"]["wallet_id"]),
                     lambda m: m["users"]["100"].update(status="pending"),
                     lambda m: m["users"]["100"]["funding"].update(status="pending"),
                     lambda m: m["users"]["100"]["funding"].update(amount_fils=100000.0),
                     lambda m: m["users"]["100"]["funding"].update(amount_fils=100001),
                     lambda m: m["users"]["100"]["funding"].update(funding_id=str(uuid.uuid4()))]
        for mutate in mutations:
            candidate = copy.deepcopy(original)
            mutate(candidate)
            with self.assertRaises(verify.seed.Blocked):
                verify.validate(candidate, GATEWAY, ISSUER)

    def test_keys_stable_directional_and_wallet_scoped(self):
        users = manifest()["users"]
        a, b = users["100"], users["101"]
        key = verify.transfer_key(a, b)
        self.assertEqual(key, verify.transfer_key(copy.deepcopy(a), copy.deepcopy(b)))
        self.assertNotEqual(key, verify.transfer_key(b, a))
        self.assertNotEqual(key, verify.transfer_key(a, dict(b, wallet_id=str(uuid.uuid4()))))
        self.assertEqual({"source_wallet_id": a["wallet_id"], "recipient_id": b["identity_id"],
                          "recipient_phone": "+971528000101", "currency": "AED", "amount_minor": 100}, verify.command(a, b, 101))

    def test_existing_manifest_lock_fails_closed(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "manifest.json"
            with verify.seed.manifest_lock(path):
                with self.assertRaises(verify.seed.Blocked):
                    with verify.seed.manifest_lock(path):
                        self.fail("Concurrent manifest access")
            self.assertFalse(path.with_name(path.name + ".lock").exists())

    def test_rejected_or_mismatched_payment_never_reported_complete(self):
        users = manifest()["users"]
        a, b = users["100"], users["101"]
        body = verify.command(a, b, 101)
        payment = dict(body, id=str(uuid.uuid4()), requester_id=a["identity_id"],
                       destination_wallet_id=b["wallet_id"], status="REJECTED")
        with self.assertRaises(verify.seed.Blocked):
            verify.completed(None, GATEWAY, "synthetic", payment, body, a, b)
        payment.update(status="COMPLETED", rejection_reason=None, amount_minor=101)
        with self.assertRaises(verify.seed.Blocked):
            verify.completed(None, GATEWAY, "synthetic", payment, body, a, b)

    def test_shifted_balances_without_durable_payment_do_not_post(self):
        class EmptyHistory:
            def json(self, *args, **kwargs):
                return {"payments": [], "next_cursor": None}
        users = manifest()["users"]
        pair = [(EmptyHistory(), "synthetic-a"), (EmptyHistory(), "synthetic-b")]
        with patch.object(verify, "balance", side_effect=[99900, 108019]), patch.object(verify, "post_payment", side_effect=AssertionError("must not post")):
            with self.assertRaises(verify.seed.Blocked):
                verify.transfer_and_return(pair, GATEWAY, users, {}, lambda: None)


class TransferOrchestrationTests(unittest.TestCase):
    """Synthetic control-flow tests; these do not prove ledger transactions."""
    def setUp(self):
        self.manifest = manifest()
        self.users = self.manifest["users"]
        self.amounts = {self.users[str(n)]["wallet_id"]: verify.amount_fils(n) for n in (100, 101)}
        self.saved = []
        self.payments = {}
        self.requests = []
        self.moving = True
        self.lose_direction = None
        self.new_posts = 0
        class Http:
            def __init__(inner, test):
                inner.test = test
            def json(inner, method, address, **kwargs):
                return next(copy.deepcopy(p) for p in inner.test.payments.values() if address.endswith(p["id"]))
        self.pair = [(Http(self), "a"), (Http(self), "b")]

    def save(self):
        self.saved.append(copy.deepcopy(self.manifest["acceptance_transfer"]))

    def post(self, http, gateway, token, body, key, statuses=(200, 202)):
        self.assertTrue(self.saved, "Intent must persist before dispatch")
        self.requests.append((key, statuses))
        if statuses == (409,):
            self.assertIn(key, self.payments)
            return {"error": "conflict"}
        if key not in self.payments:
            forward = body["source_wallet_id"] == self.users["100"]["wallet_id"]
            self.assertEqual("FORWARD_INTENT" if forward else "RETURN_INTENT", self.saved[-1]["phase"])
            source, destination = (self.users["100"], self.users["101"]) if forward else (self.users["101"], self.users["100"])
            payment = dict(body, id=str(uuid.uuid4()), requester_id=source["identity_id"],
                           destination_wallet_id=destination["wallet_id"], status="COMPLETED", rejection_reason=None)
            self.payments[key] = payment
            self.new_posts += 1
            if self.moving:
                self.amounts[source["wallet_id"]] -= 100
                self.amounts[destination["wallet_id"]] += 100
            if self.lose_direction == ("forward" if forward else "return"):
                self.lose_direction = None
                raise OSError("Synthetic lost response")
        return copy.deepcopy(self.payments[key])

    def run_flow(self, save=None):
        with patch.object(verify, "balance", side_effect=lambda h, g, t, e: self.amounts[e["wallet_id"]]), patch.object(verify, "post_payment", side_effect=self.post):
            verify.transfer_and_return(self.pair, GATEWAY, self.users, self.manifest, save or self.save)

    def test_fresh_flow_observes_exact_movement_and_persists_phases(self):
        self.run_flow()
        self.assertEqual(["FORWARD_INTENT", "FORWARD_CONFIRMED", "RETURN_INTENT", "DONE"], [s["phase"] for s in self.saved])
        self.assertEqual(2, self.new_posts)
        self.assertEqual(verify.amount_fils(100), self.amounts[self.users["100"]["wallet_id"]])

    def test_completed_without_money_movement_is_rejected(self):
        self.moving = False
        with self.assertRaisesRegex(verify.seed.Blocked, "Forward did not move"):
            self.run_flow()
        self.assertEqual(1, self.new_posts)
        self.assertEqual("FORWARD_INTENT", self.saved[-1]["phase"])

    def test_interrupted_forward_replays_owned_key_and_restores(self):
        self.lose_direction = "forward"
        with self.assertRaises(OSError):
            self.run_flow()
        self.assertEqual("FORWARD_INTENT", self.saved[-1]["phase"])
        first_key = next(iter(self.payments))
        self.run_flow()
        self.assertEqual(2, self.new_posts)
        self.assertEqual(first_key, self.manifest["acceptance_transfer"]["forward_key"])
        self.assertEqual("DONE", self.saved[-1]["phase"])

    def test_interrupted_return_replays_without_new_forward(self):
        self.lose_direction = "return"
        with self.assertRaises(OSError):
            self.run_flow()
        self.assertEqual("RETURN_INTENT", self.saved[-1]["phase"])
        self.run_flow()
        self.assertEqual(2, self.new_posts)
        self.assertEqual("DONE", self.saved[-1]["phase"])

    def test_completed_rerun_requires_saved_payment_ids_and_no_new_post(self):
        self.run_flow()
        checkpoint = copy.deepcopy(self.manifest["acceptance_transfer"])
        self.run_flow()
        self.assertEqual(2, self.new_posts)
        self.assertEqual(checkpoint, self.manifest["acceptance_transfer"])

    def test_unrelated_identical_history_shift_rejected_before_any_post(self):
        a, b = self.users["100"], self.users["101"]
        self.amounts[a["wallet_id"]] -= 100
        self.amounts[b["wallet_id"]] += 100
        self.payments["unrelated-key"] = dict(verify.command(a, b, 101), id=str(uuid.uuid4()),
            requester_id=a["identity_id"], destination_wallet_id=b["wallet_id"], status="COMPLETED")
        with self.assertRaisesRegex(verify.seed.Blocked, "Unowned balance shift"):
            self.run_flow()
        self.assertEqual([], self.requests)
        self.assertEqual([], self.saved)

    def test_failed_intent_persistence_prevents_dispatch(self):
        def failed_save():
            raise OSError("Synthetic disk failure")
        with self.assertRaises(OSError):
            self.run_flow(failed_save)
        self.assertEqual([], self.requests)


if __name__ == "__main__":
    unittest.main()
