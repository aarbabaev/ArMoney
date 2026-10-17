"""Private, read-only local metrics collector. No external notification provider."""
import argparse
import datetime as dt
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

SERVICES = frozenset(('auth-service', 'user-service', 'wallet-service', 'payment-service', 'ledger-service', 'app-gateway'))
METRICS = frozenset(('armoney_ready', 'armoney_recovery_backlog', 'armoney_replica_lag_seconds',
                     'armoney_outbox_backlog', 'armoney_recovery_failures_total', 'armoney_outbox_failures_total',
                     'armoney_recovery_oldest_pending_age_seconds', 'armoney_outbox_oldest_pending_age_seconds'))
MAX_LONG = 9223372036854775807


class ObservationError(Exception):
    def __init__(self, code):
        self.code = code
        super().__init__(code)


def stamp(now):
    return now.astimezone(dt.timezone.utc).isoformat()


def validate(config):
    if not isinstance(config, dict):
        raise ValueError('Invalid collector configuration')
    for key, low, high in (('interval_seconds', 1, 300), ('timeout_seconds', 1, 5), ('stale_after_seconds', 3, 3600)):
        if type(config.get(key)) is not int or not low <= config[key] <= high:
            raise ValueError('Invalid collection interval')
    if config['stale_after_seconds'] < config['interval_seconds'] + config['timeout_seconds']:
        raise ValueError('Freshness window is too short')
    targets = config.get('targets')
    if not isinstance(targets, list) or not 1 <= len(targets) <= len(SERVICES):
        raise ValueError('Invalid collector targets')
    seen = set()
    for target in targets:
        if not isinstance(target, dict) or target.get('service') not in SERVICES or target['service'] in seen:
            raise ValueError('Invalid or duplicate service')
        seen.add(target['service'])
        if not isinstance(target.get('url'), str):
            raise ValueError('Invalid metrics URL')
        url = urllib.parse.urlsplit(target['url'])
        if (url.scheme != 'http' or url.hostname not in (target['service'], '127.0.0.1', 'localhost', '::1')
                or url.username is not None or url.password is not None
                or url.path != '/internal/metrics' or url.query or url.fragment or not url.port):
            raise ValueError('Metrics target must be an explicit private service or loopback endpoint')
        if not isinstance(target.get('key_env'), str) or not re.fullmatch(r'[A-Z][A-Z0-9_]{0,79}', target['key_env']):
            raise ValueError('Invalid credential variable name')
    for name in ('recovery_backlog_limit', 'outbox_backlog_limit', 'replica_lag_seconds_limit'):
        if type(config.get(name)) is not int or not 1 <= config[name] <= MAX_LONG:
            raise ValueError('Invalid alert threshold')
    for name in ('recovery_oldest_pending_age_seconds_limit', 'outbox_oldest_pending_age_seconds_limit'):
        if name in config and (type(config[name]) is not int or not 1 <= config[name] <= MAX_LONG):
            raise ValueError('Invalid pending-age threshold')
    return config


def parse_metrics(raw):
    if not isinstance(raw, bytes) or len(raw) > 16384:
        raise ObservationError('INVALID_METRICS')
    try:
        text = raw.decode('utf-8')
        result = {}
        for line in text.splitlines():
            if not line or line.startswith('# HELP ') or line.startswith('# TYPE '):
                continue
            sample = re.fullmatch(r'([a-z_]+) ([0-9]{1,19})', line)
            if not sample or sample[1] not in METRICS or sample[1] in result:
                raise ValueError()
            value = int(sample[2])
            if value > MAX_LONG or (sample[1] == 'armoney_ready' and value not in (0, 1)):
                raise ValueError()
            result[sample[1]] = value
        return result
    except (UnicodeError, ValueError):
        raise ObservationError('INVALID_METRICS') from None


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *unused):
        return None


def _fetch(url, key, timeout):
    # Never use environment proxies or follow a redirect carrying the key.
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect())
    request = urllib.request.Request(url, headers={'X-Service-Key': key, 'Accept': 'text/plain'})
    try:
        with opener.open(request, timeout=timeout) as response:
            if response.status != 200 or response.headers.get_content_type() != 'text/plain':
                raise ObservationError('INVALID_METRICS')
            return parse_metrics(response.read(16385))
    except urllib.error.HTTPError as error:
        code = 'AUTH_REJECTED' if error.code in (401, 403) else 'REDIRECT_REJECTED' if 300 <= error.code < 400 else 'SCRAPE_UNAVAILABLE'
        error.close()
        raise ObservationError(code) from None
    except (OSError, urllib.error.URLError):
        raise ObservationError('SCRAPE_UNAVAILABLE') from None


def fetch(url, key, timeout):
    # A socket inactivity timeout cannot bound drip-fed headers/body or DNS.
    # One serial child owns the whole scrape; kill and reap it on the total
    # deadline. Credentials and endpoint never enter argv, stderr or output.
    deadline = time.monotonic() + timeout
    environment = {name: value for name, value in os.environ.items()
                   if name.upper() in ('SYSTEMROOT', 'WINDIR', 'TEMP', 'TMP')}
    try:
        with subprocess.Popen([sys.executable, '-B', str(Path(__file__).resolve()), '--scrape-worker'],
                              stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                              stderr=subprocess.DEVNULL, env=environment) as worker:
            try:
                remaining = deadline - time.monotonic()
                if remaining <= 0:
                    raise subprocess.TimeoutExpired('scrape', timeout)
                output, unused = worker.communicate(json.dumps([url, key, timeout]).encode('utf-8'),
                                                     timeout=remaining)
                if time.monotonic() >= deadline:
                    raise subprocess.TimeoutExpired('scrape', timeout)
            except subprocess.TimeoutExpired:
                worker.kill()
                worker.communicate()
                raise ObservationError('SCRAPE_UNAVAILABLE') from None
            if worker.returncode != 0 or len(output) > 16384:
                raise ObservationError('SCRAPE_UNAVAILABLE')
            result = json.loads(output)
            if result.get('error'):
                code = result['error']
                if code not in ('AUTH_REJECTED', 'REDIRECT_REJECTED', 'SCRAPE_UNAVAILABLE', 'INVALID_METRICS'):
                    code = 'SCRAPE_UNAVAILABLE'
                raise ObservationError(code)
            return parse_metrics(result['metrics'].encode('ascii'))
    except (OSError, ValueError, KeyError, TypeError):
        raise ObservationError('SCRAPE_UNAVAILABLE') from None


def scrape_worker():
    try:
        url, key, timeout = json.loads(sys.stdin.buffer.read(4096))
        values = _fetch(url, key, timeout)
        result = {'metrics': ''.join(name + ' ' + str(value) + '\n' for name, value in values.items())}
    except ObservationError as error:
        result = {'error': error.code}
    except Exception:
        result = {'error': 'SCRAPE_UNAVAILABLE'}
    sys.stdout.write(json.dumps(result, separators=(',', ':')))


def fresh(snapshot, now):
    try:
        observed = dt.datetime.fromisoformat(snapshot['observed_at'])
        expiry = dt.datetime.fromisoformat(snapshot['valid_until'])
        return observed <= now < expiry
    except (KeyError, TypeError, ValueError):
        return False


def alert(code, condition):
    return {'code': code, 'state': 'UNKNOWN' if condition is None else 'FIRING' if condition else 'CLEAR'}


def collect(config, environment, previous=None, now=None, fetcher=fetch):
    now = now or dt.datetime.now(dt.timezone.utc)
    previous = previous if isinstance(previous, dict) and fresh(previous, now) else {}
    old_targets = {item['service']: item for item in previous.get('targets', [])}
    targets = []
    for target in config['targets']:
        values = dict.fromkeys(sorted(METRICS))
        key = environment.get(target['key_env'])
        error = None
        if not isinstance(key, str) or not 32 <= len(key) <= 256 or any(not 33 <= ord(character) <= 126 for character in key):
            error = 'CREDENTIAL_UNAVAILABLE'
        else:
            try:
                values.update(fetcher(target['url'], key, config['timeout_seconds']))
            except ObservationError as failure:
                error = failure.code
        availability = None if error == 'CREDENTIAL_UNAVAILABLE' else 0 if error else 1
        readiness_code = 'SERVICE_NOT_READY' if target['service'] == 'app-gateway' else 'DATABASE_NOT_READY'
        rules = [alert('SERVICE_UNAVAILABLE', None if availability is None else availability == 0),
                 alert(readiness_code, None if values['armoney_ready'] is None else values['armoney_ready'] == 0)]
        for metric, limit, code in (
                ('armoney_recovery_backlog', 'recovery_backlog_limit', 'RECOVERY_BACKLOG_LIMIT'),
                ('armoney_outbox_backlog', 'outbox_backlog_limit', 'OUTBOX_BACKLOG_LIMIT'),
                ('armoney_replica_lag_seconds', 'replica_lag_seconds_limit', 'REPLICA_LAG_LIMIT')):
            value = values[metric]
            rules.append(alert(code, None if value is None else value >= config[limit]))
        for metric, limit, code in (
                ('armoney_recovery_oldest_pending_age_seconds', 'recovery_oldest_pending_age_seconds_limit', 'RECOVERY_AGE_LIMIT'),
                ('armoney_outbox_oldest_pending_age_seconds', 'outbox_oldest_pending_age_seconds_limit', 'OUTBOX_AGE_LIMIT')):
            value = values[metric]
            rules.append(alert(code, None if value is None or limit not in config else value >= config[limit]))
        old = old_targets.get(target['service'], {}).get('metrics', {})
        for metric, code in (('armoney_recovery_failures_total', 'RECOVERY_FAILURES_RECENT'),
                             ('armoney_outbox_failures_total', 'OUTBOX_FAILURES_RECENT')):
            current, baseline = values[metric], old.get(metric)
            condition = None if current is None or type(baseline) is not int or current < baseline else current > baseline
            rules.append(alert(code, condition))
        targets.append({'service': target['service'], 'availability': availability,
                        'error': error, 'metrics': values, 'alerts': rules})
    return {'observed_at': stamp(now), 'valid_until': stamp(now + dt.timedelta(seconds=config['stale_after_seconds'])),
            'targets': targets}


def notifications(snapshot, previous):
    old = {(target['service'], rule['code']): rule['state'] for target in (previous or {}).get('targets', []) for rule in target['alerts']}
    return [{'service': target['service'], **rule} for target in snapshot['targets'] for rule in target['alerts']
            if rule['state'] != old.get((target['service'], rule['code']))]


def write_snapshot(path, value):
    temporary = path.with_name(path.name + '.tmp')
    with temporary.open('w', encoding='utf-8') as output:
        json.dump(value, output, separators=(',', ':'))
    os.replace(temporary, path)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--config', required=True, type=Path)
    parser.add_argument('--output', required=True, type=Path)
    parser.add_argument('--once', action='store_true')
    args = parser.parse_args()
    try:
        config = validate(json.loads(args.config.read_text(encoding='utf-8-sig')))
        previous = None
        while True:
            current = collect(config, os.environ, previous)
            write_snapshot(args.output, current)
            for notice in notifications(current, previous):
                print(json.dumps(notice, separators=(',', ':')), flush=True)
            previous = current
            if args.once:
                break
            time.sleep(config['interval_seconds'])
    except (OSError, ValueError, TypeError, KeyError):
        raise SystemExit('Local collector failed; inspect private configuration. No credential or response body emitted.') from None


if __name__ == '__main__':
    if sys.argv[1:] == ['--scrape-worker']:
        scrape_worker()
    else:
        main()
