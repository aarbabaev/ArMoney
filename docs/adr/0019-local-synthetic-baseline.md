# ADR 0019: Explicit local synthetic baseline provisioning

Status: In implementation for issue #29; not yet deployed or acceptance-verified.

The owner uses synthetic funds and phone identities and requests a repeatable
200-user baseline plus restorable backups. CI-only funding scripts remain restricted
to disposable CI. They are not the local provisioning interface.

Identity provisioning uses Keycloak administration only to create tagged fixture
identities, then the supported native authorization-code/PKCE and gateway flows
to establish bank identities, profiles and AED wallets. Existing credentials are
never reset on retry. Verification requirements remain enforced; this tooling
must not silently mark an email or phone verified.

A separately gated offline Ledger CLI provides baseline funding. It accepts an
existing customer account and stable funding ID, validates owner/wallet/currency,
and atomically posts against an explicit dedicated clearing account. New funding
requires a pristine account with no prior journal activity; replay of the same
committed funding command succeeds even after subsequent spending. Changed
payloads and clearing-identity collisions fail. No gateway/customer funding API
is added and account balance columns are not edited directly.

Fixture indices are 100 through 299, default count 200. Email and phone retain the
same ordinal. Amounts are deterministic integer fils in [100000,500000].
The password is read only from SEED_TEST_USER_PASSWORD and is never in manifests.

A complete baseline requires quiescing all writers before capturing each owning
primary database (including Keycloak and profile shards), restricted backup storage,
checksums and schema/source metadata. Replicas are rebuilt from restored primaries.
An isolated restore and invariant check must pass before claiming a usable baseline.
Pending notifications must not unexpectedly replay against external sinks after restore.

Deployment and execution are orchestrator-only, using owner-authorized local
resources. Developer tests use isolated PostgreSQL. UI/end-to-end readiness is
separate evidence from a successful provisioning script or backup command.