---
name: bank-security
description: Review or fix Arman Bank API, identity, secret, dependency and deployment security issues with reproducible evidence and independent retesting.
---

# Banking security review

Read the assigned revision, AGENTS.md and docs/agents/workflow.md. Work on this
repository and explicitly allocated disposable fixtures. Source review is the
default; do not scan Revolut, public hosts, or the user's persistent stack.
This skill is guidance, not an installed scanner or certification.

Trace the changed entry point through gateway, service and database. Record the
attacker's privileges, asset and trust boundary before claiming a vulnerability.
Prioritize these project-specific cases:

* Gateway strips caller identity/service headers, validates sessions on every
  protected call, and forwards only the authenticated identity. Compare two
  synthetic owners across object reads, writes, retries and idempotency lookups.
* Internal endpoints require their configured service key. A shared key does not
  isolate compromised peers; distinguish that documented design limit from a
  newly introduced bypass. Fail closed on auth errors and dependency timeouts.
* Session storage contains token digests, never recoverable bearer tokens;
  revocation/expiry are enforced. Password hashing, rate limiting and generic
  auth errors survive malformed inputs, repeated attempts and concurrency.
* Validate size/type/range boundaries; inspect parameterized SQL, fixed upstream
  destinations, redirects, errors and logs. Never put real credentials or tokens
  in findings. Check Docker context exclusions, loopback bindings and CI token
  permissions when those files change.
* For dependency findings, identify the actually resolved version and reachable
  use, then consult the vendor advisory. Do not call an unrun scanner a pass or
  invent a CVE. If no scanner is available, record that coverage gap. Do not
  install a service or change credentials as an incidental part of review.

Use the [OWASP API risk taxonomy](https://owasp.org/API-Security/editions/2023/en/0x11-t10/)
to organize coverage, not as proof of a defect. For money invariants read the
separate `../bank-financial-correctness/SKILL.md` when the change affects postings.

Report findings using docs/agents/communication.md: stable ID, severity with
impact rationale, confidence, affected revision/path, minimal synthetic repro,
expected/observed behavior, proposed regression and responsible service.
Clearly distinguish confirmed defects, hypotheses and unavailable checks.
Send actionable findings promptly to the orchestrator; no direct production
fixes by the auditor. A developer's fix is FIX_READY, not CLOSED. Independently
retest the integrated revision before closing; if reproduction cannot run, keep
verification blocked and identify what evidence is missing. Escalate suspected
high-impact issues promptly without presenting a hypothesis as confirmed.
