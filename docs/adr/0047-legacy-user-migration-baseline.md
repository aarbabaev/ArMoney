# ADR 0047: Durable User baseline for legacy migration

Status: User V7 baseline and default-off Auth migration composition are implemented in source; complete runtime acceptance and deployment readiness remain pending. Feature activation is not authorized by this decision.

## Context

Existing owners must keep their identity and profile UUIDs, immutable shard placement, local credentials, wallets, account ownership, balances and history when moving to a managed login. Contact equality cannot select or link an owner. A previously unversioned legacy profile also cannot be treated as historical verified projection version 1.

The reviewed design inputs are legacy dual-proof contract Revision 4.2 (SHA256 `3b9a1711c89303558c4da67a1911aa4d8ae807c4348ced5a88e5a3f21bb086c5`) and its User U2 contract (SHA256 `adc5d85bc0be5afee68f86c65160bdb7be4def1b8e399e571616124c9f10b5f7`). These are design inputs; this ADR records the User boundary, while later Auth decisions and the migration guide describe the integrated composition. The separate [managed banking consumer](0046-managed-banking-session-consumer.md) does not itself implement legacy migration.

## Decision

User owns a dedicated central migration baseline and publication record through append-only [V7](../../user-service/src/main/resources/db/migration/V7__legacy_migration_baselines.sql). It does not query Auth, wallet or ledger databases. Auth decisions and immutable receipt snapshots arrive through authenticated HTTP ports to a fixed configured Auth PRIMARY origin.

Stage validates an existing canonical central phone claim, initialized immutable profile placement and actual physical profile in a User-owned shard. It compares central snapshots before and after shard I/O without retaining a central transaction across that I/O. The stage ACK allocates a new positive baseline generation and records a nonnegative contact mutation revision. Existing projection versions are observed exactly; absent projection is explicit, with null projection revision, phone contact version and verification epoch. This baseline is evidence for future migration proofs, not current verification authority.

Dedicated migration tables retain immutable commands, hashes and ACKs. One unresolved baseline per owner remains `RESERVED` or `PUBLISHED`; authorization expiry is not reservation release. Protected contact, claim, placement and projection mutations join the existing registration lock and reservation fences. V7 triggers retain history and constrain contact changes in the primary database; service guards participate in the same protocol. Independently authorized display-name edits remain available. No new remote-shard contact writer or name freeze is introduced.

Publication requires the original Auth PRIMARY decision and a validated immutable publication-evidence snapshot bound to the stage. It sets the central directory's same-phone `phone_verified` flag and, only for primary placement, the physical profile flag. It stores both email and phone receipt metadata in the migration ACK without making User authoritative for email. It does not create or promote `verification_projection_current`. Non-primary physical flags remain historical cache; public profile reads overlay the central phone and flag.

State progression is irreversible: `RESERVED` can become `PUBLISHED` or `ABORTED`; only a published record with an ACK and an authoritative Auth `COMPLETED` decision can become `RELEASED_AFTER_COMPLETION`. Unknown, unreachable or missing decisions fail unavailable. Exact publication replay returns the retained ACK without incrementing publication revision. A result read does not infer success from an absent ACK.

Runtime wiring is explicitly default off through `USER_MIGRATION_BASELINE_ENABLED=false`. Enabling it creates the dedicated User client/store/routes; Auth migration requires its separate configuration and readiness gates. Existing durable database fences are not erased by disabling routes.

## Consequences and limits

User publication is not successful end-to-end migration, provider ACTIVE status, OTP issuance or banking-session authority. Auth Main now composes the default-off migration resources, including durable decision/evidence endpoints, recovery, restricted admission and final cutover. The implemented FINAL transaction rechecks the User publication ACK and account/provider state before completing migration and revoking old sessions; pending failures must not revoke them merely because migration started. These Auth behaviors are separate from the User V7 boundary and still require complete isolated runtime acceptance and independent verification before activation. [ADR 0048](0048-passwordless-migration-contact-binding.md) records the separate candidate and verified-contact namespaces; the [migration guide](../legacy-user-migration.md) records the current composition and release gates.

No wallet, ledger, balance, identity UUID or existing session is rewritten by User V7. Deployment rollback must preserve migration history, unresolved fences and immutable placement. Do not delete reservations, fabricate ABORT, backfill verified history or edit a published Flyway migration to recover availability. Full deployment/readiness remains blocked until Auth integration, independent security/QA review and actual isolated acceptance are complete. See the [migration guide](../legacy-user-migration.md) for implemented routes and remaining gates.
