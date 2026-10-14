# ADR 0024: Optional isolated local Kafka infrastructure

Status: source implemented for #31; integrated CI and real-cluster acceptance
pending. No deployed readiness or measured recovery target is claimed.

## Context

[ADR 0022](0022-kafka-payment-saga.md) and the
[Saga contract](../kafka-saga-contract.md) define the future asynchronous payment
direction. The existing banking services still use HTTP and PostgreSQL for
financial authority. This owner-authorized infrastructure slice supersedes the
bootstrap Kafka exclusion only for a separate optional Compose project; it does
not enable financial adapters or authorize worker operations on persistent data.

## Decision

Define three dedicated KRaft controllers and three brokers in
`compose.kafka.yaml`, using Apache Kafka 4.3.1 pinned by manifest digest. Separate
roles allow controller quorum tests independently of broker minimum-ISR tests.
Each node has its own project-scoped volume; the project has an internal network,
no published ports and no banking network/database mounts. A single-host cluster
does not provide host-level high availability.

Require mutual TLS and hostname verification for broker/controller connections.
Use distinct node and application principals with default-deny exact topic/group
ACLs. Node/admin principals are superusers; application principals are not. Keep
credentials outside all source/build contexts, mount only each workload's own
directory read-only and exclude CA signing material. Containers run as uid/gid
1000 with dropped capabilities, a read-only root filesystem and bounded tmpfs.

Format storage only by explicit operator action with persisted cluster/node/voter
identities. Startup refuses missing or mismatched metadata and never formats.
Provision the v1 topics explicitly with three partitions, replication factor
three, minimum ISR two and unclean election disabled. Verify existing settings
instead of silently changing them. Broker acknowledgement is a transport result,
never proof of a ledger commit, financial completion or notification delivery.

Provide a directory-based cold snapshot helper in the same pinned image. Require
all six nodes stopped before each copy, regular files/directories with exact
identity/inventory/checksum validation, trusted caller-retained manifest hashes,
and empty restore volumes in a separate project. Reject conflicting metadata,
links, unsafe paths and unexpected ownership. Credentials and captured offsets
are separate recovery inputs. Preserve failed partial destinations for diagnosis;
the helper never cleans source data or adopts unrelated volumes.

## Consequences and verification

The optional cluster adds memory, disk, certificate-renewal and administrative
obligations. Replication is not backup. CA rotation, automated backup/disaster
recovery and external monitoring are not implemented. The
[operations runbook](../kafka-operations.md) documents the mount/CLI contract,
manual observation thresholds and recovery limits.

A separate trusted-owner CI job uses the existing isolated runner slots and
dedicated inner Docker daemon. Offline guards and actual Bash snapshot fixtures
run before daemon validation; one UUID-project smoke runner owns validated
resource cleanup. Required runtime cases include TLS/ACL rejection, topic
configuration, retained records, broker/controller outages, insufficient ISR,
storage identity refusal and isolated cold restore. Offline tests establish no
broker durability or RTO/RPO. Existing full backend checks remain required.

Payment/ledger Kafka adapters, financial schemas, PostgreSQL inbox/outbox
plumbing and durable Saga reconciliation remain future implementation work under
the frozen contract. No service imports a peer's classes or queries its database;
no business model is added to platform-runtime. Runtime delivery requires green
integrated CI, independent QA/security review and explicit deployment evidence.
