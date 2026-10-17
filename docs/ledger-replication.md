# Ledger replication operations

The optional `compose.ledger-replication.yaml` keeps the existing primary
`ledger-db` and its volume and adds `ledger-db-replica1` and `ledger-db-replica2`
with independent new volumes. Both receive WAL directly from primary. Base Compose
remains single-node. [ADR 0013](adr/0013-ledger-replication.md) records consistency
and availability tradeoffs. This guide is source configuration, not proof that
the persistent stack has been upgraded.

```mermaid
flowchart LR
    L["ledger-service"] -->|"Writes, payment results, WAL fence, fallback"| P[("ledger-db: primary")]
    P -->|"Physical WAL streaming"| R1[("ledger-db-replica1")]
    P -->|"Physical WAL streaming"| R2[("ledger-db-replica2")]
    L -->|"Fenced balance reads"| R1
    L -->|"Fenced balance reads"| R2
```

## Activate without replacing existing data

Back up the ledger first and schedule a write-maintenance window. Preserve existing
passwords, project name and volumes. Add a distinct random
`LEDGER_REPLICATION_PASSWORD` to the ignored local `.env`; never commit its value
or include carriage returns/newlines in it.
Use the same explicit Compose file list for every command, including SSO or other optional
files already used by the installation. The commands below show the base plus
replication file set; add existing optional files consistently.

```powershell
docker compose -f compose.yaml -f compose.ledger-replication.yaml stop payment-service wallet-service ledger-service
docker compose -f compose.yaml -f compose.ledger-replication.yaml up -d ledger-db
docker compose -f compose.yaml -f compose.ledger-replication.yaml up -d ledger-replication-setup ledger-db-replica1 ledger-db-replica2
docker compose -f compose.yaml -f compose.ledger-replication.yaml exec -T ledger-db psql -U bank -d bank -c "SELECT application_name,state,sync_state,flush_lsn,replay_lsn FROM pg_stat_replication;"
```

Wait for two streaming replicas with quorum status before starting applications:

```powershell
docker compose -f compose.yaml -f compose.ledger-replication.yaml up --build -d ledger-service wallet-service payment-service
```

The one-shot setup is designed for existing and fresh primary volumes. It creates
a dedicated non-superuser replication role, two physical slots and synchronous
quorum configuration. Both slots reserve WAL immediately, before concurrent base
backups can recycle needed segments. Only inactive never-reserved slots under the
two overlay-owned names receive guarded repair; active or unusable slots are not
silently discarded. Its local-commit setting applies only to administrative
bootstrap. Standbys bootstrap only empty data directories, refuse partial or
promoted directories and never automatically delete/reseed data. An interrupted
clone requires operator inspection and explicit recreation of only the affected
standby after confirming which volume it uses.

## Reads and availability

`LEDGER_READ_DB_URLS` is a comma-separated list of standby JDBC URLs. It uses
`DB_USER`/`DB_PASSWORD`; empty means all reads use primary. Standby pools do not run
Flyway. The router checks database name, cluster system identifier and timeline,
requires a streaming WAL receiver with the primary's active timeline and standby
recovery mode, and waits up to 250 ms for replay of a primary WAL
flush fence. It rechecks identity/recovery after the query and otherwise reads
primary. Pool acquisition, statements and sockets have separate bounded timeouts;
250 ms is not an end-to-end response SLA.

Provisioning, posting, idempotency checks and result lookup always use primary.
The overlay sets `LEDGER_REQUIRE_SYNC_CONFIRMATION=true`: returning provisioning
or a new/replayed payment result also requires durable WAL confirmation on a
quorum standby after the primary transaction. Missing confirmation produces an
unavailable response, preserving the original command for recovery. This gate
has no primary-only success fallback. It protects cancellation/restart recovery,
where a locally committed transaction can outlive its synchronous wait and a
read-only or conflict-only retry would not wait automatically.
Base Compose omits this flag and retains its existing single-node durability model.
Readiness requires a usable primary that is not in recovery. Replica outage can
degrade to primary reads; it cannot manufacture a balance or financial success.
The bootstrap `bank` role remains the existing schema-owning local role. Restricted
production roles need explicitly scoped access to the control functions used for
identity checks and replication/receiver statistics. Missing read-routing permission
falls back to primary; missing durability-confirmation permission fails closed.
The confirmation gate has a two-second retry budget with separate connection/query
timeouts, requires a named quorum sender and the matching configured standby, and
checks durable WAL receipt as well as cluster/database/active timeline identity.

`synchronous_commit=on` waits for WAL flush on at least one quorum standby.
With both disconnected, a commit can wait even though its local effects exist.
A client timeout is not proof of rollback: query the authoritative result or retry
the original payment ID. Never create a replacement transfer to escape uncertainty.

## Monitor and recover

The optional private ledger metrics observer passed the affected local ledger
checks recorded below; this is not deployment evidence. With metrics opted in,
explicitly configure `LEDGER_METRICS_REPLICA_NAMES=ledger_replica1,ledger_replica2`
for the shipped two-standby profile. Names must be unique strict CSV members of
1–64 printable ASCII characters without whitespace; their count must equal the
nonblank `LEDGER_READ_DB_URLS` count. Invalid explicit configuration fails startup.
The overlay does not set these names automatically. Absent names keep the metric
unknown; disabled metrics or absent names allocate no observation pool.

The observer owns a separate read-only primary pool, maximum one connection,
minimum idle zero, with no eager connection requirement or Flyway migrations.
It uses `DB_URL`/`DB_USER`/`DB_PASSWORD`, 250 ms acquisition/validation limits,
one-second driver connection/socket timeouts, a 500 ms statement timeout and
read-only session defaults. It closes before business pools on shutdown and
startup failure. These component limits have not established measured total
scrape timing; readiness is a separate probe. Restricted roles need visibility
of the expected `pg_stat_replication` fields and permission to execute the
observation functions; missing permission yields unknown, not a privilege change.

Each scrape clears the previous gauge and requires every expected sender exactly
once, primary mode, streaming state, non-NULL replay position/WAL difference and
a reply timestamp within 30 seconds, never in the future. Fresh zero WAL
difference reports zero even with an idle NULL interval. Positive WAL difference
requires positive `replay_lag`, rounded upward to exact integer seconds. The
aggregate is the maximum only when all expected senders are known. Missing,
duplicate, stale, nonstreaming, inaccessible or invalid members, negative WAL
difference, overflow or query failure omit the gauge, including any earlier zero.

Positive values measure recent replay acknowledgment delay; they do not measure
time since the last transaction, backlog age or catch-up ETA. Named sender
observations with a matching URL count do not establish read-URL identity or
durability. Existing readiness, routing fences and financial confirmation remain
unchanged. See the [metrics contract](operational-metrics.md#optional-ledger-replica-observation)
and [ADR 0040](adr/0040-replica-observation.md).

On 2026-10-16, `gradlew.bat :ledger-service:check --no-daemon` passed against
base `f506069e83c6622f80834f4848866dd73a92a791` plus the recorded observation
source patch: 93 tests in 13 suites, zero failures, errors or skips. Both real
physical-standby scenarios passed, including observation assertions for catch-up,
paused replay, restricted permissions and disconnects. Evidence and the unchanged
pre-run source hash manifest are preserved at
`D:/ArMoneyDockerRecovery/2026-10-15/ledger-replica-observation-check/`.
This local affected-module result does not establish current-main integrated CI,
full repository checks, applicable Compose acceptance, independent verification,
end-to-end timing or deployment; those remain pending. No persistent stack was
operated for this check.

Inspect `pg_stat_replication` for streaming/quorum state and WAL positions, and
`pg_replication_slots` for `active`, `wal_status`, `restart_lsn` and retained WAL.
`max_slot_wal_keep_size=1GB` bounds slot retention at checkpoints; it is not a hard
limit on total disk usage. A replica beyond retained WAL may need a new base backup.
Retain independent backups and test restore; replication also copies accidental
changes and is not a backup. No database ports are published by this overlay.
Replication uses SCRAM authentication on the private Docker network; database-node
TLS is not configured by this development deployment.

## Manual failover

There is no automatic HA manager or constant write proxy. Promotion alone does
not restore application availability. Do not select replica1 merely by its name:
with ANY 1, replica2 may hold a more recent acknowledged commit.

1. Stop application writes and fence the old primary so it cannot accept writes,
   including after a restart. A failed health probe is insufficient fencing.
2. Compare available replicas' received/replayed WAL and timeline. Obtain the
   replica containing acknowledged commits; if this cannot be established, do not
   promise zero data loss. Apply all available WAL before promotion.
3. On that replica, call `SELECT pg_promote();` with administrative privileges and
   verify `SELECT pg_is_in_recovery();` returns false.
4. Explicitly configure the promoted container to start as primary. The shipped
   standby entrypoint deliberately refuses a promoted volume on restart. Preserve
   its volume; replace that entrypoint with the official PostgreSQL entrypoint in
   an operator-reviewed recovery override. Stop the old setup service from targeting
   the old primary.
5. Reconfigure the remaining standby's upstream and physical slot on the new
   primary. Restore the quorum configuration and verify a synchronous standby is
   streaming before allowing application writes. Never turn off sync to mask an
   incomplete recovery.
6. Change ledger `DB_URL` and standby URLs with a recovery override and recreate
   ledger-service to reset pools and pinned identity/timeline. Resume payment and
   wallet services; recover pending commands with their original identifiers.
7. Rejoin the fenced former primary only after suitable `pg_rewind` or a new base
   backup. Never simply restart it as a second writable primary.

This is a manual recovery checklist, not a turnkey failover script or a measured
10-30 second recovery guarantee. Multi-host HA, automatic election/fencing and
reparenting require a separate tested operational design.

## Disposable verification

```powershell
.\gradlew.bat check
python scripts/ledger-replication-smoke.py
```

The smoke creates its own random Compose project, credentials, private network and
volumes, uses no local `.env`/override/host ports, and removes only that project.
Before creating fixtures it rejects remote Docker endpoints, resolves a local
Unix socket or local-dot named pipe, and pins that endpoint for every command,
including diagnostics and cleanup. Ambient Docker and Compose overrides are
removed from the subprocess environment.
Replication shell scripts are LF-pinned in `.gitattributes` so their actual
bind mounts work from Windows checkouts; no fixture-only line-ending rewrite is used.
It tests direct streaming, read-only replicas, quorum interruption/recovery,
primary restart and manual promotion/reparenting with a synchronous write using a generic isolated
table. Real ledger financial invariants and read routing are tested separately by
PostgreSQL Testcontainers. No fixture touches or funds the persistent stack.
