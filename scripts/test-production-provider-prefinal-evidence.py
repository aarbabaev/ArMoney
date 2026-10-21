"""Portable adapter-contract tests. Fake rows do not establish PostgreSQL/browser acceptance."""
import contextlib
import copy
import datetime as D
import importlib.util
import io
from pathlib import Path
import re
import unittest

PATH = Path(__file__).with_name('production-provider-prefinal-evidence.py')
SPEC = importlib.util.spec_from_file_location('prefinal_evidence', PATH)
E = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(E)
OWNER = '11111111-1111-4111-8111-111111111111'
SUBJECT = '22222222-2222-4222-8222-222222222222'
ROW = '33333333-3333-4333-8333-333333333333'
MIGRATION = '44444444-4444-4444-8444-444444444444'
CREATED = D.datetime(2026, 10, 19, 1, 0, 0, 123456, tzinfo=D.timezone.utc)
EXPIRES = CREATED + D.timedelta(hours=1)


def scope(mode='LOCAL_PASSWORD'):
    return dict(owner_id=OWNER, mode=mode, issuer=E.ISSUER,
                legacy_subject=SUBJECT if mode == 'LEGACY_OIDC_REAUTH' else None,
                target_subject=SUBJECT, target_client='armoney-android', session_hash='a' * 64)


def baseline(mode='LOCAL_PASSWORD'):
    return dict(scope(mode), version=1, session_row_id=ROW,
                created_at=CREATED.isoformat(timespec='microseconds'),
                expires_at=EXPIRES.isoformat(timespec='microseconds'), owner_generation=2,
                credential_generation=3, email_generation=4,
                association_generation=5 if mode == 'LEGACY_OIDC_REAUTH' else None, session_count=1)


def baseline_row(mode='LOCAL_PASSWORD'):
    return (ROW, CREATED, EXPIRES, 2, 3, 4, 5 if mode == 'LEGACY_OIDC_REAUTH' else None,
            1, True, True, True, True)


class Connection:
    def __init__(self, rows, settings=('on', '1s', '1000ms'), failure=None):
        self.batches = [[settings], rows]
        self.calls = []
        self.fetch_bounds = []
        self.failure = failure
    def cursor(self): return self
    def __enter__(self): return self
    def __exit__(self, *args): pass
    def execute(self, sql, args):
        self.calls.append((sql, args))
        if self.failure: raise self.failure
    def fetchmany(self, limit):
        self.fetch_bounds.append(limit)
        return self.batches.pop(0)[:limit]


class PrefinalEvidenceTests(unittest.TestCase):
    def test_baseline_binds_actual_owner_digest_and_returns_private_exact_originals(self):
        for mode in E.MODES:
            with self.subTest(mode=mode):
                c = Connection([baseline_row(mode)])
                value = E.capture_baseline(c, scope(mode))
                self.assertEqual(baseline(mode), value)
                self.assertEqual((OWNER, 'a' * 64, mode, E.ISSUER,
                                  SUBJECT if mode == E.MODES[1] else None), c.calls[1][1])
                self.assertEqual([3, 3], c.fetch_bounds)

    def test_prefinal_receipt_has_only_closed_boolean_fields_for_both_modes(self):
        for mode in E.MODES:
            with self.subTest(mode=mode):
                c = Connection([(True,) * len(E.RESULT_FIELDS)])
                output = io.StringIO()
                with contextlib.redirect_stdout(output), contextlib.redirect_stderr(output):
                    result = E.inspect_prefinal(c, scope(mode), MIGRATION, baseline(mode))
                self.assertEqual(set(E.RESULT_FIELDS) | {'prefinal_storage_evidence'}, set(result))
                self.assertTrue(all(type(v) is bool and v for v in result.values()))
                self.assertEqual('', output.getvalue())
                self.assertEqual(16, len(c.calls[1][1]))
                self.assertEqual((OWNER, MIGRATION, mode), c.calls[1][1][:3])
                self.assertEqual((ROW, baseline(mode)['created_at'], baseline(mode)['expires_at'], 2, 3, 4,
                                  5 if mode == E.MODES[1] else None, 1), c.calls[1][1][8:])
                self.assertNotIn(OWNER, str(result))
                self.assertNotIn('a' * 64, str(result))

    def test_each_failed_persisted_predicate_independently_prevents_positive_receipt(self):
        for i, name in enumerate(E.RESULT_FIELDS):
            with self.subTest(predicate=name):
                row = [True] * len(E.RESULT_FIELDS)
                row[i] = False
                result = E.inspect_prefinal(Connection([tuple(row)]), scope(), MIGRATION, baseline())
                self.assertFalse(result[name])
                self.assertFalse(result['prefinal_storage_evidence'])
                self.assertEqual(2, list(result.values()).count(False))

    def test_nonboolean_null_missing_duplicate_or_extra_result_rows_reject(self):
        row = (True,) * len(E.RESULT_FIELDS)
        for rows in ([], [row, row], [row + (True,)], [row[:-1]], [(None,) + row[1:]], [(1,) + row[1:]]):
            with self.subTest(shape=repr(rows)[:50]):
                with self.assertRaises(E.Rejected):
                    E.inspect_prefinal(Connection(rows), scope(), MIGRATION, baseline())

    def test_baseline_requires_single_live_unpromoted_historical_row(self):
        row = baseline_row()
        cases = [[], [row, row], [row[:-1]], [row + (True,)]]
        for i in range(8, 12):
            cases.append([row[:i] + (False,) + row[i+1:]])
            cases.append([row[:i] + (None,) + row[i+1:]])
        for rows in cases:
            with self.subTest(rows=len(rows)):
                with self.assertRaises(E.Rejected): E.capture_baseline(Connection(rows), scope())

    def test_baseline_rejects_invalid_timestamp_generation_and_count_data(self):
        for index, value in ((0, ROW.upper()), (1, CREATED.replace(tzinfo=None)), (2, CREATED),
                             (3, True), (4, -1), (5, 2**63 - 1), (6, 0), (7, 0), (7, 65)):
            row = list(baseline_row()); row[index] = value
            if index == 0: row[index] = 'not-a-uuid'
            with self.subTest(index=index, value=str(value)[:10]):
                with self.assertRaises(E.Rejected): E.capture_baseline(Connection([tuple(row)]), scope())

    def test_scope_rejects_unknown_fields_wrong_issuer_client_mode_and_noncanonical_identifiers(self):
        cases = [dict(scope(), extra=True)]
        for key, value in (('owner_id', OWNER.replace('1', 'A')), ('target_subject', 'bad'),
                           ('issuer', 'https://elsewhere.test'), ('target_client', 'armoney-migration-account'),
                           ('mode', 'FINAL'), ('legacy_subject', SUBJECT), ('session_hash', 'A' * 64),
                           ('session_hash', "' OR true --")):
            cases.append(dict(scope(), **{key: value}))
        cases += [dict(scope(E.MODES[1]), legacy_subject=OWNER), dict(scope(E.MODES[1]), legacy_subject=None)]
        for value in cases:
            with self.subTest(keys=tuple(value)):
                c = Connection([])
                with self.assertRaises(E.Rejected): E.capture_baseline(c, value)
                self.assertEqual([], c.calls)

    def test_prefinal_requires_same_private_baseline_scope_and_canonical_migration(self):
        for key, value in (('owner_id', SUBJECT), ('mode', E.MODES[1]), ('version', True),
                           ('session_row_id', 'bad'), ('created_at', '2026-10-19T01:00:00Z'),
                           ('expires_at', baseline()['created_at']), ('association_generation', 0),
                           ('credential_generation', -1), ('session_count', True), ('email_generation', 2**63-1)):
            c = Connection([]); b = dict(baseline(), **{key: value})
            with self.subTest(key=key):
                with self.assertRaises(E.Rejected): E.inspect_prefinal(c, scope(), MIGRATION, b)
                self.assertEqual([], c.calls)
        for migration in (None, 'bad', MIGRATION + ' ', MIGRATION.upper().replace('4', 'A')):
            c = Connection([])
            with self.assertRaises(E.Rejected): E.inspect_prefinal(c, scope(), migration, baseline())
            self.assertEqual([], c.calls)

    def test_readonly_and_statement_and_lock_timeouts_are_required_before_fact_query(self):
        for settings in (('off', '1s', '1s'), ('on', '0', '1s'), ('on', '2s', '1s'),
                         ('on', '1s', '0'), ('on', '1s', '1001ms'), ('on', None, '1s')):
            c = Connection([baseline_row()], settings=settings)
            with self.subTest(settings=settings):
                with self.assertRaises(E.Rejected): E.capture_baseline(c, scope())
                self.assertEqual(1, len(c.calls))
        for timeout in ('1ms', '999ms', '1000ms', '1s'):
            self.assertEqual(baseline(), E.capture_baseline(Connection([baseline_row()], settings=('on', timeout, timeout)), scope()))

    def test_budget_does_not_start_another_statement_without_a_full_second_remaining(self):
        times = iter((0, 0, 1.1, 1.1))
        c = Connection([baseline_row()])
        with self.assertRaises(E.Rejected): E.capture_baseline(c, scope(), clock=lambda: next(times))
        self.assertEqual(1, len(c.calls))

    def test_overdue_query_never_returns_a_receipt(self):
        times = iter((0, 0, .1, .1, 2))
        with self.assertRaises(E.Rejected):
            E.inspect_prefinal(Connection([(True,) * len(E.RESULT_FIELDS)]), scope(), MIGRATION, baseline(), clock=lambda: next(times))

    def test_database_exception_is_redacted_and_never_printed(self):
        c = Connection([], failure=RuntimeError('private-owner-secret'))
        output = io.StringIO()
        with contextlib.redirect_stdout(output), contextlib.redirect_stderr(output):
            with self.assertRaisesRegex(E.Rejected, '^prefinal_evidence_rejected$') as failure:
                E.capture_baseline(c, scope())
        self.assertEqual('', output.getvalue())
        self.assertIsNone(failure.exception.__cause__)

    def test_sql_is_fixed_parameterized_select_only_without_locks_or_other_service_tables(self):
        for sql, size in ((E.BASELINE_SQL, 5), (E.PREFINAL_SQL, 16)):
            self.assertEqual(size, sql.count('%s'))
            self.assertIsNone(re.search(r'\b(insert|update|delete|alter|create|drop|truncate|copy|call|do)\b', sql, re.I))
            self.assertNotIn(OWNER, sql)
            self.assertNotIn('a' * 64, sql)
            self.assertNotIn('wallet', sql)
            self.assertNotIn('ledger', sql)
            self.assertNotIn('for share', sql.lower())
            self.assertNotIn(';', sql)
        self.assertIn('limit 2', E.BASELINE_SQL.lower())
        self.assertIn('g.email_generation=p.email_generation+1', E.PREFINAL_SQL)
        self.assertIn('r.email_generation=p.email_generation+1', E.PREFINAL_SQL)
        self.assertIn('s.created_at=p.original_created and s.expires_at=p.original_expiry', E.PREFINAL_SQL)
        self.assertIn('r.row_id=p.original_row', E.PREFINAL_SQL)
        self.assertIn('not exists(select 1 from sso_login_proofs)', E.PREFINAL_SQL)
        self.assertIn("p.mode='LEGACY_OIDC_REAUTH' or not exists(select 1 from external_identities x where x.issuer=p.issuer and x.subject=p.target_subject)", E.PREFINAL_SQL)

    def test_account_receipt_numericdate_precision_preserves_chain_and_age_guards(self):
        # Offline SQL contract only: these assertions do not execute PostgreSQL.
        account = E.PREFINAL_SQL.split("case when p.mode='LOCAL_PASSWORD' then (select account_receipt_id is null from i)", 1)[1].split('end as account_receipt_chain_consistent', 1)[0]
        self.assertIn("date_trunc('second',r.authenticated_at)>=date_trunc('second',r.challenge_created_at)", account)
        self.assertNotIn('r.authenticated_at>=r.challenge_created_at', account)
        for guard in ('r.challenge_created_at=a.created_at', 'r.callback_database_start=claim.started_at',
                      'r.authenticated_at<=r.issued_at', 'r.issued_at<r.token_expires_at',
                      "c.completed_at>=claim.started_at and c.completed_at<claim.started_at+interval '4 seconds'",
                      'r.owner_id=p.owner', 'r.proof_mode=p.mode', 'r.issuer=p.issuer', 'r.subject=p.legacy_subject',
                      'r.owner_generation=p.owner_generation', 'r.association_generation=p.association_generation',
                      'r.credential_generation=p.credential_generation', 'r.email_generation=p.email_generation+1',
                      'r.account_deadline>clock_timestamp()', 'r.attempt_deadline>clock_timestamp()',
                      'r.token_expires_at>clock_timestamp()', 'm.account_receipt_hash=r.receipt_hash'):
            self.assertIn(guard, account)
        # Reference boundary examples document the OIDC NumericDate contract.
        # They are not an emulation or acceptance test of PostgreSQL date_trunc.
        challenge = CREATED
        same_second = challenge.replace(microsecond=0)
        earlier_second = same_second - D.timedelta(seconds=1)
        next_second = same_second + D.timedelta(seconds=1)
        self.assertLess(same_second, challenge)
        for auth_at, expected in ((same_second, True), (earlier_second, False), (next_second, True)):
            self.assertEqual(expected, auth_at.replace(microsecond=0) >= challenge.replace(microsecond=0))

    def test_all_queried_tables_are_in_published_auth_migrations(self):
        migration_dir = PATH.parent.parent / 'auth-service/src/main/resources/db/migration'
        ddl = '\n'.join(p.read_text() for p in migration_dir.glob('V*.sql'))
        tables = set(re.findall(r'create\s+table\s+(\w+)', ddl, re.I))
        used = set(re.findall(r'(?:from|join)\s+(\w+)', E.BASELINE_SQL + E.PREFINAL_SQL, re.I))
        self.assertEqual(set(), used - tables - {'p', 'i', 'owners'})


if __name__ == '__main__': unittest.main()
