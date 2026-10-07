# ArMoney documentation index

[Repository README](../README.md) is the entry point; [ARCHITECTURE.md](../ARCHITECTURE.md)
is the maintained system and agent-team map. Current source and exact-revision test
results take precedence over historical ADR scope and previous deployment evidence.

## Product, setup and operations

| Document | Scope |
| --- | --- |
| [Architecture pointer](architecture.md) | Link to the maintained map |
| [M1 plan](m1.md) | Implemented slices and durable public P2P acceptance |
| [P2P contract](p2p-contract.md) | Phone recipients, balances, transfers, recovery and notifications |
| [Onboarding](onboarding.md) | Profile/wallet HTTP walkthrough and IDEA setup |
| [Ledger](ledger.md) | Private financial API, retry semantics and fixture limits |
| [Ledger replication](ledger-replication.md) | Direct standbys, fenced reads, quorum and manual failover |
| [Email notifications](email-notifications.md) | Transactional outbox, Mailtrap configuration and delivery limits |
| [SSO and iOS runbook](sso-and-ios.md) | Windows LAN HTTPS, Keycloak, CA trust and validation boundaries |
| [Native Android README](../android/README.md) | Android Studio, SDK, local HTTPS and native app acceptance |
| [Android contract](android-contract.md) | Android build, identity and financial recovery boundaries |
| [Native iOS README](../ios/README.md) | Xcode, local configuration, simulator/device setup and app behavior |
| [SSO service README](../sso-service/README.md) | Realm, clients, identity mapping and introspection |

## Decisions

ADRs preserve the decision at the time of its slice. Their historical statements do
not override later decisions or the current architecture map. In particular, ADR
0002's deferred ledger execution is implemented by ADR 0005; wallet provisioning
extends the original metadata-only scope in ADR 0004.

| ADR | Decision |
| --- | --- |
| [0001](adr/0001-bootstrap.md) | Bootstrap service boundaries |
| [0002](adr/0002-ledger-consistency.md) | Ledger consistency boundary |
| [0003](adr/0003-auth-sessions.md) | Password identities and opaque sessions |
| [0004](adr/0004-profiles-wallets.md) | Profiles and wallet metadata |
| [0005](adr/0005-atomic-ledger.md) | Atomic ledger posting and balance projection |
| [0006](adr/0006-agent-team.md) | On-demand agent team |
| [0007](adr/0007-wallet-ledger-provisioning.md) | Durable wallet account provisioning |
| [0008](adr/0008-native-ios-and-keycloak-sso.md) | Native client and Keycloak SSO |
| [0009](adr/0009-phone-p2p-payments.md) | Phone attestation, durable P2P and in-app notifications |
| [0010](adr/0010-native-android.md) | Native Kotlin Android client and shared SSO identity |
| [0013](adr/0013-ledger-replication.md) | Direct physical ledger replication and fenced balance reads |
| [0014](adr/0014-uae-registration-phone.md) | Mandatory unique UAE registration phones and transfers without SMS verification |
| [0015](adr/0015-transactional-email-outbox.md) | Atomic email enqueue, asynchronous Mailtrap delivery and contact trust |

## Agent workflow

| Document | Scope |
| --- | --- |
| [Repository instructions](../AGENTS.md) | Delivery, ownership and financial/security rules |
| [Workflow](agents/workflow.md) | Assignments, file/build leases and independent review |
| [Communication](agents/communication.md) | Findings, remediation and evidence handoff |
| [Skill routing](agents/skills.md) | Task-specific skills and role ownership |
| [Context map](agents/context-map.md) | Source/reference lookup by task |
| [Context audit](agents/context-efficiency.md) | Historical instruction-size measurements and routing guidance |
| [Wallet provisioning mission](agents/missions/wallet-ledger.md) | Implemented slice's original acceptance scope |

Role TOMLs live under `.codex/agents/`; executable skill instructions live under
`.agents/skills/`. These are on-demand instructions, not background services.

- [AED-only policy and rollout](adr/0011-aed-only.md): one currency, integer fils,
  append-only constraints and safe handling of existing data.

- [User profile sharding](adr/0012-user-profile-sharding.md): immutable email-prefix
  placement for new profiles, legacy data preservation and central phone authority.
