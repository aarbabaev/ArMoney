# ADR 0028: Local agent observations dashboard

The dispatcher uses separate CLI workers, which the native chat agent list does
not include. A loopback-only Python HTTP server exposes a sanitized read-only
SQLite projection and a timestamped native-agent snapshot supplied by the
orchestrator. It never reads GitHub credentials, prompts or raw execution logs.

The server binds 127.0.0.1 and validates the Host header. There are no mutation
routes, CORS permissions or external assets. Worker activity combines persisted
claims with verified parent process identity and observed CLI child counts.
Per-task child identity is not available; excess running claims are uncertain.
Native observations older than 90 seconds become stale and do not contribute
to confirmed activity. The coordinator is excluded from registered worker totals.

Local browser refreshes read only this API; they do not poll GitHub or start jobs.
Missing sources are unknown, not successful or idle. This dashboard changes no
banking API, financial schema or ledger completion boundary.
