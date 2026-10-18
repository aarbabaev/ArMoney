# Disposable observer and code-intent acceptance fixtures

## Code-intent source candidate

The disposable launch adapter source now reads only named environment values in
the fixture factory, after literal fixture/CI/isolation gates. Disabled mode reads
no secret material. Core bootstrap remains environment-free. Configure, start and
close share atomic ownership with terminal OFF/FAILED/CLOSED states, immutable
selection and no hot replacement. Strict local material parsing is bounded to
16 KiB, 64 entries and canonical Base64; a configured UUID still does not prove
persisted identity. Provision while disabled, stop, then enable and restart the
disposable nodes before experiments. Eleven adapter unit tests passed at
`3082f342`; these tests do not prove factory environment-read ordering or lifecycle
races in the actual Keycloak runtime. No production SPI or default enablement is added.

The later bootstrap source adds explicit `start(configuration, localMaterial,
settings)` ownership, returning empty without allocation when disabled. It checks
copied local material before allocation, installs support last and owns one
fixed-delay maintenance scheduler with bounded shutdown. Its nine additional
tests passed at `3082f342`; fake maintenance does not establish actual
join, interruption or concurrent shutdown behavior. A configured subject UUID is
not evidence of a persisted identity: disposable provisioning must precede startup.
Core bootstrap accepts explicit configuration; the guarded fixture-only launch
adapter supplies it. `Dockerfile.code-intent` now declares dedicated disposable
SPI/theme assembly, without default enable flags or credentials. This image source
built at `175c9d1` and passed augmentation and retained JAR/SPI/theme inventory
checks. Actual orchestration, disabled provisioning followed by restart,
classloader compatibility and bootstrap invocation remain unverified.

The code-intent fixture is acceptance-only source, not a completed combined
acceptance flow. An earlier fixture test compilation failed because the pinned
realm-resource provider is not AutoCloseable. Corrected checkpoint `067a829`
passed 134 tests in 26 suites with no failures, errors or skips: 104 provider
tests, including 12 deadline tests, and 30 fixture tests (24 new, six existing).
That earlier run excluded the bootstrap and adapter additions. A subsequent
unfiltered provider/fixture check at `3082f342` passed 154 tests across 28 suites
with zero failures, errors or skips: 104 provider and 50 fixture tests, including
nine bootstrap and eleven adapter cases. Independent audit verified source/archive
binding and actual XML. This is scoped unit evidence, not an integrated runtime result.
Independent source review is not runtime readiness.

Explicit disposable bootstrap must install the fixture's settings, code-intent
resources and transport. Factories remain unavailable without installation.
Production SPI registration and `VerificationAction` eligibility rules are
unchanged. Disposable SPI/theme packaging, bootstrap, lifecycle maintenance and
the exact HTTPS `/sso/realms/armoney` two-node topology still need integration.
Auth authority must come from real verification consumption, materialization and
persisted User projection acknowledgements. LOCALSMTPTEST evidence uses an
ephemeral in-process transport peer and is not Mailtrap Sandbox delivery proof.

The request-body guard source authenticates before allocating one of eight local
permits, uses HTTP/1.1 connection-only timer capture and serializes completion
against expiry. The original transaction callback captures only experiment ID;
transaction commit is distinct from the driver's HTTP-response assertion.
Authenticated node-B membership, lookup and confirmation share one original
four-second invocation budget. These are source properties pending compilation
and independent execution, not physical-timeout guarantees.

Required open gates include locator-before-buffering behavior, stalled/drip body
termination and reader release, keep-alive/timer cleanup races, physical DNS
cancellation, clock assumptions, cross-JAR classloader compatibility and genuine
native two-node acceptance. No token redemption, banking session, notice,
production hook activation or positive feature-completion claim follows from
this candidate.

The observer fixture contract and historical build instructions below describe
the existing observer tooling; they do not establish code-intent acceptance.

This independent Java 21 / Keycloak 26.7.4 module is destructive test tooling,
not part of the production provider, its SPI descriptors, or its image. Never
deploy this artifact to an existing realm or persistent stack. The separate
Dockerfile creates an explicitly labelled acceptance-only image containing the
unchanged production provider JAR and a distinct fixture JAR. No auth service
classes, database, delivery provider or positive verification authority is used.

## Build contract

From the repository root, with the orchestrator's serialized build lease:

```powershell
.\gradlew.bat -p sso-service/provider/acceptance-fixture check jar --no-daemon --max-workers=1
```

Output: `build/libs/armoney-observer-acceptance-fixture-0.1.0.jar` under this module.
The root wrapper remains pinned to Gradle 8.14.3. This module independently pins
Keycloak 26.7.4, JUnit 5.12.2 and test-only RESTEasy 6.2.18.Final, and produces a
reproducibly ordered archive with timestamps omitted.

An independently authorized image build uses repository-root context and
`-f sso-service/provider/acceptance-fixture/Dockerfile`. It runs the production
provider's existing checks/package process and this fixture's checks before
Keycloak augmentation. Artifact/source hashes are stored separately in
`/opt/keycloak/conf/armoney-observer-fixture/`; production package provenance is
retained in its existing directory. The production Dockerfile never copies this
directory. Nothing here enables a realm action or proof factory.

## Private HTTP contract

Only the disposable private network may reach these controls. No public edge
route is permitted. Require independent random `OBSERVER_FIXTURE_CONTROL_KEY`
(32..512 printable non-space ASCII characters) and
`OBSERVER_FIXTURE_ENABLED=true`; absent/false flag or invalid configuration
returns503. Header `X-ArMoney-Observer-Fixture-Key` must match; wrong/missing
credential returns401. Keys must differ from bridge/status/binding credentials.
No credential or request body may be logged.

All three paths are POST under
`/sso/realms/armoney/armoney-observer-fixture/` when `/sso` is configured:

* `inspect`
* `delete-tab`
* `delete-root`

Each accepts exactly this JSON object, maximum4096 UTF-8 bytes; duplicate/unknown
fields, nulls, wrong types and non-ASCII/whitespace identifiers return400:

```json
{"client_id":"armoney-web","provider_root_session_id":"root","provider_tab_id":"tab"}
```

Only existing `armoney-ios`, `armoney-android`, `armoney-web` clients are accepted.
Use the trusted tuple captured from the real production required-action bridge
request. This fixture is a privileged controller, not an authorization observer:
it does not accept/return HMACs, receipts, contact values, epoch or ACTIVE.

`inspect` returns200 and exactly six fields: `root_present`, `tab_present`,
`root_timestamp` (integer epoch seconds or null if no root), `action` (string or
null if no tab), `authenticated_user_present`, `server_time` (integer epoch
seconds). Missing roots/tabs are observable absence, never successful authority.
All responses carry `Cache-Control: no-store`; error responses have no body.

Deletes return204 and are idempotent for already absent targets. `delete-tab`
only removes the requested tab when it resolves under the requested client;
`delete-root` removes the whole requested root including its other tabs.
Unknown client/provider failures return503. Every operation resolves the current
request realm by internal ID in a newly created Keycloak transaction/session,
using `KeycloakModelUtils.runJobInTransactionWithResult`. It never reuses a
caller-local root/tab model. The helper completes the transaction before the
response is constructed; a transaction failure cannot return204/200. This does
not establish cross-node visibility: the acceptance harness must query node B
after node A's response and verify absence using the real observer.

Read-only inspection never calls setTimestamp, setAction, code generation or
cleanup. The only mutations are the two explicit removal controls. Natural
USER expiry must be tested with real waiting and short isolated realm settings,
not by refreshing timestamps or introducing a synthetic successful observer.

## Evidence boundaries

Boundary tests are source-prepared; no execution is implied by their presence.
They cover strict input, disabled configuration, credential-first checks and
bounded bodies. Actual two-node transactions, clustering, commit visibility,
root/tab removal, expiry and the acceptance image require separately authorized
execution. The bridge fixture must return503 after recording/calling the real
observer, never a receipt or fake enrollment success. Positive authority, ACTIVE,
real delivery, auth route wiring and production activation remain unavailable.

## Dedicated code-intent image source

The dedicated profile replaces the observer realm-resource descriptor with only
`CodeIntentFixtureResourceFactory`, adds the disposable continuation action and
uses the dedicated `CodeIntentFixtureLoginProtocolFactory` for the reviewed
code-generation hook. It does not register old observer or visibility control resources. Theme
discovery metadata and the continuation theme inheriting `armoney` are generated
only inside this fixture build. Production provider packaging and SPI descriptors
remain separate and unchanged. Generated fixture inputs and the dedicated
Dockerfile are included in retained provenance.

Source inspection is not an image build, JAR inventory inspection or native
Keycloak acceptance. Production password proof and eligibility stay unchanged;
this assembly source establishes no token redemption, session authority or
activation.

## Auth companion assembly and separate evidence

The integrated source includes `auth-service/Dockerfile.code-intent-fixture` for
the guarded test-Java companion. It supplies no isolation flags or credentials
and built at `175c9d1` with verified non-root Java 21/keytool and retained
artifact inventory. It is independent of production Auth packaging.
Separate Auth checkpoint `7aa71f8` reported 967 cases: 966 executed passes and
one unresolved positive readiness-equivalence skip. Three authority PostgreSQL
scenarios exercised local SMTP/STARTTLS, SMS and persisted User ACK paths with a
synthetic provider peer. They do not establish native Keycloak or two-node
acceptance. Later diagnostics revealed a test SQL timestamp parameter typing
failure, preserved at `3fbc50c`. The test-only cast correction at `f274266` then
passed 972 tests across 41 suites with no failures, errors or skips, including
the positive readiness case and the strict required-test selection gate.

The first disposable image build at `84a7db5` failed during Keycloak
augmentation: legacy visibility and code-intent control resources exposed
duplicate root POST endpoints. Replacing SPI descriptors alone did not isolate
annotated resources. The SSO final image was not verified, and the sequential
Auth image stage did not run. Retain this failed result; separate fixture
artifact profiles were subsequently implemented and verified by the `175c9d1`
image build. The earlier failed result remains retained.

The source fix introduces `codeIntentJar`, producing
`armoney-code-intent-acceptance-fixture-0.1.0.jar` with only the code-intent
control resources and a dedicated `CodeIntentFixtureLoginProtocol`/factory.
Legacy visibility resources, protocol and state are excluded. The existing
observer JAR excludes code-intent classes, and its restored visibility protocol
has no reference to those excluded classes. Both profiles compile all fixture
sources and retain the full test suite; `check` also verifies actual JAR class,
SPI-target and scope inventories. Production provider packaging is unchanged.
The code-intent Dockerfile selects the dedicated artifact and protocol SPI.
Archive checks also reject retained class references to excluded profile classes.
At `175c9d1`, both images built and passed their bounded inventory/offline
checks. Independent audit verified source/archive and retained artifact hashes,
actual Keycloak augmentation and selected protocol/resource/theme metadata.
Separate unfiltered checks at the same `175c9d1` archive passed 157 tests
across 29 suites with zero failures, errors or skips: 104 provider and 53
fixture cases, including all three new protocol negative cases. Independent
audit verified actual `verifyFixtureJars` execution and both retained profiles.
These tests and image checks do not establish native login or activation.

## Disposable realm source

`code-intent-realm.json` defines an isolated `armoney` realm with the existing
allowed public client `armoney-android`, exact `https://fixture-client/callback`,
S256 and a required password-proof-only browser flow. Only the fixture
continuation action is configured. No users or credentials are embedded. This
new realm definition was not included in the tested `175c9d1` archive and has
not been imported or exercised. Provision a complete synthetic user while both
fixtures are disabled, read back its persisted UUID, stop both nodes and restart
with the enabled exact subject/pair configuration. Production realm data and
clients remain unchanged.

## Isolated native-flow wiring source

`compose.sso-code-intent.yaml` is a separate disposable acceptance topology. It uses
three empty tmpfs PostgreSQL instances, real Auth/User/SMS processes, the Auth
companion, two dedicated Keycloak nodes and a private HTTPS Caddy edge. There
are no host ports, external networks or named volumes. All images and component
environment files are required explicit inputs; the lifecycle owner must bind
verified image IDs, newly owned private paths and a fresh isolated Docker daemon.
The Compose file itself does not prove that isolation or reserve build capacity.

The canonical issuer is `https://sso-edge:8443/sso/realms/armoney`; only node B has
the private `sso-service` alias. Both nodes share the disposable SSO database and
JDBC discovery. The edge uses newly generated private fixture certificates (server certificate
and key only, never the CA private key) and HTTP/1.1 upstream. It restores only
NET_BIND_SERVICE to match the official Caddy executable file capability; this
is not host publication or a claim of tested startup. The test browser must explicitly trust the ephemeral fixture
CA in its own disposable trust store; global host trust and TLS verification
must not be weakened. No realm import, secrets or enablement are baked into
images. Phase-specific private SSO inputs select disabled provisioning, then the
root stops both nodes before restarting enabled with the read-back UUID.

The companion image Dockerfile also defines separate `fixture-user` and
`fixture-sms` targets using real production service distributions without test
classes. A bounded isolated run at `d579ebbf` built all four targets and started the
disposable PostgreSQL database and Caddy. Both Keycloak nodes exited with a
build-time `kc.http-relative-path` mismatch before realm provisioning. The run
failed at OFF readiness in 313.921 seconds; owned resources were removed. The
fixture Dockerfile now pins `/sso` before augmentation, matching optimized runtime
configuration. A fresh run at `f01ef2f3` rebuilt all four images and passed both-node
HTTP and verified edge-TLS readiness. It then failed at provisioning in 232.078
seconds; the runner exited 1, owned resources were removed and the CI controller
restored. A diagnostic run at `64d007c0` failed at discovery validation on node A despite HTTP 200, in 229.093 seconds; owned resources were removed. Source analysis identified the full `KC_HOSTNAME` URL missing `/sso`. All three fixture hostname settings now include that path; strict issuer validation is preserved. A fresh run at `af81b793` passed discovery and then failed at client readback on node A (HTTP 200), in 231.656 seconds; owned resources were removed. The rejected client predicate remains unknown. No client or security validation was relaxed. The helper now records only a fixed
checkpoint, optional node A/B and optional HTTP status on private failure output.
Client readback failures additionally identify one fixed local predicate without
recording observed values; strict client acceptance remains unchanged. The
expanded diagnostic suite passed 17 portable tests with mocked transport,
independently retested; this is separate from runtime proof. Success output remains unchanged. Diagnostic reporting
does not fix provisioning. Cluster association, realm/user provisioning and the
complete native topology remain unverified. The companion has
no health endpoint; only Auth/User/SMS expose `/health/ready`.

`provision-code-intent-realm.py` is the phase-one private-input helper. It accepts
literal isolation/disabled gates, a validated canonical issuer, synthetic admin
credentials and a complete synthetic UAE user through a private JSON file or
stdin, then writes an exclusive private result file. It refuses an existing
realm on either fixed node before mutation, validates realm/client/flow/user
readbacks and captures the persisted subject. Nine lightweight synthetic unit
tests passed; no actual import or login is implied. A failed import/provision
requires owned disposable teardown, not an automatic retry against the partial
realm. Observed 503 fixture responses show unavailability only. Physical DNS
cancellation, flags, native flow and authority remain independent runtime gates.

## Provider-owned continuation binding

The fixture bind request is now exactly `experiment_handle` and `command_id`;
the former caller-supplied `stage_handle` is rejected as an extra field. The
provider retrieves Auth readiness using its existing provider credential and
immutable preparation association, validates the canonical handle and pins the
first successful value. A successful bind replay freshly revalidates that same
handle without replacing it or extending any deadline. Changed commands, busy
ownership, claimed continuation, malformed/changed evidence, cancellation and
late responses remain fail-closed. Pure state reads perform no work.

This repairs a fixture-only gap: diagnostic READY exposed no handle through the
controller interface, yet the previous bind request required one. The controller
does not gain provider-to-Auth credentials, Flow input or authority. Native form
validation, genuine code generation, stage13, transaction COMMIT, real node-B
membership and original lookup/confirm remain unchanged. All eight new regression
cases executed in unfiltered checks at `d579ebbf`: 165 tests across 29 suites,
zero failures, errors or skips, with `verifyFixtureJars` executed. Independent
artifact verification matched the archived sources to that revision. The run
took 93.5 seconds and owned resources were removed. Fresh augmentation/startup
and actual native acceptance remain required. Production and Auth wire contracts
are unchanged.

Provider preparation now waits one second after each PREPARING response before
polling Auth STATE. Waiting is bounded by the original setup and experiment
cutoffs, with cancellation checked before and after. This avoids exhausting the
existing Auth control budget; no rate limit, mutation retry or deadline renewal
was added. Readiness observation can be delayed by up to one polling interval.
All four new pacing/cutoff/rate-rejection/cancellation regressions executed at
`64d007c0`: 169 tests across 29 suites, zero failures, errors or skips, with
`verifyFixtureJars` executed. Independent artifact verification matched all 857
archived source files to that revision. Native acceptance and mandatory CI
remain separate gates. Mandatory CI run `39083572626` completed successfully for
`64d007c0`; neither that CI nor the 169-test result covers later source changes.

## Private fixture configuration

`auth-service/src/test/fixtures/build-code-intent-env.py` creates a fresh private
POSIX-only directory containing separate Auth Main/authority/User/SMS/database
and disabled-SSO environment files, a provisioning input and material inventory.
Actual write entrypoints reject non-POSIX execution. The ON operation requires
a caller-supplied canonical subject captured from successful provisioning; it
preserves generated material, enables code-session observation and creates a new
SSO input without overwrite. It does not prove subject persistence or perform
provisioning/restart. The lifecycle owner must stop both nodes before selecting it.

The provisioning JSON matches the actual helper parser: literal isolation and
OFF gates, canonical issuer, synthetic admin credentials and a complete UAE
user. Thirteen lightweight tests passed, including parser-to-parser agreement;
Windows tests mock the platform guard and establish no POSIX permission or NTFS
ACL evidence. Files use exclusive no-follow writes on the intended Linux runner.
Reads assume a trusted owned private parent. Partial failures may leave files;
retry requires disposal rather than overwrite. Full generated material plus
explicit additional provider-owned material is checked for raw/decoded aliases;
unseen external inventories are not proven. Auth retention/encryption keys are
not copied into SSO. LOCAL SMTP TEST still uses an injected local peer rather
than real Mailtrap delivery.

The provider's Auth companion destination is the fixed
`http://auth-code-intent-fixture:8091`; the dedicated Compose service uses that
name. Configuration and static Compose parsing do not establish DNS or startup.
