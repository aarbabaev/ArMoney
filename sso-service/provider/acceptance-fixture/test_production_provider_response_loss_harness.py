"""Portable phase-contract tests only; mocks establish no runtime acceptance."""
import importlib.util
import json
from pathlib import Path
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import Mock, patch

_SPEC = importlib.util.spec_from_file_location('response_loss',
    Path(__file__).with_name('test_production_provider_response_loss.py'))
R = importlib.util.module_from_spec(_SPEC)
_SPEC.loader.exec_module(R)
EVENT = '00000000-0000-4000-8000-000000000001'
FRESH = '00000000-0000-4000-8000-000000000002'


class ResponseLossHarness(unittest.TestCase):
    def setUp(self):
        self.clock = patch.object(R.time, 'monotonic', return_value=100)
        self.clock.start()
        self.addCleanup(self.clock.stop)

    def saved(self, operation='confirm'):
        return {'version': 1, 'operation': operation, 'deadline': 400.0,
            'before_events': [], 'after_events': [EVENT] if operation == 'confirm' else [],
            'bank_sessions': 0, 'verifier_hash': R.digest('old-verifier'),
            'state_hash': R.digest('old-state'), 'nonce_hash': R.digest('old-nonce'),
            'code_hash': R.digest('old-code') if operation == 'confirm' else None}

    def outcome(self, events=(), sessions=0):
        return {'registration_active': True, 'projection_acknowledged': True,
                'bank_session_count': sessions, 'confirmed_event_ids': list(events)}

    def wire(self, status=503, body=b'{"error":"temporarily_unavailable"}'):
        wire = SimpleNamespace(data={'origin': 'https://fixture.test'}, request=Mock(return_value=(status, {}, body)))
        return wire

    def run_drop(self, operation, page=None, status=503, body=b'{"error":"temporarily_unavailable"}', after=None):
        wire = self.wire(status, body)
        factory = Mock(return_value=wire)
        page = page if page is not None else (503, {}, b'', 'https://fixture.test/action')
        with patch.object(R.C, 'outcomes', side_effect=[self.outcome(), after or self.outcome([EVENT] if operation == 'confirm' else [])]), \
                patch.object(R, 'login', return_value=(page, 'old-verifier', 'old-state', 'old-nonce')), \
                patch.object(R, 'code_form', return_value={'code': 'old-code'}) as form:
            result = R.drop(wire.data, operation=operation, deadline=400.0, factory=factory)
        return result, wire, form

    def test_stage_503_withholds_code_and_never_calls_token_endpoint(self):
        (receipt, saved), wire, form = self.run_drop('stage')
        self.assertEqual(receipt, R.phase_receipt('stage', 'drop'))
        self.assertIsNone(saved['code_hash'])
        self.assertEqual(saved['after_events'], [])
        wire.request.assert_not_called()
        form.assert_not_called()

    def test_stage_redirect_or_leaked_credentials_not_accepted(self):
        pages = [(302, {'Location': 'com.armoney.android:/oauth/callback?code=private'}, b'', 'unused'),
                 (503, {'Location': 'private'}, b'', 'unused'),
                 (503, {}, b'{"access_token":"private"}', 'unused'),
                 (503, {}, b'?code=private', 'unused')]
        for page in pages:
            with self.subTest(page=page), self.assertRaisesRegex(R.Rejected, '^response_loss_drop$'):
                self.run_drop('stage', page=page)

    def test_confirm_error_only_once_without_original_code_persistence(self):
        for status in (400, 503):
            (receipt, saved), wire, form = self.run_drop('confirm', status=status)
            self.assertEqual(receipt['status'], 'phase_verified')
            self.assertEqual(wire.request.call_count, 1)
            self.assertEqual(form.call_count, 1)
            self.assertNotIn('old-code', json.dumps(saved))
            self.assertEqual(saved['code_hash'], R.digest('old-code'))

    def test_confirm_success_or_any_returned_token_rejected(self):
        cases = [(200, b'{"access_token":"private"}'), (503, b'not-json'),
                 (503, b'{"error":"unexpected"}')]
        cases += [(503, json.dumps({'error': 'temporarily_unavailable', name: 'private'}).encode())
                  for name in ('access_token', 'refresh_token', 'id_token')]
        for status, body in cases:
            with self.subTest(status=status, body=body), self.assertRaisesRegex(R.Rejected, '^response_loss_drop$'):
                self.run_drop('confirm', status=status, body=body)

    def test_drop_requires_expected_confirmation_delta_and_no_bank_insert(self):
        for operation, after in (('stage', self.outcome([EVENT])), ('confirm', self.outcome()),
                                 ('confirm', self.outcome([EVENT], 1))):
            with self.subTest(operation=operation, after=after), self.assertRaises(R.Rejected):
                self.run_drop(operation, after=after)

    def test_same_deadline_propagated_to_wire_and_pacer(self):
        wire = self.wire()
        self.assertIs(R.wire_for({}, 400.0, lambda data: wire), wire)
        self.assertEqual(wire.deadline, 400.0)
        self.assertEqual(wire.control_pacer.deadline, 400.0)
        for deadline in (100, 461, float('nan'), float('inf'), True):
            with self.subTest(deadline=deadline), self.assertRaisesRegex(R.Rejected, '^response_loss_deadline$'):
                R.budget(deadline)

    def test_context_schema_and_hashes_closed(self):
        self.assertEqual(R.context(self.saved()), self.saved())
        cases = [dict(self.saved(), extra='private'), dict(self.saved(), code_hash=None),
                 dict(self.saved(), bank_sessions=True), dict(self.saved(), nonce_hash='private'),
                 dict(self.saved('stage'), code_hash=R.digest('code'))]
        for value in cases:
            with self.subTest(value=value), self.assertRaises(R.Rejected):
                R.context(value)

    def test_private_file_exclusive_and_permissions_revalidated(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'context.json'
            R.write_private(path, self.saved())
            self.assertEqual(R.read_context(path), self.saved())
            with self.assertRaises(FileExistsError):
                R.write_private(path, self.saved())
            path.chmod(0o644)
            with self.assertRaisesRegex(R.Rejected, '^response_loss_context$'):
                R.read_context(path)

    def run_recovery(self, *, saved=None, code='new-code', verifier='new-verifier',
                     state='new-state', nonce='new-nonce', final=None):
        saved = saved or self.saved()
        original = saved['after_events']
        combined = sorted(original + [FRESH])
        outcomes = [self.outcome(original), self.outcome(combined), final or self.outcome(combined, 1)]
        wire = self.wire()
        facts = {'active': True, 'armoney_login_event_id': FRESH,
                 'armoney_login_kind': 'interactive', 'armoney_login_notice_eligible': True}
        tokens = {'access_token': 'synthetic-at', 'refresh_token': 'synthetic-rt', 'id_token': 'synthetic-id'}
        with patch.object(R.C, 'outcomes', side_effect=outcomes), \
                patch.object(R, 'login', return_value=((302, {}, b'', 'unused'), verifier, state, nonce)), \
                patch.object(R, 'code_form', return_value={'code': code}), \
                patch.object(R.C, 'token_response', return_value=tokens) as token, \
                patch.object(R.C, 'introspect', return_value=facts), \
                patch.object(R.C, 'inspect_jwt', return_value={}), \
                patch.object(R.C, 'gateway') as gateway:
            result = R.recover(wire.data, saved, deadline=400.0, factory=lambda data: wire)
            self.assertEqual(token.call_count, 1)
            self.assertEqual(token.call_args.args[2], nonce)
            gateway.assert_called_once_with(wire, 'synthetic-at', 200)
        return result

    def test_recovery_phase_requires_fresh_c2_and_gateway_session(self):
        for operation in ('stage', 'confirm'):
            self.assertEqual(self.run_recovery(saved=self.saved(operation)), R.phase_receipt(operation, 'recover'))

    def test_recovery_rejects_original_code_or_reused_flow_secrets(self):
        for values in ({'code': 'old-code'}, {'verifier': 'old-verifier'},
                       {'state': 'old-state'}, {'nonce': 'old-nonce'}):
            with self.subTest(values=values), self.assertRaisesRegex(R.Rejected, '^response_loss_recovery$'):
                self.run_recovery(**values)

    def test_gateway_must_preserve_full_inventory_and_add_exactly_one_session(self):
        for final in (self.outcome([EVENT, FRESH], 0), self.outcome([FRESH], 1),
                      self.outcome([EVENT, FRESH], 2)):
            with self.subTest(final=final), self.assertRaisesRegex(R.Rejected, '^response_loss_recovery$'):
                self.run_recovery(final=final)

    def test_recovery_cannot_renew_deadline(self):
        factory = Mock()
        with self.assertRaisesRegex(R.Rejected, '^response_loss_deadline$'):
            R.recover({}, self.saved(), deadline=401.0, factory=factory)
        factory.assert_not_called()

    def test_public_receipt_never_contains_private_context_or_whole_pass(self):
        for operation in ('stage', 'confirm'):
            for phase in ('drop', 'recover'):
                receipt = R.phase_receipt(operation, phase)
                self.assertEqual(set(receipt), {'operation', 'phase', 'status',
                    'original_request_retried', 'whole_migration_acceptance'})
                self.assertFalse(receipt['whole_migration_acceptance'])
                self.assertFalse(receipt['original_request_retried'])
                self.assertNotIn('scoped_passed', json.dumps(receipt))
        for operation, phase in (('private', 'drop'), ('stage', 'private')):
            with self.assertRaisesRegex(R.Rejected, '^response_loss_input$'):
                R.phase_receipt(operation, phase)


if __name__ == '__main__':
    unittest.main()
