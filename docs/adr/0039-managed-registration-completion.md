# ADR 0039: managed registration completion without banking access

Status: implemented source; local affected checks passed 675 tests without failures, errors or skips. Exact-head CI, integrated
acceptance and deployment remain pending. No runtime flag is activated here.

## Configuration and scope

V9 reserves a pending identity UUID and records separate email/phone candidates
and receipts. V8 owns durable auth-to-user projection commands. Neither receipt
collection nor a projection acknowledgement establishes ACTIVE eligibility.

The independent strict boolean
`AUTH_REGISTRATION_AUTHORITY_COMPLETION_ENABLED` defaults `false`. Enabling it
requires `AUTH_VERIFICATION_AUTHORITY_BRIDGE_ENABLED=true` and
`AUTH_VERIFICATION_PROJECTION_ENABLED=true`, with their existing observation,
writer, destination and independent credentials. Invalid/incomplete combinations
fail startup; the legacy verification bridge remains incompatible with authority
mode. Completion does not implicitly enable delivery, provider actions, proof
issuance or banking eligibility.

Main supplies receipt-backed projection authority only to completion and starts
one worker through authority resources, instead of also starting the standalone
worker. Without completion, standalone projection still supplies UNAVAILABLE
authority. Enrollment/status retain seven response fields; issue/consume retain
five/three. There is no new gateway endpoint or public response field.

## Atomic completion and replay

Completion after consume requires receipts matching both current candidate IDs,
purposes and contact versions. One receipt cannot complete registration. Explicit
prepare uses the same completion operation. The second current receipt and these
writes commit or roll back in one auth transaction:

- Materialize `identities.id` using the reserved pending UUID, without copying
  local email/password credentials or registration phone into the identity row.
- Insert the permanent managed classification and exact issuer/subject mapping.
- Set mapped identity, advance epoch/projection revision and set
  `PROJECTION_PENDING`.
- Allocate the V8 hash-bound command/tombstone and persist its original operation
  ID in the completion business intent.

Contact equality detects conflicts; it never selects or links another identity.
Existing mapped owners without a managed marker need a separate migration design.
Exact local-registration retries for their existing owner remain compatible.

V10 adds `managed_registration_principals` and
`registration_completion_intents`. The domain-separated business hash binds
enrollment/reserved identity, issuer/subject, current candidate IDs and receipt
IDs. Another command UUID for the same business pair reuses the original operation
without advancing epoch or allocating another projection. Current receipt checks
precede lookup. A changed candidate/receipt pair can create another operation only
when the prior recovery barrier permits it; an old ACK remains historical.

Intent rows retain enrollment/identity/operation IDs, epoch, revision and a 32-byte
hash without copying contact values. They outlive V8 full-command cleanup. Both
V10 tables reject ordinary UPDATE, including no-op UPDATE, and DELETE. Their
identity/marker foreign keys restrict deletion; neither has an authority-row
foreign key. Permanent denial must not depend on a live authority association.
These controls cannot constrain an administrator who can change the schema.

## Projection and access boundaries

The worker commits its local claim, calls the dedicated user-service HTTP endpoint
outside that transaction, and attaches the fenced result in another transaction.
Only a matching current ACK, receipt-backed authority, operation/epoch/projection
data and acknowledged V8 tombstone can set `PROJECTED`. Retry, uncertainty and
stale/nonmatching results cannot grant that state. No HTTP runs inside completion
or attachment transactions; auth never queries user-service's database.

`PROJECTED` is the highest state introduced here. This supplies no ACTIVE,
positive login-proof eligibility, banking session/token grant or login notice.
Managed principals are denied before banking profile provisioning, during external
identity exchange, and during session creation/lookup in **all modes**. Permanent
marker checks remain with completion, bridge, projection and
`AUTH_BANKING_ELIGIBILITY_ENFORCED` disabled. That last switch separately controls
broader denial of V9 staging associations; it cannot override managed denial.
Unmanaged historical owners retain documented compatibility.

## Lock order and retention

Authority, projection, login-proof and banking admission transactions acquire the
same prefix: verification control, projection control, then login-proof control.
Missing controls fail closed. Transaction-local lock assertions protect helpers.
Existing post-lock database-time, observation-freshness and flow-expiry rechecks
remain. Remote provider observation occurs outside the transaction; provider time
is never the admission clock.

Permanent markers and intent IDs/hashes are not erasure. Canonical contact
candidates, receipt associations, reservation history and external issuer/subject
mappings retain their existing limits. Bounded flow/response/projection cleanup
does not imply a general PII purge, automatic authority deletion or an operational
retention scheduler.

## Upgrade, rollback and validation

V10 is append-only: no legacy backfill, identity linking, contact verification or
conversion of the existing 200-owner baseline. No persistent stack or owner data
is changed as part of source implementation.

Once a managed principal exists, rollback is limited to binaries enforcing the
permanent classification in every mode. **Disabling flags does not make an older
binary safe:** its compatibility path can ignore V10 denial. Do not remove
markers/intents, edit published migrations or drop tables to obtain compatibility.

Local `:auth-service:check` passed 675 tests across 38 suites, including 39 completion cases and the populated managed-principal upgrade. Independent source, documentation and security evidence reviews passed within this partial scope. Exact-head CI and deployment remain pending.
Required acceptance includes second-receipt rollback, concurrent business replay,
changed-contact/stale-ACK behavior, replay after V8 cleanup, all-mode denial and
lock waiting, populated upgrades/legacy compatibility, worker ACK attachment and
independent security review. Provider committed-state and complete registration
enforcement remain separate gates. This source slice does not complete issues
#28/#36 or authorize activation.
