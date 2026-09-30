# ADR 0006: On-demand service owners coordinated in Codex

Status: accepted for development tooling.

## Context

Six deployable services need clear ownership while cross-service contracts,
financial invariants and shared local Docker state require coordinated changes.
The current need is supervised development from a chat, not unattended hosting.

## Decision

Use a primary orchestrator, six service-owner roles, integration QA, security and
financial QA, and an independent reviewer. Store standalone Codex role TOMLs in
the repository, inheriting the user's model and permissions. Limit concurrent
workers to three and schedule dependent work in waves. Define path assignments,
build leases and evidence requirements in docs/agents/workflow.md.

## Consequences

No API server, API key or scheduler is introduced. These roles do not execute
without a Codex session and are not security sandboxes. The orchestrator must
check scope and evidence. Existing tests and CI remain the executable gates.
Local production-like data must not become test fixtures. Later unattended
GitHub execution would need separate event handling, durable task state, isolated
workspaces, credentials, retries and spend limits; this ADR does not implement it.
