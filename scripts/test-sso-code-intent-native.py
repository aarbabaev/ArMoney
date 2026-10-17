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
