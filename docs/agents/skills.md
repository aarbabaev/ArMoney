# Project skill routing

Skills live in `.agents/skills/<name>/SKILL.md` and travel with the repository.
They provide task-specific procedures; they do not install Java/PostgreSQL,
grant access, or create continuously running workers. No third-party skill
packages or scanners are installed by this change.

Each role reads the matching skill only when that kind of work is assigned.
The orchestrator includes exact skill paths in assignments; when automatic
discovery is unavailable, the worker reads those files explicitly. See the
[official skill documentation](https://learn.chatgpt.com/docs/build-skills).

| Role | Relevant skills |
| --- | --- |
| bank_orchestrator | bank-coordination; bank-api for contracts; bank-java/bank-postgres for shared implementation; bank-testing for validation |
| gateway_owner | bank-java, bank-api, bank-testing; bank-security for trust-boundary changes |
| auth_owner | bank-java, bank-postgres, bank-api, bank-testing, bank-security |
| android_owner | bank-android; bank-api for gateway contracts; bank-sso for login |
| ios_owner | bank-ios; bank-api for gateway contracts; bank-sso for login |
| sso_owner | bank-sso; bank-security and bank-testing for identity verification; bank-java/bank-postgres only for assigned adapter changes |
| user_owner | bank-java, bank-postgres, bank-api, bank-testing; bank-security for authorization changes |
| wallet_owner | bank-java, bank-postgres, bank-api, bank-testing, bank-financial-correctness |
| payment_owner | bank-java, bank-postgres, bank-api, bank-testing, bank-financial-correctness |
| ledger_owner | bank-java, bank-postgres, bank-api, bank-testing, bank-financial-correctness |
| qa_integration | bank-testing, bank-api; bank-postgres and bank-financial-correctness for persistence/recovery cases |
| qa_security | bank-financial-correctness, bank-testing; bank-security for identity-related financial cases |
| security_auditor | bank-security, bank-api; bank-postgres/bank-java for source tracing; bank-testing for regressions |
| bank_reviewer | bank-java, bank-api; bank-postgres, bank-financial-correctness, bank-security and bank-testing according to changed paths |

`qa_security` retains its existing role name for compatibility but focuses on
financial and owner-isolation acceptance. `security_auditor` owns independent
system-security review across services, deployment and dependencies. They share
finding IDs through the orchestrator when coverage overlaps.

Skills encode project-specific decisions and failure modes, not general language
manuals. Begin with the assigned capability, then load another skill only when
the changed behavior needs it; this table is a menu, not a mandatory reading list.
The [context map](context-map.md) points to authoritative source and design facts.
The [context audit](context-efficiency.md) records measured instruction sizes and
orchestration improvements. Neither skill files nor roles activate workers.

## Stack rationale

The skills support Java 21, PostgreSQL, jOOQ, Flyway and Spock, with focused
frameworks, TDD and explicit domain boundaries. Gradle, Javalin, HikariCP,
Testcontainers and ArchUnit provide build, HTTP, pooling and verification.
Redis and Kubernetes remain outside current scope. Kafka is approved as a planned architecture direction (#30-33), not implemented infrastructure.
Observability guidance covers safe diagnostics and useful failure evidence
without introducing a separate monitoring stack.

## Additional task routing
- bank_orchestrator: bank-local-operations for CI/deployment, bank-messaging for Kafka/Saga coordination.
- payment_owner, ledger_owner, wallet_owner, auth_owner, user_owner, sso_owner and gateway_owner: bank-messaging only when assigned messaging producers/consumers.
- qa_integration, qa_security and security_auditor: bank-messaging for delivery/replay tests; bank-local-operations for infrastructure recovery validation.
- bank_reviewer: bank-review-delivery plus the implementation skills for changed modules.
- Android/iOS owners retain platform/API/SSO skills; backend broker internals are not loaded for ordinary client work.

Exact paths are .agents/skills/<name>/SKILL.md. These are local project instructions,
not installed runtimes or permission grants. Load on demand rather than all skills
for every agent. SMS identity work uses bank-sso, bank-security and bank-testing;
sandbox backend work uses bank-java, bank-api and bank-postgres when persistence applies.