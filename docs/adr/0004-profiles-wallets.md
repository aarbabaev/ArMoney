# ADR 0004 — Profiles and wallet metadata

Status: Accepted for local M1

Current-status note: The metadata-only scope below was extended by [ADR 0007](0007-wallet-ledger-provisioning.md): wallets now provision ledger accounts and return 202 while pending, or 200 when ready/already closed.

Gateway revalidates the opaque session using auth /me on every protected request.
It forwards only its internal key, a verified X-Identity-Id and an allowlisted JSON
body to user/wallet. Caller-supplied identity/service headers are discarded.
Internal services require both the service key and a canonical identity UUID.
No auth response is cached. Auth outages/malformed identity fail closed with 503;
revoked or missing sessions return 401 before any downstream write.

This uses the existing shared local-development key. It is not a production trust
boundary against a compromised peer; per-service credentials or mTLS/workload
identity are required before remote deployment. Protected requests already
authorized may finish concurrently with logout; revocation affects subsequent
authorization checks, not in-flight operations.

User profile is explicitly created/updated using PUT /v1/users/me. Repeated calls
retain the profile ID. Concurrent updates use last committed write wins.
Registration does not provision a profile automatically.

Wallet owner_id means auth identity UUID, not profile ID. POST /v1/wallets returns
200 for both new and existing metadata. The unique (owner_id, currency) constraint
and INSERT ON CONFLICT plus a fresh READ COMMITTED SELECT make retries and racing
creation converge on one ID. No arbitrary idempotency key is necessary for this
naturally unique resource. A retry does not reopen CLOSED wallets.
Only EUR, USD and GBP are supported in this first slice; V2 enforces that set.
Profile V2 disallows blank names. Existing V1 migrations remain unchanged.

GET /v1/wallets filters by verified owner. No endpoint accepts owner_id/identity_id
in its body or exposes arbitrary user lookups. Extra JSON fields are rejected.
Profile and wallet provisioning are independent, explicit calls. No cross-service
transaction or profile prerequisite is implied. No service queries another DB.

Wallet ACTIVE denotes metadata status only. No balance is returned, no money
moves, and no ledger account is provisioned yet. Future ledger provisioning must
establish that boundary before payment execution. Currency validation currently
lives in wallet domain with parameterized jOOQ adapters; schema code generation
can be introduced when the financial queries need a richer generated model.

Tests cover PostgreSQL persistence/migrations, concurrent creation, retry of closed
wallets, profile upsert, input rejection and owner isolation. Gateway tests and
Compose acceptance cover forged headers, missing/revoked sessions and auth outage.
