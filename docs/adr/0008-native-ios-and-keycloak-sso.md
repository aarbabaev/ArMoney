# ADR 0008 — Native iOS and Keycloak SSO

Status: Accepted

ArMoney uses a native SwiftUI application (iOS 18+) and Keycloak as its OpenID
Connect provider. The mobile client is public: authorization code with S256 PKCE,
system-browser authentication, exact redirect URIs and no embedded client secret.
Implicit and resource-owner-password grants are disabled for the mobile client.

## Identity boundary

The app exchanges its Keycloak access token at POST /v1/auth/sso. Auth-service
introspects it at a configured internal endpoint using a confidential client;
it validates issuer, audience, mobile client, activity and expiry before creating
an ArMoney session. The external (issuer, subject) pair maps to a stable local UUID.
Email is never an account-linking key. Existing password identities and sessions
remain valid. SSO-only identities have nullable email; profile data belongs to
user-service. Migrations preserve the original UUID ownership of existing wallets.

Business APIs continue accepting only local opaque sessions. The app stores that
session in device-only Keychain storage; provider access tokens are transient.
There is no refresh-token/offline-access requirement in this slice: an expired
local session starts the browser flow again, which can reuse a Keycloak SSO cookie.

Local logout revokes the current ArMoney session. Browser logout separately ends
the Keycloak browser session. These are not global revocation of all previously
issued ArMoney sessions: back-channel logout/session linkage is a future feature.
Provider account disablement likewise does not immediately revoke an already
issued local session; its maximum lifetime remains 30 minutes.

## Network and operation

An optional explicit Compose overlay adds Keycloak, its own PostgreSQL and a
Caddy HTTPS edge. LAN clients reach only the edge. API requests go to app-gateway;
allowlisted realm login/OIDC/resources requests go to Keycloak. Admin, master realm,
management ports and databases are not published by this overlay. Existing gateway
loopback access is preserved. Caddy's private CA must be explicitly trusted on the
Mac/iPhone; the application never bypasses certificate validation.

Default bindings remain loopback until the operator configures a LAN address and
private-network firewall rule. The existing Compose project and volume names stay
unchanged to preserve data despite the ArMoney product rename.

## Verification

Backend tests cover introspection failures and claims, stable concurrent identity
mapping, legacy compatibility and gateway limits. Disposable CI exercises a real
Keycloak browser login, PKCE rejection, code replay rejection, SSO cookie reuse,
profile/wallet access and local logout. macOS CI compiles and tests the native app
on an available iPhone simulator, preferring iPhone 16 Pro Max. Physical-device
signing, certificate trust and LAN connectivity require validation on the owner's
Mac/iPhone; a Windows source review does not establish that evidence.
