# ADR 0009: Phone recipients, durable P2P and in-app notifications

Status: accepted implementation decision; deployment and exact-revision checks are separate evidence.

## Decision

User-service owns an exact E.164 directory. A profile may store a pending phone,
but only operator-attested numbers resolve. Changing a number clears its attestation; same-number retries preserve it;
a unique partial index prevents two verified owners. Lookup has a persistent limit
of 30 attempts per requester per 60 seconds, including misses. The result exposes
the matched display name and identity, never email or a directory of users.

No SMS provider or other external service is connected. Operator attestation is
an explicit administrative assertion of an out-of-band ownership check, not an
automated proof. An operator with database/container access is trusted.

Payment-service persists requester-scoped idempotency keys and canonical payload
hashes. Existing keys are checked before mutable dependencies. New commands check
the confirmed recipient identity against the phone directory and resolve both
ACTIVE/READY wallets. They snapshot identities, wallet/account IDs, currency and
integer minor units before asynchronous posting. The ledger payment ID is stable.

A fenced durable 30-second lease and bounded retries (up to 60-second backoff)
recover outages and process restarts. No database transaction spans HTTP. Only a
full matching ledger result completes or rejects a payment; unknown outcomes remain
PENDING. Ledger remains the sole authority for balances and immutable postings.
This provides idempotent effects, not exactly-once HTTP delivery. Later phone
changes do not redirect an accepted payment. Legacy scaffold rows without mappings
are not executed or exposed.

Terminal state and owner-scoped notifications commit in one payment transaction.
Unique owner/payment/type constraints deduplicate retries. The sender sees completion
or rejection, and the recipient sees successful incoming transfers. History and
inbox return the latest 100 records. Read acknowledgements are owner-scoped and
idempotent. Native iOS refreshes on foreground or manual request; no OS push is used.

The iOS client stores an uncertain immutable command and key in device-only Keychain,
scoped by origin and authenticated identity, before submission. A retry reuses both.
Logout does not erase uncertain financial intent. Amount parsing uses checked Int64
minor units, never floating point.

## Local operator procedure

First create the profile and save its phone through the authenticated app/API.
Confirm the person's ownership outside this system using an approved local process.
Record non-secret operator and evidence references; do not paste personal evidence
or tokens into command lines. From the repository using the same Compose file set
as the running deployment, execute this single-line command:

```sh
docker compose exec -T user-service java -cp '/opt/service/lib/*' com.arman.bank.userservice.VerifyPhoneMain IDENTITY_UUID EXPECTED_E164_PHONE OPERATOR_REFERENCE EVIDENCE_REFERENCE --confirm-out-of-band
```

The CLI inherits the service's existing database environment. Operator references
allow `[A-Za-z0-9._-]{1,100}` and evidence references `[A-Za-z0-9._:/-]{1,200}`.
Stale, already-attested or conflicting assignments are refused. Verification and
the audit event commit atomically. There is no public verification route.

## Consequences and verification

Gateway exposes only explicitly registered public routes; private wallet lookup
and ledger routes remain internal. The shared service key trusts peer services;
it does not protect against a compromised peer. Runtime database-role hardening,
automated ownership verification, pagination and funding are future decisions.
No new external service may be introduced without owner approval.

PostgreSQL tests cover directory concurrency, stale verification, quotas, idempotency,
leases, terminal rollback and notification isolation. Disposable Compose acceptance
adds balanced synthetic funding, concurrent spending, duplicate requests, ledger
outage, and a crash after ledger commit before local acknowledgement. These fixtures
must never run against persistent user data. macOS CI builds/tests the native client;
physical-device acceptance remains separate.

Container restart acceptance exposed stale JVM DNS routing after Docker reused
a stopped service IP for another service. A container-only additional Java security
properties file disables positive/negative/stale DNS caching before clients start.
The complete JDK security configuration remains intact; the Compose resolver is
the trusted source of internal service addresses. Strict payment lookup checks
remain in place after restart.
