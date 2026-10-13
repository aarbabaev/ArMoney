# Reviewer merge authorization

Owner instruction, 2026-10-13: the independent bank_reviewer may merge ArMoney PRs targeting main after successful review. This is standing merge authorization and supersedes earlier blanket bans on reviewer merges. It does not authorize deployment, direct main pushes, new backlog implementation, or periodic polling.

The reviewer remains read-only for source, tests, configuration and infrastructure. It must not be an implementing agent for the reviewed change.

Before merging:
- Verify the PR is open, non-draft, targets main, and belongs to authorized ArMoney work. Respect explicit hold/do-not-merge instructions.
- Record APPROVE for the exact head SHA and current main/base SHA; no unresolved blocking findings or unaccepted material residual risk.
- Require green mandatory CI and task acceptance evidence for that head, plus independent financial QA/security verification where relevant. Missing, pending, cancelled, skipped-required or failing evidence blocks merge.
- Re-read head/base/main and review/check state immediately before merge. Changes invalidate the prior decision and require renewed review/verification.
- Respect GitHub branch protection, merge queues and required approvals. Never use admin bypass, weaken rules or invent approval. When authenticated as the PR author, publish the agent verdict as COMMENT and explicitly state it is not formal GitHub approval.
- Use an allowed merge method with the expected head SHA guard. If the API rejects, stop and report the reason; do not force or push main.
- Verify the merged result and merge commit before updating issue/project status. Done requires all acceptance criteria; deployment-in-scope remains Awaiting deployment.
- Persist PR/base/head/verdict/check evidence/merge result so interrupted runs do not repeat actions.

The assigned reviewer performs the merge using available authorized tooling. If unavailable, it reports a blocker rather than claiming a merge. This policy is not a background daemon; orchestrator dispatch is still required.