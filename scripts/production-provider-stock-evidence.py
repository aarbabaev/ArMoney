"""Private snapshot evidence ONLY for root-owned, fresh isolated stock fixtures.

No CLI, environment, files, network, credentials, transaction changes or writes.
Caller owns a READ ONLY DB-API connection with statement_timeout <= 1000ms and
must hard-kill/reap the owned child at three seconds. Local checks cannot prove
physical cancellation. Root attests the fixed DB and snapshot custody. Counts
alone are not proof. Even equal row fingerprints cannot detect transient writes
that were completely undone between snapshots; this is no historical write audit.
Never publish/serialize Snapshot internals; only public_receipt()/compare() are public.
"""
import re
import time

NAMES = ('sso_code_intents', 'sso_code_intent_details', 'sso_code_intent_commands',
         'sso_code_confirmation_results', 'sso_login_proofs', 'sso_login_events',
         'managed_banking_operations', 'managed_banking_event_consumptions',
         'managed_banking_sessions', 'sessions', 'managed_banking_completions')
SETTINGS_SQL = "select current_setting('transaction_read_only'),current_setting('statement_timeout')"
SNAPSHOT_SQL = """with
 t0 as materialized (select to_jsonb(t) as row_data from sso_code_intents t limit 513),
 t1 as materialized (select to_jsonb(t) as row_data from sso_code_intent_details t limit 513),
 t2 as materialized (select to_jsonb(t) as row_data from sso_code_intent_commands t limit 513),
 t3 as materialized (select to_jsonb(t) as row_data from sso_code_confirmation_results t limit 513),
 t4 as materialized (select to_jsonb(t) as row_data from sso_login_proofs t limit 513),
 t5 as materialized (select to_jsonb(t) as row_data from sso_login_events t limit 513),
 t6 as materialized (select to_jsonb(t) as row_data from managed_banking_operations t limit 513),
 t7 as materialized (select to_jsonb(t) as row_data from managed_banking_event_consumptions t limit 513),
 t8 as materialized (select to_jsonb(t) as row_data from managed_banking_sessions t limit 513),
 t9 as materialized (select to_jsonb(t) as row_data from sessions t limit 513),
 t10 as materialized (select to_jsonb(t) as row_data from managed_banking_completions t limit 513)
select
 (select count(*) from t0) as sso_code_intents,
 (select count(*) from t1) as sso_code_intent_details,
 (select count(*) from t2) as sso_code_intent_commands,
 (select count(*) from t3) as sso_code_confirmation_results,
 (select count(*) from t4) as sso_login_proofs,
 (select count(*) from t5) as sso_login_events,
 (select count(*) from t6) as managed_banking_operations,
 (select count(*) from t7) as managed_banking_event_consumptions,
 (select count(*) from t8) as managed_banking_sessions,
 (select count(*) from t9) as sessions,
 (select count(*) from t10) as managed_banking_completions,
 encode(sha256(convert_to(jsonb_build_array(
  (select coalesce(jsonb_agg(row_data order by row_data::text),'[]'::jsonb) from t0),
  (select coalesce(jsonb_agg(row_data order by row_data::text),'[]'::jsonb) from t1),
  (select coalesce(jsonb_agg(row_data order by row_data::text),'[]'::jsonb) from t2),
  (select coalesce(jsonb_agg(row_data order by row_data::text),'[]'::jsonb) from t3),
  (select coalesce(jsonb_agg(row_data order by row_data::text),'[]'::jsonb) from t4),
  (select coalesce(jsonb_agg(row_data order by row_data::text),'[]'::jsonb) from t5),
  (select coalesce(jsonb_agg(row_data order by row_data::text),'[]'::jsonb) from t6),
  (select coalesce(jsonb_agg(row_data order by row_data::text),'[]'::jsonb) from t7),
  (select coalesce(jsonb_agg(row_data order by row_data::text),'[]'::jsonb) from t8),
  (select coalesce(jsonb_agg(row_data order by row_data::text),'[]'::jsonb) from t9),
  (select coalesce(jsonb_agg(row_data order by row_data::text),'[]'::jsonb) from t10)
 )::text,'UTF8')),'hex') as private_rows_fingerprint
"""


class Rejected(ValueError):
    def __init__(self):
        super().__init__('stock_evidence_rejected')


class Snapshot:
    __slots__ = ('_counts', '_fingerprint')

    def __init__(self, counts, fingerprint):
        object.__setattr__(self, '_counts', counts)
        object.__setattr__(self, '_fingerprint', fingerprint)
        _snapshot(self)

    def __setattr__(self, *_):
        raise AttributeError('private_snapshot_immutable')

    def __delattr__(self, *_):
        raise AttributeError('private_snapshot_immutable')

    def __repr__(self):
        return 'StockEvidenceSnapshot[private]'


def _snapshot(value):
    if type(value) is not Snapshot:
        raise Rejected()
    counts, fingerprint = getattr(value, '_counts', None), getattr(value, '_fingerprint', None)
    if (type(counts) is not tuple or len(counts) != len(NAMES)
            or any(type(n) is not int or not 0 <= n <= 512 for n in counts)
            or type(fingerprint) is not str or not re.fullmatch(r'[0-9a-f]{64}', fingerprint)):
        raise Rejected()
    return value


def public_receipt(snapshot):
    return dict(zip(NAMES, _snapshot(snapshot)._counts))


def compare(before, after):
    before, after = _snapshot(before), _snapshot(after)
    counts = before._counts == after._counts
    rows = before._fingerprint == after._fingerprint
    return {'counts_unchanged': counts, 'rows_unchanged': rows, 'verified': counts and rows}


def _rows(cursor, sql, end, clock):
    # Reserve a full server statement budget before starting either SELECT.
    if clock() >= end - 1:
        raise Rejected()
    cursor.execute(sql, ())
    rows = cursor.fetchall()
    if clock() >= end:
        raise Rejected()
    return rows


def probe(connection, *, clock=time.monotonic):
    """Return a private Snapshot with eleven counts and a canonical row digest.

    No owner filters hide revoked/expired or unrelated writes. This intentionally
    rejects counts above the fixture cap and is not a production diagnostic.
    """
    try:
        end = clock() + 3
        with connection.cursor() as cursor:
            settings = _rows(cursor, SETTINGS_SQL, end, clock)
            if (len(settings) != 1 or len(settings[0]) != 2 or settings[0][0] != 'on'
                    or type(settings[0][1]) is not str
                    or not re.fullmatch(r'(?:[1-9][0-9]{0,2}ms|1000ms|1s)', settings[0][1])):
                raise Rejected()
            rows = _rows(cursor, SNAPSHOT_SQL, end, clock)
            if len(rows) != 1 or len(rows[0]) != len(NAMES) + 1:
                raise Rejected()
            return Snapshot(tuple(rows[0][:-1]), rows[0][-1])
    except Exception:
        raise Rejected() from None
