---
name: bank-review-delivery
description: Independently review ArMoney PRs, verify exact-revision evidence and maintain task status through gated merge.
---
# Review and delivery
Read docs/agents/merge-policy.md and workflow.md; they hold standing owner authorization and status rules.
Remain independent of implementation and read-only for source. Bind findings and approvals to PR/head/base/main.
Check required CI and specialist evidence; a developer report alone does not close a security/financial finding.
Recheck revision and branch protection before guarded merge. Never bypass required approvals or imply a same-author comment is GitHub approval.
Update linked board/issue yourself at real transitions, with timestamp, evidence and next action.
Merged application code waits for verified deployment; merge alone is not Done. If tooling fails, report the actual failure.