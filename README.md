# Arman Bank — M1 bootstrap

Java 21 / Gradle multi-project, Javalin (no Spring), PostgreSQL, Flyway,
jOOQ, HikariCP, Spock, Testcontainers and ArchUnit.

This PR is the executable foundation for M1, **not a working bank**.
All six services expose only operational endpoints. Authentication, gateway
routing, customer onboarding, wallet creation and P2P execution are future slices.
No real funds or customer data. No claim that this reproduces Revolut internals.

## Run

Prerequisites: JDK 21 and Docker with Linux containers.

```sh
cp .env.example .env
# Replace the local development password in .env
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
Gateway needs only PORT.

## API and data

Each service serves its own checked-in OpenAPI contract at `/openapi.yaml`.
`/health/live` reports process liveness; `/health/ready` checks its database
through HikariCP/jOOQ. Migrations run before the listener starts. Gateway readiness
currently checks only itself; it does not yet proxy requests.
Unknown business routes return 404, not fake success responses.

See [architecture](docs/architecture.md), [ADRs](docs/adr/0001-bootstrap.md)
and [M1 delivery plan](docs/m1.md).

## Verification and contribution

CI compiles on Java 21, runs unit/architecture and PostgreSQL integration tests,
builds all images, and probes all six services on the private Compose network.
Missing Docker is a test failure, never a silent skip. Reports are uploaded.
Use a feature branch and PR; do not push to main or merge automatically.
Main had only an initial .gitattributes when bootstrap started; no existing code
was replaced. Branch protection is a repository-owner setting and is not enabled
by this code change. Configure the CI build job as required before merging.

Not included: broker, Redis, Kubernetes, production deployment, tokens, money
movement API, distributed orchestration, observability backend.
