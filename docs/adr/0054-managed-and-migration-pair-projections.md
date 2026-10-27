# ADR 0054: Explicit banking and migration pair projections

Status: Implemented configuration separation; focused configuration tests passed.
Scoped coenabled startup and synthetic LOCAL/historical-SSO browser acceptance
passed on the source/runtime recorded in PR #96. Exact-head CI, final review and
deployment remain separate gates.

## Context

Managed banking requires code-intent confirmation. Migration account proof uses a
dedicated legacy account client, which must remain outside the three banking
clients. Applying the banking client restriction directly to the complete global
legacy inventory prevented both features from starting together. Passing all
banking clients into the migration observation constructor also violated its
exact account/target pair contract.

## Decision

Validate the complete global managed and legacy inventories before deriving a
consumer projection. Unknown clients, foreign issuers and overlapping pairs fail
closed. The dedicated `armoney-migration-account` legacy pair is accepted only
with matching enabled migration configuration and an actual managed native
target. It cannot be a managed pair or verification client.

Code-intent confirmation receives only native banking pairs. Global managed
banking classification retains the dedicated account as legacy; migration
observation receives exactly its configured legacy account and managed target.
No client is relabeled, and native token admission remains restricted to
`armoney-android`, `armoney-ios` and `armoney-web`.

The two exact migration token-endpoint settings are routing metadata rather than
credential roles. Exclude only those names from the credential-alias inventory;
the migration configuration still validates their common issuer and token path.
All actual credentials remain subject to independence checks.

## Evidence and limits

Five focused suites passed 224 tests without failures, errors or skips. Cases
cover the coenabled configuration graph, native-only confirmation projection,
account-only observation, unknown client/issuer/overlap rejection and default-OFF
compatibility. This is configuration evidence, not proof of a running coenabled
Main process, full migration, session continuity or deployment readiness. No
schema, public wire contract, proof parser or persistent data changes are made.
