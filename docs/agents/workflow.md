# Bank agent team

This repository uses on-demand Codex subagents. It does not run a daemon, poll
GitHub, or start work when the chat is closed. Roles inherit the user's model,
tools and permissions; instructions are coordination rules, not access controls.
Three workers may run concurrently; other roles wait for the next wave.

## Start

Open the repository folder in Codex (the folder containing `settings.gradle` and
`AGENTS.md`, not its parent). Give the primary agent a concrete task:

> Act as bank_orchestrator. Read AGENTS.md, .codex/agents/bank_orchestrator.toml
> and docs/agents/workflow.md. Use the service owners and independent QA agents
> to implement durable wallet-to-ledger provisioning as described in
> docs/agents/missions/wallet-ledger.md. Deliver a tested PR; do not merge.

For a planning-only rehearsal, replace "implement" with "plan only, no writes or
deployment". The mission file is a proposed next slice, not a completed feature.

Custom role definitions live in `.codex/agents/*.toml`; the primary chat performs
the orchestrator role. If the host's delegation tool supports named roles, select
the role by its `name`. If it only supports task messages, read the TOML and pass
its `developer_instructions` along with the scoped assignment. Disclose this
fallback; do not claim automatic role discovery was tested by a manual dispatch.
If subagents are unavailable, report it and execute sequentially without
claiming independent review. A project opened before config changes may need a
new session to load them. Never change global permissions to activate this team.

Format reference: [official OpenAI documentation](https://learn.chatgpt.com/docs/agent-configuration/subagents).

## Ownership

| Role | Default write scope | Responsibility |
| --- | --- | --- |
| bank_orchestrator | Shared files outside service modules | Contracts, integration, platform-runtime, build, CI, Compose, docs, PR |
| gateway_owner | app-gateway/ | Public API, routing and identity checks |
| auth_owner | auth-service/ | Credentials, sessions and revocation |
| user_owner | user-service/ | Owner-scoped profiles |
| wallet_owner | wallet-service/ | Wallet lifecycle and provisioning |
| payment_owner | payment-service/ | Durable orchestration and client idempotency |
| ledger_owner | ledger-service/ | Accounts, balances, immutable balanced postings |
| qa_integration | Read-only until assigned exact test files | Cross-service acceptance, outages, recovery |
| qa_security | Read-only until assigned exact test files | Identity isolation and financial invariants |
| bank_reviewer | Read-only | Independent correctness and architecture review |
| security_auditor | Read-only until assigned exact regression files | System-security review and independent vulnerability retesting |

Owners include their module's tests and OpenAPI. Shared business models must not
move into platform-runtime. QA never edits a file concurrently with its owner.
Use [skill routing](skills.md) for each assignment and the
[communication protocol](communication.md) for findings and fix verification.
The orchestrator may explicitly transfer a named file lease after the previous
writer finishes; default ownership is not permission to edit the entire module
when a narrower assignment exists. Workers return shared-file requests to the
orchestrator. Only affected owners run; do not invent tasks to occupy every role.

## Assignment and handoff

Before writing, the orchestrator records an assignment board in the chat with:

* Task ID, role, goal and acceptance cases.
* Base commit (or exact remote revision for a connector-backed source snapshot),
  working directory, feature branch and PR base.
* Exact writable paths, read dependencies and frozen HTTP/schema contract.
* Dependencies, test commands, and whether the worker has the build lease.
* State: queued, running, review, blocked or done; handoff evidence.

Freeze cross-service contracts before dependent implementation. Dispatch at most
three workers in a wave and retain their IDs for follow-ups. Only the primary
orchestrator delegates unless explicitly assigning a coordination subtask.
Worker handoffs include changed paths, actual commands and outcomes, assumptions,
unresolved risks and requests to other owners. No result means no completed task. A worker may hand off "implementation ready
for integration; CI pending"; only the orchestrator declares final PR delivery
after integrated verification.
Persist a concise non-secret checkpoint in the PR body before ending a session;
resume by checking current source and CI, not by trusting a stale chat summary.

In a Git checkout, inspect existing changes first and use feature branches;
use isolated worktrees when concurrent work cannot be separated safely. In a
shared checkout, allocate disjoint file leases and serialize builds. In a source
snapshot without `.git`, use connector-backed feature branches and an explicit
changed-file manifest; never claim local commits or worktrees exist. Verify the
remote base has not moved before publishing and integrate intervening changes.
Never overwrite the user's uncommitted edits. Inspect every worker diff before
integrating it. Only the orchestrator publishes a combined PR.

## Testing and Docker

Only one agent holds the build lease for a shared directory: Gradle workers can
write common outputs even when checking different modules. Do not run concurrent
`clean` tasks. Prefer isolated PostgreSQL Testcontainers for local integration.

The user's Compose stack is persistent. Workers must not deploy, migrate, seed,
restart or delete it. The orchestrator alone may operate it within the user's
authorized task. Never run `docker compose down --volumes` on that stack.

Current smoke scripts assume gateway port 8080 and network `arman-bank_bank`.
Merely passing `docker compose -p` does not isolate those scripts. Use the existing
disposable GitHub Actions job for full Compose acceptance. Before adding local
parallel Compose tests, make scripts accept target/network settings, use explicit
Compose files excluding local overrides, distinct ports/volumes/project names,
synthetic credentials and project-scoped cleanup. Do not silently reuse live DBs.
`scripts/ledger-smoke.py` seeds funds for disposable CI only. Do not set `CI=true`
locally to bypass its guard or run its funding SQL against the user's data.

Run focused checks first. Runtime/schema changes require the repository's full
`gradlew check` plus applicable Compose smoke checks. QA records executed tests
separately from proposed cases; unavailable tests remain blocked. The independent
reviewer reviews the integrated revision after owners finish. Fix findings and
rerun affected checks. Final delivery needs green required CI on the exact PR
head, evidence links and explicit remaining limitations. Neither green CI nor a
reviewer's approval authorizes merging.

## Architecture documentation

The orchestrator maintains [ARCHITECTURE.md](../../ARCHITECTURE.md) in the same PR
as relevant changes. Every owner handoff includes documentation impact; the
independent reviewer checks the map against the integrated revision. Record a
no-impact reason for changes that do not alter documented behavior. This is a
per-task responsibility, not a background updater.
