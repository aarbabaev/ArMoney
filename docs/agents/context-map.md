# ArMoney project context map

Read only the row needed for the assignment. Source and maintained architecture
are authoritative; these pointers are not duplicate specifications. Existing
Java package names and paths retain `arman` compatibility.

| Question | Read on demand |
| --- | --- |
| Boundaries, readiness, implemented versus planned | Relevant section of [ARCHITECTURE.md](../../ARCHITECTURE.md) |
| Ownership, file/build lease, handoff, CI and live Docker rules | [workflow.md](workflow.md) and [AGENTS.md](../../AGENTS.md) |
| Findings and independent retest | [communication.md](communication.md) |
| Dependencies and task wiring | Root `settings.gradle`, `build.gradle`, affected module `build.gradle` |
| HTTP shape and implementation | Affected module `src/main/resources/openapi.yaml`, route/use-case and matching tests |
| Public identity boundary | `app-gateway/.../ProtectedProxy.java`, `AuthProxy.java`; [auth session ADR](../adr/0003-auth-sessions.md) |
| Database behavior | Owning module `src/main/resources/db/migration/` and persistence adapter; `platform-runtime/.../Database.java` |
| Ledger correctness | [atomic ledger ADR](../adr/0005-atomic-ledger.md), [ledger guide](../ledger.md), `LedgerEngineIntegrationSpec.groovy` |
| Ledger replication | [replication runbook](../ledger-replication.md), [ADR 0013](../adr/0013-ledger-replication.md), compose.ledger-replication.yaml and LedgerReads physical-standby tests |
| Wallet provisioning/recovery | [provisioning ADR](../adr/0007-wallet-ledger-provisioning.md), wallet `Provisioner` and `ProvisioningIntegrationSpec` |
| Disposable system evidence | `.github/workflows/ci.yml`, relevant `scripts/*smoke*`; workflow isolation rules before execution |
| Native Android slice | Assigned `android/` source/tests, [Android contract](../android-contract.md), gateway contract and [bank-android](../../.agents/skills/bank-android/SKILL.md) |
| Native iOS slice | Assigned `ios/` project/source/tests and frozen gateway contract; [bank-ios](../../.agents/skills/bank-ios/SKILL.md) |
| Keycloak slice | Assigned `sso-service/` configuration and explicit auth adapter lease; [bank-sso](../../.agents/skills/bank-sso/SKILL.md) |

Ellipses abbreviate module Java package paths; use `rg --files <module>` to locate
one named file. Search without generated `build/` outputs. The native-client/SSO rows are
target ownership and lookup instructions, not a claim those flows passed runtime
verification. Consult the integrated architecture status and current source.

Use [skill routing](skills.md) for capability selection. For external API/version
uncertainty consult current official Android, Apple, Keycloak or OpenAI documentation for
the exact question; do not paste complete manuals into project instructions.
