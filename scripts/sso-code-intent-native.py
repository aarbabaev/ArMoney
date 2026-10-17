"""Disposable native positive acceptance; connected I/O bounded, DNS unproven.

Install the fixture CA in the disposable browser trust before execution.
Never enable TLS bypass, tracing, HAR, screenshots, or token redemption.
"""
import argparse
import base64
import hashlib
import http.client
import json
import os
import re
import secrets
import socket
import stat
import threading
import time
import urllib.parse
import uuid

ISSUER = 'https://sso-edge:8443/sso/realms/armoney'
CALLBACK = 'https://fixture-client/callback'
CONTROL = '/sso/realms/armoney/armoney-code-intent-fixture/'
GATES = ('CI', 'CODE_SESSION_ACCEPTANCE_ISOLATED', 'ARMONEY_CODE_INTENT_FIXTURE_ENABLED')


class Rejected(Exception):
    pass


class CleanupFailure(Rejected):
    pass


def require(ok):
    if not ok:
        raise Rejected()


def pairs(items):
    value = {}
    for key, entry in items:
        require(key not in value)
        value[key] = entry
    return value


def parse_input(raw):
    require(len(raw) <= 8192)
    data = json.loads(raw.decode('utf-8'), object_pairs_hook=pairs)
    require(type(data) is dict and set(data) == set(GATES) | {'subject', 'username', 'password', 'controller_key'})
    require(all(data[k] is True for k in GATES))
    require(str(uuid.UUID(data['subject'])) == data['subject'])
    require(bool(re.fullmatch(r'\+9715[0-9]{8}', data['username'])))
    for key in ('password', 'controller_key'):
        require(type(data[key]) is str and 1 <= len(data[key]) <= 255 and all(32 <= ord(c) < 127 for c in data[key]))
    return data


def guard():
    require(os.name == 'posix' and all(os.environ.get(k) == 'true' for k in GATES))


def private_read(path):
    fd = os.open(path, os.O_RDONLY | os.O_NOFOLLOW)
    try:
        info = os.fstat(fd)
        require(stat.S_ISREG(info.st_mode) and info.st_uid == os.geteuid() and stat.S_IMODE(info.st_mode) == 0o600)
        return os.read(fd, 8193)
    finally:
        os.close(fd)


def callback_valid(url, state):
    require(len(url) <= 8192)
    parsed = urllib.parse.urlsplit(url)
    require((parsed.scheme, parsed.netloc, parsed.path, parsed.fragment) == ('https', 'fixture-client', '/callback', ''))
    query = pairs(urllib.parse.parse_qsl(parsed.query, keep_blank_values=True, strict_parsing=True))
    require(set(query) <= {'state', 'code', 'iss', 'session_state'} and {'state', 'code'} <= set(query))
    require(secrets.compare_digest(query['state'], state))
    require(bool(re.fullmatch(r'[A-Za-z0-9._~-]{1,2048}', query['code'])))
    require('iss' not in query or query['iss'] == ISSUER)
    require('session_state' not in query or len(query['session_state']) <= 255)
    return True


class Controls:
    def __init__(self, key):
        self.key = key

    def call(self, operation, payload, remaining):
        require(operation in {'arm', 'state', 'bind-stage', 'confirm', 'cancel'} and remaining > 0)
        expired = threading.Event()
        connected = []

        class Connection(http.client.HTTPConnection):
            def connect(self):
                super().connect()
                connected.append(self.sock)
                if expired.is_set():
                    self.sock.close()
                    raise Rejected()

        connection = Connection('sso-a', 8080, timeout=min(4, remaining))

        def cancel():
            expired.set()
            active = connected[0] if connected else None
            if active is not None:
                try:
                    active.shutdown(socket.SHUT_RDWR)
                except OSError:
                    pass
                active.close()

        timer = threading.Timer(min(4, remaining), cancel)
        timer.daemon = True
        timer.start()
        try:
            body = json.dumps(payload, separators=(',', ':')).encode()
            require(len(body) <= 12288)
            connection.request('POST', CONTROL + operation, body, {'Content-Type': 'application/json', 'X-ArMoney-Code-Intent-Fixture-Control-Key': self.key})
            response = connection.getresponse()
            require(response.status == 200 and response.getheader('Cache-Control') == 'no-store')
            raw = response.read(12289)
            require(not expired.is_set() and len(raw) <= 12288)
            result = json.loads(raw.decode('utf-8'), object_pairs_hook=pairs)
            require(type(result) is dict)
            return result
        finally:
            timer.cancel()
            timer.join()
            connection.close()


class Browser:
    def __enter__(self):
        guard()
        from playwright.sync_api import sync_playwright
        self.runtime = sync_playwright().start()
        try:
            self.browser = self.runtime.chromium.launch(headless=True)
            self.context = self.browser.new_context(service_workers='block', accept_downloads=False)
            self.page = self.context.new_page()
            self.callback = False
            self.violation = False
            self.context.route('**/*', self.route)
            return self
        except BaseException:
            self.runtime.stop()
            raise

    def route(self, route):
        parsed = urllib.parse.urlsplit(route.request.url)
        if (parsed.scheme, parsed.netloc) == ('https', 'fixture-client'):
            try:
                require(not self.callback)
                require(route.request.method == 'GET' and route.request.is_navigation_request()
                        and route.request.frame is self.page.main_frame)
                callback_valid(route.request.url, self.state)
                self.callback = True
            except Exception:
                self.violation = True
            finally:
                route.abort()
        elif (parsed.scheme, parsed.netloc) == ('https', 'sso-edge:8443') and not parsed.username and not parsed.password:
            route.continue_()
        else:
            self.violation = True
            route.abort()

    def login(self, data, state, nonce, challenge, remaining):
        self.state = state
        deadline = time.monotonic() + remaining

        def timeout():
            left = deadline - time.monotonic()
            require(left > 0 and not self.violation)
            return max(1, int(left * 1000))

        query = urllib.parse.urlencode({'client_id': 'armoney-android', 'redirect_uri': CALLBACK, 'response_type': 'code', 'scope': 'openid', 'state': state, 'nonce': nonce, 'code_challenge': challenge, 'code_challenge_method': 'S256', 'prompt': 'login'})
        self.page.goto(ISSUER + '/protocol/openid-connect/auth?' + query, timeout=timeout())
        self.page.locator('#username').fill(data['username'], timeout=timeout())
        self.page.locator('#password').fill(data['password'], timeout=timeout())
        self.page.locator('#kc-login').click(timeout=timeout())
        require(not self.violation)

    def click(self, operation, remaining):
        require(operation in {'prepare', 'continue'} and not self.violation)
        self.page.locator('button[name="operation"][value="' + operation + '"]').click(timeout=max(1, int(remaining * 1000)), no_wait_after=True)

    def completion(self, remaining):
        end = time.monotonic() + remaining
        while not self.callback and not self.violation and time.monotonic() < end:
            self.page.wait_for_timeout(25)
        require(self.callback and not self.violation)

    def __exit__(self, *_):
        failed = False
        for target, operation in ((self.context, 'close'), (self.browser, 'close'), (self.runtime, 'stop')):
            try:
                getattr(target, operation)()
            except Exception:
                failed = True
        if failed:
            raise CleanupFailure()


def execute(data, transport, browser, clock=time.monotonic, pause=time.sleep):
    phase, handle = 'arm', None
    start = clock()
    end, setup = start + 115, start + 85

    def remaining(limit=end):
        left = min(end, limit) - clock()
        require(left > 0)
        return left

    def control(op, value):
        result = transport.call(op, value, remaining())
        remaining()
        return result

    try:
        armed = control('arm', {'subject': data['subject'], 'client_id': 'armoney-android'})
        require(set(armed) == {'experiment_handle'})
        handle = armed['experiment_handle']
        require(type(handle) is str and bool(re.fullmatch(r'[A-Za-z0-9_-]{43}', handle)))
        require(base64.urlsafe_b64encode(base64.urlsafe_b64decode(handle + '=')).decode().rstrip('=') == handle)
        phase = 'login'
        verifier = secrets.token_urlsafe(32)
        challenge = base64.urlsafe_b64encode(hashlib.sha256(verifier.encode()).digest()).decode().rstrip('=')
        browser.login(data, secrets.token_urlsafe(32), secrets.token_urlsafe(32), challenge, remaining(setup))
        phase = 'prepare'
        browser.click('prepare', remaining(setup))
        phase = 'ready'
        while True:
            remaining(setup)
            result = control('state', {'experiment_handle': handle})
            require(set(result) == {'state'})
            if result['state'] == 'READY':
                break
            require(result['state'] in {'ARMED', 'FLOW_BOUND', 'PREPARING'})
            pause(min(.1, remaining(setup)))
        phase = 'bind-stage'
        require(control('bind-stage', {'experiment_handle': handle, 'command_id': str(uuid.uuid4())}) == {'state': 'STAGE_BOUND'})
        phase = 'continue'
        browser.click('continue', remaining())
        browser.completion(remaining())
        phase = 'confirm'
        require(control('confirm', {'experiment_handle': handle, 'command_id': str(uuid.uuid4())}) == {'state': 'CONFIRMED'})
        return {'status': 'confirmed', 'phase': 'confirm'}
    except Exception:
        if handle is not None:
            try:
                transport.call('cancel', {'experiment_handle': handle}, max(.001, min(4, end - clock())))
            except Exception:
                pass
        return {'status': 'not_verified', 'phase': phase}


def run_browser(data):
    try:
        with Browser() as browser:
            result = execute(data, Controls(data['controller_key']), browser)
        return result
    except CleanupFailure:
        return {'status': 'not_verified', 'phase': 'teardown'}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--input', required=True)
    parser.add_argument('--result', required=True)
    args = parser.parse_args()
    result, fd = {'status': 'not_verified', 'phase': 'guard'}, None
    watchdog = None
    try:
        guard()
        data = parse_input(private_read(args.input))
        fd = os.open(args.result, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
        os.fchmod(fd, 0o600)
        os.write(fd, json.dumps({'status': 'not_verified', 'phase': 'deadline'}).encode())
        os.fsync(fd)
        # Covers browser operations and uninterruptible platform DNS in this process.
        # Disposable runner must reap browser children if this hard bound fires.
        watchdog = threading.Timer(119, lambda: os._exit(1))
        watchdog.daemon = True
        watchdog.start()
        result['phase'] = 'browser'
        result = run_browser(data)
    except Exception:
        pass
    finally:
        if watchdog is not None:
            watchdog.cancel()
            watchdog.join()
        if fd is not None:
            os.lseek(fd, 0, os.SEEK_SET)
            os.ftruncate(fd, 0)
            with os.fdopen(fd, 'w') as output:
                json.dump(result, output)
    return 0 if result['status'] == 'confirmed' else 1


if __name__ == '__main__':
    raise SystemExit(main())
