# Mission: durable wallet-to-ledger provisioning

Status: implemented and integrated into main. This mission preserves the original
acceptance scope; it is not an instruction to reimplement the slice. Changes still
require focused/full checks and exact-head CI. Decision: [ADR 0007](../../adr/0007-wallet-ledger-provisioning.md).
The subsequent [durable public P2P](../../m1.md) slice is implemented; this mission retains its historical scope.

Goal: every usable wallet converges to exactly one matching zero-opening-balance
ledger account despite duplicate requests, timeouts and service restarts.
No funding endpoint, P2P payment execution, broker or new infrastructure.

1. Orchestrator: inspect the latest code and ADRs, then freeze an ADR and HTTP
   contract. Keep wallet ACTIVE/CLOSED lifecycle separate from provisioning
   PENDING/READY state and account ID. Decide public pending responses and the
   safe migration/reconciliation of existing wallets. Do not infer readiness
   from ACTIVE metadata or reopen closed wallets.
2. Ledger owner: verify account creation converges by stable wallet UUID with
   matching authenticated owner and currency; reject changed identity/currency.
   New accounts start at zero. Add only missing behavior/tests.
3. Wallet owner: persist intent before bounded ledger calls without holding DB
   locks across HTTP. Retry the same wallet UUID after uncertain outcomes.
   Validate returned mapping before READY. Implement durable bounded recovery
   after restart; retain pending work when ledger is down.
4. Gateway owner: apply the agreed public response/OpenAPI changes and preserve
   live authentication and client-header sanitization. Do not expose ledger.
5. QA integration: test duplicate concurrent creation, ledger outage, a lost
   response after ledger commit, restart before READY save, eventual recovery
   without duplicate accounts, and migration of existing/closed wallets.
6. QA security: verify owner isolation, forged identity rejection, mapping
   mismatch rejection, zero opening balance and no fabricated success.
7. Security auditor: review cross-service trust and ensure provisioning cannot
   attach another owner's ledger account. Route findings through the communication
   protocol and independently verify developer fixes.
8. Independent reviewer: inspect the integrated diff and test evidence.

Auth, user and payment owners remain idle unless an actual dependency emerges.
Order: contract -> ledger validation + wallet implementation -> gateway contract
integration -> independent QA/review -> fixes -> full CI -> PR, without merge.
Parallelize only independent work with frozen contracts and disjoint leases.
