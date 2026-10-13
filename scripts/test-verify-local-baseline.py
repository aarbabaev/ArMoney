"""Offline guard/contract tests only; no simulated money movement is evidence."""
import copy
import importlib.util
import io
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
                verify.transfer_and_return(pair, GATEWAY, users)


if __name__ == "__main__":
    unittest.main()
