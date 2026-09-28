# Repository instructions

## Delivery
- Use feature branches and PRs. Never push directly to main or merge without explicit owner authorization.
- Keep the change minimal and describe limitations honestly.
- Run ./gradlew check and the Compose smoke test for runtime/schema changes.
- Do not report completion while required CI is failing or pending. Report blocked checks explicitly.
- Do not weaken tests, skip Docker tests, or introduce mock financial success to obtain green CI.
- Do not commit secrets, .env, production data, tokens, or generated build output.

## Architecture
- Java 21, Javalin, Gradle. No Spring, Kafka, Redis or Kubernetes in bootstrap.
- Services do not depend on another service's classes or query another service's database.
- platform-runtime contains technical plumbing only, never shared business entities.
- Domain packages depend only on the JDK and their own domain.
- Introduce application ports/adapters when implementing a use case, not empty layers.
- Write a short ADR for boundary, consistency or persistence changes.
- Flyway migrations are append-only after publication.
- Use parameterized jOOQ queries; never concatenate client input into SQL.

## Money and security
- Represent money in integer minor units with a currency; never float/double.
- Ledger owns balance correctness. A payment is not completed before ledger confirmation.
- Ledger posting must be atomic, balanced, immutable and idempotent by payment ID.
- Require concurrency, retry, insufficient-funds and rollback tests before exposing transfers.
- Scope client idempotency keys to the authenticated requester and compare payload hashes.
- Fail closed for identity/authorization errors. Do not trust user IDs passed by a client.
- Do not log passwords, tokens, full request bodies or personal data.
