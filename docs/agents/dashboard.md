# Local agent dashboard

Run with the bundled Python executable:

```powershell
python -B infra/agent-dashboard/server.py --state C:\Users\ASUS\AppData\Local\ArMoneyDispatcher\state --port 8765
```

Open http://127.0.0.1:8765. The server is local only and does not start agents.
The dispatcher SQLite queue and process metadata are read-only observations.
Protocol test workers are excluded. Total workers counts distinct registered
native and CLI identities, including completed and queued workers, excluding
the coordinator. Active counts require fresh native observations or live CLI
children under the verified dispatcher process. It is not a CPU-use metric.

## Explicit historical attempts

The operator may annotate superseded task attempts in the private state directory
using optional `archived-tasks.json`. Keys identify exact dispatcher `task_id`
values, not agent identities or issue numbers:

```json
{
  "previous-attempt-id": {
    "reason": "Operator confirmed this attempt was superseded; original failure retained",
    "archived_at": "2026-10-14T12:00:00Z"
  }
}
```

Only write an annotation after checking the attempt's history and replacement.
Archiving does not resolve a failure, change its original state or prove that a
replacement succeeded. There is no automatic archive policy. Removing an
annotation returns the attempt to current reporting. Reasons must be nonempty,
at most 1,000 characters and contain no credentials or personal information;
timestamps must include a timezone.

The server reads the mapping and queue without modifying either. API
`dispatcher.agents` contains current attempts and `dispatcher.history` contains
explicitly archived attempts with original state, task ID, reason and timestamp.
Current queued, blocked and active counts exclude archived attempts. Total
registered still counts all distinct native/CLI identities across current and
historical attempts; repeated generations of one identity count once. The
collapsible Historical attempts section preserves original states, including
blocked/interrupted states, without presenting them as current blockers.

Missing annotations mean no attempts are archived. Invalid or unreadable
annotations produce a warning and preserve every queue attempt as current;
queue unavailability produces a source error and never invents historical rows
from annotations alone. The local endpoint never serves the annotation file.

The orchestrator writes `native-agents.json` in the private state directory:
`observed_at` is a UTC ISO timestamp and `agents` contains only `id`, `role`,
`state` and a sanitized `task`. This is a snapshot, not an automatic connection
to the native app. After 90 seconds native activity is unknown until refreshed.
The UI polls only the local read-only endpoint every three seconds. It displays
source failures and timestamps explicitly; disconnected data is not live.

Test with `python -B infra/agent-dashboard/test_server.py`.
See [ADR 0028](../adr/0028-local-agent-dashboard.md).
