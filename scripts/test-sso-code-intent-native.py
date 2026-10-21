"""Portable controller tests only; no browser, HTTP, DB or native proof."""
import importlib.util
import json
from pathlib import Path
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('native_controller', Path(__file__).with_name('sso-code-intent-native.py'))
m = importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)


def data():
    return dict(zip(m.GATES, [True] * 3), subject='12345678-1234-4234-8234-123456789abc', username='+971500000001', password='synthetic-password', controller_key='synthetic-controller-key')


class Transport:
    def __init__(self, fail=None):
        self.calls = []
        self.fail = fail

    def call(self, op, value, remaining):
        self.calls.append((op, value))
        if op == self.fail:
            raise TimeoutError()
        return {'arm': {'experiment_handle': 'A' * 43}, 'state': {'state': 'READY'}, 'bind-stage': {'state': 'STAGE_BOUND'}, 'confirm': {'state': 'CONFIRMED'}, 'cancel': {'state': 'CANCELLED'}}[op]


class Browser:
    def __init__(self, fail=False):
        self.events = []
        self.fail = fail

    def login(self, value, state, nonce, challenge, remaining):
        self.events.append('login')
        assert state != nonce and len(challenge) == 43

    def click(self, op, remaining):
        self.events.append(op)

    def completion(self, remaining):
        self.events.append('callback')
        if self.fail:
            raise m.Rejected()


class Request:
    def __init__(self, frame, method='GET', navigation=True):
        self.url = m.CALLBACK + '?state=s&code=opaque'
        self.method, self.frame, self.navigation = method, frame, navigation

    def is_navigation_request(self):
        return self.navigation


class Route:
    def __init__(self, request):
        self.request, self.aborted = request, False

    def abort(self):
        self.aborted = True

    def continue_(self):
        raise AssertionError('callback reached sink')


class ControllerTests(unittest.TestCase):
    def ready_failure(self, response=None, failure=None, expire=False):
        class ReadyTransport(Transport):
            def call(self, op, value, remaining):
                if op == 'state':
                    self.calls.append((op, value))
                    if failure is not None:
                        raise failure
                    return response
                return super().call(op, value, remaining)
        now = [0.0]
        class ReadyBrowser(Browser):
            def click(self, op, remaining):
                super().click(op, remaining)
                if expire and op == 'prepare':
                    now[0] = 85.0
        transport = ReadyTransport()
        result = m.execute(data(), transport, ReadyBrowser(), clock=lambda: now[0])
        self.assertEqual(result['status'], 'not_verified')
        self.assertEqual(result['phase'], 'ready')
        self.assertEqual([op for op, _ in transport.calls].count('cancel'), 1)
        self.assertNotIn('bind-stage', [op for op, _ in transport.calls])
        return result

    def test_ready_diagnostic_failure_classes_and_redaction(self):
        cases = [(dict(state='FAILED'), None, False, {'check': 'provider_state', 'provider_state': 'FAILED'}),
                 (None, m.ControlStatus(), False, {'check': 'control_status'}),
                 (None, m.ControlSchema(), False, {'check': 'control_schema'}),
                 (None, TimeoutError('SECRET URL PASSWORD'), False, {'check': 'transport_unknown'}),
                 (None, None, True, {'check': 'setup_deadline'})]
        for response, failure, expire, expected in cases:
            with self.subTest(expected=expected):
                result = self.ready_failure(response, failure, expire)
                self.assertEqual(result['ready_diagnostic'], expected)
                self.assertNotIn('SECRET', json.dumps(result))
                self.assertEqual(set(result), {'status', 'phase', 'ready_diagnostic'})

    def test_ready_malformed_unhashable_unknown_and_extra_fields_redacted(self):
        for response in ({'state': []}, {'state': {}}, {'state': True}, {'state': 'SECRET'},
                         {'state': 'READY', 'password': 'SECRET'}, [], None):
            with self.subTest(response=response):
                self.assertEqual(self.ready_failure(response)['ready_diagnostic'], {'check': 'control_schema'})

    def test_actual_control_transport_classifies_status_and_schema_without_network(self):
        class Timer:
            def __init__(self, *args): pass
            def start(self): pass
            def cancel(self): pass
            def join(self): pass
        class Connection:
            def __init__(self, *args, **kwargs): pass
            def request(self, *args, **kwargs): pass
            def getresponse(self): return response
            def close(self): pass
        class Response:
            def __init__(self, status, cache, body): self.status, self.cache, self.body = status, cache, body
            def getheader(self, name): return self.cache
            def read(self, limit): return self.body
        for response, expected in ((Response(503, 'no-store', b'SECRET'), m.ControlStatus),
                                   (Response(200, 'private', b'SECRET'), m.ControlSchema),
                                   (Response(200, 'no-store', b'SECRET'), m.ControlSchema),
                                   (Response(200, 'no-store', b'{"state":"READY","state":"SECRET"}'), m.ControlSchema)):
            with self.subTest(expected=expected), patch.object(m.http.client, 'HTTPConnection', Connection), patch.object(m.threading, 'Timer', Timer):
                with self.assertRaises(expected) as caught:
                    m.Controls('synthetic').call('state', {'experiment_handle': 'A' * 43}, 4)
                self.assertEqual(str(caught.exception), '')
                self.assertIsNone(caught.exception.__cause__)

    def test_ready_diagnostic_exact_schema(self):
        for invalid in ({'check': []}, {'check': 'unknown'}, {'check': 'provider_state'},
                        {'check': 'provider_state', 'provider_state': []},
                        {'check': 'provider_state', 'provider_state': 'SECRET'},
                        {'check': 'control_status', 'provider_state': 'FAILED'},
                        {'check': 'transport_unknown', 'password': 'SECRET'}):
            with self.subTest(invalid=invalid), self.assertRaises(m.Rejected):
                m.ready_diagnostic_valid(invalid)
        for state in m.PROVIDER_STATES:
            self.assertEqual(m.ready_diagnostic_valid({'check': 'provider_state', 'provider_state': state})['provider_state'], state)

    def test_other_failures_keep_original_two_fields(self):
        for operation in ('arm', 'bind-stage', 'confirm'):
            self.assertEqual(set(m.execute(data(), Transport(operation), Browser())), {'status', 'phase'})

    def test_exact_input(self):
        self.assertEqual(m.parse_input(json.dumps(data()).encode()), data())

    def test_input_rejects_extra_or_false_gate(self):
        for value in (dict(data(), provider_key='forbidden'), dict(data(), CI=False)):
            with self.assertRaises(m.Rejected):
                m.parse_input(json.dumps(value).encode())

    def test_duplicate_input_rejected(self):
        with self.assertRaises(m.Rejected):
            m.parse_input(b'{"CI":true,"CI":true}')

    def test_callback_accepts_exact_state_and_optional_issuer(self):
        self.assertTrue(m.callback_valid(m.CALLBACK + '?state=s&code=opaque', 's'))

    def test_callback_rejects_leaks_and_wrong_binding(self):
        for suffix in ('?state=x&code=a', '?state=s&code=', '?state=s&code=a&access_token=x', '?state=s&code=a&error=x', '?state=s&code=a&state=s', '?state=s&code=a&iss=wrong', '?state=s&code=a#fragment'):
            with self.assertRaises(m.Rejected):
                m.callback_valid(m.CALLBACK + suffix, 's')

    def test_guard_fails_without_posix_and_exact_markers(self):
        with patch.dict(m.os.environ, {}, clear=True):
            with self.assertRaises(m.Rejected):
                m.guard()

    def test_positive_orchestration_no_authority_injection(self):
        t, b = Transport(), Browser()
        self.assertEqual(m.execute(data(), t, b), {'status': 'confirmed', 'phase': 'confirm'})
        self.assertEqual(b.events, ['login', 'prepare', 'continue', 'callback'])
        self.assertEqual([x[0] for x in t.calls], ['arm', 'state', 'bind-stage', 'confirm'])
        bind, confirm = t.calls[2][1], t.calls[3][1]
        self.assertEqual(set(bind), {'experiment_handle', 'command_id'})
        self.assertNotEqual(bind['command_id'], confirm['command_id'])

    def test_uncertain_confirm_never_retried_cancel_once(self):
        t = Transport('confirm')
        self.assertEqual(m.execute(data(), t, Browser())['status'], 'not_verified')
        self.assertEqual([x[0] for x in t.calls].count('confirm'), 1)
        self.assertEqual([x[0] for x in t.calls].count('cancel'), 1)

    def test_callback_failure_prevents_confirm(self):
        t = Transport()
        self.assertEqual(m.execute(data(), t, Browser(True))['phase'], 'continue')
        self.assertNotIn('confirm', [x[0] for x in t.calls])

    def test_operation_allowlist_precedes_network(self):
        with self.assertRaises(m.Rejected):
            m.Controls('synthetic').call('membership', {}, 4)

    def test_callback_intercept_aborts_before_sink(self):
        b = m.Browser()
        b.state, b.callback, b.violation = 's', False, False
        b.page = type('Page', (), {'main_frame': object()})()
        route = Route(Request(b.page.main_frame))
        b.route(route)
        self.assertTrue(route.aborted and b.callback and not b.violation)

    def test_callback_rejects_post_fetch_subresource_and_foreign_frame(self):
        frame = object()
        for method, navigation, origin in [('POST', True, frame), ('GET', False, frame), ('GET', True, object())]:
            with self.subTest(method=method, navigation=navigation):
                b = m.Browser()
                b.state, b.callback, b.violation = 's', False, False
                b.page = type('Page', (), {'main_frame': frame})()
                route = Route(Request(origin, method, navigation))
                b.route(route)
                self.assertTrue(route.aborted and b.violation and not b.callback)

    def test_callback_replay_is_rejected(self):
        b = m.Browser()
        b.state, b.callback, b.violation = 's', True, False
        b.page = type('Page', (), {'main_frame': object()})()
        route = Route(Request(b.page.main_frame))
        b.route(route)
        self.assertTrue(route.aborted and b.violation)

    def test_teardown_attempts_all_resources_and_overrides_confirmed(self):
        events = []

        class Resource:
            def __init__(self, name, fail):
                self.name, self.fail = name, fail

            def close(self):
                events.append(self.name)
                if self.fail:
                    raise RuntimeError('synthetic cleanup failure')

            stop = close

        for failing in ('context', 'browser', 'runtime'):
            events.clear()
            b = m.Browser()
            b.context, b.browser, b.runtime = [Resource(name, name == failing) for name in ('context', 'browser', 'runtime')]
            with patch.object(m.Browser, '__enter__', return_value=b), patch.object(m, 'Browser', return_value=b), patch.object(m, 'execute', return_value={'status': 'confirmed', 'phase': 'confirm'}):
                self.assertEqual(m.run_browser(data()), {'status': 'not_verified', 'phase': 'teardown'})
            self.assertEqual(events, ['context', 'browser', 'runtime'])

    def test_unknown_origin_is_aborted(self):
        b = m.Browser()
        b.violation = False
        route = type('Route', (), {'request': type('Request', (), {'url': 'https://unknown.invalid/'})(), 'abort': lambda self: None})()
        b.route(route)
        self.assertTrue(b.violation)


if __name__ == '__main__':
    unittest.main()
