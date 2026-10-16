# Database backup and recovery

Disposable application recovery passed on 2026-10-16 at revision
`960087d863fbd4e57d0c1b78109ae91fe277b64d`, within the synthetic scope below.
This does not establish production recovery objectives or validation of a later
integrated revision. Never run local baseline apply commands against the
persistent stack as part of CI.

## Inventory from checked-in Compose definitions

| Store | Recovery inventory and limits |
| --- | --- |
| auth-db | Identities, external identity mappings, sessions and credential hashes |
| user-db | Profiles, authoritative phone bindings and shard placement directory |
| user-shard-s1 / user-shard-s2 | Optional profile stores; include every configured and historically referenced shard, not only current placement targets |
| wallet-db | Wallet ownership, readiness and stable ledger account mappings |
| payment-db | Original payment IDs, requester-scoped idempotency payloads, durable orchestration and notification outbox |
| ledger-db | Accounts, integer AED fils projections, immutable balanced postings and original command outcomes |
| sso-db | Keycloak realm, users, sessions and client configuration; required with SSO |
| ledger-db-replica1 / replica2 | Physical standby volumes; rebuild from recovered authoritative primary, never treat as independent logical backups |
| edge-data / edge-config | Caddy TLS material and runtime configuration; excluded from database dumps; private operator recovery required |
| assistant-db | Optional pgvector database; discovered stores require the exact pinned Compose image and assistant/operator identity; enabled assistant writers join the capture fence |
| sms-sandbox-data | Optional local SMS sink filesystem history; not a PostgreSQL store and excluded from logical dumps; separate private recovery handling required |

Read-only source bind mounts (realm import, replication scripts, Caddy configuration,
assistant initialization and optional corpus input) are configuration dependencies,
not captured database stores. Secrets, global roles/grants, matching source/schema,
TLS and notification sink configuration require separate private recovery handling.
No live inventory was inspected; enabled overlays are operator-selected.
The baseline backup rejects an enabled `sms-sandbox` service as unsupported rather
than silently claiming its filesystem history was captured. CI runner and validation
daemon volumes are test infrastructure, outside the application recovery inventory.

## Isolated restore preflight

`python -B scripts/restore-isolated-backup.py --directory <private-synthetic-backup>`
validates the entire saved inventory, sizes and SHA-256 checksums before Docker.
It rejects missing core stores, missing shards, duplicate databases, wrong database
identities and archive path substitution. `--apply` restores into new owned,
network-isolated containers without published ports and removes only those resources.
There is no existing-target restore argument. Execute only under a reserved build
slot with trusted synthetic archives. SQL archives are executable untrusted input;
a checksum does not authenticate their producer.

New backups include order-independent table-content fingerprints as well as counts
and reconciliation. These preserve evidence of original IDs and mappings; MD5
fingerprints are accidental-change detection, not cryptographic authenticity.
Aggregation costs grow with table size. Older manifests containing counts alone
cannot establish exact data continuity and are rejected by saved-archive preflight.
Malformed inventory and missing or invalid table fingerprints fail before Docker.
Optional assistant coverage is recorded in the manifest and must match its archive
inventory; configured and historically referenced user shards must be included.
The table relation count must match the number of tables covered by continuity
evidence; contradictory inventories are rejected offline.
Database restore checks do not establish
application replay, authorization or financial acceptance.

## Required recovery experiment and measurement

In an explicitly disposable topology, capture all stores with writers fenced,
record the UTC capture boundary and final acknowledged original payment ID/WAL
position, then restore and compare complete mappings, IDs and journal projections.
Retry the original commands through the real application: no duplicate postings,
no new funding IDs and no fabricated ledger success. Confirm journal mutation
rejection and AED balance reconciliation. Corrupt/missing artifacts and wrong
targets must fail before any restore operation.

`scripts/backup-recovery-smoke.py` defaults to an offline plan. Under a reserved
heavy lease, `--apply` uses already-local pinned images to create synthetic database
fixtures and restore each into a different UUID-owned, network-isolated container.
It applies checked-in service migrations, includes two user shards and pgvector,
and uses an opaque Keycloak-store marker without starting Keycloak. The source is
stopped before recovery. It compares table fingerprints and balanced ledger
projections, checks exact immutable-trigger rejection, and verifies an original
payment ID remains a single database journal row. These are database guarantees;
they do not prove real application replay, Keycloak startup or runtime grants.
No existing container, customer archive, provider or persistent Compose project is
used. Private temporary synthetic archives are removed after verification.
The script reports per-database monotonic dump/restore durations and a quiesced
synthetic capture point. These measurements are not production recovery objectives
or end-to-end application RTO/RPO.

Measure recovery time with a monotonic clock from declared outage/fencing through
restored application acceptance, including quorum and pool reconnection. Record
phase durations separately; container readiness is not the endpoint. Measure
recovery point by comparing acknowledged command IDs and WAL boundaries to the
recovered set. Quiesced logical dumps provide no continuous PITR and cannot promise
zero loss for writes outside the fence. Production RTO/RPO remain unmeasured;
the dated application experiment below measures only its disposable fixture.

### Disposable database evidence, 2026-10-14

The bundled Python invocation of `scripts/backup-recovery-smoke.py --apply`
passed on base `a3535df1d282ac512082a5127f99d54f0e6d6da5` with the uncommitted
backup/recovery patch; capture began at `2026-10-14T14:18:39Z`. Every synthetic
source was stopped before its archive was restored into a fresh isolated target.
Complete table fingerprints matched for all nine stores. The restored ledger
retained two balanced postings, no projection mismatches or negative customer
balances, immutable journal/results and one original payment-ID journal row
after a database duplicate. Synthetic identity/profile/wallet mappings and a
pending payment intent survived unchanged. This is database evidence, not
application replay or actual Keycloak startup evidence.

| Synthetic store | Fresh database restore and verification, seconds |
| --- | ---: |
| auth-db | 3.938 |
| user-db | 4.235 |
| wallet-db | 3.610 |
| payment-db | 3.797 |
| ledger-db | 5.110 |
| sso-db, opaque marker only | 3.266 |
| assistant-db, real pgvector schema | 4.516 |
| user-shard-s1 | 4.328 |
| user-shard-s2 | 5.406 |

The monotonic interval includes target creation/startup, restore, schema/data
comparison and database guards; it excludes source capture and cleanup. There
were no missing captured marker rows or table fingerprints in these quiesced
fixtures. Dataset sizes and same-host local images make these timings unsuitable
as production objectives. Private synthetic dumps and owned resources were
removed by the script. That database-only run did not establish Keycloak startup,
runtime-role grants or application replay. The later application experiment below
covers synthetic Keycloak/application recovery; restricted production grants and
production RTO/RPO remain unverified.

The real `scripts/ledger-replication-smoke.py` run passed by `2026-10-14T15:18Z`
on the same base and backup/recovery patch: streaming, replica read-only
enforcement, synchronous-quorum interruption/recovery, primary restart, physical
slots, fencing the old primary, manual promotion/reparenting and a subsequent
synchronous write all passed. An initial Windows-checkout run failed before
primary readiness because CRLF shell scripts could not start. The source LF
policy now covers all four replication scripts, with unchanged shell logic;
the rerun exercised the actual bind-mounted scripts. Random disposable fixtures
were removed after both runs. The replicated probe is a generic isolated table,
not an HTTP payment replay or a measured failover RTO.

Fence the old ledger primary before promotion, apply available WAL, reparent the
remaining standby, restore synchronous quorum and reconnect application pools.
The existing replication smoke proves generic-table continuity, not ledger/payment
replay. Same-host replicas are not host-level HA. Full Gradle/check, Compose
application acceptance, independent financial/security review and hosted CI remain
external delivery gates.

### Disposable application recovery evidence, 2026-10-16

`scripts/backup-application-recovery-smoke.py` is import-safe and defaults to an
offline plan. Its new adapter, `scripts/recovery_fixture_runtime.py`, uses explicit
owned container/network references rather than importing the existing smoke scripts
that execute at import or target fixed host ports. The HTTP, shard configuration,
PKCE and balanced clearing-account funding contracts come from the actual service
source and existing SSO/P2P/ledger smoke fixtures. No host `CI=true` override is used.

The real staged experiment passed on clean source revision
`960087d863fbd4e57d0c1b78109ae91fe277b64d`. Independent QA audited the private
stage receipt `recovery21-runtime-privatecopy-fixed.log` and standalone
`recovery21-final-private-runner.log`: the nested acceptance reports are equal,
and all three recorded tool SHA-256 values match that revision's Git source blobs.
Windows checkout line endings can differ from those versioned bytes. Receipts
remain in private operator storage and are not committed or published.

| Versioned tool under `scripts/` | Recorded SHA-256 |
| --- | --- |
| `backup-application-recovery-smoke.py` | `2814242e79e225a488172e7674767e736b4786c82948fb581bd916bc64021292` |
| `recovery_fixture_runtime.py` | `be844fab47aec2245718fa707c093e5e380986cd28a2a66707570b655557813a` |
| `stage-application-recovery-ci.py` | `73b47d7b740f586ccecae412f16675eed339d57c2d2a0b192ac5e04fd3fed18b` |

The run restored all nine stores, verified complete table fingerprints before
starting target applications, and passed legacy password/session and real native
PKCE/provider-refresh recovery, owner isolation, original payment/result/key
replay, changed-payload rejection, AED balance/journal reconciliation and mutation
rejection. The restored ledger's read-only physical standby passed live streaming,
checkpoint replay, retained-volume restart and fresh-volume rebuild checks.
Missing acknowledged fixture payments were **0**. Ownership-checked inner and
outer cleanup completed; the outer success receipt is emitted only after cleanup.

| Measurement | Seconds | Boundary |
| --- | ---: | --- |
| Fencing and capture | 78.216 | Final acknowledged fixture payment through quiesced capture and source shutdown |
| Outage through all acceptance | 225.969 | Same start through restored application and standby acceptance, before subsequent cleanup |

These are same-host synthetic measurements, excluding image provisioning/builds
and initial fixture setup. They are not production RTO/RPO, PITR, HA, promotion,
synchronous-quorum recovery or recovery of the 200-owner baseline. Assistant
coverage is schema/index continuity only; there is no assistant model or production
TLS recovery claim. Prior failed attempts are recorded below. Current-main
integration, required CI, integrated review and any application deployment remain
separate delivery gates; this evidence applies only to the recorded revision.

The apply invocation requires `--apply --daemon <explicit-local-endpoint>
--bank-images <outside-checkout-image-manifest> --dependency-images
<private-dependency-provenance-manifest>`. The endpoint must be a local
Unix socket or named pipe on a dedicated daemon whose daemon labels contain
`io.armoney.disposable-recovery=true`, with zero containers initially. It must
never be the persistent application daemon. The manifest has exactly `revision`
and `images` fields: `revision` is the current checkout HEAD, and `images` maps
each of the six bank service names to a local `sha256:` image ID. Each bank image
must also carry the matching `org.opencontainers.image.revision` label. Preparing
these trusted images and the disposable daemon belongs to the leased staging
entry point below; the application script does not build, pull, label a daemon or add providers.
An image revision label is provenance supplied by that stage, not proof of image
contents or of a clean working tree.

Keycloak 26.7.4, PostgreSQL 17.6, Caddy 2.11.4, the pinned pgvector digest and the
Python 3.13.7 Alpine transport image must already be local. Tags are resolved once
to immutable image IDs. Every resource has a unique ownership token and label.
The source and target use different internal bridge networks, named volumes and
containers, with no published ports or host mounts. Caddy supplies a disposable
local CA for the stable `https://recovery.test` issuer; only the owned runner trusts
that CA. No external identity, email, SMS or embedding provider is connected.
Cleanup rechecks ownership and removes only the individually recorded resources.

The prepared experiment creates three synthetic Keycloak users, obtains real
authorization codes with S256 PKCE, provisions profiles/wallets through the bank
gateway, funds one wallet with an explicit balanced clearing transfer and records
one completed original payment. All writers are stopped before fingerprints and
logical dumps of the nine stores are captured. All source containers are then
stopped and verified stopped before fresh target resources are allocated.
Archives stay in an outside-checkout temporary directory, are checked before
restore and are removed afterward. Full table fingerprints must match before
target applications start.

The target checks restored bank sessions (including a previously logged-out token),
fresh provider login, original provider refresh sessions, stable identity/profile/
wallet ownership and foreign-owner denial. It retries the original payment with
its original requester and idempotency key, rejects a changed payload and a foreign
requester using that key, and checks the same payment ID/result, unchanged ledger
contents, 750/250/0 AED-fils customer balances, balanced postings, reconciliation
and exact journal/result mutation-trigger rejection. Funding occurs only before
capture and is never repeated during recovery. Application containers and SQL
checks use the same `bank` database role; this does not prove restricted production
grants because the disposable PostgreSQL bootstrap role is privileged.

Three OIDC profile fixtures use shard s1. Two additional legacy registration and
password-login fixtures populate s2 and primary through trusted auth email prefix
placement. Before capture the tool requires matching populated profile/directory
counts in all three stores; after restore it verifies original bank sessions,
fresh password login, wrong-password rejection and stable profile placement.
Assistant coverage is schema and synthetic index continuity only, with no assistant
writer or external model. Edge CA/configuration is freshly generated rather than
restored; there is no production TLS recovery claim. Aggregate output records the
synthetic original payment ID, UTC acknowledgement/fence, capture WAL positions
and monotonic capture/outage-to-acceptance durations. The WAL positions describe
the logical capture boundary, not PITR replay. No production RTO/RPO, replica
failover, concurrent-spending or rollback acceptance is established by this tool.
The restored ledger primary additionally supplies a fresh physical standby. The
fixture explicitly installs and reloads a SCRAM replication HBA rule limited to
its named replication role and validated owned private subnet, then verifies that
exact rule. It does not depend on the image's loopback-only replication defaults.
The tool verifies read-only recovery, live WAL streaming past a post-start checkpoint,
the original payment and complete ledger fingerprints. It repeats those checks
after restarting the retained volume and after rebuilding onto a second fresh
volume. Neither standby is promoted; this is manual replica recovery coverage,
not synchronous-quorum restoration or an application failover test.

### Isolated source and image provisioning

`scripts/stage-application-recovery-ci.py` defaults to an offline plan. Its
`--apply --outer-daemon <explicit-local-endpoint> --source <clean-checkout>
--revision <exact-40-character-HEAD>` invocation belongs to the orchestrator's
reserved heavy-test lease. The outer daemon must already have the recovery DinD
and runner images plus the dependencies listed in that script. No pulls occur.
The stage resolves all local references to immutable image IDs, creates a random
recovery-only Compose project, and copies a Git bundle of the clean exact source
into its stopped runner. Dependency images stream directly into the owned labelled
DinD daemon. The frozen outer identity is retained separately from the exported linux/amd64 configuration digest: Docker image stores can expose different identity types. Exported configuration, platform and root filesystem must match the inspected source; the imported identity must match the SHA-256 of the verified configuration bytes. The runner
mounts only project volumes, including the dedicated inner socket, never the host
Docker socket. It clones the bundle and checks out the exact revision before the
inner wrapper builds six bank images with matching revision labels and runs real
application recovery. This also provides the source and dependencies missing from
a bare new Compose workspace. The stage rechecks daemon identities and resource
ownership before operations and removes only individually verified project
containers, volumes and network; failure cannot invoke a broad Compose teardown.
Private source bundles are temporary; logs and archives are not published. A
failed/uncertain resource creation may retain resources and fails acceptance;
cleanup never guesses ownership. Earlier runtime attempts failed before acceptance:
Windows Compose discovery needed the installation-path environment variable,
Compose named volumes required exact safe serialization checks, containerd versus
classic image IDs required configuration-digest provenance, and Windows-to-Linux
copy required explicit private file modes. Those failures are not passing evidence.
After the fixes, the 2026-10-16 revision above passed real application acceptance
and cleanup; later revisions still require their own checks.

Offline guards can be run with bundled Python using `-B` from a temporary copy
outside the checkout: `test-recovery-fixture-runtime.py` and
`test-backup-application-recovery-guards.py`. They test import/default-plan safety,
explicit target/image requirements, daemon labels, ownership revalidation, network/
port/mount/privilege restrictions and removal of ambient Docker/Compose/CI
overrides. These tests never simulate financial success.

Documentation impact: this adds a source acceptance entry point and its limitations;
it changes no application HTTP/schema/runtime contract. The architecture records
dated synthetic recovery acceptance separately from production recovery and
delivery/deployment status.

The stopped runner receives a private `recovery-dependencies.json` manifest with
exact source revision, `linux/amd64` platform and the seven allowlisted dependency
references mapped to frozen outer IDs and verified configuration IDs. Wrapper and
application validation reject missing, extra or malformed entries. The application
requires `--dependency-images`; it uses these verified IDs, including the assistant
dependency whose repository digest may not survive classic Docker import. Temurin
FROM tags must resolve to the verified configuration immediately before each build.
No ambient Docker context or dependency map is accepted.

The dependency manifest is copied as one explicit regular USTAR entry with mode
0600 and root ownership, then checked before starting the owned runner. Copyback
uses a 128-KiB byte cap and 30-second watchdog; raw header, checksum, path, type,
ownership, mode and content checks reject hidden extensions or additional entries.
This avoids trusting Windows host chmod results as Linux container permissions.

Image transfer has no disk archive spool. A bounded TAR observer rejects unsupported
extended/link/sparse entries, traversal, duplicates, truncation and trailing data;
limits are 8 GiB per stream, 4,096 members, 1 MiB per metadata file and 8 MiB
aggregate metadata. A 600-second watchdog terminates both exporter and loader.
Full image configuration stays private in memory; handoff evidence contains only
known references, platform and digests. These implementation limits and offline
guards supplement the dated real experiment; they do not establish a production
recovery objective.
