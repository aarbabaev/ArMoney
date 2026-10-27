# ADR 0059: Provider email readiness before migration completion

Status: Agreed contract; implementation and independent acceptance pending. No activation authorization is implied.

## Context

Actual disposable LOCAL migration completed Auth email and phone OTPs, User
publication, Auth FINAL and automatic User reservation release. Fresh native
OIDC authentication then reached the Gateway exchange with a literal provider
`email_verified=false` and received HTTP 503. Auth's migration admission policy
requires the provider flag to be true. Registration's separate Auth-owned email
authority in ADR 0052 does not apply to migration.

Completing Auth verification must not silently copy a boolean into Keycloak,
weaken the migration admission gate or fabricate provider verification in a
fixture. The provider prerequisite must be completed through provider authority.

## Decision

For an authenticated historical account, a production required-action trigger
uses the exact configured issuer, client and original redirect manifest to select
only `MIGRATION_ACCOUNT` or `MIGRATION_MANAGED`. It excludes a valid strict
registration creation marker. For an enabled user with a canonical current email
and a false provider verification flag, it enqueues stock Keycloak `VERIFY_EMAIL`.
The trigger never writes the verification flag. Realm-wide `verifyEmail` remains
false; registration code actions and already verified login retain their behavior.

The provider's signed email action-token flow completes before Auth starts its
existing bounded migration attempt. This preflight proves only provider email
ownership. It supplies no Auth receipt, migration command or banking proof and
does not extend any Auth deadline. Auth still requires its own current email and
phone OTP evidence and all existing migration fences.

A private `POST /sso/realms/armoney/armoney-status/code-session/v2` observation
uses the existing private status credential. Authentication precedes bounded,
strict JSON parsing. Requests contain exactly `protocol_version` (integer 2),
`purpose`, `original_redirect_uri`, `issuer`, `subject`, `client_id` and
`user_session_id`. Purpose accepts only the two migration purposes. The provider
validates both the configured manifest and the actual authenticated client-session
redirect; the request does not select an authoritative redirect.

Responses contain exactly `protocol_version`, `purpose`,
`original_redirect_uri`, `facts` and `provider_email_verified`. Nested facts retain
the exact twelve V1 fields and meanings: issuer, subject, client_id,
user_session_id, username, email, phone_number, enabled, user_session_active,
client_session_active, observed_at and valid_until. Facts and the raw verification
boolean come from one resolved user/session observation. V1 remains unchanged.
Responses are not cacheable.

Auth checks the exact schema, types, purpose, redirect and all identity/session
bindings. Only a literal boolean true supplies readiness; false, missing,
malformed, stale or unavailable observations cannot advance migration. Auth
requires readiness before accepting a managed receipt and independently at the
existing fresh-evidence boundaries, including before FINAL. It applies the
original database-start deadline and actual receive time, checks current
canonical contacts, and rechecks contact versions and generations under existing
locks after remote I/O. HTTP never runs inside the database transaction.

No schema change or new secret is required. Existing receipts are not reinterpreted
as historical provider verification. Disabled migration allocates no readiness
client; compatibility constructors without readiness fail closed. Rollout must
provide the V2 provider capability before enabling its Auth consumer. An already
completed migration can satisfy genuine provider verification and obtain fresh
native proof without replaying FINAL or rebinding its owner.

## Validation and limits

Require strict V1 compatibility and V2 schema, echo, type, binding, freshness,
outage and lifecycle tests. False provider verification must independently block
receipt and FINAL while preserving pre-FINAL sessions and financial state. True
provider verification cannot replace Auth OTPs. Preserve the existing false-flag
native exchange regression.

Require actual pinned-provider tests for email replacement before link use,
concurrent replacement during verification and observation, wrong or replayed
links, expired actions, strict registration exclusion and verified repeat login.
The reviewed stock handler compares its signed token email with the current
authenticated email; an atomic compare-and-set guarantee has not been
established. This race remains an acceptance blocker. Do not infer distributed
atomicity or permanent future admission from a successful readiness observation.

Complete fresh LOCAL and historical-SSO Mailtrap/SMS runs must retain the original
migration timeout, identity and financial preservation checks, FINAL-once behavior,
old-session denial and same-owner native banking admission. Component tests and
protocol review do not establish complete acceptance or deployment readiness.
