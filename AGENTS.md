# Repository instructions

## Delivery
- Use feature branches and PRs. Never push directly to main or merge without explicit owner authorization.
- Keep the change minimal and describe limitations honestly.
- Run ./gradlew check and the Compose smoke test for runtime/schema changes.
- Do not report completion while required CI is failing or pending. Report blocked checks explicitly.
- Do not weaken tests, skip Docker tests, or introduce mock financial success to obtain green CI.
- Do not commit secrets, .env, production data, tokens, or generated build output.

## Architecture
- ARCHITECTURE.md is the maintained system and agent-team map. For every change, assess documentation impact. Service owners report affected sections/diagrams and contract/schema/runtime changes in their handoff; the orchestrator updates the map and linked docs in the same PR. If no documented behavior changes, explain why in the PR instead of making cosmetic edits.
- The independent reviewer checks Mermaid diagrams, implemented/planned status, API/data/trust boundaries and agent responsibilities against the integrated revision. Documentation drift is a review finding. Never include secret values or present planned flows as implemented.
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

## Agent team
- For implementation tasks involving multiple services, use the primary chat as bank_orchestrator and delegate scoped work to the affected service owners plus independent QA/review. Single-module trivial edits do not require the whole team.
- Read docs/agents/workflow.md and the relevant .codex/agents role before dispatch. Use native subagents, not separate user-owned chats. If named roles are unavailable, pass the role instructions in the task message and disclose this fallback.
- The orchestrator assigns disjoint writable paths and owns shared files. Workers must not modify other services, publish branches, merge, or delegate recursively without an explicit coordination assignment.
- Serialize builds in a shared directory. Only the orchestrator may operate the user's persistent Docker stack. CI-only funding fixtures must never run on it.
- Require independent review of the integrated revision and actual test evidence. Role instructions are not filesystem security boundaries or a background execution service.
- Route task-relevant project skills using docs/agents/skills.md and include their exact paths in assignments. Read the skill before using it; unavailable automatic discovery is handled by explicit file reads.
- Use security_auditor for independent system-security review. Follow docs/agents/communication.md to route findings, assign fixes and independently retest; never equate a developer's fix report with a closed finding.
