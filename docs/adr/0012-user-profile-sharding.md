# ADR 0012: Immutable email-prefix placement for user profiles

Status: accepted

## Context

New user profiles may be placed on additional PostgreSQL databases without
moving existing profiles. A two-character email prefix is a demonstration rule,
not a guarantee of even data volume or request load. Auth owns credentials and
email uniqueness; user-service owns profiles. Existing identity and profile UUIDs
must remain compatible with sessions, wallets, payments and native clients.

## Decision

At the first profile PUT, the gateway obtains email from its existing validated
auth response and forwards it in the private X-Identity-Email header. Client
headers cannot select placement. User-service normalizes the email and selects
a configured shard from its first two characters, with an explicit default for
unmapped prefixes and identities without email (including SSO-only identities).
Mapping keys are two lowercase ASCII letters/digits. Other valid local-part
prefixes (including punctuation or a one-character local part) use the default.
Account registration itself does not create a profile or reserve a shard.

A durable directory in the original user-db pins identity UUID, profile UUID and
shard before writing profile data. Published profiles are backfilled as primary;
their data is not moved. Placement and profile IDs cannot change. Future prefix
map edits affect only identities without a directory entry. Email changes never
move a profile. Missing or unavailable assigned shards fail closed, with no
fallback writes elsewhere.

Directory reservation and shard write are separate transactions. A pending
reservation is not a successfully created profile. Retries reuse the original
profile UUID and shard and perform an idempotent upsert; confirmation marks the
directory initialized. No SQL transaction spans remote database I/O. There is
no distributed atomic commit or background provisioning worker in this slice.

Global pending/verified phone state, unique verified numbers, operator audit and
lookup quotas remain in the primary directory database. Profile display names
are read from their assigned database. Credentials, sessions and financial
databases retain their existing ownership and placement.

## Operations and limitations

Sharding is opt-in through compose.users-sharding.yaml. Base Compose keeps the
existing user-db and volume names. Retain every database referenced by pinned
directory entries, including after changing the prefix map. All configured pools
participate in readiness and shutdown. Database migrations are append-only.

The directory remains a single dependency. Prefixes can be highly skewed and do
not solve hotspots among existing users. A shared internal service key remains
the development trust boundary. SSO-only principals without email use the default
shard; this implementation does not add provider email synchronization. Operators
must use the updated phone verifier; legacy direct SQL or old service binaries
must not write phone state after the directory migration. There is no automatic
profile relocation or shard retirement.

Configuration uses `USER_SHARD_IDS=s1,s2`,
`USER_SHARD_S1_DB_URL`, `USER_SHARD_S1_DB_USER`, `USER_SHARD_S1_DB_PASSWORD`
(and corresponding settings for each shard), `USER_SHARD_PREFIX_MAP=al:s1,bo:s2`
and `USER_SHARD_DEFAULT=primary`. Shard IDs are lowercase letters/digits/underscore
up to 32 characters; environment keys uppercase the ID. `primary` is reserved
for `DB_URL`/`DB_USER`/`DB_PASSWORD`. Duplicate or unknown mappings fail startup.
Unset shard settings retain single-database behavior. Secrets belong in runtime
environment configuration and must never be committed.

Real PostgreSQL tests and disposable Compose acceptance must verify placement,
upgrades, concurrent retries, outages, owner isolation and global phone behavior.
Running source implementation is not proof of deployment or passing CI.
