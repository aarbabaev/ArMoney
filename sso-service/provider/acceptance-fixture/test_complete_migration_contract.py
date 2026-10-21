"""Synthetic offline protocol tests; never evidence of live migration success."""
import importlib.util
from pathlib import Path
import time
import unittest
from unittest.mock import patch

SPEC = importlib.util.spec_from_file_location("completion", Path(__file__).with_name("test_production_provider_complete_migration.py"))
M = importlib.util.module_from_spec(SPEC); SPEC.loader.exec_module(M)
OWNER = "00000000-0000-0000-0000-000000000001"
COMMAND = "00000000-0000-0000-0000-000000000002"
HEADERS = {"Content-Type": "text/html; charset=utf-8"}


def page(paths):
    return 200, HEADERS, "".join('<form method="post" action="' + p + '"><input type="hidden" name="csrf" value="' + 'a'*43 + '"><input type="hidden" name="command_id" value="' + str(M.uuid.UUID(int=M.uuid.UUID(COMMAND).int + index)) + '">' + ('<input name="code" inputmode="numeric">' if p == M.CONSUME else '') + '</form>' for index, p in enumerate(paths)).encode()


class Ports:
    def __init__(self):
        self.phase = 0; self.finished = False; self.acks = 0; self.bad = None
    def snapshot(self, deadline):
        out = {k: 'a'*64 for k in M.STABLE | {"session_hash"}}
        out.update({k: int(self.finished) for k in M.COUNTS})
        out.update(owner=OWNER, authority_owner=OWNER if self.finished else None,
                   grant_owner=OWNER if self.finished else None,
                   publication_ack_count=self.acks,
                   migration_state="COMPLETED" if self.finished else "ABSENT" if self.phase == 0 else "PENDING")
        if self.bad and self.phase: out.update(self.bad)
        return out
    def admission(self, session, deadline): return 401 if self.finished else 200
    def early_managed_admission(self, deadline): return 401
    def acquire_code(self, channel, command, deadline): return "123456"
    def fresh_native(self, deadline): return {"owner": OWNER, "status": 200}


class Contract(unittest.TestCase):
    def run_driver(self, ports=None, bad_sequence=False, mode="local"):
        ports = ports or Ports()
        class Wire:
            def __init__(self, data): self.deadline = time.monotonic()+100; self.calls = 0
            def request(self, url, **kwargs):
                if "callback?" in url: return page([M.STAGE])
                self.calls += 1
                nexts = [M.ISSUE, M.CONSUME, M.ISSUE, M.CONSUME, M.DECISION, M.PUBLICATION, M.PREPARE, M.FINAL]
                if self.calls == 7: ports.acks = 1
                if self.calls == 9:
                    ports.finished = True
                    return 200, HEADERS, b"Migration completed. Sign in again using the Android application."
                return page([M.FINAL if bad_sequence else nexts[self.calls-1]])
        def prefinal(value, data, factory, deadline):
            wire = factory(data); wire.request(M.P.ORIGIN + M.P.MANAGED_CALLBACK + "?code=synthetic"); ports.phase = 1
        with patch.object(M.P, "execute", prefinal):
            return M.execute({"mode": mode, "original_owner": OWNER, "original_session": "private"}, {}, ports, factory=Wire)

    def test_synthetic_sequence_both_modes(self):
        for mode in ("local", "sso"):
            self.assertTrue(self.run_driver(mode=mode)["whole_migration_verified"])

    def test_no_ports_and_no_prefilled_flags(self):
        for ports in (None, {"whole_migration_verified": True}):
            with self.assertRaises(M.C.Rejected):
                M.execute({"mode": "local"}, {}, ports)

    def test_wrong_sequence_blocks_before_final(self):
        with self.assertRaises(M.C.Rejected): self.run_driver(bad_sequence=True)

    def test_preservation_and_early_authority(self):
        for bad in ({"balance_history_hash": 'b'*64}, {"session_hash": 'b'*64},
                    {"completion_count": 1}, {"grant_count": True}, {"authority_owner": OWNER}):
            ports = Ports(); ports.bad = bad
            with self.assertRaises(M.C.Rejected): self.run_driver(ports)
            self.assertFalse(ports.finished)

    def test_bad_code_and_invalid_native_result(self):
        for setup in (lambda p: setattr(p, "acquire_code", lambda *args: "bad"),
                      lambda p: setattr(p, "fresh_native", lambda *args: {"owner": OWNER, "status": True})):
            ports = Ports(); setup(ports)
            with self.assertRaises(M.C.Rejected): self.run_driver(ports)

    def test_original_legacy_session_denied_before_final_blocks_final(self):
        ports = Ports(); ports.admission = lambda *args: 401
        with self.assertRaisesRegex(M.C.Rejected, "legacy_session_preservation"):
            self.run_driver(ports)
        self.assertFalse(ports.finished)

    def test_original_legacy_session_accepted_after_final_blocks_success(self):
        ports = Ports(); ports.admission = lambda *args: 200
        with self.assertRaisesRegex(M.C.Rejected, "old_session_denial"):
            self.run_driver(ports)
        self.assertTrue(ports.finished)

    def test_managed_purpose_credential_admitted_early_blocks_final(self):
        ports = Ports(); ports.early_managed_admission = lambda *args: 200
        with self.assertRaisesRegex(M.C.Rejected, "early_managed_admission"):
            self.run_driver(ports)
        self.assertFalse(ports.finished)

    def test_existing_prefinal_phase_rejected_before_browser_is_invoked(self):
        ports = Ports(); ports.phase = 1
        with patch.object(M.P, "execute") as browser:
            with self.assertRaisesRegex(M.C.Rejected, "baseline"):
                M.execute({"mode": "local", "original_owner": OWNER}, {}, ports)
            browser.assert_not_called()

    def test_rejects_form_injection_and_unknown_snapshot_fields(self):
        good = page([M.STAGE])[2]
        for raw in (good+good, good.replace(b'name="csrf"', b'name="owner"'),
                    good.replace(b'method="post"', b'method="post" method="get"')):
            with self.assertRaises(M.C.Rejected): M.Forms(raw)
        data = Ports().snapshot(0); data["success"] = True
        with self.assertRaises(M.C.Rejected): M.snapshot(data, OWNER)

    def test_deadline_fails_before_any_port(self):
        with self.assertRaises(M.C.Rejected):
            M.execute({"mode": "local", "original_owner": OWNER}, {}, Ports(), deadline=0)

    def test_consume_resend_commands_must_differ(self):
        valid = page([M.CONSUME, M.ISSUE])[2]
        self.assertEqual({M.CONSUME, M.ISSUE}, set(M.Forms(valid).forms))
        second = str(M.uuid.UUID(int=M.uuid.UUID(COMMAND).int + 1)).encode()
        with self.assertRaisesRegex(M.C.Rejected, "distinct_commands"):
            M.Forms(valid.replace(second, COMMAND.encode()))

    def test_code_input_is_required_once_and_only_on_consume(self):
        consume = page([M.CONSUME])[2]
        code = b'<input name="code" inputmode="numeric">'
        malformed = (consume.replace(code, code + code), consume.replace(code, b''),
                     page([M.ISSUE])[2].replace(b'</form>', code + b'</form>'))
        for raw in malformed:
            with self.assertRaises(M.C.Rejected): M.Forms(raw)

    def test_private_code_gate_binds_channel_purpose_command_and_no_reuse(self):
        import json
        value = {"channel": "PHONE", "issue_command_id": COMMAND,
                 "purpose": "LEGACY_MIGRATION_PHONE", "code": "123456"}
        gate = M.PrivateCodeGate(lambda *args: "private")
        with patch.object(M.C, "read_private", lambda path: json.dumps(value).encode()):
            self.assertEqual("123456", gate("PHONE", COMMAND, time.monotonic()+1))
            with self.assertRaises(M.C.Rejected): gate("PHONE", COMMAND, time.monotonic()+1)
            with self.assertRaises(M.C.Rejected):
                M.PrivateCodeGate(lambda *args: "private")("EMAIL", COMMAND, time.monotonic()+1)


if __name__ == "__main__": unittest.main()
