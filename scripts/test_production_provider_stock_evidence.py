"""Portable mock DB tests; no PostgreSQL/runtime/stock acceptance claim."""
import importlib.util
from pathlib import Path
import unittest

_SPEC = importlib.util.spec_from_file_location('stock_evidence',
    Path(__file__).with_name('production-provider-stock-evidence.py'))
E = importlib.util.module_from_spec(_SPEC)
_SPEC.loader.exec_module(E)


class Cursor:
    def __init__(self, settings=None, counts=None):
        self.results = [[('on', '1s')] if settings is None else settings,
                        [tuple(range(11)) + ('a' * 64,)] if counts is None else counts]
        self.calls = []
        self.closed = False

    def __enter__(self):
        return self

    def __exit__(self, *args):
        self.closed = True

    def execute(self, sql, args):
        self.calls.append((sql, args))

    def fetchall(self):
        return self.results[len(self.calls) - 1]


class Connection:
    def __init__(self, cursor):
        self.value = cursor

    def cursor(self):
        return self.value


class StockEvidenceTests(unittest.TestCase):
    def probe(self, cursor, clock=lambda: 0):
        return E.probe(Connection(cursor), clock=clock)

    def test_exact_eleven_counts_and_settings_before_one_count_select(self):
        cursor = Cursor()
        snapshot = self.probe(cursor)
        result = E.public_receipt(snapshot)
        self.assertEqual(result, dict(zip(E.NAMES, range(11))))
        self.assertEqual(cursor.calls, [(E.SETTINGS_SQL, ()), (E.SNAPSHOT_SQL, ())])
        self.assertTrue(cursor.closed)
        self.assertEqual(len(result), 11)

    def test_read_write_or_unknown_mode_never_queries_counts(self):
        for mode in ('off', 'true', True, None, 'ON'):
            cursor = Cursor(settings=[(mode, '1s')])
            with self.subTest(mode=mode), self.assertRaisesRegex(E.Rejected, '^stock_evidence_rejected$'):
                self.probe(cursor)
            self.assertEqual(len(cursor.calls), 1)

    def test_only_positive_at_most_one_second_server_timeout(self):
        for timeout in ('1ms', '999ms', '1000ms', '1s'):
            self.probe(Cursor(settings=[('on', timeout)]))
        for timeout in ('0', '0ms', '1001ms', '2s', '1min', '01ms', 1000, None, '1s ', 'private'):
            cursor = Cursor(settings=[('on', timeout)])
            with self.subTest(timeout=timeout), self.assertRaises(E.Rejected):
                self.probe(cursor)
            self.assertEqual(len(cursor.calls), 1)

    def test_settings_exact_one_two_column_row(self):
        for settings in ([], [('on',)], [('on', '1s', 'extra')], [('on', '1s'), ('on', '1s')]):
            cursor = Cursor(settings=settings)
            with self.subTest(settings=settings), self.assertRaises(E.Rejected):
                self.probe(cursor)
            self.assertEqual(len(cursor.calls), 1)

    def test_snapshot_exact_one_twelve_column_row(self):
        for counts in ([], [(0,) * 11], [(0,) * 13], [(0,) * 12, (0,) * 12]):
            with self.subTest(counts=counts), self.assertRaises(E.Rejected):
                self.probe(Cursor(counts=counts))

    def test_count_bounds_accept_zero_and_512(self):
        for value in (0, 512):
            result = E.public_receipt(self.probe(Cursor(counts=[(value,) * 11 + ('a' * 64,)])))
            self.assertTrue(all(type(number) is int and number == value for number in result.values()))

    def test_every_column_rejects_overflow_negative_bool_null_or_noninteger(self):
        for index in range(11):
            for value in (-1, 513, True, None, 1.0, '1'):
                row = [0] * 11
                row[index] = value
                with self.subTest(index=index, value=value), self.assertRaises(E.Rejected):
                    self.probe(Cursor(counts=[tuple(row) + ('a' * 64,)]))

    def test_no_statement_starts_with_less_than_one_second_left(self):
        cursor = Cursor()
        ticks = iter((0, 2))
        with self.assertRaises(E.Rejected):
            self.probe(cursor, lambda: next(ticks))
        self.assertEqual(cursor.calls, [])
        cursor = Cursor()
        ticks = iter((0, 0, 1, 2))
        with self.assertRaises(E.Rejected):
            self.probe(cursor, lambda: next(ticks))
        self.assertEqual(len(cursor.calls), 1)

    def test_result_at_deadline_rejected_even_if_query_returned(self):
        for ticks, expected in (((0, 0, 3), 1), ((0, 0, 0, 0, 3), 2)):
            cursor = Cursor()
            clock = iter(ticks)
            with self.subTest(ticks=ticks), self.assertRaises(E.Rejected):
                self.probe(cursor, lambda: next(clock))
            self.assertEqual(len(cursor.calls), expected)

    def test_driver_error_is_redacted_and_never_retried(self):
        cursor = Cursor()
        def fail(sql, args):
            cursor.calls.append((sql, args))
            raise RuntimeError('private connection information must not escape')
        cursor.execute = fail
        with self.assertRaisesRegex(E.Rejected, '^stock_evidence_rejected$') as failure:
            self.probe(cursor)
        self.assertNotIn('private', str(failure.exception))
        self.assertEqual(len(cursor.calls), 1)
        self.assertTrue(cursor.closed)

    def test_snapshot_select_is_global_bounded_canonical_and_identifiers_static(self):
        self.assertEqual(E.SNAPSHOT_SQL.lower().count('count(*)'), 11)
        self.assertEqual(E.SNAPSHOT_SQL.lower().count('limit 513'), 11)
        self.assertEqual(E.SNAPSHOT_SQL.lower().count('jsonb_agg(row_data order by row_data::text)'), 11)
        self.assertNotIn('where', E.SNAPSHOT_SQL.lower())
        self.assertNotIn('%s', E.SNAPSHOT_SQL)
        self.assertNotIn(';', E.SNAPSHOT_SQL)
        self.assertIn("encode(sha256(convert_to(jsonb_build_array(", E.SNAPSHOT_SQL)
        for index, name in enumerate(E.NAMES):
            self.assertIn('from ' + name + ' t limit 513)', E.SNAPSHOT_SQL)
            self.assertIn('from t' + str(index) + ') as ' + name, E.SNAPSHOT_SQL)

    def test_equal_counts_changed_rows_does_not_verify(self):
        before = self.probe(Cursor())
        after = self.probe(Cursor(counts=[tuple(range(11)) + ('b' * 64,)]))
        self.assertEqual(E.public_receipt(before), E.public_receipt(after))
        self.assertEqual(E.compare(before, after), {'counts_unchanged': True, 'rows_unchanged': False, 'verified': False})

    def test_counts_and_rows_both_required_and_comparison_values_are_bool(self):
        before = self.probe(Cursor())
        identical = self.probe(Cursor())
        self.assertEqual(E.compare(before, identical), {'counts_unchanged': True, 'rows_unchanged': True, 'verified': True})
        after = self.probe(Cursor(counts=[(0,) * 11 + ('a' * 64,)]))
        result = E.compare(before, after)
        self.assertEqual(result, {'counts_unchanged': False, 'rows_unchanged': True, 'verified': False})
        self.assertTrue(all(type(value) is bool for value in result.values()))

    def test_private_snapshot_strict_type_immutable_and_redacted(self):
        snapshot = self.probe(Cursor())
        self.assertEqual(repr(snapshot), 'StockEvidenceSnapshot[private]')
        self.assertNotIn('a' * 64, repr(snapshot))
        self.assertEqual(set(E.public_receipt(snapshot)), set(E.NAMES))
        with self.assertRaises(AttributeError):
            snapshot._fingerprint = 'b' * 64
        with self.assertRaises(AttributeError):
            del snapshot._counts
        class Impostor:
            _counts = tuple(range(11))
            _fingerprint = 'a' * 64
        class Subclass(E.Snapshot):
            pass
        for value in (None, E.public_receipt(snapshot), Impostor(), object.__new__(Subclass), object.__new__(E.Snapshot)):
            with self.subTest(value=type(value)), self.assertRaises(E.Rejected):
                E.public_receipt(value)
            with self.assertRaises(E.Rejected):
                E.compare(snapshot, value)

    def test_fingerprint_must_be_canonical_sha256(self):
        for fingerprint in (None, 1, 'a' * 63, 'a' * 65, 'A' * 64, 'g' * 64, 'private'):
            with self.subTest(fingerprint=fingerprint), self.assertRaises(E.Rejected):
                self.probe(Cursor(counts=[tuple(range(11)) + (fingerprint,)]))


if __name__ == '__main__':
    unittest.main()
