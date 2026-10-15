# ADR 0034: Persist disabled verification projections

Status: Accepted for disabled source plumbing; activation and deployment pending.

## Decision

Auth owns durable projection commands and ACK tracking in append-only V8.
User owns append-only V6 projection current state, immutable operation replay,
tombstones and admission. Each service uses its own database and domain types.
The [projection contract](../verification-projection-contract.md) defines the
private DTO, canonical length-prefixed SHA-256 encoding and six-field ACK.

Projection revision is independent of phone contact version. New operations use
revision CAS, strictly increasing eligibility epoch, unchanged contact version
for the same phone and increasing contact version for a replacement. Exact
retained replay returns the original ACK before new-operation checks. Expired
operation tombstones deny UUID reuse; neither timeout nor a stale ACK establishes
current eligibility.

User applies projection, central phone claim, existing directory and PRIMARY
physical profile synchronization in one primary database transaction under the
registration advisory lock. Replacement clears verification flags; same-phone
updates preserve the directory flag. No profile/directory creation, placement
change or remote shard I/O occurs. Projected identities are fenced from the
legacy phone-only operator attestation. This ACK is not operator verification
or banking authorization.

Auth stores canonical command/contact copies, fenced worker state and durable
keyed identity recovery barriers. A remote call occurs outside database locks;
ACK processing rechecks local authority. Permanent transport failures disable
retry; unresolved expiry cannot silently allocate a replacement command.
Full operation copies have bounded seven-day retention; tombstones survive that
cleanup, with capacity exhaustion denying new work rather than evicting replay
protection. Shared writer admission and retained-record/tombstone caps bound work.

## Trust boundary

The private POST uses a dedicated auth-to-user credential, independently
configured writer identity, strict JSON/body bounds, no-store replies and bounded
HTTP exchange/body deadlines. No caller identity header or shared business key
authorizes projection. Configuration defaults off and checks credential reuse.
Auth additionally requires a separately provisioned retention key.

InternalHttp supports a server-configured exact method/path exemption so a
dedicated handler can authenticate its own credential and bound its raw body.
It is not a client-selected exception, path-prefix exemption or public ingress
permission; ordinary business routes retain their existing authentication.
Real composed-service wiring acceptance remains required.

## Current limits

Main source wiring now constructs false-default projection configuration.
User enables the dedicated route and its exact exemption together. Auth constructs
configuration before database initialization, creates a worker only when enabled,
starts it after HTTP runtime and closes it on shutdown/error. No deployment flag
has been enabled; this is source integration, not runtime acceptance.
Auth's production authority factory always returns UNAVAILABLE; current V6
verification does not supply the identity/version/epoch authority required for
positive command allocation. No ACTIVE state, all-entry eligibility, banking
session, notice capture, legacy migration or provider registration is activated.
Issues #28/#36/#37 therefore remain partial.

Cleanup stores expose bounded helpers. The auth worker includes a cleanup tick
when explicitly enabled and started by Main; user cleanup has no
application scheduler. Operational cleanup/overdue/capacity monitoring is absent.
A helper or source-level scheduler does not establish deployed erasure behavior.

This ADR records implementation source, not rollout or successful end-to-end
acceptance. Current migration/concurrency/replay specifications are prepared;
populated auth V7-to-V8 and user V5-to-V6 upgrades and combined synthetic user
wiring have independent focused QA passes. Full affected checks and linked
CI/Compose acceptance
remain delivery gates. No historical
or synthetic test count substitutes for current integrated evidence.

## Consequences and acceptance

Keep both gates false until separately authorized activation. The production
auth authority remains unavailable, and no HTTP entry allocates auth commands.
Required evidence includes affected module checks, real isolated PostgreSQL
upgrade/replay/monotonicity/phone-uniqueness tests, actual-handler compatibility,
timeouts/fencing/tombstone/capacity tests, linked disposable smoke acceptance and
independent security/architecture review on the integrated revision. Deployment
is a separate authorized operation with readiness evidence.

Auth owner maintains commands/transport; user owner maintains central projection
and legacy-handler compatibility. The orchestrator owns shared runtime wiring,
contracts, CI and architecture. Neither source tests nor projection ACKs supply
missing banking authority.
