"""Disposable, private response-loss transport. No authority or retry implementation.

Invoke with one private JSON config path. Exact fields: upstream (must equal
http://auth-backend:8080),
listen_host, proxy_port, control_port, control_key, selector_dir, fixture_issuer. Control POST
/control/arm accepts {"operation":"stage"|"confirm"}; GET /control/state returns
counts only. Both require X-Fixture-Control-Key. Each operation can be armed once.
Root must provide a fresh owner-only selector_dir and independent control key.
Only the three code-intent routes and four canonical enrollment routes pass;
KEY and enrollment operations never arm, drop or retain selector metadata.
"""
import argparse
import hmac
import http.client
import json
import os
from pathlib import Path
import re
import socket
import stat
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlsplit
import uuid

ROUTES = {"/internal/sso-code-intents": "stage",
          "/internal/sso-code-intents/key": "key",
          "/internal/sso-code-intents/confirm": "confirm"}
UUID_PATH = r"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"
ENROLLMENT_ROUTES = re.compile(
    rf"/internal/enrollments(?:/{UUID_PATH}/(?:status|challenges(?:/{UUID_PATH}/consume)?))?")
LIMIT = 12288
TIMEOUT = 3.0
HOPS = {"connection", "keep-alive", "proxy-authenticate", "proxy-authorization",
        "te", "trailer", "transfer-encoding", "upgrade"}


def operation_for(path):
    if path in ROUTES:
        return ROUTES[path]
    if ENROLLMENT_ROUTES.fullmatch(path):
        return "enrollment"
    raise Rejected()


class Rejected(Exception):
    pass


def strict_json(raw):
    def pairs(items):
        result = {}
        for key, value in items:
            if key in result:
                raise Rejected()
            result[key] = value
        return result
    try:
        return json.loads(raw, object_pairs_hook=pairs,
                          parse_constant=lambda _: (_ for _ in ()).throw(Rejected()))
    except (ValueError, UnicodeError):
        raise Rejected() from None


def length(headers):
    values = [v for k, v in headers if k.lower() == "content-length"]
    if any(k.lower() == "transfer-encoding" for k, _ in headers):
        raise Rejected()
    if len(values) != 1 or not re.fullmatch(r"0|[1-9][0-9]{0,5}", values[0]):
        raise Rejected()
    count = int(values[0])
    if count > LIMIT:
        raise Rejected()
    return count


def safe_headers(headers):
    blocked = set(HOPS)
    for key, value in headers:
        if key.lower() == "connection":
            blocked.update(part.strip().lower() for part in value.split(","))
    return [(k, v) for k, v in headers
            if k.lower() not in blocked | {"host", "content-length", "x-fixture-control-key"}]


class FaultState:
    def __init__(self):
        self.lock = threading.Lock()
        self.armed = None
        self.used = set()
        self.stages = []  # bounded private metadata, never included in receipts
        self.counts = {op: {"attempts": 0, "success": 0, "drops": 0}
                       for op in ("stage", "key", "confirm", "enrollment")}

    def arm(self, operation):
        with self.lock:
            if operation not in ("stage", "confirm") or self.armed or operation in self.used:
                raise Rejected()
            self.used.add(operation)
            self.armed = operation

    def begin(self, operation):
        with self.lock:
            if sum(item["attempts"] for item in self.counts.values()) >= 512:
                raise Rejected()
            self.counts[operation]["attempts"] += 1
            drop = self.armed == operation
            if drop:
                self.armed = None  # consumed even if the upstream fails; never replay
            return drop

    def complete(self, operation, status, armed):
        with self.lock:
            if status == 200:
                self.counts[operation]["success"] += 1
                if armed:
                    self.counts[operation]["drops"] += 1
            return status == 200 and armed

    def receipt(self):
        with self.lock:
            return {op: dict(counts) for op, counts in self.counts.items()}

    def evidence(self, operation, request, response, issuer):
        request, response = strict_json(request), strict_json(response)
        binding = {}
        for name, maximum in (("issuer", 512), ("subject", 255), ("client_id", 128),
                              ("user_session_id", 255)):
            value = request.get(name) if isinstance(request, dict) else None
            if (not isinstance(value, str) or not 1 <= len(value) <= maximum
                    or any(c.isspace() or ord(c) < 33 or 0x7f <= ord(c) <= 0x9f
                           or 0xd800 <= ord(c) <= 0xdfff for c in value)):
                raise Rejected()
            binding[name] = value
        if binding["issuer"] != issuer or binding["client_id"] not in ("armoney-android", "armoney-ios", "armoney-web"):
            raise Rejected()
        canonical_uuid(binding["subject"])
        with self.lock:
            if operation == "stage":
                item = dict(binding, operation="STAGE", command_id=canonical_uuid(request.get("command_id")),
                            event_id=canonical_uuid(request.get("event_id")),
                            intent_id=canonical_uuid(response.get("intent_id")), proof_id=None)
                if len(self.stages) >= 32:
                    raise Rejected()
                self.stages.append(item)
                return dict(item)
            event = canonical_uuid(response.get("event_id"))
            matches = [item for item in self.stages if item["event_id"] == event
                       and all(item[name] == value for name, value in binding.items())]
            if len(matches) != 1:
                raise Rejected()
            return dict(matches[0], operation="CONFIRM", proof_id=canonical_uuid(response.get("proof_id")))


def canonical_uuid(value):
    if not isinstance(value, str) or str(uuid.UUID(value)) != value:
        raise Rejected()
    return value


def save_selectors(directory, operation, evidence):
    data = json.dumps(evidence, separators=(",", ":")).encode()
    if len(data) > 2048:
        raise Rejected()
    target = directory / (operation + ".json")
    fd = os.open(target, os.O_WRONLY | os.O_CREAT | os.O_EXCL | getattr(os, "O_NOFOLLOW", 0), 0o600)
    with os.fdopen(fd, "wb") as stream:
        stream.write(data)


def exchange(origin, path, headers, body, factory=None):
    """Exactly one request, no redirect/retry; total timer also interrupts slow reads."""
    connection = (factory or (http.client.HTTPSConnection if origin.scheme == "https"
                              else http.client.HTTPConnection))(
                                  origin.hostname, origin.port, timeout=TIMEOUT)
    expired = threading.Event()
    def cancel():
        expired.set()
        if connection.sock:
            try:
                connection.sock.shutdown(socket.SHUT_RDWR)
            except OSError:
                pass
        connection.close()
    timer = threading.Timer(TIMEOUT, cancel)
    timer.daemon = True
    timer.start()
    try:
        connection.putrequest("POST", path, skip_accept_encoding=True)
        for key, value in safe_headers(headers):
            connection.putheader(key, value)
        connection.putheader("Content-Length", str(len(body)))
        connection.putheader("Connection", "close")
        connection.endheaders(body)
        if expired.is_set():
            raise Rejected()
        response = connection.getresponse()
        response_headers = response.getheaders()
        size = length(response_headers)
        data = response.read(size + 1)
        if expired.is_set() or len(data) != size:
            raise Rejected()
        return response.status, response_headers, data
    finally:
        timer.cancel()
        connection.close()


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    def log_message(self, *_):
        pass

    def setup(self):
        super().setup()
        self.connection.settimeout(TIMEOUT)

    def reply(self, status, body=b"", headers=()):
        self.send_response_only(status)
        for key, value in safe_headers(headers):
            self.send_header(key, value)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Connection", "close")
        self.end_headers()
        self.wfile.write(body)
        self.close_connection = True

    def do_GET(self):
        self.handle_request()

    def do_POST(self):
        self.handle_request()

    def read_body(self, size):
        expired = threading.Event()
        def cancel():
            expired.set()
            connection = getattr(self, "connection", None)
            if connection is not None:
                try:
                    connection.shutdown(socket.SHUT_RDWR)
                except OSError:
                    pass
        timer = threading.Timer(TIMEOUT, cancel)
        timer.daemon = True
        timer.start()
        try:
            body = self.rfile.read(size)
            if expired.is_set() or len(body) != size:
                raise Rejected()
            return body
        finally:
            timer.cancel()

    def handle_request(self):
        self.close_connection = True
        try:
            config, state = self.server.config, self.server.state
            if time.monotonic() > self.server.expires:
                raise Rejected()
            headers = list(self.headers.items())
            if len(headers) > 32 or sum(len(k) + len(v) for k, v in headers) > 8192:
                raise Rejected()
            if self.server.control:
                keys = self.headers.get_all("X-Fixture-Control-Key", [])
                if len(keys) != 1 or not hmac.compare_digest(keys[0], config["control_key"]):
                    self.reply(403)
                    return
                if self.command == "GET" and self.path == "/control/state":
                    self.reply(200, json.dumps(state.receipt()).encode(), [("Content-Type", "application/json")])
                    return
                if self.command != "POST" or self.path != "/control/arm":
                    raise Rejected()
                raw = self.read_body(length(headers))
                node = strict_json(raw)
                if (not isinstance(node, dict) or set(node) != {"operation"}
                        or not isinstance(node["operation"], str)):
                    raise Rejected()
                state.arm(node["operation"])
                self.reply(200, b'{"armed":true}', [("Content-Type", "application/json")])
                return
            if self.command != "POST":
                raise Rejected()
            operation = operation_for(self.path)
            if self.headers.get_all("X-Fixture-Control-Key"):
                raise Rejected()
            # Independence is checked without retaining the transport credentials.
            if any(hmac.compare_digest(v, config["control_key"]) for k, v in headers
                   if k.lower().startswith("x-armoney-")):
                raise Rejected()
            size = length(headers)
            body = self.read_body(size)
            armed = state.begin(operation)
            status, response_headers, data = exchange(self.server.origin, self.path, headers, body)
            evidence = None
            if status == 200 and (operation == "stage" or operation == "confirm" and armed):
                evidence = state.evidence(operation, body, data, config["fixture_issuer"])
            if status == 200 and armed:
                save_selectors(Path(config["selector_dir"]), operation, evidence)
            if state.complete(operation, status, armed):
                self.connection.shutdown(socket.SHUT_RDWR)
                return
            self.reply(status, data, response_headers)
        except Exception:
            # No exception message, request, identifiers or response is logged.
            try:
                self.reply(502)
            except OSError:
                pass


class Server(ThreadingHTTPServer):
    daemon_threads = True
    def __init__(self, *args, **kwargs):
        self.slots = threading.BoundedSemaphore(8)
        super().__init__(*args, **kwargs)

    def process_request(self, request, address):
        if not self.slots.acquire(blocking=False):
            self.shutdown_request(request)
            return
        try:
            super().process_request(request, address)
        except Exception:
            self.slots.release()
            raise

    def process_request_thread(self, request, address):
        try:
            super().process_request_thread(request, address)
        finally:
            self.slots.release()

    def handle_error(self, *_):
        pass


def load_config(path):
    if path.is_symlink() or not stat.S_ISREG(path.stat().st_mode):
        raise Rejected()
    if os.name != "nt" and path.stat().st_mode & 0o077:
        raise Rejected()
    if path.stat().st_size > 4096:
        raise Rejected()
    node = strict_json(path.read_bytes())
    fields = {"upstream", "listen_host", "proxy_port", "control_port", "control_key", "selector_dir", "fixture_issuer"}
    if not isinstance(node, dict) or set(node) != fields:
        raise Rejected()
    origin = urlsplit(node["upstream"])
    if node["upstream"] != "http://auth-backend:8080":
        raise Rejected()
    if not isinstance(node["control_key"], str) or not re.fullmatch(r"[!-~]{32,256}", node["control_key"]):
        raise Rejected()
    issuer = urlsplit(node["fixture_issuer"])
    if issuer.scheme != "https" or not issuer.hostname or issuer.username or issuer.password or issuer.query or issuer.fragment:
        raise Rejected()
    if node["listen_host"] not in ("127.0.0.1", "0.0.0.0"):
        raise Rejected()
    for field in ("proxy_port", "control_port"):
        if type(node[field]) is not int or not 1024 <= node[field] <= 65535:
            raise Rejected()
    if node["proxy_port"] == node["control_port"]:
        raise Rejected()
    directory = Path(node["selector_dir"])
    if not directory.is_absolute() or directory.is_symlink() or not directory.is_dir() or any(directory.iterdir()):
        raise Rejected()
    if os.name != "nt" and directory.stat().st_mode & 0o077:
        raise Rejected()
    if os.name != "nt" and (directory.stat().st_uid != os.getuid() or path.stat().st_uid != os.getuid()):
        raise Rejected()
    return node, origin


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("config", type=Path)
    args = parser.parse_args()
    try:
        config, origin = load_config(args.config)
        state = FaultState()
        servers = []
        expires = time.monotonic() + 360
        for control, field in ((False, "proxy_port"), (True, "control_port")):
            server = Server((config["listen_host"], config[field]), Handler)
            server.config, server.origin, server.state = config, origin, state
            server.control, server.expires = control, expires
            servers.append(server)
        for server in servers:
            threading.Thread(target=server.serve_forever, daemon=True).start()
        threading.Event().wait(360)
        for server in servers:
            server.shutdown()
            server.server_close()
    except Exception:
        raise SystemExit("fault_peer_configuration_or_transport_failure") from None


if __name__ == "__main__":
    main()
