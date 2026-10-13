"""Offline runner safety tests; real ledger correctness is tested separately on PostgreSQL."""
import copy
import importlib.util
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch
import uuid

spec = importlib.util.spec_from_file_location("fund", Path(__file__).with_name("fund-local-users.py"))
fund = importlib.util.module_from_spec(spec)
spec.loader.exec_module(fund)
seed = fund.seed
GATEWAY = "https://localhost:8443"
ENDPOINT = "unix:///var/run/docker.sock"
ISSUER = GATEWAY + "/sso/realms/armoney"


def manifest(count=1):
    return {"namespace": seed.NAMESPACE, "count": count, "gateway": GATEWAY, "issuer": ISSUER,
            "users": {str(n): dict(status="ready", **{key: str(uuid.uuid4()) for key in
                ("subject", "identity_id", "profile_id", "wallet_id", "ledger_account_id")})
                for n in range(100, 100 + count)}}


class Http:
    def __init__(self, responses):
        self.responses = iter(responses)
        self.requests = []

    def json(self, method, address, data=None, **kwargs):
        self.requests.append((method, address))
        return next(self.responses)


class FundingTests(unittest.TestCase):
    def args(self, path):
        return ["fund", "--apply", "--acknowledge-local-fixtures", "--count", "1",
                "--manifest", str(path), "--ca-file", "synthetic-ca"]

    def test_default_is_offline(self):
        with patch.object(fund.sys, "argv", ["fund"]), patch.object(fund, "docker") as docker, patch.object(seed, "Http") as http:
            fund.main()
            docker.assert_not_called()
            http.assert_not_called()

    def test_apply_requires_acknowledgment(self):
        with patch.object(fund.sys, "argv", ["fund", "--apply"]), patch.object(fund, "docker") as docker:
            with self.assertRaises(seed.Blocked):
                fund.main()
            docker.assert_not_called()

    def test_deterministic_ids_amounts_and_bounds(self):
        account = str(uuid.uuid4())
        self.assertEqual(fund.funding_id(100, account), fund.funding_id("100", account))
        self.assertEqual(5, uuid.UUID(fund.funding_id(100, account)).version)
        self.assertNotEqual(fund.funding_id(100, account), fund.funding_id(101, account))
        amounts = [fund.amount_fils(n) for n in range(100, 1000)]
        self.assertTrue(all(type(a) is int and 100000 <= a <= 500000 for a in amounts))
        self.assertEqual(900, len(set(amounts)))

    def test_manifest_rejects_incomplete_foreign_or_duplicate_entries(self):
        original = manifest(2)
        mutations = [lambda m: m.update(namespace="other"), lambda m: m.update(count=1),
                     lambda m: m.update(gateway="https://other"), lambda m: m.update(issuer="https://other"),
                     lambda m: m["users"].pop("101"), lambda m: m["users"]["101"].update(status="pending")]
        for key in ("subject", "identity_id", "profile_id", "wallet_id", "ledger_account_id"):
            mutations.append(lambda m, key=key: m["users"]["101"].update({key: m["users"]["100"][key]}))
        for mutate in mutations:
            value = copy.deepcopy(original)
            mutate(value)
            with self.assertRaises(seed.Blocked):
                fund.validate(value, 2, GATEWAY, ISSUER)
        fund.validate(original, 2, GATEWAY, ISSUER)

    def test_changed_checkpoint_cannot_refill(self):
        value = manifest()
        entry = value["users"]["100"]
        checkpoint = {"funding_id": fund.funding_id(100, entry["ledger_account_id"]), "amount_fils": 100000, "status": "POSTED"}
        entry["funding"] = checkpoint
        fund.validate(value, 1, GATEWAY, ISSUER)
        for key, changed in (("funding_id", str(uuid.uuid4())), ("amount_fils", 100001), ("status", "rejected")):
            entry["funding"] = dict(checkpoint, **{key: changed})
            with self.assertRaises(seed.Blocked):
                fund.validate(value, 1, GATEWAY, ISSUER)

    def test_noncanonical_uuid_is_rejected(self):
        value = manifest()
        value["users"]["100"]["identity_id"] = "AAAAAAAA-AAAA-AAAA-AAAA-AAAAAAAAAAAA"
        with self.assertRaises(seed.Blocked):
            fund.validate(value, 1, GATEWAY, ISSUER)

    def test_invalid_manifest_stops_before_docker_or_network(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "manifest.json"
            value = manifest()
            value["users"]["100"]["status"] = "identity-created"
            path.write_text(json.dumps(value))
            with patch.object(fund.sys, "argv", self.args(path)), patch.dict(fund.os.environ, {"SEED_TEST_USER_PASSWORD": "synthetic-password"}), \
                    patch.object(seed, "Http") as http, patch.object(fund, "docker") as docker, patch.object(seed, "owner_only"):
                with self.assertRaises(seed.Blocked):
                    fund.main()
                http.assert_not_called()
                docker.assert_not_called()
            self.assertEqual(value, json.loads(path.read_text()))

    def test_checkout_manifest_is_rejected_before_lock(self):
        path = Path(__file__).with_name("synthetic-manifest.json")
        with patch.object(fund.sys, "argv", self.args(path)), patch.object(seed, "manifest_lock") as lock:
            with self.assertRaisesRegex(seed.Blocked, "outside checkout"):
                fund.main()
            lock.assert_not_called()

    def test_container_checks_exact_labels_and_running(self):
        container = "a" * 64
        good = {"com.docker.compose.project": "arman-bank", "com.docker.compose.service": "ledger-service"}
        with patch.object(fund, "docker", side_effect=[container, "true", json.dumps(good)]):
            self.assertEqual(container, fund.ledger_container(ENDPOINT))
        for running, labels in (("false", good), ("true", dict(good, **{"com.docker.compose.project": "ci"})),
                                ("true", dict(good, **{"com.docker.compose.service": "wallet-service"}))):
            with patch.object(fund, "docker", side_effect=[container, running, json.dumps(labels)]):
                with self.assertRaises(seed.Blocked):
                    fund.ledger_container(ENDPOINT)
        with patch.object(fund, "docker", return_value=container + "\n" + "b" * 64):
            with self.assertRaises(seed.Blocked):
                fund.ledger_container(ENDPOINT)

    def test_wallet_status_and_mapping_checked_before_funding(self):
        entry = manifest()["users"]["100"]
        wallet = {"id": entry["wallet_id"], "owner_id": entry["identity_id"], "currency": "AED", "status": "ACTIVE",
                  "provisioning_status": "READY", "ledger_account_id": entry["ledger_account_id"]}
        for change in ({}, {"provisioning_status": "PENDING"}, {"currency": "USD"}, {"owner_id": str(uuid.uuid4())},
                       {"ledger_account_id": str(uuid.uuid4())}, {"status": "CLOSED"}):
            http = Http([{"sub": entry["subject"]}, {"access_token": "synthetic"}, {"id": entry["identity_id"]},
                         {"wallets": [dict(wallet, **change)]}, None, None])
            with patch.object(seed, "login", return_value={"access_token": "synthetic", "refresh_token": "synthetic"}):
                if change:
                    with self.assertRaises(seed.Blocked):
                        fund.ready_wallet(http, GATEWAY, ISSUER, 100, entry, "synthetic-password")
                else:
                    fund.ready_wallet(http, GATEWAY, ISSUER, 100, entry, "synthetic-password")
            self.assertTrue(all(method == "GET" or address.endswith(("/auth/sso", "/logout")) for method, address in http.requests))

    def test_lock_precedes_read_and_acl(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "manifest.json"
            path.write_text(json.dumps(manifest()))
            with seed.manifest_lock(path), patch.object(fund.sys, "argv", self.args(path)), \
                    patch.dict(fund.os.environ, {"SEED_TEST_USER_PASSWORD": "synthetic-password"}), \
                    patch.object(Path, "read_text") as read, patch.object(seed, "owner_only") as acl:
                with self.assertRaisesRegex(seed.Blocked, "locked"):
                    fund.main()
                read.assert_not_called()
                acl.assert_not_called()

    def test_uncertain_result_retains_checkpoint_and_replays_same_command(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "manifest.json"
            path.write_text(json.dumps(manifest()))
            commands = []
            def interrupted(container, entry, endpoint):
                saved = json.loads(path.read_text())["users"]["100"]["funding"]
                self.assertEqual("pending", saved["status"])
                self.assertTrue(path.with_name(path.name + ".lock").exists())
                commands.append(copy.deepcopy(entry["funding"]))
                raise subprocess.TimeoutExpired("synthetic", 1)
            with patch.object(fund.sys, "argv", self.args(path)), patch.dict(fund.os.environ, {"SEED_TEST_USER_PASSWORD": "synthetic-password"}), \
                    patch.object(seed, "Http", return_value=Http([{"issuer": ISSUER}] * 3)), \
                    patch.object(fund, "ledger_container", return_value="a" * 64), patch.object(fund, "ready_wallet"), \
                    patch.object(seed, "owner_only"), patch.object(fund, "selected_endpoint", return_value=ENDPOINT):
                with patch.object(fund, "post", side_effect=interrupted):
                    with self.assertRaises(subprocess.TimeoutExpired):
                        fund.main()
                with patch.object(fund, "post", side_effect=lambda container, entry, endpoint: commands.append(copy.deepcopy(entry["funding"]))):
                    fund.main()
                    fund.main()
            self.assertEqual(commands[0], commands[1])
            self.assertEqual(commands[1]["funding_id"], commands[2]["funding_id"])
            self.assertEqual("POSTED", json.loads(path.read_text())["users"]["100"]["funding"]["status"])
            self.assertFalse(path.with_name(path.name + ".lock").exists())

    def test_post_requires_exact_receipt_and_dual_gates(self):
        entry = manifest()["users"]["100"]
        entry["funding"] = {"funding_id": fund.funding_id(100, entry["ledger_account_id"]), "amount_fils": 100000, "status": "pending"}
        receipt = {"funding_id": entry["funding"]["funding_id"], "account_id": entry["ledger_account_id"], "status": "POSTED"}
        with patch.object(fund, "docker", return_value="JVM log\n" + json.dumps(receipt)) as docker:
            fund.post("a" * 64, entry, ENDPOINT)
            args = docker.call_args.args
            self.assertIn("APP_ENV=local", args)
            self.assertIn("ARMONEY_LOCAL_DEMO_FUNDING=I_UNDERSTAND_SYNTHETIC_FUNDS", args)
            self.assertIn("/opt/service/lib/*", args)
        for output in ("", json.dumps(dict(receipt, status="PENDING")), json.dumps(receipt) + "\n" + json.dumps(receipt)):
            with patch.object(fund, "docker", return_value=output):
                with self.assertRaises(seed.Blocked):
                    fund.post("a" * 64, entry, ENDPOINT)

    def test_docker_failure_is_sanitized_and_bounded(self):
        with patch.object(fund.subprocess, "run", return_value=subprocess.CompletedProcess([], 1, "secret", "password")) as run:
            with self.assertRaises(seed.Blocked) as failure:
                fund.docker("ps", endpoint=ENDPOINT)
            self.assertNotIn("secret", str(failure.exception))
            self.assertNotIn("password", str(failure.exception))
            self.assertEqual(90, run.call_args.kwargs["timeout"])
            self.assertTrue(run.call_args.kwargs["capture_output"])

    def test_remote_host_override_rejected_before_subprocess(self):
        for host in ("ssh://synthetic-unintended-host", "tcp://127.0.0.1:2375", "tcp://remote:2376",
                     "npipe:////remote/pipe/docker_engine", "unix:///tmp/untrusted.sock"):
            with patch.dict(fund.os.environ, {"DOCKER_HOST": host}, clear=True), patch.object(fund.subprocess, "run") as run:
                with self.assertRaises(seed.Blocked):
                    fund.selected_endpoint()
                run.assert_not_called()

    def test_remote_selected_context_never_reaches_daemon(self):
        for environment in ({}, {"DOCKER_CONTEXT": "remote"}, {"DOCKER_HOST": ENDPOINT, "DOCKER_CONTEXT": "remote"}):
            with patch.dict(fund.os.environ, environment, clear=True), \
                    patch.object(fund.subprocess, "run", return_value=subprocess.CompletedProcess([], 0, "ssh://synthetic-unintended-host\n", "")) as run:
                with self.assertRaises(seed.Blocked):
                    fund.selected_endpoint()
                self.assertEqual(1, run.call_count)
                self.assertEqual(["docker", "context", "inspect"], run.call_args.args[0][:3])
                self.assertNotIn("exec", run.call_args.args[0])

    def test_local_endpoint_frozen_and_overrides_removed_for_all_daemon_commands(self):
        for endpoint in (ENDPOINT, "npipe:////./pipe/docker_engine", "npipe:////./pipe/dockerDesktopLinuxEngine"):
            with patch.dict(fund.os.environ, {"DOCKER_CONTEXT": "desktop-local"}, clear=True), \
                    patch.object(fund.subprocess, "run", return_value=subprocess.CompletedProcess([], 0, endpoint + "\n", "")):
                selected = fund.selected_endpoint()
            # A context/host change after selection cannot retarget ps, inspect or exec.
            with patch.dict(fund.os.environ, {"DOCKER_HOST": "ssh://remote", "DOCKER_CONTEXT": "remote", "DOCKER_TLS": "1",
                            "DOCKER_TLS_VERIFY": "1", "DOCKER_CERT_PATH": "synthetic"}, clear=True), \
                    patch.object(fund.subprocess, "run", return_value=subprocess.CompletedProcess([], 0, "", "")) as run:
                for command in ("ps", "inspect", "exec"):
                    fund.docker(command, endpoint=selected)
                    self.assertEqual(["docker", "--host", endpoint, command], run.call_args.args[0])
                    self.assertFalse(any(key.startswith("DOCKER_") for key in run.call_args.kwargs["env"]))

    def test_remote_context_apply_preserves_manifest_without_funding(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "manifest.json"
            value = manifest()
            path.write_text(json.dumps(value))
            with patch.object(fund.sys, "argv", self.args(path)), \
                    patch.dict(fund.os.environ, {"SEED_TEST_USER_PASSWORD": "synthetic-password", "DOCKER_HOST": "ssh://unintended"}, clear=True), \
                    patch.object(seed, "owner_only"), patch.object(fund.subprocess, "run") as run, patch.object(fund, "post") as post:
                with self.assertRaises(seed.Blocked):
                    fund.main()
                run.assert_not_called()
                post.assert_not_called()
            self.assertEqual(value, json.loads(path.read_text()))


if __name__ == "__main__":
    unittest.main()
