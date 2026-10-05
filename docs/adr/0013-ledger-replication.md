# ADR 0013: Direct ledger streaming replication and fenced balance reads

Status: implemented in source; deployment and exact-revision verification are separate.
Date: 2026-10-05

## Context

Ledger owns balances, immutable postings and durable payment outcomes. Scaling
reads must not weaken atomic posting or interpret a stale missing payment as a
failed transfer. Existing ledger data and Compose volume identifiers must survive.

## Decision

An opt-in Compose overlay keeps `ledger-db` as primary and creates two physical
PostgreSQL 17.6 hot standbys directly from it. There is no cascading replication.
Physical replication copies the whole ledger cluster, including schema and
Flyway history; migrations run only through the primary application pool.

Application commits use `synchronous_commit=on` with
`ANY 1 (ledger_replica1, ledger_replica2)`. One standby must durably flush WAL
before acknowledgement. This does not imply replay on either arbitrary standby.
Only administrative bootstrap uses local commit to create replication credentials,
slots and quorum configuration before standbys can connect. Application commits
never automatically downgrade to asynchronous operation.

Account balance queries select one standby round-robin, capture a WAL flush fence
from primary and check database, system identifier, timeline, recovery role and
replay progress before returning a result. Lag, wrong identity, promotion or
unavailability falls back to primary. Posting, provisioning and payment-result
lookup stay primary. All transfer checks retain the same primary transaction.
Read routing therefore still requires an available primary; it is not an outage
mode. Failover requires application restart and explicit connection reconfiguration.

## Consequences

One unavailable standby does not prevent commits. With neither available, commits
wait for synchronous confirmation; timeout/disconnection can leave an uncertain
outcome and must retain the same payment ID. Slots have bounded WAL retention;
monitor lag, disk usage and invalidated slots, and explicitly rebuild an unusable
replica without deleting primary data.

No automatic failure detector, leader election, fencing service, routing proxy,
backup archive or production HA manager is implemented. Manual promotion must
fence the old primary, select a replica containing acknowledged WAL, reparent the
remaining standby and restore synchronous protection before resuming writes.
Two containers on one host do not protect against host failure. Restricted roles,
TLS between database nodes, backups/PITR and multi-host operations remain separate
production work. See [the runbook](../ledger-replication.md).
