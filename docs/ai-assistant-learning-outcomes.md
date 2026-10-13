# Banking assistant learning-outcome evidence

Status: verified credential-page descriptions mapped to planned work. No runtime
feature, test pass or full course coverage is claimed. Read on 2026-10-13 using
owner-supplied OpenAI Academy links; private links, access keys and recipient
details are intentionally excluded. Detailed syllabi remain unavailable.

| Verified credential | Visible learning outcomes (paraphrased) | Planned application / evidence | Acceptance |
| --- | --- | --- | --- |
| Build with Retrieval-Augmented Generation | Retrieval concepts, pipeline design, context relevance/completeness, retrieval evaluation and diagnosing weak answers | Local policy ingestion/chunks, full-text/vector experiment, ranked sources, labeled synthetic query set, diagnosis of misses | Recall@5 >= 0.90 on labeled answerable questions; explicit insufficient-evidence behavior on all unanswerable fixtures; measure baseline versus hybrid rather than assert improvement |
| Design and Build Agentic Systems | Role design, tool boundaries, routing, owned handoffs and outcome/safety testing | Router, constrained policy specialist and bank-read specialist with typed handoffs and shared budgets; Codex development owners are distinct from runtime components | Correct specialist/tool choice >= 0.95 on labeled fixtures; zero unauthorized tool execution; all loop-budget and owner-isolation cases pass |
| Scope AI Solutions | Workflow opportunity discovery, value/requirements comparison, prioritization, problem framing and scope | Mission problem statement and prioritized policy/balance/last-transaction scenarios; compare structured app flow with assistant value | Reviewer traces each use case to user value and acceptance; mutation/credit/investment scope stays excluded; record measured latency/cost and limitations |
| AI Foundations | AI/LLM basics, clear instructions, context, output review and responsible use | Explicit prompts, separated untrusted context, citation checks, independent output evaluation, minimized provider data | Citation identifier validity 100%; supported policy claims >= 0.95 by independent human grading; refusal/outage fixtures all pass; no personal or credential content in traces |

Thresholds are proposed release gates, not results. Fix a versioned fixture set and
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
