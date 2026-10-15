# Proposed Kafka and payment Saga contract

Status: #30 design only. No topics, Kafka clients, Saga tables or broker deployment
are implemented by this document. #31/#32 own implementation and verification;
their assignments must freeze schemas and operational settings before code.
[ADR 0022](adr/0022-kafka-payment-saga.md) records the decision.

## Current boundaries

Source baseline: `7a99a668f4689121b30c284e3cc5c8ddbcfeb37b`.

* Payment `PaymentService`, `PaymentWorker`, `HttpPaymentPeers` and
  `PostgresPayments` implement durable acceptance and same-ID HTTP recovery.
  [P2P contract](p2p-contract.md) and service OpenAPI remain the current API.
* Ledger `PostgresLedger.post` reserves `payment_id`, compares requester and
  payload, locks accounts consistently, posts both sides and persists the
  outcome in one transaction. Its `confirmed` wrapper also guards result lookup
  with the configured replication confirmation. [ADR 0005](adr/0005-atomic-ledger.md)
  remains the accounting boundary; AED policy is [ADR 0011](adr/0011-aed-only.md).
* User `ProfileShards` routes profile data using a pinned directory entry;
  directory reservation and shard writes are separate recoverable transactions.
  Auth identities and central phone claims do not become Kafka authority.
  [ADR 0012](adr/0012-user-profile-sharding.md) describes actual profile sharding.
* Ledger is unsharded. The optional physical standbys copy its database; they
  are not independent account shards. Posting and result lookup use primary.
  [ADR 0013](adr/0013-ledger-replication.md) describes quorum confirmation and
  fenced balance reads. Profile placement has no effect on ledger placement.
* Email already has its own outbox and HTTPS/SMTP delivery adapters. Notification
  failure cannot affect financial state; [email contract](email-notifications.md)
  remains authoritative for current operation.

The following sections are proposed requirements, not descriptions of deployed
behavior. Java domain classes remain local to each service; shared runtime
plumbing must not acquire business entities.

## Synchronous and asynchronous contracts

Public submission, reads, owner authentication, recipient resolution and wallet
eligibility remain synchronous HTTP. gRPC may serve future private synchronous
calls only under a separate adapter contract; it is not currently implemented.
No public broker connection or arbitrary topic publishing is permitted.

Before accepting a new payment, payment-service checks source ownership,
recipient identity/phone, ACTIVE/READY wallets, distinct accounts and AED through
the agreed user/wallet APIs. It freezes mappings and the canonical payload.
These checks do not constitute a distributed lock: administrative wallet changes
can race admission. Ledger rechecks its own account ownership, currency and funds
inside its transaction. A stricter wallet-freeze guarantee needs a separate
contract; no consumer may infer it from an old wallet event.

Existing `(requester_id, client_idempotency_key)` lookup precedes mutable remote
admission. Equal canonical payload returns the same payment; a mismatch is 409.
The key and recipient phone remain private payment data. A newly accepted command
returns 202 PENDING only after payment, Saga and command outbox commit together.
Admission errors remain the existing 400/404/409/503 contract. Kafka unavailable
after that commit does not turn the accepted payment into an admission failure.
Bound backlog capacity before acceptance; once accepted, preserve durable work.

Only matching authoritative POSTED becomes COMPLETED. Only a matching durable
INSUFFICIENT_FUNDS, INVALID_ACCOUNT or BALANCE_LIMIT becomes REJECTED. Protocol,
authorization, transport and storage errors are not financial rejections.
Unsupported currency, invalid amount or malformed command is a protocol error:
current HTTP rejects it before posting (400), and the proposed Kafka adapter
quarantines it without creating a ledger result or making a payment terminal.
For a well-formed AED command, ledger's account eligibility check can instead
produce the durable business outcome INVALID_ACCOUNT, including unauthorized
debit ownership; this differs from missing/invalid transport credentials.
History visibility and in-app notifications retain the existing owner rules.

## Version 1 message envelope

Use bounded UTF-8 JSON (maximum 16 KiB uncompressed per record), no floating-point
amounts, canonical UUID strings and UTC RFC 3339 timestamps. Consumers reject
duplicate JSON keys, malformed required fields and out-of-range integers.

| Field | Required meaning |
| --- | --- |
| `schema_version` | Integer `1`; independent of broker/client version. |
| `message_type` | Exact command/fact name from the topic table. |
| `event_id` | Producer-assigned UUID, persisted before sending; identifies a command too. Retransmission keeps it and identical bytes. |
| `payment_id` | Stable payment UUID, also ledger's idempotency ID. Never replaced to recover uncertainty. |
| `saga_id` | Stable orchestration UUID; v1 equals `payment_id`, retained separately for future workflows. |
| `correlation_id` | Server-generated workflow UUID, frozen at acceptance; retained through reconciliation. Never an authorization token. |
| `causation_id` | Prior message UUID; initial command uses a persisted server-generated admission ID. Result references the command; terminal fact references the confirmed result. |
| `producer` | Exact owning service name. Informational assertion checked against the allowed topic; topic ACLs enforce producer authority. |
| `occurred_at` | Time of the owning durable business decision; never used to order financial effects. |
| `aggregate_version` | Positive monotonic version in the producer's payment-local stream, not a cross-service clock. |
| `payload` | Type-specific fields below; immutable for a given `event_id`. |

Kafka record key is the canonical `payment_id`, required to match the envelope.
Partitioning offers per-partition order only, not ordering across topics or
accounts. Consumers use durable business state and version guards, not assumed
global order. Changing partition counts requires draining/migrating producers
and testing possible reordering; account correctness always rests on ledger locks.

| Topic / owner of schema | Allowed producer | Consumer group / logical handler | Type and payload |
| --- | --- | --- | --- |
| `armoney.ledger.transfer.commands.v1` / ledger | payment-service | `ledger-transfer-v1` / ledger | `PostTransfer`: requester_id, debit_account_id, credit_account_id, currency=`AED`, positive signed64 amount_minor. |
| `armoney.ledger.transfer.results.v1` / ledger | ledger-service | `payment-ledger-result-v1` / payment | `TransferResult`: same requester and full command payload, outcome=`POSTED`/`INSUFFICIENT_FUNDS`/`INVALID_ACCOUNT`/`BALANCE_LIMIT`. |
| `armoney.payment.outcomes.v1` / payment | payment-service | Explicitly registered internal projections only; no consumer required initially | `PaymentCompleted`/`PaymentRejected`: requester_id, recipient_id, currency, amount_minor, nullable rejection_reason. |

Every topic is private and non-compacted. No general-purpose service wildcard
topics. User/wallet/auth facts and email consumers require their own contracts;
they are not silently authorized by this table. Payment's terminal transaction
continues to enqueue local in-app/email work, so no broker email consumer should
also enqueue it. Broker facts contain no phone, email, display name, password,
session token, raw client key or whole public request.

## Durable Saga and local atomicity

Proposed payment-owned Saga row stores `saga_id`, `payment_id`, contract version,
transport mode, command/result event IDs, correlation/admission IDs, immutable
mapping and payload hash reference, state, state version, attempts,
`next_attempt_at`, `response_due_at`, `attention_due_at`, lease token/expiry and
last sanitized error. Reconciliation metadata remains mutable in the separate
Saga record; current terminal payment rows remain immutable.

| Internal state | Legal next states and evidence | Public state |
| --- | --- | --- |
| WAITING_LEDGER | Created atomically with accepted payment and command outbox. Matching confirmed result -> COMPLETED or REJECTED; overdue/uncertain -> RECONCILING. | PENDING |
| RECONCILING | Same-ID replay or authoritative lookup; matching confirmed result -> COMPLETED or REJECTED; repeated uncertainty stays here. | PENDING |
| COMPLETED | Terminal; identical duplicates are no-ops. Contradictory facts raise an incident, never regress status. | COMPLETED |
| REJECTED | Terminal; identical duplicates are no-ops. Later funding cannot change a durable rejection. | REJECTED |

Transport acknowledgement is not a Saga success state. No CANCELLED, TIMED_OUT
or FAILED terminal financial state is introduced. Unknown or conflicting results
are quarantined and reconciled; they cannot mark a payment terminal.

1. Payment transaction: compare client key/payload; insert PENDING payment, Saga
   and PostTransfer outbox. Concurrent duplicate acceptance returns the winner.
2. Ledger transaction: insert/check inbox, compare the entire requester/command
   for the stable payment ID, lock accounts, post one balanced immutable transfer
   or persist rejection, and insert its result outbox. Inbox and financial effect
   commit together. A result event ID is durably unique per ledger payment result.
3. Ledger relay: before publishing any result, including after crash or replay,
   enforce the same configured durable quorum confirmation as HTTP `confirmed`.
   A locally committed outbox row alone is insufficient when confirmation is
   required. Unconfirmed data must never leak success through Kafka or fallback.
4. Payment result transaction: inbox dedupe plus complete payload/requester match,
   Saga version check, terminal transition, in-app notifications, email outbox
   and terminal fact outbox commit atomically. Stale leases cannot overwrite it.

No local transaction contains broker, HTTP or email network I/O. Relays use
expiring fenced leases, bounded batches and conditional completion updates.
Ledger's local debit/credit rollback is ordinary ACID rollback, not compensation.

## Delivery, replay and retention

Each service owns its PostgreSQL outbox and inbox. An outbox stores immutable
event bytes/hash and routing metadata with mutable delivery attempts/lease/ack.
An inbox uniquely keys `(logical_handler, event_id)` and stores payload hash,
payment ID and disposition. Duplicate ID with different bytes is a security or
integrity incident, never a successful duplicate. Business constraints separately
deduplicate by payment ID even when a faulty producer invents a different event ID.

Consumers commit Kafka offsets only after their local transaction or durable
quarantine record commits. Manual commits track the contiguous processed prefix
per partition; never skip earlier unfinished work when processing concurrently.
Crashes after database commit but before offset commit cause safe redelivery.
Duplicates of ledger commands retain/requeue the original result event so a lost
result can be repaired without another posting. The relay may publish a duplicate
after an acknowledgement is lost; consumer inboxes handle it.

Configure idempotent producers explicitly with `acks=all` and compatible retries
and in-flight limits. This reduces producer retry duplicates, not database relay
duplicates. Production design target: replication factor 3, minimum ISR 2 and
no unclean election; isolated single-broker tests must not claim that durability.
Kafka transactions and `read_committed` can cover Kafka offsets/output, but do not
cover PostgreSQL commits, Mailtrap or SMTP. See official [producer settings](https://kafka.apache.org/43/configuration/producer-configs/),
[topic durability settings](https://kafka.apache.org/43/configuration/topic-configs/)
and [delivery semantics](https://kafka.apache.org/43/design/design/#message-delivery-semantics).

Initial proposed broker replay window is 7 days; retained outbox payloads are at
least 30 days and until dependent processing/reconciliation is proven complete.
Unresolved payment commands/results are never purged merely by age or broker ack.
Inbox identity/hash tombstones and stable client/ledger idempotency records remain
for the lifetime in which a payment may be replayed. No automatic deletion of
those identities is part of #31/#32. Any later retention policy must cover topic,
backup restore and operator replay horizons together; shortening it needs review.
Quarantine metadata follows the same replay window with restricted access and
bounded encrypted payload retention. Financial record retention needs a separate
approved policy; broker deletion is not financial erasure.

After broker loss, reconcile all nonterminal Sagas against authoritative ledger
lookup, then replay retained original outbox events using their original IDs.
Compare terminal payment records with ledger results and notification uniqueness;
rebuild required projections from authoritative records through an audited tool.
A publish ack alone is not proof a consumer processed the event. If payloads have
expired, reconstruction must be explicit, versioned and use retained business
dedupe keys; do not blindly reset offsets or replay every historical email.
If database restoration may have lost acknowledged financial commits, fence
writes and require incident reconciliation before resuming; Kafka is not a
replacement financial database or backup policy.

## Deadlines, retries and reconciliation

Proposed starting bounds, to be tested in #32: 30-second worker leases, 6-second
remote calls, response due after 30 seconds, jittered exponential retries starting
at 1 second and capped at 60 seconds. Persist counters and next-attempt times;
dead workers are reclaimed after lease expiry. After 15 minutes unresolved,
raise an operator alert and mark attention required without changing PENDING.
Scan due Sagas at least every 30 seconds with bounded work; cap alert volume and
active retries, never discard accepted work on attempt exhaustion.

Reconciliation calls the existing private ledger payment-result lookup with the
stored requester/payment ID and validates all original fields and recognized
outcome. A confirmed result uses the same terminal transaction as an event, with
a persisted reconciliation evidence ID for causation. Missing result means replay
the same original command, not rejection: an earlier execution may still commit.
Unavailable, unauthorized, malformed or quorum-unconfirmed lookup remains pending
and alerts as appropriate. Do not read a standby for absence or invent new IDs.
Reconciliation/event races converge through row/version guards and business keys.

Compensation is not required for this Saga's single atomic financial step. Future
reversals require separate authorization and a new durable reversal command ID
linked to the original confirmed posting. They must enforce their own funds,
currency, ownership and idempotency rules; reversal can itself fail. Never edit
the original journal, debit in one service then credit in another, refund on
timeout, or reverse a payment because email delivery failed. SMTP acknowledgement
loss may duplicate mail; delivery state is separate from financial state.

## Security and schema evolution

Use TLS and distinct authenticated service principals; default-deny topic/group
ACLs implement the producer/consumer table. Payment cannot publish ledger results;
ledger cannot publish payment outcomes; gateway, clients and email workers have
no financial-topic write permission. Restrict transactional IDs if transactions
are used and reserve topic creation/alteration/deletion for operations. Separate
replay tooling credentials, approvals and audit records from runtime principals.
See [Kafka authorization and ACLs](https://kafka.apache.org/43/security/authorization-and-acls/).

The envelope's producer/requester fields are not authentication. Ledger trusts
only its authorized payment-command channel and still validates debit ownership;
a compromised authorized payment producer remains a material trust boundary.
Broker credentials never replace end-user verification at admission. Shared-key
HTTP trust limitations remain until separately addressed; avoid claiming isolation
from a producer label alone. UUIDs and amounts are sensitive linkable metadata:
restrict access, encrypt transport/storage/backups and log only bounded IDs/error
codes when operationally needed, never full bodies or contact data.

For v1, permit only additive optional fields with documented defaults; old
consumers must tolerate these fields while validating all known security and
money fields. Required-field changes, new outcome semantics and incompatible
enums require v2 topics plus explicit dual-read migration. No registry product
is selected; versioned JSON schemas and producer/consumer compatibility fixtures
are required in #32. Unknown major version/type is durably quarantined without
financial mutation, with a sanitized alert and replay after a compatible deploy.
Quarantine is not a business rejection. Preserve event identity on repair/replay;
changed payload needs a separately reviewed correction contract.

## Migration and rollback

1. #31: pin a supported broker/client version, provision isolated infrastructure,
   ACLs, quotas, retention, monitoring and restore procedures; implement technical
   plumbing without moving business models into platform-runtime. No live stack
   changes are authorized by this document.
2. #32: add append-only service migrations, schemas, inbox/outbox adapters and
   Saga recovery behind disabled switches. Keep existing HTTP APIs compatible.
   Older binaries must not run against incompatible state without verification.
3. Enable a synthetic cohort only after the acceptance matrix passes. Persist
   each new payment's HTTP or Kafka transport; HTTP workers must exclude Kafka
   Sagas. Do not dual-submit live effects as a shadow test. Existing unresolved
   HTTP payments retain their mappings and stable IDs; legacy unresolved scaffold
   rows without trusted mappings remain unprocessed.
4. Roll back admission to HTTP for new payments, fence Kafka dispatch and consumer
   ownership, drain/reconcile pending Sagas, then explicitly transfer remaining
   work to an HTTP-capable recovery version using the same payment IDs. In-flight
   duplicates must remain harmless. Never delete broker volumes, revert financial
   tables or reset client keys to roll back transport.
5. Keep result consumers/reconciliation and compatible schema until every old
   cohort is terminal and retention gates are met. Reverting to an old binary
   that cannot understand Saga ownership is not a safe rollback plan.

## Failure acceptance matrix (required, not executed)

Use disposable Kafka and real PostgreSQL with synthetic clearing-funded balanced
fixtures. CI must run the required repository checks and applicable isolated
Compose smoke flows. No tests or financial seeding on the persistent user stack.

| Injected condition | Required durable evidence |
| --- | --- |
| Payment crash before/after acceptance commit; repeated client submission | No partial acceptance; or one payment/Saga/outbox with original key and payload. Changed requester/key payload cannot steal/redefine it. |
| Broker outage, lost publish ack, duplicate/reordered commands and worker restart | PENDING intent retained; same IDs; one ledger outcome and at most one balanced transfer. |
| Ledger failure after journal insert but before commit | Inbox, result outbox, balances, journal and reservation roll back together; retry can post once. |
| Ledger local commit with quorum unavailable, then process restart | No POSTED event/HTTP success before required durable confirmation; pending payment converges after recovery. |
| Result lost, expired or delivered after reconciliation | Authoritative lookup/replay converges; one terminal transition and unique owner notifications/email intents. |
| Consumer crash after DB commit, rebalance or stale worker lease | Redelivery is a no-op; no skipped uncommitted partition prefix; old lease cannot overwrite state. |
| Concurrent identical, distinct and opposite-direction transfers | One effect per ID, no overspend/deadlock regression, balanced journal and balance reconciliation. |
| Well-formed AED command: insufficient funds then funding; credit balance overflow; missing/ineligible account or unauthorized debit ownership | Stable durable INSUFFICIENT_FUNDS, BALANCE_LIMIT or INVALID_ACCOUNT respectively; no money movement or late success for the rejected ID. |
| Unsupported currency, zero/negative/noninteger/out-of-range amount, or malformed command | HTTP protocol rejection or durable Kafka quarantine; no new ledger result/posting and no terminal payment mutation. Existing records remain unchanged. |
| Changed payload under event/payment ID; forged producer/result; foreign lookup | Conflict/quarantine or deny; no unauthorized debit/terminal transition/data disclosure. Test actual broker ACLs. |
| Unknown schema, oversized message, poison record | Bounded resource use, durable quarantine before offset advancement, alert, controlled replay; pending financial state preserved. |
| Full broker loss or retention expiry, then outbox replay | Recovery from retained DB intent/results, no second posting, no blind historical email replay; document any unrecoverable backup gap. |
| Restore an owning database behind acknowledged financial commits | Fence new financial writes, dispatch and automatic reconciliation mutations; preserve surviving evidence. Resume only after incident reconciliation resolves missing/divergent commits and independent QA verifies a consistent recovery point. Never replay against incomplete ledger history as if it proved no prior posting. |
| Email/SMTP unavailable or accepted with lost acknowledgement | Payment stays terminal; retry/possible duplicate mail reported honestly; no refund. |
| Profile shard outage, standby lag or physical replica outage | Admission/recovery respect owning contracts; no cross-database queries or assumed cross-shard transaction. |
| Transport rollback with in-flight records | Same ledger IDs converge, no financial restoration/reset, compatible recovery owns all outstanding work. |

The implementing owners return exact tested revisions, commands and outputs;
independent ledger/QA and security reviewers retest the integrated revision.
Documentation review must verify topic ownership, diagrams and proposed status.
This contract alone provides no passing runtime, durability or deployment evidence.
