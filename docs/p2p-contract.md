# P2P implementation contract

Status: implemented source contract; not a deployment or completion claim. No external provider, SMS, push service or broker is introduced.

## Identity and phone numbers

Phone numbers use canonical E.164 syntax `+[1-9][0-9]{7,14}`; reject ambiguous national numbers. They are not credentials. GET/PUT /v1/users/me retains display_name behavior and adds nullable phone_number and boolean phone_verified. PUT /v1/users/me/phone accepts exactly {phone_number}; changing a phone clears verification. Repeating the same number preserves its state. Verified numbers are unique. No self-service verification API exists. An explicit local operator CLI verifies an exact expected pending number for an identity after out-of-band ownership checking; no claim of SMS verification. It must refuse stale/duplicate assignments and record an audit event. No automated verification or live user seeding.

POST /v1/recipients/resolve accepts {phone_number}, gateway maps to POST /v1/users/resolve-phone. Requires authenticated session and service-key internally. Only verified phones resolve; 404 otherwise, 429 on a bounded requester lookup limit. Result {identity_id,display_name,phone_number}. No enumeration endpoint, emails, profile IDs or wallet lists. Caller sees a recipient confirmation before payment. This knowingly discloses a verified recipient's display name to an authenticated exact-phone lookup.

## Wallet and balance contracts

Existing GET/POST /v1/wallets unchanged. GET /v1/wallets/{id}/balance is owner-only: {wallet_id,currency,balance_minor}, integer signed64; 404 missing/foreign, 409 not ACTIVE/READY, 503 unavailable/malformed ledger. Read ledger account via existing private API and validate owner/wallet/currency/id. Never substitute zero on failure.

Private GET /v1/internal/wallets/{id} and GET /v1/internal/wallets/by-owner/{owner}/currency/{currency} return existing wallet shape (id,owner_id,currency,status,provisioning_status,ledger_account_id). Both require service key and trusted identity header. Never route them through gateway. Payment validates source owner, recipient owner, ACTIVE/READY and equal currency. No public close route in this slice; administrative lifecycle changes do not create a distributed lock with ledger.

## Payments

POST /v1/payments, authenticated, requires Idempotency-Key (1..128 ASCII letters/digits/-/_). Exact JSON: {source_wallet_id,recipient_id,recipient_phone,currency,amount_minor}. Canonical UUIDs; supported AED; positive signed64 integer minor amount; no self-transfer. recipient_id must match current verified phone resolution when creating a new payment, preventing substitution between confirmation and submission. Source wallet must belong to requester. Destination is that recipient's existing ACTIVE/READY wallet of the same currency; never auto-fund or auto-create it.

Lookup existing requester/key BEFORE remote validation; compare canonical payload hash including recipient_id and phone. Stable payment UUID is ledger payment_id. Same payload returns durable status (202 PENDING, 200 terminal); changed payload 409 idempotency_conflict. Before durable acceptance: invalid input400, missing recipient/wallet404, ineligible409, remote unavailable503. Persist resolved identities, wallet/account mappings and request snapshot before ledger calls. No remote calls inside DB transactions. Concurrent creation compares original payload and never changes mappings.

Worker uses durable leases/fencing, bounded HTTP body/timeouts, bounded retry backoff and stable command IDs; expiry/crash safely reclaims work. Only ledger POSTED gives COMPLETED; matching recognized durable ledger rejection gives REJECTED. Network errors/unknown/invalid responses remain PENDING. Validate complete ledger response against stored payment, debit/credit accounts,currency,amount. Never mark terminal on timeout or create replacement payment IDs. Legacy scaffold rows with no resolved mappings must fail closed and remain unprocessed; no invented mappings.

Payment JSON: {id,requester_id,recipient_id,source_wallet_id,destination_wallet_id,recipient_phone,currency,amount_minor,status,rejection_reason,created_at,updated_at}. rejection_reason nullable. GET /v1/payments/{id}: requester or completed recipient only, otherwise404. GET /v1/payments returns {payments:[...]} newest100 visible rows (created_at,id descending), explicitly bounded recent history, no pagination promise. Recipient sees only completed incoming payments.

## In-app notifications

Payment-owned durable notifications, created atomically with terminal payment transition. Sender gets PAYMENT_COMPLETED or PAYMENT_REJECTED; recipient gets PAYMENT_RECEIVED only for COMPLETED. Unique(owner_id,payment_id,type) prevents retry duplicates. No notifications imply independent financial authority.

GET /v1/notifications returns {notifications:[...]} newest100 for authenticated owner. Item {id,payment_id,type,currency,amount_minor,created_at,read_at}; read_at nullable. POST /v1/notifications/{id}/read, empty body, returns updated item200; repeated read preserves read_at; missing/foreign404. App fetches on open/foreground/manual refresh; no OS push or guaranteed background delivery.

## Native UI and safety

SwiftUI tabs Wallets, Transfers, Notifications, Profile. Real balances, create-wallet, profile display-name edit and phone status, account identity/sign-out, exact-phone lookup and recipient confirmation, transfer amount in decimal display converted exactly to Int64 minor units (2 digits for supported currencies). Persist pending submission's idempotency key/payload in device-only Keychain scoped to origin AND identity; retries after uncertain response/relaunch reuse it. Disable duplicate submits; do not silently discard uncertain transfers or reuse key for edited payload. Pending vs completed/rejected must be explicit. No preview funds or public top-up endpoint. Existing PKCE/session security preserved.

## Verification

Real PostgreSQL tests for phone uniqueness/stale verification, ownership, idempotency concurrency, terminal notification atomicity and durable claims. Disposable CI verifies balanced synthetic funding, successful cross-user transfer once, insufficient funds, payload conflict, ownership spoof, ledger outage/restart recovery, notification isolation/read idempotency and balance reconciliation. Never seed/fund/clear the persistent user stack. Independent security/financial review required; iOS runs on macOS CI. Published migrations remain append-only. All PRs target main; no automatic merge.
