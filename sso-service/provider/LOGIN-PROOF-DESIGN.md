# Disabled immutable login proof extension

Status: **disabled implementation; not registered, enabled or runtime proven**.
This refines the earlier blocker: pinned Keycloak 26.7.4 has a usable redirect
extension hook, but no direct code-payload extension hook. Cross-service durable
storage follows the root-frozen [auth contract](../../auth-service/LOGIN-PROOF-CONTRACT.md).
No live configuration, realm change or runtime activation is authorized.

SEC-PROOF-001 hardens both configured and generated token lifetimes: the effective
client override must be positive and at most 900 seconds, and the generated
token's actual `iat`/`exp` is checked before proof claims are copied.
SEC-PROOF-002 uses a total asynchronous HTTP exchange/body deadline and explicit
exchange/subscription cancellation; a headers-only JDK request timeout is
insufficient. These fixes have lightweight regression evidence, not runtime
activation approval or independent finding closure.

## Dormant wire compatibility

The disabled implementation preserves all 13 fields of the frozen create
payload in both the outgoing HTTP body and its persisted retry command. Cookie
reuse carries explicit JSON nulls for `event_id` and
`password_authenticated_at`; the pinned Keycloak mapper otherwise omits null
Java values. Retries retain the original command, binding and serialized bytes.
Empty unavailable responses declare JSON media type and `no-store` so the pinned
security-header filter preserves the intended empty 503 response.

On 2026-10-16, the focused wire/retry/filter checks passed (17 tests), followed
by a full provider `check jar --rerun-tasks` pass (53 tests; zero failures,
errors or skips). These are provider unit/package checks; repository CI and
applicable disposable runtime acceptance remain required. This compatibility correction does not establish positive proof-flow
acceptance, callback visibility before redirect release, or activation readiness.
The three extension factories remain unregistered. The independently accepted
read-only observer flow does not prove these dormant protocol hooks.
## Minimal provider hooks

1. Subclass the pinned `OIDCLoginProtocol` and override its public successful
   `buildRedirectUri(builder, authenticationSession, userSession, clientContext)`.
   This runs after the parent persists its authorization code and required
   actions have completed. Call the existing builder, inspect only its generated
   302 Location, and extract one server-created code parameter. Non-consumingly
   read `session.singleUseObjects().get(OAuth2CodeParser.CACHE_KEY_PREFIX + id)`
   to establish the exact code expiry and persisted session/client binding;
   missing or mismatched records withhold the response. Initially permit
   only plain `response_type=code` / query responses. Reject unsupported modes
   when the proof extension is enabled; do not silently classify fresh logins
   as cookie reuse. Never inspect an incoming client code to create proof.
2. Before returning that redirect, synchronously persist an immutable binding
   through auth's private directional bridge. Capture the password event UUID
   from the **current authentication session** only; do not copy it into a
   reusable user/client-session note. No marker means cookie reuse, recorded as
   ineligible for this code. Required actions/auth-owned eligibility still gate
   completion. A missing storage acknowledgement withholds the code response.
3. A `ClientPolicyExecutorProvider` handles only `TokenResponseContext`.
   Keycloak's `AuthorizationCodeGrantType` constructs this context after code,
   expiry, current session/user, client, redirect and PKCE checks. Obtain the
   immutable `parseResult.getCodeData()` and resolve its durable binding with
   exact issuer, subject, client, user-session ID and code digest. Reject missing,
   mismatched, expired or uncertain evidence. Set the three trusted login claims
   on `getAccessTokenResponseBuilder().getAccessToken()` before signing/build.
   This event occurs after refresh-token construction; do not add claims to the
   refresh token. Other grant contexts must not reuse this evidence.
4. Subclass `AccessTokenIntrospectionProvider` and override
   `transformAccessToken(originalToken, userSession)`. Preserve `super` behavior
   and copy only the three strictly validated login claims from the **original
   signed token** into the transformed result. Never reconstruct them from
   user/client notes or today's durable proof lookup. Stock introspection first
   creates a small token and runs mappers; a generic mapper lacks this original
   bearer argument. Missing claims remain ineligible, never inferred.

Factories overriding built-in protocol/introspection defaults are a substantial
pinned-provider integration obligation. Keep the public OIDC protocol identifier,
issuer, subject and clients unchanged. An isolated runtime must prove factory
selection and disabled delegation to built-ins before activation. No factory for
these proposed extensions is currently registered by this module.

## Auth persistence contract

The authoritative request/response schema, code HMAC tuple, separate credentials,
time/retention bounds and auth-local event port are in the linked auth contract.
The following summarizes the original design; the linked contract takes precedence:

- `/internal/sso-login-proofs`: exact-payload idempotent create. Inputs:
  `command_id`, `code_digest`, `event_id` (nullable for cookie reuse), `issuer`,
  `subject`, `client_id`, `user_session_id`, `authentication_session_id`,
  `expires_at`, `kind` (`interactive` or `reuse`). Auth verifies bridge identity,
  configured issuer/client allowlists, expiry bounds and current eligibility;
  `(issuer, code_digest)` is unique and immutable. No raw code/password/token.
- `/internal/sso-login-proofs/resolve`: exact code binding lookup after provider
  code validation. Inputs: code digest and the exact issuer/subject/client/session
  tuple. Return only the immutable event/kind/eligibility snapshot and expiry;
  a mismatch/missing/expired binding never returns eligible. No browser access.

Use an independently provisioned code-digest HMAC key, with domain separation
and a length-prefixed tuple. Digest the immutable code ID and its server-owned
issuer/client/session binding; do not store the redeemable code. Both provider
hooks use exactly the same definition. Frozen key IDs, overlap/expiry bounds,
retention and schema belong in the approved contract before implementation.

Auth persists these records in its own database and handles exact command/payload
replay, uniqueness, concurrent lookups and bounded cleanup. A code already
consumed by Keycloak cannot be retried as a successful exchange. A lost response
after persistence may leave an orphan record; it grants nothing without a valid
unconsumed provider code and expires. Withholding a redirect on uncertain proof
storage is fail-closed, not a cross-database rollback claim. SMTP notice delivery
stays independently retryable and must not determine authentication success.

## Isolation and required proof

- Two fresh tabs for one client/user produce different code records; a later
  cookie authorization must not overwrite an earlier password code's event.
- A new real password proof gets a new UUID; one flow's code and repeated bank
  exchange retain its immutable event, with auth outbox uniqueness suppressing
  duplicate notices. Failed credentials create no record or eligible claim.
- Refresh and all other grants cannot carry an earlier interactive eligibility
  snapshot into a new access token. Explicitly test refresh-token claim copying.
- Introspection of token A before and after password login B yields A's original
  login event/kind/eligibility. Current eligibility/contact enforcement is a
  separate auth check; it does not rewrite historical login-event identity.
- Run two provider instances, auth restart/storage outage, code response loss,
  expiry boundary, duplicate writes/conflicts, concurrent-tab code exchange and
  code replay. Test unsupported response modes and default-factory selection.
- Verify every native/browser client, unchanged S256/state/nonce behavior and
  that private proof/status resources cannot be reached through public ingress.

No compilation or synthetic transport unit test establishes these runtime
properties. The extension must stay disabled until independent acceptance.

## Pinned source pointers

- [Successful redirect extension](https://github.com/keycloak/keycloak/blob/26.7.4/services/src/main/java/org/keycloak/protocol/oidc/OIDCLoginProtocol.java)
- [Code persistence/parse and single-use removal](https://github.com/keycloak/keycloak/blob/26.7.4/services/src/main/java/org/keycloak/protocol/oidc/utils/OAuth2CodeParser.java)
- [Validated authorization-code exchange](https://github.com/keycloak/keycloak/blob/26.7.4/services/src/main/java/org/keycloak/protocol/oidc/grants/AuthorizationCodeGrantType.java)
- [Token generation and policy event ordering](https://github.com/keycloak/keycloak/blob/26.7.4/services/src/main/java/org/keycloak/protocol/oidc/grants/OAuth2GrantTypeBase.java)
- [TokenResponseContext](https://github.com/keycloak/keycloak/blob/26.7.4/services/src/main/java/org/keycloak/services/clientpolicy/context/TokenResponseContext.java)
- [Introspection transformation](https://github.com/keycloak/keycloak/blob/26.7.4/services/src/main/java/org/keycloak/protocol/oidc/AccessTokenIntrospectionProvider.java)

## First-login transaction visibility gate (SSO-VIS-001)

Pinned Keycloak 26.7.4 stages newly attached persistent user and authenticated
client sessions in the login request transaction. The synchronous proof hook
awaits its callback before returning the redirect; request close commits later.
Source inspection therefore identifies a circular dependency if that callback
requires a separate node to observe the new session before returning. LOGIN
events alone do not establish a commit boundary. A pre-existing cookie session
does not prove visibility of a fresh client attachment. HTTP 503 does not prove
that the request transaction rolled back. Actual cross-node confirmation remains
unrun until the dedicated experiment completes.

The disposable-only `compose.sso-session-visibility.yaml` and
`scripts/run-sso-session-visibility.sh` exercise a bounded test login hook. A
dedicated control key authenticates strict bounded requests; a signed immutable
tuple binds realm, subject, user session, external/internal client, nonce and
expiry. The held request shares no Keycloak session/model objects with control
threads. Before release, visibility is diagnostic only. After a successful
completed OIDC response, node B must see both the user and authenticated client
session. Hook failure records persistence outcomes without assuming rollback.
No code is redeemed and no banking, financial or mail operation runs.

Only `Dockerfile.visibility` registers the test hook/resource inside the fixture
JAR. Production factories, provider packaging and the existing observer image
remain unchanged. Run only under the approved disposable CI daemon and exclusive
heavy-build lease; the wrapper owns its random project and unique image cleanup.
This experiment grants no proof authority, activation or production deployment.
