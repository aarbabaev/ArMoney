---
name: bank-java
description: Implement or review Java 21 service use cases in Arman Bank with Javalin, Gradle, domain boundaries and Spock/ArchUnit verification.
---

# Java service work

This is an instruction-only project skill; it installs no tools or packages. Paths below are relative to the repository root. Follow AGENTS.md and the orchestrator's assigned writable paths.

Before changing code, read the target module's Main.java, use case, ports/adapters and tests, plus build.gradle and settings.gradle. Reuse the pinned dependencies and Gradle wrapper. Keep Java 21 compatibility; do not introduce Spring or upgrade the stack as a side effect.

Implement business rules in the owning service. Domain code uses the JDK and its own domain only; keep HTTP, jOOQ and configuration at the edges. Introduce ports for actual external dependencies rather than empty abstraction layers. platform-runtime is shared technical infrastructure: request an orchestrator-owned change instead of placing shared business entities there. Never import another service's implementation.

Use explicit immutable values and outcomes where they express business invariants. Monetary quantities are integer minor units plus currency, with overflow checks at arithmetic boundaries. Model rejection separately from transport failure. Preserve the interrupt flag when catching InterruptedException; close owned resources and avoid per-request pools or HTTP clients. A timeout is not evidence that a remote command failed before commit.

Update Spock examples for the changed behavior, including failure paths. For module X, use ./gradlew :X:test and, when persistence is involved, :X:integrationTest; Windows uses .\gradlew.bat. IntegrationSpec classes run separately from test, and check includes integrationTest. Coordinate the build lease before any Gradle invocation in the shared checkout. ArchUnit checks must continue to protect module/domain boundaries. Do not replace real PostgreSQL tests with mocks.

For runtime/schema changes, hand off the integrated revision for ./gradlew check and disposable Compose smoke verification. Report exact commands, results and untested behavior. A local unit-test pass is not a full system pass. Do not operate the user's persistent Docker stack from a worker role.
