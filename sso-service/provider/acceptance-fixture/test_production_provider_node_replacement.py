"""Opt-in live browser handshake for root-owned production-node replacement.

Root pins A, removes/replaces A while B survives, then rejoins A without import.
This browser does not attest routing/membership. Root must hard-kill/reap this
process at the original deadline, including blocked stdin/socket operations.
Original code/verifier/nonce live only in memory; no code TTL is renewed.
"""
import argparse
import importlib.util
import json
from pathlib import Path
import queue
import sys
import threading
import time

_SPEC = importlib.util.spec_from_file_location('node_replacement_core',
    Path(__file__).with_name('test_production_provider_acceptance.py'))
C = importlib.util.module_from_spec(_SPEC)
_SPEC.loader.exec_module(C)
CASE = 'node_replacement'
LABELS = {'node_input', 'node_deadline', 'node_command', 'node_stage',
          'node_redeem', 'node_replay', 'node_unexpected'}


class Rejected(ValueError):
    def __init__(self, label):
        super().__init__(label if label in LABELS else 'node_unexpected')


def require(value, label):
    if not value:
        raise Rejected(label)


def budget(deadline, clock):
    require(type(deadline) in (int, float) and 0 < deadline - clock() <= 360, 'node_deadline')


def command(source, expected, deadline, clock):
    require(expected in ('redeem', 'replay'), 'node_command')
    budget(deadline, clock)
    mailbox = queue.Queue(maxsize=1)
    def read():
        try:
            mailbox.put((True, source.readline(257)))
        except Exception:
            mailbox.put((False, b''))
    # A blocked reader cannot outlive the root-owned process deadline.
    threading.Thread(target=read, daemon=True).start()
    try:
        ok, raw = mailbox.get(timeout=max(0, deadline - clock()))
    except queue.Empty:
        raise Rejected('node_deadline') from None
    budget(deadline, clock)
    require(ok and type(raw) is bytes and 0 < len(raw) <= 256 and raw.endswith(b'\n'), 'node_command')
    try:
        value = C.parse_json(raw)
        require(type(value) is dict and set(value) == {'command'} and value['command'] == expected, 'node_command')
    except Exception:
        raise Rejected('node_command') from None


def receipt(phase, status='ready', check=None):
    require(phase in ('code_staged', 'redeemed', 'replay_denied', 'failed')
            and status in ('ready', 'scoped_passed', 'not_verified'), 'node_input')
    require((phase, status) in (('code_staged', 'ready'), ('redeemed', 'ready'),
            ('replay_denied', 'scoped_passed'), ('failed', 'not_verified')), 'node_input')
    value = {'version': 1, 'phase': phase, 'status': status, 'whole_migration_acceptance': False}
    if phase == 'replay_denied':
        value['case'] = CASE
    if phase == 'failed':
        require(check in LABELS, 'node_input')
        value['check'] = check
    else:
        require(check is None, 'node_input')
    return value


def emit(sink, value):
    sink.write(json.dumps(value, separators=(',', ':')) + '\n')
    sink.flush()


def execute(data, *, deadline, source, sink, factory=C.Wire, clock=time.monotonic):
    budget(deadline, clock)
    wire = factory(data)
    wire.deadline = deadline
    wire.control_pacer = C.ControlPacer(deadline)
    try:
        before = C.outcomes(wire)
        require(before['registration_active'] and before['projection_acknowledged'], 'node_stage')
        form, nonce = C.fresh_code(wire)  # ordinary openid, genuine original PKCE/state
        require(C.outcomes(wire) == before, 'node_stage')
        budget(deadline, clock)
        emit(sink, receipt('code_staged'))
    except Rejected:
        raise
    except Exception:
        raise Rejected('node_stage') from None
    command(source, 'redeem', deadline, clock)
    try:
        # Exactly one original redemption. No loop, restage, manual confirm or retry.
        tokens = C.token_response(wire, form, nonce)
        require(all(type(tokens.get(name)) is str for name in ('access_token', 'refresh_token', 'id_token')), 'node_redeem')
        facts = C.introspect(wire, tokens['access_token'])
        C.proof(facts, True)
        for name in ('refresh_token', 'id_token'):
            require(not C.CLAIMS.intersection(C.inspect_jwt(tokens[name])), 'node_redeem')
        confirmed = C.outcomes(wire)
        C.assert_confirmation_delta(before['confirmed_event_ids'], confirmed['confirmed_event_ids'], facts['armoney_login_event_id'])
        require(confirmed == dict(before, confirmed_event_ids=confirmed['confirmed_event_ids']), 'node_redeem')
        C.gateway(wire, tokens['access_token'], 200)
        admitted = C.outcomes(wire)
        require(admitted == dict(confirmed, bank_session_count=before['bank_session_count'] + 1), 'node_redeem')
        budget(deadline, clock)
        emit(sink, receipt('redeemed'))
    except Rejected:
        raise
    except Exception:
        raise Rejected('node_redeem') from None
    command(source, 'replay', deadline, clock)
    try:
        require(C.outcomes(wire) == admitted, 'node_replay')
        status, _, raw = wire.request(data['origin'] + '/sso/realms/armoney/protocol/openid-connect/token', form=form)
        require(type(status) is int and status == 400, 'node_replay')
        error = C.parse_json(raw)
        require(type(error) is dict and error.get('error') == 'invalid_grant'
                and not {'access_token', 'refresh_token', 'id_token'}.intersection(error), 'node_replay')
        require(C.outcomes(wire) == admitted, 'node_replay')
        budget(deadline, clock)
        result = receipt('replay_denied', 'scoped_passed')
        emit(sink, result)
        return result
    except Rejected:
        raise
    except Exception:
        raise Rejected('node_replay') from None


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--input', required=True)
    parser.add_argument('--deadline', required=True, type=float)
    args = parser.parse_args()
    try:
        data = C.configuration(C.read_private(args.input))
        execute(data, deadline=args.deadline, source=sys.stdin.buffer, sink=sys.stdout)
        return 0
    except Exception as failure:
        label = str(failure) if isinstance(failure, Rejected) else 'node_unexpected'
        emit(sys.stdout, receipt('failed', 'not_verified', label))
        return 1


if __name__ == '__main__':
    raise SystemExit(main())
