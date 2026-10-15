# Local task dispatcher

The dispatcher runs a finite, explicitly authorized local manifest through the
Codex CLI. It fills available capacity after each completion and exits when no
eligible task remains. It does not periodically poll GitHub or run new backlog
work. GitHub synchronization is optional; a board status is not evidence that an
agent is still running or that a change is deployed.
Assignments run as separate noninteractive Codex CLI jobs, not native agents in
the desktop chat's panel. Queue scheduling and child processes use local hardware;
model inference uses the authenticated Codex service and its account limits.

Install the reviewed `infra/dispatcher` package under a stable directory outside
any feature checkout, preserving `infra/dispatcher` beneath that directory.
Keep SQLite state, the manifest and process metadata outside the repository.
Do not update an installation while its dispatcher is running. Use an existing
authenticated Codex CLI (`codex login status`); the launcher does not copy
credentials, configure login, or change global permissions. Python 3.12+ and
Git must be available. The Codex executable must support the flags used in
`runner.py`; the launcher alone does not prove CLI compatibility.

Start a hidden, one-shot Windows process from PowerShell:

```powershell
& 'C:\ArMoneyDispatcher\infra\dispatcher\Start-Dispatcher.ps1' `
  -Python 'C:\Python312\python.exe' `
  -Runtime 'C:\ArMoneyDispatcher' `
  -State 'C:\ArMoneyDispatcherState' `
  -Repository 'C:\src\arman-bank' `
  -Codex 'C:\tools\codex.exe' `
  -Manifest 'C:\ArMoneyDispatcherState\authorized-tasks.json' `
  -ExternalAgents 0 -ExternalHeavy 2
```

The hard caps are six agents and two heavy tasks, including external
reservations. The launcher conservatively reserves both heavy slots by default;
set `-ExternalHeavy` to the observed external usage only after coordinating the
build lease. These are configured reservations, not automatic process discovery.
Use `-GitHub` only for the authorized repository synchronization. State contains
task prompts and evidence; keep it local and exclude secrets from all inputs.
The launcher waits up to ten seconds for a matching PID in `READY.json` before
confirming initialization. This handshake proves initialization only, not that
an assignment, authentication, deployment or hosted transition succeeded. A
timeout retains the process metadata and queue for inspection and graceful stop.

The manifest supplies separate implementation, QA and review tasks with distinct
`agent_id` values. Each task requires `task_id`, `authorized: true`, `role`,
`issue`, `stage`, existing absolute local `cwd`, `prompt`, `agent_id`, explicit
`paths`, `dependencies` and `heavy`. Paths are exact files/directories within cwd;
glob leases are rejected. Priority is an integer and attempts are bounded from
one to ten (default three). QA/review require `read_only: true`, empty writable
paths and an eligible independent role. Review depends on successful independent
QA. Running and interrupted tasks reserve overlapping cwd trees and path leases.

An optional nonblank `pipeline_id` of at most 120 characters identifies an
explicit generation within an issue. Omitting it preserves the legacy issue-wide
group. Owner prerequisites, independent validation, findings, source fingerprints
and remediation are scoped to the same issue and pipeline. A new approved
generation uses new task and runtime identities; existing task IDs cannot be
reused with changed payloads. Known dependency edges must stay within that issue
and pipeline; a manifest containing a crossing edge is rejected atomically.
Unknown dependencies remain queued until an explicitly compatible prerequisite
is supplied. Historical failed generations do not block a distinct approved
generation. Existing queue history is preserved; pipeline migration is a separate
operator reconciliation step, not an automatic relabeling or replay.

Before claiming any queued assignment, the scheduler verifies its directory,
Git common-directory identity and role file. Unsupported assignments become
Blocked without consuming an attempt or launching Codex. Independent clones
are rejected even if they contain the same commits; use a worktree of the
configured repository. Diagnostic codes include `REPOSITORY_MISMATCH`,
`ROLE_UNAVAILABLE` and `ASSIGNMENT_CWD_UNAVAILABLE`; messages contain no raw
Git output, paths, tokens or model text. This preflight does not prove Codex
authentication, available capacity or sandbox permission to execute a test.

Inspect queued/blocked assignments without claiming or running them:

```powershell
python -B -m infra.dispatcher.cli --state C:\ArMoneyDispatcherState preflight --repository C:\src\arman-bank --codex C:\tools\codex.exe
```

The runner returns structured `ready`, `blocked`, `request_changes` or `approve`
results. Exit code zero alone is insufficient. Successful evidence includes the
actual Git `head_sha` and `tree_sha` fingerprint; QA/review must match the
implementation revision and tree, and review must also match QA. Findings route
only when their HEAD/tree match those same successful pipeline prerequisites;
missing or stale finding fingerprints remain blocked for operator reconciliation
without invalidating owners. Actionable findings route back to the original
authorized implementation assignment. Only `request_changes`
with reproduction evidence can automatically retry within the existing `max_attempts`;
repaired implementation is followed by fresh QA and review. Rejected success
claims, stale evidence and interrupted workers remain blocked for inspection.
`blocked` represents unavailable dependencies, capacity or test infrastructure,
including read-only sandbox denial of TEMP/build writes. It never invalidates a
successful owner result or automatically retries the owner. An operator resolves
the dependency and explicitly resumes that same blocked validation assignment.
Read-only validation stays read-only; no write exception is granted to make tests pass.
Retries invalidate older successful validation.
Approval records local `next_stage: awaiting_merge`; the hosted board stays in
Review while CI and externally verified merge gates remain unresolved. Only
externally verified merge and applicable
deployment/readiness evidence permit Done; Git status, agent approval and a
successful local command cannot establish deployment.

`-StatusIssue` selects the operational issue containing timestamped observations
of this queue and configured external reservations. Observations may be stale
after a hard crash; this is not a heartbeat or a count of native agents in other
chats. Failed comments, board writes and summaries remain in the reporting
outbox and retry on the next invocation even when the queue is empty.

`Store(db_path)` exposes `enqueue(spec)` (duplicate exact payload returns false),
`enqueue_batch(specs)` (validates all specs and atomically inserts all or none),
`claim_available(limits)` (atomic claims with incremented attempts),
`complete(task_id, attempt, result)` (stale completions return false), `snapshot()`
and `recover()`. Recovery quarantines running tasks as interrupted and retains
their capacity and leases. Confirm the orphan child and its descendants have
stopped before calling `reconcile(task_id, orphan_stopped=True)`; only then may
an operator explicitly `resume(task_id)`. Do not guess that a crashed parent
means its child stopped. No automatic replay occurs.

`Scheduler(store, runner, limits=None, transition=None).run()` acquires the
queue's exclusive OS leader lock and awaits completions without an idle polling
loop. An optional async transition callback receives `(event, task, result)` for
started/completed/preflight_blocked events. Callback failures persist in `event_failures()` and do
not discard claims or completion evidence. A leader crash releases the OS lock
but does not release interrupted task leases. Do not delete the leader lock file.

Request a graceful stop:

```powershell
& 'C:\ArMoneyDispatcher\infra\dispatcher\Stop-Dispatcher.ps1' `
  -State 'C:\ArMoneyDispatcherState'
```

The helper checks the recorded PID, creation time, executable and command line,
then creates `STOP`. The dispatcher checks it between completions; active work
finishes before exit. It never kills an unknown process or deletes tasks,
worktrees, SQLite state or persistent Docker resources. Reconcile interrupted
work before explicitly removing STOP for another invocation. A manifest waiting
on a dependency remains queued when the one-shot process exits.

Import an explicitly approved catalog from the Project once:

```powershell
python -B -m infra.dispatcher.cli --state C:\ArMoneyDispatcherState import-project --catalog C:\ArMoneyDispatcherState\approved-catalog.json
```

The private catalog is a JSON array of the task specifications described above,
including separate owner, QA and reviewer assignments and existing isolated
worktrees. Import reads compact Project metadata only. It admits catalogued open
issues created by the repository owner with empty, Backlog or Ready status, and
excludes held tasks, pull requests, drafts and paused iOS work. It never derives
permissions from issue text, creates worktrees or starts execution. Validation
and pagination failures import nothing; prior issue claims prevent duplicate
pipelines. Start the dispatcher separately to drain the imported queue. An empty
queue exits without periodically checking GitHub.

Core tests use temporary SQLite databases and fake asynchronous runners:
`python -B -m unittest infra.dispatcher.test_scheduler -v`. They validate queue
behavior, not authentication, hosted board access, real Codex execution or
deployment readiness. Runtime integration needs separate recorded evidence.
