---
name: bank-postgres
description: Change or review Arman Bank PostgreSQL persistence, Flyway migrations and jOOQ transactions, including concurrency and retry correctness.
---

# PostgreSQL persistence work

This instruction-only skill installs nothing. Paths are repository-relative. Read the owning service's src/main/resources/db/migration and persistence adapter, plus platform-runtime/src/main/java/com/arman/bank/runtime/Database.java. For ledger changes, also read docs/adr/0005-atomic-ledger.md and ledger-service/src/main/java/com/arman/bank/ledgerservice/infrastructure/PostgresLedger.java.

Work only in the service's own database. Use parameterized jOOQ queries; static SQL with bound parameters is supported by existing adapters. Never interpolate client values or accept client-controlled SQL identifiers. Keep related reads, checks and writes inside the same Database.transaction callback and use its DSLContext throughout. Reuse the shared Hikari pool; close acquired resources and never hold a database transaction open across a remote HTTP call.

State the invariant before choosing locking or isolation. Current ledger posting reserves payment_id, then locks account rows in PostgreSQL UUID order; opposite-direction transfers must follow the same order. Balance checks, journal entry, projection and terminal outcome are atomic. Preserve durable rejection and request/payload comparison on duplicate IDs. A changed requester or payload must conflict rather than reuse another result.

Do not add blanket retries. If a change needs retry for a confirmed rollback such as a deadlock or serialization failure, retry the entire transaction with a bounded policy and stable business IDs; test exhaustion. A connection failure during commit has an uncertain outcome: recover through lookup or repeat the idempotent command with its original ID. Never create a new payment ID merely because a request timed out.

Published Flyway migrations are append-only. Add the next migration and consider populated existing databases, defaults/backfill, constraints, locks and deployment order. Never repair migration history, drop data or invent balances to make a migration pass. Runtime credentials must not acquire additional privileges silently; report privilege separation work separately.

Use Testcontainers PostgreSQL through the existing IntegrationSpec suites for migrations, constraints and transaction semantics. Cover clean creation and representative upgrade data where the migration requires it; test concurrent conflicts, duplicate requests, rollback after partial work, overflow and reconciliation for monetary changes. Testcontainers/CI data is disposable; do not run funding fixtures or migration experiments against the user's persistent Compose databases. Do not print .env or connection secrets.

Return the migration/adapter paths, transaction and lock assumptions, commands/results and any recovery limitations to the orchestrator. Schema or consistency changes need an ADR and integrated Compose smoke evidence before completion.
