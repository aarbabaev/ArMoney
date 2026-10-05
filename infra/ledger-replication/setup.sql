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
SELECT pg_create_physical_replication_slot('ledger_replica1') WHERE NOT EXISTS (SELECT FROM pg_replication_slots WHERE slot_name='ledger_replica1');
SELECT pg_create_physical_replication_slot('ledger_replica2') WHERE NOT EXISTS (SELECT FROM pg_replication_slots WHERE slot_name='ledger_replica2');
ALTER SYSTEM SET synchronous_standby_names = 'ANY 1 (ledger_replica1, ledger_replica2)';
SELECT pg_reload_conf();
