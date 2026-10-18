# ADR 0048: Passwordless migration contact binding

Status: design accepted; implementation and integrated acceptance in progress.

## Context

Historical SSO identities have no password hash. The published credentials-pair
constraint also prevents storing an email in that credential row. Requiring an
existing credential email therefore excludes those owners from migration. A
provider email claim does not establish possession or justify contact linkage.

## Decision

Preserve the credential constraint and existing identity UUID. After fresh proof
of the exact existing SSO association, require an explicit email candidate through
the restricted account capability. The browser submission is POST
`/migration/legacy/email` with exactly `csrf`, `command_id` and `email`; the genuine
producer authenticates its purpose, original cookie, command and deadline.
Candidate submission alone grants no contact or banking authority.

User U2 retains a positive candidate-domain email version and digest as opaque,
immutable Auth attestations. They do not assert possession. Publication must bind
actual consumed EMAIL receipt evidence to the same original candidate. Verified
owner-contact generation is a separate Auth namespace and never replaces the
original User stage fields or refreshes an ACK. The absent-phone projection triple
is unchanged; email binding fields never use zero or null as an absence sentinel.

Only genuine EMAIL consumption may atomically create the separate owner contact
and its receipt-authorized generation transition in the same Auth transaction.
Use a versioned, domain-separated binding with one canonical UTC microsecond
Instant codec for new fields. Existing persisted version-one hashes are unchanged.
Replay must retain original evidence and expiry, and cannot resurrect a candidate
or authorize an unrelated contact generation.

## Consequences and limits

No dummy password, provider-email copy, contact-based identity lookup, credential
constraint relaxation or automatic recovery is permitted. Preserve UUIDs,
wallet/account ownership, balance and history. Old sessions are revoked only after
successful final cutover, not candidate submission or OTP delivery.

The seventh browser route and contact persistence are under integration. Main
composition, final publication/recovery, banking-session handoff, real provider
acceptance and deployment remain separate gates. Default-off configuration and
edge rejection remain in force until the complete flow passes independent review
and isolated acceptance. Source components or component tests do not establish
feature readiness.
