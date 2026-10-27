# Keycloak verification provider under integration

This standalone Java 21 module targets Keycloak **26.7.4**. Its default-off
production composition has independently verified disposable registration and
synthetic zero-balance LOCAL/historical-SSO acceptance. Exact-head CI, final review
and deployment readiness remain separate gates; no persistent activation is
claimed. Neither the existing realm export nor any persisted realm/user is changed
by building this module.
Its standalone settings avoid changing the root service dependency graph.

## Implemented adapters

The current production source registers eight SPI descriptor types, including
the code-intent OIDC protocol, token-response policy, introspection adapter and
genuine registration-creation action. These replace neither the durable Auth
authority nor its separate managed-session admission checks. Historical proof
factories and acceptance/control resources are excluded from the production
package inventory. An earlier dedicated-account checkpoint passed an isolated
130-case provider suite and its then-seven-descriptor package check. Its optimized
image and disabled-banking realm provisioning were scoped evidence for that
revision. Current image and functional evidence are recorded in the delivery PR;
earlier results do not clear subsequent source changes or authorize persistent
activation.

Production configuration uses `ARMONEY_LOGIN_PROOF_ENABLED` (default false),
`ARMONEY_CODE_INTENT_CODE_HMAC_KEYS` and
`ARMONEY_CODE_INTENT_PURPOSE_MANIFEST`, alongside the existing distinct transport,
binding, actor and pair settings. The bounded key ring accepts canonical 32-byte
keys and checks standard/URL Base64 aliases across roles. Unknown nonempty secret
containers are rejected. The exact issuer/client/original-redirect manifest
separates native banking, managed migration and legacy account migration.
The dedicated public `armoney-migration-account` client is accepted only for the
exact account callback's `MIGRATION_ACCOUNT` purpose and strict read-only code
observation. It cannot enter managed banking, registration or verification
allowlists. This configuration is implemented, with scoped historical-SSO
acceptance; it does not authorize persistent activation.
See [ADR 0050](../../../docs/adr/0050-production-provider-code-intent.md).
The disposable dedicated client exposes its token only to the existing
`armoney-auth` introspection reader. Its single inspection audience mapper does
not add `armoney-api` or any banking proof and cannot grant banking admission.

### Separate production-provider acceptance topology

`compose.production-provider-acceptance.yaml` is an explicit disposable topology,
not a persistent-stack overlay. Root must establish a fresh owned Docker daemon,
verify image identities and inherited volume shadows, enforce an outer deadline,
and stage service readiness before starting its opt-in controller and edge
profiles. No host ports, host sockets or persistent volumes are declared.

`scripts/production-provider-fixture-environment.py` generates eleven new private
configuration files from fresh random material. It never reads the operator's
`.env`. Caller assertions do not attest isolation. The provisioning helper refuses
an existing realm; it does not import, reset or update the operator's realm.

The separate disposable controller image includes only six explicitly selected
test-class families and production Auth dependencies. It must never replace the
production Auth service or be packaged into the provider JAR. Its version-2
protocol reports a bounded, sorted inventory of genuine confirmed events, rather
than selecting a latest event or treating multiple ordinary logins as ambiguous.
Delivery observations come from actual SMTP/SMS transport and successful fenced
outbox completion. The controller neither creates registration authority nor
substitutes an acceptance response for a financial result.

The browser driver under `acceptance-fixture` requires genuine registration,
both OTP forms, projected completion and a subsequent fresh password login.
Portable driver tests are not service acceptance. Full migration callbacks,
pre-FINAL continuity, unknown-outcome recovery and device acceptance remain
separate gates. No emulator is started by these checks.

- `armoney-verify-email-code` and `armoney-verify-phone-code` are required-action
  factories selected only for genuine server-marked registration creation, never
  blanket obligations for existing or migration users. They re-enroll/read auth state against the current server user on
  each request, issue/resend challenges and consume six ASCII digits. Only auth
  owns OTP receipts, expiry, attempts, cooldown, delivery and contact versions.
  Email completion requires the authoritative email receipt; the final phone
  step requires both receipts and the strict `PROJECTED` wire state, including
  projection ACK. This is registration projection completion, not banking ACTIVE
  authority. A genuine new registration then shows a fixed sign-in page before
  code issuance and requires a fresh password flow; no registration password
  action return is treated as authentication proof. Production browser/restart
  acceptance remains pending.
- After confirmed email completion, a purpose-scoped authentication-session
  routing marker prevents Keycloak's repeated trigger evaluation from scheduling
  email again before phone. Suppression requires a fresh Auth observation and
  the same enrollment, contact version, user, email and original flow binding.
  Missing, changed or unavailable evidence requeues email. This marker grants no
  verification or banking authority; phone still requires both receipts and
  `PROJECTED`. See [ADR 0051](../../docs/adr/0051-registration-required-action-routing.md).
- After phone consumption, a pending form exposes an explicit status-only POST.
  It uses the original flow's protected consumption routing state and fresh Auth
  contact versions and dual-verification facts. It neither issues nor consumes
  another OTP. `PROJECTION_PENDING` remains pending; only `PROJECTED` reaches the
  fixed sign-in page. Empty, unknown and duplicate operations are rejected.
  Runtime acceptance of this continuation remains pending.
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

## Historical component checkpoints

The sections below retain evidence and limitations at their named earlier
revisions. Statements about unwired components or three-descriptor packaging
describe those snapshots, not the current production composition above. They do
not establish a successful current image, enabled flow or native banking login.

### Live enrollment observation and disabled accounts

The separate enrollment observer keeps its exact twelve-field response. An
existing disabled account may return `enabled: false` and `authentication_session_active: true`
only after the current server flow, issuer, subject, client, HMAC binding,
canonical contacts and original deadline all validate. Missing/deleted users or
flows, expiry and binding failures remain unavailable; no negative session fact
is inferred from absence. Responses remain `no-store`.

Auth's status-only consumer can use this negative observation to invalidate
eligibility. Positive enrollment/proof consumers still require both flags true.
This does not establish ACTIVE enrollment, create a banking session or activate
registration. On 2026-10-16, nine focused provider tests and the full provider check/package
run (54 tests, zero failures/errors/skips; counts are not additive) passed.
Sixteen offline diagnostic tests passed after the committed-baseline fixture fix.
Actual pinned two-node acceptance at `85d7cbd` passed: node B returned the exact
12-field negative observation after node A disabled the existing user, retaining
the same live flow, bindings and future deadline; subsequent deletion returned503.
Independent QA, security and reviewer audits verified the retained standalone
receipt and the hosted build job117102507642 in CI38932950571.
See [the runtime checkpoint](https://github.com/aarbabaev/ArMoney/pull/81#issuecomment-6220625685).
These results apply to that source revision. After integration of main's dormant
proof-wire corrections, fresh combined-head CI remains required before approval.
## Private auth wire

### Unwired code-intent configuration

`CodeIntentConfiguration` is a pure explicit-Map parser; no factory calls it and
it allocates no HTTP/DB resources. `ARMONEY_LOGIN_PROOF_ENABLED` defaults false
and accepts only lowercase `true`/`false`. Shared managed/legacy pair variables
use exact `{issuer, client_id}` JSON objects, at most16 pairs/16KiB per array;
supplied dormant arrays still receive strict validation. Enabled scope is the
single configured issuer and existing public verification-client subset.
Unknown/overlapping pairs deny; mixed-subject routing remains unsupported.
Actual public-client models must still be checked by future runtime consumers.

Enabled parsing requires separate write/confirm/lookup credentials, the existing
private bridge base, writer/actor and code-key-ID configuration. Constructor-held
code material is supplied separately under its configured key ID (32 bytes),
along with all other locally held current/retained secrets for inequality checks;
no new code-key environment aliases are introduced. Callers must supply their
complete locally known material inventory. Raw and canonical-base64-decoded
aliases reject. Auth-only dependency flags, databases, migrations, retention/AEAD
keys and remote model/deployment state are not inspected by this provider parser.
No secrets are imported merely to compare them. Configuration is not wired,
stage/lookup/confirmation transport remains unimplemented, and no SPI registration
or production enablement changes.

At source revision 89a74046a1168d14ce0140447553c0264d4c256e, the isolated Java 21
run compiled all provider production/test sources and passed eight filtered
CodeIntentConfigurationTest cases with zero failures, errors or skips. Independent
review verified retained XML, source/archive hashes and recorded cleanup; the
original orchestrator separately verified no owned containers, volumes or networks
remained. This focused run does not establish full provider check, packaging,
required PR CI, runtime wiring or activation. The URI error-redaction and Unicode
space findings are verified only within this unwired parser scope.

### Unwired invocation deadline

`InvocationDeadline` provides one fixed four-second monotonic budget, started
before serialization/DNS by a future consumer. It owns only explicitly registered
exchange futures/body cancellation hooks; completed registrations detach without
renewing the budget. Expiry, backward elapsed time or close terminally cancels
owned resources, including late registrations. Cancellation callbacks run outside
the ownership monitor. The utility creates no executor, thread, scheduler, HTTP
client or database resource. Existing `DeadlineHttp`, `ProofClient`, token hooks
and SPI registrations are unchanged.

At source `34a153`, the isolated focused check actually passed eight deadline
tests and eight configuration tests: 16 tests across two suites, zero failures,
errors or skips. Independent review verified retained XML and source/archive
matching. Evidence prefix:
`D:/ArMoneyDockerRecovery/2026-10-17/auth-code-confirmation/armoney-sso-deadline-8cf71eb6f45b40578c40c7513349a3f0`.
These deterministic tests do not establish physical DNS/HTTP termination or a
runtime aggregate lookup/confirmation deadline. Consumer integration and
exact-head required CI remain unproven; no production activation follows.

### Unwired code-intent client

`CodeIntentResources.open(configuration, localKeyMaterial)` now provides an unwired
lifecycle owner with `client()`, `keys()` and idempotent `close()`. Configuration
and key validation precede HTTP allocation; disabled configuration returns empty.
Enabled resources own one transport and release it after construction failure.
Supplied key bytes are copied; the caller must supply its complete local current
and retained secret inventory. Close revokes access; it does not promise key
zeroization. At source `d8638d0eff87c5802c7b4a187e40154d8b14a4fe`, the isolated
run passed 32 focused tests across five suites with zero failures, errors or
skips: four lifecycle tests plus the prior 28 client/HTTP/configuration/deadline
tests. Independent QA and security verified the evidence and matching source.
The lifecycle cases cover disabled/invalid configuration without allocation,
defensive copies, one owned transport, idempotent close, post-close denial,
construction-failure cleanup and leaving borrowed HTTP clients open. The runner
exited zero with cleanup verified; overall elapsed time was 97.109 seconds
(BUILD 50 seconds). Retained evidence is under
`D:/ArMoneyDockerRecovery/2026-10-17/auth-code-confirmation/` with run suffix
`59a352bd3110436aa94e1ab22090c92e`; archive SHA256 is
`b9c7eb361437b018b8486645b028e1b8ae6310bf20916e7c442ee0a06fa4a61f`.
These are synthetic-client unit tests, not real-network, physical DNS termination
or zeroization proof. No SPI registration, hook, token release or activation is
added; combined runtime and clock gates remain open.

`CodeIntentClient` and `CodeIntentHttp` provide separate narrow stage, original-key
lookup and confirmation operations at `/internal/sso-code-intents`,
`/internal/sso-code-intents/key` and `/internal/sso-code-intents/confirm`.
The client requires exact enabled managed-pair membership before fanout, separate
directional credentials, strict acknowledgements and a directly returned
confirmation snapshot. There is no historical-resolve fallback or inline retry.
Lookup and confirmation share the caller-created `InvocationDeadline`, including
serialization, body parsing and selection. The transport caps streaming bodies,
requests HTTP/1.1 without redirects, and owns only its invocation cancellation.
Transport owners must close it: internally constructed HTTP clients are shut down;
injected HTTP clients remain caller-owned. Existing bridges, token hooks and the
three SPI descriptors remain unchanged; no factory wires this client.

At source `c6b42ba5d1b240f8605fc94c2371babfee770164`, the isolated focused run
compiled all provider sources and passed
28 tests across four suites: six client, six HTTP, eight configuration and eight
deadline tests, zero failures, errors or skips. Independent review verified the
retained evidence; overall elapsed time was 89.313 seconds. Evidence prefix:
`D:/ArMoneyDockerRecovery/2026-10-17/auth-code-confirmation/armoney-sso-client-b361df009d71468e91fb3b6f40bc6d92`.
The HTTP tests use synthetic clients, not real network/DNS operations. They do not
establish physical DNS/HTTP termination, a real aggregate transport deadline,
full provider check, packaging, required CI or production activation. The four
source files were subsequently ported unchanged onto the newer main-based client
branch; the focused result remains attributed to its tested source.

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
errors or skips.

The separate disposable two-node code-session acceptance passed on 2026-10-17
(Asia/Dubai), tested source `c8a8dda`, with an outer elapsed time of 270.469 seconds.
The harness consists of `compose.sso-code-session.yaml`,
`scripts/run-sso-code-session.sh`, `scripts/sso-code-session-acceptance.py` and
`scripts/test-sso-code-session-diagnostics.py`. It reuses the dedicated visibility
fixture image and provenance; it does not enable the production configuration.
The CI-guarded runner creates a unique owned project/image, enables this observer
only on node B, and leaves node A off. Actual cases covered the cross-node
12-field baseline, HTTP/authentication/strict-body boundaries, foreign binding,
the same allowlisted client changed to confidential and restored, disabled-user
negative observation, explicit null contacts after admin clear/readback and
restore, session deletion, and short-policy expiry. Per-exchange HTTP/body bounds
are four seconds and 12 KiB. The runner's 540-second acceptance-phase limit does
not bound image build or node startup. Inner and outer cleanup were verified;
the root independently verified no owned containers, volumes or networks remain.
The retained evidence prefix is
`D:/ArMoneyDockerRecovery/2026-10-16/code-session-verification/armoney-code-session-manual-5b43c1e505174c569469fcb30067e9d7`.
This result does not establish Auth confirmation, code redemption, clock
continuity/equal rates, or production activation.
The first isolated harness run at `fa5049f` failed at BASELINE after completed
callback; the saved log lacked endpoint status, so that failure's cause is not
established by source inspection. Later diagnostic failures also do not establish
their earlier causes retroactively. Exact-head required CI remains pending.
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

The implemented migration email-readiness repair follows
[ADR 0059](../../docs/adr/0059-migration-provider-email-readiness.md). Its trigger
requires an enabled realm required-action model with alias/provider ID
`armoney-migration-email-verification`, `defaultAction=false`. Stock `VERIFY_EMAIL`
must also be enabled with `defaultAction=false`; keep realm `verifyEmail=false`.
Do not assign the trigger itself as a user action. It selects only exact configured
migration-purpose callbacks and enqueues the stock email action without changing
the verified flag. Existing strict registration code actions remain separate.
It evaluates during authenticated `AUTHENTICATE` and `REQUIRED_ACTIONS` phases,
including Keycloak's trigger check before the first required-action redirect.

Stock provider verification requires genuine private realm SMTP configuration
with authenticated, verified STARTTLS. Never persist SMTP credentials in realm
exports or templates. Complete it before starting Auth's bounded migration
attempt. The private `armoney-status/code-session/v2` endpoint reuses
`AUTH_TO_SSO_STATUS_KEY` and the enabled production purpose manifest; its exact
versioned contract is in ADR 0059. Deploy the provider capability before enabling
the consuming Auth migration configuration. V1 remains unchanged. Independent
disposable tests verify old-link rejection after committed email replacement,
Auth current-contact rejection and synthetic LOCAL/SSO delivered-link acceptance.
Separate action-token negatives, CI and final review remain required. Atomicity
with concurrent provider contact updates is not established; see ADR 0059 for
the boundary.

The default Docker build selects the stock email sender. The separate opt-in
build argument `ARMONEY_EMAIL_SENDER_PROVIDER=armoney-strict-mailtrap` selects the
strict sender during optimized Keycloak augmentation; a runtime environment
variable is not a substitute for that build. The image records the selection in
`io.armoney.email-sender`. The only other accepted build value is `default`.
The strict sender preserves stock email templates and action tokens, restricts
ArMoney SMTP to `sandbox.smtp.mailtrap.io:2525`, and requires verified STARTTLS
before authentication. It delegates unrelated realms explicitly to stock.
SMTP work has five-second phase limits and a fourteen-second attempt plus at most
one second for cleanup; vault resolution and physical DNS cancellation are outside
that guarantee. No automatic retry occurs after uncertain delivery. Real hostile
TLS/logging/write-stall and runtime dependency acceptance remain required.

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
