import copy
import datetime as dt
import importlib.util
import json
from pathlib import Path
import tempfile
import threading
import time
import unittest
from unittest.mock import patch
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

loader = importlib.util.spec_from_file_location('collector', Path(__file__).with_name('collector.py'))
collector = importlib.util.module_from_spec(loader)
loader.loader.exec_module(collector)
KEY = 'synthetic-local-metrics-key-32-characters'


class CollectorTests(unittest.TestCase):
    def setUp(self):
        self.config = json.loads(Path(__file__).with_name('collector.example.json').read_text())
        self.now = dt.datetime.now(dt.timezone.utc)

    def collect(self, values=None, previous=None, now=None):
        return collector.collect(self.config, {'INTERNAL_METRICS_KEY': KEY}, previous,
                                 now or self.now, lambda *unused: values or {})

    def test_example_covers_exactly_six_private_service_targets(self):
        expected = {'auth-service', 'user-service', 'wallet-service', 'payment-service', 'ledger-service', 'app-gateway'}
        collector.validate(self.config)
        self.assertEqual({target['service'] for target in self.config['targets']}, expected)
        self.assertEqual(len(self.config['targets']), 6)
        for target in self.config['targets']:
            self.assertEqual(target['url'], 'http://' + target['service'] + ':8080/internal/metrics')
            self.assertEqual(target['key_env'], 'INTERNAL_METRICS_KEY')

    def test_six_sources_keep_payment_totals_and_gateway_scope_distinct(self):
        calls = []
        def observed(url, key, timeout):
            calls.append((url, key, timeout))
            if url.startswith('http://payment-service:'):
                return {'armoney_ready': 1, 'armoney_recovery_backlog': 123, 'armoney_outbox_backlog': 234}
            return {'armoney_ready': 0}
        result = collector.collect(self.config, {'INTERNAL_METRICS_KEY': KEY}, now=self.now, fetcher=observed)
        targets = {target['service']: target for target in result['targets']}
        self.assertEqual(len(calls), 6)
        self.assertTrue(all(key == KEY and timeout == 3 for _, key, timeout in calls))
        payment = targets['payment-service']
        self.assertEqual(payment['metrics']['armoney_recovery_backlog'], 123)
        self.assertEqual(payment['metrics']['armoney_outbox_backlog'], 234)
        gateway_alerts = {rule['code']: rule['state'] for rule in targets['app-gateway']['alerts']}
        self.assertEqual(gateway_alerts['SERVICE_NOT_READY'], 'FIRING')
        self.assertNotIn('DATABASE_NOT_READY', gateway_alerts)
        for service, target in targets.items():
            self.assertIsNone(target['metrics']['armoney_replica_lag_seconds'])
            if service != 'payment-service':
                self.assertIsNone(target['metrics']['armoney_recovery_backlog'])
                self.assertIsNone(target['metrics']['armoney_outbox_backlog'])
            if service != 'app-gateway':
                self.assertEqual(target['alerts'][1]['code'], 'DATABASE_NOT_READY')

    def test_gateway_target_cannot_substitute_another_service_or_duplicate_identity(self):
        config = copy.deepcopy(self.config)
        config['targets'][-1]['url'] = 'http://auth-service:8080/internal/metrics'
        with self.assertRaises(ValueError):
            collector.validate(config)
        config = copy.deepcopy(self.config)
        config['targets'][-1] = copy.deepcopy(config['targets'][0])
        with self.assertRaises(ValueError):
            collector.validate(config)

    def test_missing_metrics_are_unknown_not_healthy_zero(self):
        target = self.collect({'armoney_ready': 1})['targets'][0]
        self.assertIsNone(target['metrics']['armoney_recovery_backlog'])
        self.assertEqual(target['alerts'][1]['state'], 'CLEAR')
        self.assertTrue(all(rule['state'] == 'UNKNOWN' for rule in target['alerts'][2:]))

    def test_readiness_and_backlog_thresholds_are_observed_independently(self):
        target = self.collect({'armoney_ready': 0, 'armoney_recovery_backlog': 100,
                               'armoney_replica_lag_seconds': 30, 'armoney_outbox_backlog': 99})['targets'][0]
        states = {rule['code']: rule['state'] for rule in target['alerts']}
        self.assertEqual(states['SERVICE_UNAVAILABLE'], 'CLEAR')
        self.assertEqual(states['DATABASE_NOT_READY'], 'FIRING')
        self.assertEqual(states['RECOVERY_BACKLOG_LIMIT'], 'FIRING')
        self.assertEqual(states['REPLICA_LAG_LIMIT'], 'FIRING')
        self.assertEqual(states['OUTBOX_BACKLOG_LIMIT'], 'CLEAR')

    def test_scrape_failure_drops_old_observations_instead_of_reusing_readiness(self):
        previous = self.collect({'armoney_ready': 1})
        def fail(*unused):
            raise collector.ObservationError('SCRAPE_UNAVAILABLE')
        result = collector.collect(self.config, {'INTERNAL_METRICS_KEY': KEY}, previous, self.now, fail)
        target = result['targets'][0]
        self.assertEqual(target['availability'], 0)
        self.assertIsNone(target['metrics']['armoney_ready'])
        self.assertEqual(target['alerts'][1]['state'], 'UNKNOWN')

    def test_missing_credential_is_unknown_and_never_attempts_a_request(self):
        def forbidden(*unused):
            self.fail('Credential failure must not make a request')
        result = collector.collect(self.config, {}, now=self.now, fetcher=forbidden)
        self.assertEqual(result['targets'][0]['error'], 'CREDENTIAL_UNAVAILABLE')
        self.assertIsNone(result['targets'][0]['availability'])
        self.assertTrue(all(rule['state'] == 'UNKNOWN' for rule in result['targets'][0]['alerts']))

    def test_counter_baseline_resets_and_stale_data_are_unknown(self):
        metric = 'armoney_outbox_failures_total'
        initial = self.collect({metric: 3})
        increased = self.collect({metric: 4}, initial)
        reset = self.collect({metric: 1}, initial)
        stale = self.collect({metric: 4}, initial, self.now + dt.timedelta(seconds=46))
        state = lambda snapshot: snapshot['targets'][0]['alerts'][-1]['state']
        self.assertEqual(state(initial), 'UNKNOWN')
        self.assertEqual(state(increased), 'FIRING')
        self.assertEqual(state(reset), 'UNKNOWN')
        self.assertEqual(state(stale), 'UNKNOWN')
        self.assertFalse(collector.fresh(initial, self.now + dt.timedelta(seconds=46)))

    def test_invalid_header_credentials_are_unknown_without_attempting_a_request(self):
        def forbidden(*unused):
            self.fail('Invalid credential must not make a request')
        for key in (None, 'x' * 31, 'x' * 257, 'x' * 31 + ' ', 'x' * 31 + '\t',
                    'x' * 31 + '\n', 'x' * 31 + '\u007f', 'x' * 31 + '\u00e9'):
            with self.subTest(key_length=len(key) if isinstance(key, str) else None):
                result = collector.collect(self.config, {'INTERNAL_METRICS_KEY': key},
                                           now=self.now, fetcher=forbidden)
                for target in result['targets']:
                    self.assertEqual(target['error'], 'CREDENTIAL_UNAVAILABLE')
                    self.assertTrue(all(rule['state'] == 'UNKNOWN' for rule in target['alerts']))

    def test_labels_duplicates_and_noninteger_values_are_rejected(self):
        for raw in (b'armoney_ready{owner="secret"} 1', b'armoney_ready 1\narmoney_ready 0',
                    b'armoney_ready 2', b'armoney_ready NaN', b'armoney_ready 1.0',
                    b'armoney_outbox_backlog -1', b'armoney_outbox_backlog 9223372036854775808',
                    b'other_metric 1', b'x' * 16385):
            with self.subTest(raw_length=len(raw)), self.assertRaises(collector.ObservationError):
                collector.parse_metrics(raw)

    def test_int64_and_output_are_exact_and_contain_no_key_url_or_body(self):
        values = collector.parse_metrics(b'# HELP armoney_ready SECRET_RESPONSE\narmoney_outbox_backlog 9223372036854775807\n')
        result = self.collect(values)
        output = json.dumps(result)
        self.assertEqual(result['targets'][0]['metrics']['armoney_outbox_backlog'], collector.MAX_LONG)
        for forbidden in (KEY, 'SECRET_RESPONSE', '/internal/metrics', 'http:', 'key_env'):
            self.assertNotIn(forbidden, output)

    def test_targets_are_private_fixed_routes_without_credentials_or_redirect_destinations(self):
        for url in ('https://example.com/internal/metrics', 'http://example.com:80/internal/metrics',
                    'http://user:password@payment-service:8080/internal/metrics',
                    'http://payment-service:8080/v1/payments', 'http://payment-service:8080/internal/metrics?token=secret'):
            config = copy.deepcopy(self.config)
            config['targets'][0]['url'] = url
            with self.assertRaises(ValueError):
                collector.validate(config)
        collector.validate(self.config)

    def test_atomic_output_and_notifications_use_only_finite_rule_state(self):
        first = self.collect({'armoney_ready': 0})
        self.assertTrue(collector.notifications(first, None))
        self.assertEqual(collector.notifications(first, first), [])
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'status.json'
            collector.write_snapshot(path, first)
            self.assertEqual(json.loads(path.read_text()), first)
            self.assertFalse(path.with_name('status.json.tmp').exists())

    def test_slow_drip_has_total_deadline_and_does_not_delay_next_target(self):
        self.assert_slow_scrape_deadline(False)

    def test_slow_headers_have_total_deadline_and_do_not_delay_next_target(self):
        self.assert_slow_scrape_deadline(True)

    def assert_slow_scrape_deadline(self, slow_headers):
        requests = []
        handlers = []
        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *unused):
                pass
            def do_GET(self):
                handlers.append(threading.current_thread())
                requests.append(self.path)
                if len(requests) == 1 and slow_headers:
                    try:
                        self.wfile.write(b'HTTP/1.0 200 OK\r\nContent-Type: text/plain\r\nX-Drip: ')
                        for unused in range(20):
                            self.wfile.write(b'a')
                            self.wfile.flush()
                            time.sleep(0.15)
                        self.wfile.write(b'\r\n\r\narmoney_ready 1\n')
                    except (BrokenPipeError, ConnectionResetError, ConnectionAbortedError):
                        pass
                    return
                self.send_response(200)
                self.send_header('Content-Type', 'text/plain')
                self.end_headers()
                try:
                    if len(requests) == 1:
                        for byte in b'armoney_ready 1\n':
                            self.wfile.write(bytes([byte]))
                            self.wfile.flush()
                            time.sleep(0.15)
                    else:
                        self.wfile.write(b'armoney_ready 1\n')
                except (BrokenPipeError, ConnectionResetError, ConnectionAbortedError):
                    pass
        server = ThreadingHTTPServer(('127.0.0.1', 0), Handler)
        thread = threading.Thread(target=server.serve_forever)
        thread.start()
        try:
            config = copy.deepcopy(self.config)
            config['timeout_seconds'] = 1
            config['targets'] = config['targets'][:2]
            for target in config['targets']:
                target['url'] = 'http://127.0.0.1:' + str(server.server_port) + '/internal/metrics'
            started = time.monotonic()
            children = []
            spawn = collector.subprocess.Popen
            def child(*args, **kwargs):
                self.assertNotIn(KEY, str(args))
                self.assertNotIn('http:', str(args))
                self.assertNotIn(KEY, str(kwargs.get('env')))
                process = spawn(*args, **kwargs)
                children.append(process)
                return process
            with patch.object(collector.subprocess, 'Popen', side_effect=child):
                result = collector.collect(config, {'INTERNAL_METRICS_KEY': KEY})
            self.assertEqual(2, len(children))
            self.assertTrue(all(process.poll() is not None for process in children))
            self.assertTrue(all(process.stdin.closed and process.stdout.closed for process in children))
            elapsed = time.monotonic() - started
            self.assertLess(elapsed, 1.8, 'Scrape must honor total deadline and reach next target')
            first, second = result['targets']
            self.assertEqual(first['error'], 'SCRAPE_UNAVAILABLE')
            self.assertTrue(all(value is None for value in first['metrics'].values()))
            states = {rule['code']: rule['state'] for rule in first['alerts']}
            self.assertEqual(states['SERVICE_UNAVAILABLE'], 'FIRING')
            self.assertTrue(all(state == 'UNKNOWN' for code, state in states.items() if code != 'SERVICE_UNAVAILABLE'))
            self.assertEqual(second['metrics']['armoney_ready'], 1)
            self.assertEqual(len(requests), 2)
            for forbidden in (KEY, 'http:', '/internal/metrics'):
                self.assertNotIn(forbidden, json.dumps(result))
        finally:
            server.shutdown()
            server.server_close()
            thread.join(timeout=2)
            for handler in handlers:
                handler.join(timeout=2)
            self.assertFalse(thread.is_alive())
            self.assertTrue(all(not handler.is_alive() for handler in handlers))

    def test_transport_sends_only_private_key_and_does_not_follow_redirects(self):
        requests = []
        redirect = [False]
        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *unused):
                pass
            def do_GET(self):
                requests.append((self.path, self.headers.get('X-Service-Key')))
                if redirect[0]:
                    self.send_response(302)
                    self.send_header('Location', '/credential-sink')
                    self.end_headers()
                else:
                    self.send_response(200)
                    self.send_header('Content-Type', 'text/plain; version=0.0.4')
                    self.end_headers()
                    self.wfile.write(b'armoney_ready 1\n')
        server = ThreadingHTTPServer(('127.0.0.1', 0), Handler)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            url = 'http://127.0.0.1:' + str(server.server_port) + '/internal/metrics'
            self.assertEqual(collector.fetch(url, KEY, 1), {'armoney_ready': 1})
            redirect[0] = True
            with self.assertRaises(collector.ObservationError) as failure:
                collector.fetch(url, KEY, 1)
            self.assertEqual(failure.exception.code, 'REDIRECT_REJECTED')
            self.assertEqual(requests, [('/internal/metrics', KEY), ('/internal/metrics', KEY)])
        finally:
            server.shutdown(); server.server_close(); thread.join(timeout=2)


if __name__ == '__main__':
    unittest.main()
