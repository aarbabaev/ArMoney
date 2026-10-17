# ArMoney  -  System and Agent Team Architecture

> The main project map, updated 2026-10-13 from main
> `ce5175bbf2d4183b16f76977ef6f7627c2ed5ee2`, including the assistant release in PR #46.
> Exact-head checks and deployment evidence are recorded separately in that PR.
> Describes the source code, not the guaranteed state of running containers.
> This revision adds the #30 Saga design and #31 optional Kafka infrastructure
> source; Kafka cluster acceptance and deployment remain unverified.

The staged internal proof-creation consumer described in
[ADR 0042](docs/adr/0042-staged-proof-eligibility-consumption.md) now rereads
current eligibility evidence after persistence work. Its source security review
and execution audits are clear: the local Auth check passed 766 tests across 43
suites on 2026-10-16 with no failures, errors or skips. Exact integration-head CI
and runtime acceptance remain pending. Production proof routes
retain UNAVAILABLE, managed banking denial remains unconditional, and no
provider adapter, ACTIVE status or session activation is added.

## Navigation

1. [Readiness and stack](#readiness-and-stack)
2. [Services and runtime](#services-and-runtime)
3. [API and authentication](#api-and-authentication)
4. [Data and ledger](#data-and-ledger)
5. [Phone P2P and notifications](#phone-p2p-and-notifications)
6. [Agent team](#agent-team)
7. [Communication and remediation](#communication-and-remediation)
8. [Verification and delivery](#verification-and-delivery)
9. [Keeping documentation current](#keeping-documentation-current)

## Readiness and stack

A banking backend with service boundaries for identity, profiles, wallets, payments, and ledger accounting.
**M1 is the first functional milestone:** authorized public P2P, durable recovery,
phone recipient confirmation, and a native client. Completion requires passing
exact-revision CI and independent review; source implementation is not deployment evidence.

| Area | Current state | Next steps |
| --- | --- | --- |
| Auth | Password login and Keycloak exchange, stable local identities, opaque sessions | Global provider logout/revocation integration |
| SSO | Optional Keycloak realm, browser authorization code + PKCE | Production identity controls, MFA, email verification |
| iOS | Existing native SwiftUI login, wallets/balances, transfers/history, notifications and profile (iOS 18+); feature development paused | Physical iPhone validation and regression CI paused by owner |
| Android | Native Kotlin/Compose SSO, wallets/balances, phone transfers/history, inbox and profile (API26+) | Physical-device/LAN acceptance; exact-revision CI evidence in the delivery PR |
| User | Profile, optional immutable email-prefix sharding and central unique UAE registration-phone directory | Prefixes do not guarantee uniform load; automated ownership proof requires a separate approved provider |
| Wallet | Metadata, durable ledger provisioning, owner-scoped live balances | Lifecycle controls |
| Ledger | Private accounts, balances, atomic postings; optional two direct physical standbys and fenced balance reads | Manual failover; production HA and backup/PITR |
| Payment | Durable P2P, requester idempotency, recovery, history, in-app notifications and transactional Mailtrap email outbox | Email disabled until configured; real sending requires verified contact; duplicates possible after ambiguous provider acceptance |
| AI assistant | Implemented read-only C# service, local policy RAG, constrained bank reads and Android screen; local rollout verified on 2026-10-13 | Finite demonstration topics, English extracts, at most six wallets and first-page history of 50; physical-device/live-owner acceptance not run |
| Gateway | Auth, profile/phone, wallets/balances, P2P and notifications | Further hardening |
| Local SMS sandbox | Optional authenticated capture API and web inbox, bounded persistent file storage; no carrier delivery | Identity OTP and transaction producers are separate integration tasks |
| Operational metrics (#22) | Source: six-service private readiness, payment backlog counts/oldest unresolved ages, process-local worker failure counters, optional named-replica observations and local collector | Baseline and replica observations deployed with bounded independent audits on 2026-10-16; age local checks passed 272 tests; worker/age exact-head CI and rollout remain pending; counters reset and are unknown until first failure |
| Kafka infrastructure | Optional source-defined three-controller/three-broker KRaft cluster, mutual TLS, exact service ACLs and cold snapshot helper | Real-cluster acceptance and deployment pending; service adapters and financial Saga recovery remain planned |

A payment email presentation candidate adds HTML/plain text and a frozen optional
profile display name; it is not yet validated on current CI or deployed. Auth
remains the destination-verification authority. See [ADR 0030](docs/adr/0030-transaction-email-presentation.md).

Auth also contains a default-disabled registration verification delivery worker
and encrypted intent/challenge Store, described in
[ADR 0031](docs/adr/0031-registration-verification-delivery.md) and the
[delivery contract](auth-service/VERIFICATION-DELIVERY-CONTRACT.md). This clean
source slice has not been deployed or activated; clean-revision acceptance is
pending. Main registers no verification/proof routes (absent routes remain 404),
starts no login-notice worker, and preserves current registration/session/ACTIVE
authority and working web SSO. Delivery acknowledgement can open a challenge
through a fenced Store operation; it cannot establish verified ownership, a
receipt, projection acknowledgement or ACTIVE identity. No new provider or
Compose activation is included. The unpublished V6 migration's lineage and the
limits of historical dirty-worktree evidence are recorded in the contract.

Java 21, Gradle multi-project, Javalin, PostgreSQL, Flyway, jOOQ, HikariCP;
Spock, Testcontainers, ArchUnit; REST/OpenAPI; Docker Compose, GitHub Actions.
Exact versions: [build.gradle](build.gradle), [runtime build](platform-runtime/build.gradle),
[Gradle wrapper](gradle/wrapper/gradle-wrapper.properties), [Compose](compose.yaml).
Spring, Redis and Kubernetes are not implemented. Optional Kafka infrastructure
is defined separately in [compose.kafka.yaml](compose.kafka.yaml); the banking
services still use their existing HTTP transport. Native iOS uses SwiftUI; Android uses Kotlin and Jetpack Compose;
Keycloak and Caddy are optional container services, configured by compose.sso.yaml.

### Internal call inventory

The [service call inventory](docs/internal-call-inventory.md) and
[auth/SSO inventory](docs/auth-transport-inventory.md) classify implemented calls,
trust, deadlines and failure behavior at the stated source revision. They are
preparation for issue #25, not a protocol migration or deployment. The #30
architecture decision remains a gate before changing affected flows; public
client/OIDC protocols remain unchanged.

### Proposed Kafka and Saga direction

[ADR 0022](docs/adr/0022-kafka-payment-saga.md) and the
[message and recovery contract](docs/kafka-saga-contract.md) define the future
messaging direction. The following business flow is **planned, not implemented**;
the current payment worker still calls Ledger over HTTP. No Kafka service
adapter, financial inbox/outbox or Saga table is introduced by #31.

```mermaid
flowchart LR
    P["Planned Payment Saga and command outbox"] -.-> K["Kafka command topic: infrastructure only"]
    K -.-> L["Planned Ledger inbox: atomic debit and credit"]
    L -.-> Q["Planned result outbox: replication confirmation gate"]
    Q -.-> R["Kafka result topic: infrastructure only"]
    R -.-> I["Planned Payment inbox and terminal state"]
    I -.-> N["Existing independent notification delivery"]
```

Payment and Ledger retain separate local PostgreSQL transactions. Ledger is
unsharded; its physical standbys replicate one primary, while user profile shards
are a different data boundary. Stable payment IDs, authoritative confirmation
and reconciliation remain required. Broker acknowledgements, timeouts and
notification failures cannot establish completion or initiate refunds.

## Services and runtime

The optional [SMS sandbox](docs/sms-sandbox.md) runs from `compose.sms.yaml` on
a dedicated Docker bridge network with loopback-only ingress. It has its own persistent
volume and independent sender/reader credentials. It is not connected to the
identity or payment flows yet; see [ADR 0020](docs/adr/0020-local-sms-sandbox.md).

```mermaid
flowchart LR
    Operator["Local operator browser / reader credential"] --> Inbox["SMS sandbox HTTP inbox"]
    Fixture["Authorized test sender / sender credential"] --> Inbox
    Inbox --> Store["Private bounded file snapshot and replay tombstones"]
```

No external SMS provider is called. The sandbox does not decide whether an OTP
is valid and is not a production delivery or multi-instance messaging service.

### Optional private Kafka infrastructure

[ADR 0024](docs/adr/0024-local-kafka-infrastructure.md) and
[Kafka operations](docs/kafka-operations.md) describe #31's source-defined
infrastructure. An explicitly selected Compose project has three dedicated KRaft
controllers, three brokers and six project-scoped storage volumes, using the
pinned Apache Kafka image. It has a separate internal network, no published host
ports and no connections to banking networks or databases.

```mermaid
flowchart LR
    Tools["Private TLS admin and synthetic test clients"] --> Brokers["Three brokers: mutual TLS 9092"]
    Brokers --> Controllers["Three dedicated controllers: mutual TLS 9093"]
    Brokers --> Logs["Three project-scoped broker volumes"]
    Controllers --> Metadata["Three project-scoped metadata volumes"]
    Payment["Planned payment adapter: not connected"] -.-> Brokers
    Ledger["Planned ledger adapter: not connected"] -.-> Brokers
```

TLS authenticates distinct node/application principals; default-deny ACLs allow
payment commands and ledger results only for their owning producers/consumer
groups. Node/admin credentials are cluster-wide authority; applications are not
superusers. Each container mounts only its credential directory read-only, with
the signing CA excluded. Explicit formatting and identity-checked startup protect
storage from automatic reinitialization. Topics use three replicas, minimum ISR
two and disabled unclean election; this configuration is not measured durability.

The cold snapshot helper checks node/cluster metadata, exact directory/file
inventory and hashes. Its caller must prove all six nodes stopped, validate
resource ownership and restore to empty volumes in a separate project. Offline
synthetic file tests do not establish real Kafka recovery or RTO/RPO. The CI job
must run the isolated failure and restore suite before runtime acceptance; this
map makes no deployed-cluster readiness claim.

The orchestrator owns Kafka Compose/CI, storage and security configuration and
this map. Payment/ledger owners own future service adapters; independent QA and
the security auditor verify transport/recovery and trust boundaries, followed by
the independent reviewer of the integrated revision.

**Base backend connections are shown below; the optional native-client SSO edge is described separately.** Wallet provisions accounts through the
private ledger API. Payment validates user/wallet mappings and posts through ledger.

```mermaid
flowchart TB
    C["Postman / HTTP client"] -->|"127.0.0.1:8080"| G["app-gateway"]
    subgraph NET["Docker network: arman-bank_bank"]
        G -->|"Auth API / session validation"| A["auth-service"]
        G -->|"Profile"| U["user-service"]
        G -->|"Wallets and balances"| W["wallet-service"]
        G -->|"P2P, history, notifications"| P["payment-service"]
        P -->|"Private notification email lookup"| A
        P -->|"Registered UAE recipient"| U
        A -->|"Private immutable phone binding"| U
        P -->|"Private wallet mapping"| W
        P -->|"Idempotent posting"| L
        L["ledger-service: private API"]
        W -->|"Durable account provisioning and balance reads"| L
        A --> AD[("auth-db")]
        U --> UD[("user-db: placement and phone directory; legacy profiles")]
        U --> US1[("Optional user-shard-s1: profile data")]
        U --> US2[("Optional user-shard-s2: profile data")]
        W --> WD[("wallet-db")]
        P --> PD[("payment-db")]
        L --> LD[("ledger-db")]
    end
    P -->|"Optional HTTPS API or Sandbox SMTP STARTTLS"| MT["Mailtrap: Sandbox SMTP or HTTPS API"]
```

| Module | Responsibility | Boundary |
| --- | --- | --- |
| app-gateway | External routes, session validation, header sanitization | No database or financial logic |
| auth-service | Credentials, SSO identity mapping, sessions, limits | Does not own profiles |
| user-service | Profile placement, sharded display names and central registered UAE phone directory | Does not issue tokens or claim SMS proof; owns all profile shards |
| wallet-service | Owner, currency, lifecycle and durable ledger mapping | Not the source of balances |
| payment-service | Transfer intent, client idempotency, recovery, notifications and email outbox/dispatcher | Completion requires matching ledger confirmation; email failure never changes money |
| ledger-service | Accounts, balances, immutable paired postings | No public funding API |
| platform-runtime | HTTP lifecycle, DB wiring, migrations, health and opt-in operational metrics adapter | A library, not a separate service; no shared business entities or collection from service databases |

### Code boundaries

```mermaid
flowchart LR
    HTTP["HTTP adapters / Javalin"] --> APP["Application use cases and ports"]
    APP --> DOMAIN["Domain: JDK and own types"]
    SQL["Persistence adapter / jOOQ"] --> PORTS["Application ports"]
    MAIN["Main: wiring"] --> HTTP
    MAIN --> APP
    MAIN --> SQL
    MAIN --> RT["platform-runtime"]
    SQL --> RT
```

This is a rule for organizing use cases, not a claim that every scaffold contains all
layers. Ports are introduced for real dependencies. Services do not import each other's
Java code or read each other's databases. ArchUnit checks architectural constraints.

### Containers and local environment

Java container startup adds `infra/runtime/java-security.properties` to the JDK
security defaults. Positive, negative and stale JVM DNS caches are disabled for
dynamic Compose service names: restarted containers may exchange IP addresses.
The setting is container-scoped and does not replace the JDK security policy.


```mermaid
flowchart LR
    SRC["Source + Gradle wrapper"] --> BUILD["Docker build: JDK 21"]
    BUILD --> DIST["Service installDist"]
    DIST --> RUN["JRE 21 / bank user"]
    RUN --> PG["Service-owned PostgreSQL"]
    PG --> VOL["Persistent volume"]
    ENV["Local .env"] -->|"Compose substitutes values"| RUN
```

Inside Compose, services listen on 8080 and PostgreSQL on 5432. Base Compose publishes
only the gateway on loopback; the five databases have separate volumes. Flyway runs
while DB wiring is created, before the HTTP listener opens. Migration failure prevents startup.

Variables: `PORT`, `DB_URL`, `DB_USER`, `DB_PASSWORD`; protected internal APIs use
`INTERNAL_AUTH_KEY`; the gateway uses `AUTH_BASE_URL`, `USER_BASE_URL`, `WALLET_BASE_URL`, `PAYMENT_BASE_URL`.
Payment uses `USER_BASE_URL`, `WALLET_BASE_URL`, `LEDGER_BASE_URL`.
Auth also requires `USER_BASE_URL` for idempotent registration-phone binding.
Secrets come from local configuration; their values must not appear in documentation.
A regular Java launch **does not read `.env` automatically**: configure environment
variables/an env file in IDEA; Compose uses its own substitution mechanism.

The user's local `compose.override.yaml` exposes databases to DataGrip on
`127.0.0.1`: auth **5433**, user **5434**, wallet **5435**, payment **5436**, ledger
**5437**. This is not guaranteed configuration for a fresh clone: check your own
override. All database containers use `bank` as the database and user name; the password is local.
Restarting does not require deleting volumes. See [README](README.md) and [IDEA](docs/onboarding.md).

## Native clients and SSO

The optional overlay adds an HTTPS edge and a Keycloak-owned PostgreSQL database.
Caddy supplies default SNI from ARMONEY_HOST for clients using numeric IP origins; certificate name and CA validation still apply. Only the edge is bound to the selected LAN interface; the existing gateway binding
remains loopback. Keycloak administration and management paths are not exposed on
the LAN listener. An optional workstation-only override exposes the admin console
through Caddy at `https://localhost:9443`, bound to `127.0.0.1`; it uses a separate
admin hostname and master-realm frontend URL, preserving the ArMoney issuer.
See [local administration](docs/sso-and-ios.md#optional-host-only-administration).

```mermaid
flowchart LR
    I["ArMoney SwiftUI / iPhone"] --> E["HTTPS edge :8443"]
    AND["ArMoney Kotlin / Android"] --> E
    WEB["ArMoney Angular / Browser"] --> E
    E -->|"Optional web overlay: SPA assets"| STATIC["Web static host"]
    B["System authentication browser"] --> E
    OP["Operator on Docker host"] -.-> ADM["Optional loopback TLS :9443"]
    ADM -.->|"Admin / master realm only"| K
    E -->|"/v1 APIs"| G["App gateway"]
    E -->|"Allowlisted /sso routes"| K["Keycloak"]
    K --> KD[("SSO PostgreSQL")]
    G -->|"SSO access-token exchange"| A["Auth service"]
    A -->|"Private introspection"| K
    A --> AD[("Identity mapping / opaque sessions")]
```

```mermaid
sequenceDiagram
    participant I as Native app
    participant B as System browser
    participant K as Keycloak
    participant G as Gateway
    participant A as Auth
    I->>B: Authorization code request with S256 PKCE and state
    B->>K: Login or reuse SSO cookie
    K-->>I: Exact callback with code and state
    I->>K: Code plus original verifier
    K-->>I: Provider access token
    I->>G: POST /v1/auth/sso
    G->>A: Bounded exchange with service key
    A->>K: Introspect at pinned internal endpoint
    K-->>A: Active token and claims
    A->>A: Validate claims, map issuer/subject to local UUID
    A-->>I: Opaque ArMoney session through gateway
    I->>I: Keychain on iOS / Keystore-backed storage on Android
```

No identity is linked by email. Existing password identities and their wallet UUIDs
remain unchanged; SSO-only /auth/me responses have email=null. The client consumes
no ID-token claims or refresh tokens. Local sessions expire after 30 minutes;
provider logout/disablement does not immediately revoke issued local sessions.
Local logout and browser logout are separate operations, not global single logout.
The app supports profile onboarding and actual wallet PENDING/READY states, with
no fabricated balances or transfer success. TLS trust must be configured on each
Mac/iPhone/Android device; native code never bypasses certificate validation. See
[setup](docs/sso-and-ios.md), [iOS](ios/README.md) and [ADR 0008](docs/adr/0008-native-ios-and-keycloak-sso.md).

Android build/configuration and acceptance are described in [android/README.md](android/README.md),
[Android contract](docs/android-contract.md) and [ADR 0010](docs/adr/0010-native-android.md).
The native presentation uses the semantic light/dark tokens documented in
[Android design](docs/android-design.md); device visual acceptance remains separate
from JVM contrast checks and successful compilation.
The Android public client uses its own exact callback; both native clients map the
same issuer/subject to one bank identity. No new financial service is introduced.

## Browser client

The Angular project lives in web/. Its source and acceptance are tracked in
[issue #44](https://github.com/aarbabaev/ArMoney/issues/44), [web guide](docs/web-client.md)
and [ADR 0021](docs/adr/0021-browser-spa-session.md). Browser source is implemented;
this map does not claim deployment or completed end-to-end acceptance.

The optional web overlay routes SPA assets through the existing HTTPS edge;
/v1 and allowlisted Keycloak paths remain on that same origin. Browser tokens are
transient memory-only; a new SSO flow is required after reload. The armoney-web
public client uses code/S256 and exact local HTTPS callbacks. Auth requires matching
web client_id/azp and API audience; issuer/subject still map to the existing bank
identity. Native callbacks and absent-azp compatibility are preserved.

```mermaid
flowchart LR
    U["Browser user"] --> SPA["Angular SPA: transient bank session"]
    SPA -->|"Code / S256 / exact state callback"| K["Keycloak via HTTPS edge"]
    SPA -->|"Bearer / same-origin /v1"| G["Gateway: revalidate session"]
    G --> A["Auth: issuer and subject mapping"]
    G --> B["Owner-scoped banking APIs"]
    SPA --> D[("Origin / verified owner local data")]
    D --> P["Immutable unresolved command and original key"]
    D --> C["Demo cards only: no payment credentials"]
    G -->|"Protected stateless message"| AI["Read-only banking assistant"]
```

Browser assistant turns use the protected POST /v1/assistant/messages contract with
current session authorization, a 40-second timeout and no automatic retry. Questions
and answers remain in memory; logout/expiry clears them and late responses cannot
cross sessions. Exact financial facts, citations and provenance are validated before
display. Successful response fixtures establish client behavior; real unavailable-service
acceptance is exercised in disposable SSO CI. This does not claim a browser turn
against the deployed model. Web hosting removes Caddy's unneeded low-port file
capability while retaining a read-only root filesystem and dropped capabilities.

Balances and payment amounts use lossless int64 JSON with integer fils in the
browser; JavaScript floating-point numbers cannot preserve the full wire range.
Uncertain transfer commands are durable before submission and isolated by verified
owner with cross-tab coordination. Logout/expiry never convert uncertainty into
success or authorize a replacement key. Browser storage is not Keystore-equivalent
and clearing/eviction can destroy recovery evidence. Demo card state is browser-local;
no issuing, delivery or cross-device synchronization is implemented.

Current main Android includes signed cursor pages. The preserved local Android
snapshot also includes account details and demo cards absent from that main source;
the browser audit covers both explicitly. Legacy newest-100 routes remain bounded.
The AI service belongs to separate issue #43; no invented assistant gateway route,
policy answer or live financial response is represented as implemented.

## API and authentication

Contracts are in each service's `src/main/resources/openapi.yaml`.
`/openapi.yaml` serves YAML, not Swagger UI. Gateway `/` is not a user interface; the optional web overlay serves the browser SPA at the HTTPS edge.

| Access | Method and path | Purpose |
| --- | --- | --- |
| Gateway | POST `/v1/auth/register`, POST `/v1/auth/login` | Registration / session |
| Gateway | POST `/v1/auth/sso` | Provider-token exchange; requires optional SSO configuration |
| Gateway + Bearer | GET `/v1/auth/me`, POST `/v1/auth/logout` | Identity / revocation |
| Gateway + Bearer | GET / PUT `/v1/users/me` | Own profile |
| Gateway + Bearer | GET / POST `/v1/wallets`, GET `/v1/wallets/{id}/balance` | Own wallets and live balances |
| Gateway + Bearer | PUT `/v1/users/me/phone`, POST `/v1/recipients/resolve` | Immutable registration phone and exact registered-recipient lookup |
| Gateway + Bearer | POST / GET `/v1/payments`, GET `/v1/payments/{id}` | Idempotent transfers and participant history |
| Gateway + Bearer | GET `/v1/payments/page`, GET `/v1/notifications/page` | Owner-bound cursor pages |
| Gateway + Bearer | GET `/v1/notifications`, POST `/v1/notifications/{id}/read` | Own in-app inbox |
| Private ledger | POST `/v1/ledger/accounts`, GET `/v1/ledger/accounts/{id}` | Account and balance |
| Private ledger | POST `/v1/ledger/transfers`, GET `/v1/ledger/transfers/{id}` | Posting/replay and outcome |
| Each service | GET `/health/live`, `/health/ready`, `/openapi.yaml` | Operational endpoints |

```mermaid
sequenceDiagram
    actor C as Client
    participant G as Gateway
    participant A as Auth
    participant DB as Auth DB
    participant S as User, Wallet or Payment
    C->>G: Login with email/password
    G->>A: Login + service key
    A->>DB: Verify credentials, persist session digest
    A-->>G: access_token and expiry
    G-->>C: Login response
    C->>G: Protected request + Bearer
    G->>A: Validate session through me
    A->>DB: Digest and expires_at
    A-->>G: Verified identity
    G->>S: Request + trusted identity + service key
    S-->>G: Own data
    G-->>C: Client response
```

Passwords are protected with Argon2id. The token is a random opaque string, not a JWT;
`sessions` stores its SHA-256 digest. Sessions last 30 minutes; logout deletes the session.
The gateway validates every protected business request through auth, replaces the client's
identity with the trusted identity, and does not forward the bearer token to user/wallet/payment.
Authorization failure blocks the request.

Only profile PUT receives `X-Identity-Email`, derived from the authenticated auth
response and normalized by the gateway. Client email headers are discarded;
malformed auth email metadata fails closed. Missing/null email is valid for SSO
and uses the configured profile default shard.

Ledger requires `X-Service-Key` and a trusted `X-Identity-Id`; the gateway does not proxy it.
The shared service key does not isolate compromised services from one another.
A persistent service's readiness checks its own database; user-service checks the
primary directory and all configured profile shards. Gateway readiness checks
only the gateway itself. `UP` does not prove downstream availability or P2P correctness.

### Opt-in operational metrics

Six Java services register `GET /internal/metrics` on their existing listeners only
when `INTERNAL_METRICS_KEY` is present. There is no fallback to
`INTERNAL_AUTH_KEY`; blank, non-printable/non-ASCII or whitespace keys and keys
outside 32â€“256 characters fail startup.
Business routes are configured first, followed by `OperationalMetricsHttp` in
its declared `(config, key, metrics, readiness)` order. Business authorization
middleware applies to `/v1/`; the metrics adapter checks its own `X-Service-Key`
without requiring an owner identity. Unauthorized scrapes return a generic 401
before invoking readiness or service observations. The endpoint must remain outside public gateway and
edge routes; listener isolation and dedicated key provisioning remain integration
responsibilities.

Each metrics adapter shares the actual readiness supplier used by
`/health/ready`. Authorized scrapes return Prometheus text format 0.0.4 with
`Cache-Control: no-store` and HTTP 200, including `armoney_ready` zero when the
probe fails or throws a runtime exception. The technical registry accepts fixed
enum measurements without caller-controlled labels; unobserved measurements are
omitted. Gateway readiness is local-only. Payment uses read-only bounded queries
for mapped pending payments and pending email outbox rows, including delayed and
leased work; failed collection removes those observations.

The local collector authenticates fixed private targets, refuses redirects and
environment proxies, bounds I/O and emits expiring atomic status snapshots plus
finite stdout state-transition alerts. Missing/stale data stays unknown; no
external notification provider is used. Payment worker failure counters are
process-local observations and remain unknown until the first failure; they
reset on restart. Oldest unresolved age is source-implemented with a database
anchor and unknown-on-invalid-time behavior. On 2026-10-16 its affected Java/PG
checks passed 272 tests with zero failures, errors or skips; exact-head CI,
actual Compose acceptance and rollout remain pending. Replica lag is a
separately verified operational observation.
An explicit `compose.metrics.yaml` override provides a
nonroot collector without published ports. The baseline from `a0fab1b` was
deployed and independently verified on 2026-10-16 with a dedicated private key,
six ready targets, two fresh snapshots and public internal-path 404. This does
not establish deployment of worker counters or replica observations, which remain
pending. CI source includes an isolated UUID-project fixture with owned-resource
cleanup, real edge exclusion and a reversible payment-database outage.
Baseline exact-head CI and isolated failure acceptance passed in PR #73;
each later integration still requires its own checks and rollout evidence.
Source wiring does not establish deployment. See [operational metrics](docs/operational-metrics.md)
and [ADR 0027](docs/adr/0027-local-operational-metrics.md).

```mermaid
flowchart LR
    S["Six Java services: private keyed metrics"] --> C["Local collector: bounded reads"]
    C --> J["Expiring atomic status snapshot"]
    C --> A["Finite local stdout alerts"]
    P["Payment-owned read-only backlog queries"] --> S
    E["Public edge: no metrics route"] -.-> S
```

## Data and ledger

### User profile placement

Optional [profile sharding](docs/adr/0012-user-profile-sharding.md) selects a physical
database at the first profile PUT using two normalized email characters and a
configured prefix map. The primary directory permanently pins identity UUID,
profile UUID and shard. Existing profiles are backfilled as `primary` without
moving data. Changes to email or the map affect no previously pinned identity.
Missing or unavailable assigned databases never trigger fallback writes.

Registration remains in auth-service and binds a central phone claim through
user-service; it does not provision a profile or choose a shard. Unmapped,
non-alphanumeric prefixes and email-less identities use the configured default.
The directory, registration claims, global phone uniqueness, attestation audit and lookup quota remain
centralized. Reservations and shard writes are separate durable transactions;
failed first writes require retry with the same pin. Prefix placement does not
guarantee balanced load or remove the primary directory dependency.

```mermaid
flowchart LR
    G["Gateway: verified identity and email"] --> U["user-service"]
    U -->|"Reserve or read immutable placement; phone state"| D[("Primary user-db")]
    U -->|"Pinned profile data"| S1[("Profile shard s1")]
    U -->|"Pinned profile data"| S2[("Profile shard s2")]
    U -->|"Legacy or default profile data"| D
```


UUIDs shared across services are **logical references**, not cross-database foreign keys.
`owner_id` means the auth identity UUID, not the profile ID.

```mermaid
flowchart LR
    ID["Auth identity"] -. "identity_id" .-> PROFILE["User profile"]
    ID -. "owner_id" .-> W["Wallet"]
    ID -. "owner_id" .-> A["Ledger account"]
    W -. "wallet_id and confirmed ledger_account_id" .-> A
    P["Durable payment intent"] -->|"Stable payment_id"| R["Transfer request"]
    P --> N["Owner-scoped terminal notifications"]
    R -->|"POSTED"| T["Immutable transfer"]
    T --> D["Debit account"]
    T --> C["Credit account"]
    T --> V["postings VIEW: debit and credit"]
```

| Database | Tables / guarantees |
| --- | --- |
| auth-db | `identities`: separate notification email and provider verification flag; nullable email/password_hash pair for SSO-only principals, unique non-null email and registration_phone (null for historical unenrolled users); `external_identities`: (issuer, subject) PK and unique local identity mapping; `sessions`: token_hash PK, FK identity_id, expires_at; `auth_attempts`: persistent limits |
| user-db | Existing `profiles` remain in place; `profile_directory` pins identity/profile UUID and shard and owns authoritative globally unique non-null phone state; `registration_phone_claims` binds unique identity and UAE phone before profile creation; operator audit and persistent lookup quota remain central |
| Optional user shards | `profiles`: display name keyed by pinned profile/identity UUID; phone state is overlaid from the primary directory |
| wallet-db | `wallets`: unique(owner_id,currency), AED, ACTIVE/CLOSED, PENDING/READY, unique ledger_account_id, durable retry lease; no balance |
| payment-db | `payments`: unique(requester_id,idempotency_key), request_hash, wallet IDs, amount/currency, PENDING/COMPLETED/REJECTED, recipient/account snapshot, fenced lease/backoff; notifications and email outbox unique(owner,payment,type); durable email leases, attempts and delivery outcomes |
| ledger-db | `accounts`: unique wallet_id, owner, CUSTOMER/CLEARING, balance_minor; `transfers`: payment_id PK and account/currency FKs; `transfer_requests`: durable payload/outcome; `postings`: view |
| Every database | `flyway_schema_history`: technical record of applied migrations |

Accounts referenced in transfer_requests do not have to exist: this table also preserves
rejections. `accounts.wallet_id` is not yet validated through an HTTP request to wallet-service.
Exact columns, indexes, and constraints are defined in service migrations; published
migrations are append-only. The current DB owner has administrative capabilities:
triggers do not protect against an administrator. A restricted runtime DB role is future work.

### Optional ledger replicas

The isolated nine-store recovery drill passed again on 2026-10-17 at
`a3fd9cec8d1eb8a77dd0ddbd02c0a059153f498a`: 82.102 seconds capture,
231.073 seconds outage through acceptance, and 718.438 seconds total staging and
cleanup. Independent retained-evidence review and financial QA cover one completed
pre-capture synthetic payment, balanced AED replay and standby restart/rebuild.
They do not establish in-flight/concurrent recovery or production RTO/RPO.
Independent security review is also clear. Exact-head CI remains a separate gate; the workflow does not
invoke this full drill. See the dated evidence and retention limits in
[database recovery](docs/backup-recovery.md).

Saved logical backup preflight, persistent-store coverage and disposable recovery
tooling are documented in [database recovery](docs/backup-recovery.md). Disposable
application recovery passed on 2026-10-16 at `960087d863fbd4e57d0c1b78109ae91fe277b64d`:
nine restored stores, legacy/native PKCE sessions, owner isolation, original
payment replay, reconciled immutable AED journal, and read-only physical standby
restart/fresh-volume rebuild, with zero missing acknowledged fixture payments and
ownership-checked cleanup. Capture took 78.216 seconds and outage through all
acceptance took 225.969 seconds before cleanup. Independent QA matched the private
stage/runner receipts and three versioned source hashes. This same-host fixture
does not establish production RTO/RPO, PITR, HA, promotion, synchronous-quorum
recovery, the 200-owner baseline, assistant-model or production TLS recovery.
Subsequent integrated-revision CI/review and deployment remain separate gates.

`compose.ledger-replication.yaml` preserves `ledger-db` and adds two direct
PostgreSQL physical hot standbys. Application commits require WAL flush on at
least one synchronous standby. Only account/balance reads can use replica pools:
each query checks database/cluster/timeline/recovery identity and a primary WAL
replay fence, then falls back to primary when unavailable or stale. Provisioning,
posting and durable result lookup stay primary. Replicas never run Flyway.
The replicated profile requires an explicit post-transaction quorum WAL flush
confirmation before acknowledging provisioning or new/replayed payment results.
This prevents cancellation/restart recovery from returning a locally committed
but unreplicated result. Missing confirmation stays unavailable/uncertain.

```mermaid
flowchart LR
    L["ledger-service"] -->|"Commands, results, fence and fallback"| P[("ledger-db primary")]
    P -->|"Direct WAL"| R1[("ledger-db-replica1")]
    P -->|"Direct WAL"| R2[("ledger-db-replica2")]
    L -->|"Fenced account reads"| R1
    L -->|"Fenced account reads"| R2
```

Read routing requires primary availability; it does not provide automatic outage
reads. With both synchronous replicas unavailable, commit acknowledgement waits;
timeouts retain uncertain command semantics. Promotion, fencing, reparenting and
application pool reconfiguration are manual. No HA manager, failover proxy or
production backup/PITR is introduced. Containers sharing one host are not independent
host failure domains. See [ADR 0013](docs/adr/0013-ledger-replication.md) and
[operations and disposable acceptance](docs/ledger-replication.md).

### Atomic posting  -  implemented

```mermaid
sequenceDiagram
    participant C as Trusted internal client
    participant L as Ledger
    participant DB as Ledger PostgreSQL
    C->>L: payment_id, accounts, currency, amount_minor
    L->>DB: BEGIN, reserve payment_id
    alt Previously persisted request
        L->>DB: Compare requester and full payload
        L-->>C: Persisted outcome or conflict
    else New request
        L->>DB: Lock both accounts in SQL UUID order
        L->>DB: Check owner, currency, funds, overflow
        alt Valid posting
            L->>DB: INSERT transfer, trigger updates both balances
            L->>DB: POSTED, COMMIT with deferred constraints
            L-->>C: POSTED
        else Business rejection
            L->>DB: Persist rejection, COMMIT without moving money
            L-->>C: Durable rejection
        end
    end
```

AED is the only supported currency. Money is represented as integer fils: `100` equals
1 AED. Wallet creation, payments and ledger posting reject other currencies.
Append-only migrations enforce this rule and refuse non-AED historical data without
conversion; see [ADR 0011](docs/adr/0011-aed-only.md). The amount must be positive, CUSTOMER balances cannot be negative,
and overflow is prohibited. One immutable transfer produces two opposite entries in the
postings view. The posting, balances, and terminal result commit in one PostgreSQL transaction.
Deferred constraints prevent committing an unfinished PENDING request.

A retry uses the same `payment_id`, requester, and payload; a changed request produces
a conflict. A durable insufficient-funds rejection remains a rejection even after later
funding. After a timeout, retry or look up **the same ID**: no response does not mean rollback.
A transaction failure rolls back its changes.
POST: 200 POSTED, 409 rejection/conflict; outcome GET: 200 even for a persisted rejection,
404 for a missing result or one belonging to another requester. Accounts open with zero balance.
There is no funding API; CLEARING fixtures are used only in isolated tests.
Details: [ledger](docs/ledger.md), [ADR 0005](docs/adr/0005-atomic-ledger.md).

### Durable wallet provisioning  -  implemented

POST /v1/wallets commits intent and returns 202 for ACTIVE/PENDING, or 200 for READY
and existing CLOSED wallets. GET includes provisioning_status and nullable
ledger_account_id. ACTIVE alone is not readiness: payments require READY.
Wallet requires LEDGER_BASE_URL (Compose supplies http://ledger-service:8080).

```mermaid
sequenceDiagram
    participant C as Client via gateway
    participant W as Wallet API
    participant DB as Wallet PostgreSQL
    participant JOB as Wallet background worker
    participant L as Ledger
    C->>W: Create wallet
    W->>DB: Commit metadata and PENDING intent
    W-->>C: 202 PENDING
    JOB->>DB: Claim one due ACTIVE wallet with a fenced lease
    JOB->>L: Idempotent account request using stable wallet UUID
    alt Valid matching response
        L-->>JOB: Existing or new account UUID
        JOB->>DB: Save READY mapping if claim is still current
    else Timeout, outage or invalid mapping
        JOB->>DB: Schedule durable bounded retry
    end
    C->>W: Poll own wallets
    W-->>C: READY with ledger_account_id after confirmation
```

The worker processes up to ten sequential claims per tick with no transaction held
across HTTP. Each claim has a 30-second lease; stale workers are fenced by a token.
The scheduler ticks after a one-second fixed delay. Failed attempts back off through
2/4/8/16/32/60 seconds; pending work is retained, not silently abandoned. HTTP has
2-second connect, 5-second request and 6-second whole-response deadlines and a 4 KiB
response bound. Worker database operations use a 2-second lock timeout and a
5-second statement timeout. Shutdown waits up to 10 seconds for the worker before
closing resources. If it cannot stop, shutdown reports an explicit error and leaves
resources open until process termination; the durable lease permits recovery.

V3 initializes existing wallets as PENDING. Existing ACTIVE wallets reconcile;
CLOSED wallets are neither reopened nor claimed. An administrative close after a
claim cannot cancel an in-flight ledger call atomically and may leave an unmapped
zero-balance account; no public close endpoint exists. A ledger commit followed by a lost
response or wallet crash is recovered with the same wallet UUID. A valid replay may
return a nonzero ledger balance; wallet only owns the mapping. Mismatched owner,
wallet or currency never becomes READY. See [ADR 0007](docs/adr/0007-wallet-ledger-provisioning.md).

## Registration phone binding

```mermaid
sequenceDiagram
    participant C as Native client
    participant K as Keycloak
    participant A as Auth service
    participant U as User service
    C->>K: Register with required UAE phone and password
    K-->>C: Authorization code through PKCE
    C->>A: Exchange provider token through gateway
    A->>A: Verify issuer and subject then reserve unique phone
    A->>U: Bind identity and immutable phone privately
    alt Identical durable binding acknowledged
        U-->>A: 204
        A-->>C: Bank session
    else Conflict or unavailable
        U-->>A: 409 or unavailable
        A-->>C: No new session and retry retained enrollment
    end
```

## Phone P2P and notifications

[HTTP contract](docs/p2p-contract.md) and [ADR 0014](docs/adr/0014-uae-registration-phone.md)
cover current phone admission and recipient eligibility. [ADR 0009](docs/adr/0009-phone-p2p-payments.md)
retains the durable payment design and optional local operator procedure.

```mermaid
sequenceDiagram
    actor C as Native client
    participant G as Gateway
    participant U as User
    participant P as Payment
    participant W as Wallet
    participant DB as Payment DB
    participant L as Ledger
    C->>G: Resolve exact registered UAE phone
    G->>U: Authenticated lookup, persistent quota
    U-->>C: Verified identity and display name via gateway
    C->>C: Confirm recipient, save immutable command/key in Keychain
    C->>G: POST payment with Idempotency-Key
    G->>P: Trusted requester and command
    P->>DB: Look up existing requester/key first
    opt New intent
        P->>U: Recheck phone maps to confirmed recipient
        P->>W: Validate source owner and both READY mappings
        P->>DB: Persist payment and account snapshot
    end
    P-->>C: PENDING or prior result via gateway
    loop Durable fenced worker until known outcome
        P->>L: Same payment UUID and account payload
        L-->>P: Matching POSTED or durable rejection
        P->>DB: Commit terminal state, notifications and email outbox atomically
    end
    C->>G: Refresh or continue history and own inbox
```

No shared distributed transaction is held across services. Ledger owns the money;
payment remains PENDING after timeout, malformed response or unavailable dependencies.
A crash after ledger commit is recovered by replaying the same ledger command. Terminal
notifications and email outbox events commit with payment state, with unique constraints preventing duplicate rows.
Email delivery is asynchronous and disabled by default. A separate worker resolves contact
through a private auth API and submits through fixed Mailtrap HTTPS endpoints or
Sandbox SMTP with required STARTTLS outside DB
transactions. Sandbox captures mail; real Sending requires verified contact. Durable
leases/backoff recover work, but provider acceptance followed by a lost acknowledgement
can produce duplicate email. Mail success is not a financial completion condition.
See [email operations](docs/email-notifications.md), [ADR 0015](docs/adr/0015-transactional-email-outbox.md)
and [SMTP transport ADR](docs/adr/0017-mailtrap-sandbox-smtp.md).

The recipient sees only completed incoming payments; the sender sees all its intents.
Legacy history and inbox endpoints return the latest 100 entries. Separate `/page`
endpoints provide owner-bound signed cursor pagination; Android uses load-more and
first-page refresh. Ordering uses immutable `(created_at, id)` keys, not offsets;
this is not a historical snapshot. See [pagination ADR](docs/adr/0018-cursor-pagination.md).

New registrations require a unique immutable UAE mobile number. Auth reserves it
across legacy and SSO identities, then idempotently binds it in user-service before
issuing a session. Keycloak requires the number as its username. Registration is not
ownership proof: claimed phones with a profile resolve even while phone_verified is
false. No SMS, push provider or external verification service is connected. Lookup
is exact and limited to 30 attempts per requester per 60 seconds, including misses.
It reveals the matched display name, not email or a user directory. Public phone
changes are disabled. Historical accepted payments remain readable and replayable.
See [ADR 0014](docs/adr/0014-uae-registration-phone.md) for enrollment failure recovery,
provider-account collisions and historical-user migration limits.

The native clients expose wallets, transfers, notifications and profile. Amounts use
exact Int64/Long minor units. An uncertain command/key is retained in device-only
Keychain (iOS) or Keystore-backed encrypted storage (Android), scoped per origin and
identity across restart/logout; retry uses the same payload. Foreground/manual refresh
fetches in-app notifications; no background delivery or OS push is promised.
Existing scaffold payment rows without recipient/account mappings are not executed or
exposed. See [M1](docs/m1.md) for acceptance and remaining scope.

## Agent team

Agents run for specific tasks inside Codex; they are not bank containers or permanent
background processes. The main chat acts as the orchestrator. Up to six subagents run
concurrently when the active session permits it; only roles needed for actionable work are activated. The primary orchestrator is separate. Prefer four implementers, QA and an independent reviewer; heavy build concurrency remains capped at two isolated CI slots.
Models/permissions are inherited from the session; instructions are not a security sandbox.

The optional local dispatcher runs a finite owner-approved manifest through
separate `codex exec` processes. SQLite persists claims, dependency stages,
attempts and reporting failures. Completion immediately schedules eligible work;
an empty or dependency-blocked queue exits without periodic GitHub polling.
Implementation, QA and review are independent assignments bound to the same Git
revision and source fingerprint. Only requests for changes with nonblank evidence
route back to the original owner within the manifest's retry budget. Dependency
and infrastructure blockers require operator action and preserve successful
implementations. Repository, role and usable-HEAD preflight rejects unsupported
assignments before consuming an attempt. Interrupted work retains its
leases. Optional pipeline identifiers isolate generations on the same issue;
prerequisites, evidence fences and remediation stay within that pipeline. Stale
change findings cannot invalidate a successful implementation. Interrupted work retains its
leases until an operator proves orphan processes stopped. Agent results never
establish merge, deployment or Done; those remain externally verified gates.
The runner filters credential environment variables and connector settings;
role file leases are coordination rules within each workspace sandbox.
See [dispatcher operations](docs/agents/dispatcher.md) and
[ADR 0023](docs/adr/0023-local-task-dispatcher.md).

The [local agent dashboard](docs/agents/dashboard.md) reads a sanitized queue
projection and timestamped native-agent observations on loopback only. It
distinguishes registered workers, confirmed activity and stale/unknown sources;
it cannot control workers or observe native agents continuously. See
[ADR 0028](docs/adr/0028-local-agent-dashboard.md).

Explicit operator archive annotations separate superseded attempts from current
counts while retaining their original queue states and history. They do not
resolve issues, mutate SQLite or approve work; invalid annotations hide nothing.

```mermaid
flowchart LR
    O["Owner-approved local manifest"] --> D["Local one-shot dispatcher"]
    D --> Q[("Private SQLite queue and reporting outbox")]
    D --> W["Separate Codex CLI processes in leased worktrees"]
    W --> C["Existing account-backed model service"]
    W -->|"Completion and evidence"| D
    D -->|"Timestamped status transitions"| G["GitHub issues and Project"]
    G -->|"Compact issue metadata"| T["One-shot intake with approved local catalog"]
    T -->|"Authorized eligible pipelines"| Q
    D --> I["Idle exit: no task polling"]
    R["Independent reviewer and verified CI"] --> M["Guarded merge"]
    M --> P["Separate authorized deployment and readiness verification"]
```

```mermaid
flowchart LR
    H["User"] --> O["bank_orchestrator"]
    O --> AI["ai_assistant_owner: development role"]
    O --> G["gateway_owner"]
    O --> A["auth_owner"]
    O --> IOS["ios_owner"]
    O --> ANDROID["android_owner"]
    O --> WEBOWNER["web_owner"]
    O --> SSO["sso_owner"]
    O --> U["user_owner"]
    O --> W["wallet_owner"]
    O --> P["payment_owner"]
    O --> L["ledger_owner"]
    O --> QI["qa_integration"]
    O --> QF["qa_security: financial QA"]
    O --> S["security_auditor"]
    O --> R["bank_reviewer"]
    QI -->|"Results / defects"| O
    QF -->|"Invariants"| O
    S -->|"Findings / retest"| O
    R -->|"Review"| O
    O --> PR["PR and evidence"]
    PR --> H
```

| Agent | Responsibility / writable scope |
| --- | --- |
| bank_orchestrator | Assignment, integration, shared config/runtime, Compose/CI, this documentation, PR |
| ai_assistant_owner | ai-assistant-service/** and tests: C# assistant, owned policy/index/provider adapters and constrained read tools |
| gateway_owner | app-gateway: routes, public contract, identity |
| auth_owner | auth-service: credentials, sessions, limits |
| android_owner | android: native Kotlin/Compose client, browser SSO, gateway contracts and secure recovery |
| web_owner | web: Angular browser client, dedicated browser SSO, exact money, owner-scoped local recovery and tests |
| ios_owner | ios: native SwiftUI client, gateway integration, browser authentication and tests |
| sso_owner | sso-service: Keycloak/OIDC configuration; auth SSO adapter files only under an explicitly transferred lease |
| user_owner | user-service: profiles, immutable phone claims, recipient lookup and attestation |
| wallet_owner | wallet-service: metadata/lifecycle, durable provisioning and live balances |
| payment_owner | payment-service: orchestration/idempotency, recovery, in-app notifications and email outbox/dispatcher |
| ledger_owner | ledger-service: accounts, balances, posting correctness |
| qa_integration | End-to-end contracts, outages/recovery; only assigned test files |
| qa_security | Financial invariants and owner isolation; only assigned tests |
| security_auditor | System security, trust, secrets, dependencies, Docker/CI; independent retesting |
| bank_reviewer | Independent review; read-only source with gated PR merge authorization |

Owners also maintain module tests and OpenAPI. QA/security are read-only until specific
test files are assigned. Each file has one writer; builds in a shared directory run
serially. The orchestrator manages shared Docker within the authorized task scope.

Roles: `.codex/agents`; skills: `.agents/skills`.

| Skill | Purpose |
| --- | --- |
| bank-java | Java 21, Gradle, layer boundaries, and resources |
| bank-postgres | jOOQ/HikariCP, Flyway, transactions/locks/retries |
| bank-api | OpenAPI, Javalin, identity, bounded HTTP |
| bank-android | Native Kotlin client, browser PKCE, Keystore persistence and emulator evidence |
| bank-ios | Native SwiftUI iOS 18+, gateway integration, Keychain and device validation |
| bank-sso | Keycloak OIDC, authorization code/PKCE, identity mapping and session migration |
| bank-testing | Spock, Testcontainers, ArchUnit, evidence |
| bank-financial-correctness | Postings, balances, concurrency, idempotency |
| bank-security | Security review and fix verification |
| bank-coordination | Assignments, messages, finding IDs, checkpoints |

Details: [skills matrix](docs/agents/skills.md), [workflow](docs/agents/workflow.md),
[team ADR](docs/adr/0006-agent-team.md). If automatic loading is unavailable, the role/skill
is read explicitly and passed into a scoped assignment; the fallback is disclosed to the user.

## Communication and remediation

Shared files do not imply shared conversation context. Saving Markdown does not notify
anyone by itself; the orchestrator forwards concrete assignments and evidence.

```mermaid
sequenceDiagram
    participant S as Security
    participant O as Orchestrator
    participant D as Service developer
    participant Q as QA
    S->>O: SEC-ID, revision, severity, evidence, repro
    O->>D: Assignment + file lease + regression criteria
    D-->>O: Patch/revision and test results
    O->>S: Independent retest of integrated code
    O->>Q: Related acceptance tests
    S-->>O: VERIFIED / REOPENED / BLOCKED
    Q-->>O: Evidence
    O-->>D: Closure or further remediation
```

Use `send_message` for a running agent and `followup_task`, or the current client's
equivalent, for an agent that has finished. An unavailable agent is replaced with a new
one carrying the same IDs and context. On session resumption, assignments are restored
from the checkpoint and checked against source code/CI. These are orchestrator actions,
not a background task queue.

```mermaid
stateDiagram-v2
    [*] --> NEW
    NEW --> TRIAGED
    TRIAGED --> ASSIGNED
    ASSIGNED --> FIX_READY
    FIX_READY --> VERIFIED: independent retest
    VERIFIED --> CLOSED: evidence accepted
    FIX_READY --> REOPENED: issue remains
    REOPENED --> ASSIGNED
    FIX_READY --> BLOCKED: code or environment unavailable
    BLOCKED --> FIX_READY: evidence available
```

BLOCKED is possible at other stages, preserving the previous status and the reason.
False positives are closed with evidence and independent review. Confirmed unresolved
critical/high findings block PR readiness; green CI or the word "fixed" does not close
a finding. Accepting residual risk and merging are user decisions.
Records/checkpoints: [protocol](docs/agents/communication.md).

## Verification and delivery

All new PRs target `main`, which contains the integrated backend and native iOS/SSO
slices. Feature branches start from the latest main unless the user specifies
otherwise; the former bootstrap branch is no longer the default integration target. No direct pushes
to main. Independent reviewer merges follow [owner-authorized gates](docs/agents/merge-policy.md).

```mermaid
flowchart LR
    T["Task + contract"] --> DEV["Implementation"]
    DEV --> DOC["API / architecture / ADR"]
    DOC --> REV["Independent QA / security / review"]
    REV --> FIX["Fixes"]
    FIX --> CI["CI for the exact PR head"]
    CI --> TEST["Java / Spock / ArchUnit / PostgreSQL"]
    TEST --> SMOKE["Isolated Compose + smoke"]
    SMOKE --> READY["Reviewer merges after all gates pass"]
    CI --> ANDROID["Kotlin tests / lint / APK"]
    ANDROID --> READY
```

| Check | What it establishes |
| --- | --- |
| `./gradlew test` | Unit/architecture checks; excludes IntegrationSpec |
| `./gradlew check` | Also runs real PostgreSQL Testcontainers; requires Docker |
| `./gradlew installDist` | Runnable distributions |
| `scripts/smoke.py` | Readiness of six services, not business correctness |
| Auth/onboarding smoke in CI | Auth, profile, wallets through HTTP |
| Provisioning smoke in CI | Concurrent retries, ledger outage, wallet restart and unique zero-balance account mapping |
| Ledger smoke in CI | Private posting with synthetic funds |
| `scripts/p2p-smoke.py` in disposable CI | Phone resolution, public P2P, duplicate/concurrent spending, outages, post-commit recovery, notification isolation and journal reconciliation |
| Android CI | Debug APK, lint and JVM tests; emulator checks only on explicit owner request |
| Angular CI | Production build, unit/client contracts, browser fixtures and dependency audit; real backend acceptance in the disposable SSO job |
| SSO browser smoke in CI | Disposable Keycloak authorization code/PKCE and gateway exchange for iOS, Android and web; real Angular gateway smoke |
| Kafka offline guards and snapshot fixtures | Isolation/parser gates and real Bash operations on synthetic files; no broker durability evidence |
| Separate Kafka CI acceptance job | Required isolated cluster publish/consume, ACL/TLS denial, outages, retained restart and cold restore; real-cluster execution remains pending |
| iOS validation | Paused by owner; existing script requires Mac/Xcode when resumed |

### Local build infrastructure

Pull-request CI selects affected components and their dependency/acceptance closure.
Unchanged components record explicit no-op checks; full release checks are manually
available. The duplicate main-push build is disabled. See [affected CI](docs/affected-ci.md)
and [ADR 0028](docs/adr/0028-affected-ci.md) for the selection and fixture limits.


GitHub Actions schedules backend, Android and Angular work on disposable Linux Docker runners
labelled `armoney-local`. Up to two allowlisted slots run in separate Windows controller
processes. Each slot registers one job at a time, then removes that job's runner,
workspace and dedicated Docker daemon storage. Slot 1 preserves the original project
and mutex names; slot 2 has its own project, mutex, network and disposable volumes.
Same-slot contention and dirty-state checks fail before registration; cleanup targets
only the selected slot and exact runner registration. Artifacts persist in the shared
`armoney-ci-artifacts`. The application's running Docker stack is separate. PostgreSQL readiness probes use
TCP loopback to avoid accepting the temporary initialization server as ready.

```mermaid
flowchart LR
    GH["GitHub Actions: checks and scheduling"] --> R1["Slot 1: ephemeral Linux runner"]
    GH --> R2["Slot 2: ephemeral Linux runner"]
    H1["Windows controller 1: legacy mutex and project"] --> R1
    H2["Windows controller 2: separate mutex and project"] --> R2
    R1 --> D1["Slot 1 inner Docker daemon and workspace"]
    R2 --> D2["Slot 2 inner Docker daemon and workspace"]
    R1 --> A["Shared local artifact volume: reports and APK"]
    R2 --> A
    M["iOS development and CI paused by owner"]
    P["Persistent banking stack: not mounted in CI"]
```

The public repository requires approval for all external contributors before runner
registration. Approving untrusted workflow code is unsafe: privileged DinD is not a
hostile-code sandbox. Labels and workflow conditions are only additional checks.
The host administration token never enters the job; registration credentials are removed
before the runner accepts work. Failed isolation checks also prevent Docker cleanup.
Both slots repeat the trust audit before every registration. Shared host resources and
the writable artifact volume do not isolate mutually untrusted jobs. Current backend
and Android archive names are distinct within each run/attempt; future same-kind matrix
jobs require unique archive paths. Concurrency does not guarantee faster completion.

Android emulator instrumentation is excluded from routine CI and runs only on an explicit
owner request. The retained script uses software emulation on Docker Desktop without KVM. iOS
development and CI are paused by explicit owner instruction on 2026-10-12. Existing
source and test scripts remain; resumption requires Mac/Xcode. No iOS pass is claimed.
Operational steps and limitations: [local CI](infra/local-ci/README.md),
[ADR 0016](docs/adr/0016-local-ci-runners.md),
[ADR 0021](docs/adr/0021-local-ci-slots.md). Deployment remains an explicit owner action.

On Windows, use `.\gradlew.bat`. CI: [.github/workflows/ci.yml](.github/workflows/ci.yml).
Fixture funding and `down --volumes` are only for disposable CI environments, never user
databases. Smoke scripts currently expect port 8080 and the `arman-bank_bank` network;
a different Compose project name alone does not provide an isolated parallel test.

## Keeping documentation current

**Document owner: bank_orchestrator.** Every change is assessed for documentation impact.
Service owners report changes to APIs, schemas, connections, invariants, and limitations
in their handoffs. The orchestrator updates this file and related documents **in the same PR**;
bank_reviewer checks them against the integrated code.

| Change | Documentation to review |
| --- | --- |
| Service / HTTP dependency | Service map, responsibilities, sequence diagrams |
| API / auth | API table, OpenAPI, trust model |
| Migration / consistency | Data, ledger flow, ADR |
| Compose / env / ports | Runtime and instructions, without secrets |
| Agent / skill / communication | Team, skills matrix, protocol, role files |
| CI / tests / readiness | Checks, limitations, M1 plan |

If documented behavior is unchanged, the PR explains why documentation is unaffected
instead of making a cosmetic date change. A substantive update records the date and
verified source revision. Planned behavior must not be presented as implemented, and
completed features must not remain only in the future section. ADRs preserve history;
a new decision gets a new ADR. The independent reviewer treats documentation drift as a defect.

This is a rule for every task and review, **not a background update while Codex is closed**.
External changes are reconciled the next time work resumes on the repository. The rule
is established in [AGENTS.md](AGENTS.md), role instructions, and the PR template.
Further reading: [README](README.md), [IDEA and manual testing](docs/onboarding.md),
[ledger](docs/ledger.md), [M1](docs/m1.md), [ADRs](docs/adr/0001-bootstrap.md).

### Context-efficient agent routing

Dedicated ios_owner, android_owner and sso_owner roles own native clients and SSO.
They load bank-ios, bank-android and bank-sso skills only for relevant tasks. Shared instructions
remain in AGENTS.md/workflow; [context-map](docs/agents/context-map.md) points to
contracts on demand. [Audit](docs/agents/context-efficiency.md) records measured
text reductions, not estimated billing savings. Fresh scoped worker contexts and
bounded evidence replace full-history copies for independent tasks. iOS CI is paused;
disposable browser CI verifies Keycloak code/PKCE and SSO.

See [implemented features, deployment boundaries and agreed roadmap](docs/delivery-status.md) for current versus planned capabilities.

### Explicit synthetic local baseline

The local baseline tools use Keycloak administration for synthetic identity creation,
then the existing native PKCE and Gateway flows for profiles and wallet provisioning.
A separately opted-in Ledger CLI posts deterministic initial funds atomically through
the ledger journal; it adds no public funding endpoint. Its clearing account is reserved
for synthetic local funds. Manifests and database backups remain outside Git with
restricted permissions. Backup capture quiesces writers across owning primary databases;
replicas are rebuilt from primaries after restore. Tooling is not an automatic startup seed
and does not bypass email/phone verification requirements. Execution and restore evidence
are separate delivery gates. See [ADR 0019](docs/adr/0019-local-synthetic-baseline.md)
and [operations](docs/local-baseline.md).

## Disabled auth login proof storage

Auth contains V7 login proof/event storage, immutable payload replay, shared
writer admission and strict private route classes. Main now conditionally
registers the private proof bridge only through strict default-off
`AUTH_LOGIN_PROOF_BRIDGE_ENABLED` configuration. Defaults retain 404. The
configured production eligibility adapter remains unavailable; this bridge
cannot grant ACTIVE eligibility or complete banking sessions and notices.
Positive enforcement remains rejected. No provider hook or deployment is
activated by this source change. See [ADR 0033](docs/adr/0033-disabled-login-proof-storage.md),
the [proof contract](auth-service/LOGIN-PROOF-CONTRACT.md) and the current
[staging/runtime boundary](auth-service/CODE-INTENT-STAGING.md).

Internal V12 code-intent staging adds immutable replay, separately encrypted
short-lived original-session details and durable nonce/key lifecycle fencing.
A private staging HTTP adapter exists in source with strict thirteen-field requests,
five-field acknowledgements and the existing directional stage credential.
It is constructed by configuration but has no Main route or worker registration. Auth's global sixteen
authorized-operation gate uses one physical PRIMARY connection across admission,
provider observation outside transactions and final writes. HTTP/1.1 inbound
bodies have a four-second absolute deadline with physical transport closure;
this is not a deadline for database or provider operations. These controls do not
implement post-commit confirmation or positive token release. The approved
[joint contract](docs/adr/0043-code-intent-confirmation.md) distinguishes those
future steps from implemented staging. The accepted [durable confirmation
amendment](docs/adr/0045-durable-code-confirmation-anchors.md) specifies original-actor
anchors, replay completion, original-key lookup and one producer deadline.
Confirmation, lookup, explicit pair configuration and activation remain unimplemented.

## Disabled verification projection plumbing

Auth V8 contains durable verification-projection commands, fenced ACK/retry state
and keyed identity recovery barriers. User V6 contains projection CAS state,
immutable operation replay, durable expired-operation tombstones and shared writer
admission. Projection revision is independent of contact version; new operations
require increasing epoch and appropriate same/replacement-phone contact versions.
User atomically synchronizes central phone ownership, existing directory and
PRIMARY physical phone/verification flags without shard I/O or placement changes.
Projected identities cannot use the legacy phone-only operator attestation.

The dedicated auth-to-user private POST uses a separate credential, six-field
hash-bound ACK and strict bounded transport. InternalHttp supports an exact
server-configured method/path exemption for independently authenticated dedicated
handlers; ordinary business route checks remain in force. Composed-service wiring
acceptance is separate from the runtime helper's focused tests.

Main source wiring now constructs projection configuration with false defaults.
Enabled user configuration pairs the dedicated route with its exact exemption;
enabled auth configuration constructs a worker, starts it after HTTP runtime and
closes it on shutdown/error. These flags have not been enabled for deployment.
Standalone projection authority is UNAVAILABLE; separately gated completion source
supplies current receipt authority only to projection. No ACTIVE eligibility, session/notice capture, legacy
migration or provider activation is supplied. Issues #28/#36/#37 remain partial.
Bounded cleanup helpers exist; the auth worker runs cleanup ticks only if
explicitly enabled and started. User cleanup scheduling and operational
monitoring remain absent. No deployment or runtime erasure result is claimed.

Independent focused QA has passed the populated auth V7-to-V8/user V5-to-V6
upgrades and combined synthetic user wiring. These are focused checks, not full
affected-check, linked CI/Compose or deployment completion. Full affected checks,
remaining real isolated PostgreSQL concurrency acceptance, actual
handler compatibility, linked disposable smoke tests and independent review
remain delivery gates. See [ADR 0034](docs/adr/0034-verification-projection.md)
and the [projection contract](docs/verification-projection-contract.md).

## Disabled verification authority staging

Auth V9 source adds stable pending association, independent contact candidates/
versions and current receipt pointers, bounded authorized flows, permanent
contact-free denial barriers, command replay and reservation history. Pending
UUIDs stay outside banking identities when completion is disabled. Separate
default-off completion source materializes managed principals without banking
access, as described below. In staging-only mode, new-subject projection remains unavailable;
caller-owned preparation/ACK helpers stage existing-mapped projection under the
shared verification-control, projection-control, then login-proof-control order.
ACK can yield PROJECTED only. Final disabled auth source has independent bounded
source/security clearance and passing focused unit/PostgreSQL evidence, including
the trusted timing-anchor regression. Full auth check passed 513 tests without failures, errors or skips;
published-head CI and runtime acceptance remain separate gates.

The [disposable observer acceptance harness](docs/sso-observer-acceptance.md)
adds a separate test-only session-control JAR and two-node Keycloak topology.
Production packaging excludes this fixture. Cross-node committed-state visibility,
session-removal and expiry assertions passed disposable CI run 38877887316 on
2026-10-16 at source `86c42d5`; see the linked acceptance receipt. This proof
does not establish production deployment or authorize verification activation.

The assigned timing adjustment makes admission return an auth DB timestamp
after its locks, which the service passes unchanged to remote observation.
Final database age/future/expiry checks follow all authority row locks; provider
time cannot become the trusted anchor. Independent focused auth tests verify this
boundary; it does not establish a durable-commit latency bound.

Disabled provider source adds a read-only pre-token observation subpath using
six exact server-context fields, seven-field enrollment results and twelve-field
observations. Internal-ID HMAC, required-action/USER expiry and render-first
sequencing are implemented source boundaries. Independent provider checks passed;
two-instance committed-state visibility, session/tab/user removal and pinned
browser/runtime behavior passed disposable CI run 38877887316 at source
`86c42d5` on 2026-10-16. No new SPI or realm activation is introduced; production
activation remains a separate gate, and the final action still requires ACTIVE.

The earlier authority stage did not wire an observation supplier. The observation
integration now provides explicit Main/routes/configuration wiring behind the
default-false authority-bridge flag. Bridge-only positive proof/projection eligibility remains UNAVAILABLE;
the bridge alone supplies no ACTIVE, principal materialization, legacy cutoff, bank-session or notice flow
is activated. The earlier projection deployment remains default-off; this is
not new authority rollout evidence. Candidate/contact and immutable phone-claim
history retention differs from bounded flow/command-response cleanup; no general
PII purge or operational cleanup guarantee is claimed.

Source completion, affected auth checks, isolated PostgreSQL upgrade/concurrency
acceptance, pinned provider cross-instance/runtime proof, linked disposable smoke
tests and independent review remain required. See
[ADR 0035](docs/adr/0035-disabled-verification-authority.md) and the
[authority contract](docs/verification-authority-contract.md).

## Disabled Keycloak provider package

The standalone `sso-service/provider` Java 21 module packages verification
required actions, a password-validation marker and a keyed status observation
resource for Keycloak 26.7.4. Its image recipe and themes are source artifacts;
Compose and realm configuration do not activate them. Exactly three allowlisted
SPI descriptors are packaged. Proof protocol, token policy and introspection
factories remain unregistered, and provider observations do not grant eligibility.

Auth owns verification receipts. Positive login-proof eligibility and
enforcement across every session/token entry path remain planned, as recorded in
the [proof contract](auth-service/LOGIN-PROOF-CONTRACT.md). Existing web/native
OIDC behavior remains unchanged. Standalone checks and package provenance do not
establish runtime registration or delivery acceptance. See
[ADR 0032](docs/adr/0032-disabled-keycloak-provider.md).

## Banking assistant feature slice

[PR #46](https://github.com/aarbabaev/ArMoney/pull/46) merged after independent review and all required [CI #91 checks](https://github.com/aarbabaev/ArMoney/actions/runs/38387669495) passed on head `6a5bcfb6`. Local rollout of merged revision `ce5175bb` was verified on 2026-10-13; [issue #43](https://github.com/aarbabaev/ArMoney/issues/43) records the release checkpoint. This is dated deployment evidence, not a guarantee about future running containers. The [mission](docs/agents/missions/ai-assistant.md), [ADR 0020](docs/adr/0020-banking-ai-assistant-proposal.md), [HTTP/private trust contract](docs/ai-assistant-contract.md), [operations](docs/ai-assistant-operations.md) and [verified learning-outcome matrix](docs/ai-assistant-learning-outcomes.md) define scope and limits.

C#/.NET 10 is a service-local exception to Java 21. Domain/application/ports/adapters keep model, embedding, PostgreSQL and HTTP details outside the domain. ai-assistant-service owns only its separate PostgreSQL policy source/index, credentials and assistant-db volume. Operator ingestion is a separate local container/CLI; runtime has policy SELECT privileges only. No other service database or business classes are reused. Hybrid exact pgvector/full-text ranking is measured against a lexical baseline; active document/index versions, effective dates and provenance are retained. Initial documents are owner-approved demonstration rules from implemented ArMoney behavior, not legal/production bank policy.

```mermaid
flowchart LR
    A["Android Assistant tab: implemented, APK verified"] --> G["Gateway: authoritative session validation"]
    G -->|"Opaque active-turn read delegation"| S["C# assistant: bounded router and specialists"]
    S -->|"Fixed authenticated reads"| G
    G --> W["Wallet API"]
    G --> P["Payment history API"]
    W --> L["Ledger: balance authority"]
    S --> D[("Assistant PostgreSQL: policies and exact vector/full-text index")]
    I["Local operator ingestion CLI"] --> D
    S -->|"Minimal authorized context; external execution"| O["OpenAI model and embedding APIs"]
```

Gateway keeps the original bank bearer and internal bank key; assistant receives only a short-lived opaque read handle and independent directional credentials. Every fixed GET callback and final response revalidates the session; credentials alone cannot select an owner. No transfer/mutation, arbitrary URL/SQL/shell or ordinary-user upload tool exists. Gateway projects direction/status/amount/time and validates returned bank facts against its observed reads. Financial values use integer AED fils. Default last transaction is the first visible payment by created_at DESC, id DESC across own wallets; outgoing PENDING/COMPLETED/REJECTED and only COMPLETED incoming rows are visible. First-page history is bounded to 50; no complete monthly totals are promised.

The planner selects a finite policy intent; application code supplies fixed retrieval questions and filters relevant sections. Policy selection returns citation identifiers, never free-form answer prose. Application code renders exact demonstration source excerpts with document/version/section and explicit conflicting or insufficient evidence. Bank facts never enter model context. Prompts and documents are untrusted data; deterministic code validates tools, authorization and budgets. Runtime provider/embedding ports use external OpenAI despite local Docker hosting; owner authorized minimal egress. No personal server conversation is persisted; Android clears displayed turns on session change and rejects stale results. Provider store=false is not a zero-retention guarantee. The ai_assistant_owner configures a Codex developer, distinct from runtime assistant roles and containers. Local and required CI checks, measured model evaluation, independent review and local deployment readiness passed. Authenticated actual-provider acceptance ran in a matching disposable stack; no live-owner message or physical-device UX pass is claimed. See the versioned [learning-outcome evidence](docs/ai-assistant-learning-outcomes.md) for scope and limitations.


### Auth pre-token observation bridge: deployed, default-disabled

The observation client and explicit authority-mode routes are implemented under
[ADR 0037](docs/adr/0037-auth-verification-observation.md) and the
[wire contract](docs/auth-verification-observation-contract.md).
`AUTH_VERIFICATION_AUTHORITY_BRIDGE_ENABLED` defaults false; off constructs no client,
authority repository or routes. Auth owns the database admission timestamp and staged
contact receipts. A strictly bounded provider observation supplies current server
session/contact facts, never identity, receipt, epoch or ACTIVE authority. Explicit
legacy construction remains separate; authority mode never accepts shortened bindings.

Enrollment/status, issue and consume preserve their distinct seven/five/three-field
responses. Main owns client startup/shutdown cleanup. No gateway route, principal
materialization, positive projection/proof eligibility or bank session/notice is added.
Independent focused transport/configuration/routes (84) and PostgreSQL operation (2)
checks passed; the full Auth check passed 599 tests with no failures or skips.
PR #70 passed exact-head CI and was deployed at af1f7975 with the bridge disabled. Existing native PKCE login, profile, AED wallet read and logout passed; this was not new registration activation. Separate two-node provider committed-state, removal and expiry evidence remains required before activation.

### Default-off banking eligibility barrier

Auth includes the independent strict AUTH_BANKING_ELIGIBILITY_ENFORCED switch, default false. Its deny-only path rejects all authority-associated V9 staging identities before profile provisioning and during session creation/validation under the shared control prefix. Off preserves compatibility for unmanaged historical identities, including populated staging. Permanent V10 managed-principal denial applies in every flag mode, as described below. This is not complete dual-OTP enforcement, provider proof or activation. Source and checks are tracked separately; no deployment enables the switch. See [ADR 0038](docs/adr/0038-default-off-banking-eligibility.md).

### Managed registration completion: source implemented, default-disabled

The independent strict `AUTH_REGISTRATION_AUTHORITY_COMPLETION_ENABLED` defaults
false and requires authority bridge and projection enabled. With both current
contact receipts, completion atomically materializes the reserved UUID, exact
issuer/subject mapping, permanent V10 managed marker and business intent, and V8
projection command under verification, projection, then login-proof control locks.
Replays of the same current candidate/receipt pair preserve original operation,
epoch and payload even with another command UUID. No HTTP runs in the transaction.
A current fenced worker ACK can set `PROJECTED` only.

Managed identities are denied before banking provisioning and during exchange,
session creation and validation even after every flag is disabled. This adds no
ACTIVE, positive proof authority, bank-session grant, notice or legacy backfill;
the 200-owner baseline is not converted. Public enrollment/status retains seven
fields. Permanent contact-free marker/intent IDs and hashes outlive V8 command
cleanup and have no authority-row foreign key; existing retained contact and
external-mapping limits still apply. Once materialized, rollback requires binaries
enforcing the marker in every mode: disabling flags does not make older binaries
safe. Local affected Auth checks passed 675 tests across 38 suites without failures, errors or skips. Exact-head CI is pending; runtime activation, deployment and complete
registration enforcement are not claimed. See
[ADR 0039](docs/adr/0039-managed-registration-completion.md).

### Internal managed eligibility grants: validated internal evidence slice

`AUTH_REGISTRATION_ELIGIBILITY_GRANTS_ENABLED` defaults false and requires managed
completion, authority bridge and projection configuration. V11 source adds
immutable evidence keyed by managed identity/epoch, binding current email/phone
candidate receipts, exact acknowledged projection and expiry. First-grant creation
uses an authorized PRETOKEN status flow without requiring an existing grant or
ACTIVE. Auth admission supplies a database anchor, HTTP observation occurs outside
the transaction, and local revalidation uses the shared verification, projection,
then login-proof control locks. The record is local evidence, never a credential;
future consumers must obtain fresh current evidence and enforce expiry again.

Local invalidation preserves historical rows and suspends authority/advances epoch.
On 2026-10-16, the full local Auth check passed 728 tests across 41 suites in
11m 50s, with zero failures, errors or skips. Independent security verification of
the Auth capability verified SEC-GRANT-001 (synthetic negative STATUS HTTP through the
real Auth adapter/service/PostgreSQL path) and SEC-GRANT-002 (final expiry
revalidation). Actual Keycloak negative-fact propagation remains unproven; the
provider currently supplies positive facts or unavailable responses. Exact combined
CI is pending. No ACTIVE, public seven-field response change, session/token grant,
positive proof authority, legacy backfill or activation is introduced. Managed
banking denial remains in force in every mode. No automatic renewal, suspension
recovery or general retention/erasure guarantee is supplied. See
[ADR 0041](docs/adr/0041-internal-managed-eligibility-grants.md).
### Authenticated token context: metadata only

Auth retains optional client/session/time and login-proof claim context after its
existing mandatory introspection checks. Each field distinguishes absence,
malformation and valid format. Format classification grants no proof authority or
banking eligibility. Malformed optional context preserves unmanaged legacy login
compatibility; permanent managed-principal denial remains unconditional.
The public HTTP contract and production proof wiring are unchanged. See
[the token context contract](docs/auth-token-context.md). The full local Auth check passed 847 tests across 43 suites on 2026-10-17 with zero failures, errors or skips. Independent evidence audit and exact-head CI remain separate gates; this does not establish deployment or activation.

### Bounded replica observation follow-up

Ledger can optionally observe explicitly named primary WAL senders for metrics only.
`LEDGER_METRICS_REPLICA_NAMES` must match the configured read-URL count; absent
names allocate no observation pool and retain UNKNOWN. An authenticated scrape
uses a separate bounded read-only pool. Complete fresh observations distinguish
known zero backlog from recent replay delay; missing, stale or uncertain members
clear the previous observation. This does not change money, read routing,
readiness or durability. Local affected Ledger checks passed 93 tests across 13
suites, including real physical standbys, on the recorded source patch. Combined
current-main CI, Compose acceptance and deployment remain pending. See
[ADR 0040](docs/adr/0040-replica-observation.md).

### Diagnostic first-login session visibility

The test-only SSO-VIS-001 topology uses two disposable Keycloak nodes to inspect
user and authenticated client-session visibility before and after a bounded
login redirect barrier. It is not a production service or proof authority.
The isolated experiment at `05bff8e96182d48c0fb2bd13c5aa2d4004d12c21`
passed on 2026-10-17 (Asia/Dubai): the session pair was not visible on node B while
the login hook was held and visible after the completed response in all four
cases, including hook HTTP 503. That failure therefore does not guarantee
rollback. Independent QA and security bound the result to the fixture artifacts
and verified cleanup. Production positive-proof hooks remain unregistered;
the precommit recording/postcommit authority contract remains an activation gate.
See [the proof design](sso-service/provider/LOGIN-PROOF-DESIGN.md#first-login-transaction-visibility-gate-sso-vis-001).

### Default-off client-session observer follow-up

The provider source adds private `armoney-status/code-session`, separately gated
by default-false `ARMONEY_CODE_SESSION_OBSERVATION_ENABLED`. Its strict four-field
request binds the exact user/session/public client; the exact twelve-field
response includes both session flags and observed/deadline timestamps. Existing
status/enrollment contracts and production factory registrations are unchanged.
Canonical contacts, LOGGED_IN online persistence, exact client membership and
pinned user/client idle/max validity are required. This is observation only,
not Auth receipts/projection/ACTIVE authority.
Null adapter-local persistence state requires an independent exact durable online
realm/session/subject LOGGED_IN tuple from Keycloak's session persister; explicit
TRANSIENT and unavailable/mismatched provenance deny. That follow-up passed
65 local provider tests across 13 suites. The isolated two-node acceptance at
`c8a8ddab6d9e91efca1c1e4823b4b86fe025557c` passed in 270.469 seconds on
2026-10-17 (Dubai), with independently matched source/archive/JAR evidence and
verified owned-resource cleanup. It covered the strict twelve-field baseline,
HTTP boundaries, foreign binding, confidential client, disabled user, explicit
null-contact readback/restoration, session deletion and policy expiry. The
disposable fixture uses `compose.sso-code-session.yaml` and
`scripts/run-sso-code-session.sh`; production activation remains default-off.
No cause is inferred for the earlier BASELINE or NULL_CONTACT failures.
Earlier local provider checks passed
64 tests across 13 suites, independently bound to unchanged source and packaged
SPI evidence. Exact-head required CI remains pending. This acceptance does not
establish Auth confirmation, code redemption, clock-rate assumptions or
production deployment. See the provider README and scoped frozen contract in
[PR #85](https://github.com/aarbabaev/ArMoney/pull/85); clock-rate assumptions and
all activation gates remain explicit.

### Unwired code-intent configuration validation

The provider contains a pure configuration parser for the scoped managed code-intent
flow. It validates explicit issuer/client pairs, disabled-by-default settings and
locally supplied credential/key material without allocating network or database
resources. It is not wired into production factories; existing SPI registrations,
transports and token hooks remain unchanged. Auth-only dependencies, remote public
client models and deployment state are outside this parser's validation boundary.
At `89a7404`, isolated Java 21 compilation and eight focused configuration tests
passed with zero failures, errors or skips; confirmation and activation remain
gated. See the provider README for the exact source scope and limitations.

The provider also contains an unwired invocation-owned deadline utility. At
34a1537855f551ed269d49f1c3bf8177bcc15796, eight deterministic deadline tests and
eight configuration tests passed with zero failures, errors or skips; independent
review verified retained results and source provenance. Existing production
transports and hooks do not consume this utility. Aggregate transport integration and physical
DNS/HTTP termination remain unproven; these tests do not authorize activation.

An unwired code-intent client now provides narrow stage, key-selection and
confirmation calls. Exact enabled managed-pair checks precede network fanout;
lookup and confirmation share the original invocation deadline. Its transport
caps streamed responses and distinguishes owned clients, which must be closed,
from injected clients retained by their caller. At
`c6b42ba5d1b240f8605fc94c2371babfee770164`, isolated Java 21 compilation and
28 focused tests passed: eight configuration, eight deadline, six client and six
HTTP transport tests, with zero failures, errors or skips. Independent review
verified source provenance and test results. The transport tests use fake clients;
they do not establish real-network or physical DNS cancellation, full provider
checks or packaging. Production factory integration, token release and activation
remain pending.

The unwired `CodeIntentResources` lifecycle owner parses explicit configuration
and constructs keys before allocating its single owned HTTP transport. Disabled
configuration returns no resources. It copies caller-supplied local key material,
cleans up failed construction and closes owned resources idempotently; callers
remain responsible for a complete local current/retained secret inventory.
Independent source review approved this boundary; its four new tests have not
been compiled or executed. It adds no SPI registration, hook or token release.
Runtime lifecycle, combined Auth confirmation, DNS and clock gates remain open.
