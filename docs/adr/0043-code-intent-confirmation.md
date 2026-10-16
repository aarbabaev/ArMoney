# ADR 0043: staged code intent and postcommit proof confirmation

Status: **CONTRACT FROZEN FOR SCOPED IMPLEMENTATION. NOT IMPLEMENTED OR ACTIVATED.**
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
Independent QA and security approved the contract at `0302cd967769a468fa142c6cd2e5cd3e034d976b`;
the orchestrator jointly froze it on 2026-10-17. Its managed INTERACTIVE scope
supersedes only the contradictory pre-write observation ordering; legacy behavior
is unchanged. The linked filename remains DRAFT for compatibility.

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
exempt fresh correctly bound denial from positive-create capacity/cleanup gates
and require domain-separated keyed hashes plus original key IDs for all retained
tombstone identities. Missing keys fail closed without record recreation. Clock
continuity alone does not prove equivalent elapsed-time rates; that assumption
or a quantified conservative margin remains explicit at the runtime gate. These choices
are frozen design choices, not deployed guarantees. Independent Auth/SSO source
assessment and QA/security contract review do not establish the runtime clock
assumption or implemented retention/privacy guarantees. Implementation needs
exact-head full checks, disposable multi-node acceptance and independent review.
## Pending amendment: encrypted short-lived opaque binding

The grant seam cannot reconstruct the original authentication-session HMAC from
the frozen six-field code binding. A DRAFT amendment in the linked contract
proposes AES-256-GCM encryption of ONLY that opaque value in short-lived detail,
with row-bound AAD, independent versioned keys and original expiry+five-minute
deletion. It explicitly changes the frozen no-recoverable-binding rule and is
NOT approved by the existing contract freeze. The six-field confirmation DTO
stays unchanged; no browser identity or mutable note may supply missing metadata.
Independent QA/security and orchestrator must approve the exception before any
schema or source implementation. Long-lived tombstones remain keyed pseudonyms.
Nonce uniqueness uses a separate non-personal lifetime registry: commit a
reservation before encryption, burn failed reservations, cap100,000/key and eight
active/retained keys. Delete registry rows only after explicit retirement and
actual final detail purge. A persistent key catalog (cap1,024) uniquely retains
key IDs and fingerprints of random key material to prohibit ID/material reuse
after registry erasure; no person/intent links or catalog deletion. Admission
budget precedes nonce reservation. This is a pending amendment, not implemented.

## Pending amendment: global authorized in-flight admission

A separate DRAFT amendment proposes 16 PostgreSQL session-advisory slots shared
by stage, explicit confirmation (including remote-work replay) and legacy proof
operations. One Auth-owned connection spans bounded
HTTP outside a transaction and the final business transaction, preserving the
pool-five local bound without nested-connection deadlock. The guarantee covers
authorized operations, not orphan physical HTTP workers after disconnect. TTL
leases and process-local semaphores cannot substitute for that global boundary.
Shared burst credits/time survive key migration. Independent QA/security freeze
is required before connection/transaction-seam implementation or runtime changes.
Session locks survive SQL commit/rollback and logical pooled close: explicitly
unlock and verify zero owned admission locks before pool return, or evict the
physical connection on uncertainty. COMMIT disconnect can leave a durable result;
withhold token release, preserve identity and reconcile exact results/current
gates rather than claim rollback or resurrect a code. Tests must exercise those
failure boundaries and enough independent sessions to saturate all 16 slots.
