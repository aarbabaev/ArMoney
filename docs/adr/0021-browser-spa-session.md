# ADR 0021: Browser SPA session and durable command separation

Status: accepted for the Angular client implementation; runtime acceptance pending.

## Context

Gateway currently revalidates opaque bearer sessions for every protected request.
Native SSO exchanges a provider access token for a separate 30-minute bank session.
The browser needs its own public OIDC client, exact redirects and safe recovery of
an uncertain transfer across reloads and owner changes.

## Decision

Serve Angular and gateway under the same HTTPS origin. Use a dedicated armoney-web
public client, authorization code with S256 PKCE, strict state/redirect validation
and no offline refresh. Allowed local origins are https://localhost:8443 and
https://localhost:4200; callback and logout paths are /oauth/callback and /oauth/logout.
LAN/custom origins require deliberate exact client configuration, not wildcards.
Auth validates API audience, client_id and matching present azp for web. Native
compatibility for absent azp is preserved. Issuer/subject remain the identity key.

The SPA holds provider and bank credentials only transiently in memory; credentials
are not stored in localStorage, IndexedDB or durable browser storage. A transient
authorization transaction may store state/PKCE material in tab-scoped sessionStorage
until callback consumption. Reload starts a new SSO flow and verifies /v1/auth/me
before exposing owner data. Logout clears memory and attempts server revocation;
provider cookies and other independent bank sessions have separate lifetimes.

Keep the existing bearer gateway boundary rather than add a BFF/cookie session
store and a second session lifecycle. Cookies are not used as bank authentication,
so cross-origin forms cannot authenticate a protected banking action. Same-origin
hosting avoids permissive CORS. CSP, text-only rendering, no unsafe HTML, no remote
scripts and bounded dependencies reduce XSS exposure. XSS can still act with an
active in-memory credential; browser storage is not Android Keystore equivalent.

Store immutable unresolved transfer commands separately, under origin and verified
owner. Persist the original idempotency key, exact payload and attempt marker before
network submission. Use transactional cross-tab coordination and fail closed on
corrupt/unreadable state. Preserve uncertainty across logout, refresh and expiry;
retry only the identical command after the same owner signs in. A client-side
timeout or logout cannot establish payment rejection or completion. Server status
and ledger confirmation remain authoritative. Deleting browser data can destroy
local recovery evidence; users must reconcile history before issuing replacements.

Demo cards use separate owner-scoped local records and clearly describe their demo
status. They contain no payment credentials and imply no issuing or delivery.

## Consequences

Reload requires authentication and cannot restore a durable bearer token. Durable
browser data is accessible to same-origin script and may be evicted; it is not an
encrypted hardware-backed vault. Same-origin security and owner checks cannot
protect against a fully compromised browser profile. Existing backend contracts
and native sessions remain compatible. A future BFF would require a separate ADR
covering cookie CSRF, fixation, rotation and revocation before implementation.
