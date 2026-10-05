# Ledger slice

AED is the only supported currency; `balance_minor` and `amount_minor` are integer
fils. Optional [replication operations](ledger-replication.md) use two direct
physical standbys for fenced balance reads. Posting, account creation and payment
result lookup remain primary-only; a replica is never the balance authority for
a transfer decision. See [ADR 0013](adr/0013-ledger-replication.md).

Ledger implements zero-balance account creation, owner-scoped balances, atomic
same-currency posting and durable payment results. These routes are internal:

| Method | Path | Purpose |
| --- | --- | --- |
| POST | /v1/ledger/accounts | Create or retrieve an account by wallet_id and currency |
| GET | /v1/ledger/accounts/{id} | Read the verified owner's account and balance_minor |
| POST | /v1/ledger/transfers | Post or replay a payment_id |
| GET | /v1/ledger/transfers/{id} | Read the verified requester's stored result |

Both X-Service-Key and X-Identity-Id are required. Only trusted service callers
may supply identity headers. There is no direct gateway ledger route. Public P2P goes through payment-service,
which validates wallet ownership and confirmed phone recipients before posting.
OpenAPI is available on ledger-service's own /openapi.yaml.

An account request contains wallet_id (UUID) and currency (AED).
A transfer contains payment_id, debit_account_id, credit_account_id (UUIDs),
currency and amount_minor (positive int64; 100 fils = 1 AED).
POST returns 200 for POSTED, 409 with a durable outcome for rejection,
or 409 idempotency_conflict if the ID's payload/requester changes.
GET result returns 200 even for a stored rejection, 404 when absent/not owned.
Lookup/retry the SAME payment_id after a timeout.

Balances start at zero. Wallet-service automatically provisions ACTIVE/PENDING wallets
through this private API and persists the confirmed mapping; see ADR 0007.
No funding API exists. Synthetic funding happens only in isolated tests, with a
real opposite posting against a test clearing account.

## Local update / IDEA
For the base backend, docker compose up --build -d applies outstanding migrations
without deleting volumes. Existing SSO deployments must retain their explicit
Compose file set from [the SSO runbook](sso-and-ios.md).
Existing INTERNAL_AUTH_KEY also configures ledger. No new secret is needed.
The DB port below requires an ignored loopback port override; base Compose does
not publish ledger or its database. IDEA does not load .env automatically.
For IDEA use Java 21, PORT=8084, DB_URL=jdbc:postgresql://127.0.0.1:5437/bank,
DB_USER=bank, DB_PASSWORD from LOCAL_DB_PASSWORD, INTERNAL_AUTH_KEY from .env.
Keep these in an ignored .env file. Docker gateway does not forward to this port.

Run ./gradlew :ledger-service:check with Docker to run the real PostgreSQL suite.
scripts/ledger-smoke.py is intentionally CI-only: it inserts synthetic clearing
funding into disposable databases. It must not be used as a real top-up tool.
See ADR 0005 for locking, trust boundaries, migration and recovery semantics.
