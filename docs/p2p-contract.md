# P2P implementation contract

Status: implemented source contract; not a deployment or completion claim. Optional Mailtrap transactional email is configured separately; no SMS, push service or broker is introduced.

## Identity and phone numbers

New registration and recipient input uses canonical UAE mobile syntax `+9715[024568][0-9]{7}`. No other country, national format or country selector is supported. Auth reserves the phone uniquely across credential modes; Keycloak requires it as the username. Registration is a claim, not ownership proof. See [ADR 0014](adr/0014-uae-registration-phone.md) for bank enrollment completion, retries and historical migration limits.

GET/PUT /v1/users/me retains display-name behavior, nullable phone_number and boolean phone_verified. A new profile copies the immutable registration claim. PUT /v1/users/me/phone accepts only the identical registered number; a change or missing enrollment returns 409. There is no self-service phone change or verification endpoint. Auth's private idempotent claim endpoint is never forwarded by the gateway.

POST /v1/recipients/resolve accepts {phone_number}; the gateway maps it to POST /v1/users/resolve-phone. Registered claims with a matching profile resolve regardless of phone_verified. No SMS is sent and no verification is fabricated. Missing profile/claim returns 404; a bounded requester lookup limit returns 429. Result {identity_id,display_name,phone_number} exposes no email, profile ID or wallet list. The caller must confirm the recipient name before payment. A claimed number can belong to someone else: this limitation is explicitly accepted for the current policy.

## Wallet and balance contracts

Existing GET/POST /v1/wallets unchanged. GET /v1/wallets/{id}/balance is owner-only: {wallet_id,currency,balance_minor}, integer signed64; 404 missing/foreign, 409 not ACTIVE/READY, 503 unavailable/malformed ledger. Read ledger account via existing private API and validate owner/wallet/currency/id. Never substitute zero on failure.

Private GET /v1/internal/wallets/{id} and GET /v1/internal/wallets/by-owner/{owner}/currency/{currency} return existing wallet shape (id,owner_id,currency,status,provisioning_status,ledger_account_id). Both require service key and trusted identity header. Never route them through gateway. Payment validates source owner, recipient owner, ACTIVE/READY and equal currency. No public close route in this slice; administrative lifecycle changes do not create a distributed lock with ledger.

## Payments

POST /v1/payments, authenticated, requires Idempotency-Key (1..128 ASCII letters/digits/-/_). Exact JSON: {source_wallet_id,recipient_id,recipient_phone,currency,amount_minor}. Canonical UUIDs; supported AED; positive signed64 integer minor amount; no self-transfer. recipient_id must match current registered UAE phone resolution when creating a new payment, preventing substitution between confirmation and submission. Source wallet must belong to requester. Destination is that recipient's existing ACTIVE/READY wallet of the same currency; never auto-fund or auto-create it.

New commands require a UAE recipient; historical accepted commands retain read/replay support. Lookup existing requester/key BEFORE new-phone admission and remote validation; compare canonical payload hash including recipient_id and phone. Stable payment UUID is ledger payment_id. Same payload returns durable status (202 PENDING, 200 terminal); changed payload 409 idempotency_conflict. Before durable acceptance: invalid input400, missing recipient/wallet404, ineligible409, remote unavailable503. Persist resolved identities, wallet/account mappings and request snapshot before ledger calls. No remote calls inside DB transactions. Concurrent creation compares original payload and never changes mappings.

Worker uses durable leases/fencing, bounded HTTP body/timeouts, bounded retry backoff and stable command IDs; expiry/crash safely reclaims work. Only ledger POSTED gives COMPLETED; matching recognized durable ledger rejection gives REJECTED. Network errors/unknown/invalid responses remain PENDING. Validate complete ledger response against stored payment, debit/credit accounts,currency,amount. Never mark terminal on timeout or create replacement payment IDs. Legacy scaffold rows with no resolved mappings must fail closed and remain unprocessed; no invented mappings.

Payment JSON: {id,requester_id,recipient_id,source_wallet_id,destination_wallet_id,recipient_phone,currency,amount_minor,status,rejection_reason,created_at,updated_at}. rejection_reason nullable. GET /v1/payments/{id}: requester or completed recipient only, otherwise404. GET /v1/payments returns {payments:[...]} newest100 visible rows (created_at,id descending), explicitly bounded recent history, no pagination promise. Recipient sees only completed incoming payments.

## In-app notifications

Payment-owned durable notifications, created atomically with terminal payment transition. Sender gets PAYMENT_COMPLETED or PAYMENT_REJECTED; recipient gets PAYMENT_RECEIVED only for COMPLETED. Unique(owner_id,payment_id,type) prevents retry duplicates. No notifications imply independent financial authority.

GET /v1/notifications returns {notifications:[...]} newest100 for authenticated owner. Item {id,payment_id,type,currency,amount_minor,created_at,read_at}; read_at nullable. POST /v1/notifications/{id}/read, empty body, returns updated item200; repeated read preserves read_at; missing/foreign404. App fetches on open/foreground/manual refresh; no OS push or guaranteed background delivery.

## Native UI and safety

SwiftUI tabs Wallets, Transfers, Notifications, Profile. Real balances, create-wallet, profile display-name edit and phone status, account identity/sign-out, exact-phone lookup and recipient confirmation, transfer amount in decimal display converted exactly to Int64 minor units (100 fils = 1 AED; exactly two decimal places). Persist pending submission's idempotency key/payload in device-only Keychain scoped to origin AND identity; retries after uncertain response/relaunch reuse it. Disable duplicate submits; do not silently discard uncertain transfers or reuse key for edited payload. Pending vs completed/rejected must be explicit. No preview funds or public top-up endpoint. Existing PKCE/session security preserved.

## Verification

Real PostgreSQL tests for registration uniqueness/immutable claims, ownership, idempotency concurrency, terminal notification atomicity and durable claims. Disposable CI verifies balanced synthetic funding, successful cross-user transfer once, insufficient funds, payload conflict, ownership spoof, ledger outage/restart recovery, notification isolation/read idempotency and balance reconciliation. Never seed/fund/clear the persistent user stack. Independent security/financial review required; iOS runs on macOS CI. Published migrations remain append-only. All PRs target main; no automatic merge.

Terminal outcomes also enqueue durable email events in the same transaction as in-app notifications. Delivery is disabled by default and does not change the public payment result. See [email delivery contract and limitations](email-notifications.md).
