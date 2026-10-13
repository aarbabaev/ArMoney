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
## Reviewer owns task status updates

Owner instruction, 2026-10-13: bank_reviewer updates linked GitHub issue/Project status itself as part of its verdict and merge handoff, rather than leaving this to an unspecified later orchestrator action.
- Verified merge + all acceptance criteria met: Done; verify issue closure as appropriate.
- Verified merge with required deployment still outstanding: Awaiting deployment.
- External dependency, missing required evidence, failed infrastructure/CI or merge-protection blocker: Blocked, with concrete reason, evidence, responsible owner and next action.
- Confirmed code defects / REQUEST_CHANGES: In Progress, with finding IDs routed through the orchestrator to implementation owners.
- Review actively running: Review. Approval with an explicit merge hold: Awaiting merge.
- Pending CI is not success. Record pending checks and never mark Done before verified merge.
Recheck PR head/base and linked issue/project item before writing. Publish a timestamped checkpoint containing PR URL, reviewed SHA, verdict, actual merge result and status reason. Deduplicate unchanged updates. If GitHub rejects a status change, report the failure and intended status; do not claim the board was updated.