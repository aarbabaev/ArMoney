# Banking AI assistant mission

Status: feature source implemented; local focused checks passed. Integrated
Compose/model evaluation, independent retesting, exact-head CI and deployment
remain pending. This is not a completed or deployed banking feature.
Tracking: [issue #43](https://github.com/aarbabaev/ArMoney/issues/43),
[delivery board](https://github.com/users/aarbabaev/projects/1).
Remote source baseline: main `112b01d90c247bd774db7fa9f9720edcc0b439f8`.
Connector-backed feature branch: `feat/banking-ai-assistant`; PR base: `main`.
The local folder is a source snapshot without Git and has differences from main.
Publish only an explicit changed-file manifest against the verified remote base.

## Authorization and requirement evidence

On 2026-10-13 the owner authorized implementation once requirements are resolved,
then supplied four OpenAI Academy credential links and permitted minimal policy
excerpts and personal banking facts in OpenAI API requests. Credential titles,
descriptions and skill lists were read from their pages. Detailed course syllabi
were not supplied; the outcome mapping covers the visible descriptions and skills,
not unseen course content. Private credential keys/links are excluded from Git.
See [outcome matrix](../../ai-assistant-learning-outcomes.md).
The owner also selected clearly labeled demonstration rules derived from
implemented ArMoney behavior as the initial corpus; no legal bank policy is implied.

Two original requirement questions are resolved at the visible-outcome level.
Before provider-dependent work, freeze the contract and obtain the independent
security design handoff. Actual model evaluation also needs a locally configured
API key, supported model access and a synthetic evaluation budget; never ask for
the key in public issues or copy it to Android. This is an environment dependency,
not a request to repeat the owner's data-egress consent.

## Problem and priorities

Users need concise answers grounded in current bank rules and their own live
wallet/payment facts. Existing dashboards answer structured balance/history
questions reliably; the assistant adds natural-language explanation and cited
policy discovery. Prioritize policy questions, current wallet balances and the
last visible transaction. Transfer execution, credit decisions, investment advice,
arbitrary uploads and autonomous financial actions are outside this slice.
Value is measured by retrieval relevance, supported answers, correct tool choice
and fewer unsupported claims, not by adding agent complexity.

## Work board and file leases

| ID | Role | Writable scope | Dependency / next milestone | State |
| --- | --- | --- | --- | --- |
| AI-PLAN | bank_orchestrator | Shared docs, role/config, CI/Compose; publication | Integrated checks and truthful evidence | Active integration |
| AI-API-DESIGN | gateway_owner | app-gateway/** | Frozen delegation/message contract; 58 unit tests passed | Owner handoff ready; integration pending |
| AI-SEC-DESIGN | security_auditor | None | Original-bearer design rejected; opaque delegation inspected | Independent runtime retest passed; exact-head review pending |
| AI-SERVICE | ai_assistant_owner | ai-assistant-service/** including tests | 70 deterministic/real PostgreSQL assertions; 22-question actual-model gate passed | Owner handoff ready; integration pending |
| AI-ANDROID | android_owner | android/** | 31 JVM tests, lint and APK passed; no device/emulator claim | Owner handoff ready; integration pending |
| AI-QA | qa_integration | Only individually leased acceptance/evaluation files | Five offline checks; independent actual-provider 35-request smoke passed | Scoped local verification passed |
| AI-SEC | security_auditor | Read-only; exact regression files only by lease | Delegation, containment, metric and grounding findings independently retested | Scoped local verification passed |
| AI-REVIEW | bank_reviewer | Read-only source; verdict/status per merge policy | Exact head green CI, QA/security evidence | Queued |

Runtime agent IDs and changing checkpoints belong in issue/PR handoffs; this table
does not start workers. Service owners must not edit shared docs or publish.
Wallet/payment owners are dispatched only if existing read APIs cannot meet the
frozen contract. Builds are serialized. Only the primary orchestrator operates
persistent Docker within task authorization; CI funding stays disposable.

## Acceptance and delivery

Use [architecture proposal](../../adr/0020-banking-ai-assistant-proposal.md).
Every planned guarantee needs implementation and independent evidence:

- Separate C# service/database/index/credentials/volume and controlled ingestion.
- Authenticated owner-scoped bank reads; no client/model identity authority.
- Bounded Agentic RAG with server-validated read tools and grounded citations.
- Precisely ordered live transactions, honest status and empty/outage behavior.
- Android navigation, provenance/citations, loading/error/empty/expired sessions.
- Owner/session isolation, forged headers/tool args, prompt injection, unauthorized
  ingestion, conflicting/expired documents, bounded loops, provider/backend outage.
- Synthetic retrieval/grounding/tool-choice evaluation; actual provider runs
  reported separately from deterministic mocked-provider tests.
- .NET build/tests, full repository `./gradlew check`, Android JVM/lint/APK checks,
  disposable Compose smoke and exact-head CI. No emulator without owner request.
- English architecture/API/Android/operations docs and ADR decisions; Russian
  explanation of completed behavior, tradeoffs, tests and remaining limits.

Independent review follows [merge policy](../merge-policy.md). Do not mark the
feature Done on planning, code handoff or merge alone. Application completion
requires deployed revision and readiness/acceptance evidence. If work stops,
record waiting/idle and the specific dependency in issue and Project together.
