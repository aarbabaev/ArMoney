# ADR 0023: Local completion-driven task dispatch

## Context

Native subagents finish assigned turns but do not automatically claim another
GitHub task. Markdown roles are instructions rather than an execution service.
The owner requires work to continue through an authorized queue without idle
polling or a new chat message between stages.

## Decision

Use a local Python standard-library supervisor and the installed authenticated
Codex CLI. A private SQLite queue atomically claims explicit manifests with
dependencies, priority, disjoint worktrees and bounded attempts. An OS leader
lock prevents competing supervisors. Six agent slots and two heavy-job slots
include configured external reservations; the default reserves both heavy
slots for the existing CI controllers. These limits do not discover unrelated
processes automatically.

Subprocess completion refills eligible slots. Implementation, independent QA
and review require matching host-observed Git revisions and source fingerprints.
Only requests for changes with nonblank evidence route to the original authorized
owner within its retry budget. Dependency and infrastructure blockers route to
the operator and preserve successful implementations. Repository, role and usable
Git HEAD checks reject unsupported assignments before consuming an attempt.
Rejected success evidence stays blocked. Crash recovery quarantines
leases until explicit orphan-stop reconciliation; it never blindly replays.

GitHub transitions and queue observations use a separate parent-owned durable
outbox. Credentials are excluded from child environments and connector settings;
raw execution streams are discarded. The existing Windows sandbox selection is
retained without changing global settings. Windows state DACLs restrict the
directory and existing files to the current user. Worktree leases in prompts
remain coordination rules, not per-file operating-system enforcement.

The supervisor exits when no eligible work remains. Another explicit invocation
is required to import another approved manifest; no webhook, cron, idle polling
or automatic authorization of third-party issue text is introduced.

## Consequences

Queue progress can continue after the parent chat closes while the local process
and computer remain running. Native chat agents and these CLI jobs are separate.
Model execution uses the existing cloud-backed account; local commands consume
host resources. Timestamped GitHub observations are not a live heartbeat after
a crash. Failed hosted updates remain retryable.

Code review claims never establish green required CI, a merge or deployment.
The existing independent reviewer merge policy and separately authorized local
deployment continue to govern those transitions. No banking service, ledger
consistency, schema or payment completion boundary changes in this ADR.
