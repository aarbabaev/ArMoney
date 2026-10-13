# Bank agent team

This repository uses on-demand Codex subagents. It does not run a daemon, poll
GitHub, or start work when the chat is closed. Roles inherit the user's model,
tools and permissions; instructions are coordination rules, not access controls.
Three workers may run concurrently; other roles wait for the next wave.

Independent feature implementation may continue in separate worktrees while the
orchestrator validates or deploys an earlier integration. Do not idle all owners
merely because another task is Awaiting deployment. Keep writable paths disjoint,
serialize shared builds and persistent-stack operations, and record each active
task's runtime agent and current stage on its Project item. Review and deployment
are active work even when the In Progress development column is empty.

## Start

Open the repository folder in Codex (the folder containing `settings.gradle` and
`AGENTS.md`, not its parent). Give the primary agent a concrete task:

> Act as bank_orchestrator. Read AGENTS.md, .codex/agents/bank_orchestrator.toml
> and docs/agents/workflow.md. Use the service owners and independent QA agents
> to implement durable wallet-to-ledger provisioning as described in
> docs/agents/missions/wallet-ledger.md. Deliver a tested PR; do not merge.

For a planning-only rehearsal, replace "implement" with "plan only, no writes or
deployment". The mission file records the provisioning slice and its acceptance criteria;
choose the next concrete slice from the current architecture map.

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
| sso_owner | sso-service/; explicitly leased auth adapter files | Keycloak/OIDC and identity mapping |
| android_owner | android/ | Native Kotlin/Compose app, gateway integration, browser authentication and secure local recovery |
| ios_owner | ios/ | Native SwiftUI app, browser authentication and secure session storage |
| user_owner | user-service/ | Owner-scoped profiles |
| wallet_owner | wallet-service/ | Wallet lifecycle and provisioning |
| payment_owner | payment-service/ | Durable orchestration and client idempotency |
| ledger_owner | ledger-service/ | Accounts, balances, immutable balanced postings |
| qa_integration | Read-only until assigned exact test files | Cross-service acceptance, outages, recovery |
| qa_security | Read-only until assigned exact test files | Identity isolation and financial invariants |
| bank_reviewer | Read-only source; gated PR merge | Independent correctness and architecture review |
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
head, evidence links and explicit remaining limitations. Owner standing authorization permits reviewer merges only under [merge policy](merge-policy.md).

## Architecture documentation

The orchestrator maintains [ARCHITECTURE.md](../../ARCHITECTURE.md) in the same PR
as relevant changes. Every owner handoff includes documentation impact; the
independent reviewer checks the map against the integrated revision. Record a
no-impact reason for changes that do not alter documented behavior. This is a
per-task responsibility, not a background updater.

## Pull request base

Target `main` for all subsequent pull requests. The backend and native iOS/SSO
slices have been integrated into main. Start future feature branches from the latest main unless the user
explicitly chooses a different starting point; do not continue using the bootstrap
branch as the default integration target. PR creation never authorizes a merge.

## Focused context

Use [context map](context-map.md) to load relevant knowledge on demand. Prefer a fresh
worker context with an exact contract, revision and file lease over inheriting the
entire conversation. Reuse that worker for corrections. Read selected instructions
in full once, return bounded evidence, and avoid repeated polling of unchanged work.
See the measured [context audit](context-efficiency.md); do not interpret character
reductions as measured token savings.

Reviewer merges follow [standing owner authorization](merge-policy.md); PR creation alone is insufficient. This policy overrides older example missions that prohibit merges unless the current owner explicitly places that task on hold.

## Dashboard is the operational source of truth

Owner instruction, 2026-10-13: keep working through authorized actionable tasks while the active session can progress, with truthful development, review and deployment status. When the authorized queue is empty, remain idle until asked; do not reinstate periodic polling. Backlog-only requests remain planned work, not automatic implementation authorization.

Status contract and responsible actor:
- Backlog: planned, not scheduled for execution.
- Ready: authorized and eligible to start once recorded dependencies permit.
- In Progress: implementing owner actively working; orchestrator records role/runtime, branch, start time and next milestone.
- Review: independent reviewer actively reviewing the published PR; reviewer owns findings and status.
- Awaiting merge: approved but merge is waiting on an explicit constraint; reviewer records it.
- Awaiting deployment: merged application change not yet verified on the local stack; orchestrator owns deployment tracking.
- Done: deployed revision and applicable readiness/acceptance checks confirm the application change is ready for owner use. Merge alone is insufficient. Documentation-only work may be Done after verified merge with an explicit no-deployment-needed reason.
- Blocked: work cannot currently proceed; name the dependency, responsible party and next action. Never leave a blocked task presented as actively implementing.

Until the board has a separate deployment status, use Awaiting deployment during both queued and running deployment, with an explicit activity checkpoint distinguishing them.
At every meaningful handoff, the responsible actor updates the issue and Project together: active/queued/idle/blocked, actual agent role/runtime, PR and revision, completed evidence, next action, and deployment state. Include timestamps; do not post noisy unchanged updates.
When work stops without completion, record the idle/waiting state and reason; In Progress must not imply an agent is running when none is.
Record deploy start, deployed commit, result and readiness/acceptance evidence. A failed deploy cannot be Done. Preserve existing deployment authorization boundaries; when authorization is missing, make that waiting reason explicit.
This is an on-demand agent workflow, not an always-running service. Do not claim continuous execution after all agents stop. Do not report completion or actual status changes before tool verification.
