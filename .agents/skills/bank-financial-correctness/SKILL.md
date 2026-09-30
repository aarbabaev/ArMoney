---
name: bank-financial-correctness
description: Implement or independently verify ledger, wallet and payment money invariants, idempotency, concurrent spending and recovery from uncertain outcomes in this P2P banking repository.
---

# Financial correctness

Read the affected migrations, HTTP contract and `docs/adr/0005-atomic-ledger.md`.
Inspect
`ledger-service/src/test/groovy/com/arman/bank/ledgerservice/LedgerEngineIntegrationSpec.groovy`
and `LedgerHttpIntegrationSpec.groovy` before extending behavior. Coordinate
cross-service contract changes through the orchestrator. This skill covers
financial integrity; the security auditor independently assesses exploitability
and authorization boundaries.

## Preserve the state model

- Represent an amount as integer minor units plus currency. Validate positive
  transfer amounts and detect signed 64-bit overflow; never use floating point
  or silently round an unrepresentable API amount.
- The ledger owns balances. Posting, both balance changes and the durable result
  must commit atomically. Journal entries and terminal results are immutable.
  Test database enforcement as well as application validation.
- Debit and credit must use the same currency and distinct eligible accounts.
  Verify debit ownership from authenticated identity. Customer balances cannot
  become negative; the fixture clearing account is an explicit exception.
- Replays of one payment ID must return the same result without another posting.
  Compare requester and all relevant payload fields; reject reuse with changed
  data. A durable insufficient-funds result stays rejected after later funding.
  Public client keys are requester-scoped and must map to a stable ledger ID.
- Acquire account locks in a consistent order. Avoid relying on a pre-transaction
  balance read; concurrency tests must demonstrate that overspending is impossible.
- A transport timeout does not prove rejection. Preserve the operation ID, query
  the authoritative result or retry the same command, and reconcile after restart.
  Never create a fresh ID to escape uncertainty or report payment completion
  before ledger confirmation. Wallet provisioning retries must converge on one
  account without changing owner/currency mapping.

## Executable acceptance

For affected money paths, prove these on real PostgreSQL with synthetic fixtures:

1. Each posting has two opposite signed entries and nets to zero per currency.
   Reconcile each stored balance with its signed journal sum, and all balances
   including clearing accounts with the journal total.
2. Simultaneous distinct transfers cannot overspend; simultaneous identical
   commands move funds once; opposite-direction commands finish within a bound.
3. Insufficient funds, wrong currency, missing/foreign accounts, amount limits
   and credit overflow move no money and preserve documented outcomes.
4. Inject failure after journal insertion but before commit; assert rollback of
   the journal, both balances and incomplete reservation, then successful retry.
5. Lost responses and process restart retain replay/lookup semantics. Test both
   sides of the commit boundary when adding distributed orchestration.
6. Attempts to mutate/delete committed journal or terminal results fail under
   the actual application database role; distinguish trigger guarantees from
   privileges a database administrator can bypass.

Use the existing `fund` and `assertReconciled` patterns in
`LedgerEngineIntegrationSpec`: funding creates an explicit clearing counterpart
and a real balanced transfer. Do not update balances directly to make a test
pass or expose fixture funding as a customer API. Fixtures run only in disposable
Testcontainers or the dedicated CI environment, never the user's databases.

Use `$bank-testing` for commands and isolation rules. Report each violated
invariant with the operation/retry sequence, expected and actual durable state,
affected owner and regression test. The orchestrator assigns the repair; an
independent QA pass verifies the integrated revision before closing the finding.
