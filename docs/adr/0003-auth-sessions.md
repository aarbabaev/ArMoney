# ADR 0003 — Identity and opaque sessions

Status: Accepted for local M1 development

Current-status note: This records the password-session slice. [ADR 0008](0008-native-ios-and-keycloak-sso.md) adds optional SSO and a LAN HTTPS edge while preserving local opaque sessions.

Implement registration, login, current identity and logout in auth-service.
Gateway forwards a fixed route allowlist; it never accepts client-supplied user IDs
as authentication. No other service gains access to auth tables.

## Credentials and sessions

Use Bouncy Castle Argon2id v19 with 19 MiB memory, 2 iterations, parallelism 1,
16-byte random salts and 32-byte hashes, encoded in PHC form. This follows the
[OWASP password storage baseline](https://cheatsheetseries.owasp.org/cheatsheets/Password_Storage_Cheat_Sheet.html).
Application limits hashing to two concurrent requests. Passwords have at least
15 Unicode code points and at most 128 UTF-16 code units, preserving whitespace.
Email comparison is explicitly ASCII case-insensitive after trimming, max 254.
Email ownership is NOT verified by registration.

Sessions use 32 CSPRNG bytes encoded as base64url. Only SHA-256 token digests
are persisted. Thirty-minute absolute expiry and server-side deletion on logout
make revocation immediate; tokens survive application restarts until expiry.
No cookies, refresh tokens or JWT signing keys in this slice.

Expired session rows are removed on new login. Attempt rows are removed after
15 minutes on a subsequent auth attempt. An idle database can retain expired rows;
expiry checks still reject the sessions. Add scheduled retention before production.

## Abuse and failure behavior

Registration returns the same 202 for a new or existing email, never overwrites
credentials. Unknown and wrong-password login both perform a password hash and
return the same 401. PostgreSQL atomically limits normalized email attempts to
10 per 15 minutes (registration and login combined), including successful ones.
Gateway also caps registration/login traffic at 60 per minute per instance and
16 concurrent proxied requests. This intentionally simple global cap can cause
cross-user contention, resets on gateway restart, and is not a production abuse
defense. Account limits can be abused for temporary denial of service; plan
risk-based, per-client and per-account controls with email verification/MFA.

Auth HTTP checks a configured gateway service key using constant-time comparison.
Gateway overwrites X-Service-Key and never forwards X-User-Id or arbitrary headers.
Missing configuration prevents startup. No request bodies or tokens are logged.
Client identity endpoints always resolve identity from the stored session.
Maximum request body is 4096 bytes, redirects are disabled and upstream HTTP
has connect/request timeouts. Auth outages return 503, never unauthenticated access.

## Limits

The current deployment binds public access to localhost. HTTP inside Compose and one shared
service key are interim choices; use TLS/mTLS or a managed workload identity
before remote deployment. Password recovery, email verification, MFA, breached
password checks, audit events, key rotation, production roles and session policy
are separate work. User profile and wallet provisioning are not part of registration.
Only auth identity exists after this slice; no money endpoints are exposed.
