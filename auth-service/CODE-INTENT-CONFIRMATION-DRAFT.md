# Draft code-intent and confirmation contract

Status: **CONTRACT FROZEN FOR SCOPED IMPLEMENTATION; NOT IMPLEMENTED OR ACTIVATED.**
Date: 2026-10-17 (Asia/Dubai). Scope: existing issues #28 and #36.

The orchestrator approved joint contract freeze on 2026-10-17 after independent
QA and security GO at `0302cd967769a468fa142c6cd2e5cd3e034d976b`.
This contract supersedes the synchronous pre-write positive-status requirement
ONLY for the explicitly scoped managed INTERACTIVE intent/confirmation flow.
Unchanged legacy/historical clauses in LOGIN-PROOF-CONTRACT.md still apply.
The filename retains DRAFT for link compatibility. No existing wire DTO, route,
schema or production factory changes in this documentation/fixture PR.
See [ADR 0043](../docs/adr/0043-code-intent-confirmation.md).

## Initial per-kind scope

The new stage/confirm flow initially supports ONLY managed INTERACTIVE login for
the explicitly configured managed issuer/client scope. A syntactically valid
13-field Write with kind=reuse is rejected by these new operations; preserving
the existing Write shape does not authorize every kind. Every supported
confirmation requires the owned grant, both receipts and exact projection ACK.
No interactive event is created or reused to accommodate cookie reuse. Existing
legacy/reuse paths and their read-only historical metadata remain unchanged;
supporting them through intent confirmation needs a separate explicit policy
freeze and acceptance. Missing classification or ambiguous managed scope denies.
OIDC_CONTINUATION remains distinct from ACTIVE and cannot bypass banking denial.

## Operations and directional authentication

| Operation | Proposed private path | Credential/header | Capability |
| --- | --- | --- | --- |
| stageCodeIntent(writer, Write) | POST /internal/sso-code-intents | SSO_LOGIN_PROOF_WRITE_KEY / X-ArMoney-Login-Proof-Write-Key | Stage only |
| confirmCodeIntent(confirmer, CodeBinding) | POST /internal/sso-code-intents/confirm | SSO_LOGIN_PROOF_CONFIRM_KEY / X-ArMoney-Login-Proof-Confirm-Key | Explicit confirmation only |
| Historical resolve | POST /internal/sso-login-proofs/resolve | SSO_LOGIN_PROOF_RESOLVE_KEY / X-ArMoney-Login-Proof-Resolve-Key | Read existing confirmed proof only |

Credentials are independently provisioned, mutually distinct, printable ASCII
32..256 characters and distinct from provider status, bridge and code HMAC keys.
Authenticated server configuration maps each credential to its actor and permitted
issuer/client scope. No caller-supplied actor header is trusted. Stage credentials
cannot confirm; resolver credentials cannot stage or confirm. Authentication and
bounded durable admission precede parsing-dependent remote work. No new credential
is provisioned by this document; missing configuration stays unavailable.

All destinations are fixed private allowlisted URLs, excluded from public ingress;
no redirects, two-second connect and four-second total exchange/body deadline,
12 KiB maximum request/response, strict duplicate/unknown/wrong-type rejection,
no-store responses and no raw payload, digest, code, token or contact logging.
No inline automatic confirmation retry. Any explicitly retried internal operation
retains its immutable identity and original deadlines.

## Exact stage request and response

The request is exactly the existing 13-field Write schema:

```text
command_id, code_digest, code_key_id,
issuer, subject, client_id, user_session_id, authentication_session_id,
issued_at, expires_at, kind, event_id, password_authenticated_at
```

Retain existing types, canonical HMAC/length-prefix definition, allowlists,
explicit nullable reuse fields, 120-second maximum code validity, original
issued_at tolerance and password-time bounds. Derive only from the generated
code and server-owned flow; never hash a browser code parameter. Stage validates
directional actor, bounded admission, strict payload, immutable hash and original
time bounds. It does not require visibility of the not-yet-committed session.

Exact response (200 for initial commit and valid exact replay):

```text
intent_id: canonical UUID
code_key_id: original allowlisted string
state: literal "STAGED"
issued_at: original RFC3339 UTC integer-second timestamp
expires_at: original RFC3339 UTC integer-second timestamp
```

This response is a noncredential acknowledgement: it contains no proof_id,
eligible flag, event authorization, recipient, banking principal or token. Store
the canonical payload/hash, authenticated actor scope and original times in
separate intent storage; command and code-binding uniqueness compare full payloads.
The staged event UUID is an untrusted candidate identifier, not a created event.
No contact/recipient snapshot is captured. Exact replay cannot renew a lifetime,
replace a candidate, or open a new CREATE admission window at redemption.

### Original admission cutoff versus code expiry

For the first stage commit, Auth DB time must satisfy
issued_at - 30s <= stage_db_now <= issued_at + 30s and stage_db_now < expires_at.
Persist the original successful stage/admission anchor and
fresh_admission_cutoff = issued_at + 30s with the immutable intent. No first stage
or replacement intent is allowed after that cutoff, even if the code remains
unexpired. Exact stage replay after the cutoff may acknowledge the same existing
intent before its original expiry, subject to authentication, exact comparison
and current invalidation; it cannot create a new admission or renew any bound.

Confirmation may occur after fresh_admission_cutoff when the original stage was
timely and the original code, owned grant/receipt/ACK and observation deadlines
are still valid. It consumes the existing admission anchor, never reopens CREATE
or re-applies the first-stage issued_at tolerance as a fresh admission. Code
validity remains at most 120 seconds from the original issued_at. The final
transaction rechecks every applicable original deadline; no 30-second admission
rule extends the code or any shorter authority/evidence deadline.

## Exact confirmation request and response

The request is exactly CodeBinding, retaining the current resolve binding:

```text
code_digest: 64 lowercase hexadecimal characters
code_key_id: allowlisted key ID
issuer, subject, client_id, user_session_id: bounded strings
```

Only the validated authorization-code grant hook may submit it. Keycloak must
first validate the code, client, redirect, expiry, PKCE, user and session. The
original authentication-session identity and intent metadata come from the
immutable stored intent, not unavailable or browser-supplied grant parameters.
The internal confirmation identity is a deterministic 32-byte HMAC using a
separate retention HMAC key and domain `armoney-code-confirmation-v1`. Input is
the length-prefixed authenticated logical actor scope, immutable intent UUID and
canonical full CodeBinding in the field order above. Keep that identity stable
through credential rotation; persist the canonical request hash. Provisioning,
key `SSO_LOGIN_PROOF_RETENTION_HMAC_KEY` is exactly 32 random bytes in canonical
base64, distinct from every credential and code-digest key. Keep the original
key/key ID available until its last tombstone expires; rotation must not change
the identity of an existing intent. Key-ring provisioning, rotation and equality
checks remain disabled configuration gates. Separate directional credentials do not relax
issuer/client allowlists.

Exact 200 response is the existing immutable six-field proof snapshot:

```text
proof_id: canonical UUID
event_id: canonical UUID for interactive; explicit null for reuse
kind: "interactive" | "reuse"
login_notice_eligible: literal boolean
issued_at, expires_at: original immutable RFC3339 UTC integer-second timestamps
```

Interactive confirmation can become eligible only after every owned gate passes;
reuse remains unsupported by the new operations and cannot produce this new
confirmation response. Existing historical reuse metadata still has null event
and false eligibility. Confirmation never emits access
tokens, starts a banking session or enqueues/sends a notice. Notice eligibility is
historical metadata consumed only by a separately validated banking session/notice
transaction. Resolve returns only an already-confirmed snapshot and remains
read-only, with no confirmation, observation fanout, recipient changes or writes.
After suspension or other current invalidation, resolve may still return an
unexpired historical snapshot under its existing read-only contract. Such a
response cannot satisfy current confirmation or authorize token release: the
grant hook must obtain a successful current confirmation before using historical
metadata, and fails closed even if a subsequent/independent resolve is readable.

## Replacement provider observation: exact draft v2

Proposed separate endpoint POST `/realms/armoney/armoney-status/code-session`,
using existing AUTH_TO_SSO_STATUS_KEY / X-ArMoney-Auth-Status-Key. Exact request:
`issuer`, `subject`, `client_id`, `user_session_id` (four strings).
The provider resolves the allowlisted public client to its own internal ID and
checks the actual authenticated client membership of that exact user session.

Exact success response (12 fields; no fallback to the earlier 11-field draft):

```text
issuer, subject, client_id, user_session_id: exact request binding strings
username: bounded string
email, phone_number: canonical strings or explicit null
enabled, user_session_active, client_session_active: literal booleans
observed_at, valid_until: RFC3339 UTC integer-second timestamps
```

The provider captures one observed_at before session checks; its own clock and
trusted realm/client/session models determine time;
the caller supplies none. A positive response requires enabled user, exact
user ownership, valid user session and valid authenticated client session. A
negative observation may invalidate existing authority, never grant it. A disabled
existing user may return enabled=false only with the same exact live session
binding and nonnull future deadline; positive consumers require all three flags
true. Missing/inactive/foreign sessions fail closed without a fabricated deadline.
Missing
objects, uncertainty or unsupported deadline derivation fail closed. Auth rejects
binding/type mismatch, negative flags, absent/null/invalid relative deadline,
and evidence older than its original local observation budget.
Neither the old eight-field response nor the earlier 11-field draft satisfies
this operation. Enrollment status
and its pre-token authentication_session_active field are different contracts.

**Proposed pinned calculation, requiring independent verification:** run both
AuthenticationManager.isSessionValid and isClientSessionValid and use the same
SessionExpirationUtils server-model user/client maximum and idle calculations.
valid_until is the minimum of provider-local observation time + five seconds
and all finite user/client maximum and idle deadlines. Floor milliseconds to
whole seconds; the utility's -1 maximum means unbounded and is excluded from
the finite minimum, never interpreted as a timestamp. Use conservative idle
deadlines without optional 120-second cluster grace. Require exact membership,
both validity checks and a strictly future result. Initially reject unsupported,
offline or transient sessions. Unknown policy or failed derivation rejects;
never substitute token expiry, access-token lifespan or guessed session lifetime.
The observer's deadline is a conservative evidence-use bound, never a provider
lease or distributed revocation guarantee.

### Relative deadline conversion: pending independent proof review

No provider-to-PostgreSQL clock-offset bound is inferred or required by this
proposed relative conversion. Both provider timestamps use the same clock and
are floored to whole seconds. Define delta = valid_until - observed_at in integer
seconds and require 1 <= delta <= 5. The difference of two floors can overstate
the underlying duration by less than one second, so subtract one second when
converting to Auth's DB clock. The provider captures observed_at only after Auth's
original DB start; anchoring the remaining duration at that earlier start is
conservative under the clock-continuity assumptions below.

Auth records immutable DB start before remote I/O and DB receive afterward.
Reject receive < start or receive - start > four seconds. Compute once:
effective_db_deadline = min(start + 5s, start + (delta - 1)s).
After locks and at the final application check, DB now must be >= original start
and strictly < effective_db_deadline. delta=1 leaves no usable window and denies.
No absolute provider timestamp is compared with DB time as synchronization proof.
Equality, an empty window, backward time or uncertainty rejects.
No resampling, polling, inline retry or reread may replace the original start or
renew the observation budget. Fresh negative observations use the same checks.
The final application check is not a durable COMMIT/fsync deadline guarantee.
This proof assumes trustworthy continuously advancing provider/DB clock rates
during the operation, with EQUIVALENT elapsed-second rates, no undetected time
jumps, and the same provider clock for
capture and session deadline calculations. Detected discontinuity/range error
fails closed. Relative conversion removes cross-host offset dependence; it does
not prove absence of clock jumps, unequal rates/drift or concurrent provider
revocation. Continuity alone is insufficient: a slower DB clock can exhaust the
arbitrarily small flooring margin. This DTO cannot detect or establish equivalent
rates. Until that normal-clock rate assumption is accepted at the runtime gate,
retain NO-GO or freeze a quantified conservative rate margin. Independent
security/SSO must validate these assumptions and floor/unit edge cases before runtime activation.

## Owning transaction and replay/invalidation

1. Read the immutable intent and trusted DB admission anchor. Reject missing,
   mismatched, expired or disallowed original admission before remote fanout.
2. Obtain one invocation-local fresh v2 observation outside the transaction.
3. In ONE owning transaction lock verification, projection and proof state in
   deterministic V -> projection -> proof order. Recheck owned identity/mapping,
   grant, both receipts and contact versions, epoch, exact six projection ACK
   fields, recovery state and every original deadline against DB time.
4. Atomically write the V7 proof/event and separate intent-confirmation result.
   Re-read all application deadlines/evidence before commit; failure rolls back
   all local effects. Extract a caller-owned core from writeObserved if needed;
   never nest its existing owning transaction or perform network I/O while locked.
5. Exact replay returns the original IDs/event/expiry only after the same current
   invalidation and freshness gates. Changed command/payload/binding conflicts.
   A concurrent first confirmation has one immutable result/event; neither replay
   nor conflict changes receipts, admission time, recipient or expiry.

Suspension, deletion, contact changes, epoch/projection/recovery mismatch or
negative provider status deny and use the existing owned invalidation protocol;
never silently reverify contacts. Required-action OIDC_CONTINUATION does not mean
ACTIVE. The managed banking marker remains unconditionally denied until its
separate authorized consumer is independently verified. An intent, V7 event or
successful OIDC code exchange alone is not banking authority.

### Exact transaction ownership and lock order

One owner uses the same DSL transaction context and transaction ID for
lockControls in V -> projection -> proof order. The ordered business locks are:
authority; identity/mapping; candidate/receipt/challenge rows sorted by stable
purpose and UUID; projection command/tombstone; intent; stage/confirmation
idempotency keys; event/proof/command rows. No later lock path may acquire an
earlier class. Extract the caller-owned write core rather than call owning
writeObserved from inside this transaction. No nested transaction and no remote
I/O while holding any of these locks.

Before HTTP, commit an opaque admission anchor binding authenticated logical
actor, intent, full code binding/request hash, DB start and authority identity,
epoch and revision. It is not authority or a renewable grant. After observation,
re-read the same authority stamp while locked; never apply a negative observation
to an identity/revision different from the captured stamp.

### Denial commit is distinct from positive-proof rollback

A fresh, authenticated, correctly bound negative observation at the SAME current
authority stamp may suspend the authority monotonically once. It writes no proof,
event, confirmation or optional audit side effects. Return an internal
DENIED_COMMITTED outcome, commit that denial, THEN produce generic HTTP 409
outside the transaction. Already-SUSPENDED state does not advance epoch again.
A changed authority stamp denies without applying old negative facts. Capacity
limits, positive-create admission and unhealthy cleanup MUST NOT prevent this
fresh correctly bound denial from committing, including when creation tables are
full. Such denial creates no intent/proof/event/confirmation/audit rows or aliases
and needs no positive-create capacity. Authenticated bounded execution, current
stamp, freshness and row-lock checks still apply; no exception grants authority. Recheck
observation freshness after the suspension update and before commit; if stale,
roll back only that attempted transaction and report unavailable. Missing, 503,
malformed, mismatched or stale evidence is UNAVAILABLE, not revocation evidence.
Positive proof/event/confirmation writes remain atomic and all roll back on any
failed final owned-evidence/deadline check. These are different outcomes; a
generic thrown proof error must not accidentally undo a required committed denial.

### Capacity, retained identity and bounded cleanup

Shared durable admission uses burst 20 and refill two requests per second per
logical actor; at most 16 configured actors and 16 in-flight operations globally.
New intent plus existing legacy-proof creation share a limit of 1,000 per hour,
charged once per intent; confirmation does not charge a second creation.
Each of intent, stage-command tombstone, confirmation tombstone, proof and proof
command has a 100,000-row capacity. At most four stage-command aliases bind one
intent. Authenticated exact unexpired replay is compared before capacity rejection
and remains usable at capacity; aliases/new payloads cannot bypass the limit.

Retain minimal pseudonymous intent/idempotency tombstones until ORIGINAL code
expiry + seven days. They contain original bounds, hashes and outcome ID, never
raw subject, user/authentication-session identifiers, contacts, redeemable codes,
tokens or canonical request JSON. Retention-HMAC identity is separate from stored
short-lived details; do not retain an authority foreign key that pins personal
data or a permanent marker beyond deletion. The separate detail record contains
event/password time, binding and authority hash without recipient, and is deleted
at original code expiry + five minutes. Missing details before code expiry return
503 and never recreate the intent. V7 historical metadata remains seven days;
recipient/identity fields become NULL after 24 hours independently of pending
notice delivery. These limits do not authorize a new notice worker or erasure
claim during DB outage.

ALL retained payload, binding, authority and actor hashes are keyed HMAC-SHA256
with separate domain labels `armoney-intent-payload-v1`,
`armoney-intent-binding-v1`, `armoney-intent-authority-v1` and
`armoney-intent-actor-v1`, respectively, using the retention key and canonical
length-prefixed inputs. Unkeyed raw hashes are forbidden, including as fallback.
Persist the original retention key ID with each tombstone. A bounded key ring
contains only configured current and not-yet-expired retained keys; lookup uses
the stored original key ID, never an unbounded scan or regenerated identity.
Retire a key only after its last retained tombstone expires. Missing key or
unavailable bounded ring returns 503 and never recreates an intent, alias or
confirmation from the request. Rotation cannot replace original hashes or
extend original expiry. These records remain pseudonymous sensitive data, not
anonymous data; key loss is not evidence of successful cleanup.

Cleanup wakes every 30 seconds, at most ten transactions per wake and 100 rows
per table per transaction, with a one-second lock timeout. It takes the same
control/business lock prefix/order as writers and deletes children before parents;
it must not reverse that order to obtain a purge. Report overdue purge > five
minutes or any table >=80% capacity. Unhealthy cleanup gates NEW positive creation;
fresh correctly bound committed denial is exempt from positive-create caps and
cleanup health gates under the denial rules above;
authenticated exact unexpired replay remains allowed at capacity. No scheduler,
alert provider or runtime setting is enabled here. Cleanup outage, concurrent
confirmation and purged tombstone boundaries require actual PostgreSQL acceptance.

Stage/confirm errors use generic 400 malformed, 401 invalid credential, 404 absent
intent/proof, 409 conflicting/currently denied, 410 expired, 429 bounded capacity
with Retry-After and 503 unavailable/uncertain. No error body reveals bindings or
secrets; no failure becomes synthetic eligible/reuse success. A missing current
key may attempt only the bounded previous-key lookup; mismatch, ambiguity,
unavailability and expiry never fall back.

## Code consumption and token boundaries

Pinned Keycloak removes the single-use code before the TokenResponseContext
policy hook. Confirm explicitly there, then resolve the immutable snapshot before
final token serialization/release. Validate actual token iat/exp and effective
bounded lifetime. No token release on missing/denied/uncertain confirmation.
Auth may commit then lose its response after the code was consumed. Internal
idempotency does not guarantee a repeated `/token` request reaches confirmation.
Never resurrect a code, reinsert cache state or issue a token from invalid replay;
require a fresh authorization flow when the result cannot safely complete.
Orphan intents/confirmed records grant no banking session or notice and expire
without renewal. Cleanup must follow the bounded retention and deduplication
rules above; it must not remove an unexpired confirmation under an active writer.

Refresh STRIPS ALL THREE login-proof claims from both newly issued access and
refresh tokens and performs no intent, confirmation, event or notice creation.
It must not copy the interactive event or eligibility from the old refresh token.
Introspection copies validated
original signed claims only, without confirmation or mutable eligibility lookup.
REUSE event claim is absent (not explicit null), kind is "reuse", eligibility false.

## Required independent acceptance matrix

| Scenario | Required observed result |
| --- | --- |
| Fresh user/new client, PRE pair invisible | Stage succeeds without authority; confirmation only after committed BOTH observation |
| Callback abort or hook 503 | No rollback assumption; no authority from persisted sessions or orphan intent |
| Confirmation commit then response loss | One immutable result; no token/code resurrection, fresh flow if code consumed |
| Stage/confirm exact replay and changed payload | Original time/result retained; conflict cannot overwrite or renew |
| First stage after issued_at+30s, code still valid | Reject new admission; do not stage merely because code expiry is later |
| Timely stage, confirmation after admission cutoff but before all original deadlines | Use original admission without reset; succeed only if all current gates still pass |
| New-flow reuse, legacy or ambiguous classification | Reject without creating an interactive event; existing legacy/reuse paths unchanged |
| Concurrent redemption/confirmation, including lost commit acknowledgement | One code redemption and one immutable result/event; no duplicate notice or token resurrection |
| Disabled/deleted user; missing/foreign/expired client membership | Fail closed; existing invalidation independently verified |
| Stale status, missing/expired deadline, old eight/11-field DTO | Deny without mutation/authority; no fallback |
| Constant cross-host clock offsets, backward DB time or exchange >4s | Offset cancels in relative duration; backward/slow/uncertain operation denies |
| Relative delta=1..5, floor differences, all four minimum deadlines, remember-me and client overrides | Verify exact provider calculation and conservative DB-start conversion |
| Maximum -1 sentinel, floor-to-seconds, equality, idle grace, offline/transient/foreign membership | Reject invalid/unsupported cases; preserve finite idle bounds and exact membership |
| Lock/final check crosses effective_db_deadline | Atomic denial; never renew original DB start |
| Each receipt/contact version/epoch, each of six ACK fields, recovery mismatch | Independently exercise each mismatch; atomic denial/rollback, no stale snapshot acceptance |
| Successful confirmation followed by suspension, then exact replay and historical resolve | Replay/current token release deny; historical metadata cannot bypass invalidation |
| Negative provider observation becomes stale during a blocked lock | No stale positive authorization or uncontrolled repeated invalidation; preserve owned epoch semantics |
| Locks cross code/grant/receipt/ACK expiry or observation budget | Exercise each boundary; final DB-time check rolls back every new local effect |
| Wrong directional credential/public ingress/oversize/duplicate JSON | Reject before unauthorized capability/fanout; no sensitive logs |
| Refresh, introspection, cookie reuse | No new interactive event/confirmation; original-claim and reuse rules preserved |
| Intent cleanup, Auth restart, provider-node change, missing integration | Bounded retention/idempotency preserved; unavailable never grants access |
| Real PostgreSQL lock contention, expiry after inserted rows/final-check trigger | One owning transaction; all positive effects roll back at each final boundary |
| Fresh negative, already suspended, changed authority stamp and stale-after-update negative | Commit valid denial once; no epoch storm/old-fact application; stale attempt alone rolls back |
| Cleanup vs confirmation/replay, capacity full, alias cap and unhealthy purge | Same lock order, no deadlock/unexpired purge or admission bypass; exact replay remains bounded |
| Every creation cap full and unhealthy cleanup, fresh negative at same stamp | Committed denial still succeeds once without new rows/aliases; no epoch storm or authority |
| Each retained hash domain, key rotation, missing original key and raw-hash fallback attempt | Keyed domain isolation and original key ID required; missing key 503, no fallback/recreation |
| Continuous but unequal DB/provider rates, including slower DB | No inferred rate/clock proof; retain runtime gate or quantified margin |
| Detail deletion, minimal tombstone replay, retention expiry and credential rotation | No intent resurrection or lifetime renewal; stable HMAC identity, no retained raw identifiers |
| Refresh carrying each/all three old claims in access and refresh outputs | Strip all three in BOTH output token types; no intent, confirmation, event or notice |

No acceptance row is established by contract freeze. Existing SSO-VIS-001 proves only its recorded
fixture observations; all new production ports, schemas, routes and hooks remain
unimplemented and default-off. Scoped implementation must verify deadline
derivation, clock continuity/equivalent elapsed rates, retention/privacy and actual
transaction/lock ownership against these frozen choices. The runtime clock gate
remains NO-GO unless the stated assumption is explicitly accepted or a quantified
conservative rate margin is approved. Contract GO is not runtime GO, merge
approval, provider registration or feature completion.
