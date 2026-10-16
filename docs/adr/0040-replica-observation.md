# ADR 0040: Optional primary-side ledger replica observation

Status: implemented in source; affected local ledger checks passed; integrated verification and deployment pending.
Date: 2026-10-16

## Context

Issue #22 defines a private operational replica-lag gauge, previously omitted
because ledger supplied no observation. A missing measurement must remain unknown.
The optional replication profile already has named direct WAL senders, fenced
reads and explicit durability confirmation. Instrumentation must preserve those
financial and availability decisions and must not invent healthy zeroes.

## Decision

After metrics authentication, ledger queries its own primary's
`pg_stat_replication` through an optional, separately owned read-only Hikari pool.
The pool uses the existing primary credentials, performs no migrations, has at
most one connection and closes during shutdown or startup failure cleanup.
Acquisition/validation limits are 250 ms; driver connection/socket limits are one
second; statement timeout is 500 ms. Component settings do not establish an
observed response-time guarantee. Readiness still runs separately after observation.

The operator supplies strict, unique printable ASCII application names in
`LEDGER_METRICS_REPLICA_NAMES`, each 1–64 characters, with no whitespace or empty
members. The count must match nonblank `LEDGER_READ_DB_URLS` entries. Invalid
explicit configuration fails startup generically. Absent names leave the gauge
unknown; absent metrics opt-in or absent names allocate no observation pool.
No privilege changes or automatic Compose enablement accompany this decision.

Each scrape clears the prior gauge. Every expected sender must appear once,
report streaming state, and have visible non-NULL replay position and WAL
difference on a primary that is not in recovery. Reply timestamps must be no more
than 30 seconds old and not in the future relative to the primary sample.
Fresh zero WAL difference yields zero even if the idle interval is NULL. Positive
WAL difference requires a positive replay interval, rounded upward to integer
seconds with checked 64-bit conversion. The exported value is the maximum of
all expected members; any unknown member makes the aggregate unknown. Negative
WAL differences, duplicates, missing fields, stale replies, inaccessible statistics,
errors and overflow yield omission, never a substitute zero.

Positive intervals mean PostgreSQL's recent WAL replay acknowledgment delay.
They are not backlog age or catch-up ETA; idle time since the last transaction
is not a replication-lag measurement. See [PostgreSQL 17 statistics](https://www.postgresql.org/docs/17/monitoring-stats.html#MONITORING-PG-STAT-REPLICATION-VIEW).

## Consequences and verification

This adds one optional primary connection and a read-only query per authenticated
scrape. The role must have permission to execute the observation functions and
visibility of sender statistics; masked or denied fields remain unknown. Named
primary senders and a matching URL count do not prove read-URL membership or
cluster/database/timeline identity. Read routing, readiness, synchronous
durability, payment outcomes and all accounting invariants remain unchanged.

Classification and HTTP test source covers unknown handling, stale zero removal,
authentication ordering and invalid configuration. The existing disposable
physical-standby fixture is extended for catch-up, paused replay, restricted
permissions and disconnects. On 2026-10-16,
`gradlew.bat :ledger-service:check --no-daemon` passed in 2 minutes 13 seconds
against base `f506069e83c6622f80834f4848866dd73a92a791` plus the recorded source
patch. XML reports show 93 tests across 13 suites, zero failures, errors or skips,
including both real physical-standby scenarios. The source hash manifest remained
unchanged during the run. Full logs, XML/HTML reports and the manifest are retained
privately at `D:/ArMoneyDockerRecovery/2026-10-15/ledger-replica-observation-check/`.
This affected-module check does not establish integrated current-main CI, full
repository checks, applicable disposable Compose acceptance, end-to-end timing,
independent integrated verification or deployment. Those remain pending. No
persistent stack was operated, altered or funded for this verification.

The exact configuration and classification rules are maintained in the
[metrics guide](../operational-metrics.md#optional-ledger-replica-observation).
