# ADR 0027: Opt-in private operational metrics

Status: Accepted; six-service readiness, payment backlog observations and local
collector/alerts implemented in source. The main-based local full Gradle check
passed; isolated Compose acceptance, exact-head CI, independent verification and
deployment remain pending for the worker-observation integration. Payment worker
failure callbacks now provide process-local counters; replica lag is a separate
integration. Pending-age source is implemented below; its affected local checks
passed, while exact-head CI, actual Compose acceptance and rollout remain pending.

Worker callbacks observe at most one failure per tick, preserve interruption and
cannot alter financial or retry outcomes. A ledger business rejection is not an
operational failure; email provider rejection and throttling are. Counts reset
on restart and remain unknown until first observed failure; persisted attempts
are not historical failure totals. The affected Payment check passed 231 tests
on 2026-10-16 with zero failures, errors or skips. Exact-head combined validation
and deployment are separate gates.

Pending-age source extends the same payment-owned read-only observation with
one database statement timestamp and oldest durable creation time. Empty means
known zero; any future/nonfinite timestamp makes that population's age unknown,
while valid counts remain known. Observation failure clears counts and ages.
Collector age thresholds are optional positive integers; absent thresholds are
UNKNOWN. Deploy the new collector before emitting new metric names because its
strict allowlist intentionally rejects unknown measurements. On 2026-10-16,
affected Java/PG checks passed 272 tests across 20 suites with zero failures,
errors or skips; the collector's separate 19 tests passed. Independent review
verified the executed-source binding. Exact-head CI, actual Compose acceptance
and rollout remain pending for this follow-up.

## Context

Issue #22 requires operational visibility without exposing credentials, personal
data or financial records. The shared runtime provides technical plumbing only;
each service retains ownership of its database and operational observations.

## Decision

Provide an opt-in `OperationalMetrics` registry and `OperationalMetricsHttp`
adapter in platform-runtime. Registration adds `GET /internal/metrics` to the
service's existing listener. It does not add a public gateway or edge route.
Every scrape requires a configured 32–256-character printable nonwhitespace ASCII
`X-Service-Key`, checked
using constant-time byte comparison. Unauthorized requests return a generic 401
before invoking readiness or service observations. Responses use `Cache-Control: no-store`.

The registry accepts only finite enum measurements, with no caller-controlled
labels. It does not collect identities, IP addresses, request bodies, tokens,
raw paths, monetary amounts or balances. Services supply operational counts;
the runtime never queries service databases. Readiness exceptions produce zero.
Missing observations are omitted. Owners must explicitly invalidate stale gauges;
the registry does not establish freshness or automatically expire observations.
Counters are process-local and reset on restart; they saturate at `Long.MAX_VALUE`.

## Consequences and limits

The endpoint is absent unless a service explicitly registers the adapter.
Registration uses the same listener as the service, so network isolation still
depends on deployment configuration. The key authenticates collectors but does
not isolate peers sharing that key. Do not publish the service listener or add
edge routing for this endpoint. Existing service authorization middleware must
preserve the metrics adapter's authenticated internal boundary.

Six Java services register only when `INTERNAL_METRICS_KEY` is explicitly
present, without business-key fallback. Invalid configured keys fail startup.
Metrics reuse actual health readiness; gateway readiness remains local-only.
Payment additionally supplies read-only, statement-timeout-bounded counts of
mapped pending payments and pending email rows, including delayed/leased work.
Failed observations become unknown; counters and replica lag are not invented.

A private stdlib collector validates fixed targets, refuses redirects/proxies,
bounds I/O, writes timestamped atomic snapshots and emits finite local stdout
alerts on state transitions. Missing/stale data and reset counter baselines are
unknown. Deployment isolation, key provisioning and actual failure acceptance
remain required. No external notification provider is introduced.

An explicit `compose.metrics.yaml` override supplies dedicated metrics keys and
a pinned, nonroot private collector with no published ports. Private config and
status paths must already exist. Default Compose behavior and persistent resource
names are unchanged. The isolated CI runner reuses built service image IDs in a
fresh UUID project, checks actual edge exclusion and payment-database failure,
and gates cleanup on resource ownership. Source orchestration is implemented;
actual Compose runtime acceptance and deployment remain pending.

The main-based integration passed full Gradle check on 2026-10-16: 1,210 tests,
100 suites and zero failures, errors or skips, including 64 metrics cases.
Exact-head CI and independent security/failure verification remain pending. See
[operational metrics](../operational-metrics.md).

## Dated baseline delivery checkpoint

PR #73 passed exact-head CI and real metrics failure acceptance on 2026-10-16
before merge as `a0fab1b0afa3ea98f6cb0d437550aedf3cf04c82`. This supersedes
earlier pending-acceptance statements for that baseline only. Persistent deployment
remains pending. The replica-observation follow-up is covered by ADR 0040 and
requires its own combined-head checks; no worker-age/counter implementation is
implied.
