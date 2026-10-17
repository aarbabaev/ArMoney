# Operational metrics

Tracking: issue #22. Source includes the technical registry, private HTTP adapter,
opt-in readiness for six Java services, two payment backlog measurements, and a
local collector with finite alerts and runbooks. The main-based local full
Gradle/PostgreSQL check passed. Baseline exact-head CI and Compose failure
acceptance passed for PR #73 on 2026-10-16. The baseline was deployed from
`a0fab1b0afa3ea98f6cb0d437550aedf3cf04c82` that day: six ready services,
missing/wrong-key rejection, two advancing fresh collector snapshots and trusted
HTTPS internal-path exclusion were independently verified. Existing environments
were preserved except for the dedicated metrics key; no financial, delivery or
outage probe was run on persistent data. This verifies the baseline only.
The replica follow-up passed its separate exact-head CI in PR #78; replica and
worker-observation deployment remain pending. This worker integration still
requires its own exact-head CI and applicable acceptance.

## Service readiness opt-in

When `INTERNAL_METRICS_KEY` is absent, a service does not register the
endpoint; `INTERNAL_AUTH_KEY` is never used as a fallback. An explicitly blank
key, whitespace/control/non-ASCII characters, or a key outside the adapter's
32–256-character bounds fails startup. Use a
dedicated collector credential. The readiness supplier is shared with
`/health/ready` and preserves the existing database readiness check. Probe
exceptions yield readiness zero; metrics scrapes still return HTTP 200.
Auth, user, wallet, payment and ledger reuse their configured database readiness
suppliers; user readiness includes every configured profile shard. Gateway
readiness is local process readiness and does not establish peer health.
Listener isolation and key provisioning remain integration responsibilities.

Source verification confirms the production route configuration registers
business routes first and passes `(config, key, metrics, readiness)` in the
adapter's declared order. Existing business authorization checks apply only to
`/v1/` routes; the metrics adapter authenticates `/internal/metrics` itself.
The same database readiness supplier is passed to `ServiceRuntime.start` and
the metrics adapter. The configuration tests use a standalone adapter fixture;
combined business-route middleware and actual database failure still need
integrated verification. None of these source observations imply deployment.

## Integration contract

Create one `OperationalMetrics` per service process. In the existing runtime route
configuration, call:

```java
OperationalMetricsHttp.configure(config, metricsKey, metrics, readinessSupplier);
```

The adapter registers `GET /internal/metrics` on that service's listener, requiring
`X-Service-Key`. Use a separately configured collector key outside version control;
deployment and key provisioning are operator responsibilities. Do not expose the
endpoint through public gateway or Caddy routes. The adapter has no owner header
requirement and never queries a business database. Unauthorized scrapes cannot
invoke readiness or the optional service-owned observation callback. Successful
scrapes return Prometheus text format
0.0.4 and HTTP 200, including readiness zero when the probe fails.

| Registry enum | Exported measurement | Meaning |
| --- | --- | --- |
| `Gauge.READY` | `armoney_ready` | Current configured readiness probe, 0 or 1 |
| `Gauge.RECOVERY_BACKLOG` | `armoney_recovery_backlog` | Commands awaiting operational recovery |
| `Gauge.REPLICA_LAG_SECONDS` | `armoney_replica_lag_seconds` | Observed replication lag, integer seconds |
| `Gauge.OUTBOX_BACKLOG` | `armoney_outbox_backlog` | Records awaiting outbox delivery |
| `Counter.RECOVERY_FAILURES` | `armoney_recovery_failures_total` | Failed recovery attempts in this process |
| `Counter.OUTBOX_FAILURES` | `armoney_outbox_failures_total` | Failed delivery attempts in this process |

Use `set(Gauge, long)` for a valid nonnegative observation,
`increment(Counter)` for an observed failed attempt, and `unavailable(Gauge)` when
a source is unavailable or its observation becomes stale. Unknown gauges and
never-observed counters are omitted rather than emitted as healthy zeroes.
The registry has no clock or expiry policy; owners must implement freshness.
Counter resets are expected after restart. These counts do not establish payment
completion, rollback, account correctness or ledger balance.

## Measurements, collection and remaining acceptance

Payment observes its own database with a read-only transaction and a two-second
statement timeout. Recovery backlog counts mapped `PENDING` payments with a
non-null recipient; legacy unmapped commands are excluded. Email outbox backlog
counts all `PENDING` rows, excluding `SENT`, `SKIPPED` and `DEAD`. Both counts
include delayed and leased work; neither measures age or due-only batches.
Collection failure removes both gauges. Disabled email delivery does not hide
existing pending rows. Payment workers now observe process-local recovery and
email delivery failures. Each tick increments its counter at most once, even
when a provider failure is followed by a persistence failure. Recovery counts
uncertain/null peer results and dependency/store failures; confirmed ledger
business rejection is not an operational failure. Email counts permanent
rejection, throttling, transient delivery and dependency/store failures. Missing
or unverified email and stale leases retain their existing ordinary outcomes.
Shutdown interruption is preserved without counting a failure. Callback runtime
exceptions cannot change payment, retry or outbox outcomes.

Counters reset on process restart and remain omitted/UNKNOWN until the first
observed failure. Persisted attempt counts are not historical failure totals.
Optional replica observation is described below; its deployment remains separate.
Queue age is not measured.

On 2026-10-16, the affected Payment check passed 231 tests with zero failures,
errors or skips, including 26 worker-observation cases and PostgreSQL payment
and email-outbox recovery assertions. Independent source review found no
blocking issue. Combined exact-head CI, Compose acceptance and deployment of
these worker observations remain pending; these local results do not establish
live delivery or historical failure counts.

### Optional ledger replica observation

Ledger can observe named, directly connected primary WAL senders after a metrics
scrape authenticates. Enable the endpoint with its dedicated `INTERNAL_METRICS_KEY`
and configure `LEDGER_METRICS_REPLICA_NAMES` as strict comma-separated sender
application names. Each name must contain 1–64 printable ASCII characters, with
no whitespace, empty members or duplicates. Names are case-sensitive and their
count must equal the number of nonblank entries in `LEDGER_READ_DB_URLS`.
For the existing two-standby overlay the value is
`ledger_replica1,ledger_replica2`. This documents an explicit configuration value;
the replication overlay does not automatically enable this observation.
Invalid explicit names or a count mismatch fail startup with a generic error.
Absent names leave the gauge unknown. With metrics disabled or names absent,
the observer allocates no database pool.

The observer owns a separate read-only Hikari pool against `DB_URL`, reusing
`DB_USER`/`DB_PASSWORD`. It has maximum size one, minimum idle zero and no eager
connection requirement. Acquisition and validation limits are 250 ms each;
PostgreSQL driver connection and socket timeouts are one second each. Session
options set `statement_timeout=500` ms and `default_transaction_read_only=on`.
No Flyway migrations run through this pool. It closes before the business pools
on shutdown and during startup failure cleanup. These are component timeout
settings, not measured end-to-end timing evidence or a whole-scrape deadline;
the existing readiness probe runs separately afterward.

The role must be able to execute the observation functions and see the expected
sender fields in `pg_stat_replication`. Restricted-role masking or denied access
produces unknown, without granting privileges or weakening checks. Runtime
observation failures expose no raw database diagnostics in the response.

Every observation removes the previous gauge before querying primary. All
expected names must appear exactly once with primary recovery mode false,
`state=streaming`, a non-NULL replay LSN and WAL difference, and `reply_time`
no more than 30 seconds old and no later than the primary observation timestamp.
Unexpected names do not satisfy missing expected members. Classification is:

| Sender observation | Result |
| --- | --- |
| Fresh streaming sender with zero flushed-WAL/replay difference | Zero, including a NULL idle `replay_lag` |
| Positive WAL difference and positive `replay_lag` | Interval seconds rounded upward to an exact signed 64-bit integer |
| Positive WAL difference with NULL, zero or negative interval | Unknown |
| Missing, duplicate, stale, future-dated, nonstreaming, inaccessible or invalid expected sender; negative WAL difference; overflow | Unknown |

The gauge is the maximum across all expected members only when each is known;
otherwise it is omitted. Connection/query failure also removes any earlier zero.
Positive values describe PostgreSQL's recent replay acknowledgment delay, not
current backlog age or a catch-up estimate. Zero describes the fresh reported
WAL positions at observation time, not an ongoing guarantee. No calculation uses
time since the last transaction. See [PostgreSQL's measurement semantics](https://www.postgresql.org/docs/17/monitoring-stats.html#MONITORING-PG-STAT-REPLICATION-VIEW).

Names identify primary-side cluster observations; matching their count to read
URLs does not prove that those URLs reach those senders or validate their cluster,
database or timeline identity. Existing read-routing fences, durability
confirmation and readiness behavior are unchanged. This metric cannot confirm a
payment outcome. [ADR 0040](adr/0040-replica-observation.md) records the decision.

On 2026-10-16, `gradlew.bat :ledger-service:check --no-daemon` passed in 2 minutes
13 seconds with Temurin Java 21.0.12.1 and process-local
`JAVA_TOOL_OPTIONS=-Dapi.version=1.44`. The tested revision was base
`f506069e83c6622f80834f4848866dd73a92a791` plus the recorded ledger observation
source patch, not an integrated current-main revision. XML reports contain 93
tests across 13 suites: 65 unit and 28 integration tests, with zero failures,
errors or skips. The two real physical-standby scenarios passed, including the
new observation assertions for catch-up, paused replay, restricted permissions
and disconnects. The pre-run eight-file SHA256 manifest was unchanged after the
run. The full log, exit code, XML/HTML reports, summary and source manifest are
preserved privately at
`D:/ArMoneyDockerRecovery/2026-10-15/ledger-replica-observation-check/`.
This affected-module result does not establish integrated current-main CI, full
repository checks, applicable Compose acceptance, independent verification,
end-to-end timeout behavior or deployment. Those remain pending. No persistent
database or Docker stack was operated for this check.

The stdlib-only [local collector](../infra/observability/README.md) accepts fixed
service/DNS or loopback targets and exact private paths, disables redirects and
environment proxies, and bounds time and response size. Atomic snapshots carry
observation and expiry timestamps. Finite state-transition alerts go only to
local stdout; gateway unready means `SERVICE_NOT_READY`, not database failure.
Missing/stale measurements and first/reset counter baselines remain unknown.

The configured request timeout is a total deadline, capped at five seconds,
including isolated child startup, DNS resolution, connection, headers and body.
Startup overhead consumes the same budget. The collector runs targets serially,
with at most one scrape child active; on timeout it terminates and reaps that
child, then continues to the next target. Timeout yields `SCRAPE_UNAVAILABLE`,
availability zero and `SERVICE_UNAVAILABLE=FIRING`, while measurements and their
alerts are `UNKNOWN`. Credentials and endpoint URLs reach the child through
standard input rather than command arguments. Child standard error is suppressed;
raw responses, URLs and credentials are not logged.

No external provider is introduced. Root still owns private Compose deployment,
key provisioning and independent failure acceptance. Exercise database unavailability,
recovery retries, outbox delivery failure and replica delay after owner wiring.
Runbooks and thresholds must reflect those observed failure cases. Do not seed
or alter financial data on the persistent stack for verification.

## Optional private deployment and disposable acceptance

`compose.metrics.yaml` is an explicit opt-in override; the default Compose stack
does not enable metrics or a collector. Provision a dedicated
`INTERNAL_METRICS_KEY` independently of business and Auth verification credentials.
Set `METRICS_CONFIG_PATH` to an absolute private JSON configuration file (the
collector example contains no key), and `METRICS_STATUS_DIR` to an existing private
directory writable by UID/GID 10001. Both paths must exist before Compose starts;
it will not silently create them. Do not commit private configuration or credentials.

The collector uses a pinned official Python OCI image, a nonroot user, read-only
root filesystem and source/config mounts, a writable private status mount, no
capabilities and no published ports. Its key is passed through the container
environment; operators with Docker access can inspect that environment. This
is a local provisioning boundary, not isolation from the Docker operator.
The existing service listeners and persistent volume names remain unchanged.

CI includes offline collector, deployment-guard and staging checks. The backend
job additionally calls `scripts/run-metrics-ci.sh` after building bank fixtures.
That runner requires the exact isolated CI daemon/workspace markers, mints a
fresh `armoney-metrics-test-<32hex>` project and reuses existing service image IDs.
It creates private temporary configuration/status mounts, removes the fixture
gateway host binding and starts the actual repository Caddy route configuration
on a random loopback HTTPS port with a locally trusted CA. No SSO provider flow
is enabled by this fixture. It checks ownership before mutations and cleanup,
and removes only its disposable resources. It never funds accounts or changes
the persistent project.

`scripts/metrics-smoke.py` requires that owned project, HTTPS loopback origin and
trusted CA. It checks six-source authentication/readiness, empty-database backlog
zeroes, timestamp expiry and public-edge 404. A reversible payment-database
outage must produce health 503 and unknown backlog observations; if database pool
waits exhaust the collector's total deadline, the whole scrape is unavailable
and its measurements remain unknown. The exact database container is restored
before recovery is checked. The collector's background status document must
remain fresh. This fixture establishes neither nonempty business processing nor
queue-age, delivery-failure counters or replication-delay observations.

The runner handles termination with ownership-checked cleanup. Forced process or
runner termination can still interrupt cleanup; investigate only the recorded
isolated CI project and never remove the persistent stack. Actual runtime/CI
results are required before treating this source configuration as verified.

## Executed evidence

On 2026-10-16, the main-based integration (`af1f7975a4ef888000c88e88fbd83ade919b1776`
plus the metrics patch) passed `gradlew.bat check --no-daemon` with approved
Temurin Java 21 and process-local `JAVA_TOOL_OPTIONS=-Dapi.version=1.44`.
The command exited zero after 16 minutes 1 second; all 38 tasks executed.
XML reports contain 1,210 tests across 100 suites with zero failures, errors or
skips. Module totals are gateway 69, Auth 605, ledger 61, payment 205, runtime 30,
SMS sandbox 30, user 127 and wallet 83. The 64 metrics cases include two actual
PostgreSQL payment aggregate cases; these observations do not establish a
successful financial transfer. Reports remain under each module's
`build/reports/tests/test/` and `build/reports/tests/integrationTest/`.

The same integration passed 62 offline Python checks: collector 16, deployment
guards 12, CI staging 6, CI impact selection 21 and CI workflow guards 7.
`git diff --check` passed. No Compose runtime, persistent-stack operation or
external notification provider call was performed for these results. A separate optional
YAML parser check could not run because PyYAML was unavailable; actual Compose
configuration validation and failure acceptance remain required in isolated CI.
These working-tree results do not establish exact-head CI or independent review.

### Historical local evidence

The following Java evidence describes the retained historical snapshot, not the
new main-based integration.

On 2026-10-14, base `a3535df1d282ac512082a5127f99d54f0e6d6da5` plus the four
metrics source/test files passed `gradlew.bat --no-daemon :platform-runtime:test`
with Java 21: nine cases, zero failures/errors/skips. Tests cover unauthorized
scrapes without probe invocation, readiness exceptions without detail leakage,
endpoint absence when not opted in, missing/stale gauges, invalid values and
concurrent failure counts. Reports: `platform-runtime/build/reports/tests/test/`.
This focused result does not establish integrated service instrumentation,
deployment, alerts, full CI or independent security acceptance.

On 2026-10-15 the expanded collector independently passed thirteen lightweight
tests, including loopback transport, credential redirect refusal, six-service
configuration and unknown/stale handling. New service and payment aggregate
fixtures are implemented but their integrated Gradle/PostgreSQL runs are pending.

The payment readiness wiring subsequently passed
`gradlew.bat --no-daemon :platform-runtime:test :payment-service:test --tests '*PaymentMetricsSpec'`
on the same base plus the metrics source and payment wiring patch. The unchanged
runtime task was up-to-date; the payment fixture executed ten cases with zero
failures/errors/skips. Reports: `payment-service/build/reports/tests/test/`.

See [ADR 0027](adr/0027-local-operational-metrics.md).

Replica observation is not injected by the optional Compose file. To enable it,
set `LEDGER_METRICS_REPLICA_NAMES` explicitly in a private service environment
override together with the matching Ledger read URLs. Leaving it absent disables
the observation pool; an explicitly blank value is invalid and must not be used
as an interpolation default.
