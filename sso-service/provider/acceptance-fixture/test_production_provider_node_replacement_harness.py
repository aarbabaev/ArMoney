"""Portable handshake/invariant checks; no actual node/runtime evidence."""
import importlib.util
import io
import json
from pathlib import Path
from types import SimpleNamespace
import unittest
from unittest.mock import Mock, patch

_SPEC = importlib.util.spec_from_file_location('node_replacement',
    Path(__file__).with_name('test_production_provider_node_replacement.py'))
N = importlib.util.module_from_spec(_SPEC)
_SPEC.loader.exec_module(N)
OLD = '00000000-0000-4000-8000-000000000001'
NEW = '00000000-0000-4000-8000-000000000002'
EXTRA = '00000000-0000-4000-8000-000000000003'


class NodeReplacementHarness(unittest.TestCase):
    def outcome(self, events=(OLD,), sessions=2):
        return {'registration_active': True, 'projection_acknowledged': True,
                'confirmed_event_ids': list(events), 'bank_session_count': sessions}

    def run_case(self, *, commands=b'{"command":"redeem"}\n{"command":"replay"}\n',
                 outcomes=None, status=400, body=b'{"error":"invalid_grant"}', tokens=None):
        baseline = self.outcome()
        confirmed = self.outcome((OLD, NEW))
        admitted = self.outcome((OLD, NEW), 3)
        results = outcomes if outcomes is not None else [baseline, baseline, confirmed, admitted, admitted, admitted]
        wire = SimpleNamespace(request=Mock(return_value=(status, {}, body)))
        form = {'code': 'private-original-code', 'code_verifier': 'private-original-verifier'}
        tokens = tokens if tokens is not None else {'access_token': 'private-at', 'refresh_token': 'private-rt', 'id_token': 'private-id'}
        facts = {'active': True, 'armoney_login_event_id': NEW, 'armoney_login_kind': 'interactive',
                 'armoney_login_notice_eligible': True}
        sink = io.StringIO()
        with patch.object(N.C, 'outcomes', side_effect=results), \
                patch.object(N.C, 'fresh_code', return_value=(form, 'private-nonce')) as fresh, \
                patch.object(N.C, 'token_response', return_value=tokens) as redeem, \
                patch.object(N.C, 'introspect', return_value=facts), \
                patch.object(N.C, 'inspect_jwt', return_value={}), \
                patch.object(N.C, 'gateway') as gateway:
            try:
                result = N.execute({'origin': 'https://fixture.test'}, deadline=400.0,
                    source=io.BytesIO(commands), sink=sink, factory=lambda data: wire, clock=lambda: 100)
            except N.Rejected as error:
                return error, sink, wire, fresh.call_count, redeem.call_count, gateway.call_count
        self.assertEqual(redeem.call_args.args, (wire, form, 'private-nonce'))
        self.assertIs(wire.request.call_args.kwargs['form'], form)
        self.assertEqual(wire.deadline, 400.0)
        self.assertEqual(wire.control_pacer.deadline, 400.0)
        return result, sink, wire, fresh.call_count, redeem.call_count, gateway.call_count

    def test_success_requires_exact_three_phases_and_original_form_once_each(self):
        result, sink, wire, fresh, redeem, gateway = self.run_case()
        self.assertEqual(result, N.receipt('replay_denied', 'scoped_passed'))
        self.assertEqual([json.loads(line)['phase'] for line in sink.getvalue().splitlines()],
                         ['code_staged', 'redeemed', 'replay_denied'])
        self.assertEqual((fresh, redeem, gateway, wire.request.call_count), (1, 1, 1, 1))
        for private in ('private-original-code', 'private-original-verifier', 'private-nonce', 'private-at', OLD, NEW):
            self.assertNotIn(private, sink.getvalue())

    def test_unknown_out_of_order_eof_oversize_extra_or_duplicate_command_rejected(self):
        commands = (b'', b'{"command":"replay"}\n', b'{"command":"unknown"}\n',
                    b'{"command":"redeem","extra":1}\n',
                    b'{"command":"redeem","command":"redeem"}\n',
                    b' ' * 257 + b'\n', b'{"command":"redeem"}')
        for raw in commands:
            with self.subTest(raw=raw):
                result, sink, wire, fresh, redeem, gateway = self.run_case(commands=raw)
                self.assertIsInstance(result, N.Rejected)
                self.assertEqual(str(result), 'node_command')
                self.assertEqual((fresh, redeem, gateway, wire.request.call_count), (1, 0, 0, 0))
                self.assertNotIn('replay_denied', sink.getvalue())

    def test_second_command_requires_replay_without_redeeming_again(self):
        result, sink, wire, fresh, redeem, gateway = self.run_case(commands=b'{"command":"redeem"}\n{"command":"redeem"}\n')
        self.assertIsInstance(result, N.Rejected)
        self.assertEqual((fresh, redeem, gateway, wire.request.call_count), (1, 1, 1, 0))

    def test_expired_or_renewed_deadline_rejects_before_read(self):
        source = Mock()
        for deadline in (100, 461, float('nan'), float('inf'), True):
            with self.subTest(deadline=deadline), self.assertRaisesRegex(N.Rejected, '^node_deadline$'):
                N.command(source, 'redeem', deadline, lambda: 100)
        source.readline.assert_not_called()

    def test_deadline_checked_after_command_read(self):
        clock = iter((100, 100, 401))
        with self.assertRaisesRegex(N.Rejected, '^node_deadline$'):
            N.command(io.BytesIO(b'{"command":"redeem"}\n'), 'redeem', 400, lambda: next(clock))

    def test_command_wait_is_bounded_by_remaining_original_budget(self):
        source = Mock()
        with patch.object(N.threading, 'Thread') as thread, \
                patch.object(N.queue, 'Queue') as queue:
            queue.return_value.get.side_effect = N.queue.Empty
            with self.assertRaisesRegex(N.Rejected, '^node_deadline$'):
                N.command(source, 'redeem', 400, lambda: 100)
            queue.return_value.get.assert_called_once_with(timeout=300)
            self.assertTrue(thread.call_args.kwargs['daemon'])

    def test_stage_extra_event_or_session_prevents_ready(self):
        for staged in (self.outcome((OLD, NEW)), self.outcome(sessions=3)):
            result, sink, wire, fresh, redeem, gateway = self.run_case(outcomes=[self.outcome(), staged])
            self.assertIsInstance(result, N.Rejected)
            self.assertEqual(str(result), 'node_stage')
            self.assertEqual(sink.getvalue(), '')
            self.assertEqual(redeem, 0)

    def test_confirmation_extra_event_or_pre_gateway_session_rejected(self):
        for confirmed in (self.outcome((OLD, NEW, EXTRA)), self.outcome((OLD, NEW), 3)):
            result, sink, wire, fresh, redeem, gateway = self.run_case(outcomes=[self.outcome(), self.outcome(), confirmed])
            self.assertIsInstance(result, N.Rejected)
            self.assertEqual(str(result), 'node_redeem')
            self.assertEqual(gateway, 0)

    def test_gateway_must_add_one_session_and_preserve_full_inventory(self):
        for admitted in (self.outcome((OLD, NEW), 2), self.outcome((OLD, NEW), 4), self.outcome((NEW,), 3)):
            result, sink, wire, fresh, redeem, gateway = self.run_case(outcomes=[self.outcome(), self.outcome(), self.outcome((OLD, NEW)), admitted])
            self.assertIsInstance(result, N.Rejected)
            self.assertEqual(str(result), 'node_redeem')
            self.assertEqual(wire.request.call_count, 0)

    def test_first_redemption_without_all_three_tokens_rejected(self):
        for missing in ('access_token', 'refresh_token', 'id_token'):
            tokens = {name: 'private' for name in ('access_token', 'refresh_token', 'id_token') if name != missing}
            result, sink, wire, fresh, redeem, gateway = self.run_case(tokens=tokens)
            self.assertIsInstance(result, N.Rejected)
            self.assertEqual(str(result), 'node_redeem')
            self.assertEqual(wire.request.call_count, 0)

    def test_replay_requires_400_invalid_grant_without_any_token(self):
        cases = [(200, b'{"error":"invalid_grant"}'), (503, b'{"error":"invalid_grant"}'),
                 (400, b'{"error":"temporarily_unavailable"}'), (400, b'not-json')]
        cases += [(400, json.dumps({'error': 'invalid_grant', name: 'private'}).encode())
                  for name in ('access_token', 'refresh_token', 'id_token')]
        for status, body in cases:
            with self.subTest(status=status, body=body):
                result, sink, wire, fresh, redeem, gateway = self.run_case(status=status, body=body)
                self.assertIsInstance(result, N.Rejected)
                self.assertEqual(str(result), 'node_replay')
                self.assertNotIn('replay_denied', sink.getvalue())
                self.assertEqual(wire.request.call_count, 1)

    def test_replay_cannot_change_inventory_or_session_count(self):
        admitted = self.outcome((OLD, NEW), 3)
        for changed in (self.outcome((OLD, NEW, EXTRA), 3), self.outcome((OLD, NEW), 4)):
            result, sink, wire, fresh, redeem, gateway = self.run_case(outcomes=[self.outcome(), self.outcome(),
                self.outcome((OLD, NEW)), admitted, admitted, changed])
            self.assertIsInstance(result, N.Rejected)
            self.assertEqual(str(result), 'node_replay')

    def test_receipts_closed_and_never_whole_acceptance(self):
        for phase, status in (('code_staged', 'ready'), ('redeemed', 'ready'), ('replay_denied', 'scoped_passed')):
            self.assertFalse(N.receipt(phase, status)['whole_migration_acceptance'])
        for phase, status in (('code_staged', 'scoped_passed'), ('private', 'ready')):
            with self.assertRaises(N.Rejected):
                N.receipt(phase, status)
        self.assertEqual(str(N.Rejected('private exception')), 'node_unexpected')


if __name__ == '__main__':
    unittest.main()
