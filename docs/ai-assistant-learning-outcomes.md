# Banking assistant learning-outcome evidence

Status: implemented first feature slice with measured evidence, green required
CI, independent review and verified local rollout. No full course coverage is claimed.
Credential descriptions read on 2026-10-13 using
owner-supplied OpenAI Academy links; private links, access keys and recipient
details are intentionally excluded. Detailed syllabi remain unavailable.

| Verified credential | Visible learning outcomes (paraphrased) | Planned application / evidence | Acceptance |
| --- | --- | --- | --- |
| Build with Retrieval-Augmented Generation | Retrieval concepts, pipeline design, context relevance/completeness, retrieval evaluation and diagnosing weak answers | Local policy ingestion/chunks, full-text/vector experiment, ranked sources, labeled synthetic query set, diagnosis of misses | Recall@5 >= 0.90 on labeled answerable questions; explicit insufficient-evidence behavior on all unanswerable fixtures; measure baseline versus hybrid rather than assert improvement |
| Design and Build Agentic Systems | Role design, tool boundaries, routing, owned handoffs and outcome/safety testing | Router, constrained policy specialist and bank-read specialist with typed handoffs and shared budgets; Codex development owners are distinct from runtime components | Correct specialist/tool choice >= 0.95 on labeled fixtures; zero unauthorized tool execution; all loop-budget and owner-isolation cases pass |
| Scope AI Solutions | Workflow opportunity discovery, value/requirements comparison, prioritization, problem framing and scope | Mission problem statement and prioritized policy/balance/last-transaction scenarios; compare structured app flow with assistant value | Reviewer traces each use case to user value and acceptance; mutation/credit/investment scope stays excluded; record measured latency/cost and limitations |
| AI Foundations | AI/LLM basics, clear instructions, context, output review and responsible use | Explicit prompts, separated untrusted context, citation checks, independent output evaluation, minimized provider data | Citation identifier validity 100%; supported policy claims >= 0.95 by independent human grading; refusal/outage fixtures all pass; no personal or credential content in traces |

Thresholds are release gates. Fix a versioned fixture set and
grading rubric before running evaluation; report model/version, prompt/index
version, configuration, sample count and observed metrics. Small synthetic sets
do not establish production safety. Machine validation of a citation ID does not
prove semantic entailment; grade claims against cited excerpts independently.

Runtime specialists may be in-process use cases behind ports; multiple services,
autonomous transfers or an unrelated SDK are unnecessary to demonstrate roles
and handoffs. Human ChatGPT workplace practice is documented as prompt/context
design and review, not a claim that this API service implements the ChatGPT UI.
Unseen syllabus outcomes must be reviewed if supplied later; they are not silently
declared covered. See [mission](agents/missions/ai-assistant.md).

## Measured local evidence, 2026-10-13

Synthetic evaluation `synthetic-v1-intent-extractive-extension` uses 22 fixed
questions (original 20 plus two semantic relevance probes), model
`gpt-4.1-mini-2025-04-14`, prompt `router-policy-v2-typed-intent-extractive`,
chunker `heading-1000-overlap100-v1`, index `demo-main-112b01d-ft-v1` and
`text-embedding-3-small`/1536 dimensions. Final run took 38,715 ms in aggregate;
the conservative configured cost upper bound was $0.22, not measured billing.
Routing and intent selection were 22/22; lexical Recall@5 was 11/14 (78.57%),
hybrid 14/14 (100%), citation validity 14/14, reported errors zero. The first
run failed because an absent savings-rate policy routed as an unrelated request;
general category instructions fixed this without changing fixtures or gates.

Independent QA graded 14/14 answered policy responses supported and relevant,
plus one correct insufficient-evidence policy response. Responses are exact
relevant demonstration excerpts, not generated policy prose. Repeated claims
are not independent samples. Three unsafe-action/SQL/URL fixtures establish
rejection routing only; four balance/history fixtures establish routing only.
Russian routing works in this set, but demonstration answers remain English.

Independent actual-provider Compose smoke separately passed 35 HTTP requests
and four assistant turns (configured $0.08 upper bound, ingestion excluded):
two fresh users' integer-zero balances matched real owner-scoped ledger APIs;
empty history, exact demo citations, spoofed owner fields, unauthorized private
reads and revoked sessions behaved as specified. No financial funding or mock
financial success was used. Nonempty transaction status/order, in-flight
revocation, real expiry, dependency fault injection and device UX are outside
that smoke's coverage; deterministic checks cover selected corresponding paths.

Local full Java check passed 506 tests with no failures/errors/skips; .NET passed
70 deterministic/isolated PostgreSQL assertions after the publication fix; Android passed 31 JVM tests,
lint and debug APK build. Independent security retested delegated read boundaries,
ingestion containment, fixed evaluation denominators and extractive grounding:
70 service assertions plus 30 gateway negative tests passed. Integrated review
also found active/retired policy indexes could be extended after publication;
permanent seals and serialized operator writes now reject that, independently
retested with real PostgreSQL including the activation/staging race. These local results
do not substitute for required exact-head CI or deployed readiness/acceptance.
## Verified release checkpoint

PR #46 merged as `ce5175bbf2d4183b16f76977ef6f7627c2ed5ee2` after all three jobs
and required acceptance steps in [CI #91](https://github.com/aarbabaev/ArMoney/actions/runs/38387669495)
passed on head `6a5bcfb6`. This includes assistant readiness/outage, profile
sharding, ledger physical replication and browser SSO checks. Independent review
verified all 64 published files against the reviewed source. Earlier failed
publication/guard/startup attempts were fixed and independently retested; they
are not rewritten as successful runs.

Local rollout on 2026-10-13 verified healthy runtime, a sealed active index with
four pinned 1536-dimensional embedded chunks, runtime SELECT with INSERT denied,
public unauthenticated HTTP 401 with no-store, and real pinned-model Responses
HTTP 200. No bank schema migration or financial funding was performed. The four
chunk corpus makes hybrid Recall@5 a pipeline-completeness check, not evidence
of ranking quality at larger scale. Authenticated positive model/bank acceptance
ran independently in a matching disposable stack. Live-owner messages and
physical-device UX were not run. Issue #43 was closed as Done for this documented
scope; the Project status update could not be confirmed because browser tooling
was unavailable.

