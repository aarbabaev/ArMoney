# Local banking assistant operations

PR #46 delivered the first feature slice; local rollout of revision `ce5175bb`
was verified on 2026-10-13 after green CI and independent review. The dated
[release evidence](ai-assistant-learning-outcomes.md) distinguishes deployed
readiness from disposable authenticated acceptance and unrun device checks.
These commands alone are not deployment evidence. Future changes require fresh
revision, readiness and acceptance verification before marking work Done.

## Private settings and provider scope

Keep settings in local `.env.assistant` (ignored by `.env.*`), never in source,
issues, screenshots or Android. `OPENAI_API_KEY` needs Responses creation and
embeddings requests only; use a separate project/key with restricted permissions.
Hosted file storage, project administration and payment operations are unnecessary.
The selected pinned model is `gpt-4.1-mini-2025-04-14`; embeddings use
`text-embedding-3-small`, 1,536 dimensions. Model access and billing must be available.
Pricing verified 2026-10-13: model input $0.40/output $1.60 per million tokens;
embeddings $0.02 per million. Reverify prices before changing models; the independent
`OPENAI_PRICE_MODEL` must match `OPENAI_MODEL` or configuration fails closed.

Generate independent `GATEWAY_ASSISTANT_KEY`, `ASSISTANT_GATEWAY_KEY`,
`ASSISTANT_DB_PASSWORD` and `ASSISTANT_RUNTIME_DB_PASSWORD`. The runtime does not
receive the original bank bearer, bank internal key or ingestion database password.
The gateway URL is fixed configuration, never chosen by user/model arguments.
Private settings are read locally; diagnostics must not print resolved Compose
configuration, keys, connection strings, provider bodies or personal messages.

Default per-turn cap is $0.02, four agent steps, two retrieval refinements,
1,000 total generated tokens, 30 seconds and eight delegated reads. These are
upper bounds, not latency/quality promises. Gateway admits four simultaneous
turns and 10 turns/minute per authenticated owner; limits reset on restart.
Actual-provider synthetic acceptance uses four turns ($0.08 ceiling at the
configured per-turn cap), with ingestion and labeled model-evaluation runs
budgeted separately. Do not treat project budget notifications as a hard API cap.

Local Docker hosts the application and policy store/index. Model and embedding
execution is external OpenAI access, authorized for minimal excerpts/facts by the
owner. Use `store=false` and no server transcript persistence. This is not Zero
Data Retention: default provider abuse monitoring may retain content for up to
30 days, subject to actual account controls and legal exceptions. See
[official provider data controls](https://developers.openai.com/api/docs/guides/your-data).

## Start and ingest approved demonstration rules

Use base Compose plus `compose.assistant.yaml`; include any existing authorized
SSO/shard/replica overlays when operating the persistent stack. Keep compatibility
project and old volumes intact. Only bank_orchestrator operates that stack.

```powershell
docker compose --env-file .env --env-file .env.assistant -f compose.yaml -f compose.assistant.yaml config --quiet
docker compose --env-file .env --env-file .env.assistant -f compose.yaml -f compose.assistant.yaml up -d assistant-db
docker compose --env-file .env --env-file .env.assistant -f compose.yaml -f compose.assistant.yaml run --rm assistant-ingestion operator migrate
docker compose --env-file .env --env-file .env.assistant -f compose.yaml -f compose.assistant.yaml run --rm assistant-ingestion operator stage /policies/demo.json
docker compose --env-file .env --env-file .env.assistant -f compose.yaml -f compose.assistant.yaml run --rm assistant-ingestion operator embed demo-main-112b01d-ft-v1
docker compose --env-file .env --env-file .env.assistant -f compose.yaml -f compose.assistant.yaml run --rm assistant-ingestion operator activate demo-main-112b01d-ft-v1
docker compose --env-file .env --env-file .env.assistant -f compose.yaml -f compose.assistant.yaml up --build -d ai-assistant-service app-gateway
```

Ingestion is a locally authorized operator container/CLI with its own DB
credential and read-only policy mount, not an ordinary-user HTTP upload. Only
UTF-8 Markdown/text documents and bounded manifests are supported; no PDFs,
remote URLs or arbitrary archive parsing. Demonstration documents are owner-selected
rules derived from implemented main revision `112b01d`; they are explicitly not
legal/production bank policies. Test fixtures do not silently become bank rules.

Staging preserves source/version/effective dates/provenance/digest/chunks/index
version. Reingestion of identical content is idempotent; changed content under
the same published version is a conflict. Embed/index outside activation;
activate only after staging succeeds. Roll back by activating a previous retained
index with `operator rollback <index-version>`; do not rewrite published records.
Published indexes remain permanently sealed after rollback. New documents and
embedding changes require a new document/index version. Activation and rollback
require explicit `ASSISTANT_RETRIEVAL_MODE`; hybrid rejects incomplete pinned
embeddings before changing the pointer. Per-index PostgreSQL transaction locks
serialize publication and operator writes. V003 backfills the current active
index; older deployments must inventory and seal previously retired indexes.
This feature's first rollout has no preexisting assistant corpus.
No command deletes financial data or funds a wallet. Preserve assistant DB volume;
do not use `down --volumes` on the user stack.

Database initialization creates a separate runtime login with membership in a
NOLOGIN reader group. Migration grants SELECT only on policy tables. The runtime
cannot ingest or migrate. Schema/role setup must be tested on a fresh DB and an
existing populated assistant DB; initialization scripts run only on first creation.
Credentials/rotation and backups are local operator duties; never copy database
payloads into Git. Bank databases remain outside the assistant's ownership.

## Retrieval and answer behavior

Hybrid mode combines lexical ranking with exact pgvector cosine ranking and
reciprocal rank fusion over active/effective chunks. Lexical mode is an explicit
baseline/diagnostic configuration; it is not evidence that embeddings improved
quality. Pin model/dimension/index/chunk versions; mixed vector versions cannot
be silently compared. No approximate index is required for this small corpus.

Public messages use the [frozen contract](ai-assistant-contract.md).
The planner selects a finite policy intent; application code supplies its fixed
search question. Bank facts never enter model context. Policy selection returns
validated citation identifiers, not free-form answer prose; application code
renders exact relevant source excerpts with a demonstration label. New policy
categories require deliberate intent/section extension. Russian questions can
route correctly while current demonstration excerpts remain English.

Financial
facts come from authenticated wallet/payment APIs and are rendered from validated
integer fils/time/direction/status, separate from cited policy explanations.
Last transaction means creation-order first visible payment across own wallets;
history is bounded to the first page of 50. Complete monthly spending or last
completed payment outside that window is not supported by this slice.
Empty history, conflicting/insufficient policies and outages are explicit.
Session expiry/revocation suppresses the final answer and clears Android chat.

## Verification and failure evidence

`/health/live` establishes process availability. `/health/ready` checks the
configured database, active index and required settings; it does not establish
OpenAI access or answer quality. Provider setup probes, measured evaluation and
real authenticated assistant acceptance are separate checks. Log only safe
outcomes/request IDs/durations/aggregate usage, never bodies or credentials.

Run real PostgreSQL checks in `compose.assistant-tests.yaml` with explicit project
`armoney-assistant-check`. No host ports, financial fixtures or user volumes are
shared. Full repository check/Compose smoke belong on the disposable CI daemon.
The cross-service smoke requires a primary-created isolation marker and refuses
the normal local gateway port 8080; never forge the marker or set CI=true to
bypass existing financial-fixture guards. Actual-provider acceptance is separate
from outage-only smoke and deterministic mocked-provider tests.

Required evidence: .NET persistence/provider/agent checks, Java full check,
Android JVM/lint/APK, disposable Compose acceptance, measured model/retrieval
evaluation, independent security/QA/reviewer and exact-head green CI. Preserve
pending/unavailable checks explicitly. No emulator run or physical-device pass
is implied by an APK build. See [mission](agents/missions/ai-assistant.md),
[learning-outcome gates](ai-assistant-learning-outcomes.md) and
[delivery workflow](agents/workflow.md).
