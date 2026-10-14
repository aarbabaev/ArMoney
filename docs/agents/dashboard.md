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

The orchestrator writes `native-agents.json` in the private state directory:
`observed_at` is a UTC ISO timestamp and `agents` contains only `id`, `role`,
`state` and a sanitized `task`. This is a snapshot, not an automatic connection
to the native app. After 90 seconds native activity is unknown until refreshed.
The UI polls only the local read-only endpoint every three seconds. It displays
source failures and timestamps explicitly; disconnected data is not live.

Test with `python -B infra/agent-dashboard/test_server.py`.
See [ADR 0028](../adr/0028-local-agent-dashboard.md).
