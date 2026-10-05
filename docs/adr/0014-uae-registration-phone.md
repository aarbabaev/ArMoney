# ADR 0014: Mandatory unique UAE registration phone

Status: accepted

## Decision

New ArMoney registrations require a canonical UAE mobile number matching
`^\+9715[024568][0-9]{7}$`. Clients use the fixed country code `+971`; there is
no country selector. National formats, spaces, other countries and unsupported
prefixes are rejected by the server. The mobile prefixes follow the
[TDRA numbering guidance](https://tdra.gov.ae/en/consumer-tool-hub/topics/porting-numbers).

Auth owns an immutable, database-unique registration phone across password and
SSO bank identities. Keycloak uses that number as its username, so its registration
form also requires and uniquely constrains the number. The provider subject,
not phone or email, remains the SSO identity key. Matching phone numbers never
link accounts or overwrite another account's credentials.

Keycloak registration and bank enrollment are separate transactions. Bank enrollment
finishes at the token exchange after auth reserves the phone and user-service
acknowledges its durable binding. A Keycloak account whose phone conflicts with a
legacy bank identity can exist, but cannot obtain a new bank identity/session.
This is not a distributed transaction or a shared database between the services.

Auth commits its identity and phone reservation together, then calls the private
idempotent `PUT /internal/registrations/me` with the trusted identity header and
`{phone_number}`. User-service acknowledges the identical durable binding with 204,
rejects a different owner/phone with 409 and exposes no such public gateway route.
No new session is issued before acknowledgment. A timeout leaves a recoverable
identity reservation; login/token-exchange retries reuse it. Do not compensate by
deleting an identity after an uncertain response.

User-service stores a registration claim separately from the optional display-name
profile. Creating the profile copies the registered phone into the primary directory;
registration does not invent a display name or pin a shard. The central directory
and claims each enforce phone uniqueness, and their write paths serialize collision
checks. Optional profile shards retain display names and immutable placement;
phone state is overlaid from the primary directory. No transaction spans shard I/O. The existing public phone endpoint accepts
only an identical registered value; self-service changes are unavailable.

## Ownership and transfers

No SMS provider or ownership verification is added. At the owner's request,
registered phone claims with a profile can resolve for transfers even while
`phone_verified=false`. This flag describes actual ownership attestation, not
registration completeness. Never fabricate a verification audit event. This
supersedes ADR 0009's verified-only recipient policy.

A person can claim a number they do not own. Uniqueness does not prove ownership.
The number is not a password-reset or account-linking credential. Exact authenticated
lookup remains quota-limited; the sender must review the recipient name. Existing
wallet ownership, AED-only money, immutable payment mappings and ledger guarantees
are unchanged. New transfers reject non-UAE numbers; historical accepted commands
keep their original payload, read and idempotent replay semantics.

## Existing records and rollout

Published main migrations are unchanged; new constraints and claim records are additive.
User-service V5 follows the existing V4 profile-shard directory. The original draft
V4 registration migration was renumbered before merge/deployment to avoid a version collision.
Do not clear users, rewrite phones or choose a winner among duplicate historical
profile numbers. The new global directory index intentionally fails migration when
duplicates exist. Resolve such conflicts explicitly before rollout, retaining
identity IDs and verification evidence. Stop old phone-writing service versions
before enabling the new claim writer.

Historical auth identities with no registration phone retain existing login/session
compatibility. They do not become new phone-directory entries automatically. Existing
profile numbers without a registration claim are not discoverable. Enrolling those
accounts needs an operator migration that reconciles auth, profile and provider
records; no automated historical enrollment or self-service phone replacement is
included. Existing profile data and historical payments stay readable.

Realm import only initializes a new Keycloak realm. Apply the maintained SSO update
procedure to existing realms; never delete its volume to refresh configuration.
Old non-phone usernames must be tested for unchanged login compatibility.

## Verification

Cover missing/foreign/malformed numbers, concurrent duplicate reservations across
credential modes, immutable binding, collision rollback, lost provisioning responses
and unavailable dependencies without session creation. Test claimed-but-unverified
recipient discovery, private route isolation, duplicate legacy profile migration,
and historical payment replay. Disposable Compose/browser CI validates the real
registration form, both native client token claims and a full AED transfer. Existing
database and financial tests remain mandatory. No live data is seeded or reset.
