# ArMoney

For the complete system and agent-team map with Mermaid diagrams, see
[ARCHITECTURE.md](ARCHITECTURE.md). It is maintained alongside project changes.

Repository: [aarbabaev/ArMoney](https://github.com/aarbabaev/ArMoney).
The Compose project, network/volume names and Java packages retain their existing
compatibility identifiers; a repository rename must not rename persistent resources.
Existing Git checkouts can update their remote with:

```sh
git remote set-url origin https://github.com/aarbabaev/ArMoney.git
```

The local folder need not be renamed. See the [documentation index](docs/README.md).

Java 21 / Gradle multi-project, Javalin (no Spring), PostgreSQL, Flyway,
jOOQ, HikariCP, Spock, Testcontainers and ArchUnit.

A banking backend with explicit service ownership and a PostgreSQL-backed ledger.
AED is the only supported currency; amounts use integer fils (100 fils = 1 AED).
There is no currency exchange or multi-currency account selection.
Native clients live in [ios/](ios/README.md) (SwiftUI) and [android/](android/README.md)
(Kotlin/Jetpack Compose). Optional Keycloak SSO and
local HTTPS setup are documented in [docs/sso-and-ios.md](docs/sso-and-ios.md).
Identity registration, login, current identity and logout now work through gateway.
Profiles and operator-attested phone recipients, live wallet balances, durable P2P transfers,
history and in-app notifications are implemented. See the [P2P contract](docs/p2p-contract.md)
and [operator procedure](docs/adr/0009-phone-p2p-payments.md). No SMS or push provider is connected.

## Run

Prerequisites: JDK 21 and Docker with Linux containers. The commands below run
the base backend. For Windows LAN HTTPS/Keycloak deployment, use the explicit
Compose file set in [the SSO runbook](docs/sso-and-ios.md).

```sh
# First clone only: do not overwrite an existing .env
cp .env.example .env
# Replace the local development password and INTERNAL_AUTH_KEY in .env
docker compose up --build -d
curl http://localhost:8080/health/ready
curl http://localhost:8080/openapi.yaml
```

Only gateway is published, on loopback port 8080. Internal services and databases
are reachable on the Compose network. Each persistent service owns one database
container and volume. Local database users own their schema to run migrations;
production must separate the migration role from the restricted runtime role.

```sh
./gradlew test                 # Spock + ArchUnit, no Docker needed
./gradlew check                # also real PostgreSQL Testcontainers; Docker required
./gradlew installDist          # six runnable distributions
python3 scripts/smoke.py       # all six containers must already be running
docker compose down           # preserves data
```

Windows: use `gradlew.bat`; copy `.env.example` only if `.env` does not already exist. Preserve existing database passwords and secrets when adding new settings.
Wrapper version and distribution checksum are pinned. Java toolchain is 21.
Dependency versions are centralized in the root and platform-runtime builds.

For direct local runs, provide PORT (default 8080), DB_URL, DB_USER and DB_PASSWORD
for persistent services, then run `./gradlew :ledger-service:run`.
Each service must use a different PORT when started outside Compose.
Gateway needs PORT, AUTH_BASE_URL, USER_BASE_URL, WALLET_BASE_URL, PAYMENT_BASE_URL
and INTERNAL_AUTH_KEY; payment needs USER_BASE_URL, WALLET_BASE_URL, LEDGER_BASE_URL
and INTERNAL_AUTH_KEY; auth-service also needs
INTERNAL_AUTH_KEY with the same value (at least 32 random characters). For an existing
installation, append INTERNAL_AUTH_KEY to .env without changing LOCAL_DB_PASSWORD.

## API and data

Each service serves its own checked-in OpenAPI contract at `/openapi.yaml`.
`/health/live` reports process liveness; `/health/ready` checks its database
through HikariCP/jOOQ. Migrations run before the listener starts. Gateway readiness
currently checks only itself; auth routes proxy to auth-service with bounded timeouts.
Auth endpoints are listed below. Other unimplemented business routes return 404.

See [architecture](docs/architecture.md), [ADRs](docs/adr/0001-bootstrap.md)
and [M1 delivery plan](docs/m1.md).

## Verification and contribution

For development with service-owner agents, an orchestrator and independent QA,
see [the agent team workflow](docs/agents/workflow.md). Roles are on-demand Codex
configuration; they do not start an unattended service.

CI compiles on Java 21, runs unit/architecture and PostgreSQL integration tests,
builds all images, and probes all six services on the private Compose network.
Missing Docker is a test failure, never a silent skip. Reports are uploaded.
Use a feature branch and PR; do not push to main or merge automatically.
Start feature branches from the latest main and target main in pull requests.
Branch protection is a repository-owner setting; source code alone does not establish
that it is enabled. Required checks must pass on the exact PR head before delivery.
CI also includes macOS native build/simulator tests and disposable browser SSO
coverage. These do not establish physical-iPhone or persistent-stack acceptance.

Not included: broker, Redis, Kubernetes, production deployment, money movement API,
distributed orchestration, observability backend, MFA and email verification.

## Auth API through gateway

| Method | Path | Result |
| --- | --- | --- |
| POST | /v1/auth/register | 202 for new or existing email; no password overwrite |
| POST | /v1/auth/login | 200 with access_token, token_type, expires_in and expires_at |
| POST | /v1/auth/sso | Exchange a verified Keycloak access token for a local session; optional SSO configuration required |
| GET | /v1/auth/me | Current identity; requires Authorization: Bearer <access_token> |
| POST | /v1/auth/logout | 204; revokes that session |

Password registration/login accept JSON with exactly email and password.
SSO exchange accepts only access_token. Issuer/subject mapping keeps SSO identities
separate from password identities, without email linking. See the SSO runbook. Passwords require
15+ characters (up to 128 UTF-16 units); emails are trimmed/lowercased.
Sessions expire after 30 minutes. Responses use Cache-Control: no-store.
For invalid input expect 400, invalid credentials/session 401, oversized body 413,
limits 429 with Retry-After, and unavailable auth dependency 503.

Flyway applies outstanding migrations at startup. Auth V2 adds password sessions
and V3 adds SSO mapping while preserving existing UUIDs; V1 is unchanged.
Use `docker compose up --build -d` after updating .env with INTERNAL_AUTH_KEY.
Do not delete volumes. Local DataGrip port overrides remain compatible.

`python3 scripts/auth-smoke.py` creates a synthetic identity and tests the complete
flow through gateway. CI runs this against disposable data. Do not use real
credentials in tests. See [auth ADR](docs/adr/0003-auth-sessions.md) for limits.

## Profiles and wallets

See [Postman walkthrough and IDEA settings](docs/onboarding.md) and [ADR 0004](docs/adr/0004-profiles-wallets.md).
Profile and wallet routes require a valid session through gateway. Wallets store metadata and a confirmed ledger account mapping; balances remain in ledger.
Gateway also requires USER_BASE_URL and WALLET_BASE_URL (provided by Compose).
Run `python3 scripts/onboarding-smoke.py` after updating the containers.

## Private ledger

Ledger now supports zero-balance accounts, owner-scoped balance reads and atomic,
retry-safe transfer commands for trusted internal callers. It is not exposed by
gateway; payment orchestration and public P2P remain next.
See [ledger guide](docs/ledger.md) and [ADR 0005](docs/adr/0005-atomic-ledger.md).
Existing INTERNAL_AUTH_KEY also configures ledger; no new secret is required.


## Wallet account provisioning

POST /v1/wallets returns 202 while an ACTIVE wallet is PENDING and 200 when READY
(or when returning an existing CLOSED wallet). Poll GET /v1/wallets for
provisioning_status and ledger_account_id. Repeating creation uses the same wallet
and ledger account. An unavailable ledger leaves durable pending work that resumes
after restart; no funds are created.

Wallet now requires LEDGER_BASE_URL; Compose sets http://ledger-service:8080.
V3 migrates existing ACTIVE wallets to pending provisioning without changing IDs;
CLOSED wallets stay closed and are excluded. Preserve existing .env and volumes.
See [ADR 0007](docs/adr/0007-wallet-ledger-provisioning.md).
The disruptive scripts/provisioning-smoke.py runs only in disposable CI.
