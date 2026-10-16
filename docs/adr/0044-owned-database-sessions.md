# ADR 0044: Callback-scoped owned database sessions

Status: implementation in progress; runtime acceptance pending.

## Context

Code-intent staging must commit a nonce reservation before encryption, then check
the same key generation before inserting encrypted details. Future PostgreSQL
session-advisory admission also requires one exclusive physical session across
multiple transactions and external work. Borrowing another pooled connection for
each phase cannot establish that ownership.

## Decision

`Database.withSession` borrows one connection for a callback. Its `OwnedSession`
supports sequential autocommit and transaction callbacks on that same connection,
and explicit invalidation. The original transaction API and pool limit of five
remain unchanged. This is technical plumbing, without shared business entities.

The session rejects cross-thread, nested-phase and post-callback use. SQL contexts
must not escape their callbacks: this is a trusted caller contract, not a sandbox
that mechanically prevents a retained DSL reference from executing SQL.

External calls and encryption occur outside SQL transactions. Commit failure is
an uncertain outcome, not proof of rollback. Callers must retain their original
operation identity, withhold authorization and reconcile under current gates.
Invalidation must dispose of the physical session before it can return to use;
cleanup failures must not hide the original failure. Session advisory locks,
when introduced, require explicit verified release or physical eviction.

## Validation and limits

Required checks include same-backend continuity, committed and rolled-back
phases, guards, pool exhaustion, physical invalidation and advisory-lock release.
Fault injection must distinguish failure before commit from a durable commit
whose acknowledgement is lost, and exercise eviction/abort/restore failures.
A JDBC fault wrapper models these cases; it is not evidence of a real wire fault.

Compilation, PostgreSQL execution and independent final review remain separate
gates. This seam alone does not implement global admission, confirmation, token
release or managed banking sessions, and does not enable production wiring.
