# Legacy user migration baseline

The User V7 slice implements durable profile/contact staging, publication and reservation fences. It is default off. It does not implement a complete legacy login migration, and no full-readiness or production-activation claim follows from these source changes. [ADR 0047](adr/0047-legacy-user-migration-baseline.md) records the boundary and design provenance.

## Implemented User behavior

An authenticated stage command identifies the existing identity and profile; User verifies the exact central canonical phone, initialized immutable placement and the existing physical row in its own shard. Missing or conflicting data fails closed. No replacement UUID, contact-based owner lookup or contact rebinding is performed. Names are outside the baseline and independently authorized display-name edits remain available.

Stage retains an immutable ACK with a newly allocated positive `baseline_generation`, `contact_mutation_revision` (zero allowed), Auth's opaque email version/digest, a baseline hash and exact projection discriminant. When `projection_present=false`, `projection_revision`, `phone_contact_version` and `verification_epoch` are null. No historical positive projection version is invented. Canonical phone is not returned in the ACK or copied into retained stage-command JSON; the existing phone claim remains User's authority.

The central store checks snapshots before and after its physical-shard existence read; it holds no central transaction across that shard I/O. A concurrent baseline change rejects the command. Baseline hashes use domain-separated SHA256 over four-byte big-endian length-prefixed UTF-8 fields in the declared order, not serialized JSON. Exact DTO fields, canonical UUIDs and integer constraints are implemented in [MigrationBaseline](../user-service/src/main/java/com/arman/bank/userservice/domain/MigrationBaseline.java).

Publication resolves a durable Auth PRIMARY decision and immutable email/phone receipt evidence outside User transactions, then rechecks the original central baseline under lock. It stores the final ACK and sets the same-phone central directory flag. Only primary placement also updates the physical profile flag. Non-primary shard contact flags remain historical cache; ordinary profile reads overlay central phone authority. Publication changes neither contact bytes nor placement/name and does not insert or update `verification_projection_current`, establish provider ACTIVE status or issue a bank token. Auth remains authoritative for email; no public `email_verified` field is introduced.

## Private HTTP surface

[MigrationBaselineRoutes](../user-service/src/main/java/com/arman/bank/userservice/infrastructure/MigrationBaselineRoutes.java) registers these routes only when enabled. All are POST under `/v1/internal/migrations/legacy/`, with `Cache-Control: no-store` responses. Dedicated credentials are checked before parsing the bounded 12 KiB JSON command and do not use ordinary bank-session identity authentication.

| Route suffix | Credential header | Effect |
| --- | --- | --- |
| `user-stage` | `X-ArMoney-Migration-Stage-Key` | Reserve the existing baseline and return its immutable stage ACK. |
| `user-publish` | `X-ArMoney-Migration-Publication-Key` | Validate Auth decision/evidence and publish the same-baseline flag and final ACK. |
| `user-publication-result` | `X-ArMoney-Migration-Publication-Key` | Return a retained final ACK; unavailable if none exists. |
| `user-resolve` | `X-ArMoney-Migration-Publication-Key` | Resolve only an authoritative abort or completed publication. |

The stage request is exactly `protocol_version, migration_id, stage_operation_id, command_id, decision_command_id, identity_id, profile_id, canonical_phone, email_contact_version, email_digest, policy_version, created_at, authorization_deadline, payload_hash`. Protocol version is 1; the authorization interval is positive and at most 900000 ms. Publish/result/resolve use exactly `migration_id, decision_id, stage_operation_id, command_id, payload_hash`, retaining the original stage payload hash and stable publication command.

The client calls Auth PRIMARY POST `/v1/internal/migrations/legacy/decision` and `/v1/internal/migrations/legacy/publication-evidence` with `X-ArMoney-Migration-Decision-Key`. It uses the original staged `decision_command_id`; neither endpoint is implemented by this User slice. The client fixes its origin, rejects redirects and bounds responses/calls. This is an implementation description, not proof of absolute DNS or slow-body cancellation. Inbound parsing has a byte cap; a separately proven absolute inbound read deadline is not claimed here.

## Configuration and durable fences

| Configuration | Meaning |
| --- | --- |
| `USER_MIGRATION_BASELINE_ENABLED` | Defaults to `false`; exact `true` enables the dedicated runtime resources/routes. |
| `USER_MIGRATION_WRITER_ID` | Stable authenticated Auth writer identity. |
| `AUTH_TO_USER_MIGRATION_STAGE_KEY` / `_PREVIOUS` | Current and optional previous stage credential. |
| `AUTH_TO_USER_MIGRATION_PUBLICATION_KEY` / `_PREVIOUS` | Current and optional previous publication credential. |
| `USER_TO_AUTH_MIGRATION_DECISION_KEY` | Dedicated outbound Auth PRIMARY decision/evidence credential. |
| `AUTH_MIGRATION_PRIMARY_ORIGIN` | Explicit fixed Auth PRIMARY origin; no caller-selected URL. |

Configuration rejects aliased/reused migration-purpose credentials and collisions with other configured secrets; [ProfileRoutes](../user-service/src/main/java/com/arman/bank/userservice/infrastructure/ProfileRoutes.java) also checks separation from ordinary internal credentials. Never log configuration maps, contact bodies, OTPs, credentials or provider responses. No secret values belong in deployment examples.

V7 adds `migration_contact_counters`, `migration_baselines`, retained-history constraints and contact guards. `RESERVED` and `PUBLISHED` both fence protected mutations. Fresh stage admission is bounded by 10000 unresolved and 100000 retained records; existing exact replay is handled before new admission. There is no automatic history deletion or timer-based reservation release.

The explicit transitions are `RESERVED -> PUBLISHED -> RELEASED_AFTER_COMPLETION` and `RESERVED -> ABORTED`. A durable Auth ABORT can release an unpublished reservation; Auth COMPLETED releases only one with a retained publication ACK. Unknown/timeout/missing decisions are unavailable, never implicit abort. Repeated publication preserves its ACK and `publication_revision=1`. A published record cannot be reset to an unpublished state.

Phone registration/verification, ordinary verification-projection writes and protected central profile changes are fenced. Display-name-only writes may proceed without replacing protected baseline fields. The source supports User-owned non-primary shards through central authority and existing placement; it does not add a cross-service database connection or a new distributed contact writer. Disabling the feature does not delete V7 data or release unresolved fences; recovery requires the authoritative protocol. Preserve tables and append-only Flyway history during rollback.

## Pending complete migration and acceptance

Auth V15-V22 implement restricted account/provider admission, OTP persistence, retained publication decisions, immutable receipt snapshots and typed source authority in the integration source. Default-off Main composition selects the browser, private decision and User mutation routes, current-User client and source-aware native consumers. Isolated Main composition and positive core cutover/rollback evidence do not establish complete production browser/native acceptance or unknown-outcome recovery. Existing bank tokens keep their original expiry/logout/security-revocation behavior until successful final migration. Enabled managed cutover denies public local registration/password login and legacy-pair SSO new admission; the restricted migration proof endpoints remain separate. Pending proof or an old token cannot issue a new ordinary bank session.

U2 `email_contact_version` and `email_digest` identify the immutable Auth-owned migration email binding; they do not independently assert possession. In the proposed passwordless-owner contact flow, candidate version 1 and its candidate-domain digest are permitted only after genuine existing-account admission. Publication must bind actual consumed EMAIL receipt evidence to that same original candidate. A resulting verified owner-contact generation belongs to a separate Auth namespace and cannot replace or reinterpret the original stage fields or refresh the ACK. This meaning does not change the absent-phone projection triple, permit zero/null email binding fields, or grant User email authority. The candidate flow is under implementation, not deployed functionality.

Only final successful Auth migration may classify/link the same owner and atomically revoke old sessions after checking the durable User publication ACK and fresh account/provider state. New bank-session issuance remains the separate [managed banking consumer](adr/0046-managed-banking-session-consumer.md). Missing contacts or unusable proof require separately authorized recovery. Names, identity/profile UUIDs, wallet/account ownership, balances/history and credential hashes must be preserved; migration does not grant funding, rewrite ledger data or authorize password reset.

The source includes `MigrationBaselineSpec`, `MigrationBaselineRoutesSpec`, `MigrationBaselineIntegrationSpec` and `MigrationBaselineUpgradeIntegrationSpec`; their presence is not a test result. Required isolated evidence includes real PostgreSQL upgrade/data preservation, absent and present projection snapshots, exact replay and unknown publication recovery, authoritative abort/completion, expired-but-still-fenced reservations, credential rotation/isolation, capacity, protected mutation races and non-primary shard name/contact overlay behavior. Complete readiness additionally needs Auth V15/OIDC/dual-OTP integration and independent security/QA acceptance. No build, deployment, migration activation or live-data operation is authorized by this guide.

## Live User observation and conservative contact mutation

`AUTH_MIGRATION_USER_REVISION_ENABLED` and `USER_LIVE_REVISION_ENABLED` default to false. Selection requires independently validated purpose credentials and the genuine registration-completion projection authority; an unavailable default projection adapter is never substituted. Auth's selected current-User client is shared by the C2 and managed consumers, and private route exemptions are registered only alongside their purpose-authenticated handlers.

A new migration-backed contact mutation without supported original command custody returns unavailable before an Auth receipt or transition and before any User write. It is never treated as initial registration. This release does not introduce a new migration contact-command producer. See [ADR 0049](adr/0049-live-migration-contact-revisions.md) for the boundary and remaining acceptance.

The browser resource graph additionally requires `AUTH_TO_USER_MIGRATION_PUBLICATION_KEY` and `USER_TO_AUTH_MIGRATION_DECISION_KEY` as separate purpose credentials. Enabling source flags does not itself establish external browser, provider, Mailtrap inbox or Android acceptance.

Both OIDC callbacks require the original `state` and `code`. Standard optional
`iss` must exactly match the issuer derived from the server-selected account or
managed authorization endpoint. Optional `session_state` is bounded opaque
metadata and is discarded; it cannot replace a session identifier or establish
authority. Duplicate decoded fields, unknown fields, malformed encoding, control
characters and mixed error/code responses are rejected. Original attempt cookies,
PKCE and nonce checks still apply. Source support for these callback fields does
not establish successful end-to-end migration acceptance.

### Opt-in edge ingress under verification

The default edge configurations continue to deny `/migration`. The separate
`compose.migration-ingress.yaml` and `compose.migration-web-ingress.yaml` overlays
select candidate ingress files for API-only and web stacks respectively. Neither
overlay enables Auth, Gateway or provider features. They require the separate
private `MIGRATION_EDGE_TO_GATEWAY_KEY` and exact canonical HTTPS
`MIGRATION_PUBLIC_ORIGIN`; these values must agree with Gateway configuration.
Do not add these overlays to a persistent stack before integrated acceptance.

Candidate ingress matches the original raw URI and exact fifteen method/path
pairs, verifies actual TLS and configured authority, removes caller trust headers
and supplies the two fixed migration-hop headers. Unknown HTTP authorities receive
a local 404 without reaching migration, OIDC, API or web upstreams. A conflicting TLS server name
is rejected. An absent server name is supported for certificate-validated LAN IP
connections only with the exact configured authority. Browser Origin, cookies,
CSRF and immutable callback/command binding remain independently checked by
Gateway and Auth. No migration rewrite or access logging is introduced.
Migration upstream requests use fresh connections so a replayable GET is not
automatically retried after a pooled-connection read failure. The isolated TLS
fixture requires one upstream attempt for each stalled phase. Other upstream
routes retain their existing connection settings.

Edge transport limits are phase/inactivity limits: two seconds for connection,
five seconds for response headers and five seconds for individual reads/writes.
They do not establish an absolute five-second normal HTTP stream deadline;
continuous upstream drip may exceed that duration. The fixed trusted Gateway
retains its own absolute request budget and finite response cap. Header/body
stall, drip, client-abort, TLS and trust-header acceptance remain required;
source configuration is not evidence those tests passed.

Actual AuthMain/UserMain process acceptance requires an explicit isolated-fixture opt-in. CI supplies `-Darmoney.test.isolated.user.fixture=true` only to the affected Gradle step after the disposable-daemon guard succeeds. It is not a global runtime setting and must not be enabled against the persistent banking stack.
