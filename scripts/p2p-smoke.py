"""Disposable CI only: actual gateway P2P, recovery, notifications and balanced funding."""
import concurrent.futures
import json
import os
import subprocess
import time
import urllib.error
import urllib.request
import uuid

if os.environ.get('CI') != 'true':
    raise SystemExit('P2P fixtures and fault injection require disposable CI; never run on live data.')

BASE = 'http://localhost:8080'


def call(method, path, token=None, body=None, key=None, extra=None):
    headers = {'Content-Type': 'application/json'}
    if token:
        headers['Authorization'] = 'Bearer ' + token
    if key:
        headers['Idempotency-Key'] = key
    headers.update(extra or {})
    req = urllib.request.Request(BASE + path, method=method, headers=headers,
        data=None if body is None else json.dumps(body).encode())
    try:
        response = urllib.request.urlopen(req, timeout=30)
    except urllib.error.HTTPError as error:
        response = error
    with response:
        raw = response.read()
        return response.status, json.loads(raw) if raw else None


def expect(code, result):
    assert result[0] == code, f'Expected HTTP {code}, got {result[0]}'
    return result[1]


def compose(*args, input=None):
    return subprocess.run(['docker', 'compose', *args], input=input, text=True,
        capture_output=True, check=True, timeout=60).stdout


def sql(service, text):
    return compose('exec', '-T', service, 'psql', '-U', 'bank', '-d', 'bank', '-At',
        '-v', 'ON_ERROR_STOP=1', input=text).strip()


def until(function, predicate, timeout=90):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        value = function()
        if predicate(value):
            return value
        time.sleep(1)
    raise AssertionError('Bounded acceptance wait timed out')


def make_user(label):
    creds = {'email': f'p2p-{uuid.uuid4()}@example.test', 'password': 'p2p-only-long-password'}
    expect(202, call('POST', '/v1/auth/register', body=creds))
    token = expect(200, call('POST', '/v1/auth/login', body=creds))['access_token']
    owner = expect(200, call('GET', '/v1/auth/me', token))['id']
    expect(200, call('PUT', '/v1/users/me', token, {'display_name': label}))
    status, wallet = call('POST', '/v1/wallets', token, {'currency': 'EUR'})
    assert status in (200, 202)
    wallet = until(lambda: expect(200, call('GET', '/v1/wallets', token))['wallets'][0],
        lambda w: w['provisioning_status'] == 'READY')
    return token, owner, wallet


alice, alice_id, a = make_user('P2P Alice')
bob, bob_id, b = make_user('P2P Bob')
outsider, outsider_id, _ = make_user('P2P Other')
phone = '+1555' + str(uuid.uuid4().int % 10**7).zfill(7)
pending = expect(200, call('PUT', '/v1/users/me/phone', bob, {'phone_number': phone}))
assert pending['phone_verified'] is False
expect(404, call('POST', '/v1/recipients/resolve', alice, {'phone_number': phone}))
# Only the local operator CLI may attest ownership. This is synthetic CI, never real phone ownership.
compose('exec', '-T', 'user-service', 'java', '-cp', '/opt/service/lib/*',
    'com.arman.bank.userservice.VerifyPhoneMain', bob_id, phone, 'ci-operator', 'synthetic-case', '--confirm-out-of-band')
recipient = expect(200, call('POST', '/v1/recipients/resolve', alice, {'phone_number': phone}))
assert recipient['identity_id'] == bob_id and recipient['display_name'] == 'P2P Bob'
assert 'email' not in recipient
expect(401, call('POST', '/v1/recipients/resolve', body={'phone_number': phone}))
expect(404, call('GET', '/v1/internal/wallets/' + a['id'], alice))
expect(404, call('GET', '/v1/wallets/' + a['id'] + '/balance', bob))

# Funding has an explicit clearing counterpart and uses ledger's immutable transfer trigger.
reserve, reserve_wallet, funding = (str(uuid.uuid4()) for _ in range(3))
account_a, account_b = (str(uuid.UUID(w['ledger_account_id'])) for w in (a, b))
sql('ledger-db', f"""BEGIN;
INSERT INTO accounts(id,wallet_id,currency,account_kind) VALUES ('{reserve}','{reserve_wallet}','EUR','CLEARING');
INSERT INTO transfers(payment_id,debit_account_id,credit_account_id,currency,amount_minor)
VALUES ('{funding}','{reserve}','{account_a}','EUR',1000); COMMIT;""")


def balance(token, wallet):
    return expect(200, call('GET', '/v1/wallets/' + wallet['id'] + '/balance', token))['balance_minor']


def terminal(payment_id):
    def probe():
        result = call('GET', '/v1/payments/' + payment_id, alice)
        if result[0] == 503:  # Expected briefly while the deliberately restarted process boots.
            return None
        return expect(200, result)
    return until(probe, lambda p: p is not None and p['status'] != 'PENDING')


command = {'source_wallet_id': a['id'], 'recipient_id': bob_id,
    'recipient_phone': phone, 'currency': 'EUR', 'amount_minor': 250}
idem = str(uuid.uuid4())
with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:
    results = list(pool.map(lambda _: call('POST', '/v1/payments', alice, command, idem), range(4)))
assert all(code in (200, 202) for code, _ in results)
ids = {p['id'] for _, p in results}
assert len(ids) == 1
first_id = ids.pop()
assert terminal(first_id)['status'] == 'COMPLETED'
assert balance(alice, a) == 750 and balance(bob, b) == 250
expect(409, call('POST', '/v1/payments', alice, dict(command, amount_minor=251), idem))
expect(404, call('GET', '/v1/payments/' + first_id, outsider))
assert expect(200, call('GET', '/v1/payments/' + first_id, bob))['status'] == 'COMPLETED'
assert call('POST', '/v1/payments', outsider, command, str(uuid.uuid4()),
    {'X-Identity-Id': alice_id, 'X-Service-Key': os.environ['INTERNAL_AUTH_KEY']})[0] in (404, 409)
expect(400, call('POST', '/v1/payments', alice, dict(command, amount_minor=1.5), str(uuid.uuid4())))

# A ledger outage must persist PENDING; restart payment while work is outstanding.
compose('stop', 'ledger-service')
try:
    code, pending_payment = call('POST', '/v1/payments', alice, dict(command, amount_minor=50), str(uuid.uuid4()))
    assert code == 202 and pending_payment['status'] == 'PENDING'
    compose('restart', 'payment-service')
finally:
    compose('start', 'ledger-service')
assert terminal(pending_payment['id'])['status'] == 'COMPLETED'
assert balance(alice, a) == 700 and balance(bob, b) == 300

# Simulate ledger commit followed by a failed local completion transaction and process restart.
sql('payment-db', """CREATE FUNCTION ci_block_completion() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN IF NEW.status <> 'PENDING' THEN RAISE EXCEPTION 'ci-local-ack-failure'; END IF; RETURN NEW; END; $$;
CREATE TRIGGER ci_block_completion BEFORE UPDATE ON payments FOR EACH ROW EXECUTE FUNCTION ci_block_completion();""")
try:
    code, uncertain = call('POST', '/v1/payments', alice, dict(command, amount_minor=50), str(uuid.uuid4()))
    assert code == 202
    pid = str(uuid.UUID(uncertain['id']))
    until(lambda: sql('ledger-db', f"SELECT count(*) FROM transfers WHERE payment_id='{pid}';"), lambda n: n == '1')
    assert expect(200, call('GET', '/v1/payments/' + pid, alice))['status'] == 'PENDING'
    compose('restart', 'payment-service')
finally:
    sql('payment-db', 'DROP TRIGGER ci_block_completion ON payments; DROP FUNCTION ci_block_completion();')
assert terminal(pid)['status'] == 'COMPLETED'
assert balance(alice, a) == 650 and balance(bob, b) == 350
assert sql('ledger-db', f"SELECT count(*) FROM transfers WHERE payment_id='{pid}';") == '1'

# Distinct concurrent commands cannot overdraw. Exactly one of two 600-minor-unit requests can post.
with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
    results = list(pool.map(lambda _: call('POST', '/v1/payments', alice,
        dict(command, amount_minor=600), str(uuid.uuid4())), range(2)))
assert all(code in (200, 202) for code, _ in results)
outcomes = [terminal(p['id']) for _, p in results]
assert sorted(p['status'] for p in outcomes) == ['COMPLETED', 'REJECTED']
assert next(p for p in outcomes if p['status'] == 'REJECTED')['rejection_reason'] == 'INSUFFICIENT_FUNDS'
assert balance(alice, a) == 50 and balance(bob, b) == 950
notifications = expect(200, call('GET', '/v1/notifications', bob))['notifications']
assert len(notifications) == 4 and all(n['type'] == 'PAYMENT_RECEIVED' for n in notifications)
assert len({(n['payment_id'], n['type']) for n in notifications}) == 4
sender_notifications = expect(200, call('GET', '/v1/notifications', alice))['notifications']
assert len(sender_notifications) == 5
nid = notifications[0]['id']
expect(404, call('POST', '/v1/notifications/' + nid + '/read', outsider))
read = expect(200, call('POST', '/v1/notifications/' + nid + '/read', bob))
assert read['read_at'] and expect(200, call('POST', '/v1/notifications/' + nid + '/read', bob))['read_at'] == read['read_at']
assert expect(200, call('GET', '/v1/notifications', outsider))['notifications'] == []
assert len(expect(200, call('GET', '/v1/payments', bob))['payments']) == 4
# Every projected balance agrees with the journal; all currencies remain globally balanced.
assert sql('ledger-db', "SELECT count(*) FROM accounts a WHERE balance_minor <> COALESCE((SELECT sum(amount_minor) FROM postings p WHERE p.account_id=a.id),0);") == '0'
assert sql('ledger-db', 'SELECT count(*) FROM (SELECT currency FROM postings GROUP BY currency HAVING sum(amount_minor) <> 0) x;') == '0'
print('P2P phone resolution, ownership, duplicate/concurrent transfers, recovery, notifications and reconciliation passed')
