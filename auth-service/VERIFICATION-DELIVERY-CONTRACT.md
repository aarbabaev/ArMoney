# Registration verification delivery, revision 1

Status: disabled implementation slice; local QA and source review complete; published-head CI and linked acceptance pending.
Auth owns the encrypted intent and challenge. Delivery acceptance opens an OTP
challenge only through the existing fenced database acknowledgement; it never
creates a receipt, identity, projection acknowledgement or ACTIVE eligibility.

## Configuration and lifecycle

`AUTH_VERIFICATION_DELIVERY_ENABLED` defaults false. Disabled construction creates
no worker, HTTP client or SMTP transport and requires no delivery credentials.
This clean slice does not register verification or proof routes and does not add
login notices, a verification bridge or eligibility enforcement. Absent routes
remain 404; there is no installed proof-write endpoint returning 503.
Do not activate this slice before disposable integration and security acceptance.

Enabled delivery requires `VERIFICATION_KEY_ID`, independent canonical-base64
32-byte `VERIFICATION_OTP_HMAC_KEY` and `VERIFICATION_DELIVERY_ENCRYPTION_KEY`,
`VERIFICATION_SMS_SEND_TOKEN`, and existing `MAILTRAP_SMTP_USERNAME`,
`MAILTRAP_SMTP_PASSWORD`, `MAILTRAP_FROM_EMAIL` (optional from-name).
Only current verification keys are loaded; rotation with outstanding encrypted
intents remains a deployment gate requiring a separately frozen key-ring policy.

SMTP reuses the existing sandbox transport and validation with a private copy of
configuration; it does not start a login-notice worker. Host must remain
exactly `sandbox.smtp.mailtrap.io`, mode `sandbox`, port 25, 587 or 2525. Mandatory
authenticated STARTTLS, hostname/certificate validation and the existing 15-second
total deadline remain unchanged. No API or live sending mode is introduced.

`VERIFICATION_SMS_BASE_URL` defaults to `http://sms-sandbox:8080`. Only that exact
private service origin (optional trailing slash) is accepted; no user information,
query, fragment or other path/host/port/scheme. Loopback fixtures have a package-
private constructor seam, never an environment bypass. Sender credentials are
32..128 URL-safe ASCII characters and must differ from configured reader and
business/bridge/status/proof credentials. Compose networking and secret mappings
remain orchestrator-owned and are not provided by this service slice.

Main starts the worker after HTTP startup, closes it before database shutdown,
and closes created resources after startup failure. One serial intent is processed
per tick, two seconds after the previous tick completes. A claim transaction
commits before decrypt/send; the finish transaction follows external I/O. Expired
claims are not decrypted or sent. The current store supplies a 30-second fenced
lease, at most three attempts and 5/15-second backoff. Retry uses the same delivery
UUID and encrypted candidate/purpose/version; no new code or expiry is created.
Interruption preserves the uncertain lease and restores the interrupt flag.
All other nonaccepted or uncertain sends finish false or recover through expiry
if persistence fails. The database alone decides whether an acknowledgement is
still current; stale workers cannot open superseded challenges.

## SMS request and acknowledgement

Send only `POST /api/messages`, with `Authorization: Bearer <sender credential>`,
`Idempotency-Key: <delivery UUID>`, and JSON exactly `destination`, `text`, `purpose`.
The destination is the decrypted canonical candidate; purpose is
`REGISTRATION_PHONE`. A reusable HTTP client refuses redirects, has a two-second
connect deadline and four-second total request/body deadline.

Only 201 with `replay:false` or 200 with `replay:true` acknowledges acceptance.
Require application/json and a strict object of at most 4096 bytes with exactly
`id` (full hyphenated UUID), `createdAt` (nonnegative signed-64-bit epoch
milliseconds), and `replay` (literal boolean). Duplicate/unknown/missing/wrongly
typed fields, trailing data, redirects, all other statuses, timeout and ambiguous
responses are nonaccepted. In particular 409/410/429/507 never become success.
No inbox reads, clears or automatic key rotation occur. SMS sandbox acceptance
is synthetic capture, not real carrier delivery or proof of possession.

## Confidentiality and evidence limits

No payload, recipient, OTP, provider exception or credential is logged. Decryption
uses existing authenticated metadata and erases its temporary plaintext bytes;
HTTP request/response byte arrays are cleared where owned. Java strings and HTTP
implementation copies cannot be reliably erased; no string-erasure guarantee is
claimed. Email/SMS content repeats the original code on retry. A message can arrive
after code expiry/supersession; the database rejects its consumption. SMTP response
loss may duplicate messages without extending code validity or verifying ownership.

Worker/configuration/loopback HTTP specifications exercise ordering, expiry,
corruption, retries, interruptions, defaults, dedicated keys, sandbox destinations,
strict acknowledgements, redirects and body deadlines. These synthetic fixtures
do not establish PostgreSQL fencing or SMTP TLS behavior. Existing isolated
verification PostgreSQL and SMTP suites, focused new tests, required affected-component checks and linked SSO acceptance,
disposable Compose acceptance and independent security retesting are required on
the integrated revision. No build or live provider evidence is implied by source.

The following authority and migration-lineage statements describe this historical
clean delivery slice, not the later integrated Main composition. See the current
[architecture map](../ARCHITECTURE.md#managed-banking-consumer-and-legacy-migration-integration)
for default-off production wiring and scoped acceptance.

The registration, session and ACTIVE-identity authority remains unchanged. No
verification routes are registered by Main, no new provider is connected, and no
runtime activation or deployment is included. Projection, legacy identity
migration, provider contact observation and all-entry banking enforcement remain
outside this slice. Never infer verified ownership or ACTIVE eligibility from
delivery acceptance.

## Migration lineage and source evidence

The clean slice adds unpublished `V6__registration_verification.sql`. Its bytes
match the original dirty worktree's unpublished `V7__registration_verification.sql`
(SHA-256 `808ab95a0d986c15abe3f50c78173c14ad648c3173af6081a7fefc79887f46eb`).
The original dirty V6/V7/V8 lineage is untouched; this is not a renumbering of an
applied or published migration. A bounded audit of 38 advertised heads and their
history found no published V6–V8; it does not establish absence from every clone
or database. Deployment must independently verify target Flyway history.

Historical 71/51/6/7 test counts belong only to the original dirty slice, including
proof-related scope, and are not acceptance evidence for this clean adaptation.
Clean-revision evidence totals 212 unit and 56 distinct PostgreSQL integration tests, combining the initial run and focused rerun after two fixture-only closure corrections. No single successful full-check rerun is claimed. Independent source review is clear; published-head CI and disposable linked acceptance remain pending. The original
untracked Store preimage was not retained: the recorded current `61F78` full-source
review checkpoint and prior six PostgreSQL cases do not establish a minimal diff
against that missing preimage. Review the complete adapted Store and its fences;
do not claim omitted proof security tests were included here.
