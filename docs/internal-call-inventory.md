# Internal call inventory and transport classification

Status: first preparatory acceptance slice of #25, source inventory only. This
document does not approve or implement gRPC, protobuf, service discovery or a
payment Saga. Initial inventory baseline: `457024e6f862f746e9207f24a6423835cc50cd54`;
the payment email/profile boundary below is refreshed against main source
`af1f7975a4ef888000c88e88fbd83ade919b1776` for DOC-CALL002. This is source
inspection, not runtime verification of that revision. #30 and [ADR 0022](adr/0022-kafka-payment-saga.md)
are the architecture decision gate; #34 owns the discovery dependency. Public
client APIs remain HTTP. The [Kafka/Saga contract](kafka-saga-contract.md) is a
proposed business transport contract, not evidence of running service consumers.
Existing broker infrastructure does not establish a migrated payment workflow.

Auth-service and SSO implementation, introspection and registration are covered
by their separate owner. The payment caller's auth email lookup is included below
so that the cross-owner boundary is not omitted from both inventories.
Gateway session validation is a prerequisite to the calls below, not permission
to accept client-supplied identity. PostgreSQL connections are listed separately
from service RPCs. No runtime tests or network probes were performed for this
documentation change.

## Reading the inventory

All listed service-to-service calls currently use HTTP/JSON. A synchronous gRPC
candidate is a **possible future adapter**, subject to #30, equivalent contracts,
owner isolation, bounded decoding and #34 endpoint/lifecycle design. It does not
authorize a blocking chain of payment orchestration RPCs. Asynchronous business
intent/facts are Kafka candidates only where a versioned contract exists; user,
wallet and email topics are not implicitly authorized.

Java internal APIs use `X-Service-Key` and server-selected `X-Identity-Id`, enforced
by [InternalHttp](../platform-runtime/src/main/java/com/arman/bank/runtime/InternalHttp.java).
These credentials identify a trusted boundary, not independent service principals:
a shared key does not isolate a compromised peer. Origin configuration accepts
HTTP or HTTPS and rejects embedded credentials/query/fragment/path prefixes where
the adapter validates an origin. Do not describe all internal traffic as TLS.
All monetary fields below are signed integer fils and AED; no float conversion
or protobuf numeric relabelling is proposed.

## Gateway forwarding: gateway_owner to service owners

Implementation: [ProtectedProxy.configure/forward/forwardPage/send](../app-gateway/src/main/java/com/arman/bank/appgateway/ProtectedProxy.java).
Targets are configured by `USER_BASE_URL`, `WALLET_BASE_URL`, `PAYMENT_BASE_URL` in
[gateway Main](../app-gateway/src/main/java/com/arman/bank/appgateway/Main.java).
The gateway creates trusted owner headers after current session validation;
client identity/service-key headers are not copied. `X-Identity-Email` is sent
only for profile PUT and only from validated identity metadata. Payment creation
alone forwards the validated requester-scoped `Idempotency-Key`.

| Caller method / destination owner | Actual method and target route | Wire request / response reference | Classification |
| --- | --- | --- | --- |
| `forward`, user_owner | GET, PUT `/v1/users/me`; PUT `/v1/users/me/phone` | No GET body; PUT `display_name` or `phone_number`; profile view with `id`, `identity_id`, display name, phone and verification state. [ProfileRoutes](../user-service/src/main/java/com/arman/bank/userservice/infrastructure/ProfileRoutes.java), [user OpenAPI](../user-service/src/main/resources/openapi.yaml) | Synchronous profile read/update candidate; no invented profile-event topic. |
| `forward`, user_owner | Public POST `/v1/recipients/resolve` becomes POST `/v1/users/resolve-phone` | `phone_number` -> registered `identity_id`, `display_name`, `phone_number`; not an email/name authority derived from a client. Same user route/OpenAPI references. | Synchronous admission lookup candidate. |
| `forward`, wallet_owner | GET, POST `/v1/wallets`; GET `/v1/wallets/{id}/balance` | POST `{currency}`; wallet list/view or `{wallet_id,currency,balance_minor}`. [WalletRoutes](../wallet-service/src/main/java/com/arman/bank/walletservice/infrastructure/WalletRoutes.java), [wallet OpenAPI](../wallet-service/src/main/resources/openapi.yaml) | Synchronous request/view boundary. Wallet creation may durably return PENDING; not a new synchronous provisioning chain. |
| `forward`, payment_owner | POST, GET `/v1/payments`; GET `/v1/payments/{id}` | POST source wallet, recipient ID/phone, AED, integer amount plus separate idempotency header; payment view/list. [PaymentRoutes](../payment-service/src/main/java/com/arman/bank/paymentservice/infrastructure/PaymentRoutes.java), [payment OpenAPI](../payment-service/src/main/resources/openapi.yaml) | Preserve public HTTP durable admission and PENDING semantics; an internal synchronous adapter must not execute the financial Saga inline. |
| `forwardPage`, payment_owner | GET `/v1/payments/page`; GET `/v1/notifications/page` | Only canonical bounded `limit` and opaque URL-safe `cursor`; paginated views and `next_cursor`. Same payment contract. | Synchronous read candidate. |
| `forward`, payment_owner | GET `/v1/notifications`; POST `/v1/notifications/{id}/read` | No request body; owner-scoped notification list/view, persisted read timestamp. Same payment contract. | Synchronous projection read/update candidate; reading a notification is not ledger posting. |

Shared bounds: connection timeout 2 s, redirects disabled, 16 admitted proxy
requests. Incoming body is at most 4,096 bytes; body-free routes reject any body.
Downstream request timeout is 8 s (payment POST 20 s), with corresponding async
future wait 9 s (21 s); response body limit 131,072 bytes. These are **not** one
global gateway end-to-end budget: session validation has its own preceding wait.
The proxy performs no automatic business-command retry. Public payment replay
must retain the requester key/payload; a timeout does not prove rejection.

## Wallet provisioning and authoritative balance reads

Implementation: [HttpLedgerAccounts.provision/balance](../wallet-service/src/main/java/com/arman/bank/walletservice/infrastructure/HttpLedgerAccounts.java),
[WalletProvisioner.runOnce/start](../wallet-service/src/main/java/com/arman/bank/walletservice/application/WalletProvisioner.java),
[PostgresWallets](../wallet-service/src/main/java/com/arman/bank/walletservice/infrastructure/PostgresWallets.java).
Owner: wallet_owner caller, ledger_owner destination. `LEDGER_BASE_URL` is a
validated configured origin. Both calls use the service key and the wallet's
persisted owner, never an arbitrary incoming owner.

| Method | Actual ledger route | Payload and validated response | Classification / uncertain outcome |
| --- | --- | --- | --- |
| `provision(Wallet)` | POST `/v1/ledger/accounts` | `{wallet_id,currency}` -> account `id`, matching `wallet_id`, `owner_id`, `currency`; funded replay is valid and not a wallet readiness failure. [LedgerRoutes](../ledger-service/src/main/java/com/arman/bank/ledgerservice/infrastructure/LedgerRoutes.java), [ledger OpenAPI](../ledger-service/src/main/resources/openapi.yaml) | Current durable background HTTP command, idempotent by wallet identity. Retrying the same wallet recovers an uncertain response. A gRPC adapter could preserve this behavior; an async wallet topic needs its own approved contract. |
| `balance(Wallet)` | GET `/v1/ledger/accounts/{ledgerAccountId}` | Requires ACTIVE/READY wallet; response account/wallet/owner/AED must match and `balance_minor` must be nonnegative integral long. Same ledger contract. | Synchronous authoritative read candidate, never a Kafka balance-authority replacement. |

Connection timeout 2 s, request 5 s, future wait 6 s, streamed response cap 4,096
bytes, redirects disabled. Balance concurrency is capped at 16. Provisioner
wakes every second; PostgreSQL claims/fences ownership, and retry delay is bounded
exponential up to 60 s. No SQL transaction spans HTTP. Cancellation/uncertain
provisioning leaves durable recovery; there is no account-ID replacement guess.

## Payment admission and durable ledger command

Implementation: [HttpPaymentPeers.resolve/post/call](../payment-service/src/main/java/com/arman/bank/paymentservice/infrastructure/HttpPaymentPeers.java),
[PaymentService.submit](../payment-service/src/main/java/com/arman/bank/paymentservice/application/PaymentService.java),
[PaymentWorker.tick](../payment-service/src/main/java/com/arman/bank/paymentservice/application/PaymentWorker.java),
[PostgresPayments.claim/finish/retry](../payment-service/src/main/java/com/arman/bank/paymentservice/infrastructure/PostgresPayments.java).
All destinations come from configured `USER_BASE_URL`, `WALLET_BASE_URL` and
`LEDGER_BASE_URL`. Headers are `X-Service-Key`, `X-Identity-Id=requester`, and
`Accept: application/json`; redirects are disabled. The requester is persisted
payment authority, not a caller-supplied alternate owner.

| Caller / destination owner | Actual method and route | Wire and checks | Proposed transport placement |
| --- | --- | --- | --- |
| `resolve`, user_owner | POST `/v1/users/resolve-phone` | Phone -> recipient UUID/name/phone; phone must match and UUID must equal the submitted recipient. | Synchronous admission lookup candidate. |
| `resolve`, wallet_owner | GET `/v1/internal/wallets/{sourceWalletId}` | No body; wallet UUID, requester owner, AED, ACTIVE/READY and ledger account UUID validated. | Synchronous eligibility/mapping candidate. |
| `resolve`, wallet_owner | GET `/v1/internal/wallets/by-owner/{recipientId}/currency/{currency}` | No body; destination UUID/recipient owner/AED/eligibility/account checked; debit and credit mappings must differ. Internal route is service-key trusted; payment caller checks ownership. | Synchronous admission candidate; does not authorize cross-owner public access. |
| `post`, ledger_owner | POST `/v1/ledger/transfers` | `{payment_id,debit_account_id,credit_account_id,currency,amount_minor}` -> complete matching command plus `outcome`. POSTED requires HTTP 200; supported business rejections require 409. | Future **asynchronous Kafka financial command/result**, not a synchronous gRPC payment->ledger Saga step. Preserve same-ID HTTP recovery until rollout is approved and verified. |

Admission resolves these three peers within one 15 s deadline; each call receives
at most 6 s or the remaining deadline, and the future wait uses that same
remaining budget. Ledger post has a 6 s deadline. Responses are capped at 8,192
bytes and require JSON object content type. The application admits at most eight
concurrent new resolutions. Durable requester/key replay is checked **before**
consulting mutable peer mappings. Successful admission freezes resolved account
IDs before returning durable payment state.

The current worker polls every 250 ms; SQL leases last 30 s, stale claims cannot
finish a payment, and retry delay is bounded exponential up to 60 s. It re-POSTs
the **same** payment/command on uncertainty. Timeout, capacity failure or peer
unavailability does not authorize COMPLETED, a new payment UUID or a refund.
Ledger GET `/v1/ledger/transfers/{id}` exists for authoritative reconciliation,
but this `HttpPaymentPeers` implementation does not call it: do not mislabel it
as an existing worker lookup path.

The #30 proposed contract keeps both ledger entries and outcome in one atomic
ledger transaction. It proposes `armoney.ledger.transfer.commands.v1`,
`armoney.ledger.transfer.results.v1` and `armoney.payment.outcomes.v1`; payment UUID is the
record key, event UUID/bytes remain stable across retransmission, and matching
requester/full payload must survive inbox/outbox dedupe. Payment orchestration
owns its Saga; no debit/credit split, cross-service database query or synchronous
gRPC chain replaces it. Quorum confirmation remains required before exposing a
confirmed ledger result. These are planned requirements, not current consumers.

## Assistant delegation and read-only tool calls

Implementation: [AssistantProxy.message/tool/send](../app-gateway/src/main/java/com/arman/bank/appgateway/AssistantProxy.java),
[AssistantDelegations](../app-gateway/src/main/java/com/arman/bank/appgateway/AssistantDelegations.java),
[GatewayBankReads](../ai-assistant-service/Adapters/GatewayBankReads.cs),
[assistant Program](../ai-assistant-service/Program.cs),
[assistant OpenAPI](../ai-assistant-service/openapi.yaml).
Owners: gateway_owner and ai_assistant_owner, with wallet/payment owners retaining
their underlying data contracts.

| Caller -> destination | Actual request | Trust, wire and bounds | Classification |
| --- | --- | --- | --- |
| Gateway `message` -> assistant | POST `/v1/assistant/messages`, `{message}` | `ASSISTANT_URL`; `X-Gateway-Assistant-Key` plus opaque `X-Assistant-Delegation`; no user bearer reaches assistant. Gateway outer budget 35 s, request/wait at most 30 s remaining; 32,768-byte response cap. Assistant request limit 4,096 bytes and turn cancellation after 30 s; 32,768-byte serialized reply cap. | Synchronous bounded turn/delegation candidate; no financial writes or proposed business topic. |
| Assistant `WalletIds` -> gateway | GET `/v1/internal/assistant/wallets` | `GATEWAY_URL`, `X-Assistant-Gateway-Key`, delegation handle; no Authorization header; at most ten validated wallet IDs. | Synchronous read candidate, preserving gateway authorization. |
| Assistant `Balance` -> gateway | GET `/v1/internal/assistant/wallets/{id}/balance` | Same credential/handle; validates requested wallet ID, AED and nonnegative integer fils. | Synchronous read candidate, not cached model balance authority. |
| Assistant `History` -> gateway | GET `/v1/internal/assistant/payments/page?limit=50` | Same credential/handle; validates at most 50 payments, UUID/currency/amount/direction/status; incoming entries must be completed. | Synchronous read candidate. |
| Gateway `tool` -> wallet/payment | GET `/v1/wallets`, GET `/v1/wallets/{id}/balance`, GET `/v1/payments/page?limit=50` | Gateway revalidates the turn owner, supplies normal trusted service/owner headers and returns a projected read-only response. Request at most 8 s remaining, wait at most 9 s, upstream response cap 131,072 bytes. | Preserve current read boundaries; no assistant direct database or ledger access. |

Assistant callbacks use a 10 s HttpClient timeout, caller cancellation, streamed
65,536-byte response cap and disabled redirects. Delegation is random, turn-local,
removed at completion, deadline-bound and limited to eight callback acquisitions;
it is not a durable credential or restart-safe workflow ID. Gateway uses a 2 s
HTTP connect timeout, four concurrent turns and sixteen concurrent tool reads;
the assistant itself admits four turns. Gateway revalidates the session again before releasing the final
answer. No automatic replay of an expired turn or mutation retry is implemented.

## Notification delivery and external adapters

[MailtrapDelivery.send](../payment-service/src/main/java/com/arman/bank/paymentservice/infrastructure/MailtrapDelivery.java),
[BoundedEmailHttp.exchange](../payment-service/src/main/java/com/arman/bank/paymentservice/infrastructure/BoundedEmailHttp.java),
[MailtrapSmtp](../payment-service/src/main/java/com/arman/bank/paymentservice/infrastructure/MailtrapSmtp.java),
[EmailWorker](../payment-service/src/main/java/com/arman/bank/paymentservice/application/EmailWorker.java)
and [email contract](email-notifications.md) own the existing external boundary.
The following payment email/contact and optional profile-name calls are present
in inspected main revision `af1f7975a4ef888000c88e88fbd83ade919b1776`.

### Payment recipient email lookup

| Caller / destination owner | Actual method and route | Trust and response contract | Bounds, retries and classification |
| --- | --- | --- | --- |
| payment_owner `MailtrapDelivery.recipient(UUID owner)` -> auth_owner | GET `AUTH_BASE_URL` + `/v1/internal/identities/{owner}/email`; no request body | `AUTH_BASE_URL` is the validated configured auth origin in [EmailConfiguration](../payment-service/src/main/java/com/arman/bank/paymentservice/infrastructure/EmailConfiguration.java), not a client-selected destination. `owner` comes from the claimed email outbox row's `ownerId`, not a client-selected email recipient. Only `X-Service-Key` is supplied. HTTP 200 requires a JSON object containing `email` (null or a validated textual email) and boolean `verified`; auth remains authoritative for both. | Request timeout 5 s; [BoundedEmailHttp.exchange](../payment-service/src/main/java/com/arman/bank/paymentservice/infrastructure/BoundedEmailHttp.java) uses a 2 s connect timeout, 6 s future wait, 8,192-byte streamed response cap and no redirects. Future private synchronous RPC candidate only, preserving auth contact authority and all failure/freeze semantics; not a new Kafka contact event. |

The exact caller is [MailtrapDelivery.recipient](../payment-service/src/main/java/com/arman/bank/paymentservice/infrastructure/MailtrapDelivery.java).
[EmailWorker.tick](../payment-service/src/main/java/com/arman/bank/paymentservice/application/EmailWorker.java)
passes the persisted owner only when a destination has not yet been frozen.
HTTP 404 returns a missing recipient (`null`, unverified); HTTP 200 with a null
email is also missing. The worker records `SKIPPED/missing_email`. Other HTTP
statuses, transport failures and malformed response fields throw and follow the
existing `dependency_unavailable` durable retry path; they do not establish a
missing recipient or authorize another address. In sending mode, an unverified
address is separately `SKIPPED/unverified_email`. A successful lookup freezes
email, verification and optional display name (including a null fallback) with
the outbox lease before provider send, via
[PostgresEmailOutbox.freezeRecipient](../payment-service/src/main/java/com/arman/bank/paymentservice/infrastructure/PostgresEmailOutbox.java).
Retries reuse that first frozen snapshot instead of rediscovering the address or
name. The API provider `to` entry contains only `email`; the name is used in the
rendered greeting, not supplied as a recipient address/name field. Auth's
[incoming contact-boundary inventory](auth-transport-inventory.md#public-oidc-and-auth-boundaries-keep-http)
is maintained separately; this entry records the payment-owned outbound edge.

### Payment optional profile greeting lookup

| Caller / destination owner | Actual method and route | Trust and response contract | Bounds, retries and classification |
| --- | --- | --- | --- |
| payment_owner `MailtrapDelivery.displayName(UUID owner)` -> user_owner | GET `USER_BASE_URL` + `/v1/users/me`; no request body | `USER_BASE_URL` is a validated configured origin in [EmailConfiguration](../payment-service/src/main/java/com/arman/bank/paymentservice/infrastructure/EmailConfiguration.java). Sends `X-Service-Key` and `X-Identity-Id` from the claimed outbox owner. HTTP 200 must contain an object with textual `identity_id` matching that owner UUID (case-insensitive textual comparison) and textual `display_name`. The stripped name must be nonempty, at most 100 Unicode code points and contain no ISO control characters. | Request timeout 3 s; shared `BoundedEmailHttp` uses 2 s connect timeout, 6 s future wait, 8,192-byte streamed response cap and no redirects. Optional synchronous profile-read candidate only; no Kafka profile topic or gRPC rollout authorized. |

`recipient` calls this lookup only after auth returns a valid non-null email.
Non-200, mismatched/invalid profile data and other lookup failures yield no name;
[PaymentEmail.render](../payment-service/src/main/java/com/arman/bank/paymentservice/infrastructure/PaymentEmail.java)
uses `Hello,` as the fallback. Interruption is preserved and propagated, rather
than converted into a greeting fallback. This is a profile display name, not a
verified or legal name. The lookup cannot replace auth's email authority or
redirect delivery to a profile-provided address.

Mailtrap HTTPS sends configured sandbox/sending JSON to fixed provider origins,
using sandbox API token or sending bearer credentials. HTTP request timeout is
5 s, connect timeout 2 s, future wait 6 s, response cap 8,192 bytes; redirects are
disabled. SMTP is sandbox-only, fixed Mailtrap hostname/allowed ports, required
STARTTLS and hostname verification; connection/read/write timeouts are 5 s and
attempt deadline 15 s. API 429 is rate-limited, 408/5xx transient, other non-200
permanent; SMTP adapter classifies provider replies without exposing provider
text. The email worker runs every second with its own SQL outbox, 30 s leases,
at most eight attempts, bounded `min(3600, 15 << attempts)` retry delay and
frozen destination/mode.
Accepted SMTP/API delivery followed by a crash can duplicate mail; it is not
exactly-once mailbox delivery. Provider failure never changes financial outcome.
This provider adapter is **not** an internal gRPC migration or an automatically
authorized Kafka email consumer.

Assistant external [OpenAiProvider](../ai-assistant-service/Adapters/OpenAiProvider.cs)
and [OpenAiEmbeddings](../ai-assistant-service/Adapters/OpenAiEmbeddings.cs) use fixed
HTTPS Responses/embeddings endpoints and provider bearer credentials. Program
sets a 25 s provider HttpClient timeout, turn cancellation, no redirects;
Responses cap is 65,536 bytes, embeddings cap 131,072 bytes. Cost/model/input
guards apply. These optional external calls are not internal service discovery,
Kafka events or new provider authorization. No live provider request was made.

## Readiness, data connections and discovery dependency

Current discovery is configured origins plus normal DNS (Compose service names
in local configuration), not an implemented registry or gRPC resolver. Required
environment variables are bound in each service `Main` and assistant `Program`.
Do not invent TTLs, failover targets, TLS identities, retry policies or circuit
breakers. #34 must define endpoint ownership, health vs discovery semantics,
resolver lifecycle and failure behavior before a migration can rely on them.

- Java [ServiceRuntime](../platform-runtime/src/main/java/com/arman/bank/runtime/ServiceRuntime.java)
  exposes `/health/live` and `/health/ready`; readiness uses the service's passed
  predicate/database checks, not universal peer reachability. Gateway readiness
  must not be presented as auth, ledger, Kafka or provider workflow readiness.
- User [ProfileShards](../user-service/src/main/java/com/arman/bank/userservice/infrastructure/ProfileShards.java)
  and [PostgresProfiles](../user-service/src/main/java/com/arman/bank/userservice/infrastructure/PostgresProfiles.java)
  use owner-service JDBC connections to configured primary/profile shards and
  pinned placement. No outbound service HTTP client was found in user-service.
  This is not an authorization to query peer databases or replace SQL with gRPC.
- Ledger [PostgresLedger](../ledger-service/src/main/java/com/arman/bank/ledgerservice/infrastructure/PostgresLedger.java)
  and [LedgerReads](../ledger-service/src/main/java/com/arman/bank/ledgerservice/infrastructure/LedgerReads.java)
  own primary posting and configured standby reads/confirmation. No outbound
  service HTTP client was found in ledger-service. Physical database replication
  and confirmation are PostgreSQL behavior, not RPC to another financial owner.
- Assistant readiness checks configuration and active policy-index readiness,
  explicitly reporting provider evaluation as not verified. Npgsql policy access
  uses the assistant's own read-only data source. Operator ingestion uses a
  separate credential/process; it is not a runtime write tool or cross-bank DB
  connection. HTTP health probes are operational reads, not business commands.

## Decisions and acceptance still required

This fulfills inventory/classification preparation only. #25 is not complete.
Before protocol code, #30 must settle which private synchronous boundaries, if
any, gain gRPC, freeze versioned contracts/status mapping/deadlines/size limits,
and preserve owner propagation, requester keys and unknown-outcome handling.
#34 must provide reviewed discovery and identity/health behavior. Security and
financial reviewers must independently verify trust and same-ID replay. Proposed
Saga migrations require append-only schemas, transactional outbox/inbox behavior,
actual duplicate/restart/rollback tests and replication-confirmed terminal facts.
No protobuf/runtime code, schema, public API or deployment changed here. The
orchestrator should link this inventory from the maintained architecture map
without changing implemented/planned labels prematurely.
