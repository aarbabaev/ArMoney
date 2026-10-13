# Delivery status and agreed roadmap

This page distinguishes implemented source from deployed functionality and planned work.
Snapshot: 2026-10-13. GitHub issues/PR checks and deployment checkpoints hold live status;
this file does not imply continuously running agents.

## Implemented baseline

- Java 21/Javalin services with PostgreSQL, jOOQ/Flyway; AED integer fils only.
- Gateway identity enforcement, Keycloak OIDC with native-client PKCE, profiles,
  unique registered UAE phones, wallets and internal P2P transfers.
- Ledger atomic balanced immutable postings and payment-ID idempotency; durable
  payment recovery. This is not yet the planned Kafka-backed Saga.
- User profile shards and ledger standbys exist. Replication does not imply automatic
  primary failover or host-level high availability.
- Native Android supports accounts, profile, transfers, history and notifications.
  iOS source exists but development/native CI remain paused.
- Transactional in-app/email notification intent and Mailtrap Sandbox SMTP delivery.
  SMTP acknowledgement loss can cause duplicate email; no real SMS provider is connected.
- Registration already uses a UAE phone as Keycloak username with required email,
  name and password. Phone ownership is not verified by SMS; registration email OTP
  and new-login notification emails are not implemented.

## Pagination deployment checkpoint

[PR #39](https://github.com/aarbabaev/ArMoney/pull/39) adds owner-bound signed cursor
pagination and Android load-more while preserving legacy newest-100 routes.
PR #39 merged at `6a6289751102122c703197b99604c169f103a190` after green backend/Android CI
and independent verification. Payment and Gateway were updated locally on 2026-10-13;
all six services passed readiness. The Android APK is built; device installation and
interactive acceptance have not been performed. The PR also carries updated reviewer
merge/status instructions; those instructions are not a background scheduler.

## Synthetic baseline delivery

Issue #29 adds explicit local provisioning and balanced Ledger funding tooling.
Its source, CI, deployment and verified backup checkpoints must be tracked separately.
Tool availability alone does not mean that 200 users have been created or a backup
has been restored successfully. See [local baseline operations](local-baseline.md).

## Agreed backlog

| Work | Tracking | Status / dependency |
| --- | --- | --- |
| Kafka contracts and persistent Saga design | #30 | Planned first |
| Local Kafka durable infrastructure | #31 | After #30 |
| Kafka payment Saga with Outbox/Inbox | #32 | After #30/#31 |
| Multi-instance recovery and fault tests | #33 | After #32 |
| Synchronous HTTP/gRPC load balancing | #34 | After #33; coordinate #25 |
| Synchronous gRPC contracts | #25 | Async commands/events use Kafka instead |
| Local SMS sandbox and protected web inbox | #35 | Planned, no carrier delivery |
| Phone/password registration and SMS verification | #36 | After sandbox and #28 |
| One-time registration email OTP | #28 | Mailtrap SMTP; not login MFA |
| Email notification on new successful login | #37 | No notification on token refresh |
| Transaction SMS notifications | #38 | Sandbox only; independent delivery failure |
| 200 synthetic users and baseline backups | #29 | Coordinate verification and #21 restore |
| Backup/restore and monitoring | #21 / #22 | Separate acceptance work |
| Client visual consistency | #27 | Android active; iOS remains paused |

Email is mandatory at registration and verified once; changing it requires verification
again. Routine login remains phone plus password. A successful new login triggers an
email notification, not an email OTP. Transactions send email/in-app/SMS notifications
through their configured sandbox channels when implemented. These are planned changes,
not current runtime guarantees.

The owner has specified synthetic funds and phone data only. Keep financial correctness
and isolation tests; never contact a real phone number merely because a synthetic value
has valid UAE syntax. No external SMS provider is authorized.

## Team and deployment truth

See [workflow](agents/workflow.md) and [reviewer merge policy](agents/merge-policy.md).
Backlog is planned; Ready is eligible; In Progress names an active implementer; Review
names the reviewer. Awaiting deployment means the application change is not ready for
owner use yet. Done requires verified deployment/acceptance, except explicit
documentation-only work. If agents stop, record idle/waiting honestly in the issue.
Merge authorization does not automatically grant deployment authorization.

Local Docker inspection on 2026-10-13 showed the existing backend, Keycloak, Caddy,
user shards, ledger standbys and local CI containers running. Container uptime is not
an end-to-end acceptance result and does not identify a deployed source revision.
Kafka and the SMS sandbox were not present in that inspected stack.
