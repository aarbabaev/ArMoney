# ADR 0044: Callback-scoped owned database sessions

Status: source implemented; focused PostgreSQL and request-runtime acceptance
verified. Final integrated revision checks and activation remain separate gates.

## Context

Code-intent staging must commit a nonce reservation before encryption, then check
the same key generation before inserting encrypted details. PostgreSQL
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
cleanup failures must not hide the original failure. Auth now uses this seam for
session advisory admission: explicit verified release or physical eviction is
required before returning the connection to the pool.

## Validation and limits

Required checks include same-backend continuity, committed and rolled-back
phases, guards, pool exhaustion, physical invalidation and advisory-lock release.
Fault injection must distinguish failure before commit from a durable commit
whose acknowledgement is lost, and exercise eviction/abort/restore failures.
A JDBC fault wrapper models these cases; it is not evidence of a real wire fault.

The earlier 47-test platform check includes 17 owned-session cases. Auth's
focused real-process acceptance at `2f8b0ae` passed seven cases, including two
actual Main processes, sixteen shared permits, slow-body closure and connection
reuse. Independent QA and security verified the retained evidence. These are
bounded checks, not a full current-head CI result or activation approval.

The seam alone does not implement business admission. Auth supplies its own
global sixteen-operation gate and callback-scoped request capability. Neither
implements confirmation, positive token release or managed banking sessions.
