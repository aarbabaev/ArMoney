\set QUIET on
-- Suppress administrative credential statements even on a primary configured
-- for statement logging. psql's quoted variable syntax escapes SQL literals;
-- ALTER ROLE does not accept a protocol bind parameter in its PASSWORD clause.
SET log_statement = 'none';
SET log_min_duration_statement = -1;
SET log_min_duration_sample = -1;
SET log_transaction_sample_rate = 0;
SET log_min_error_statement = 'panic';
\getenv replication_password LEDGER_REPLICATION_PASSWORD
SELECT set_config('ledger.bootstrap_password', :'replication_password', false) AS ignored \gset
DO $$
BEGIN
  IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'ledger_replication') THEN
    CREATE ROLE ledger_replication LOGIN REPLICATION;
  END IF;
  EXECUTE format('ALTER ROLE ledger_replication WITH LOGIN REPLICATION NOSUPERUSER NOCREATEDB NOCREATEROLE NOBYPASSRLS PASSWORD %L', current_setting('ledger.bootstrap_password'));
END $$;
-- Reserve WAL before either fast-checkpoint base backup begins. Without immediate
-- reservation, concurrent backups can recycle WAL before their receiver attaches.
DO $$
DECLARE
  target_name text;
  existing record;
BEGIN
  PERFORM pg_advisory_xact_lock(182736451, 1);
  FOREACH target_name IN ARRAY ARRAY['ledger_replica1', 'ledger_replica2'] LOOP
    SELECT * INTO existing FROM pg_replication_slots WHERE slot_name = target_name;
    IF NOT FOUND THEN
      PERFORM pg_create_physical_replication_slot(target_name, true);
    ELSIF existing.slot_type <> 'physical' OR existing.temporary THEN
      RAISE EXCEPTION 'Reserved ledger slot % has incompatible configuration', target_name;
    ELSIF existing.restart_lsn IS NULL THEN
      -- Repair only a never-reserved, inactive physical slot under these exact
      -- overlay-owned names. No existing replay position is discarded. A racing
      -- receiver makes pg_drop_replication_slot fail rather than dropping it.
      IF existing.active OR existing.wal_status IS NOT NULL THEN
        RAISE EXCEPTION 'Ledger slot % requires explicit operator recovery', target_name;
      END IF;
      PERFORM pg_drop_replication_slot(target_name);
      PERFORM pg_create_physical_replication_slot(target_name, true);
    ELSIF existing.wal_status IN ('lost', 'unreserved') THEN
      RAISE EXCEPTION 'Ledger slot % requires explicit operator recovery', target_name;
    END IF;
  END LOOP;
END $$;
ALTER SYSTEM SET synchronous_standby_names = 'ANY 1 (ledger_replica1, ledger_replica2)';
SELECT pg_reload_conf();
