---
name: bank-postgres
description: Change ArMoney PostgreSQL migrations and jOOQ transactions with durable concurrency/retry guarantees.
---

# PostgreSQL changes

Read the affected migrations/adapter and platform-runtime/src/main/java/com/arman/bank/runtime/Database.java. Ledger work also needs docs/adr/0005-atomic-ledger.md and PostgresLedger.java in ledger-service/src/main/java/com/arman/bank/ledgerservice/infrastructure/.

Use the same Database.transaction DSLContext for all atomic reads/checks/writes. Bind values, keep identifiers static, reuse the pool, and do not hold transactions over remote calls. Published Flyway migrations are append-only: consider populated upgrades, defaults/backfill, locking, privileges and deployment order; request the consistency/persistence ADR from the orchestrator.

Current ledger posting reserves payment_id then locks accounts in PostgreSQL UUID order. Preserve durable rejection and requester/payload comparison. For money invariants and reconciliation load bank-financial-correctness; do not duplicate that state model here.

Retry confirmed rollbacks only with a bounded whole-transaction policy and stable IDs. Commit connection loss has an uncertain outcome: lookup/retry the original idempotent command. Prove affected migration, constraints, conflict, rollback and recovery behavior using existing real PostgreSQL IntegrationSpec suites under the build lease. Never experiment on live data or print connection secrets. Follow bank-testing for isolation and evidence.
