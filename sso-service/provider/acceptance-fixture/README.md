# Disposable observer acceptance fixture

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
