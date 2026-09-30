---
name: bank-java
description: Implement or review ArMoney Java 21 service use cases and module boundaries.
---

# Java service changes

Follow AGENTS.md architecture rules. Use docs/agents/context-map.md to locate the affected entry point, adapter, tests and pinned dependencies; read only the changed use case and its dependencies. Keep HTTP/jOOQ/configuration outside the JDK-only domain. platform-runtime is technical plumbing and requires an assigned shared-file lease.

Preserve explicit business rejection versus transport uncertainty. Reuse owned pools/clients, close resources and preserve interruption. For remote commands, a timeout does not establish rollback.

Use the build lease and bank-testing skill for commands: test excludes IntegrationSpec, integrationTest includes it, check includes both. Existing ArchUnit specs guard service/domain boundaries. Report documentation/contract impact and actual evidence through the workflow; do not claim mocks prove database behavior.
