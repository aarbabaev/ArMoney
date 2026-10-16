# Operational metrics

Tracking: issue #22. Source includes the technical registry, private HTTP adapter,
opt-in readiness for six Java services, two payment backlog measurements, and a
local collector with finite alerts and runbooks. The main-based local full
Gradle/PostgreSQL check passed; exact-head CI, Compose failure acceptance and
persistent deployment remain pending.

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
existing pending rows. Worker failure counters and replica lag remain unknown
until their owners provide actual observations.

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
