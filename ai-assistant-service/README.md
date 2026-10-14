# ArMoney assistant service

C#/.NET 10 service-local stack exception. Framework-free domain records, application ports and typed in-process router/policy/bank specialists separate HTTP, PostgreSQL and OpenAI adapters. No other service classes or database are referenced. Initial corpus is owner-approved **demonstration** ArMoney behavior from main `112b01d90c247bd774db7fa9f9720edcc0b439f8`, not production/legal bank policy.

## Trust and bounds

Private `POST /v1/assistant/messages` accepts only `{ "message": "..." }`, gateway direction key and opaque request-local delegation handle. Original bank bearer and global service key are forbidden. Initial and final wallet callbacks authenticate the retained session; all callback paths are fixed. Gateway derives canonical transaction direction and revalidates every read and final public return. No public ingestion, conversation state, uploads, mutation tools, arbitrary URL/SQL/shell or transfer execution.

Questions/documents are untrusted data. Structured planner selects only policy/balances/last_transaction/unsupported plus a typed finite policy intent: currency support, conversion, fils ratio, balance authority, payment completion, concurrent spending, sender visibility, recipient visibility, history order, conversation storage, provider retention or unsupported policy. Server-owned maps derive a generic intent-specific question and finite retrieval phrase; neither contains personal text. Refinements are validated and mapped to finite policy facets. Only trusted facet phrases are embedded, never arbitrary model-produced names or the original personal question. Retrieved text cannot expand tools. Policy selectors emit only outcome, verified retrieved citation IDs and optional bounded refinement. The server filters evidence to the intent's approved demonstration section and displays exact cited excerpts with fixed demonstration framing; free generated factual prose is never displayed. Insufficient/conflicting wording is fixed. Unsupported policy intents return insufficient evidence without unrelated quotes. Financial values/status/time come exclusively from typed callbacks and are rendered using integer fils. Independent actual model grading must verify intent fidelity and relevance, in addition to source identity. This intentionally narrow demonstration corpus requires explicit typed-intent/section extensions for future policy categories; it is not a general arbitrary-document answering system.

Each turn allows four provider handoff steps, two refinements, six chunks/6,000 characters, 2,000 input Unicode codepoints and 4,096 UTF-8 bytes (entire JSON HTTP body also capped4096 bytes), 1,000 total provider output tokens and 30 seconds. Unknown prices fail closed. Provider token upper bounds include UTF-8 bytes plus 4,096 instruction/JSON overhead, output maximum and embeddings; reservations accumulate per turn. Service concurrency is four. Per-owner rate limits belong to gateway, which retains authoritative owner identity. Eight callback calls permit six balance reads plus initial/final authentication. More than six wallets yields unavailable instead of partial totals. No provider/backend error becomes a zero balance or completed payment. History is first page limit50, newest created_at then ID. Replies explicitly label default newest visible history regardless of completion. Filtered completed/settled/status/date-range/monthly/wallet history is outside this slice; planner refuses it and a deterministic guard rejects common English/Russian filter terms and numeric filters. The language guard is deliberately conservative and does not establish intent recognition for every possible phrasing. Wallet-specific or exhaustive searches remain unsupported.

Maximum response: 32 KiB, reply8,000 characters, six citations with excerpt1,000, three provenance records, ten wallet facts. Responses use exact public snake_case schema. Generic errors are `{ "error": "..." }` at 400/401/413/429/503. No automatic HTTP/provider/Npgsql logging is enabled. `/health/live` proves process liveness. `/health/ready` checks credentials/prices/DB/effective active index and hybrid embedding completeness, never actual provider access or answer quality; `provider_evaluation` remains `not_verified`.

## Configuration

| Variable | Meaning / default |
|---|---|
| `ASSISTANT_DATABASE_URL` | Required Npgsql runtime connection string using read-only LOGIN member of assistant_reader |
| `ASSISTANT_INGESTION_DATABASE_URL` | Operator-only migration/ingestion credential; never supplied to runtime |
| `GATEWAY_URL` | Required fixed gateway HTTP(S) origin |
| `GATEWAY_ASSISTANT_KEY` | Required gateway to assistant key, at least32 characters |
| `ASSISTANT_GATEWAY_KEY` | Required distinct assistant to gateway key, at least32 characters |
| `OPENAI_API_KEY` | Local secret; never commit or print |
| `OPENAI_MODEL`, `OPENAI_PRICE_MODEL` | Required identical pinned provider/pricing model IDs |
| `OPENAI_INPUT_USD_PER_MILLION`, `OPENAI_OUTPUT_USD_PER_MILLION` | Required positive decimal pinned model prices |
| `ASSISTANT_MAX_REQUEST_USD` | Required positive per-turn ceiling; suggested0.02 |
| `ASSISTANT_RETRIEVAL_MODE` | Runtime lexical by default; hybrid explicitly enables exact cosine+RRF. Operator activate/rollback require explicit lexical or hybrid, with no default |
| `OPENAI_EMBEDDING_USD_PER_MILLION` | Required positive embedding price for hybrid or operator embed |
| `ASSISTANT_EVAL_MAX_USD` | Required positive synthetic provider evaluation/embedding budget |
| `ASSISTANT_TEST_DATABASE_URL` | Mandatory disposable test DB for full deterministic test executable |

Embedding model is fixed `text-embedding-3-small`, dimension1536. Full-text baseline uses PostgreSQL simple tokenizer and GIN; hybrid uses exact vector scans and reciprocal-rank fusion with constant60. No approximate-index recall claim. Retrieval filters active/effective versions before ranking. Runtime config must select lexical or hybrid deliberately; quality improvement remains a measured hypothesis.

## Operator workflow

Operator actions are CLI-only. Documents are local UTF-8 Markdown/text, max1MiB, confined to manifest directory, no symlinks at any path ancestor or remote sources. Mount the policy directory read-only. Manifest includes logical ID, version, source provenance, effective UTC interval, superseded version, index version and relative file. Identifiers use ASCII letters/digits/underscore/dot/hyphen. Chunker `heading-1000-overlap100-v1` preserves headings, unique section/chunk ordinals and bounded overlap. Metadata stays with each document join. SHA-256 covers source bytes. Same version/digest/metadata is idempotent; changed content or metadata conflicts. A document and all chunks stage atomically before activation. V003 records a permanent publication seal: both active and previously published inactive indexes reject new documents/versions and embedding changes. Identical published reingestion remains safe because it changes nothing. Stage, embedding writes and activation serialize using the same per-index PostgreSQL advisory transaction lock; model embedding requests occur before the locked write transaction, which rechecks the seal. Activation/rollback require explicit operator retrieval mode. Hybrid activation validates every chunk has a pinned1536-dimensional embedding before sealing or switching the pointer; failures retain the prior healthy index. Lexical activation explicitly permits lexical-only corpora. Runtime hybrid readiness independently rejects missing embeddings. Activation/rollback switch only the singleton pointer; published documents/indexes remain intact. Use new document and index versions for modifications or for adding embeddings to a previously published lexical index.

```sh
dotnet Assistant.dll operator migrate
dotnet Assistant.dll operator stage /policies/demo.json
# Optional before activation; external approved policy-only embedding call:
dotnet Assistant.dll operator embed demo-main-112b01d-ft-v1
# Set ASSISTANT_RETRIEVAL_MODE explicitly to lexical or hybrid for both commands:
dotnet Assistant.dll operator activate demo-main-112b01d-ft-v1
dotnet Assistant.dll operator rollback <previous-index>
```

SQL migrations are append-only with recorded SHA-256. Runtime never migrates or ingests. Admin provisions credentials separately; migration grants only SELECT to assistant_reader. Migration V002 requires pgvector PostgreSQL; V003 backfills the current active index seal and records future publications permanently. The serialized orchestrator operates Docker and deployment; service owners never operate the user's persistent stack.

## Checks and evidence

```sh
dotnet build Assistant.csproj -c Release
dotnet run --project tests/Assistant.Tests.csproj -c Release
dotnet run --project evaluations/Assistant.Evaluation.csproj -c Release
```

Tests require an explicitly disposable PostgreSQL connection and fail if absent; transport/mock tests are not provider quality evidence. Actual evaluation fixture/report procedure is in evaluations/README.md. Repository Gradle checks, Android checks, isolated Compose smoke and independent QA/security/review remain integration gates. Do not mark delivery complete on health or mocked tests alone.

OpenAI is external even when application/index are local. Responses use `store=false`, no server conversation objects. It does not imply zero retention: official [data controls](https://developers.openai.com/api/docs/guides/your-data) describe abuse monitoring retention up to30 days, subject to account controls. No zero-retention account setup is claimed. Source APIs: [Responses structured output](https://developers.openai.com/api/docs/guides/structured-outputs), [.NET support](https://learn.microsoft.com/en-us/dotnet/core/releases-and-support), [Npgsql10](https://www.npgsql.org/doc/release-notes/10.0.html), [pgvector exact retrieval](https://github.com/pgvector/pgvector).
