# ADR 0022: Kafka transport and durable payment Saga

Status: proposed implementation contract for #30; not implemented or deployed.

## Context

Payment-service already persists requester-scoped idempotency, resolved account
mappings and PENDING work, then retries the same ledger payment ID over HTTP.
Ledger commits both sides of a transfer and its immutable outcome in one
PostgreSQL transaction. Email has a separate durable delivery lifecycle.

Owner-authorized Kafka planning (#30) supersedes the bootstrap exclusion only
for this future messaging direction. It does not add Kafka to today's runtime
or authorize an unreviewed deployment. Implementation belongs to #31/#32.

## Decision

Use Kafka for asynchronous business commands and committed facts. Keep HTTP for
public APIs, admission checks and authoritative reconciliation. gRPC is a
possible future synchronous adapter, not an implemented protocol or a reason
to change public APIs. Payment-service orchestrates a durable Saga in its own
database; no service accesses a peer database.

The [versioned contract](../kafka-saga-contract.md) defines IDs, topics,
ownership, local transaction boundaries, legal state changes, retry deadlines,
replay, security and acceptance gates. PostgreSQL outboxes and consumer inboxes
provide atomic local intent and duplicate-safe application. Delivery is at least
once; Kafka transactions do not atomically commit PostgreSQL or SMTP effects.
See [Apache Kafka delivery semantics](https://kafka.apache.org/43/design/design/#message-delivery-semantics).

The Saga has one financial step: ask ledger to post the complete transfer.
Debit and credit are never separate Saga steps. Completion requires ledger's
matching, durably confirmed POSTED result; timeouts remain uncertain. Ledger
publishing must preserve the existing synchronous-replication confirmation gate,
including after restart. No timeout, broker failure or email failure creates a
refund. A future authorized reversal is a separate balanced ledger command with
a stable new reversal ID linked to the original, never a journal mutation.

User profile sharding does not shard accounts. Both accounts remain in the same
ledger primary; physical ledger standbys contain replicas of that same database.
Neither Kafka nor the Saga creates a distributed database transaction. Any
future cross-ledger-shard transfer needs a separate accounting and settlement ADR.

## Consequences and rollout

Public PENDING/COMPLETED/REJECTED semantics and client keys stay compatible.
Broker failure can delay work without losing accepted intent while the owning
databases remain durable. Outbox retention, inbox identity retention and audited
replay are operational obligations, not broker defaults. Disaster recovery cannot
promise zero loss after loss of both authoritative databases and their backups.

The change adds infrastructure, service principals, schema evolution and replay
operations. Stage additive migrations and disabled adapters, then exercise
disposable PostgreSQL/Kafka failure tests before enabling any cohort. Pin each
payment's transport, retain its stable ID during rollback, and reconcile every
pending command before retiring an adapter. Never restore older financial state
to undo a transport rollout. Existing migrations remain append-only.

#31 must deliver broker infrastructure, security and isolated infrastructure checks;
#32 must implement service-owned outbox/inbox primitives, their atomic business
integration, and independently verify the service adapters and recovery
cases in the contract. The orchestrator confirms issue scope before dispatch.
This documentation change installs no broker, changes no schema or API, and
provides no runtime test evidence. Independent ledger and security review remain
required before implementation acceptance.
