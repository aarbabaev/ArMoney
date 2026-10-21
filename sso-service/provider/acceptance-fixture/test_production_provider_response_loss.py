"""Opt-in real browser phases for root-owned response-loss acceptance.

Root alone arms the peer and captures baseline/drop/fresh read-only DB evidence.
Neither phase claims a complete acceptance case. Never retry the original code.
The root runner must reap the process at its original whole-run deadline.
"""
import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import stat
import time

_SPEC = importlib.util.spec_from_file_location('response_loss_core',
    Path(__file__).with_name('test_production_provider_acceptance.py'))
C = importlib.util.module_from_spec(_SPEC)
_SPEC.loader.exec_module(C)
CONTEXT_LIMIT = 4096
CONTEXT_KEYS = {'version', 'operation', 'deadline', 'before_events', 'after_events',
    'bank_sessions', 'verifier_hash', 'state_hash', 'nonce_hash', 'code_hash'}
LABELS = {'response_loss_input', 'response_loss_deadline', 'response_loss_context',
          'response_loss_drop', 'response_loss_recovery', 'response_loss_unexpected'}


class Rejected(ValueError):
    def __init__(self, label):
        super().__init__(label if label in LABELS else 'response_loss_unexpected')


def require(value, label):
    if not value:
        raise Rejected(label)


def budget(deadline):
    require(type(deadline) in (int, float) and 0 < deadline - time.monotonic() <= 360,
            'response_loss_deadline')


def digest(value):
    require(type(value) is str and 0 < len(value) <= 16384, 'response_loss_context')
    return hashlib.sha256(value.encode()).hexdigest()


def context(value):
    try:
        require(type(value) is dict and set(value) == CONTEXT_KEYS, 'response_loss_context')
        require(type(value['version']) is int and value['version'] == 1
                and value['operation'] in ('stage', 'confirm'), 'response_loss_context')
        budget(value['deadline'])
        before, after = C.confirmation_ids(value['before_events']), C.confirmation_ids(value['after_events'])
        require(before <= after and len(after - before) == (value['operation'] == 'confirm'), 'response_loss_context')
        require(type(value['bank_sessions']) is int and 0 <= value['bank_sessions'] <= 64, 'response_loss_context')
        for name in ('verifier_hash', 'state_hash', 'nonce_hash'):
            require(type(value[name]) is str and re.fullmatch(r'[0-9a-f]{64}', value[name]), 'response_loss_context')
        require(value['code_hash'] is None if value['operation'] == 'stage' else
                type(value['code_hash']) is str and re.fullmatch(r'[0-9a-f]{64}', value['code_hash']), 'response_loss_context')
        return value
    except Rejected:
        raise
    except Exception:
        raise Rejected('response_loss_context') from None


def read_context(path):
    fd = None
    try:
        fd = os.open(path, os.O_RDONLY | os.O_NOFOLLOW)
        info = os.fstat(fd)
        require(stat.S_ISREG(info.st_mode) and info.st_mode & 0o777 == 0o600
                and info.st_uid == os.getuid() and info.st_nlink == 1
                and info.st_size <= CONTEXT_LIMIT, 'response_loss_context')
        raw = os.read(fd, CONTEXT_LIMIT + 1)
        require(len(raw) <= CONTEXT_LIMIT, 'response_loss_context')
        return context(C.parse_json(raw))
    except Exception:
        raise Rejected('response_loss_context') from None
    finally:
        if fd is not None:
            os.close(fd)


def write_private(path, value):
    raw = json.dumps(value, separators=(',', ':')).encode()
    require(len(raw) <= CONTEXT_LIMIT, 'response_loss_context')
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
    with os.fdopen(fd, 'wb') as stream:
        stream.write(raw)
        stream.flush()
        os.fsync(stream.fileno())


def wire_for(data, deadline, factory):
    budget(deadline)
    wire = factory(data)
    wire.deadline = deadline
    wire.control_pacer = C.ControlPacer(deadline)
    return wire


def login(wire):
    # One new authorization request with the real ordinary password form.
    wire.cookies.clear()
    page, verifier, state, nonce = C.authorize(wire)
    page = C.follow(wire, C.submit(wire, page, 'kc-form-login', {
        'username': wire.data['username'], 'password': wire.data['password']}))
    return page, verifier, state, nonce


def code_form(wire, page, verifier, state):
    C.expected_status(page[0], 302)
    code = C.callback(page[1].get('Location', ''), C.CALLBACK, state,
                      wire.data['origin'] + '/sso/realms/armoney')
    return {'grant_type': 'authorization_code', 'client_id': C.CLIENT,
            'redirect_uri': C.CALLBACK, 'code': code, 'code_verifier': verifier}


def phase_receipt(operation, phase):
    require(operation in ('stage', 'confirm') and phase in ('drop', 'recover'), 'response_loss_input')
    return {'operation': operation, 'phase': phase, 'status': 'phase_verified',
            'original_request_retried': False, 'whole_migration_acceptance': False}


def drop(data, *, operation, deadline, factory=C.Wire):
    require(operation in ('stage', 'confirm'), 'response_loss_input')
    wire = wire_for(data, deadline, factory)
    try:
        before = C.outcomes(wire)
        require(before['registration_active'] and before['projection_acknowledged'], 'response_loss_drop')
        page, verifier, state, nonce = login(wire)
        code_hash = None
        if operation == 'stage':
            require(page[0] == 503 and not page[1].get('Location'), 'response_loss_drop')
            require(not re.search(rb'(access_token|refresh_token|id_token|[?&]code=|"code"\s*:|com\.armoney\.android:)', page[2]),
                    'response_loss_drop')
        else:
            form = code_form(wire, page, verifier, state)
            code_hash = digest(form['code'])
            status, _, raw = wire.request(data['origin'] + '/sso/realms/armoney/protocol/openid-connect/token', form=form)
            require(status in (400, 503), 'response_loss_drop')
            error = C.parse_json(raw)
            require(type(error) is dict and error.get('error') in
                    ('temporarily_unavailable', 'server_error', 'invalid_grant')
                    and not {'access_token', 'refresh_token', 'id_token'}.intersection(error), 'response_loss_drop')
        after = C.outcomes(wire)
        require(after['bank_session_count'] == before['bank_session_count'], 'response_loss_drop')
        saved = context({'version': 1, 'operation': operation, 'deadline': deadline,
            'before_events': before['confirmed_event_ids'], 'after_events': after['confirmed_event_ids'],
            'bank_sessions': before['bank_session_count'], 'verifier_hash': digest(verifier),
            'state_hash': digest(state), 'nonce_hash': digest(nonce), 'code_hash': code_hash})
        budget(deadline)
        return phase_receipt(operation, 'drop'), saved
    except Exception:
        raise Rejected('response_loss_drop') from None


def recover(data, private_context, *, deadline, factory=C.Wire):
    saved = context(private_context)
    require(type(deadline) is type(saved['deadline']) and deadline == saved['deadline'], 'response_loss_deadline')
    wire = wire_for(data, deadline, factory)
    try:
        before = C.outcomes(wire)
        require(before['confirmed_event_ids'] == saved['after_events']
                and before['bank_session_count'] == saved['bank_sessions']
                and before['registration_active'] and before['projection_acknowledged'], 'response_loss_recovery')
        page, verifier, state, nonce = login(wire)
        for name, value in (('verifier_hash', verifier), ('state_hash', state), ('nonce_hash', nonce)):
            require(digest(value) != saved[name], 'response_loss_recovery')
        form = code_form(wire, page, verifier, state)
        require(saved['code_hash'] is None or digest(form['code']) != saved['code_hash'], 'response_loss_recovery')
        tokens = C.token_response(wire, form, nonce)
        require(all(type(tokens.get(name)) is str for name in ('access_token', 'refresh_token', 'id_token')), 'response_loss_recovery')
        facts = C.introspect(wire, tokens['access_token'])
        C.proof(facts, True)
        for name in ('refresh_token', 'id_token'):
            require(not C.CLAIMS.intersection(C.inspect_jwt(tokens[name])), 'response_loss_recovery')
        confirmed = C.outcomes(wire)
        C.assert_confirmation_delta(before['confirmed_event_ids'], confirmed['confirmed_event_ids'], facts['armoney_login_event_id'])
        require(confirmed['bank_session_count'] == before['bank_session_count'], 'response_loss_recovery')
        C.gateway(wire, tokens['access_token'], 200)
        after = C.outcomes(wire)
        require(after['confirmed_event_ids'] == confirmed['confirmed_event_ids']
                and after['bank_session_count'] == before['bank_session_count'] + 1, 'response_loss_recovery')
        budget(deadline)
        return phase_receipt(saved['operation'], 'recover')
    except Exception:
        raise Rejected('response_loss_recovery') from None


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--input', required=True)
    parser.add_argument('--operation', required=True, choices=('stage', 'confirm'))
    parser.add_argument('--phase', required=True, choices=('drop', 'recover'))
    parser.add_argument('--deadline', required=True, type=float)
    parser.add_argument('--context', required=True)
    parser.add_argument('--result', required=True)
    args = parser.parse_args()
    result = {'operation': args.operation, 'phase': args.phase, 'status': 'not_verified',
              'check': 'response_loss_unexpected', 'whole_migration_acceptance': False}
    fd = None
    try:
        data = C.configuration(C.read_private(args.input))
        budget(args.deadline)
        fd = os.open(args.result, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
        os.write(fd, json.dumps(result).encode()); os.fsync(fd)
        if args.phase == 'drop':
            result, saved = drop(data, operation=args.operation, deadline=args.deadline)
            write_private(args.context, saved)
        else:
            saved = read_context(args.context)
            require(saved['operation'] == args.operation, 'response_loss_context')
            result = recover(data, saved, deadline=args.deadline)
    except Exception as failure:
        label = str(failure) if isinstance(failure, Rejected) else 'response_loss_unexpected'
        result = {'operation': args.operation, 'phase': args.phase, 'status': 'not_verified',
                  'check': label, 'whole_migration_acceptance': False}
    finally:
        if fd is not None:
            os.lseek(fd, 0, os.SEEK_SET); os.ftruncate(fd, 0)
            os.write(fd, json.dumps(result, separators=(',', ':')).encode()); os.fsync(fd); os.close(fd)
    return 0 if result['status'] == 'phase_verified' else 1


if __name__ == '__main__':
    raise SystemExit(main())
