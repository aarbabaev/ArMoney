# Planned verification projection contract

Status: **DISABLED STORAGE/TRANSPORT SUBSET IMPLEMENTED; POSITIVE AUTHORITY AND ACTIVATION REMAIN PLANNED.**

Baseline: `923a8ee1aae1225505ce7dad6097f22ce7f626c5`, branch
`feat/verification-projection`. This draft closes the next dependency of
registration verification/login proof. It does not enable ACTIVE eligibility,
bank sessions, notices, migration or provider factories. Main source wiring now
constructs false-default projection configuration: user pairs its dedicated route
and exact exemption when enabled; auth creates a worker only when enabled,
starts it after HTTP runtime and closes it on shutdown/error. Deployment flags
remain unchanged/off. Production auth authority is UNAVAILABLE and no HTTP entry
allocates auth commands. Auth cleanup ticks every30seconds when enabled/started;
user cleanup remains an explicit helper without a scheduler. Populated upgrade
and combined synthetic user-wiring specifications have independent focused QA
passes; full affected checks, linked CI/Compose and deployment remain pending.
Review with
[login proof contract](../auth-service/LOGIN-PROOF-CONTRACT.md).

## Authority and version vocabulary

Auth owns identity UUID, verified contact receipts and eligibility epoch. User
owns central phone uniqueness, profile placement and projection CAS. No service
queries another service's database or imports its business classes.

Projection revision is a positive integer independent of contact versions.
Every new epoch-bound projection increments it. Phone contact version is a
positive integer changed only with the phone contact. Email-only epoch changes
preserve phone/version but increment projection revision. Eligibility epoch is
an auth-owned monotonically increasing positive signed 64-bit integer, never
reused/decremented. No projection acknowledgement proves ACTIVE: auth must
separately check current receipts, epoch, mapping and all-entry eligibility.

## Private HTTP and exact DTO

Private auth-to-user POST `/v1/internal/verification-projections`.
Request object contains exactly:

```json
{
  "operation_id": "00000000-0000-4000-8000-000000000001",
  "identity_id": "00000000-0000-4000-8000-000000000002",
  "expected_version": 0,
  "version": 1,
  "canonical_phone": "+971501234567",
  "phone_contact_version": 1,
  "verification_epoch": 1,
  "payload_hash": "64-lowercase-hex-characters"
}
```

The example hash is a placeholder, not a valid request. UUIDs must use canonical
lowercase hyphenated form. canonical_phone must match
`^\+9715[024568][0-9]{7}$`; reject instead of normalizing. JSON numeric fields
must be integral, never strings/floats. expected_version is 0..2147483646;
version equals expected_version+1 and is at most 2147483647.
phone_contact_version is 1..2147483647. verification_epoch is
1..9223372036854775807. Exhaustion fails closed; never wrap.

Return 200 for first commit and retained exact replay, exactly six fields:

```text
operation_id: UUID
identity_id: UUID
version: projection revision
phone_contact_version: integer
verification_epoch: integer
payload_hash: 64 lowercase hexadecimal characters
```

No phone echo, profile ID, ACTIVE state or verification boolean is returned.
Strict JSON rejects duplicate/unknown fields, trailing tokens, nulls and wrong
types. Request and response limits are 12 KiB. All replies use
Cache-Control: no-store. Errors have only a generic error code: 400 malformed,
401 credential rejected, 409 conflict, 410 expired operation replay, 429 admission/
capacity and Retry-After: 60, 503 unavailable. Never include contact/body/SQL/key
material. There is no public/gateway route or bearer-session authorization.

## Canonical hash

payload_hash is lowercase SHA-256 hex over these UTF-8 fields in order:

```text
armoney-verification-projection-v1
operation_id
identity_id
expected_version
version
canonical_phone
phone_contact_version
verification_epoch
```

Encode each field as four-byte unsigned big-endian UTF-8 byte length followed
by bytes; concatenate without separators. UUID strings are canonical, numeric
strings decimal without leading zero, phone already canonical. Exclude
payload_hash. Both adapters independently recompute it. A hash alone authenticates
nothing; the dedicated transport credential establishes writer identity.

## User-owned CAS and phone transaction

Use the primary user database and existing registration advisory transaction lock
`725631904128`, with deterministic identity/claim row locking. Authenticate and
admit before parsing. Map configured credential to a fixed authenticated writer;
ignore/reject caller owner/writer headers as authority.

1. Check authenticated-writer/operation_id replay first. Compare complete original
   canonical payload/hash. Retained exact replay returns its original immutable
   ACK, even after later projection revisions. Changed operation payload is 409.
2. For a new operation, lock current projection. Compare expected_version with
   current projection revision (zero only when no projection exists). Reserve
   unique identity/version and authenticated-writer/operation. Competing operation
   for the same revision is 409. Never overwrite a committed operation.
   New operations MUST strictly increase verification_epoch over stored epoch.
   If phone is unchanged, phone_contact_version MUST equal stored contact version;
   if changed, it MUST strictly increase. CAS revision alone never permits an old
   epoch/contact snapshot or a stale phone restoration. Exact old replay is
   checked first and returns its original ACK without applying these new-op rules.
3. Check central registration_phone_claims and profile_directory, including
   existing legacy ownership. Another identity's phone is 409. Initial revision
   zero does not mean existing claims/directory can be ignored. The same identity
   must have compatible existing ownership; conflicting legacy state is blocked.
4. Atomically update current projection, central phone claim and existing
   directory overlay; persist replay/ACK and tombstone. A changed phone resets
   directory.phone_verified=false; the same nonnull phone preserves its existing
   flag. Initial NULL-to-phone sets false. ACK is not operator attestation and
   never sets true. Nonnull request phone cannot clear a phone to NULL.
   For existing PRIMARY placement, synchronize the physical profiles phone and
   flag in this SAME primary database transaction: changed/NULL phone becomes
   requested phone with verified=false; same phone mirrors the locked directory
   flag. This retires old verified P before replacement Q and releases the V3
   unique_verified_phone constraint consistently. If an initialized PRIMARY
   placement lacks its physical row, or physical/central ownership is conflicting,
   fail closed rather than silently repairing unrelated data.
   No profile/directory creation, profile-ID allocation or relocation occurs.
   Preserve initialized and all immutable placement fields, including an existing
   uninitialized placement. Non-primary shard physical rows are not mutated here;
   central ownership/overlay remains authoritative, with no shard I/O under lock.
5. Roll back all changes on any conflict/error. Existing unique indexes remain
   authoritative under concurrency; translate their violations generically.

The ACK means committed central projection, not creation of a physical shard
profile or banking eligibility. Profile creation later must see the projected
central claim. Existing registerPhone, save, changePhone, lookup, overlay and
operator verification paths must be reviewed/tested against this protocol.
It is NOT assumed that current handlers are safe merely because a central
overlay exists. If any path can restore stale ownership or bypass projection
versions, implementation must stop for an explicit additional owner lease;
never silently weaken CAS or edit unrelated paths. The proposed implementation
lease must include PostgresProfiles.java to fence verifyPendingPhone: acquire
the same registration advisory lock and reject any identity with projection state,
before changing verification flags or audit rows. Thus the old phone-only
operator evidence cannot attest P->Q->P after projection enrollment. Existing
operator attestation remains a legacy facility, never auth eligibility authority.
For registerPhone, verify old P is rejected after Q while current Q is a no-op;
for save, preserve its central re-read after shard I/O and prove it cannot restore
old P. Do not remove unique constraints or immutable placement triggers.

## Auth durable command and ACK tracker

Before sending, auth transactionally persists operation, full canonical payload/
hash, expected revision, current contact version and reserved epoch. One unresolved
command per identity; serialize subsequent desired changes behind it.
States: PENDING, ACKNOWLEDGED, SUPERSEDED, CONFLICT.
Also persist terminal DISABLED_BAD_REQUEST (400), DISABLED_UNAUTHORIZED (401),
EXPIRED_REPLAY (410), and EXPIRED_UNRESOLVED. These states clear worker leases,
disable retries and require explicit reviewed recovery; process restart cannot
silently requeue them. A corrected credential is not automatic retry authority.

Current V6 registration verification does not provide the authoritative bank
identity/contact-version/eligibility-epoch supplier required to create commands.
The production factory MUST remain UNAVAILABLE (503), with no command allocation
or send, until that separately implemented trusted local port exists. Test-only
synthetic authority is not production eligibility and must not be wired to Main.

Send outside database locks. Retry identical bytes/operation across response loss,
restart and two workers. A lost earlier ACK is resolved by exact replay before a
successor is allocated. On ACK, lock local identity/enrollment/tracker and compare
all six ACK fields, current receipts, contact versions and reserved epoch. An
old ACK can remain historical but never acknowledge a new epoch or activate a
changed enrollment. An email-only update from revision r sends r/r+1 with unchanged
phone version and new epoch. Any intervening authority change prevents positive
acceptance and requires a successor command.

Timeout/429/503/malformed response leaves pending; 409 records conflict for
resolution without changing identity authority. A 410 requires explicit recovery
from durable local/user state, never a fresh UUID automatic resubmission.
No timeout is an ACK. No tracker path sets ACTIVE, creates a bank session,
captures a notice or finalizes migration. Production eligibility remains
UNAVAILABLE regardless of tracker ACK.

## Credentials, disabled lifecycle and deadlines

Dedicated environment key AUTH_TO_USER_VERIFICATION_PROJECTION_KEY, header
X-ArMoney-Verification-Projection-Key; a configured fixed writer ID maps from
this credential, never from JSON. Printable ASCII 33..126, 32..256 characters;
distinct from internal business/proof/bridge/status/OTP/encryption/OAuth secrets.
No fallback to INTERNAL_AUTH_KEY. Keys/configuration remain secret-free in Git.

AUTH_VERIFICATION_PROJECTION_ENABLED and USER_VERIFICATION_PROJECTION_ENABLED
default false; only literal true/false accepted. Disabled auth has no worker;
disabled user has no route. Invalid enabled configuration fails startup. Root
has integrated this source lifecycle wiring; activation remains separate.
Missing positive eligibility
adapter stays unavailable even with transport enabled.

Use fixed configured destination, no redirects, two-second connect and four-second
total exchange-and-body deadline, cancellation and bounded body subscription.
Do not rely on a headers-only timeout. No automatic HTTP retry: the durable worker
retries transient outcomes with exponential backoff capped at 60 seconds.
Do not automatically retry 400/401/409/410. Worker uses expiring fenced claims;
stale completion cannot overwrite a successor. Network I/O never holds DB locks.

## Admission, seven-day replay and durable tombstones

Across user instances, each authenticated writer has a durable 120-request/minute
token bucket, burst20. Each instance additionally limits concurrent requests to
16. Admit retries and malformed authenticated bodies before processing; invalid
credentials cannot allocate writer maps or call downstream services.
At most 16 configured writers. No arbitrary forwarded identity/source maps.

Retain full operation payload and immutable ACK seven days. Cap these retained
records at 100000; exact retained replay remains readable when storage is full,
but admission still applies. Capacity rejects new operations with 429 before
state mutation; bounded cleanup can reclaim expired full records.

Every accepted operation also creates an immutable durable non-contact tombstone
keyed by authenticated writer/operation_id, containing original payload hash and
expiry metadata, never identity_id, phone or full body. After full replay expiry,
an operation UUID present in tombstones returns 410 regardless of supplied body;
it never becomes a new command. No post-seven-day immutable ACK promise exists.
Changed retained replay is 409; tombstone-only replay is always generic 410.

Tombstones MUST NOT be deleted on the seven-day schedule or an ordinary restart.
They are retained for the lifetime of this protocol namespace. Cap at 1000000
tombstones; on exhaustion, deny new operations with 429 while preserving retained
replay/expired-operation rejection. No eviction/reuse to regain capacity.
Namespace retirement/archival requires a separately reviewed protocol migration
that rejects the retired namespace, not an assumed finite dedup horizon.
Thus old UUID reuse cannot mutate state after ACK deletion. Tombstone metadata
is operational, non-contact data; do not present it as anonymous user data.

Auth command payload/ACK copies, including phone/identity, have a hard logical
seven-day lifetime from command creation, including unresolved commands. After
deadline stop retry/lookup of full data; erase full payload/ACK in batches100
every30seconds, healthy-service target five minutes, with overdue alerts and no
physical-purge guarantee during outage. Never retain pending contact copies
indefinitely. At most100000 full command rows INCLUDING unresolved/terminal rows;
exhaustion blocks new commands, never evicts live pending commands to make room.

Create durable auth operation tombstones at allocation, retaining operation ID,
payload hash, outcome, expiry and a separately keyed identity-equality digest,
but no raw phone/identity/full ACK. At most1000000, never ordinary eviction.
Unresolved expiry becomes EXPIRED_UNRESOLVED; its identity digest remains a
barrier to automatic successor allocation until explicit durable-state recovery.
ACKNOWLEDGED/SUPERSEDED tombstones likewise prevent old UUID reuse. Independent
current authority/state is not erased merely because historical command PII is
purged. A tombstone hit never recreates payload from a browser value. Namespace
retirement and recovery need separately reviewed protocols; no automatic new-UUID
resubmission resolves ambiguity. Exact retained replay still obeys admission.
Cleanup uses database-time
batches at most 100 rows and operational overdue/capacity monitoring; no network
or service outage implies successful cleanup.

## Proposed disjoint implementation leases

These are proposed, not permissions to write. Root must confirm each exact lease
and migration allocation against latest main before dispatch.

Auth new Java paths below auth-service/src/main/java/com/arman/bank/authservice/:

- domain/VerificationProjection.java
- application/VerificationProjectionStore.java
- infrastructure/PostgresVerificationProjectionStore.java
- infrastructure/HttpVerificationProjection.java
- infrastructure/VerificationProjectionConfiguration.java
- infrastructure/VerificationProjectionWorker.java

Auth schema: auth-service/src/main/resources/db/migration/
V8__verification_projection_commands.sql.

User new Java paths below user-service/src/main/java/com/arman/bank/userservice/:

- domain/VerificationProjection.java
- application/VerificationProjectionStore.java
- infrastructure/PostgresVerificationProjectionStore.java
- infrastructure/VerificationProjectionConfiguration.java
- infrastructure/VerificationProjectionRoutes.java

User schema: user-service/src/main/resources/db/migration/
V6__verification_projections.sql.

Additional required user-owner existing-file lease:
user-service/src/main/java/com/arman/bank/userservice/infrastructure/PostgresProfiles.java
for the projected-identity operator fence and any compatibility fixes proven
necessary by registerPhone/save races. Do not change unrelated profile behavior.

Allocate module-local focused domain/config/HTTP and PostgreSQL specifications
under corresponding src/test/groovy packages. Root owns Main/OpenAPI/ADR/
architecture/CI/shared contract edits. Existing handler changes require explicit
additional user-owner leases after compatibility review. Observed baseline has
auth V1..V7 and user V1..V5; numbers above are tentative until frozen.

## Independent acceptance gates

- Shared canonical encoding/hash vectors, numeric boundaries and noncanonical
  UUID/phone rejection; strict JSON, credential separation and no-store errors.
- Real isolated PostgreSQL: simultaneous exact replay, changed-payload conflicts,
  two identities racing one phone, competing revisions and rollback faults.
- New higher revision with old/equal epoch rejects; same-phone contact-version
  changes reject; changed-phone stale/equal contact version rejects; exact old
  replay still returns original ACK without mutation. P->Q then attempted stale
  P restoration must reject even with a fresh operation/revision.
- Existing PRIMARY verified P replaced by Q resets both physical/directory flags,
  permits later legitimate owner of released P without V3 collision, handles
  initial NULL flags, preserves same-phone flags, immutable placement and
  uninitialized status. Sharded reads use central state without shard mutation.
- Projected identity operator P->Q->P stale attestation rejects under the common
  lock; no operator result grants auth eligibility. registerPhone old-P rejection,
  current-Q no-op and save-after-shard-I/O central re-read are explicit regressions.
- Email-only epoch refresh with unchanged phone version, stale/lost ACK, exact
  response-loss retry after a later revision and two workers/instances/restart.
- Seven-day ACK removal, old UUID same/changed-body rejection from tombstone,
  record/tombstone capacity with exact retained replay and admission enforced.
- Existing registerPhone/save/changePhone/lookup/operator paths racing projection,
  profile creation after projection, primary and sharded directory overlays,
  and stale handlers unable to restore old phone/authority.
- Auth remote I/O outside locks, receipt/epoch mutation during ACK processing,
  fenced worker retry and no ACTIVE/session/notice/migration side effect.
- Disabled Main/route/worker behavior, malformed/slow/oversized upstream response,
  unavailable database, timeouts, cancellation and durable cross-instance budgets.
- Missing trusted auth authority returns503 with zero allocation/send; 400/401/410
  persist terminal disabled state across restart; auth unresolved-capacity and
  seven-day PII expiry preserve tombstone/recovery barriers without infinite retry.

Source review or synthetic fixtures cannot establish these runtime properties.
Affected module checks, real PostgreSQL upgrade/concurrency coverage, linked
disposable acceptance and independent security review must pass on the integrated
revision before any activation decision.
