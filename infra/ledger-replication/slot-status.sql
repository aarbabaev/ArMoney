-- Run on the primary using an authorized monitoring connection. Alert on a
-- missing/inactive slot, lost/unreserved WAL, or retained WAL approaching 1 GiB.
-- max_slot_wal_keep_size is checkpoint-enforced, not a total pg_wal disk quota.
SELECT slot_name, active, wal_status, safe_wal_size,
       pg_wal_lsn_diff(pg_current_wal_lsn(), restart_lsn)::bigint AS retained_wal_bytes
FROM pg_replication_slots
WHERE slot_name IN ('ledger_replica1', 'ledger_replica2')
ORDER BY slot_name;
SELECT application_name, state, sync_state, sent_lsn, write_lsn, flush_lsn, replay_lsn,
       write_lag, flush_lag, replay_lag
FROM pg_stat_replication
WHERE application_name IN ('ledger_replica1', 'ledger_replica2')
ORDER BY application_name;
