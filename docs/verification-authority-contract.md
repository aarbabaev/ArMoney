# Planned verification authority contract

Status: **DISABLED STAGING IMPLEMENTED WITH INDEPENDENT FOCUSED EVIDENCE; NO POSITIVE AUTHORITY OR ACTIVATION.**

Baseline main: `2d004340bc103c95c2be4093a6adb074f6a32ed5`.
This is the next prerequisite of #28/#36/#37, not completion of registration,
banking eligibility, migration, login proof or notices. It extends neither
current HTTP DTOs nor production configuration by itself. Read the
[projection contract](verification-projection-contract.md) and
[proof contract](../auth-service/LOGIN-PROOF-CONTRACT.md).

## Actual source and known gaps

Current verification enrollment has one binding_digest and one contact_version
per issuer/subject. New tabs/clients/relogin can fail that binding check. Result
version/emailContactVersion/phoneContactVersion alias the same integer. The
store's two-receipt completion counts that shared version. These are source
constraints to replace explicitly, not evidence of independent contact authority.
VerificationRoutes.safeState rejects proposed PROJECTED/SUSPENDED states; its
strict request parser also rejects the proposed expanded binding fields.

V8 projection storage supports durable commands and local authority callbacks, but
its production factory remains UNAVAILABLE. allocate currently owns its own
transaction; a new caller-owned transaction helper is required. No V6 row supplies
a trusted bank identity/eligibility epoch. Provider's existing post-token status
requires user_session_id and cannot validate a pre-token authentication flow.
The enrollment-status provider endpoint below is a proposal, not current runtime
behavior. Its pinned Keycloak lookup/cluster/expiry visibility is unproven.

## Stable association and non-banking state

Create one stable enrollment per exact trusted issuer/subject. Its identity
association is server-owned, never selected by an incoming identity/profile ID.
For an existing unique external mapping, reference its existing auth UUID without
changing mapping ownership. For a genuinely new subject with no legacy conflict,
reserve a pending UUID in V9 STAGING ONLY, never in identities/external_identities
or as a banking principal. New-subject projection returns503 without allocation
until separately reviewed non-banking-principal materialization AND all credential/
session gates exist. Null credentials alone are NOT a safe eligibility barrier.
No materialization, legacy association backfill or positive supplier is authorized
here. V8's identities foreign key is preserved, not bypassed.

Never find/link an unmapped legacy UUID through email/phone equality. Existing
claim/mapping conflicts produce BLOCKED_MIGRATION. Legacy association needs the
separate dual-proof migration contract; this slice neither activates migration
nor chooses its cutoff/recovery policy.

V9 authority states are exactly PENDING_CONTACTS, PROJECTION_PENDING, PROJECTED,
SUSPENDED, BLOCKED_MIGRATION. EXCLUDE ACTIVE. PROJECTED means matching receipts
and projection ACK only; it remains bank-ineligible. An association/contact/epoch
conflict suspends or blocks, never auto-repairs identity ownership. Existing
verification state labels are historical; adapters need explicit translation,
not a permissive string passthrough. Enrollment/status response is EXACTLY seven
fields: enrollment_id, version, state, email_contact_version,
phone_contact_version, email_verified, phone_verified. All three version fields
are independent positive integers. Do not expose verification_epoch or invent an
eighth field. Explicitly translate PROJECTED/SUSPENDED; ACTIVE is denied, never
advertised. Provider final phone action still waits for ACTIVE, so PROJECTED
cannot complete banking enrollment in this slice.

Existing V6 enrollment expires24hours after creation. Expiry denies unavailable
without extension, implicit resume/recreation, abuse-budget reset or phone-claim
release. New flow authorization cannot resurrect expired enrollment. Recovery
and enforcement cutoff are later owner decisions, not blockers to this safe-off
staging slice.

## Immutable candidates and independent receipts

Store immutable email/phone candidate rows, each with purpose, own monotonically
increasing contact version, canonical value, enrollment and creation time.
Current email_candidate_id and phone_candidate_id pointers identify authoritative
current candidates. Initial versions are one. Same canonical value is idempotent;
a changed value creates the next purpose-specific version and advances only that
pointer. Never mutate an old candidate or reuse its version. Keep canonical UAE
and strict email validation; never accept an arbitrary destination at issue time.

Issue/consume binds enrollment, purpose, exact candidate/version and authorized
flow. A receipt uniquely references that immutable candidate and challenge.
Changing phone does not erase or reissue a valid unchanged-email receipt. Receipt
booleans derive independently from current pointers, not provider verified flags
or a count of two rows sharing one version. Enrollment version is an independent
monotonic revision for response concurrency; it does not alias either contact
version. New result DTO must carry all three separately.

Preserve existing cryptographic code/delivery separation, five-minute challenge,
five attempts, 60-second resend cooldown and atomic 3/hour/10/day purpose budgets.
New flow bindings never reset enrollment/purpose/contact abuse history. Supersede
old pending challenges on candidate replacement; stale codes cannot produce a
current receipt. Candidate values/OTP/body content never enter logs.

## Proposed pre-token flow authorization

Preserve four auth enrollment/status/issue/consume paths. Common request context
becomes exactly six server-derived fields, in addition to operation-specific
fields:

```text
issuer
subject
client_id
provider_root_session_id
provider_tab_id
authentication_session_id
```

authentication_session_id is the existing opaque HMAC of server realm/root/tab/
client context, never a browser form identifier. Auth must not trust the other
five strings merely because a browser supplied them. The authenticated provider
bridge constructs every field from protected server context after authentication.
Keep X-ArMoney-SSO-Bridge-Key / SSO_VERIFICATION_BRIDGE_KEY, with strict configured
issuer/client allowlists. Do not add client-controlled identity/contact authority.

Binding HMAC v1 is exactly 32 HMAC-SHA256 bytes encoded as canonical43-character
unpadded base64url. Key bytes are UTF-8 bytes of the configured binding key.
Message is ASCII bytes of "armoney-provider-binding-v1" immediately followed by
four-byte unsigned big-endian UTF-8 length-prefixed fields: realm INTERNAL ID,
root.getId(), authentication-session tab ID, client INTERNAL ID, in that order.
The public client_id resolves an enabled standard-flow allowlisted client; it
must not replace its internal ID in HMAC input. Never use cookie aliases, user
attributes or mutable notes as substitutes. Compare HMAC bytes in constant time;
require exact issuer/subject and strict username/canonical-phone validation.

Proposed private provider POST
`/realms/armoney/armoney-status/enrollment` uses existing independent
AUTH_TO_SSO_STATUS_KEY / X-ArMoney-Auth-Status-Key. Request is exactly the six-field
tuple above. Provider resolves the actual current root/tab/client authentication
session; authenticated user must equal subject, issuer/client must be allowlisted,
session must be unexpired and recomputed HMAC must match. Response is exactly the
same tuple plus username, email, phone_number, enabled,
authentication_session_active, authentication_session_expires_at (UTC timestamp).
phone_number must already be canonical. It returns no verified flags, receipt, identity ID,
epoch or ACTIVE. Existing three-field post-token status is unchanged.
These are exactly12 response fields (six echoed binding fields plus six observed
fields). Both request and response reject unknown/duplicate fields, nulls, wrong
types and noncanonical identifiers/phone/HMAC/timestamps. No implied DTO extension.

Pinned Keycloak 26.7.4 expiry proposal: require authentication-session action
REQUIRED_ACTIONS, positive realmAccessCodeLifespanUserAction, isActionActive(USER),
and strict provider now < deadline. deadline is the minimum of
root.timestamp + max(realm login lifespan, USER-action lifespan, access-code
lifespan) and root.timestamp + USER-action lifespan. Reject invalid/future root
timestamps, nonpositive USER lifespan and arithmetic overflow. Derive
authentication_session_expires_at from that bound. The lookup
getAuthenticationSessionByIdAndClient does not validate expiry; the broader
SessionExpiration maximum of login/user-action/code lifespans is insufficient.
This observation is read-only: never refresh root timestamp or mutate session
state to make evidence appear current; never setTimestamp, change action or
generate a code. Another tab may legitimately advance root.timestamp, but auth's
original flow TTL remains immutable and cannot extend on retry.
Cached API/source review supports this
proposal but is not runtime clearance.

Private HTTP can observe only previously committed provider state. The first
requiredActionChallenge renders only and performs NO authority RPC. The first
bridge call is a subsequent form POST after the preceding provider request has
committed. Missing/stale root, tab, authenticated user or required-action phase
returns503 and denies that request; retry requires a new provider request.
Never fall back to caller-local context or uncommitted session objects. Actual
two-instance transaction visibility remains an unproven runtime acceptance gate.
Challenge-render/subsequent-POST sequencing is required but does not itself prove
cluster visibility, session-removal race behavior or successful private lookup.

Perform remote observation before local locks; fixed destination, no redirects,
two-second connect/four-second total exchange/body deadline and 12 KiB cap.
The service owns the pre-I/O anchor: store.admit(writer, Flow) returns an auth
database Instant sampled AFTER admission locks/checks, before returning to the
service. Service passes that exact trusted value to
Observations.observe(Flow, Instant startedAt). The observation supplier/provider
cannot choose or refresh it. This API adjustment is assigned for implementation
and has independent focused source/security and timing-regression verification;
there is no production observation supplier or Main wiring.
After ALL row locks, sample clock_timestamp again before mutation and at the
final application check before COMMIT. Evidence age must be zero through five
seconds from the trusted anchor, with provider/flow/enrollment expiry still
strictly in the future; any future timestamp, stale age or expiry fails closed.
At this final locked check, exact tuple/contacts must
match, enabled and authentication_session_active must be literal true.
This bounds a check, not durable COMMIT or atomic provider revocation. No network
inside a local transaction. Missing/unavailable/mismatched evidence denies;
there is no fallback to post-token status, email equality, mutable notes or
positive synthetic evidence.

Candidate contacts come only from this authenticated current provider observation.
An incoming enrollment email/phone must exactly match the observed canonical
values; auth never takes them as independent authority. A status contact change
invalidates stale flow commands; candidate replacement requires an explicit
idempotent enrollment command, not a silent status-read mutation.

## Multiple bounded flow bindings

Associate many authorized flows with the stable enrollment; each exact six-field
tuple gets an immutable flow row, digest, issued_at and expires_at. Auth DB TTL
is min(provider authentication_session_expires_at, enrollment expiry,
auth database creation time + ten minutes). Reject expired/future-invalid bounds;
never renew beyond provider expiry. Same-flow exact retries never extend TTL.
Never renew or reissue the SAME expired six-field tuple, even after a fresh
observation. A new flow requires a new server root/tab context and its new HMAC,
then a successful independent observation. An explicit new generation field
would require a version2 protocol and is outside this minimal contract.
Preserve operation UUID/replay tombstones; expiry cannot turn an old command
into a new operation.
Maintain a durable contact-free denial barrier for each admitted six-field tuple,
using a separately keyed canonical tuple digest plus a stable context fingerprint:
SHA-256 over length-prefixed domain "armoney-flow-denial-v1", issuer, subject,
client_id, provider_root_session_id and provider_tab_id, excluding the rotatable
authentication_session_id HMAC. Retain protocol namespace and expiry/outcome metadata, no contact
or raw provider identifiers. Barrier is permanent for this protocol namespace,
not aged out after seven days or provider session expiry; cap40000 barriers and
deny new bindings on exhaustion. Never evict to regain admission. Retention-key
rotation must preserve lookup of all existing barriers; key loss/unknown rotation
state fails closed, never treats a tuple as unseen. Binding-HMAC rotation cannot
bypass the stable context fingerprint either. Reauthorization needs new
server root/tab and HMAC, not a renamed writer/key or historical success reply.

At most four live flows per enrollment across all clients and 40000 live/recent
flow rows globally. Keep 20 new flows/hour per issuer/subject and1000 globally.
At most16 configured bridge writers and16 concurrent requests per instance;
durable per-writer admission120/minute burst20 across instances occurs before
provider fanout. One status call per admitted operation, no inline retry.
Capacity is checked before provider fanout and denies429 rather than evicts a
live binding; fresh observation alone does not reserve a slot across races, so
recheck capacity under the final transaction lock. Expired rows grant nothing;
cleanup is bounded100-row batches and must not delete command replay protection.

A flow authorizes only its exact enrollment and tuple. Tabs/clients may share
current receipts through the stable enrollment after independent authorization;
they cannot replay one another's issue/consume commands or authorize another
issuer/subject. Scope command replay to authenticated writer, issuer/subject,
flow digest and command UUID, comparing full canonical operation payload hash.
Retain full replay responses seven days, then contact-free permanent operation
UUID tombstones (cap1000000, exhaustion denies new operations). After response
expiry return expired rather than reexecute. Check live authorized flow before
returning any historical success as current-session evidence; expired tuple is
denied even if its old command once succeeded. A new browser session cannot submit an old flow identifier
to recover access. Binding-key rotation requires reauthorization; never silently
rebind a row. Lost enrollment response retries the same command. Unknown provider
session, expired evidence or lost trusted recovery context fails closed.

Flows provide neither a verified contact nor identity association. Expiring one
flow does not delete enrollment, release another identity's phone or invalidate
unchanged receipts; every new operation still needs a currently authorized flow.

## One auth transaction: reserve epoch and allocate projection

Proposed owning service accepts an already verified immutable observation, then
opens ONE auth DB transaction. Frozen lock order is:

1. verification_control singleton;
2. auth_verification_projection_control singleton;
3. identity association/enrollment in stable UUID order;
4. flow/current candidate/receipt rows in stable purpose/version order;
5. projection command/tombstone rows.

This global order applies to EVERY PostgresVerificationProjectionStore entry:
allocate, finish, claim, admission, status and cleanup, and every
PostgresVerificationStore writer/receipt transaction. Read/status paths needing
only control state may stop after their required prefix; none may first acquire
projection control then later verification control. Caller-owned helper asserts
required locks/context and neither reacquires public transactional APIs nor opens
a nested transaction. Refactor worker/cleanup/admission paths under explicit
leases, not just allocation, before enabling any authority supplier.

All allocation, ACK attachment and candidate/receipt mutations participating in
this protocol must follow that order; existing adapters require explicit leases
to remove conflicting lock order. Never call a public repository method that
opens a nested receipt/projection transaction while holding these locks.

Validate current association, flow, independent current receipts and canonical
phone reservation. Reserve strictly increasing eligibility epoch and independent
projection revision, then generate and persist one stable operation/payload/hash
and V8 command in the SAME transaction. Use a specifically leased
PostgresVerificationProjectionStore.allocateInTransaction(DSLContext, ...)
helper; it must not open/commit another transaction or perform network I/O.
A failure rolls back epoch reservation, authority state and command together.
Exact retry finds the same command; no fresh UUID after uncertain commit.

Any authority-affecting contact/receipt/association/suspension change advances
epoch and makes earlier ACKs historical. An email-only change preserves phone
version but reserves new projection revision and epoch. Existing unresolved
command/recovery barriers still prohibit unsafe successor allocation.
Only after commit may the existing fenced worker send to user-service.

## ACK attachment and authority limit

Process ACK in one caller-owned auth transaction using the same control-first
lock order. Compare all six fields: operation_id, identity_id, projection revision,
phone_contact_version, verification_epoch and payload_hash. Revalidate the
association, current independent receipts and candidate pointers, reserved epoch
and latest operation. Exact successful replay is idempotent. Wrong/stale/partial/
lost ACK cannot attach a different epoch or resurrect replaced contacts.

Matching ACK transitions authority to PROJECTED only. Missing authority or
foreign-key materialization must return unavailable; no ACTIVE, proof eligibility,
bank session, notice, provider success or migration-completion side effect.
Both proof and projection production positive factories stay UNAVAILABLE until
separate all-entry authority/activation work is reviewed.

## Proposed implementation leases, not permission to write

New auth Java domain/IdentityVerification.java,
application/IdentityVerificationStore.java,
application/IdentityVerificationService.java,
infrastructure/PostgresIdentityVerificationStore.java, and append-only
V9__identity_verification_authority.sql (verify next unused number before freeze).

Explicit existing auth leases are required for VerificationService,
PostgresVerificationStore, Verification.Result/domain types and
PostgresVerificationProjectionStore transaction-bound allocation/ACK helpers.
VerificationRoutes request/result/safeState adapter changes require a separate
explicit lease after transport agreement; current Main registers none of these
verification bridge routes. Do not patch Main or SSO implicitly.

SSO owner must separately own the enrollment status subpath, exact binding DTO,
session lookup/expiry and provider tests; no new SPI is implied. Root owns shared
contracts, OpenAPI, ADR, architecture, CI and activation. Existing migrations are
append-only and no legacy identity/contact backfill is authorized.

## Acceptance before integration or activation

Real isolated PostgreSQL: independent email/phone versions and receipt reuse,
replacement vs consume, duplicate/reordered commands, two tabs/clients racing,
new-flow budgets not resetting OTP limits, association/phone conflicts, exact
rollback between epoch reservation and V8 insertion, lost commit/ACK replay,
email-only projection update, stale ACK and contact/epoch change during attachment,
deterministic lock order/deadlock retry, recovery barriers and populated V8-to-V9
upgrade without identity verification backfill.

Pinned provider/browser: authenticated pre-token root/tab/client lookup across
two instances, session expiry/removal, tuple/HMAC/client/subject mismatch,
contacts changing during observation, transaction visibility and no post-token
bootstrap dependency. Synthetic callbacks alone cannot prove these properties.

Route/config: strict expanded DTO, separate result versions, new safe state
allowlist, default-off/no Main exposure, unavailable factories and no ACTIVE/
bank-session/notice output. Independent auth/security/SSO review must precede
implementation; affected checks/linked disposable acceptance and independent
retest precede any later deployment decision.

## Decisions still requiring review

Final disabled auth source has independent bounded source/security clearance and
focused unit/PostgreSQL passes; provider source checks are independently clear.
Full auth check passed 513 tests without failures, errors or skips; published-head CI and deployment
remain pending. No new observation supplier, routes/config/Main authority wiring,
principal materialization or positive ACTIVE/session/notice behavior is supplied.
Runtime gates still
include pinned two-instance committed-state lookup, required-action/USER expiry,
root timestamp movement, tab/user/session removal races and strict canonical HMAC
agreement. No source/API inspection establishes those results.

Lock-order regression must race allocation/ACK finish with claim/admit/status/
cleanup and candidate/receipt writes, including rollback/retry after deadlock and
transaction loss. Barrier tests must cover expired-tuple historical replay,
retention/key rotation, cap exhaustion and enrollment24hour expiry without resume,
new-subject503/no identities insertion and independent seven-field result versions.

Resolved review IDs: AUTH-V9-001 staging-only UUID/no materialization; AUTH-V9-002
exact seven fields/independent versions/explicit states; AUTH-V9-003 all-entry
control-first lock order; AUTH-V9-004 permanent expired-binding deny barriers;
AUTH-V9-005 hard existing enrollment expiry. SSO-AUTH001 pins USER/action expiry;
SSO-AUTH002 requires committed-state sequencing without runtime-proof claims;
SSO-AUTH003 prohibits expired tuple renewal and retains permanent barriers;
SSO004 freezes seven-field auth/twelve-field observation DTOs; SSO005 freezes
canonical HMAC/internal-ID encoding. These are proposed contract resolutions,
not independently verified implementation fixes.

Owner recovery/cutoff decisions and future non-banking-principal materialization
remain out of scope, not blockers to staging-only safe-off implementation.
Positive provider/all-entry banking authority remains unavailable.
