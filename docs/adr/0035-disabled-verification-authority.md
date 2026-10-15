# ADR 0035: Stage disabled verification authority

Status: Accepted for disabled source staging with independent focused evidence;
full auth check passed; integrated runtime acceptance and activation remain pending.

## Decision and source boundary

V9 appends auth-owned verification authority, independent immutable contact
candidates/current pointers, phone reservation history, authorized flow rows,
permanent contact-free flow denial barriers, shared admission and command replay
metadata. Existing V6/V8 migrations are not rewritten. No identity, mapping or
receipt backfill is performed. Read the
[authority contract](../verification-authority-contract.md) for the normative
limits and the [projection contract](../verification-projection-contract.md)
for the independent six-field projection ACK.

New domain/application/repository source stages stable enrollment association,
independent enrollment/email/phone versions and purpose-bound receipts. Pending
UUIDs are staging identifiers, never bank principals. Existing unique external
mapping may reference its established auth UUID; legacy identities cannot be
linked by matching contacts. New subjects cannot allocate V8 projection commands:
the missing identities foreign-key materialization remains unavailable (503).

Projection preparation validates current candidates/receipts, association and
epoch, then reserves epoch/revision and allocates a stable V8 command within
one caller-owned auth transaction. Existing projection helpers follow
to the global verification-control-first, projection-control-second lock order.
No nested public repository transaction or network call belongs under those
locks. ACK attachment compares operation/hash/revision/contact/epoch and current
receipts; only PROJECTED is possible, never ACTIVE. Final auth source has
independent bounded auth/security clearance and focused unit/
PostgreSQL passes. This is disabled-source evidence, not full-check, CI or rollout
completion.

The timing adjustment gives the service ownership of the pre-I/O anchor:
store.admit(writer, Flow) returns auth DB time after admission locking; the service
passes it into Observations.observe(Flow, startedAt). No provider-chosen timestamp
can replace it. After all authority row locks, database clock_timestamp checks
age/future/expiry before mutation and at the final application check before
COMMIT. This is a five-second final-check bound, not a durable-commit guarantee.
The API change has independent focused regression evidence (SEC001 verified);
production observation/Main wiring remains absent.

## Read-only provider evidence

The disabled provider source adds pre-token enrollment observation to the existing
status resource, with no new SPI descriptor or realm activation. Requests contain
six exact server-context binding fields; enrollment results contain seven exact
fields with independent versions; observation replies contain twelve exact fields.
Neither provider attributes nor observations certify receipts or eligibility.

HMAC uses realm/client internal IDs and server root/tab IDs, canonical unpadded
base64url and strict comparison. Lookup requires authenticated subject, allowlisted
client, REQUIRED_ACTIONS phase, active USER action and strict computed lifespan
expiry. Observation does not mutate timestamp/action or generate a code.
The initial required-action challenge renders only; later form POST may call the
bridge after a prior provider request commits. This ordering is required but does
not prove committed-state visibility across provider instances or removal races.

Independent provider source checks have passed. They are not a live provider,
browser or cluster acceptance result. Pinned two-instance committed-session lookup,
expiry/root-timestamp movement, tab/user/session removal and transaction visibility
remain mandatory unrun runtime gates. The final action continues waiting for
ACTIVE; PROJECTED cannot complete banking enrollment.

## Retention and ownership

Flow TTL is bounded by enrollment/provider expiry and ten minutes. Permanent
contact-free context barriers prevent an expired tuple from returning as a new
flow, including after retention/binding-key changes; bounded capacity fails closed.
Command replies expire and bounded cleanup clears response copies; replay metadata
and denial barriers are not ordinary age-out records. Twenty-four-hour enrollment
expiry does not extend lifetime, reset OTP budgets, free phone history or imply
recovery.

Current cleanup removes expired flow rows and clears expired authority-command
responses in bounded batches. It does not erase immutable candidate contacts,
historical reservations or the original V6 enrollment contacts. These owning
verification records must not be confused with short-lived flow/command PII.
No broader candidate/reservation erasure policy or deployed purge guarantee is
claimed. Operational retention/recovery and bounded scheduling/monitoring require
separate review before activation.

## Consequences and gates

Auth Main/routes/configuration do not wire this authority or a production
observation supplier; existing strict verification adapters still need explicit
DTO/state integration. Proof/projection positive factories remain UNAVAILABLE.
No non-banking-principal materialization, ACTIVE state, banking session, notice,
legacy cutoff or provider activation is implemented. The earlier deployed
projection slice remains default-off; this authority source has not been deployed.

Focused auth unit/PostgreSQL checks now pass independently, including populated
upgrade and concurrency/rollback/replay/barrier coverage. Full auth check passed
513 tests without failures, errors or skips. Before later wiring: retain passing affected checks;
prove all entry-point lock order and current-receipt ACK races; run the pinned
provider cross-instance/browser acceptance and linked disposable smoke tests;
obtain independent auth/SSO/security and architecture review on the integrated
revision. No developer handoff substitutes for independent retesting.

Auth owns authority/persistence and provider owner owns read-only context
resolution. Root owns transport/configuration integration, shared documentation,
CI and activation. Legacy recovery/cutoff and future principal materialization
remain separate decisions.
