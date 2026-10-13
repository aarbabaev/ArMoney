---
name: bank-messaging
description: Design or verify ArMoney Kafka contracts, Saga recovery and transactional Outbox/Inbox when assigned messaging work.
---
# Messaging and Saga
Kafka is planned, not an existing runtime dependency; confirm current source and the assigned issue before changes.
Keep a durable Saga state machine in Payment and atomic balanced posting in Ledger.
Define message IDs, schema versions, aggregate keys, legal transitions and unknown-result reconciliation.
Commit business changes, inbox deduplication and outgoing intent within the owning transaction where applicable.
Offset commit follows durable handling; retries/rebalances/replay must not duplicate financial effects.
Define retention for deduplication and events, poison-message handling and retry ordering. Kafka transactions do not encompass PostgreSQL or SMTP.
Test crash boundaries, stale workers, consumer rebalance and concurrent spending using isolated fixtures.
Follow the assigned ADR for topics/replicas/ACLs; do not add infrastructure or external providers beyond task authorization.