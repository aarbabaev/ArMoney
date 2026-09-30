# ADR 0007: Durable asynchronous wallet account provisioning

Status: accepted.

## Decision

Wallet creation commits metadata and provisioning intent in its own PostgreSQL
transaction. Lifecycle (`ACTIVE` / `CLOSED`) stays separate from provisioning
(`PENDING` / `READY`). A READY wallet stores its immutable ledger account UUID;
PENDING has no account mapping. An ACTIVE wallet is usable for future payment
flows only after READY, and this slice does not itself expose public transfers.

POST `/v1/wallets` remains unique by authenticated owner/currency and preserves the
same wallet UUID on retry. It returns 202 for ACTIVE/PENDING, or 200 for READY and
existing CLOSED wallets. GET returns 200 with `provisioning_status` and nullable
`ledger_account_id` in every wallet. Clients poll GET to observe eventual READY.
Creation does not wait for the ledger on the HTTP request thread.

A bounded background worker claims due ACTIVE/PENDING work durably before making
HTTP calls. Claims expire so another worker can recover abandoned work after a
process failure. Requests and retries reuse the wallet UUID and trusted owner.
No wallet database transaction is held across HTTP. Retry scheduling is durable
and bounded in rate; a failing wallet must not starve later pending wallets.

Ledger POST `/v1/ledger/accounts` is the idempotency boundary. Concurrent/repeated
requests with the same wallet, owner and currency converge on the same account;
changed owner/currency conflict. The wallet client validates the returned account
UUID and exact owner/wallet/currency before storing READY. An existing account can
have a nonzero balance on replay; wallet does not become a second balance owner.
Timeouts, malformed responses and mapping mismatches never fabricate success.

## Migration and recovery

Published V1/V2 remain unchanged. V3 starts existing wallets as PENDING, preserving
their identity and lifecycle. Existing ACTIVE wallets are reconciled without a
new client request. CLOSED wallets are neither reopened nor provisioned. A CLOSED
wallet may remain PENDING with no account, or retain an existing READY mapping.

If ledger commits and its response is lost, or wallet crashes before saving READY,
the next attempt repeats the same wallet UUID. The ledger returns the original
account and the wallet persists the mapping. This is recovery over two independent
transactions, not a distributed transaction or exactly-once HTTP delivery.

## Operations and validation

Wallet requires `LEDGER_BASE_URL` and the existing internal service key. A reusable
HTTP client has bounded request/response handling and does not follow redirects.
Worker database operations have 2-second lock and 5-second statement timeouts.
Shutdown waits up to 10 seconds for the worker before closing its resources. If
the worker remains blocked, an explicit error leaves resources open until process
termination; the durable lease allows recovery. Administrative closure after a
claim cannot atomically cancel an in-flight ledger call and may leave an unmapped
zero-balance account. There is no public close endpoint. Wallet readiness continues
to check its own database; ledger outage is represented by pending provisioning.
The current shared-key trust boundary is unchanged.

Regression evidence includes concurrent duplicate requests, mismatched owner and
currency, populated migration, outage and retry, response loss after remote commit,
restart before local confirmation, expired-claim recovery, closed-wallet behavior
and fairness. End-to-end CI uses disposable Compose data and injects a ledger
outage plus wallet restart; it does not fund accounts or touch the user's databases.
