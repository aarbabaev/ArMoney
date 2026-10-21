"""Fail-closed production-provider acceptance driver; never fixture authority.

Run explicitly with --input PRIVATE_JSON --result NEW_RECEIPT. unittest discovery
does not execute this driver. Controller protocol below requires separate review
and implementation; no existing fixture route is assumed to implement it.
"""
import argparse
import base64
import hashlib
from html.parser import HTMLParser
import http.cookiejar
import importlib.util
import json
import os
from pathlib import Path
import re
import secrets
import ssl
import stat
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid

CLIENT = 'armoney-android'
ACCOUNT_CLIENT = 'armoney-migration-account'
CALLBACK = 'com.armoney.android:/oauth/callback'
CLAIMS = {'armoney_login_event_id', 'armoney_login_kind', 'armoney_login_notice_eligible'}
OPERATIONS = {'capabilities', 'read_delivery', 'outcomes', 'remove_policy', 'restore_policy'}
CASES = ('registration_no_code', 'native_confirmation', 'gateway_admission', 'refresh_no_proof',
         'offline_refresh_no_proof', 'token_exchange_no_proof',
         'migration_managed_no_proof', 'migration_account_dedicated_no_proof', 'policy_removed_after_issue_denied',
         'concurrent_same_client_callbacks')
UNIMPLEMENTED = ('stage_response_loss', 'confirmation_response_loss', 'node_replacement',
                 'master_stock', 'disabled_stock',
                 'full_migration_callbacks_pre_final')
LIMIT = 131072


class Rejected(Exception):
    """Contains fixed diagnostic labels only."""


def require(condition, label='contract'):
    if not condition:
        raise Rejected(label)


def unique(items):
    result = {}
    for key, value in items:
        require(key not in result, 'duplicate_field')
        result[key] = value
    return result


def parse_json(raw):
    require(isinstance(raw, bytes) and len(raw) <= LIMIT, 'body_bound')
    try:
        return json.loads(raw.decode('utf-8'), object_pairs_hook=unique,
                          parse_constant=lambda _: (_ for _ in ()).throw(Rejected('json')))
    except (ValueError, UnicodeError):
        raise Rejected('json') from None


def https_origin(value):
    require(type(value) is str, 'origin')
    try:
        uri = urllib.parse.urlsplit(value)
        valid = (uri.scheme == 'https' and uri.hostname and not uri.username and not uri.password
                 and uri.path == '' and not uri.query and not uri.fragment
                 and value == 'https://' + uri.netloc and uri.port != 0
                 and uri.netloc == uri.netloc.lower() and '%' not in value)
    except ValueError:
        valid = False
    require(valid and all(33 <= ord(c) < 127 for c in value), 'origin')
    return value


def secret(value):
    require(type(value) is str and 16 <= len(value) <= 512
            and all(33 <= ord(c) < 127 for c in value), 'private_field')
    return value


def configuration(raw):
    data = parse_json(raw)
    required = {'version', 'isolated', 'origin', 'ca_file', 'controller_origin', 'controller_key',
                'controller_paths', 'gateway_origin', 'introspection_client', 'introspection_secret',
                'username', 'email', 'password', 'first_name', 'last_name', 'migration_callbacks', 'account_client'}
    require(type(data) is dict and set(data) == required, 'input_fields')
    require(type(data['version']) is int and data['version'] == 1 and data['isolated'] is True, 'isolation')
    require(data['account_client'] == ACCOUNT_CLIENT, 'account_client')
    for key in ('origin', 'controller_origin', 'gateway_origin'):
        https_origin(data[key])
    for key in ('controller_key', 'introspection_secret', 'password'):
        secret(data[key])
    require(len({data[k] for k in ('controller_key', 'introspection_secret', 'password')}) == 3, 'credential_alias')
    require(type(data['controller_paths']) is dict and set(data['controller_paths']) == OPERATIONS, 'controller_operations')
    for path in data['controller_paths'].values():
        require(type(path) is str and re.fullmatch(r'/[A-Za-z0-9/_-]{1,200}', path)
                and '//' not in path, 'controller_path')
    require(len(set(data['controller_paths'].values())) == len(OPERATIONS), 'controller_path')
    require(type(data['ca_file']) is str and Path(data['ca_file']).is_absolute(), 'ca_path')
    require(type(data['introspection_client']) is str and re.fullmatch(r'[A-Za-z0-9_-]{1,128}', data['introspection_client'])
            and data['introspection_client'] != CLIENT, 'introspection_client')
    require(type(data['username']) is str and re.fullmatch(r'\+9715[024568][0-9]{7}', data['username']), 'synthetic_phone')
    require(type(data['email']) is str and re.fullmatch(r'[a-z0-9._+-]+@[a-z0-9.-]+\.(test|invalid)', data['email']), 'synthetic_email')
    for key in ('first_name', 'last_name'):
        require(type(data[key]) is str and re.fullmatch(r'[A-Za-z ]{1,64}', data[key]), 'synthetic_name')
    callbacks = data['migration_callbacks']
    require(type(callbacks) is dict and set(callbacks) == {'account', 'managed'}, 'migration_callbacks')
    for name, path in (('account', '/migration/legacy/account-oidc/callback'), ('managed', '/migration/managed/callback')):
        require(callbacks[name] == data['gateway_origin'] + path, 'migration_callback')
    return data


def read_private(path):
    require(os.name == 'posix', 'private_file_platform')
    fd = os.open(path, os.O_RDONLY | os.O_NOFOLLOW)
    try:
        info = os.fstat(fd)
        require(stat.S_ISREG(info.st_mode) and info.st_uid == os.geteuid()
                and stat.S_IMODE(info.st_mode) == 0o600, 'private_file')
        return os.read(fd, LIMIT + 1)
    finally:
        os.close(fd)


def pkce():
    verifier = secrets.token_urlsafe(32)
    return verifier, base64.urlsafe_b64encode(hashlib.sha256(verifier.encode('ascii')).digest()).decode().rstrip('=')


def callback(url, redirect, state, issuer):
    require(type(url) is str and len(url) <= 8192, 'callback')
    base, separator, query = url.partition('?')
    require(separator and base == redirect and '#' not in url, 'callback_target')
    try:
        values = unique(urllib.parse.parse_qsl(query, keep_blank_values=True, strict_parsing=True))
    except ValueError:
        raise Rejected('callback') from None
    require(set(values) <= {'code', 'state', 'iss', 'session_state'} and {'code', 'state'} <= set(values), 'callback_fields')
    require(secrets.compare_digest(values['state'], state), 'callback_state')
    require(re.fullmatch(r'[A-Za-z0-9._~-]{1,2048}', values['code']) is not None, 'callback_code')
    require('iss' not in values or values['iss'] == issuer, 'callback_issuer')
    return values['code']


class Forms(HTMLParser):
    def __init__(self, html, page_url, origin):
        super().__init__(convert_charrefs=True)
        self.forms, self.current = {}, None
        self.heading_depth, self.heading_parts = 0, []
        self.page_url, self.origin = page_url, origin
        self.feed(html)
        require(self.current is None, 'unclosed_form')

    def handle_starttag(self, tag, attrs):
        if tag in {'h1', 'h2'}:
            self.heading_depth += 1
        if tag not in {'form', 'input', 'button'}:
            return
        values = unique(attrs)
        if tag == 'form':
            require(self.current is None, 'nested_form')
            name = values.get('id')
            require(name and name not in self.forms and values.get('method', '').lower() == 'post', 'form')
            action = urllib.parse.urljoin(self.page_url, values.get('action', ''))
            parsed = urllib.parse.urlsplit(action)
            require(parsed.scheme == 'https' and 'https://' + parsed.netloc == self.origin and not parsed.fragment
                    and not parsed.username and not parsed.password, 'form_origin')
            self.current = {'action': action, 'fields': {}, 'buttons': []}
            self.forms[name] = self.current
        elif self.current is not None and values.get('type', '').lower() == 'hidden':
            name = values.get('name')
            require(name and name not in self.current['fields'], 'form_fields')
            self.current['fields'][name] = values.get('value', '')
        elif tag == 'button' and self.current is not None:
            self.current['buttons'].append(values)

    def handle_endtag(self, tag):
        if tag in {'h1', 'h2'}:
            self.heading_depth = max(0, self.heading_depth - 1)
        if tag == 'form':
            require(self.current is not None, 'form')
            self.current = None

    def handle_data(self, data):
        if self.heading_depth:
            self.heading_parts.append(data)


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *_):
        return None


class ControlPacer:
    """Respect the fixture's 2/second control budget without retrying any POST."""
    def __init__(self, deadline, clock=time.monotonic, pause=time.sleep):
        self.deadline, self.clock, self.pause = deadline, clock, pause
        self.last = None

    def before_post(self):
        now = self.clock()
        require(now < self.deadline, 'deadline')
        due = now if self.last is None else self.last + .55
        if now < due:
            require(due < self.deadline, 'deadline')
            self.pause(due - now)
            now = self.clock()
            require(now >= due, 'controller_pacing')
        require(now < self.deadline, 'deadline')
        self.last = now


class Wire:
    def __init__(self, data):
        self.data = data
        self.deadline = time.monotonic() + 360
        self.control_pacer = ControlPacer(self.deadline)
        self.context = ssl.create_default_context(cafile=data['ca_file'])
        self.cookies = http.cookiejar.CookieJar()
        self.opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect(),
            urllib.request.HTTPSHandler(context=self.context), urllib.request.HTTPCookieProcessor(self.cookies))

    def request(self, url, *, form=None, document=None, headers=None):
        origin = 'https://' + urllib.parse.urlsplit(url).netloc
        require(origin in {self.data[k] for k in ('origin', 'gateway_origin', 'controller_origin')}, 'request_origin')
        remaining = self.deadline - time.monotonic()
        require(remaining > 0, 'deadline')
        require(form is None or document is None)
        supplied = dict(headers or {})
        body = None
        if form is not None:
            body = urllib.parse.urlencode(form).encode()
            supplied['Content-Type'] = 'application/x-www-form-urlencoded'
        if document is not None:
            body = json.dumps(document, separators=(',', ':')).encode()
            supplied['Content-Type'] = 'application/json'
        request = urllib.request.Request(url, data=body, headers=supplied)
        try:
            response = self.opener.open(request, timeout=min(10, remaining))
        except urllib.error.HTTPError as failure:
            response = failure
        except Exception:
            raise Rejected('transport') from None
        with response:
            raw = response.read(LIMIT + 1)
            require(len(raw) <= LIMIT and time.monotonic() < self.deadline, 'response_bound')
            return response.status, response.headers, raw

    def control(self, operation, payload):
        require(operation in OPERATIONS, 'controller_operation')
        self.control_pacer.before_post()
        status, _, raw = self.request(self.data['controller_origin'] + self.data['controller_paths'][operation],
            document=payload, headers={'X-ArMoney-Production-Acceptance-Key': self.data['controller_key']})
        require(status == 200, 'controller_status')
        result = parse_json(raw)
        require(type(result) is dict, 'controller_schema')
        return result


def expected_status(observed, expected):
    require(type(observed) is int and type(expected) is int and observed == expected, 'http_status')


def proof(value, present):
    require(type(value) is dict and value.get('active') is True, 'inactive_token')
    if not present:
        require(not CLAIMS.intersection(value), 'unexpected_proof')
    else:
        require(CLAIMS <= set(value), 'missing_proof')
        try:
            require(str(uuid.UUID(value['armoney_login_event_id'])) == value['armoney_login_event_id'], 'proof_event')
        except (ValueError, TypeError, AttributeError):
            raise Rejected('proof_event') from None
        require(value['armoney_login_kind'] == 'interactive'
                and value['armoney_login_notice_eligible'] is True, 'proof_value')


def submit(wire, page, form_id, fields):
    status, _, raw, url = page
    expected_status(status, 200)
    try:
        selected = Forms(raw.decode('utf-8'), url, wire.data['origin']).forms[form_id]
    except (UnicodeError, KeyError):
        raise Rejected('missing_form') from None
    values = dict(selected['fields'])
    require(not set(values).intersection(fields), 'form_override')
    values.update(fields)
    status, headers, raw = wire.request(selected['action'], form=values)
    return status, headers, raw, selected['action']


def follow(wire, page):
    for _ in range(8):
        status, headers, raw, url = page
        if status not in (302, 303):
            return page
        target = urllib.parse.urljoin(url, headers.get('Location', ''))
        callbacks = {CALLBACK, *wire.data['migration_callbacks'].values()}
        if target.partition('?')[0] in callbacks:
            return page
        require(target.startswith(wire.data['origin'] + '/'), 'redirect_origin')
        status, headers, raw = wire.request(target)
        page = status, headers, raw, target
    raise Rejected('redirect_bound')


def client_redirect(data, client, redirect):
    require((client == CLIENT and redirect in {CALLBACK, data['migration_callbacks']['managed']})
            or (client == ACCOUNT_CLIENT and data['account_client'] == ACCOUNT_CLIENT
                and redirect == data['migration_callbacks']['account']), 'client_redirect')


def authorize(wire, redirect=CALLBACK, registration=False, offline=False, client=CLIENT):
    client_redirect(wire.data, client, redirect)
    require(not registration or client == CLIENT, 'registration_client')
    require(not offline or client == CLIENT, 'offline_client')
    verifier, challenge = pkce()
    state, nonce = secrets.token_urlsafe(32), secrets.token_urlsafe(32)
    issuer = wire.data['origin'] + '/sso/realms/armoney'
    endpoint = 'registrations' if registration else 'auth'
    url = issuer + '/protocol/openid-connect/' + endpoint + '?' + urllib.parse.urlencode({
        'client_id': client, 'redirect_uri': redirect, 'response_type': 'code', 'scope': 'openid offline_access' if offline else 'openid',
        'state': state, 'nonce': nonce, 'code_challenge': challenge, 'code_challenge_method': 'S256',
        'prompt': 'login', 'max_age': '0'})
    status, headers, raw = wire.request(url)
    return follow(wire, (status, headers, raw, url)), verifier, state, nonce


def introspect(wire, token):
    credential = base64.b64encode((wire.data['introspection_client'] + ':' + wire.data['introspection_secret']).encode()).decode()
    status, _, raw = wire.request(wire.data['origin'] + '/sso/realms/armoney/protocol/openid-connect/token/introspect',
        form={'token': token}, headers={'Authorization': 'Basic ' + credential})
    expected_status(status, 200)
    return parse_json(raw)


def inspect_jwt(token):
    """Inspection only: signature trust comes from actual provider introspection/Auth."""
    require(type(token) is str and len(token) <= 16384, 'jwt')
    parts = token.split('.')
    require(len(parts) == 3 and all(re.fullmatch(r'[A-Za-z0-9_-]+', p) for p in parts), 'jwt')
    try:
        raw = base64.urlsafe_b64decode(parts[1] + '=' * (-len(parts[1]) % 4))
    except ValueError:
        raise Rejected('jwt') from None
    require(base64.urlsafe_b64encode(raw).decode().rstrip('=') == parts[1], 'jwt')
    value = parse_json(raw)
    require(type(value) is dict, 'jwt')
    return value


def check_nonce(tokens, nonce, issuer, client=CLIENT):
    identity = inspect_jwt(tokens.get('id_token'))
    require(type(identity.get('nonce')) is str and secrets.compare_digest(identity['nonce'], nonce), 'id_nonce')
    require(identity.get('iss') == issuer and (identity.get('aud') == client or
        type(identity.get('aud')) is list and client in identity['aud']), 'id_binding')
    require('azp' not in identity or identity['azp'] == client, 'id_binding')
    require(not CLAIMS.intersection(identity), 'id_proof')


def token_response(wire, form, nonce=None):
    issuer = wire.data['origin'] + '/sso/realms/armoney'
    status, _, raw = wire.request(issuer + '/protocol/openid-connect/token', form=form)
    expected_status(status, 200)
    tokens = parse_json(raw)
    require(type(tokens) is dict and type(tokens.get('access_token')) is str
            and tokens.get('token_type', '').lower() == 'bearer', 'token_schema')
    if nonce is not None:
        check_nonce(tokens, nonce, issuer, form['client_id'])
    return tokens


def fresh_code(wire, redirect=CALLBACK, offline=False, client=CLIENT):
    wire.cookies.clear()
    page, verifier, state, nonce = authorize(wire, redirect, offline=offline, client=client)
    page = follow(wire, submit(wire, page, 'kc-form-login', {
        'username': wire.data['username'], 'password': wire.data['password']}))
    expected_status(page[0], 302)
    code = callback(page[1].get('Location', ''), redirect, state,
                    wire.data['origin'] + '/sso/realms/armoney')
    return {'grant_type': 'authorization_code', 'client_id': client, 'redirect_uri': redirect,
            'code': code, 'code_verifier': verifier}, nonce


def session_count(wire):
    # Controller contract counts ALL persisted session rows for this fixed owner,
    # including expired/revoked rows; filtering active sessions would hide inserts.
    result = outcomes(wire)
    count = result.get('bank_session_count')
    require(type(count) is int and count >= 0, 'session_count')
    return count


def confirmation_ids(value):
    require(type(value) is list and len(value) <= 16 and all(type(item) is str for item in value), 'confirmation_inventory')
    try:
        require(all(str(uuid.UUID(item)) == item for item in value), 'confirmation_inventory')
    except (ValueError, TypeError, AttributeError):
        raise Rejected('confirmation_inventory') from None
    require(value == sorted(set(value)), 'confirmation_inventory')
    return set(value)


def outcomes(wire):
    value = wire.control('outcomes', {'username': wire.data['username']})
    require(set(value) == {'registration_active', 'projection_acknowledged', 'bank_session_count', 'confirmed_event_ids'}, 'outcomes_schema')
    require(type(value['registration_active']) is bool and type(value['projection_acknowledged']) is bool
            and type(value['bank_session_count']) is int and value['bank_session_count'] >= 0, 'outcomes_schema')
    confirmation_ids(value['confirmed_event_ids'])
    return value


def assert_confirmation_delta(before, after, event=None):
    original, current = confirmation_ids(before), confirmation_ids(after)
    expected = set() if event is None else {event}
    require(event is None or confirmation_ids([event]) == expected, 'confirmation_event')
    require(original <= current and current - original == expected
            and (event is None or event not in original), 'confirmation_delta')


def gateway(wire, token, expected):
    before = session_count(wire)
    status, _, raw = wire.request(wire.data['gateway_origin'] + '/v1/auth/sso', document={'access_token': token})
    expected_status(status, expected)
    if expected == 200:
        session = parse_json(raw)
        require(type(session) is dict and type(session.get('access_token')) is str
                and re.fullmatch(r'[A-Za-z0-9_-]{43}', session['access_token'])
                and session.get('token_type') == 'Bearer' and type(session.get('expires_in')) is int
                and 0 < session['expires_in'] <= 1800 and type(session.get('expires_at')) is str, 'bank_session')
    require(session_count(wire) == before + (1 if expected == 200 else 0), 'session_outcome')


def no_proof(wire, tokens):
    proof(introspect(wire, tokens['access_token']), False)
    require(not CLAIMS.intersection(inspect_jwt(tokens['access_token'])), 'access_proof')
    for name in ('refresh_token', 'id_token'):
        if name in tokens:
            require(not CLAIMS.intersection(inspect_jwt(tokens[name])), 'grant_proof')


def policy_removed(wire, completed):
    original = outcomes(wire)['confirmed_event_ids']
    form, nonce = fresh_code(wire)  # Code exists BEFORE the policy is disabled.
    try:
        require(wire.control('remove_policy', {}) == {'state': 'disabled'}, 'policy_remove')
        tokens = token_response(wire, form, nonce)
        no_proof(wire, tokens)
        gateway(wire, tokens['access_token'], 401)
        assert_confirmation_delta(original, outcomes(wire)['confirmed_event_ids'])
    finally:
        # Also attempt restoration after an unknown removal result; controller must
        # enforce original-document ownership/drift checks, never overwrite drift.
        require(wire.control('restore_policy', {}) == {'state': 'restored'}, 'policy_restore')
    completed.append('policy_removed_after_issue_denied')


PENDING_INFO = b'Contact verified. Account activation is pending; check status to continue.'
TERMINAL_INFO = b'Registration verified. Sign in to continue.'


def require_phone_transition(wire, page):
    """Email completion must advance to the actual server-owned phone action."""
    try:
        require(page[0] == 200, 'phone_transition')
        parsed = Forms(page[2].decode('utf-8'), page[3], wire.data['origin'])
        heading = ' '.join(' '.join(parsed.heading_parts).split())
        require('Verify your phone' in heading and 'Verify your email' not in heading, 'phone_transition')
        action = parsed.forms['armoney-verification']['action']
        fields = urllib.parse.parse_qsl(urllib.parse.urlsplit(action).query,
                                       keep_blank_values=True, strict_parsing=True)
        execution = [value for key, value in fields if key == 'execution']
        require(execution == ['armoney-verify-phone-code'], 'phone_transition')
    except (Rejected, ValueError, KeyError, UnicodeError, AttributeError):
        raise Rejected('phone_transition') from None


def await_registration_info(wire, page, clock=time.monotonic, pause=time.sleep):
    end = clock() + 15
    while page[0] == 200 and PENDING_INFO in page[2]:
        require(clock() < end, 'projection_pending')
        require_phone_transition(wire, page)
        try:
            selected = Forms(page[2].decode('utf-8'), page[3], wire.data['origin']).forms['armoney-verification']
            buttons = [button for button in selected['buttons'] if button.get('name') == 'operation'
                       and button.get('value') == 'status']
            require(len(buttons) == 1 and buttons[0].get('type', 'submit').lower() == 'submit'
                    and 'formnovalidate' in buttons[0]
                    and not {'disabled', 'formaction', 'formmethod', 'form'}.intersection(buttons[0]), 'pending_status_form')
        except (Rejected, ValueError, KeyError, UnicodeError, AttributeError):
            raise Rejected('pending_status_form') from None
        pause(min(.2, max(0, end - clock())))
        require(clock() < end, 'projection_pending')
        # Submit the actual rendered status-only action with current hidden
        # bindings; never resend an OTP or request another challenge.
        page = follow(wire, submit(wire, page, 'armoney-verification', {'operation': 'status'}))
        require(clock() <= end, 'projection_pending')
    expected_status(page[0], 200)
    require(TERMINAL_INFO in page[2] and not page[1].get('Location')
            and page[3].partition('?')[0] != CALLBACK, 'registration_info')
    return page


def execute(wire, completed=None):
    completed = [] if completed is None else completed
    # Require reviewed controller support before creating any account.
    capabilities = wire.control('capabilities', {})
    require(capabilities == {'version': 2, 'production_provider_only': True,
        'operations': sorted(OPERATIONS)}, 'controller_capabilities')
    data = wire.data
    page, _, _, _ = authorize(wire, registration=True)
    page = follow(wire, submit(wire, page, 'kc-register-form', {
        'username': data['username'], 'email': data['email'], 'firstName': data['first_name'],
        'lastName': data['last_name'], 'password': data['password'], 'password-confirm': data['password']}))
    for purpose in ('REGISTRATION_EMAIL', 'REGISTRATION_PHONE'):
        if purpose == 'REGISTRATION_PHONE':
            # Do not send on a re-entered email form or misdiagnose absent PHONE
            # delivery when Keycloak has not advanced its required action.
            require_phone_transition(wire, page)
        page = follow(wire, submit(wire, page, 'armoney-verification', {'operation': 'send'}))
        delivery = wire.control('read_delivery', {'username': data['username'], 'purpose': purpose})
        require(set(delivery) == {'code', 'challenge_id'} and type(delivery['code']) is str
                and re.fullmatch(r'[0-9]{6}', delivery['code']), 'delivery_schema')
        require(type(delivery['challenge_id']) is str and str(uuid.UUID(delivery['challenge_id'])) == delivery['challenge_id'], 'delivery_schema')
        page = follow(wire, submit(wire, page, 'armoney-verification', {'operation': 'verify', 'code': delivery['code']}))
    page = await_registration_info(wire, page)
    authority = outcomes(wire)
    require(authority.get('registration_active') is True and authority.get('projection_acknowledged') is True
            and authority.get('bank_session_count') == 0, 'registration_outcome')
    require(authority['confirmed_event_ids'] == [], 'registration_confirmation')
    completed.append('registration_no_code')
    # BANK_NATIVE positive requires a current persistent ONLINE session. Finish
    # its admission before creating the separate offline-refresh test session.
    form, nonce = fresh_code(wire)
    tokens = token_response(wire, form, nonce)
    require(type(tokens.get('refresh_token')) is str and
            not CLAIMS.intersection(inspect_jwt(tokens['refresh_token'])), 'initial_refresh_proof')
    access = introspect(wire, tokens['access_token'])
    proof(access, True)
    outcome = outcomes(wire)
    assert_confirmation_delta(authority['confirmed_event_ids'], outcome['confirmed_event_ids'], access['armoney_login_event_id'])
    completed.append('native_confirmation')
    gateway(wire, tokens['access_token'], 200)
    completed.append('gateway_admission')
    for case, initial in (('refresh_no_proof', tokens), ('offline_refresh_no_proof', None)):
        if initial is None:
            previous = outcomes(wire)['confirmed_event_ids']
            bank_sessions = session_count(wire)
            offline_form, offline_nonce = fresh_code(wire, offline=True)
            initial = token_response(wire, offline_form, offline_nonce)
            offline_access = introspect(wire, initial['access_token'])
            proof(offline_access, True)
            assert_confirmation_delta(previous, outcomes(wire)['confirmed_event_ids'], offline_access['armoney_login_event_id'])
            require(session_count(wire) == bank_sessions, 'offline_session_outcome')
        refresh = initial.get('refresh_token')
        require(type(refresh) is str, 'refresh_missing')
        require(not CLAIMS.intersection(inspect_jwt(refresh)), 'initial_refresh_proof')
        if case == 'offline_refresh_no_proof':
            require(inspect_jwt(refresh).get('typ') == 'Offline', 'offline_token')
        else:
            require(inspect_jwt(refresh).get('typ') == 'Refresh', 'ordinary_refresh_token')
        before_refresh = outcomes(wire)['confirmed_event_ids']
        refreshed = token_response(wire, {'grant_type': 'refresh_token', 'client_id': CLIENT, 'refresh_token': refresh})
        no_proof(wire, refreshed)
        gateway(wire, refreshed['access_token'], 401)
        assert_confirmation_delta(before_refresh, outcomes(wire)['confirmed_event_ids'])
        completed.append(case)
    before_exchange = outcomes(wire)['confirmed_event_ids']
    status, _, raw = wire.request(data['origin'] + '/sso/realms/armoney/protocol/openid-connect/token', form={
        'grant_type': 'urn:ietf:params:oauth:grant-type:token-exchange', 'client_id': CLIENT,
        'subject_token': tokens['access_token'], 'subject_token_type': 'urn:ietf:params:oauth:token-type:access_token'})
    if status == 200:
        exchanged = parse_json(raw)
        require(type(exchanged) is dict and type(exchanged.get('access_token')) is str, 'token_schema')
        no_proof(wire, exchanged)
        gateway(wire, exchanged['access_token'], 401)
    else:
        require(status in (400, 403), 'exchange_status')
        failure = parse_json(raw)
        require(type(failure) is dict and failure.get('error') in
                {'unsupported_grant_type', 'unauthorized_client', 'invalid_request', 'access_denied'}, 'exchange_error')
    assert_confirmation_delta(before_exchange, outcomes(wire)['confirmed_event_ids'])
    completed.append('token_exchange_no_proof')
    # Only BANK_NATIVE + MIGRATION_MANAGED share the Android client manifest.
    for purpose in ('managed',):
        previous = outcomes(wire)['confirmed_event_ids']
        migration, migration_nonce = fresh_code(wire, data['migration_callbacks'][purpose])
        migration_tokens = token_response(wire, migration, migration_nonce)
        no_proof(wire, migration_tokens)
        gateway(wire, migration_tokens['access_token'], 401)
        assert_confirmation_delta(previous, outcomes(wire)['confirmed_event_ids'])
        completed.append('migration_' + purpose + '_no_proof')
    # Dedicated account-only client is excluded from every banking-token allowlist.
    # Redeeming its code tests purpose isolation, not the full migration callback.
    before_account = outcomes(wire)
    account, account_nonce = fresh_code(wire, data['migration_callbacks']['account'], client=ACCOUNT_CLIENT)
    account_tokens = token_response(wire, account, account_nonce)
    no_proof(wire, account_tokens)
    gateway(wire, account_tokens['access_token'], 401)
    after_account = outcomes(wire)
    assert_confirmation_delta(before_account['confirmed_event_ids'], after_account['confirmed_event_ids'])
    completed.append('migration_account_dedicated_no_proof')
    final_cases(wire, completed)
    require(tuple(completed) == CASES, 'case_inventory')
    return receipt('scoped_passed', 'implemented_cases_passed', completed)


def load_concurrency():
    # Fixed sibling only. Importing defines helpers; it never calls either CLI.
    spec = importlib.util.spec_from_file_location('production_provider_concurrency_case',
        Path(__file__).with_name('test_production_provider_concurrency.py'))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def final_cases(wire, completed):
    # policy_removed returns only after its finally block confirms restoration.
    policy_removed(wire, completed)
    require(tuple(completed) == CASES[:-1], 'case_inventory')
    try:
        result = load_concurrency().execute(wire.data, deadline=wire.deadline)
        expected = {'case': 'concurrent_same_client_callbacks', 'status': 'scoped_passed',
            'positive_confirmations': 2, 'positive_bank_sessions': 2,
            'negative_no_side_effects': True, 'whole_migration_acceptance': False}
        require(type(result) is dict and set(result) == set(expected)
                and all(type(result[key]) is type(value) and result[key] == value
                        for key, value in expected.items()), 'concurrent_case')
    except Exception:
        raise Rejected('concurrent_case') from None
    completed.append('concurrent_same_client_callbacks')


def receipt(status, check, completed=None):
    require(status in {'not_verified', 'scoped_passed'}, 'receipt')
    require(type(check) is str and re.fullmatch(r'[a-z_]{1,80}', check), 'receipt')
    value = {'status': status, 'check': check}
    if completed is not None:
        require(type(completed) is list and len(set(completed)) == len(completed)
                and all(case in CASES for case in completed), 'receipt_cases')
        require(status != 'scoped_passed' or tuple(completed) == CASES, 'receipt_incomplete')
        value.update(passed_cases=list(completed), unimplemented_cases=list(UNIMPLEMENTED))
    else:
        require(status != 'scoped_passed', 'receipt_incomplete')
    return value


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--input', required=True)
    parser.add_argument('--result', required=True)
    args = parser.parse_args()
    result = receipt('not_verified', 'input')
    fd, completed = None, []
    try:
        data = configuration(read_private(args.input))
        fd = os.open(args.result, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
        os.write(fd, json.dumps(receipt('not_verified', 'interrupted')).encode())
        os.fsync(fd)
        result = execute(Wire(data), completed)
    except Rejected as failure:
        label = str(failure)
        result = receipt('not_verified', label if re.fullmatch(r'[a-z_]{1,80}', label) else 'contract', completed)
    except Exception:
        result = receipt('not_verified', 'unexpected_failure', completed)
    finally:
        if fd is not None:
            os.lseek(fd, 0, os.SEEK_SET)
            os.ftruncate(fd, 0)
            os.write(fd, json.dumps(result, separators=(',', ':')).encode())
            os.fsync(fd)
            os.close(fd)
    return 0 if result['status'] == 'scoped_passed' else 1


if __name__ == '__main__':
    raise SystemExit(main())
