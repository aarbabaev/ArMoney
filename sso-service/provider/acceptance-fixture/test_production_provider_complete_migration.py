#!/usr/bin/env python3
"""Separate full migration driver; importing/running this file sends nothing.

Root supplies active, bounded ports (never prefilled success flags):
 snapshot(deadline): read-only owner-scoped SQL evidence with the exact schema
 below; hashes are private canonical row-set SHA256s, not secret-bearing output.
 admission(session, deadline): actual original legacy session public banking
 HTTP status: 200 until successful FINAL, then 401/403.
 early_managed_admission(deadline): actual public banking HTTP status using the
 managed/purpose credential under migration, which must remain 401/403 before
 FINAL. This must never reuse the original legacy banking session credential.
 acquire_code(channel, issue_command_id, deadline): actual delivered six digits;
 EMAIL uses an authorized inbox reader or manual private-file gate; PHONE uses
 the authenticated sandbox inbox and LEGACY_MIGRATION_PHONE purpose. Neither
 adapter may query/decrypt OTP database records or inject delivery success.
 fresh_native(deadline): new genuine native confirmation then public admission,
 returning exactly {owner, status}. FINAL itself does not issue a banking token.

No CLI adapters exist: the orchestrator owns topology, private input, DB reads,
delivery authorization and fresh native confirmation. Offline tests establish
protocol guards only, never runtime acceptance. Existing prefinal stays unchanged.
"""
from html.parser import HTMLParser
import importlib.util
from pathlib import Path
import re
import time
import uuid

SPEC = importlib.util.spec_from_file_location("complete_prefinal", Path(__file__).with_name("test_production_provider_prefinal_migration.py"))
P = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(P)
C = P.C
STAGE = P.STAGE
ISSUE = "/migration/legacy/otp/issue"
CONSUME = "/migration/legacy/otp/consume"
DECISION = "/migration/legacy/verify/decision"
PUBLICATION = "/migration/legacy/verify/publication"
PREPARE = "/migration/legacy/verify/final-command"
FINAL = "/migration/legacy/verify/final"
PATHS = {STAGE, ISSUE, CONSUME, DECISION, PUBLICATION, PREPARE, FINAL}
STABLE = {"identity_hash", "credential_hash", "profile_core_hash", "wallet_hash", "account_hash", "balance_history_hash"}
COUNTS = {"completion_count", "publication_ack_count", "managed_authority_count", "grant_count"}
KEYS = STABLE | COUNTS | {"owner", "session_hash", "migration_state", "authority_owner", "grant_owner"}


class PrivateCodeGate:
    """Root calls this after an authorized operator writes actual inbox digits.

    Supply a fresh private file for each issue command, using C.read_private's
    size/mode/symlink protections. This never waits for input or sends delivery.
    A missing file blocks; rerunning the entire migration is not automatic.
    The root can instead implement bounded interactive waiting in its adapter.
    File fields: channel, issue_command_id, purpose, code. PHONE purpose must be
    LEGACY_MIGRATION_PHONE; EMAIL purpose must be LEGACY_MIGRATION_EMAIL.
    """
    def __init__(self, path_for_command):
        C.require(callable(path_for_command), "private_gate_path")
        self.path_for_command = path_for_command
        self.used = set()

    def __call__(self, channel, issue_command_id, deadline):
        C.require(channel in {"EMAIL", "PHONE"} and time.monotonic() < deadline
                  and issue_command_id not in self.used, "private_gate_request")
        value = C.parse_json(C.read_private(self.path_for_command(channel, issue_command_id)))
        C.require(type(value) is dict and set(value) == {"channel", "issue_command_id", "purpose", "code"}
                  and value["channel"] == channel and value["issue_command_id"] == issue_command_id
                  and value["purpose"] == "LEGACY_MIGRATION_" + channel
                  and type(value["code"]) is str and re.fullmatch(r"[0-9]{6}", value["code"])
                  and time.monotonic() < deadline, "private_gate_input")
        self.used.add(issue_command_id)
        return value["code"]


def receipt(mode):
    C.require(mode in {"local", "sso"}, "mode")
    return {"local_mode": mode == "local", "sso_mode": mode == "sso",
            "otp_submitted": False, "final_submitted": False,
            "db_preservation_verified": False, "whole_migration_verified": False}


def snapshot(value, owner):
    C.require(type(value) is dict and set(value) == KEYS, "snapshot_schema")
    C.require(value["owner"] == owner and str(uuid.UUID(owner)) == owner, "snapshot_owner")
    C.require(all(type(value[k]) is str and re.fullmatch(r"[a-f0-9]{64}", value[k]) for k in STABLE | {"session_hash"}), "snapshot_hash")
    C.require(all(type(value[k]) is int and 0 <= value[k] <= 1 for k in COUNTS), "snapshot_counts")
    C.require(value["migration_state"] in {"ABSENT", "PENDING", "COMPLETED"}, "snapshot_state")
    C.require(all(value[k] is None or value[k] == owner for k in ("authority_owner", "grant_owner")), "snapshot_binding")
    return dict(value)


class Forms(HTMLParser):
    def __init__(self, raw):
        super().__init__(convert_charrefs=True)
        self.forms = {}; self.current = None; self.current_path = None; self.code_inputs = 0
        C.require(type(raw) is bytes and len(raw) <= C.LIMIT, "form_bound")
        self.feed(raw.decode("utf-8", "strict")); self.close()
        C.require(self.current is None and 1 <= len(self.forms) <= 2, "forms_count")
        commands = [form["command_id"] for form in self.forms.values()]
        C.require(len(set(commands)) == len(commands), "distinct_commands")

    def handle_starttag(self, tag, attrs):
        C.require(len(dict(attrs)) == len(attrs), "duplicate_attribute")
        a = dict(attrs)
        if tag == "form":
            path = a.get("action")
            C.require(self.current is None and a.get("method", "").lower() == "post"
                      and path in PATHS and path not in self.forms, "form_action")
            self.current = {}; self.forms[path] = self.current
            self.current_path = path; self.code_inputs = 0
        elif tag == "input" and self.current is not None:
            name = a.get("name")
            if a.get("type") == "hidden":
                C.require(name in {"csrf", "command_id"} and name not in self.current, "hidden_field")
                self.current[name] = a.get("value")
            else:
                C.require(self.current_path == CONSUME and name == "code" and "value" not in a
                          and self.code_inputs == 0, "visible_field")
                self.code_inputs += 1

    def handle_endtag(self, tag):
        if tag == "form":
            C.require(self.current is not None and set(self.current) == {"csrf", "command_id"}, "form_fields")
            token, command = self.current["csrf"], self.current["command_id"]
            C.require(type(token) is str and re.fullmatch(r"[A-Za-z0-9_-]{43}", token), "csrf")
            C.require(type(command) is str and str(uuid.UUID(command)) == command and uuid.UUID(command).int != 0, "command")
            C.require(self.code_inputs == (1 if self.current_path == CONSUME else 0), "code_input_count")
            self.current = None
            self.current_path = None


def response_forms(response, expected):
    status, headers, raw = response
    C.expected_status(status, 200)
    C.require(headers.get("Content-Type", "").lower().split(";", 1)[0] == "text/html", "html")
    forms = Forms(raw).forms
    C.require(set(forms) in expected, "continuation_sequence")
    return forms


def execute(value, data, ports, *, factory=C.Wire, deadline=None):
    """Return scalar receipt only after live observations; exceptions fail closed.

    Call this from the outer historical/cutover entry BEFORE any P.execute or
    migration browser phase: the ABSENT baseline is captured here, and this
    function itself invokes the genuine prefinal flow. An existing prefinal
    receipt/phase cannot be used as the entry point.
    Caller must keep exceptions/private observations out of logs. At most nine
    continuation POSTs occur; no retries, polling, resend, or mutation via ports.
    """
    result = receipt(value["mode"])
    C.require(all(callable(getattr(ports, name, None)) for name in
                  ("snapshot", "admission", "early_managed_admission", "acquire_code", "fresh_native")), "missing_live_ports")
    limit = min(time.monotonic() + 120, deadline if deadline is not None else float("inf"))
    owner = value["original_owner"]

    def bounded():
        C.require(time.monotonic() < limit, "deadline")

    def observed():
        bounded(); out = snapshot(ports.snapshot(limit), owner); bounded(); return out

    baseline = observed()
    C.require(baseline["migration_state"] == "ABSENT" and all(baseline[k] == 0 for k in COUNTS)
              and baseline["authority_owner"] is None and baseline["grant_owner"] is None, "baseline")
    retained = []

    # Reuse genuine callback, original cookie jar, explicit SSO email selection,
    # and all existing prefinal guards without changing its receipt contract.
    def retain(config):
        wire = factory(config); original = wire.request
        def request(url, **kwargs):
            answer = original(url, **kwargs)
            if url.startswith(P.ORIGIN + P.MANAGED_CALLBACK + "?"):
                retained.append((wire, answer))
            return answer
        wire.request = request
        return wire

    P.execute(value, data, factory=retain, deadline=limit)
    C.require(len(retained) == 1, "genuine_callback")
    wire, callback = retained[0]; limit = min(limit, wire.deadline)
    forms = response_forms(callback, [{STAGE}])

    def preserve():
        current = observed()
        C.require(all(current[k] == baseline[k] for k in STABLE | {"session_hash"}), "prefinal_preservation")
        C.require(current["migration_state"] == "PENDING" and current["completion_count"] == 0
                  and current["managed_authority_count"] == 0 and current["grant_count"] == 0
                  and current["authority_owner"] is None and current["grant_owner"] is None, "early_authority")
        status = ports.admission(value["original_session"], limit); bounded()
        C.require(type(status) is int and status == 200, "legacy_session_preservation")
        early = ports.early_managed_admission(limit); bounded()
        C.require(type(early) is int and early in {401, 403}, "early_managed_admission")

    def post(path, expected, code=None):
        nonlocal forms
        bounded(); preserve()
        C.require(path in forms, "returned_command")
        body = dict(forms[path])
        if code is not None:
            C.require(path == CONSUME and type(code) is str and re.fullmatch(r"[0-9]{6}", code), "delivered_code")
            body["code"] = code
        response = wire.request(P.ORIGIN + path, form=body, headers={"Origin": P.ORIGIN})
        bounded()
        if path != FINAL: forms = response_forms(response, expected)
        return response

    post(STAGE, [{ISSUE}])
    for channel in ("EMAIL", "PHONE"):
        issue = forms[ISSUE]["command_id"]
        post(ISSUE, [{CONSUME}, {CONSUME, ISSUE}])
        code = ports.acquire_code(channel, issue, limit); bounded()
        post(CONSUME, [{ISSUE} if channel == "EMAIL" else {DECISION}], code)
        del code
    result["otp_submitted"] = True
    post(DECISION, [{PUBLICATION}]); post(PUBLICATION, [{PREPARE}]); post(PREPARE, [{FINAL}])
    # A persisted User acknowledgement must exist BEFORE irreversible FINAL.
    C.require(observed()["publication_ack_count"] == 1, "publication_ack")
    status, headers, raw = post(FINAL, [])
    result["final_submitted"] = True
    C.expected_status(status, 200)
    C.require(headers.get("Content-Type", "").lower().split(";", 1)[0] == "text/html"
              and type(raw) is bytes and len(raw) <= C.LIMIT
              and b"Migration completed. Sign in again using the Android application." in raw
              and b"<form" not in raw.lower(), "completed_browser")
    final = observed()
    C.require(all(final[k] == baseline[k] for k in STABLE) and final["migration_state"] == "COMPLETED"
              and all(final[k] == 1 for k in COUNTS)
              and final["authority_owner"] == owner and final["grant_owner"] == owner, "completed_persistence")
    denial = ports.admission(value["original_session"], limit); bounded()
    C.require(type(denial) is int and denial in {401, 403}, "old_session_denial")
    result["db_preservation_verified"] = True
    native = ports.fresh_native(limit); bounded()
    C.require(type(native) is dict and set(native) == {"owner", "status"}
              and native["owner"] == owner and type(native["status"]) is int and native["status"] == 200, "fresh_native_admission")
    result["whole_migration_verified"] = True
    return result
