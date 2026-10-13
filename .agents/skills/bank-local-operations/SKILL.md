---
name: bank-local-operations
description: Operate or validate ArMoney local Docker CI, deployment, backup and recovery with revision evidence.
---
# Local operations
Read infra/local-ci/README.md and the relevant Compose/runbook before execution.
Only the primary orchestrator operates the persistent stack; workers use isolated fixtures and serialized builds.
Resolve deployment revision, explicit Compose files, ports and persistent volume identities before mutation. Never replace credentials or remove volumes as routine cleanup.
CI success and container uptime are not deployment acceptance. Record deployed revision, readiness, applicable smoke and failure/rollback outcome.
Read docs/agents/workflow.md for board transitions; application changes stay Awaiting deployment until verified ready.
Respect iOS pause and emulator explicit-only policy. Record same-host redundancy limits and verify restores on isolated resources.