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

## Stack rationale

Revolut's public [Java engineering vacancy](https://www.revolut.com/en-LU/careers/position/backend-software-engineer-java-530d3bfd-bdca-4390-8f8a-bc88ddc1eb51/)
lists Java 17/21, PostgreSQL, jOOQ, Flyway and Spock, and discusses lean frameworks,
TDD and DDD (checked 2026-09-30). We select relevant practices for this Java 21
project; these skills do not reproduce confidential Revolut standards. Gradle,
Javalin, HikariCP, Testcontainers and ArchUnit remain this repository's choices.
Redis, Kubernetes and cloud infrastructure mentioned in that vacancy do not
expand our current scope. Observability checks cover safe diagnostics and useful
failure evidence without introducing a new monitoring stack.
