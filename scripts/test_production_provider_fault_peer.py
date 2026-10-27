"""Portable transport tests, never production banking/authority evidence.

These use in-memory fake connection objects; no sockets, services or credentials.
"""
import importlib.util
import io
import json
from pathlib import Path
import tempfile
import unittest
from email.message import Message
from types import SimpleNamespace
from unittest.mock import patch
from urllib.parse import urlsplit

SPEC = importlib.util.spec_from_file_location("fault_peer", Path(__file__).with_name("production-provider-fault-peer.py"))
peer = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(peer)


class Response:
    def __init__(self, status=200, body=b"{}", headers=None):
        self.status, self.body = status, body
        self.headers = headers if headers is not None else [("Content-Length", str(len(body)))]
        self.reads = []

    def getheaders(self):
        return self.headers

    def read(self, size):
        self.reads.append(size)
        return self.body[:size]


class Connection:
    def __init__(self, response):
        self.response, self.sock = response, None
        self.requests, self.headers, self.closes = [], [], 0

    def putrequest(self, *args, **kwargs):
        self.requests.append((args, kwargs))

    def putheader(self, *args):
        self.headers.append(args)

    def endheaders(self, body):
        self.body = body

    def getresponse(self):
        return self.response

    def close(self):
        self.closes += 1


class StateTests(unittest.TestCase):
    def test_drop_only_after_full_real_200(self):
        state = peer.FaultState()
        state.arm("stage")
        armed = state.begin("stage")
        self.assertEqual(0, state.receipt()["stage"]["drops"])
        response = Response(200, b'{"actual":"bytes"}')
        connection = Connection(response)
        status, headers, body = peer.exchange(urlsplit("http://owned-auth:8080"),
                                             "/internal/sso-code-intents", [], b"original",
                                             lambda *a, **kw: connection)
        self.assertEqual(response.body, body)
        self.assertEqual([len(body) + 1], response.reads)
        self.assertTrue(state.complete("stage", status, armed))
        self.assertEqual(1, state.receipt()["stage"]["drops"])
        self.assertEqual(1, len(connection.requests))

    def test_non_200_consumes_arm_but_forwards_and_never_retries(self):
        state = peer.FaultState()
        state.arm("confirm")
        armed = state.begin("confirm")
        response = Response(503, b"unchanged")
        connection = Connection(response)
        actual = peer.exchange(urlsplit("http://owned-auth:8080"),
                               "/internal/sso-code-intents/confirm", [], b"original",
                               lambda *a, **kw: connection)
        self.assertEqual((503, response.headers, b"unchanged"), actual)
        self.assertFalse(state.complete("confirm", actual[0], armed))
        self.assertFalse(state.begin("confirm"))
        self.assertEqual(1, len(connection.requests))
        with self.assertRaises(peer.Rejected):
            state.arm("confirm")

    def test_two_distinct_arms_only_and_key_cannot_consume(self):
        state = peer.FaultState()
        for bad in ("key", "STAGE", "", None):
            with self.assertRaises(peer.Rejected):
                state.arm(bad)
        state.arm("stage")
        self.assertFalse(state.begin("key"))
        with self.assertRaises(peer.Rejected):
            state.arm("confirm")
        self.assertTrue(state.begin("stage"))
        state.arm("confirm")
        self.assertTrue(state.begin("confirm"))
        for operation in ("stage", "confirm"):
            with self.assertRaises(peer.Rejected):
                state.arm(operation)

    def test_bounded_attempts(self):
        state = peer.FaultState()
        for _ in range(512):
            state.begin("key")
        with self.assertRaises(peer.Rejected):
            state.begin("stage")


class TransportTests(unittest.TestCase):
    def test_auth_body_path_preserved_hops_removed(self):
        connection = Connection(Response())
        headers = [("X-ArMoney-Login-Proof-Write-Key", "synthetic-secret"),
                   ("Content-Type", "application/json"), ("Connection", "X-Remove"),
                   ("X-Remove", "gone"), ("Host", "untrusted"), ("Content-Length", "1")]
        peer.exchange(urlsplit("http://fixed-auth:8080"), "/internal/sso-code-intents", headers,
                      b"exact", lambda *a, **kw: connection)
        self.assertEqual("POST", connection.requests[0][0][0])
        self.assertEqual("/internal/sso-code-intents", connection.requests[0][0][1])
        self.assertEqual(b"exact", connection.body)
        self.assertIn(("X-ArMoney-Login-Proof-Write-Key", "synthetic-secret"), connection.headers)
        self.assertNotIn(("X-Remove", "gone"), connection.headers)
        self.assertNotIn(("Host", "untrusted"), connection.headers)
        self.assertIn(("Content-Length", "5"), connection.headers)
        self.assertGreater(connection.closes, 0)

    def test_invalid_response_length_never_accepted(self):
        cases = [[], [("Content-Length", "1"), ("Content-Length", "1")],
                 [("Content-Length", "1"), ("Transfer-Encoding", "chunked")],
                 [("Content-Length", str(peer.LIMIT + 1))], [("Content-Length", "-1")]]
        for headers in cases:
            with self.subTest(headers=headers), self.assertRaises(peer.Rejected):
                peer.exchange(urlsplit("http://fixed-auth:8080"), "/internal/sso-code-intents", [], b"",
                              lambda *a, **kw: Connection(Response(headers=headers)))

    def test_truncated_or_overlong_response_rejected(self):
        for data in (b"", b"too long"):
            with self.subTest(data=data), self.assertRaises(peer.Rejected):
                peer.exchange(urlsplit("http://fixed-auth:8080"), "/internal/sso-code-intents", [], b"",
                              lambda *a, **kw: Connection(Response(body=data, headers=[("Content-Length", "1")])))

    def test_transport_failure_no_retry(self):
        connection = Connection(Response())
        connection.getresponse = lambda: (_ for _ in ()).throw(TimeoutError())
        with self.assertRaises(TimeoutError):
            peer.exchange(urlsplit("http://fixed-auth:8080"), "/internal/sso-code-intents", [], b"",
                          lambda *a, **kw: connection)
        self.assertEqual(1, len(connection.requests))
        self.assertGreater(connection.closes, 0)

    def test_total_deadline_not_only_per_read_timeout(self):
        connection = Connection(Response())
        class ImmediateTimer:
            def __init__(self, seconds, callback):
                self.seconds, self.callback = seconds, callback
            def start(self):
                self.callback()
            def cancel(self):
                pass
        with patch.object(peer.threading, "Timer", ImmediateTimer), self.assertRaises(peer.Rejected):
            peer.exchange(urlsplit("http://fixed-auth:8080"), "/internal/sso-code-intents", [], b"",
                          lambda *a, **kw: connection)


class PrivacyTests(unittest.TestCase):
    def test_only_closed_response_selectors_written_exclusively(self):
        identifier = "00000000-0000-4000-8000-000000000001"
        evidence = {"intent_id": identifier}
        with tempfile.TemporaryDirectory() as directory:
            peer.save_selectors(Path(directory), "stage", evidence)
            raw = (Path(directory) / "stage.json").read_bytes()
            self.assertEqual({"intent_id": identifier}, json.loads(raw))
            self.assertNotIn(b"never-persist", raw)
            with self.assertRaises(FileExistsError):
                peer.save_selectors(Path(directory), "stage", evidence)

    def test_receipt_contains_only_counts(self):
        state = peer.FaultState()
        state.arm("confirm")
        self.assertEqual({"stage", "key", "confirm", "enrollment"}, set(state.receipt()))
        for values in state.receipt().values():
            self.assertEqual({"attempts", "success", "drops"}, set(values))
            self.assertTrue(all(type(value) is int for value in values.values()))

    def test_duplicate_json_and_noncanonical_uuid_rejected(self):
        with self.assertRaises(peer.Rejected):
            peer.strict_json(b'{"operation":"stage","operation":"confirm"}')
        with self.assertRaises((peer.Rejected, ValueError)):
            peer.canonical_uuid("not-an-id")

    def test_actual_stage_pair_required_for_confirmation_evidence(self):
        identifier = "00000000-0000-4000-8000-000000000001"
        other = "00000000-0000-4000-8000-000000000002"
        binding = {"issuer": "https://fixture.local/realms/armoney", "subject": identifier,
                   "client_id": "armoney-android", "user_session_id": "opaque-session"}
        stage = dict(binding, command_id=identifier, event_id=other, private="never-persist")
        reply = {"intent_id": identifier, "private": "never-persist"}
        state = peer.FaultState()
        record = state.evidence("stage", json.dumps(stage).encode(), json.dumps(reply).encode(), binding["issuer"])
        self.assertIsNone(record["proof_id"])
        self.assertNotIn("private", record)
        confirm = {"event_id": other, "proof_id": identifier}
        record = state.evidence("confirm", json.dumps(binding).encode(), json.dumps(confirm).encode(), binding["issuer"])
        self.assertEqual(identifier, record["intent_id"])
        self.assertEqual("CONFIRM", record["operation"])
        with self.assertRaises(peer.Rejected):
            peer.FaultState().evidence("confirm", json.dumps(binding).encode(), json.dumps(confirm).encode(), binding["issuer"])
        with self.assertRaises(peer.Rejected):
            state.evidence("confirm", json.dumps(dict(binding, user_session_id="different")).encode(),
                           json.dumps(confirm).encode(), binding["issuer"])

    def test_handler_suppresses_request_and_exception_logging(self):
        self.assertIsNone(peer.Handler.log_message(None, "PRIVATE"))
        self.assertIsNone(peer.Server.handle_error(None, "PRIVATE"))


class ControlTests(unittest.TestCase):
    def call(self, path, body=b"", key="synthetic-control-credential-00000000", method="POST"):
        handler = object.__new__(peer.Handler)
        handler.path, handler.command = path, method
        handler.headers = Message()
        handler.headers["Content-Length"] = str(len(body))
        if key is not None:
            handler.headers["X-Fixture-Control-Key"] = key
        handler.rfile = io.BytesIO(body)
        handler.server = SimpleNamespace(config={"control_key": "synthetic-control-credential-00000000"},
                                         state=peer.FaultState(), control=True,
                                         expires=float("inf"))
        replies = []
        handler.reply = lambda *args: replies.append(args)
        with patch.object(peer, "exchange", side_effect=AssertionError("no upstream control actions")):
            handler.handle_request()
        return handler, replies

    def test_control_requires_independent_key(self):
        for key in (None, "wrong"):
            handler, replies = self.call("/control/arm", b'{"operation":"stage"}', key)
            self.assertEqual(403, replies[0][0])
            self.assertEqual(set(), handler.server.state.used)

    def test_closed_arm_schema_and_routes(self):
        for path, body in (("/elsewhere", b'{"operation":"stage"}'),
                           ("/control/arm", b'{"operation":"key"}'),
                           ("/control/arm", b'{"operation":"stage","extra":1}'),
                           ("/control/arm", b'{"operation":[]}')):
            with self.subTest(path=path, body=body):
                handler, replies = self.call(path, body)
                self.assertEqual(502, replies[0][0])
                self.assertEqual(set(), handler.server.state.used)

    def test_arm_does_not_issue_transport(self):
        handler, replies = self.call("/control/arm", b'{"operation":"stage"}')
        self.assertEqual(200, replies[0][0])
        self.assertEqual("stage", handler.server.state.armed)
        self.assertTrue(all(value["attempts"] == 0 for value in handler.server.state.receipt().values()))


class EnrollmentRouteTests(unittest.TestCase):
    def test_only_four_exact_enrollment_routes(self):
        identifier = "00000000-0000-4000-8000-000000000001"
        routes = ["/internal/enrollments", f"/internal/enrollments/{identifier}/status",
                  f"/internal/enrollments/{identifier}/challenges",
                  f"/internal/enrollments/{identifier}/challenges/{identifier}/consume"]
        for route in routes:
            self.assertEqual("enrollment", peer.operation_for(route))
            for suffix in ("/", "?x=1", "/extra", "#fragment"):
                with self.subTest(route=route, suffix=suffix), self.assertRaises(peer.Rejected):
                    peer.operation_for(route + suffix)
        for route in (f"/internal/enrollments/{identifier}",
                      "/internal/enrollments/not-uuid/status",
                      "/internal/enrollments/00000000-0000-4000-8000-00000000000A/status"):
            with self.assertRaises(peer.Rejected):
                peer.operation_for(route)

    def test_enrollment_never_faults_or_consumes_arm(self):
        state = peer.FaultState()
        state.arm("stage")
        for status in (200, 429, 503):
            armed = state.begin("enrollment")
            self.assertFalse(armed)
            self.assertFalse(state.complete("enrollment", status, armed))
        self.assertEqual("stage", state.armed)
        self.assertEqual(0, state.receipt()["enrollment"]["drops"])
        with self.assertRaises(peer.Rejected):
            state.arm("enrollment")

    def test_passthrough_response_metadata_once(self):
        headers = [("Content-Length", "2"), ("Content-Type", "application/json"),
                   ("Cache-Control", "no-store"), ("Retry-After", "1")]
        connection = Connection(Response(429, b"{}", headers))
        result = peer.exchange(urlsplit("http://auth-backend:8080"), "/internal/enrollments", [], b"{}",
                               lambda *a, **kw: connection)
        self.assertEqual((429, headers, b"{}"), result)
        forwarded = peer.safe_headers(result[1])
        for header in headers[1:]:
            self.assertEqual(1, forwarded.count(header))
        self.assertEqual(1, len(connection.requests))


if __name__ == "__main__":
    unittest.main()
