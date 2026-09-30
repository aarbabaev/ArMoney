# Agent context audit

Audit baseline: main `4ef94d8018de8594055d174750b776b8163c5a56` (PR #9 merged),
read from a source snapshot without `.git`. Counts below measure instruction
text, not runtime tokens or billing. Model tokenization, automatic context,
caching and actual task paths vary; no exact token-saving claim is justified.

## Findings and changes

The eleven roles repeated architecture, handoff and skill menus already present
in AGENTS.md/workflow. Keep short role-local lease, secret, no-publication,
no-recursive-delegation, live-Docker and build guards; refer to the shared rules
for details. Preserve service-specific financial, identity and review duties.
Add dedicated `ios_owner` and `sso_owner` with real directory scopes and explicit
adapter handoff. Role files configure instructions; they do not start workers.

Java/API/PostgreSQL skills duplicated general architecture and verification
rules. Their revised bodies retain project paths, transaction/retry behavior and
non-obvious test wiring. Keep the detailed financial acceptance, security
finding lifecycle and fixture-isolation guidance because those protect concrete
failure modes. Add native-iOS and Keycloak skills. Use context-map.md for project
knowledge and authoritative pointers rather than copying manuals into skills.

## Measured text size

Counts use UTF-8 decoded text with CRLF normalized to LF; words are non-whitespace
runs (`\S+`). Full file contents include TOML/YAML metadata. These totals exclude
AGENTS.md, workflow, routing/map/audit docs, runtime prompts and tool responses.
The comparison isolates this worker's owned instruction files, not total session
context. Baseline was captured before editing.

| Group | Before chars / words | After chars / words |
| --- | ---: | ---: |
| Same 11 role files | 32,580 / 3,966 | 19,335 / 2,377 |
| Role set including 2 new roles | 32,580 / 3,966 | 22,314 / 2,745 |
| Same 7 skill files | 21,335 / 2,825 | 17,349 / 2,247 |
| Skill set including 2 new skills | 21,335 / 2,825 | 20,880 / 2,721 |

Reproduce after counts with Python `Path.read_text(encoding="utf-8")`, normalize
line endings, then sum `len(text)` and `len(re.findall(r"\S+", text))` over
`.codex/agents/*.toml` and `.agents/skills/*/SKILL.md` respectively. Adding a
role does not mean its instructions are loaded into every assignment.

## Orchestrator operating changes

- Prefer `fork_turns="none"` when dispatch supports it. Send the revision,
  directory, role, exact write/read leases, frozen contract, acceptance cases,
  relevant skill paths and build permission. Full-history forks copy unrelated
  conversation and outputs; use a bounded history only for a concrete dependency.
- Keep shared AGENTS/workflow canonical. Read a role once, then only relevant
  source sections and assigned skills. The routing table is a menu, not a request
  to load every role, skill or module. Use `rg` to locate before bounded reads.
- Bound tool output to the question. Report failing assertions and report paths,
  not repeated complete build logs or complete connector responses. Never print
  secret-bearing config merely to audit settings; inspect only relevant keys.
- Reuse an assigned worker for fixes. Record its runtime ID and revision; send
  the finding plus necessary evidence. A message delivery is not completion.
- Use event-driven waits or bounded waits with backoff; avoid repeated identical
  polls. Give meaningful user progress updates without dumping unchanged status.
- Integrate compact handoffs (paths, contract/docs delta, exact evidence,
  blockers). Independent QA/review still reads the integrated revision and is
  never removed merely to reduce context.

The project config was inspected; this audit does not change global permissions,
model selection or service credentials. Instruction size reductions alone cannot
establish that a specific task consumed fewer tokens. Compare actual usage on
comparable tasks only when trustworthy runtime telemetry is available.
