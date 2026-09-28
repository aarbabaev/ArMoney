# ADR 0002 — Ledger is the consistency boundary

Status: Accepted design; transfer execution deferred

Payment workflow state and wallet metadata must not become balance authorities.
The ledger owns accounts and financial effects in a single PostgreSQL transaction.

For same-currency P2P, model a transfer as one positive amount with distinct debit
and credit accounts. A view expands it into two opposite postings, making an
unbalanced transfer unrepresentable. Composite foreign keys enforce currency
agreement. Corrections will be compensating transfers, not edits.

This deliberately supports only two-sided same-currency transfers. Fees, FX,
multi-leg journals, holds and funding need a later model decision. Immutability
triggers do not protect against a database administrator; production roles and
audit/backup policy remain necessary.

Before exposing writes, add deterministic account locking, no-overdraft enforcement,
payload-aware idempotency, concurrency and crash/retry integration tests.
The schema alone is not a complete financial consistency implementation.
