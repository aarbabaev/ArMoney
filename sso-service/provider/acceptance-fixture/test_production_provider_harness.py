"""Portable harness checks only; these establish no provider/runtime acceptance."""
import base64
import hashlib
import importlib.util
import json
from pathlib import Path
from types import SimpleNamespace
import unittest
import urllib.parse
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('production_acceptance', Path(__file__).with_name('test_production_provider_acceptance.py'))
driver = importlib.util.module_from_spec(spec)
spec.loader.exec_module(driver)


class ProductionHarnessTest(unittest.TestCase):
    def concurrency_receipt(self):
        return {'case': 'concurrent_same_client_callbacks', 'status': 'scoped_passed',
            'positive_confirmations': 2, 'positive_bank_sessions': 2,
            'negative_no_side_effects': True, 'whole_migration_acceptance': False}

    def test_ten_required_cases_and_six_pending(self):
        self.assertEqual(len(driver.CASES), 10)
        self.assertEqual(driver.CASES[-2:], ('policy_removed_after_issue_denied', 'concurrent_same_client_callbacks'))
        self.assertEqual(driver.UNIMPLEMENTED, ('stage_response_loss', 'confirmation_response_loss',
            'node_replacement', 'master_stock', 'disabled_stock', 'full_migration_callbacks_pre_final'))

    def test_concurrency_runs_after_restoration_with_original_deadline(self):
        completed, order = list(driver.CASES[:-2]), []
        wire = SimpleNamespace(data={'synthetic': 'private'}, deadline=123.25)
        def policy(actual, cases):
            self.assertIs(actual, wire)
            order.append('restored')
            cases.append('policy_removed_after_issue_denied')
        def run(data, *, deadline):
            self.assertEqual(order, ['restored'])
            self.assertIs(data, wire.data)
            self.assertEqual(deadline, 123.25)
            self.assertEqual(completed, list(driver.CASES[:-1]))
            order.append('concurrent')
            return self.concurrency_receipt()
        with patch.object(driver, 'policy_removed', side_effect=policy), \
                patch.object(driver, 'load_concurrency', return_value=SimpleNamespace(execute=run)):
            driver.final_cases(wire, completed)
        self.assertEqual(completed, list(driver.CASES))
        self.assertEqual(order, ['restored', 'concurrent'])

    def test_policy_failure_never_starts_concurrency(self):
        completed = list(driver.CASES[:-2])
        with patch.object(driver, 'policy_removed', side_effect=driver.Rejected('policy_restore')), \
                patch.object(driver, 'load_concurrency') as load:
            with self.assertRaisesRegex(driver.Rejected, '^policy_restore$'):
                driver.final_cases(object(), completed)
        load.assert_not_called()
        self.assertEqual(completed, list(driver.CASES[:-2]))

    def test_concurrency_failure_or_open_receipt_withholds_case(self):
        invalid = [None, dict(self.concurrency_receipt(), extra=True),
            dict(self.concurrency_receipt(), positive_confirmations=True),
            dict(self.concurrency_receipt(), positive_bank_sessions=1),
            dict(self.concurrency_receipt(), negative_no_side_effects=False),
            dict(self.concurrency_receipt(), whole_migration_acceptance=True),
            dict(self.concurrency_receipt(), status='not_verified')]
        for result in invalid:
            with self.subTest(result=result):
                completed = list(driver.CASES[:-2])
                def policy(wire, cases):
                    cases.append('policy_removed_after_issue_denied')
                with patch.object(driver, 'policy_removed', side_effect=policy), \
                        patch.object(driver, 'load_concurrency', return_value=SimpleNamespace(execute=lambda *a, **k: result)):
                    with self.assertRaisesRegex(driver.Rejected, '^concurrent_case$'):
                        driver.final_cases(SimpleNamespace(data={}, deadline=1), completed)
                self.assertEqual(completed, list(driver.CASES[:-1]))
        completed = list(driver.CASES[:-2])
        with patch.object(driver, 'policy_removed', side_effect=lambda w, c: c.append('policy_removed_after_issue_denied')), \
                patch.object(driver, 'load_concurrency', side_effect=RuntimeError('private error must not escape')):
            with self.assertRaisesRegex(driver.Rejected, '^concurrent_case$'):
                driver.final_cases(SimpleNamespace(data={}, deadline=1), completed)
        self.assertEqual(completed, list(driver.CASES[:-1]))

    def config(self):
        return {'version': 1, 'isolated': True, 'origin': 'https://sso.example.test',
            'account_client': 'armoney-migration-account',
            'ca_file': str(Path('owned-ca.crt').resolve()), 'controller_origin': 'https://controller.example.test',
            'controller_key': 'synthetic-controller-credential',
            'controller_paths': {op: '/controls/' + op for op in driver.OPERATIONS},
            'gateway_origin': 'https://gateway.example.test', 'introspection_client': 'acceptance-reader',
            'introspection_secret': 'synthetic-introspection-credential', 'username': '+971501234567',
            'email': 'synthetic@example.test', 'password': 'synthetic-password-credential',
            'first_name': 'Synthetic', 'last_name': 'Owner',
            'migration_callbacks': {'account': 'https://gateway.example.test/migration/legacy/account-oidc/callback',
                'managed': 'https://gateway.example.test/migration/managed/callback'}}

    def parse(self, value):
        return driver.configuration(json.dumps(value).encode())

    def test_valid_configuration(self):
        self.assertEqual(self.parse(self.config()), self.config())

    def test_missing_and_unknown_fields_fail(self):
        for key in self.config():
            value = self.config()
            del value[key]
            with self.subTest(key=key), self.assertRaises(driver.Rejected):
                self.parse(value)
        with self.assertRaises(driver.Rejected):
            self.parse(dict(self.config(), extra=True))

    def test_duplicate_json_and_nonfinite_numbers_fail(self):
        for raw in (b'{"a":1,"a":2}', b'{"a":NaN}', b'{"a":Infinity}'):
            with self.assertRaises(driver.Rejected):
                driver.parse_json(raw)

    def test_untrusted_origin_variants_fail(self):
        for value in ('http://sso.test', 'https://u:p@sso.test', 'https://sso.test/',
                      'https://sso.test?x=1', 'https://SSO.test', 'https://sso.test:0'):
            with self.subTest(value=value), self.assertRaises(driver.Rejected):
                driver.https_origin(value)

    def test_controller_operation_cannot_be_omitted(self):
        value = self.config()
        del value['controller_paths']['read_delivery']
        with self.assertRaises(driver.Rejected):
            self.parse(value)

    def test_missing_controller_capability_stops_before_browser(self):
        class UnavailableController:
            def control(self, operation, payload):
                if operation != 'capabilities' or payload != {}:
                    raise AssertionError('Unexpected operation')
                return {}
        with self.assertRaisesRegex(driver.Rejected, '^controller_capabilities$'):
            driver.execute(UnavailableController())

    def test_hidden_form_binding_cannot_be_overridden(self):
        class NoNetwork:
            data = {'origin': 'https://sso.example.test'}
            def request(self, *args, **kwargs):
                raise AssertionError('Unexpected network call')
        page = (200, {}, b'<form id="login" method="post"><input type="hidden" name="csrf" value="original"></form>',
                'https://sso.example.test/sso/auth')
        with self.assertRaisesRegex(driver.Rejected, '^form_override$'):
            driver.submit(NoNetwork(), page, 'login', {'csrf': 'replacement'})

    def test_no_shared_credentials(self):
        value = self.config()
        value['password'] = value['controller_key']
        with self.assertRaises(driver.Rejected):
            self.parse(value)

    def test_migration_callback_exact(self):
        value = self.config()
        value['migration_callbacks']['managed'] += '?purpose=bank'
        with self.assertRaises(driver.Rejected):
            self.parse(value)

    def test_pkce_actual_hash_and_freshness(self):
        first, challenge = driver.pkce()
        second, _ = driver.pkce()
        self.assertNotEqual(first, second)
        self.assertEqual(challenge, base64.urlsafe_b64encode(hashlib.sha256(first.encode()).digest()).decode().rstrip('='))
        self.assertEqual(len(challenge), 43)

    def test_callback_binding(self):
        issuer = 'https://sso.example.test/sso/realms/armoney'
        good = driver.CALLBACK + '?code=original.code.id&state=original'
        self.assertEqual(driver.callback(good, driver.CALLBACK, 'original', issuer), 'original.code.id')
        for bad in (good + '&state=original', good + '#fragment', good.replace('state=original', 'state=other'),
                    good.replace('/oauth/', '/other/'), good + '&iss=https%3A%2F%2Fwrong.test', good + '&purpose=bank'):
            with self.subTest(value=bad), self.assertRaises(driver.Rejected):
                driver.callback(bad, driver.CALLBACK, 'original', issuer)

    def test_form_keeps_hidden_binding(self):
        forms = driver.Forms('<form id="login" method="post" action="/sso/login?tab=original"><input type="hidden" name="csrf" value="original"></form>',
            'https://sso.example.test/sso/auth', 'https://sso.example.test')
        self.assertEqual(forms.forms['login']['fields'], {'csrf': 'original'})
        self.assertEqual(forms.forms['login']['action'], 'https://sso.example.test/sso/login?tab=original')

    def test_ambiguous_or_external_forms_fail(self):
        samples = ('<form id="x" method="post" action="https://evil.test/x"></form>',
            '<form id="x" method="post" action="http://sso.example.test/x"></form>',
            '<form id="x" method="post"><form id="y" method="post"></form></form>',
            '<form id="x" method="post"><input type="hidden" name="csrf"><input type="hidden" name="csrf"></form>',
            '<form id="x" id="y" method="post"></form>', '<form id="x" method="get"></form>')
        for html in samples:
            with self.subTest(html=html), self.assertRaises(driver.Rejected):
                driver.Forms(html, 'https://sso.example.test/sso/auth', 'https://sso.example.test')

    def test_expected_failure_does_not_accept_arbitrary_errors(self):
        driver.expected_status(401, 401)
        for observed in (200, 400, 403, 500, 503, True):
            with self.assertRaises(driver.Rejected):
                driver.expected_status(observed, 401)

    def test_claim_absence_rejects_even_false_claims(self):
        driver.proof({'active': True}, False)
        for claim in driver.CLAIMS:
            with self.assertRaises(driver.Rejected):
                driver.proof({'active': True, claim: False}, False)

    def test_claim_presence_requires_typed_original_event(self):
        value = {'active': True, 'armoney_login_event_id': '12345678-1234-4234-8234-123456789abc',
            'armoney_login_kind': 'interactive', 'armoney_login_notice_eligible': True}
        driver.proof(value, True)
        for claim in driver.CLAIMS:
            changed = dict(value)
            del changed[claim]
            with self.assertRaises(driver.Rejected):
                driver.proof(changed, True)

    def test_receipt_never_accepts_private_diagnostics(self):
        self.assertEqual(driver.receipt('not_verified', 'controller_status'),
            {'status': 'not_verified', 'check': 'controller_status'})
        for label in ('https://secret.test', 'password=synthetic', 'token\nvalue'):
            with self.assertRaises(driver.Rejected):
                driver.receipt('not_verified', label)

    def jwt(self, value):
        encoded = base64.urlsafe_b64encode(json.dumps(value).encode()).decode().rstrip('=')
        return 'eyJhbGciOiJSUzI1NiJ9.' + encoded + '.c3ludGhldGlj'

    def test_id_token_nonce_inspection_is_exact(self):
        issuer = 'https://sso.example.test/sso/realms/armoney'
        value = {'nonce': 'original', 'iss': issuer, 'aud': driver.CLIENT}
        driver.check_nonce({'id_token': self.jwt(value)}, 'original', issuer)
        for field, changed in (('nonce', 'other'), ('nonce', 123), ('iss', 'https://wrong.test'), ('aud', 'other-client')):
            bad = dict(value)
            bad[field] = changed
            with self.subTest(field=field), self.assertRaises(driver.Rejected):
                driver.check_nonce({'id_token': self.jwt(bad)}, 'original', issuer)
        with self.assertRaises(driver.Rejected):
            driver.check_nonce({}, 'original', issuer)

    def test_id_token_cannot_carry_banking_proof(self):
        issuer = 'https://sso.example.test/sso/realms/armoney'
        for claim in driver.CLAIMS:
            value = {'nonce': 'original', 'iss': issuer, 'aud': driver.CLIENT, claim: False}
            with self.assertRaises(driver.Rejected):
                driver.check_nonce({'id_token': self.jwt(value)}, 'original', issuer)

    def test_case_receipt_rejects_arbitrary_or_duplicate_cases(self):
        result = driver.receipt('not_verified', 'transport', ['registration_no_code'])
        self.assertEqual(result['passed_cases'], ['registration_no_code'])
        self.assertIn('node_replacement', result['unimplemented_cases'])
        for cases in (['fabricated'], ['native_confirmation', 'native_confirmation']):
            with self.assertRaises(driver.Rejected):
                driver.receipt('scoped_passed', 'implemented_cases_passed', cases)

    def test_scoped_success_requires_every_implemented_case(self):
        for completed in (None, [], list(driver.CASES[:-1])):
            with self.assertRaises(driver.Rejected):
                driver.receipt('scoped_passed', 'implemented_cases_passed', completed)
        value = driver.receipt('scoped_passed', 'implemented_cases_passed', list(driver.CASES))
        self.assertEqual(value['passed_cases'], list(driver.CASES))
        self.assertEqual(value['unimplemented_cases'], list(driver.UNIMPLEMENTED))

    def test_account_primitive_is_separate_from_full_migration(self):
        self.assertEqual(driver.CLIENT, 'armoney-android')
        self.assertIn('migration_managed_no_proof', driver.CASES)
        self.assertEqual(driver.ACCOUNT_CLIENT, 'armoney-migration-account')
        self.assertIn('migration_account_dedicated_no_proof', driver.CASES)
        self.assertIn('full_migration_callbacks_pre_final', driver.UNIMPLEMENTED)

    def test_account_client_input_cannot_select_banking_client(self):
        for client in ('armoney-web', driver.CLIENT, 'caller-selected', None):
            value = self.config()
            value['account_client'] = client
            with self.assertRaises(driver.Rejected):
                self.parse(value)

    def test_client_redirect_purposes_are_closed(self):
        data = self.config()
        driver.client_redirect(data, driver.CLIENT, driver.CALLBACK)
        driver.client_redirect(data, driver.CLIENT, data['migration_callbacks']['managed'])
        driver.client_redirect(data, driver.ACCOUNT_CLIENT, data['migration_callbacks']['account'])
        for client, redirect in ((driver.CLIENT, data['migration_callbacks']['account']),
                (driver.ACCOUNT_CLIENT, driver.CALLBACK),
                (driver.ACCOUNT_CLIENT, data['migration_callbacks']['managed']),
                ('armoney-web', data['migration_callbacks']['account'])):
            with self.assertRaises(driver.Rejected):
                driver.client_redirect(data, client, redirect)

    def test_account_nonce_is_bound_to_dedicated_audience(self):
        issuer = 'https://sso.example.test/sso/realms/armoney'
        identity = {'nonce': 'original', 'iss': issuer, 'aud': driver.ACCOUNT_CLIENT, 'azp': driver.ACCOUNT_CLIENT}
        driver.check_nonce({'id_token': self.jwt(identity)}, 'original', issuer, driver.ACCOUNT_CLIENT)
        with self.assertRaises(driver.Rejected):
            driver.check_nonce({'id_token': self.jwt(identity)}, 'original', issuer, driver.CLIENT)
        identity['azp'] = driver.CLIENT
        with self.assertRaises(driver.Rejected):
            driver.check_nonce({'id_token': self.jwt(identity)}, 'original', issuer, driver.ACCOUNT_CLIENT)

    def test_policy_restoration_after_unknown_removal(self):
        # Control-flow test only: no successful authority or HTTP response is mocked.
        operations = []
        class UnknownRemoval:
            def control(self, operation, payload):
                operations.append(operation)
                if operation == 'remove_policy':
                    raise driver.Rejected('transport')
                return {'state': 'restored'}
        with patch.object(driver, 'outcomes', return_value={'confirmed_event_ids': []}), patch.object(driver, 'fresh_code', return_value=({'code': 'synthetic'}, 'nonce')) as issued:
            with self.assertRaisesRegex(driver.Rejected, '^transport$'):
                driver.policy_removed(UnknownRemoval(), [])
            issued.assert_called_once()
        self.assertEqual(operations, ['remove_policy', 'restore_policy'])

    def test_unknown_restoration_is_a_failure(self):
        class DriftedPolicy:
            def control(self, operation, payload):
                if operation == 'remove_policy':
                    raise driver.Rejected('transport')
                return {'state': 'unknown'}
        completed = []
        with patch.object(driver, 'outcomes', return_value={'confirmed_event_ids': []}), patch.object(driver, 'fresh_code', return_value=({'code': 'synthetic'}, 'nonce')):
            with self.assertRaisesRegex(driver.Rejected, '^policy_restore$'):
                driver.policy_removed(DriftedPolicy(), completed)
        self.assertEqual(completed, [])

    def test_session_count_rejects_boolean_or_missing(self):
        class Outcome:
            data = {'username': 'synthetic'}
            def __init__(self, value):
                self.value = value
            def control(self, operation, payload):
                return self.value
        for value in ({}, {'bank_session_count': True}, {'bank_session_count': -1}):
            with self.assertRaises(driver.Rejected):
                driver.session_count(Outcome(value))


    def test_multiple_native_confirmation_deltas_retain_original_events(self):
        first = '12345678-1234-4234-8234-123456789abc'
        second = '22345678-1234-4234-8234-123456789abc'
        driver.assert_confirmation_delta([], [first], first)
        driver.assert_confirmation_delta([first], [first, second], second)
        driver.assert_confirmation_delta([first, second], [first, second])
        for before, after, event in (([first], [second], second), ([first], [first], first),
                ([], [first, second], first), ([first], [first, second], None)):
            with self.assertRaises(driver.Rejected):
                driver.assert_confirmation_delta(before, after, event)

    def test_confirmation_inventory_is_bounded_sorted_unique_and_canonical(self):
        first = '12345678-1234-4234-8234-123456789abc'
        second = '22345678-1234-4234-8234-123456789abc'
        self.assertEqual(driver.confirmation_ids([first, second]), {first, second})
        for value in (None, first, [first, first], [second, first], [first.upper()], [False],
                      ['not-an-event'], [str(__import__('uuid').UUID(int=i)) for i in range(17)]):
            with self.assertRaises(driver.Rejected):
                driver.confirmation_ids(value)

    def test_v1_nullable_confirmation_schema_is_rejected(self):
        class OldController:
            data = {'username': 'synthetic'}
            def control(self, operation, payload):
                return {'registration_active': False, 'projection_acknowledged': False,
                        'bank_session_count': 0, 'confirmed_event_id': None}
        with self.assertRaisesRegex(driver.Rejected, '^outcomes_schema$'):
            driver.outcomes(OldController())


    def pending_page(self, button='<button name="operation" value="status" type="submit" formnovalidate>Check status</button>'):
        # Literal production renderPending message: do not derive from the driver
        # constant, which would conceal the actual cross-file contract mismatch.
        html = ('<h1>Verify your phone</h1><p>Contact verified. Account activation is pending; check status to continue.</p>'
            '<form id="armoney-verification" method="post" action="/sso/login-actions/required-action?execution=armoney-verify-phone-code&amp;tab_id=synthetic">'
            '<input type="hidden" name="csrf" value="original">' + button + '</form>')
        return (200, {}, html.encode(), 'https://sso.example.test/sso/login-actions/required-action')

    def test_pending_projection_uses_rendered_status_button_without_otp(self):
        pending = self.pending_page()
        terminal = (200, {}, driver.TERMINAL_INFO, pending[3])
        class Browser:
            data = {'origin': 'https://sso.example.test'}
        wire = Browser()
        with patch.object(driver, 'submit', return_value=terminal) as submit, patch.object(driver, 'follow', side_effect=lambda wire, page: page):
            self.assertEqual(driver.await_registration_info(wire, pending, clock=lambda: 0, pause=lambda _: None), terminal)
        submit.assert_called_once_with(wire, pending, 'armoney-verification', {'operation': 'status'})

    def test_pending_fixture_matches_actual_provider_render_pending(self):
        provider = Path(__file__).parent.parent / 'src/main/java/com/arman/bank/sso/VerificationAction.java'
        message = 'Contact verified. Account activation is pending; check status to continue.'
        self.assertIn('render(context,"' + message + '",true)', provider.read_text(encoding='utf-8'))
        self.assertIn(message.encode(), self.pending_page()[2])
        self.assertEqual(driver.PENDING_INFO, message.encode())

    def test_pending_requires_real_enabled_status_button_in_phone_form(self):
        class Browser:
            data = {'origin': 'https://sso.example.test'}
        for button in ('', '<button name="operation" value="verify" formnovalidate>Verify</button>',
                '<button name="operation" value="status">Check status</button>',
                '<button name="operation" value="status" formnovalidate disabled>Check status</button>',
                '<button name="operation" value="status" formnovalidate formaction="/other">Check status</button>'):
            with self.subTest(button=button), patch.object(driver, 'submit') as submit:
                with self.assertRaisesRegex(driver.Rejected, '^pending_status_form$'):
                    driver.await_registration_info(Browser(), self.pending_page(button), clock=lambda: 0, pause=lambda _: None)
                submit.assert_not_called()

    def test_status_retry_retains_form_binding_and_sends_no_otp(self):
        class Browser:
            data = {'origin': 'https://sso.example.test'}
            def request(self, url, *, form):
                self.sent = form
                self.url = url
                return 200, {}, driver.TERMINAL_INFO
        wire = Browser()
        driver.await_registration_info(wire, self.pending_page(), clock=lambda: 0, pause=lambda _: None)
        self.assertEqual(wire.sent, {'csrf': 'original', 'operation': 'status'})
        self.assertIn('execution=armoney-verify-phone-code&tab_id=synthetic', wire.url)

    def test_pending_is_not_pass_and_unavailable_does_not_retry(self):
        pending = (200, {}, driver.PENDING_INFO, 'https://sso.example.test/sso/login-actions/required-action')
        ticks = iter((0, 16))
        with self.assertRaisesRegex(driver.Rejected, '^projection_pending$'):
            driver.await_registration_info(object(), pending, clock=lambda: next(ticks), pause=lambda _: None)
        unavailable = (200, {}, b'Verification is unavailable. Please try again.', pending[3])
        with patch.object(driver, 'submit') as submit:
            with self.assertRaisesRegex(driver.Rejected, '^registration_info$'):
                driver.await_registration_info(object(), unavailable)
            submit.assert_not_called()

    def test_registration_code_redirect_is_not_terminal_info(self):
        page = (302, {'Location': driver.CALLBACK + '?code=unexpected'}, driver.TERMINAL_INFO,
                'https://sso.example.test/sso/login-actions/required-action')
        with self.assertRaises(driver.Rejected):
            driver.await_registration_info(object(), page)


    def test_email_verification_must_advance_to_phone_before_phone_delivery(self):
        class Browser:
            data = {'origin': 'https://sso.example.test'}
        def page(heading, execution):
            html = ('<h1>Verify your ' + heading + '</h1><form id="armoney-verification" method="post" '
                'action="/sso/login-actions/required-action?execution=' + execution + '&amp;tab_id=synthetic"></form>')
            return (200, {}, html.encode(), 'https://sso.example.test/sso/login-actions/required-action')
        driver.require_phone_transition(Browser(), page('phone', 'armoney-verify-phone-code'))
        for heading, action in (('email', 'armoney-verify-email-code'), ('email', 'armoney-verify-phone-code'),
                ('phone', 'armoney-verify-email-code'), ('phone', 'unknown'),
                ('phone', 'armoney-verify-phone-code&amp;execution=armoney-verify-email-code')):
            with self.subTest(heading=heading, action=action), self.assertRaisesRegex(driver.Rejected, '^phone_transition$'):
                driver.require_phone_transition(Browser(), page(heading, action))


    def test_ordinary_authorization_does_not_request_offline_scope(self):
        class Browser:
            data = {'origin': 'https://sso.example.test', 'migration_callbacks': {
                'managed': 'https://gateway.example.test/migration/managed/callback',
                'account': 'https://gateway.example.test/migration/legacy/account-oidc/callback'}}
            def __init__(self):
                self.requests = []
            def request(self, url):
                self.requests.append(url)
                return 200, {}, b''
        ordinary, offline = Browser(), Browser()
        driver.authorize(ordinary)
        driver.authorize(offline, offline=True)
        for browser, scope in ((ordinary, 'openid'), (offline, 'openid offline_access')):
            self.assertEqual(len(browser.requests), 1)
            query = urllib.parse.parse_qs(urllib.parse.urlsplit(browser.requests[0]).query)
            self.assertEqual(query['scope'], [scope])
            self.assertEqual(query['client_id'], [driver.CLIENT])
            self.assertEqual(query['redirect_uri'], [driver.CALLBACK])
            self.assertEqual(query['code_challenge_method'], ['S256'])
            self.assertEqual(query['prompt'], ['login'])
            self.assertEqual(query['max_age'], ['0'])
            self.assertEqual(len(query['code_challenge'][0]), 43)
            self.assertTrue(query['state'][0])
            self.assertTrue(query['nonce'][0])
        self.assertNotEqual(ordinary.requests, offline.requests)


    def test_control_pacing_first_call_spacing_and_no_unneeded_wait(self):
        class Clock:
            now = 0.0
            waits = None
            def __init__(self):
                self.waits = []
            def read(self):
                return self.now
            def sleep(self, seconds):
                self.waits.append(seconds)
                self.now += seconds
        clock = Clock()
        pacer = driver.ControlPacer(360, clock.read, clock.sleep)
        pacer.before_post()
        self.assertEqual(clock.waits, [])
        clock.now = .1
        pacer.before_post()
        self.assertEqual(len(clock.waits), 1)
        self.assertAlmostEqual(clock.waits[0], .45)
        self.assertAlmostEqual(pacer.last, .55)
        clock.now = 2
        pacer.before_post()
        self.assertEqual(len(clock.waits), 1)
        self.assertEqual(pacer.last, 2)

    def test_control_pacing_cannot_extend_deadline_or_send_after_oversleep(self):
        waits = []
        pacer = driver.ControlPacer(.5, lambda: 0, waits.append)
        pacer.before_post()
        with self.assertRaisesRegex(driver.Rejected, '^deadline$'):
            pacer.before_post()
        self.assertEqual(waits, [])
        ticks = iter((0, .1, 361))
        pacer = driver.ControlPacer(360, lambda: next(ticks), waits.append)
        pacer.before_post()
        with self.assertRaisesRegex(driver.Rejected, '^deadline$'):
            pacer.before_post()
        self.assertEqual(len(waits), 1)

    def test_control_429_is_one_failed_post_without_retry(self):
        wire = driver.Wire.__new__(driver.Wire)
        wire.data = {'controller_origin': 'https://controller.example.test', 'controller_key': 'synthetic',
                     'controller_paths': {'outcomes': '/fixture/production-provider/outcomes'}}
        wire.control_pacer = driver.ControlPacer(360, lambda: 0, lambda _: self.fail('First call must not wait'))
        calls = []
        def request(*args, **kwargs):
            calls.append(1)
            return 429, {}, b''
        wire.request = request
        with self.assertRaisesRegex(driver.Rejected, '^controller_status$'):
            wire.control('outcomes', {'username': 'synthetic'})
        self.assertEqual(calls, [1])


if __name__ == '__main__':
    unittest.main()
