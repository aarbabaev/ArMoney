# Assistant HTTP and trust contract

Status: frozen implementation target for issue #43; integration and deployment
evidence must be read separately. Public contract belongs to gateway OpenAPI;
this document records the private delegated-read boundary.

## Message

`POST /v1/assistant/messages`, one current ArMoney bearer session,
`Content-Type: application/json`, exact `{"message":"..."}` object. No owner,
conversation, history or tool parameters. Reject unknown/duplicate fields,
blank input, over 2,000 Unicode code points or 4,096 UTF-8 bytes.
Stateless turns; no server transcript persistence or automatic POST retry.

Success: `reply`, `outcome`, `citations`, `facts`, `provenance` only.
Outcome: `answered`, `insufficient_evidence`, `empty_history`, `conflicting_evidence`.
Citations contain `document_id`, `version`, `section`, `excerpt` from retrieved
demonstration documents. Facts contain nullable `wallets` and `last_transaction`.
Each wallet: `wallet_id`, `currency: AED`, integer Int64 `balance_minor`.
Last transaction: `id`, `created_at`, `currency: AED`, positive integer Int64
`amount_minor`, `direction: incoming|outgoing`, actual `status`:
`PENDING|COMPLETED|REJECTED`. Provenance: `source`, `fetched_at`, `scope`.
Sources: `wallet-service`, `payment-service`, `policy-index`.
Amounts are integer fils; times are UTC/offset instants displayed in Asia/Dubai.
The whole response is at most 32,768 bytes, reply 8,000 code points, six citations
and three provenance entries. Citation limits: ID128/version64/section256/excerpt1000;
scope128. Financial facts are independently checked against gateway's observed
delegated-read results; policy text remains subject to semantic grounding evaluation.

Errors: exact `{"error":"..."}`; 400 `invalid_request`, 401 `invalid_credentials`,
413 `request_too_large`, 429 `server_busy`, 503 `service_unavailable`.
All responses: `Cache-Control: no-store`. Gateway deadline35s, service30s,
Android40s. Session revalidation happens before reads and before final return.

## Read-only delegation

Gateway alone retains the original bank bearer and verified owner, creating a
cryptographically random request-bound opaque handle. Assistant receives only
`X-Assistant-Delegation` and `X-Gateway-Assistant-Key`. For callbacks it presents
the handle and independent `X-Assistant-Gateway-Key`; credentials in one direction
are not accepted in the other. No global internal bank key reaches the assistant.
Handle expires within the active turn, permits at most eight callbacks, is
deleted on completion/error/cancellation, and is never stored or sent to OpenAI.
Restart invalidates handles. A service credential alone conveys no owner authority.

Only these private GET paths exist, with empty request bodies:

| Gateway route | Owner-scoped upstream / projection |
| --- | --- |
| `/v1/internal/assistant/wallets` | Wallet list: `wallets` containing `id,currency,status,provisioning_status`; no owner/ledger identifiers |
| `/v1/internal/assistant/wallets/{id}/balance` | Wallet balance: `wallet_id,currency,balance_minor` |
| `/v1/internal/assistant/payments/page?limit=50` | Payments page: `payments` containing `id,created_at,currency,amount_minor,direction,status`, plus nullable `next_cursor`; direction derived by gateway's verified owner |

No arbitrary method/path/URL/query/owner parameter, transfers, wallet/profile
mutations, recipient discovery or ledger route. Private routes require both
handle and callback key and cannot be used with a public bearer alone.
Gateway revalidates the retained session on every read, requiring the same owner,
then reconstructs its own upstream identity/service headers. Caller forged
headers do not participate. Cursor traversal beyond the fixed first page is not
available in this slice; answers must not claim complete monthly/history totals.

"Last transaction" is the first visible row across the owner's wallets by
`created_at DESC, id DESC`, not completion time. Sender sees all own statuses;
recipient sees only completed incoming payments. Empty history is explicit;
outage never becomes zero balance or invented/completed payment. Display facts
separately from policy citations, with source/fetch time and bounded scope.

Original-bearer forwarding was rejected in independent design finding
AI-SEC-DESIGN-01 because it could be replayed against existing mutation routes.
The opaque handle design still requires integrated negative-path tests before
the finding can be independently marked verified.
