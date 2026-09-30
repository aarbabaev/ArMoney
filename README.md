# Arman Bank — M1 bootstrap

Полная карта системы и команды с Mermaid-диаграммами:
[ARCHITECTURE.md](ARCHITECTURE.md). Обновляется вместе с изменениями проекта.

Java 21 / Gradle multi-project, Javalin (no Spring), PostgreSQL, Flyway,
jOOQ, HikariCP, Spock, Testcontainers and ArchUnit.

This is the foundation for M1, **not a working bank**.
Identity registration, login, current identity and logout now work through gateway.
Current-user profiles and wallet metadata are implemented. P2P execution remains a future slice.
No real funds or customer data. No claim that this reproduces Revolut internals.

## Run

Prerequisites: JDK 21 and Docker with Linux containers.

```sh
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

Windows: use `gradlew.bat` and `Copy-Item .env.example .env`.
Wrapper version and distribution checksum are pinned. Java toolchain is 21.
Dependency versions are centralized in the root and platform-runtime builds.

For direct local runs, provide PORT (default 8080), DB_URL, DB_USER and DB_PASSWORD
for persistent services, then run `./gradlew :ledger-service:run`.
Each service must use a different PORT when started outside Compose.
Gateway needs PORT, AUTH_BASE_URL and INTERNAL_AUTH_KEY; auth-service also needs
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
Main had only an initial .gitattributes when bootstrap started; no existing code
was replaced. Branch protection is a repository-owner setting and is not enabled
by this code change. Configure the CI build job as required before merging.

Not included: broker, Redis, Kubernetes, production deployment, money movement API,
distributed orchestration, observability backend, MFA and email verification.

## Auth API through gateway

| Method | Path | Result |
| --- | --- | --- |
| POST | /v1/auth/register | 202 for new or existing email; no password overwrite |
| POST | /v1/auth/login | 200 with access_token, token_type, expires_in and expires_at |
| GET | /v1/auth/me | Current identity; requires Authorization: Bearer <access_token> |
| POST | /v1/auth/logout | 204; revokes that session |

Registration/login accept JSON with exactly email and password. Passwords require
15+ characters (up to 128 UTF-16 units); emails are trimmed/lowercased.
Sessions expire after 30 minutes. Responses use Cache-Control: no-store.
For invalid input expect 400, invalid credentials/session 401, oversized body 413,
limits 429 with Retry-After, and unavailable auth dependency 503.

Existing installations receive V2 automatically at auth startup; V1 is unchanged.
Use `docker compose up --build -d` after updating .env with INTERNAL_AUTH_KEY.
Do not delete volumes. Local DataGrip port overrides remain compatible.

`python3 scripts/auth-smoke.py` creates a synthetic identity and tests the complete
flow through gateway. CI runs this against disposable data. Do not use real
credentials in tests. See [auth ADR](docs/adr/0003-auth-sessions.md) for limits.

## Profiles and wallets

See [Postman walkthrough and IDEA settings](docs/onboarding.md) and [ADR 0004](docs/adr/0004-profiles-wallets.md).
Profile and wallet routes require a valid session through gateway. Wallets contain metadata only, not balances.
Gateway also requires USER_BASE_URL and WALLET_BASE_URL (provided by Compose).
Run `python3 scripts/onboarding-smoke.py` after updating the containers.

## Private ledger

Ledger now supports zero-balance accounts, owner-scoped balance reads and atomic,
retry-safe transfer commands for trusted internal callers. It is not exposed by
gateway; wallet account provisioning and payment orchestration are next.
See [ledger guide](docs/ledger.md) and [ADR 0005](docs/adr/0005-atomic-ledger.md).
Existing INTERNAL_AUTH_KEY also configures ledger; no new secret is required.

