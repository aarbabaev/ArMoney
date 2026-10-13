# Synthetic local baseline

Issue #29 provides explicit local tools, not automatic production startup seeding.
Default count is 200, with ordinals 100 through 299. A count from 1 through 900
is supported; preserve the same count and private manifest on every retry.

Each ordinal `n` creates phone `+971528000{n}`, email `aarbabaev{n}@test.ae`,
first name `Arman`, last name `Arbabaev {n}`, a complete profile and one AED wallet.
The common password comes only from local `SEED_TEST_USER_PASSWORD`. Never place it
in source, command arguments, issues or manifests. These are synthetic fixtures.

## Identity and wallet provisioning

Use the existing trusted local CA and the configured HTTPS Gateway origin. Set
`KEYCLOAK_ADMIN_TOKEN` to a short-lived administration token in the process environment.
An optional `KEYCLOAK_ADMIN_REFRESH_TOKEN` allows the long run to refresh once after
an explicit HTTP 401; rotated credentials stay in memory. The administration origin
must be loopback. The local Caddy administration listener uses
`https://localhost:9443/sso` when enabled; disposable CI uses port 9090 instead.

```powershell
python -B scripts/seed-local-users.py
python -B scripts/seed-local-users.py --apply --acknowledge-local-fixtures --count 200 --gateway https://localhost:8443 --admin-base https://localhost:9443/sso --ca-file C:/private/armoney-root.crt --manifest C:/private/users.json
```

Substitute the actual configured private-IP origin when it differs from localhost.
The parent directory must already exist outside the checkout. The first command is
an offline plan. Apply creates Keycloak identities, then uses the existing native
authorization-code/PKCE flow and Gateway APIs. No password grant is enabled for clients.
Email/phone verification requirements are not bypassed; required actions stop the run.

The manifest contains identity/account mappings and has an owner-only file ACL.
An exclusive lock covers reads, validation and writes. Do not run another initializer
against the same fixture range with a different manifest. Unknown existing identities
are rejected, not adopted or overwritten. Interrupted creation before its checkpoint
can require manual reconciliation. Remove a stale lock only after confirming no seeder
or funding runner is active. Never delete the manifest to work around a collision.

## Initial funds

Deploy the reviewed Ledger distribution before funding. Run:

```powershell
python -B scripts/fund-local-users.py
python -B scripts/fund-local-users.py --apply --acknowledge-local-fixtures --count 200 --manifest C:/private/users.json --ca-file C:/private/armoney-root.crt --gateway https://localhost:8443
```

This verifies each authenticated user's ACTIVE/READY wallet and identity mapping,
then invokes the Ledger CLI in the exact local Compose Ledger container. The CLI is
gated by `APP_ENV=local` and `ARMONEY_LOCAL_DEMO_FUNDING=I_UNDERSTAND_SYNTHETIC_FUNDS`.
It is not a customer HTTP endpoint. Stable UUIDv5 commands and their payloads are
checkpointed before dispatch. The amount in integer fils is
`100000 + ((n - 100) * 7919 % 400001)`, within 1,000–5,000 AED.

Ledger validates ownership, currency and account identity, and posts balanced immutable
entries against a dedicated clearing account. A new funding command requires a pristine
account. Repeating the original command after an uncertain result or subsequent spending
does not credit again. Do not invent a new funding ID to retry or refill an account.

## Backup and restore boundaries

After all 200 users are funded, verify the fixed default baseline through Gateway:

```powershell
python -B scripts/verify-local-baseline.py --apply --acknowledge-local-fixtures --gateway https://localhost:8443 --issuer https://localhost:8443/sso/realms/armoney --ca-file C:/private/armoney-root.crt --manifest C:/private/users.json --transfer-and-return
```

This requires the same process-only fixture password. It checks all fixture mappings,
profiles, wallet readiness and expected balances. The optional transfer flag posts
1 AED between two fixture wallets, checks idempotent replay and changed-payload
rejection, then transfers it back and verifies the original balances. The same private
manifest retains flushed intent, phase, key and payment-ID checkpoints before dispatch.
Keep it for interrupted recovery; matching history alone is not proof that this verifier
owns a transfer. Unexplained balance changes block before sending another payment.
Omit the flag for read-only balance verification;
without `--apply`, no requests are made. No emulator is launched.

After acceptance checks pass:

```powershell
python -B scripts/backup-local-baseline.py
python -B scripts/backup-local-baseline.py --apply --output C:/private/baseline-20261013
```

Apply requires a new directory outside Git. It stops previously running application,
SSO and edge writers, captures each primary (including Keycloak and profile shards),
then restarts those writers in a `finally` block. Coordinate this outage with other
operators: direct database clients must not write during capture. Process termination
or host failure can prevent cleanup; inspect the stack before restarting work.
Before dumping, every configured or directory-referenced shard must be present;
missing historical shards block capture instead of producing an incomplete template.
`--expected-fixture-count` defaults to 200 and must match a smaller/larger configured
dataset when using the backup tool separately from the fixed 200-user verifier.

The restricted backup manifest records source revision/worktree state, server and
image versions, checksums, Flyway state, table counts and ledger reconciliation metrics.
Snapshots contain identity data and password hashes; keep them private and out of Git.
Verification restores each archive into its own new PostgreSQL container with no network,
published ports or live volumes, compares schema/data metrics and financial invariants,
and removes only its own labeled verification resources.

`database_restore_verified` establishes database restoration, not full application
acceptance. Independent isolated-stack login, ownership, balances and transfer/retry
checks are still required before declaring the template ready. A successful dump alone
is insufficient. The archive excludes `.env`, deployment configuration, TLS volumes,
global roles/grants and replica topology; preserve those separately in private storage.

There is deliberately no automatic restore into the persistent stack. Before replacing
live data, obtain explicit confirmation identifying the backup and destructive target,
stop all writers, preserve a rollback copy, restore all matching primaries together and
rebuild replicas. Keep email/SMS workers disabled until pending outbox state and sink
configuration are inspected, to avoid replaying notifications. Restore matching source,
credentials/configuration and schema versions, then validate isolated acceptance before
opening ingress. Issue #21 tracks broader operational recovery acceptance.
