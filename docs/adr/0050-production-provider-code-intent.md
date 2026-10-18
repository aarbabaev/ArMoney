# ADR 0050: Production provider code-intent composition

Status: Proposed; implementation and independent acceptance pending. No activation authorization is implied.

## Context

The durable Auth code-intent and confirmation adapters do not connect themselves
to Keycloak's authorization-code lifecycle. The stock Compose image, unregistered
provider adapters and fixture-only SPI registrations cannot establish production
registration or migrated-owner banking admission. Passing component tests with
these features disabled is not evidence that native sign-in works with them enabled.

## Decision under implementation

Retain standard OIDC authorization-code endpoints, public native clients, exact
redirect URIs and S256 PKCE. Android continues to send only the provider access
token to Gateway's existing SSO exchange. It requests fresh interaction through
`prompt=login` and `max_age=0`; server-side checks remain authoritative.

A production protocol factory owns the code-intent resources. Before releasing
the actual generated authorization code, the protocol retains its immutable
binding through Auth. The authorization-code token-response policy uses the
actual consumed code after Keycloak validates its client, original redirect and
PKCE. Banking proof claims may be signed and released only after durable
confirmation of that same code and event. Uncertain outcomes recover the original
operation rather than generating replacement authority.

Purpose classification uses an exact server-owned issuer/client/original-redirect
manifest. A native banking callback and a migration callback may share a public
client, but they have separate purposes. Mutable client-session notes and caller
purpose parameters cannot classify a consumed code. Unsupported banking response
types fail closed. Only the confirmed executor may produce banking proof claims;
no mapper, refresh path or session note may supply them. Missing policy attachment
may produce an ordinary OIDC token without these claims. Auth's exact managed-pair
route must reject that token without writing a bank session or falling back to
historical admission. This banking-boundary denial requires explicit acceptance,
including policy removal after code issuance.

During enabled managed cutover, the account-migration legacy client is likewise
excluded from new banking admission, as are ordinary local registration/password
login. Its signed OIDC account proof remains usable only by the restricted
migration route. Existing stored sessions are not revoked by this admission gate.

The dedicated public `armoney-migration-account` client is restricted to the exact
account-migration callback and `MIGRATION_ACCOUNT` purpose. It is not a banking,
registration or verification client. Its read-only code observation requires the
enabled production manifest and exact public-client/S256 configuration. The three
existing banking clients retain their managed admission allowlist; introducing
the dedicated client must not widen that allowlist or contact-based identity linking.

Migration account and managed callbacks use fresh signed OIDC account proofs
without requiring positive banking eligibility before FINAL. They cannot carry
native banking proof claims. Refresh and other grants cannot mint or inherit
those claims. Introspection preserves only validated original signed claims; it
does not reconstruct historical proof from current session state.

Registration authority requires a server-only marker recorded after genuine
Keycloak user creation in the original authentication session. Existing-user
login and migration do not acquire registration authority through contact
equality or blanket default required actions. Existing banking sessions retain
their original expiry, logout and security revocation behavior before successful
FINAL; this does not permit minting a new historical banking session. Successful
FINAL atomically cuts off old sessions and preserves the original owner identity.

Completing registration and its verification does not itself prove fresh password
authentication. Registration password action completion can defer or fail credential
persistence. Require a subsequent genuine ordinary password login before native
banking proof; never infer that proof from user creation or a void action return.

The production package must exclude fixture control resources and historical
proof factories. Disabled and unrelated realm/system-client behavior retains
stock Keycloak semantics. Key material is supplied outside version control through
a strict retained key ring, independently validated from other purpose credentials.
The optimized image must build and run with the same `/sso` relative path.

## Consequences and acceptance

Provider source, packaging, realm flows, private Auth transport and trusted browser
ingress require one reviewed configuration graph. Export changes do not update a
running realm automatically. Existing users and persistent data must be preserved;
realm reimport is not a migration strategy.

Required isolated acceptance includes native versus migration purpose separation,
same-client concurrent callbacks, wrong redirect/state/nonce/PKCE, stale cookies,
code replay, lost stage/confirmation responses, node replacement, missing policy
attachment, registration marker provenance, pre-FINAL session continuity and
atomic FINAL/rollback. Include disabled/master/system-client stock behavior and
the production JAR inventory. Native device acceptance and Mailtrap inbox delivery
remain separate evidence. No emulator is started without an explicit owner request.

Deployment and feature activation remain gated on integrated independent review,
exact-head checks and these acceptance results. This ADR describes the intended
composition and must not be read as a deployed capability.
