# Private local collector and alert evaluation

The standard-library Python collector reads explicitly configured private
`GET /internal/metrics` endpoints. It never queries business databases, posts a
financial command, sends a notification to an external provider or opens an HTTP
listener. Only fixed service names and metric names enter its output. Keys come
from environment variables and never enter the status document or notifications.
HTTP redirects and environment proxies are disabled. Responses are limited to
16 KiB, total request deadlines to five seconds and samples to nonnegative Java
long integers. Each scrape runs in one isolated child process, with at most one
child active per collector. The deadline includes child startup, DNS resolution,
connection, response headers and body reads; a slow stream does not reset it.
Startup overhead consumes part of the configured request budget. On timeout the
collector terminates and reaps the child before continuing to the next target.
The endpoint and credential travel to the child only through standard input,
never command arguments. Child standard error is suppressed; raw responses,
URLs and credentials are not logged.
The example thresholds are operational starting values, not measured service SLAs.

```sh
python -B infra/observability/collector.py --config /private/collector.json --output /private/metrics-status.json --once
python -B infra/observability/test_collector.py
```

Without `--once`, the collector repeats locally at the configured interval.
Run it only inside the intended private service network, or against explicitly
configured loopback listeners. Do not publish a private service listener to make
collection convenient. Provision `INTERNAL_METRICS_KEY` separately on the service
and collector; no fallback to business authorization keys exists. This directory
does not enable the collector or modify deployment configuration.

## Observations and local notifications

The atomic JSON status document has `observed_at` and `valid_until`. Consumers
must call the equivalent of `fresh(snapshot, now)` before using any value. An
expired, absent or unreadable document means unknown observations and unknown
alerts. Missing metrics are JSON null and alert state `UNKNOWN`, never zero or
`CLEAR`. A failed scrape drops all previous values. A request deadline produces
`SCRAPE_UNAVAILABLE`, availability zero and `SERVICE_UNAVAILABLE=FIRING`; its
measurements and associated measurement alerts remain `UNKNOWN`. Collection
continues with the remaining configured targets. HTTP availability means the
metrics endpoint could be observed; it is separate from database readiness.

Each alert has finite `code` and `state` (`UNKNOWN`, `FIRING`, `CLEAR`). A transition
prints one bounded JSON notice to local standard output, containing only service,
code and state. A repeated unchanged state does not print another notice. This is
local operator output; there is no email, push, webhook or delivery guarantee.
Counters are process-lifetime observations: the first sample, a decreased counter
or a stale baseline produces `UNKNOWN` for recent failure alerts. A baseline
increase produces `FIRING`; an unchanged valid baseline produces `CLEAR`.

## Operator response

| Alert code | Safe investigation and recovery |
| --- | --- |
| `SERVICE_UNAVAILABLE` | Check collector/source process readiness, private network reachability and exact endpoint configuration. For `AUTH_REJECTED`, compare dedicated credential provisioning privately; never print keys. For `REDIRECT_REJECTED`, correct the endpoint rather than enabling redirects. |
| `DATABASE_NOT_READY` | Check the owning service and its database readiness/logs with normal secret redaction. Verify `/health/ready` recovery. Do not seed balances or bypass database checks. |
| `SERVICE_NOT_READY` | Check app-gateway's local runtime readiness. This probe has no downstream database or dependency-health guarantee; inspect each dependent service separately. |
| `RECOVERY_BACKLOG_LIMIT` | Ask the payment owner to inspect durable pending commands, leases and ledger reachability. Preserve payment IDs and idempotency keys; no replacement payment or manual completion. |
| `OUTBOX_BACKLOG_LIMIT` | Inspect the owning outbox worker, retry schedule and configured delivery dependency. Keep original records; verify an actual worker outcome before closing the incident. |
| `REPLICA_LAG_LIMIT` | Check replica connectivity and authoritative replication position. Do not promote a replica, alter primary data or claim zero lag from a missing sample. |
| `RECOVERY_FAILURES_RECENT` / `OUTBOX_FAILURES_RECENT` | Investigate the corresponding worker failure and retry behavior, then verify recovery independently. A counter reset is unknown, not recovery. |
| Any `UNKNOWN` state | Check source instrumentation, freshness, collector health and credential provisioning. Do not claim the associated service or queue is healthy. |

## Source and delivery limits

The example explicitly lists auth-service, user-service, wallet-service,
payment-service, ledger-service and app-gateway on their private Docker DNS names.
Each now has opt-in source readiness wiring using its dedicated metrics key.
Gateway readiness is local-only and does not probe downstream services or a
database. Do not treat its `armoney_ready=1` as end-to-end banking availability.
The endpoint is authenticated on its service listener; public edge/gateway routing
must not expose it. The example is not evidence of deployment or network isolation.

Payment-service observes aggregate counts in a read-only transaction only after
scrape authentication. Recovery backlog counts `PENDING` payments with non-null
`recipient_id`, including delayed retries and actively leased commands. Outbox
backlog counts all `PENDING` email_outbox records, including delayed and leased
records. These are unresolved totals, not only currently claimable batches. An
observation failure removes both gauges, so the collector reports unknown rather
than retaining a stale zero. No customer fields or financial amounts are exported.

Other unavailable queue measurements, process failure counters and replica lag
are omitted by sources and remain unknown in the collector until trustworthy
owner instrumentation exists. Backlog thresholds measure record counts, not
elapsed queue age; oldest-item age is not implemented. The collector cannot infer
worker freshness from a present gauge. Private deployment configuration,
CI and independent failure acceptance remain
integration gates. No persistent stack, provider or financial fixture is operated
by these scripts or their tests.
