# ADR 0041: internal managed eligibility evidence grants

Status: implemented internal evidence slice; local Auth validation and independent
Auth capability security verification completed on 2026-10-16. Exact combined CI
is pending. No runtime activation, deployment or completed registration-enforcement
result is claimed.

## Scope and prerequisites

Managed registration completion materializes a permanently denied principal and
can reach `PROJECTED` after receipt-bound projection acknowledgement. This slice
adds an internal evidence record for that principal; it does not turn the record
into a credential or a banking decision.

`AUTH_REGISTRATION_ELIGIBILITY_GRANTS_ENABLED` is an independent strict boolean,
default `false`. True requires managed completion, which already requires the
authority bridge and projection configuration. It supplies no implicit delivery,
provider-action, login-proof or session activation. The existing public
enrollment/status response retains seven fields and never advertises ACTIVE.
Managed banking denial remains enforced in every flag mode, even when an internal
grant exists and all optional flags are disabled.

## First-grant bootstrap and transaction boundary

The first grant is prepared through the existing PRETOKEN enrollment status flow.
It requires a current authorized pre-token flow and complete local evidence, not
an existing eligibility grant, bank session or ACTIVE status. This avoids making
first-grant creation depend on a token whose future issuance would itself require
that grant. No standalone positive lookup or grant-consumer endpoint is added.

The implemented sequence preserves auth's time and trust boundary:

1. Admission commits an auth-database timestamp after its control locks.
2. The bounded provider observation request runs outside the auth transaction,
   using that exact admission anchor and exact server-side flow tuple.
3. The owning auth transaction takes verification, projection, then login-proof
   control locks. It authorizes the tuple HMAC/live barriers and revalidates local
   state, observation age and expiry after potentially waiting locks.
4. Caller-owned grant helpers reuse that transaction; they perform no HTTP and
   open no nested transaction. A transaction-scoped authorization marker binds
   enrollment, flow, observation and admission anchor to the owning context.

STATUS observations are separate from positive-only operations. Fully validated
negative boolean facts can reach local invalidation through the real Auth HTTP
adapter and service. The final-check helper revalidates current evidence, live
flow and effective expiry before returning a new or replayed row. Local regression
execution and independent Auth capability verification cover SEC-GRANT-001
(negative STATUS invalidation) and SEC-GRANT-002 (final expiry revalidation).
These results do not establish actual Keycloak negative-fact propagation.

## V11 evidence and idempotency

V11 adds `banking_eligibility_grants`, uniquely keyed by identity and verification
epoch. Source requires the permanent managed marker, reserved/mapped identity,
exact issuer/subject association and an existing `PROJECTED` authority with a
verified enrollment. Both current candidate versions must have receipts tied to
consumed challenges. Current contacts and reserved phone ownership must agree.

The projection must match identity, enrollment, epoch, revision, phone version,
both current receipt IDs and email version. Its exact payload hash and persisted
ACK/tombstone must agree, the tombstone must be `ACKNOWLEDGED` without a recovery
barrier, and the corresponding permanent completion intent must exist. Provider
facts cannot supply missing receipts, projection ACKs or an identity association.

The immutable record retains candidate/receipt IDs and versions, operation ID,
projection revision/hash, association digest, creation time and expiry. Its
effective deadline is bounded by enrollment, authority, full projection-command
and tombstone expiry. An exact same-identity/epoch replay returns the original
record; changed evidence conflicts, and expiry cannot be extended by replay.
The database also constrains expiry to follow creation and not exceed the stored
enrollment/authority deadlines. Final runtime revalidation checks the retained
grant and current evidence deadlines after transaction work.

UPDATE and DELETE reuse the permanent-record rejection trigger. The managed-marker
foreign key restricts deletion; candidate, receipt and authority IDs/hashes are
intentionally not foreign-keyed to those historical rows. They remain evidence
identifiers, not anonymous data or an erasure mechanism. Existing contact and
external-mapping retention limits still apply. Source imposes a global 100,000-row
admission cap; it adds no purge, renewal or operational retention scheduler.

## Invalidation and future consumers

Local mutation hooks preserve immutable grant history and suspend the authority,
advancing the epoch when needed. A fresh, authorized changed/disabled/inactive
provider STATUS observation commits suspension before returning conflict.
Unknown or unavailable provider evidence is a fail-closed availability failure,
not invented revocation evidence. Positive-only observations remain unchanged;
negative facts are accepted only through the separately validated STATUS path.

A retained row, matching ID or unexpired timestamp is never sufficient for future
banking access. A future consumer must obtain fresh provider evidence and recheck
current association, candidates/receipts, epoch, projection ACK/recovery state and
all deadlines under the shared lock boundary. Neither an old grant nor a disabled
flag can bypass permanent managed-principal denial. No consumer, ACTIVE state,
bank-session/token grant, positive login-proof authority or login notice is wired
in this slice.

Suspension, expired evidence, cleaned full projection commands and recovery
barriers cannot be repaired by replaying a historical grant. Automatic recovery,
reenrollment, renewed eligibility and provider/cutoff rollout need a separate
validated design. V11 performs no legacy backfill or conversion of the existing
200-owner baseline. The compatible-binary rollback restriction after managed
materialization remains in force; see [ADR 0039](0039-managed-registration-completion.md).

## Validation and remaining integration gates

On 2026-10-16, the full local `:auth-service:check --no-daemon` completed in
11m 50s: 728 tests across 41 suites, with zero failures, errors or skips. This
includes 28 grant integration cases, a populated V10-to-V11 upgrade preserving
200 legacy owners and sessions without backfill, real HTTP adapter/service/
PostgreSQL negative STATUS regressions, and final expiry and actual PostgreSQL
lock-wait freshness cases. The initial run had one test error-classification
expectation mismatch; its results were preserved, and only that assertion was
corrected to the existing expired-flow `BINDING` denial before the passing rerun.
No production source changed between runs. Independent security verification of
the Auth capability verified SEC-GRANT-001 and SEC-GRANT-002. Exact combined CI
remains pending; these results do not authorize activation.

The negative-observation regression uses a synthetic provider HTTP response through
the real Auth adapter, service and PostgreSQL path. The current Keycloak observer
emits positive facts or unavailable responses; actual Keycloak negative-fact
propagation is not established by this slice. Unavailable observations never mint
grants. Provider-side invalidation and positive session/proof acceptance remain
separate integration gates before activation. The internal record remains
noncredential evidence, with default-off creation, no public ACTIVE status or
session grant, and unconditional managed-principal banking denial.
