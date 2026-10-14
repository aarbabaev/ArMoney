"""Bounded black-box checks. Primary must create a disposable-stack marker.

No Docker, SQL, funding, provider credentials, or financial mutation is used.
Actual mode submits four turns, each bounded by the declared deployed turn cap.
This bounds turn spend, not separately authorized corpus ingestion spend.
"""
import argparse
import datetime
import json
from pathlib import Path
import secrets
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid


def require(value, message):
    if not value:
        raise AssertionError(message)


def isolated(base, marker):
    url = urllib.parse.urlsplit(base)
    require(url.scheme in ('http', 'https') and url.hostname and not url.username
            and not url.password and url.path in ('', '/') and not url.query
            and not url.fragment, 'Expected explicit HTTP gateway origin')
    require(not (url.hostname.lower() in ('localhost', '127.0.0.1', '::1')
                 and (url.port or 80) == 8080), 'Persistent gateway port is forbidden')
    origin = base.rstrip('/')
    data = json.loads(Path(marker).read_text(encoding='utf-8'))
    require(data.get('disposable') is True and data.get('gateway_origin') == origin
            and isinstance(data.get('compose_project'), str)
            and data['compose_project'].startswith('assistant-qa-'),
            'Primary-created marker must bind an isolated assistant-qa project to origin')
    return origin


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


class Client:
    def __init__(self, origin):
        self.origin = origin
        self.opener = urllib.request.build_opener(NoRedirect())
        self.requests = 0

    def call(self, method, path, token=None, body=None, extra=None, raw=None):
        self.requests += 1
        require(self.requests <= 150, 'HTTP request budget exhausted')
        headers = {'Content-Type': 'application/json'}
        if token:
            headers['Authorization'] = 'Bearer ' + token
        headers.update(extra or {})
        payload = raw if raw is not None else (None if body is None else json.dumps(body).encode())
        request = urllib.request.Request(self.origin + path, data=payload, headers=headers, method=method)
        try:
            response = self.opener.open(request, timeout=40)
        except urllib.error.HTTPError as error:
            response = error
        with response:
            raw_body = response.read(131073)
            require(len(raw_body) <= 131072, 'Oversized API response')
            if path.startswith('/v1/assistant/') or (method == 'GET' and
                    (path == '/v1/internal/assistant/wallets' or
                     path.startswith('/v1/internal/assistant/wallets/') or
                     path.startswith('/v1/internal/assistant/payments/page'))):
                require(response.headers.get('Cache-Control') == 'no-store', 'Missing no-store')
                require(len(raw_body) <= 32768, 'Oversized assistant response')
            return response.status, json.loads(raw_body) if raw_body else None


def expect(code, result):
    require(result[0] == code, f'Expected HTTP {code}; observed HTTP {result[0]}')
    return result[1]


def instant(value):
    result = datetime.datetime.fromisoformat(value.replace('Z', '+00:00'))
    require(result.tzinfo is not None, 'Missing instant offset')
    return result


def answer_shape(answer):
    require(set(answer) == {'reply', 'outcome', 'citations', 'facts', 'provenance'}, 'Unexpected answer shape')
    require(isinstance(answer['reply'], str) and 0 < len(answer['reply']) <= 8000, 'Invalid reply')
    require(answer['outcome'] in ('answered', 'empty_history', 'insufficient_evidence', 'conflicting_evidence'), 'Invalid outcome')
    require(set(answer['facts']) == {'wallets', 'last_transaction'}, 'Unexpected facts shape')
    require(len(answer['citations']) <= 6 and len(answer['provenance']) <= 3, 'Collection bound exceeded')
    sources = set()
    for entry in answer['provenance']:
        require(set(entry) == {'source', 'fetched_at', 'scope'}, 'Invalid provenance')
        require(entry['source'] in ('wallet-service', 'payment-service', 'policy-index') and entry['source'] not in sources, 'Invalid source')
        sources.add(entry['source'])
        instant(entry['fetched_at'])
        require(isinstance(entry['scope'], str) and 0 < len(entry['scope']) <= 128, 'Invalid scope')
    for citation in answer['citations']:
        require(set(citation) == {'document_id', 'version', 'section', 'excerpt'}, 'Invalid citation')
        for field, maximum in [('document_id', 128), ('version', 64), ('section', 256), ('excerpt', 1000)]:
            require(isinstance(citation[field], str) and 0 < len(citation[field]) <= maximum, 'Invalid citation bound')
    require(not answer['citations'] or 'policy-index' in sources, 'Citation lacks provenance')
    return sources


def user(client, index):
    creds = {'email': f'assistant-qa-{uuid.uuid4()}@example.test', 'password': secrets.token_urlsafe(24)}
    phone = '+97158' + str(secrets.randbelow(10000000)).zfill(7)
    expect(202, client.call('POST', '/v1/auth/register', body=dict(creds, phone_number=phone)))
    token = expect(200, client.call('POST', '/v1/auth/login', body=creds))['access_token']
    owner = expect(200, client.call('GET', '/v1/auth/me', token))['id']
    expect(200, client.call('PUT', '/v1/users/me', token, {'display_name': f'Synthetic QA {index}'}))
    result = client.call('POST', '/v1/wallets', token, {'currency': 'AED'})
    require(result[0] in (200, 202), 'Wallet creation rejected')
    for _ in range(30):
        wallets = expect(200, client.call('GET', '/v1/wallets', token))['wallets']
        if len(wallets) == 1 and wallets[0]['provisioning_status'] == 'READY':
            return token, owner, wallets[0]['id']
        time.sleep(1)
    raise AssertionError('Wallet provisioning did not become READY in 30 seconds')


def run(client, actual):
    a, owner_a, wallet_a = user(client, 1)
    b, owner_b, wallet_b = user(client, 2)
    message = {'message': 'What is my current balance?'}
    spoof = {'X-Identity-Id': owner_b, 'X-Service-Key': 'forged', 'X-Assistant-Delegation': 'A' * 43}
    for token in (None, 'A' * 43):
        expect(401, client.call('POST', '/v1/assistant/messages', token, message, spoof))
    for raw in (b'{"message":"balance","owner":"forged"}', b'{"message":"balance","message":"policy"}'):
        expect(400, client.call('POST', '/v1/assistant/messages', a, raw=raw))
    expect(404, client.call('GET', '/v1/wallets/' + wallet_b + '/balance', a, extra=spoof))
    for headers in ({}, {'X-Assistant-Gateway-Key': 'forged'}, {'X-Assistant-Delegation': 'A' * 43}, dict(spoof, **{'X-Assistant-Gateway-Key': 'forged'})):
        expect(401, client.call('GET', '/v1/internal/assistant/wallets', extra=headers))
    expect(401, client.call('GET', '/v1/internal/assistant/wallets', a))
    # Unmapped private mutation route cannot authorize a financial action.
    require(client.call('POST', '/v1/internal/assistant/payments', body={}, extra=spoof)[0] in (404, 405), 'Private mutation exposed')
    if not actual:
        require(expect(503, client.call('POST', '/v1/assistant/messages', a, message)) == {'error': 'service_unavailable'}, 'Outage error mismatch')
    else:
        for token, wallet, headers in ((a, wallet_a, spoof), (b, wallet_b, {})):
            observed = expect(200, client.call('GET', '/v1/wallets/' + wallet + '/balance', token))
            answer = expect(200, client.call('POST', '/v1/assistant/messages', token, message, headers))
            require('wallet-service' in answer_shape(answer), 'Missing wallet source')
            require(answer['outcome'] == 'answered' and answer['facts']['wallets'] == [observed], 'Facts differ from actual owner-scoped ledger balance')
            require(type(observed['balance_minor']) is int and observed['balance_minor'] == 0, 'Fresh unfunded fixture balance is not integer zero')
        history = expect(200, client.call('GET', '/v1/payments/page?limit=50', a))
        require(history['payments'] == [], 'Fresh fixture unexpectedly has payment history')
        answer = expect(200, client.call('POST', '/v1/assistant/messages', a, {'message': 'What is my last transaction?'}))
        require('payment-service' in answer_shape(answer) and answer['outcome'] == 'empty_history' and answer['facts']['last_transaction'] is None, 'Invented transaction or missing history source')
        answer = expect(200, client.call('POST', '/v1/assistant/messages', a, {'message': 'What currency does the ArMoney demonstration policy support?'}))
        answer_shape(answer)
        require(answer['outcome'] == 'answered' and answer['citations'], 'Demo policy was not grounded')
        corpus = Path(__file__).resolve().parents[1] / 'ai-assistant-service' / 'policies'
        manifest = json.loads((corpus / 'demo.json').read_text(encoding='utf-8'))
        text = (corpus / 'demo.md').read_text(encoding='utf-8')
        for citation in answer['citations']:
            require(citation['document_id'] == manifest['DocumentId'] and citation['version'] == manifest['Version'] and citation['excerpt'] in text, 'Citation differs from ingested demonstration evidence')
        require('AED' in answer['reply'] and 'demonstration' in answer['reply'].lower(), 'Policy reply lacks expected demonstration currency scope')
    expect(204, client.call('POST', '/v1/auth/logout', a))
    expect(401, client.call('POST', '/v1/assistant/messages', a, message, spoof))
    expect(204, client.call('POST', '/v1/auth/logout', b))
    print(f'PASS: {"actual-provider bounded factual/citation checks" if actual else "provider-outage negative checks (no positive model evaluation)"}; HTTP requests={client.requests}')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--base', required=True)
    parser.add_argument('--disposable-marker', required=True, help='Primary-created JSON attestation, never CI=true')
    parser.add_argument('--actual-provider', action='store_true')
    parser.add_argument('--deployed-turn-cost-cap-usd', type=float, default=0.02, help='Must match deployed server cap; no key required')
    args = parser.parse_args()
    require(0 < args.deployed_turn_cost_cap_usd <= 0.20, 'Four turns must stay below $1')
    origin = isolated(args.base, args.disposable_marker)
    print(f'Declared assistant turns={4 if args.actual_provider else 1}; actual-mode upper turn spend=${4 * args.deployed_turn_cost_cap_usd:.2f}; ingestion excluded')
    run(Client(origin), args.actual_provider)


if __name__ == '__main__':
    try:
        main()
    except Exception as error:
        # Never emit upstream bodies, URLs, credentials, or exception diagnostics.
        print('FAIL: ' + (str(error) if isinstance(error, AssertionError) else type(error).__name__))
        raise SystemExit(1)
