# ArMoney browser client

Tracking: [issue #44](https://github.com/aarbabaev/ArMoney/issues/44).
Source implementation is ready for independent review. Deployment and real backend
browser acceptance are pending; this document is not a readiness certificate.
Assistant integration uses the protected stateless contract delivered by issue #43.

## Source baseline and parity audit

Remote main baseline: `112b01d90c247bd774db7fa9f9720edcc0b439f8`.
Main integrated for final validation: `1e312ee5ba10564a396bfdd3ba71cb53f23179a4`.
Android semantic design uses the shared [tokens](android-design.md).
The original sibling arman-bank directory is a preserved source snapshot without
Git metadata. Its account/detail/demo-card functionality is included in scope,
but it cannot be honestly assigned a Git revision. Current main Android includes
cursor pagination that the snapshot lacks. Source and actual checks take precedence
over the older Android guide's newest-100 description.

| Screen / action | Android source baseline | Gateway / local state | Browser scope and limitations |
| --- | --- | --- | --- |
| Hosted sign-in / callback | Login.kt, AuthorizationJournal.kt | OIDC code/S256; POST /v1/auth/sso; GET /v1/auth/me | Dedicated armoney-web; state transaction tab-scoped, credentials memory-only; reload requires login |
| Logout / expiry / account | BankModel.kt, SecureStore.kt | POST /v1/auth/logout; independent 30-minute bank session | Clear active owner immediately; preserve durable unresolved intent; browser provider cookie is separate |
| Home / wallet list / creation | MainActivity.kt, BankModel.kt | GET/POST /v1/wallets; GET /v1/wallets/{id}/balance | AED, ACTIVE/CLOSED plus PENDING/READY; no automatic funding, unavailable balances remain unavailable |
| Account details / copy / send shortcut | Snapshot MainActivity.kt, AccountPresentation.kt | Owned wallet ID and loaded participant history | Identifier explicitly not IBAN; ready-gated source choice; filter only loaded history, disclose partial traversal |
| Phone lookup / confirmation | MainActivity.kt, BankModel.kt | POST /v1/recipients/resolve | Canonical UAE phone, confirmed identity/name; phone is user-claimed, no SMS ownership verification |
| Review / submit / recover transfer | PaymentRecovery.kt, SecureStore.kt | POST /v1/payments + requester-scoped Idempotency-Key | Exact fils and immutable payload; durable marker before POST, same-key retry after uncertain result; no false completion |
| History / load more / details | CursorPage.kt, Contracts.kt, BankModel.kt | GET /v1/payments/page; GET /v1/payments/{id} | Real signed owner-bound cursor pages; legacy GET /v1/payments remains newest 100; no invented client pagination |
| Inbox / load more / mark read | MainActivity.kt, CursorPage.kt | GET /v1/notifications/page; POST /v1/notifications/{id}/read | Owner-scoped in-app records; preserve immutable fields/read timestamp; no background push |
| Profile name / phone / status | MainActivity.kt, BankModel.kt | GET/PUT /v1/users/me | Name edit and server phone/verification display; no invented verification action |
| Demo card creation / freeze / close | Snapshot DemoCards.kt, DemoCardScreen.kt | Browser-local origin/verified-owner storage | Virtual/physical demos, ACTIVE/FROZEN/CLOSED, terminal closure confirmation, one nonclosed per wallet/type; no issuing/delivery/sync |
| Assistant chat | Merged issue #43 | POST /v1/assistant/messages | Current-session bearer, one volatile turn, verified integer facts, demonstration citations and source/time/scope; explicit unavailable/busy/empty/evidence outcomes |

## Browser trust and exact money

See [ADR 0021](adr/0021-browser-spa-session.md). All banking calls go through
gateway on the same HTTPS origin. No service keys, database connection or model
API key exists in Angular. User IDs never authenticate requests. The server still
owns resource isolation, balance correctness and transfer completion.

Browser storage is not Keystore-equivalent. Credentials are not stored durably.
Separate local command/card records are scoped by origin and verified owner;
corruption fails closed and does not silently overwrite evidence. Cross-tab locks
serialize command changes. Logout, expiry and old responses must not reveal another
owner's data. Clearing site data or browser eviction can destroy recovery intent;
reconcile server history before creating a replacement after such loss.

Amounts are integer AED fils, represented using bigint/lossless JSON across the
full Java long wire range. Decimal entry is parsed textually, never via float.
Outbound amount_minor remains an unquoted JSON integer, as required by gateway.
History and notifications use actual server statuses, not optimistic success.

## Local startup

Use Node 24.15+ compatible with pinned Angular 22 and TypeScript 6.0.
Version compatibility is maintained in the [official Angular matrix](https://angular.dev/reference/versions).
Dependency/install and development commands are in [web/README.md](../web/README.md).

The opt-in static-host overlay expects the existing HTTPS/SSO configuration:

```sh
docker compose -f compose.yaml -f compose.sso.yaml -f compose.web.yaml up --build -d
```

Default URL: https://localhost:8443/. The web container has no host-published port;
the existing edge serves assets and allowlisted gateway/Keycloak paths. This command
is an operator instruction, not a claim the persistent installation was updated.
Use the existing Compose project and secrets; never delete/recreate volumes.

The realm export contains exact browser callbacks for https://localhost:8443 and
https://localhost:4200, with /oauth/callback and /oauth/logout paths. Existing realms
ignore import changes on restart: an administrator must deliberately provision and
read back the new client, preserving users, issuer and existing clients. See
[SSO README](../sso-service/README.md). LAN/custom hosts require exact origin/redirect
configuration and trusted certificates; no wildcard or TLS bypass is supported.

Development uses HTTPS https://localhost:4200 and proxies /v1 and /sso to the trusted
edge. Install/trust a local CA and configure the development certificate and Node
CA trust; do not disable proxy certificate validation. The configured issuer must
be consistent across proxy discovery, callback redemption and server introspection.

## Verification and remaining gates

Transport fixtures establish browser behavior only. Financial acceptance requires
the actual disposable backend, durable payment result and unchanged/balanced ledger
outcomes. Real browser smoke must cover callback/login, logout/expiry, owner isolation,
recovery across reload/tabs and demo corruption. Full Gradle check, native/SSO
regressions and mandatory exact-head CI remain required for this runtime change.

SEC-WEB-001 (logout during pending work and stale card responses), session-generation
races and QA pagination, immutable-notification and card-capacity findings have developer
fixes. Independent exact-revision retest remains required. Final PR records exact revision,
executed commands, outcomes, blocked checks and deployment status. No device acceptance or persistent deployment is implied by a build.

The profile uses synchronous reactive form controls so initial template-driven
registration cannot overwrite an early edit. Its browser regression enters text
at input insertion and checks retention and the submitted profile payload.
SEC-WEB-002 keeps candidate session expiry local until the callback generation
and identity checks pass. SEC-WEB-003 distinguishes an absent IndexedDB key from
a present corrupt record, including a stored `undefined` value, using key and
value reads in the same transaction. Corruption blocks replacement and payment
submission while preserving the saved record. Focused regressions cover these
cases; exact-head CI and deployment verification remain separate gates.

### Local backend evidence (2026-10-13)

Primary executed `gradlew.bat --no-daemon check` with Java 21 and real PostgreSQL
Testcontainers: BUILD SUCCESSFUL, 34 tasks, 10m8s. XML reports contain 314 unit
and 186 integration cases, zero failures/errors/skips. The tested backend source
includes the browser allowlist/mandatory azp change; no service source changed
subsequently. Main design merge 7a99a668 changes Android presentation only.
This local evidence does not replace exact-head CI or disposable web/SSO smoke.
Compose config with synthetic configuration values and Python AST validation of
web-smoke, sso-smoke and browser-CA helper also passed; these are structural checks,
not runtime acceptance.

### Browser developer evidence

Angular 22.2.2 production build and pinned dependency audit passed; audit reports
zero vulnerabilities. Vitest covers exact long money, immutable command recovery,
held logout/expiry operations, session generation, cursor supersession and notification
immutability. Six Playwright fixtures cover mobile/desktop light/dark axe checks,
exact money, reload recovery, two real tabs/Web Locks, demo cards and storage corruption.
These fixtures use a separate HTTP loopback test entry and synthetic sessions;
production builds exclude that entry. No TLS certificate bypass is configured.
Independent execution, exact-head CI and real disposable smoke are tracked in
[PR #49](https://github.com/aarbabaev/ArMoney/pull/49); pending checks are not success.


### Assistant browser integration (2026-10-14)

The browser sends only the current question to the same-origin protected gateway.
It never calls the provider or private delegation routes. Request limits are 2,000
Unicode code points and 4,096 encoded bytes; response limits and typed facts are
validated before display. The client allows 40 seconds for the gateway's 35-second
deadline, sends no automatic retries, and renders all returned text as escaped text.
One current answer lives in memory; logout/expiry clears it and session generations
discard late responses. Turns do not supply conversational history. Financial facts
remain separate from demonstration citations, with source, Asia/Dubai time and scope.
No answer is financial command acceptance. The UI discloses limited history and
provider retention; configured assistant outages remain explicit unavailable states.

Developer Vitest coverage now includes authenticated request shape, exact Int64
facts, input/response bounds, malformed facts/provenance, concurrent-turn rejection,
logout/new-owner races, 401 expiry and 400/413/429/503 handling. Browser transport
fixtures cover escaped citations, exact amounts, accessibility and stale-answer
removal after outage; these are client evidence, not actual-provider acceptance.

The static image removes Caddy's unneeded low-port file capability before running
on port 8080. This retains the overlay's read-only filesystem, cap_drop ALL and
no-new-privileges. The orchestrator reproduced the original execution denial and
verified the rebuilt image runs and serves index.html with those exact restrictions
in an isolated network-none container. Persistent rollout remains a separate gate.
