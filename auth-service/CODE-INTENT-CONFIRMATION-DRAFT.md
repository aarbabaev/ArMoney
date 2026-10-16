# Draft code-intent and confirmation contract

Status: **UNFROZEN PROPOSAL; NO IMPLEMENTATION OR ACTIVATION AUTHORIZATION.**
Date: 2026-10-17 (Asia/Dubai). Scope: existing issues #28 and #36.

This is a proposed successor to the synchronous pre-write positive-status
requirement in LOGIN-PROOF-CONTRACT.md. That contract remains authoritative until
the orchestrator jointly freezes this draft with Auth/SSO and independent QA and
security. No existing wire DTO, route, schema or production factory changes here.
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
The internal confirmation identity is deterministic from authenticated confirmer
scope plus the immutable intent ID and full CodeBinding; persist its canonical
hash. Separate directional credentials do not relax issuer/client allowlists.

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

Exact success response (11 fields):

```text
issuer, subject, client_id, user_session_id: exact request binding strings
username: bounded string
email, phone_number: canonical strings or explicit null
enabled, user_session_active, client_session_active: literal booleans
valid_until: RFC3339 UTC integer-second timestamp
```

The provider's own clock and trusted realm/client/session models determine time;
the caller supplies none. A positive response requires enabled user, exact
user ownership, valid user session and valid authenticated client session. A
negative observation may invalidate existing authority, never grant it. A disabled
existing user may return enabled=false only with the same exact live session
binding and nonnull future deadline; positive consumers require all three flags
true. Missing/inactive/foreign sessions fail closed without a fabricated deadline.
Missing
objects, uncertainty or unsupported deadline derivation fail closed. Auth rejects
binding/type mismatch, negative flags, absent/null/nonfuture deadline, excessive
clock skew, and evidence older than its original local observation budget.
The old eight-field response MUST NOT satisfy this operation. Enrollment status
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
lease or distributed revocation guarantee. Auth also records DB observation start
before I/O; after locks and at the final application check, DB now must be no
earlier than that start and strictly before both start+5s and valid_until.

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
without renewal. Cleanup must be bounded and preserve confirmed-proof retention
and notice deduplication; exact scheduling/retention is a freeze gate.

Refresh performs no intent, confirmation or event creation. Its claim preservation
policy must be frozen separately before activation. Introspection copies validated
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
| Stale status, clock skew, missing/expired deadline, old eight-field DTO | Deny without mutation/authority; no fallback |
| Each receipt/contact version/epoch, each of six ACK fields, recovery mismatch | Independently exercise each mismatch; atomic denial/rollback, no stale snapshot acceptance |
| Successful confirmation followed by suspension, then exact replay and historical resolve | Replay/current token release deny; historical metadata cannot bypass invalidation |
| Negative provider observation becomes stale during a blocked lock | No stale positive authorization or uncontrolled repeated invalidation; preserve owned epoch semantics |
| Locks cross code/grant/receipt/ACK expiry or observation budget | Exercise each boundary; final DB-time check rolls back every new local effect |
| Wrong directional credential/public ingress/oversize/duplicate JSON | Reject before unauthorized capability/fanout; no sensitive logs |
| Refresh, introspection, cookie reuse | No new interactive event/confirmation; original-claim and reuse rules preserved |
| Intent cleanup, Auth restart, provider-node change, missing integration | Bounded retention/idempotency preserved; unavailable never grants access |

No row is established by this draft. Existing SSO-VIS-001 proves only its recorded
fixture observations; all new production ports, schemas, routes and hooks remain
unimplemented and default-off. Joint freeze must resolve deadline derivation,
cleanup retention/scheduling, actual transaction ownership and refresh preservation
before a separately scoped implementation assignment.
