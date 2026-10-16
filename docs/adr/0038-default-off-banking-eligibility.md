# ADR 0038: default-off deny-only banking eligibility barrier

Status: implemented source; integrated checks and deployment pending.

## Context

Verification authority staging can refer to an existing identity. A staging association must not revoke legacy banking access while enforcement is disabled. Conversely, future principal materialization must not accidentally expose a banking session before its eligibility protocol is complete.

## Decision

Add the independent strict boolean `AUTH_BANKING_ELIGIBILITY_ENFORCED`, default `false`. Main constructs its configuration before opening the database. It does not inherit delivery, observation, projection or proof switches. No deployment configuration enables it in this change.

Off preserves the current register, password-login, SSO and bank-session compatibility path even when staging authorities exist. On rejects an identity associated through a pending or mapped verification authority in any current V9 state. `PROJECTED` is still not banking eligibility. Identities with no authority association retain compatibility access; this is a deny-only prerequisite, not complete registration enforcement or a legacy cutoff policy.

Check before profile provisioning and recheck during session creation and session lookup. The PostgreSQL adapter takes the existing verification-control lock before checking association; authority writers use the same control prefix. The final session write cannot race past a committed association. A missing required control record fails closed. Generic credential failures and unknown-user password hashing remain unchanged.

The first check rejects an already associated identity before profile provisioning. A concurrent association can commit after that check while a remote profile request is in flight; no database lock is held across that call. The transactional final check still rejects banking-session creation, so this slice does not promise to prevent every concurrent profile or identity metadata side effect. Existing SSO contact refresh can also precede the check. No schema, public HTTP shape, principal materialization, contact verification, provider proof, projection activation or login notification is introduced.

## Validation and activation limits

Verify disabled compatibility with populated staging and a previously issued session; pending-only and mapped associations; all V9 states; SSO and password entry paths; missing control records; no profile/session side effects; and actual database lock waiting during concurrent association/session creation. Tests require isolated PostgreSQL. Source review alone is not executed acceptance.

Both current contact receipts, matching projection, fresh provider state, migration/cutoff decisions and end-to-end acceptance remain prerequisites to a later complete enforcement design. Do not enable this partial gate as a substitute, or mark issues #28/#36 complete.