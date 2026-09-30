---
name: bank-testing
description: Design and run Spock, PostgreSQL Testcontainers, ArchUnit and cross-service acceptance checks for this banking repository, with isolated fixtures and evidence tied to the tested revision.
---

# Banking tests

Read `AGENTS.md`, `docs/agents/workflow.md`, the changed module's tests and
`build.gradle` before selecting checks. Obtain the orchestrator's exact test-file
lease before editing and the build lease before running Gradle in a shared
directory. A module-scoped command still writes shared dependency outputs.

## Select evidence by behavior

- Use Spock unit specifications for domain validation, state transitions and
  HTTP proxy decisions. Prefer public behavior assertions over implementation
  call counts; do not mock a successful ledger posting as proof of money movement.
- Use PostgreSQL Testcontainers with actual Flyway migrations for transactions,
  constraints, locking, rollback and repository behavior. H2 or an in-memory
  repository cannot establish PostgreSQL correctness.
- Extend the existing ArchUnit specifications for new dependency boundaries.
  Domain code remains JDK-only; service modules must not import one another.
- Add HTTP acceptance cases for public status/body contracts and negative paths.
  Cover outages, retries, restart recovery and revoked identity when the change
  touches those behaviors. Test a real failure before claiming a fix.

From the repository root on Windows, examples of focused checks are:

```powershell
.\gradlew.bat :ledger-service:test --tests '*TransferSpec'
.\gradlew.bat :ledger-service:test --tests '*LedgerArchitectureSpec'
.\gradlew.bat :ledger-service:integrationTest --tests '*LedgerEngineIntegrationSpec'
.\gradlew.bat check
```

Use `./gradlew` on POSIX. `test` excludes `*IntegrationSpec`; `integrationTest`
includes them, and `check` runs both. Do not present `test` alone as integration
coverage. Runtime or schema changes require full `check` and applicable Compose
smoke checks. An unavailable Docker daemon is a blocked check, never permission
to skip integration tests or claim success.

## Keep fixtures isolated

Prefer fresh Testcontainers databases and synthetic identities. Bound concurrent
tasks with timeouts, assert every result and clean up executors/containers.
For financial fixtures and reconciliation use `$bank-financial-correctness`.

Only the orchestrator operates the user's persistent Compose stack. Current
smoke scripts assume port 8080 and network `arman-bank_bank`; changing only the
Compose project name does not isolate them. Full Compose acceptance belongs in
the disposable `.github/workflows/ci.yml` job until scripts support explicit
isolated targets. `scripts/ledger-smoke.py` funds test accounts in CI only.
Never set `CI=true` locally to bypass its guard, seed live databases, or run
`docker compose down --volumes` on the user's stack.

## Handoff

Return the tested revision or snapshot manifest, commands, actual outcomes,
report paths under `<module>/build/reports/tests/`, and untested cases separately.
Send failures to the orchestrator with a reproducible case and affected owner;
do not patch another owner's files without a transferred lease. Re-test the
integrated fix and ensure required CI is green on the delivered PR head. A
passing health endpoint proves readiness only, not the business workflow.
