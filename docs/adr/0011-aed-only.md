# ADR 0011: AED-only monetary operations

Status: accepted

## Decision

ArMoney supports AED only. An amount is a signed 64-bit integer number of fils:
100 fils equals 1 AED. Transfers require a strictly positive amount. Existing
overflow, nonnegative customer balance, atomic posting and idempotency rules remain.

The currency field stays in contracts and stored records to make the monetary
unit explicit. New wallet, payment and ledger commands reject every value other
than uppercase `AED`, including former EUR/USD/GBP values. Clients offer an AED
wallet action without a currency selector. No FX or multi-currency feature is planned.

Wallet, payment and ledger services independently enforce their boundary; they do
not share a business-currency type through platform-runtime. Their next Flyway
migrations enforce AED in persisted monetary tables, including notifications and
durable transfer requests. Published migrations are unchanged.

## Existing data and rollout

No migration relabels, converts, rounds or deletes money. If a database contains
non-AED monetary records, its migration fails before changing them. Operators must
resolve this before deployment; arbitrary relabeling is not a conversion strategy.
In particular, do not bypass immutable-journal triggers or rewrite payment hashes.

The owner explicitly authorized a fresh local start, including deletion of bank
user data and ArMoney realm end users. That is a one-time workstation operation,
not an application startup behavior or an automated migration. Take private backups
of all six databases, stop ingress and banking services, then clear bank-owned
application tables and delete only end users from the ArMoney realm. Preserve Flyway
history, Keycloak master administrators, service accounts, clients and configuration.
Apply the new schema before reopening services. Never run this reset in CI against
the workstation or check backups, credentials or reset data into Git.

Existing device sessions will be invalid after this reset; sign in or register
again. Encrypted uncertain commands must never be rewritten as AED. Old identity
scopes remain isolated from newly registered identities.

## Verification

Service tests cover accepted AED, rejection of unsupported currencies, database
constraints and populated migration behavior. Existing concurrency, rollback,
replay, insufficient-funds and owner-isolation tests remain required in AED.
Disposable Compose tests exercise AED onboarding, P2P and notifications and reject
former currencies through the gateway. Native clients retain exact integer parsing
and payment recovery tests; iOS changes are limited to currency compatibility while
feature development remains paused.
