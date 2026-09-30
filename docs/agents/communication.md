# Agent messages and security findings

The primary chat is the orchestrator. Native subagents communicate via explicit
tool messages and final handoffs. They share source files in a shared workspace,
but do not automatically read one another's conversation or receive new work
from TOML/Markdown files. This protocol is executed by the orchestrator while the
session runs; it is not an unattended message broker.

## Routing

1. Auditor sends a finding to the orchestrator promptly, and includes it in its
   final handoff so interruption does not silently lose it.
2. Orchestrator records the finding and assigns the relevant service owner exact
   paths, affected revision, reproduction and required regression. It uses
   `collaboration.send_message` for a running worker or `followup_task` to resume
   a completed worker. With different host APIs, use equivalent native tools.
   If an agent is unavailable, start a replacement carrying the same context.
3. Developer acknowledges the assignment, fixes within its file lease and sends
   changed paths, new revision (or patch manifest), tests and any residual risk.
4. Orchestrator integrates and resumes an independent security auditor to retest
   the integrated revision. QA reruns affected functional/financial acceptance.
5. Auditor returns evidence; orchestrator updates the finding and PR checkpoint.
   Confirmed unresolved critical/high findings block delivery. Lower severity
   findings are fixed in scope or explicitly reported with owner and follow-up;
   they cannot disappear because CI is green. Only the user can accept residual
   risk; agents cannot silently downgrade findings to bypass delivery gates.

For shared infrastructure the orchestrator is the fix owner. Disputed findings
retain their evidence and uncertainty until independent reproduction or an
explained false-positive decision. Failed verification reopens the same ID.

## Finding record

```text
ID: SEC-001
Severity / impact / confidence:
Source revision and path(s):
Attacker prerequisites and affected asset:
Synthetic reproduction:
Expected / observed:
Owner / assigned runtime agent ID:
Required regression:
State / last update:
Fix revision or patch manifest:
Independent verifier / command / outcome:
Remaining limitations:
```

State flow: NEW -> TRIAGED -> ASSIGNED -> FIX_READY -> VERIFIED -> CLOSED.
Verification failure: FIX_READY -> REOPENED -> ASSIGNED. Missing environment or
evidence: BLOCKED, retaining the previous state and reason. A disproved finding
can close as FALSE_POSITIVE only with recorded evidence and independent review.
VERIFIED requires observed results on the integrated revision. CLOSED records
orchestrator acceptance of that evidence, not merge authorization.

Checkpoint open IDs, owner, revision, state and next action in the private PR
description. Keep tokens, customer data and exploitable secret values out of all
messages. On a new session the orchestrator rechecks source/CI, reads the
checkpoint, and reassigns remaining work; runtime agent IDs may no longer exist.

## Example (hypothetical, not a finding in this repository)

The auditor observes that identity A can read identity B's wallet using a guessed
UUID. It sends SEC-001 with a two-user test and path/revision to the orchestrator.
The orchestrator assigns wallet_owner the owner-filter fix and a regression,
with gateway_owner involved only if identity propagation is also defective.
The developer returns FIX_READY. The auditor repeats both other-owner rejection
and legitimate-owner success on the integrated patch. Only after that evidence
does the orchestrator close SEC-001. A developer message saying "fixed" is not
independent verification.
