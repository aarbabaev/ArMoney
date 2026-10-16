# ADR 0043: staged code intent and postcommit proof confirmation

Status: **DRAFT FOR JOINT AUTH/SSO, QA AND SECURITY FREEZE. NOT IMPLEMENTED.**
Date: 2026-10-17 (Asia/Dubai).

## Context

The isolated SSO-VIS-001 experiment at `05bff8e` observed the session pair as
not visible from node B while the redirect hook held the login request. Both
sessions were visible after the response, including callback abort and hook 503.
This does not identify which member was absent during PRE or promise rollback.
See the retained evidence described in the provider design.

The existing pre-write positive-session requirement therefore cannot implement
the tested fresh-login ordering. V7 interactive proof requires an event and true
notice eligibility; it must not be repurposed as non-authoritative pending state.

## Proposed decision

Initially restrict the new operations to explicitly classified managed INTERACTIVE
login. New-flow reuse/legacy inputs deny without manufacturing an interactive
event; existing legacy/reuse paths remain unchanged. Use separate immutable
code-intent storage. Stage the existing strict Write
payload before redirect without creating a proof, event, recipient, notice,
banking session, projection acknowledgement or ACTIVE authority. An explicit,
separately authenticated confirmation operation runs only after Keycloak validates
the authorization code and before final token serialization/release.

Confirmation obtains fresh committed user AND authenticated client-session
observations outside the local transaction. One owning Auth transaction locks
verification, projection and proof state in order, rechecks all owned evidence
and deadlines, and atomically records the V7 proof/event and confirmation.
Historical resolve remains read-only. No nested transaction or hidden remote
fanout is introduced by resolve. A historical snapshot readable after suspension
cannot substitute for successful current confirmation or permit token release.
Original fresh stage admission (issued_at plus 30 seconds) is distinct from the
unchanged code expiry (at most 120 seconds); confirmation uses the original timely
admission without creating or extending a CREATE window. V7 is unchanged; future
migrations are append-only.

The exact draft DTOs, credentials, invalidation/replay rules, timeouts and
acceptance cases are in
[the draft contract](../../auth-service/CODE-INTENT-CONFIRMATION-DRAFT.md).
That draft does not supersede the current contract until joint freeze.

## Consequences and limits

Keycloak consumes its single-use code before the policy response hook. An Auth
commit followed by timeout can therefore leave a confirmed orphan while the code
is no longer usable. Exact internal replay cannot resurrect the code or authorize
an invalid token replay; a fresh authorization flow may be required. A record
alone never creates a banking session or dispatches a notice.

The replacement observer must bind the specific client, observed_at and a trusted
valid_until in an exact 12-field response. Convert its same-clock relative
duration conservatively to the original Auth DB start; do not assume synchronized
host clocks or renew the budget. The current eight-field status response is insufficient and cannot be
accepted as a fallback. Required-action OIDC_CONTINUATION is distinct from ACTIVE;
managed banking denial remains unconditional until its separate consumer passes
all gates. No provider factory, route, credential or runtime flag is enabled here.

The draft fixes the proposed one-owner lock order, separates committed denial
from positive-proof rollback, bounds retention/cleanup/capacity and strips all
three proof claims from both refreshed access and refresh tokens. These choices
remain proposals, not deployed guarantees. Freeze requires independent Auth/SSO
source assessment and QA/security review of the complete choices, including
clock-continuity assumptions and retention/privacy constraints. Implementation then needs
exact-head full checks, disposable multi-node acceptance and independent review.
