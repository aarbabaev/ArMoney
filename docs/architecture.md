# Architecture

## Boundaries

| Service | Owns | Bootstrap |
| --- | --- | --- |
| app-gateway | External routing and request policy | Operational API only, no proxy |
| auth-service | Credentials and authentication | Identity schema; no login endpoint |
| user-service | Customer profile | Profile schema |
| wallet-service | Wallet identity, owner, currency, lifecycle | Wallet schema, no stored balance |
| payment-service | P2P request state and client idempotency | Payment schema |
| ledger-service | Accounts, immutable paired postings, authoritative balance | Transfer value object and schema |

All services are independently deployable Gradle application projects.
platform-runtime shares lifecycle, database wiring and operational HTTP only.
No service-to-service Java dependencies. Business code grows in domain,
application and infrastructure packages when there is an actual use case.

## Planned P2P flow (not implemented here)

Client -> gateway -> payment -> ledger over authenticated HTTP.
Payment resolves and authorizes the sender, validates wallets/currency and records
PENDING with an idempotency key scoped to the requester plus a payload hash.
Ledger locks both accounts in deterministic order and checks the available balance
within the same database transaction that inserts a transfer.
One row yields equal debit and credit entries in the postings view. Foreign keys
enforce matching account currencies. The immutable-row trigger prevents ordinary
updates/deletes; production permissions must also prohibit TRUNCATE and DDL.

Ledger uniqueness on payment_id is the retry boundary. Before returning an existing
result, compare its full payload; conflicting reuse must fail.
A timeout means UNKNOWN outcome, never automatic rejection. Payment retries with
the same payment ID or queries ledger, then transitions to COMPLETED/REJECTED.
Recovery must reconcile PENDING records after restart. No distributed transaction
and no assertion of exactly-once HTTP delivery.

Bootstrap does not yet enforce balances or implement any posting endpoint.
The schema's initial account type is a customer wallet; funding/clearing accounts,
holds, fees, FX and reversal policy need separate ADRs before implementation.

## Runtime

Migration failure prevents startup. Readiness fails when the database is unreachable;
liveness remains independent of database health. Pools and listeners close at shutdown.
Each database belongs to exactly one service. IDs across services are references,
not cross-database foreign keys. The local Compose network is not authentication:
internal HTTP requires service identity before business endpoints are enabled.
