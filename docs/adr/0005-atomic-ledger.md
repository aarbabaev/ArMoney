# ADR 0005 — Atomic ledger commands and balance projection

Status: Accepted for local M1

## Scope and trust
Ledger now exposes private /v1/ledger account, balance, transfer and result APIs.
There are no gateway ledger routes. The caller must provide the internal service key
and an already verified X-Identity-Id. This is a trusted service-to-service API,
not an end-user authentication endpoint: it does not accept a bearer token as proof.
Account provisioning trusts the service caller to supply the correct wallet UUID;
wallet existence/status must be checked by future wallet/payment orchestration.
Owner means auth identity UUID. Account reads and command lookup are owner-scoped.
Only the source owner can debit a CUSTOMER account; CLEARING accounts cannot be
created, read or debited through the HTTP API. Shared-key limitations from ADR 0004
still apply. Distinct production roles/credentials are still required.

Wallet metadata is not automatically provisioned into ledger yet. Payment-service
orchestration and public P2P endpoints are the next slice. There is no top-up API.
New customer accounts start at zero. Testcontainers and CI use explicit synthetic
CLEARING accounts and balanced funding journal entries; no production seeding occurs.

## Atomicity and journal
V1's immutable transfer row continues to represent exactly two opposite postings.
V2 adds a BIGINT balance projection on accounts and an AFTER INSERT trigger to
update both sides in the same transaction. Customer balances cannot be negative.
BIGINT overflow aborts the transaction. Transfer mutations and TRUNCATE are blocked.
Corrections must be new compensating entries, never edits.

Application transactions first reserve a unique payment_id, then lock both account
rows in PostgreSQL UUID order. Read/check/post/result persistence share that transaction.
Deterministic account lock order handles concurrent and opposite-direction commands.
The adapter checks ownership, currency, available balance and recipient overflow.
The DB trigger/check constraint additionally blocks a direct SQL overdraft.
Locking and balance decisions use PostgreSQL's default READ COMMITTED isolation.
Privileged SQL administrators can still alter balance projections; production must
restrict runtime permissions. Reconciliation against postings is tested.

## Stable retry and recovery
transfer_requests stores requester and every original payload field (compared exactly,
not only via a hash) together with a durable outcome. Same payment_id and payload
return the original outcome, including INSUFFICIENT_FUNDS after later funding.
Any payload or requester change conflicts. Concurrent identical requests wait on
the unique insert and observe one committed result. Different payment IDs remain
distinct intentional commands: client idempotency-key mapping belongs to payment-service.

Database/network errors roll back entries, balances and the command reservation.
A timeout after commit has an uncertain result: caller must lookup or retry the same
payment_id, never invent a new ID. Deferred constraints prevent committing PENDING
or a POSTED result without the matching journal entry. Terminal results are immutable.
No automatic retries hide ambiguous outcomes.

## Migration and limits
V1 is unchanged. V2 backfills projections from postings. Legacy rows have no owner and
are inaccessible through the API. Negative legacy customer balances or BIGINT overflow
cause migration failure rather than invented funding or data deletion; operator review
and an explicit migration would be needed for any such manually inserted legacy data.
EUR/USD/GBP are the supported application currencies. Amounts are positive integer
minor units. Account identities cannot be remapped after creation.

Tests cover concurrent debit exhaustion, same-ID retry, opposite directions,
durable rejection, authorization, mismatched payload/currency, overflow, SQL overdraft,
immutability, rollback after balance trigger execution and restart/reconciliation.
Compose additionally verifies the private HTTP flow on disposable funded accounts.
