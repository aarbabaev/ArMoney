# ADR 0046: Separate managed banking session consumer

Status: accepted design; implementation and runtime acceptance in progress.

## Context

Code confirmation and durable eligibility evidence do not themselves authorize a
banking session. Generic Auth persistence deliberately rejects permanently managed
principals. Removing that check would bypass proof, contact and projection checks.
Existing-user migration also requires a separate contract; this decision does not
implement migration or authorize feature activation.

## Decision

Introduce a separate default-off Auth consumer for explicitly configured, disjoint
managed issuer/client pairs. Preserve generic managed-principal denial in every
configuration mode. Missing proof metadata, unknown pairs and absent markers never
select a legacy fallback or allocate another identity.

When the consumer is enabled, new banking admission is limited to its managed
path. Public local registration/password login and explicit legacy SSO pairs
cannot create or provision an identity or mint a bank session. Legacy pairs may
still identify restricted migration account proofs; such OIDC tokens are not
banking credentials. Default-off compatibility is distinct from this cutover.
Restricted migration password proof and already stored live sessions remain
available under their existing checks until successful atomic FINAL.

Initial admission accepts a fresh authenticated INTERACTIVE event with exact
issuer, subject, client and provider-session binding and bounded token issuance and
expiry. REUSE and refreshed tokens cannot start another banking session without a
separate producer correlation contract. Notice flags do not authorize access.

Resolve the authoritative successful confirmation result, original proof/event and
permanent identity. Require current independent contact receipts and consumed
challenges, contact versions, association, eligibility epoch, matching grant,
projection revision and every persisted User acknowledgement field. Do not select
the latest historical proof or call remote profile provisioning during admission.

Use the existing strict twelve-field code-session observer outside transactions.
Keep one primary connection and the request-owned admission capability. Preserve
the original DB start anchor, four-second receive bound and existing relative
provider lease calculation; never resample or compare provider wall time to DB
wall time. Revalidate the original authority stamp and all local evidence under
verification, projection and proof controls before business locks. Catalog locks
precede authority reconstruction. Recheck strict DB-clock deadlines after effects
and immediately before commit on positive and negative paths.

Atomically insert only the opaque session digest, immutable managed binding,
one-use event consumption and a domain-separated keyed access-token fingerprint.
Event uniqueness is by issuer/event independently of token rotation. Persist
original key IDs and retain keys and replay tombstones while referenced. Bounded
capacity or unresolved recovery denies allocation; cleanup cannot restore replay
eligibility. Lost responses or uncertain commit acknowledgements return no token
and require a genuinely new authorization event.

Every managed-session validation checks current local versions/epoch/ACK and fresh
authenticated provider facts. No positive authorization cache is introduced.
Session expiry cannot exceed token expiry, applicable local evidence deadlines or
the existing 30-minute maximum. Each admission or validation operation must finish
within its own fresh provider observation lease; that short operation lease is not
a cached authorization lifetime. Unavailable or malformed observations deny without invalidating
state. A fresh authenticated negative observation may commit monotonic invalidation
only against its original stamp and deadlines; return denial outside the transaction
so an exception does not roll back that invalidation.

## Existing users and activation

Existing users must verify email and phone on their next login while retaining
their identity UUID, wallet ownership and balance. Old sessions are revoked only
after successful migration. Pending migration must not install the permanent marker,
which would prematurely invalidate those sessions. Contact equality is never account
linkage. Migration routing, dual proofs and cross-service cutover remain a separate
design and acceptance gate.

The consumer remains disabled until implementation, populated upgrades, independent
security and real PostgreSQL race/recovery tests, actual provider/native confirmation,
projection and deployment acceptance pass. Clock assumptions and physical network
cancellation limits remain explicit; this design does not claim distributed atomic
revocation or successful Mailtrap delivery.

## Consequences

Managed sessions require dedicated persistence and validation, rather than reusing
legacy session creation. Tests must cover binding and ACK-field mismatches, expiry
behind locks, contact/epoch changes, concurrent exchanges and invalidation, replay,
uncertain commits, logout, unavailable observers and unchanged legacy ownership.
