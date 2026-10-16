# Disabled Keycloak verification provider

This standalone Java 21 module targets Keycloak **26.7.4**. It is implementation
for integration and isolated acceptance, not an activated or end-to-end verified
flow. Neither the existing realm export nor any persisted realm/user is changed.
Its standalone settings avoid changing the root service dependency graph.

## Implemented adapters

- `armoney-verify-email-code` and `armoney-verify-phone-code` are required-action
  factories. They re-enroll/read auth state against the current server user on
  each request, issue/resend challenges and consume six ASCII digits. Only auth
  owns OTP receipts, expiry, attempts, cooldown, delivery and contact versions.
  Email completion requires the authoritative email receipt; the final phone
  step requires both receipts and auth state `ACTIVE`, including projection ACK.
- The flow binding is HMAC-SHA-256 over a domain-separated, length-prefixed tuple
  of realm ID, root authentication-session ID, tab ID and client internal ID.
  Both required actions derive the same opaque binding. No browser field selects
  issuer, subject, contact destination, challenge ID, purpose or version.
- The existing server username supplies the phone candidate only if it already
  matches canonical `+9715[024568][0-9]{7}`. It is the built-in property mapped to
  `phone_number` by the current realm, not an editable verification attribute.
  Non-phone legacy usernames remain blocked for explicit migration.
- `armoney-password-proof` extends the pinned password-only form. It creates a
  server authentication-session UUID only after the parent's real credential
  validation succeeds. Failed validation clears the marker; repeated successful
  validation in that session retains it. It emits **no token claims**.
- `POST /realms/{realm}/armoney-status` reads current provider user/session state.
  Require `X-ArMoney-Auth-Status-Key`, plus JSON with exactly `issuer`, `subject`
  and `user_session_id`. A valid current subject/session returns those bindings,
  `username`, `email`, canonical `phone_number` or null, `enabled`, and
  `session_active`. Responses are `no-store`; errors have empty bodies.
  These are observations, never OTP receipts or eligibility proof. Auth must
  compare them to its own verified candidate versions/epoch on all entry paths.

## Private auth wire

### Default-off code-session observation

`POST /realms/{realm}/armoney-status/code-session` is a separate source-level
observer gated by `ARMONEY_CODE_SESSION_OBSERVATION_ENABLED` (only literal `true`
enables it; absent/default remains false). It authenticates the independent
`AUTH_TO_SSO_STATUS_KEY` / `X-ArMoney-Auth-Status-Key` before parsing. The existing
eight-field status and enrollment endpoints retain their contracts.

The request has exactly `issuer`, `subject`, `client_id`, `user_session_id`.
Only enabled public clients `armoney-ios`, `armoney-android`, `armoney-web` are
supported. Require the exact persistent online LOGGED_IN user session and its
authenticated client membership, plus both pinned manager validity checks.
Pinned reload adapters may have null adapter-local persistence state. Only that
null case requires an independent exact online realm/session/subject LOGGED_IN
row through Keycloak's own persister SPI; missing/mismatched/unavailable durable
provenance denies. Explicit TRANSIENT always denies without that lookup. No
durable client lazy-loading or timestamp/lifetime substitution is permitted.
The exact response has the four bindings, `username`, `email`, `phone_number`,
`enabled`, `user_session_active`, `client_session_active`, `observed_at` and
`valid_until`. Contacts are canonical or explicit null; malformed/noncanonical
email fails closed without normalization. Disabled-user negative observations
require the same valid live binding. Positive consumers require all three flags.

The deadline is the floored minimum of capture+five seconds and all finite
user/client idle/max deadlines, with pinned remember-me/client overrides. Only
maximum-lifespan `-1` is unbounded; idle grace is excluded. Missing, foreign,
expired, offline, transient, unsupported or uncertain state fails closed.
Timestamps are observations, not verification receipts, a provider lease or
banking authority. The frozen relative Auth conversion and its equivalent-clock-
rate assumption are described in [PR #85](https://github.com/aarbabaev/ArMoney/pull/85).
Source review does not prove that runtime clock assumption or production safety.

On 2026-10-17 (Asia/Dubai), the earlier observer source at `8af2fc9` passed provider
`check jar --rerun-tasks`: 64 tests in
13 suites with zero failures, errors or skips; the 11 new observer tests are
included. Independent QA bound reports, unchanged source manifests and the JAR
with three production SPI descriptors and no fixture classes. Two earlier failed
runs (64/7 and 64/5) remain retained; fixes corrected strict test-model policy
inputs and pinned Profile lifecycle without changing production checks.
The later null-reload durable-provenance correction passed a fresh provider
`check jar --rerun-tasks` on 2026-10-17: 65 tests across 13 suites, zero failures,
errors or skips. Its actual two-node acceptance remains pending.
The first isolated harness run at `fa5049f` failed at BASELINE after completed
callback; the saved log lacked endpoint status, so that failure's cause is not
established by source inspection. Exact-head required CI remains pending.
No production flag, realm, route consumer, proof
hook registration or banking eligibility is activated by this implementation.

The destination is fixed by `ARMONEY_AUTH_BRIDGE_URL`: HTTPS, or the exact
private HTTP hostname `auth-service`. No redirects; two-second connect and
four-second total exchange deadline; request/response bodies are bounded to 12 KiB.
The shared asynchronous exchange waits only through that deadline, including
response-body completion, then cancels both exchange and body subscription.
It creates no helper executor or blocking background task. The JDK request
timeout alone does not bound a delayed response body.
No request bodies, codes, passwords or contact values are logged.

`X-ArMoney-SSO-Bridge-Key` uses independent `SSO_VERIFICATION_BRIDGE_KEY`.
Commands use the mission's enrollment/challenge/consume paths and carry
`command_id`, `issuer`, `subject`, `authentication_session_id`, and the applicable
server-selected `purpose`/`contact_version`. Enrollment also carries current
`phone_number`/`email`. Status uses private
`POST /internal/enrollments/{id}/status` with the three binding fields.
Enrollment/status responses require `enrollment_id`, `version`, `state`,
`email_contact_version`, `phone_contact_version`, `email_verified`,
`phone_verified`. Verification booleans must be literal JSON booleans.

Provider command UUIDs remain in authentication-session notes until an observed
response permits advancement. A transport failure does not infer rollback.
Auth must implement durable exact-payload replay, server-binding checks and
receipt/current-contact validation. This module neither imports auth classes
nor accesses an auth database.

## Configuration and integration requests

`ARMONEY_VERIFICATION_ENABLED` defaults false. Enabled startup requires
`ARMONEY_AUTH_BRIDGE_URL`, `SSO_VERIFICATION_BRIDGE_KEY` and separate
`ARMONEY_PROVIDER_BINDING_KEY` (minimum 32 characters). The private observation
endpoint requires independent `AUTH_TO_SSO_STATUS_KEY`; a missing key returns
503. Operator-generated keys must never enter exports, templates or clients.
Binding-key rotation invalidates in-flight enrollment bindings; finish or
explicitly restart those flows under an agreed rotation procedure.

All directional credentials and cryptographic keys must be independently
provisioned. The proof client rejects equal write/resolve credentials, but a
complete cross-key equality startup validator is **not implemented**. Validation
across bridge/status/proof credentials, the code-HMAC key ring, provider binding,
OTP HMAC and delivery encryption keys remains an activation gate. Operator
separation requirements are not evidence that this missing validator exists.

The orchestrator owns provider-JAR packaging into `/opt/keycloak/providers/`,
the Keycloak optimized build, mounting the theme at
`/opt/keycloak/themes/armoney`, private ingress restrictions for `armoney-status`,
directional credentials, root build/CI and the ADR/architecture map. Preserve
issuer, subjects, clients, exact redirects/logout URIs and S256 PKCE.

A deliberate existing-realm update helper is **deferred** until independent
runtime proof and frozen activation/migration policy exist. Do not configure
these actions, replace a live authentication execution, activate enforcement or
mark fixtures verified by editing attributes. Future activation must configure
email before phone, wire the password-proof execution, and test registration,
cookie reuse, refresh, account-console changes, all clients and all bank session
entry paths. Required actions alone cannot enforce refresh or already-issued
bank sessions; auth eligibility enforcement remains essential.

## #37 disabled proof plumbing and remaining gates

The password marker is not copied into user/client-session notes or token
claims. A mutable session note cannot safely bind a particular authorization
code when another flow for the same client runs before exchange. Introspection
also must not recalculate old-token eligibility from current notes.

Pinned source establishes:

- [UsernamePasswordForm](https://github.com/keycloak/keycloak/blob/26.7.4/services/src/main/java/org/keycloak/authentication/authenticators/browser/UsernamePasswordForm.java)
  delegates form validation to real username/password validation before success.
- [OIDCLoginProtocol](https://github.com/keycloak/keycloak/blob/26.7.4/services/src/main/java/org/keycloak/protocol/oidc/OIDCLoginProtocol.java)
  constructs and persists OAuth2Code after required actions. Its code creation
  block does not expose a protected custom-proof payload hook.
- [OAuth2Code](https://github.com/keycloak/keycloak/blob/26.7.4/services/src/main/java/org/keycloak/protocol/oidc/utils/OAuth2Code.java)
  serializes a fixed code payload without an extension claim map.
- [AuthorizationCodeGrantType](https://github.com/keycloak/keycloak/blob/26.7.4/services/src/main/java/org/keycloak/protocol/oidc/grants/AuthorizationCodeGrantType.java)
  validates code/client/redirect/PKCE before constructing token context from the
  authenticated client session. Its immutable code data is available to client
  policy contexts, not automatically to generic custom mappers.
- [AbstractOIDCProtocolMapper](https://github.com/keycloak/keycloak/blob/26.7.4/services/src/main/java/org/keycloak/protocol/oidc/mappers/AbstractOIDCProtocolMapper.java)
  invokes claim mapping for introspection; using current notes here would
  change an earlier token's interpretation.

Disabled implementation now includes `LoginProof` DTOs, `CodeKeys`, `ProofClient`,
`ProofLoginProtocol`, `ProofTokenPolicy`, `ProofIntrospection` and `ProofClaims`.
It follows the root-frozen [auth proof contract](../../auth-service/LOGIN-PROOF-CONTRACT.md).
The password-only hook also records the stable password timestamp at credential
validation. Neither that timestamp nor the event is copied into reusable sessions.

The successful-redirect override reads the exact existing `code:` record without
consuming it, checks code/session/client binding and uses its actual expiry. It
withholds the redirect until auth acknowledges the immutable write. Pending
authentication-session state retains the original command, key, event and times
for uncertain-response retry. The validated-code response hook resolves that
durable binding and copies exactly three claims before access-token signing.
It removes those claims from the refresh token. The introspection override copies
only strict original signed claims after preserving parent behavior.

**No factory for these three proof extensions is registered.** There is no enabled
startup/configuration path or realm change. Constructors require an explicit
enabled flag; disabled hooks delegate or do nothing. Key-ring provisioning,
allowlist/startup checks, default-factory selection and actual provider
activation remain integration work. Do not register the factories or activate
#37 before the [runtime gates](LOGIN-PROOF-DESIGN.md) and independent review pass.
No generic LOGIN event or mutable-session-note mapper supplies eligibility.

Effective access-token lifetime follows the pinned provider's client
`access.token.lifespan` override before the realm default. Values must be positive
and at most 900 seconds; the session-derived `-1` lifetime is unsupported. Both
redirect creation and the validated-code policy check the effective setting.
The policy separately checks the generated access token's actual `iat`/`exp`
before copying proof claims: positive lifetime at most 900 seconds, present
timestamps, unexpired token and bounded future issuance. These checks do not
replace independent signed-token and refresh acceptance.

## Evidence and remaining gates

The clean Windows candidate ported onto `83043b9` has passed standalone Gradle
checks under JDK 21: **39 tests passed**, with zero failures, errors or skips.
The Git Bash `package-provider.sh` run also completed its six Gradle tasks with
39 passing tests. Package verification confirmed exactly three existing SPI
descriptors, 39 hashed source inputs and 192 hashed resolved dependencies.
The packaged provider JAR SHA-256 is
`2a59c0b54075b4d721ee774e06b3ba10382f6fc1f048bbd5f1f84a9c8bee77ac`.
Clean Gradle reports are under `build/reports/tests/test/` and
`build/test-results/test/`; package manifests identify the actual input bytes
and artifacts rather than claiming that the base commit alone describes them.
These Windows checks and package-directory verification do not establish a
built Docker image or a working Keycloak registration/runtime flow.

Historical sibling evidence is retained separately: before this clean port,
independent standalone Gradle XML reports in `armoney-login-notice`, timestamped
2026-10-15 11:52:52-53 UTC, record 39 tests with zero failures, errors or skips
after the pinned RESTEasy test-runtime dependency was added. That successful
run supersedes the earlier seven-test RuntimeDelegate failure. It is historical
evidence, not the clean Windows verification described above.

The historical Java 21 `javac` run compiled all main and test sources against Keycloak 26.7.4 core,
common, server-spi, server-spi-private and services jars. The fixture classpath
uses Jakarta REST 3.1.0, Jackson 2.21.1 (annotations 2.21; JDK8/JSR310 modules)
and JUnit Platform console 1.12.2. This is a manually assembled proof classpath,
not evidence of Gradle's resolved dependencies or packaged runtime readiness.
Status boundary fixtures additionally use RESTEasy core/core-spi 6.2.18.Final,
JBoss Logging 3.6.1.Final and Jakarta Annotations 2.1.1.

Historical JUnit Console reports used `build/proof-reports`; generated
jars/classes are ignored. Safety tests cover binding component/key separation, tuple
partitioning, destination/path/header rejection, exact/oversized response limits.
Bridge contract tests use a synthetic in-process HTTP transport to assert the
fixed URI/header/deadline, rejection of redirects/status errors/malformed JSON,
and oversized-request rejection before transport. Completion-policy tests cover
strict booleans, both receipts and projection activation. They do not prove
Keycloak routing, browser flow, transactional OTP authority, clustered sessions
or delivery.
Additional boundaries reject duplicate/trailing JSON, preserve interruption,
hide transport exception details, and reject missing/wrong status credentials,
oversized/malformed bodies and unsupported caller fields before user/session
provider access. SEC-VER-001 resend tests cover fresh commands after definitive
cooldown rejection and exact command retention after ambiguous timeout.

Proof fixtures cover strict DTO/echo/null/type/expiry rules, separate credentials,
404-only bounded key fallback, generated code/query restrictions, an independent
.NET HMAC vector, and original-token claim isolation. Tests invoke the actual
pinned redirect override with synthetic cache/session collaborators: an uncertain
write withholds the response, retry keeps its original pending write, and missing
or mismatched code records never write proof. Policy fixtures reject invalid
code context and ignore refresh/introspection/other contexts. These do not prove
real callback ordering, signatures, cookie behavior, clustered persistence or
refresh grants. SEC-PROOF-001 tests reject client overrides of 3600 and -1 against
a 900-second realm default, allow bounded effective overrides, and reject missing,
overlong or expired actual token timestamps. SEC-PROOF-002 uses real loopback JDK
HTTP exchanges for delayed-body total timeout, normal success and oversized-body
failure, plus cancellation-before-subscription coverage. No persistent service or
database is used. The historical lightweight suite contains 39 passing tests.

Linux CI, Docker image building and real Keycloak activation remain **unrun**.
Applicable linked acceptance, isolated two-instance Keycloak/browser flows and
independent runtime security/QA verification remain required. Broader registration,
ACTIVE eligibility and proof-factory activation are not established by the
Windows package checks. Gradle/heavy builds require the orchestrator's serialized
lease. Neither current nor historical evidence authorizes runtime activation.
