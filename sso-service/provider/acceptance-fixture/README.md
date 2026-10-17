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
