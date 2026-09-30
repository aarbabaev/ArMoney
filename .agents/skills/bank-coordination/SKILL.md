---
name: bank-coordination
description: Coordinate Arman Bank service owners, route review findings to developers, and track independent verification through PR delivery.
---

# Team coordination

Use docs/agents/workflow.md for assignments and build/file leases, and
docs/agents/communication.md for findings. Determine the latest remote base and
preserve user edits before dispatch. Read only the task-relevant skills from
docs/agents/skills.md; include their exact paths in the task assignment.

Keep an explicit mapping of task ID to runtime agent ID, role, directory, revision,
writable files, skill paths, dependencies and state in the chat. Reserve one of
the three worker slots for QA/security when implementation risk warrants it.
Independent review must be assigned to an agent other than the implementing
worker; do not relabel the developer as reviewer of their own patch.

Use native collaboration messaging: send_message for a running worker, and
followup_task for an idle/completed worker, or the host's equivalent continuation
tool. If none is available, spawn a new scoped worker carrying the finding and
relevant history. Do not create or message separate user-owned chats implicitly.
Workers do not share private reasoning or receive every other worker's output.
Relay actionable evidence explicitly; a saved finding file alone notifies nobody.

On a finding, identify the actual owner, acquire a file lease, assign the fix and
regression with the same finding ID, and wait for acknowledgment/evidence. If the
owner is busy, queue the assignment and track it instead of assuming delivery
means a completed fix. Resume the independent auditor after integration. If a
fix touches a different service, split named assignments and freeze the contract.

Keep a sanitized checkpoint in the PR description using the communication
template. Do not auto-create vulnerability issues or post exploit details beyond
the authorized private project. At a session boundary, record open work and exact
revisions. On resume, recheck source and CI and recreate runtime agent mappings;
do not assume old agents are alive. A configuration file does not deliver messages
or run a background queue. If interrupted, report pending work honestly.
