# ADR 0051: Registration required-action routing

Status: Implemented in the integration source; independent runtime acceptance pending.

## Context

Keycloak reevaluates required-action triggers after a successful action. Adding
the email action unconditionally schedules it again ahead of phone. An isolated
browser run reproduced email delivery followed by another initial email form;
that run did not complete registration.

## Decision

After fresh Auth-confirmed email completion, retain an authentication-session
routing marker containing enrollment ID, positive email contact version and an
HMAC binding. The domain-separated binding includes purpose, realm, root session,
tab, internal client, user, current email, version and enrollment.

Every subsequent suppression validates the binding and rereads Auth status.
Changed, malformed, missing or unavailable evidence clears the marker and
requeues email. Normal authentication-session reset removes the note. The note
is neither a receipt nor eligibility authority and does not update provider
contact-verification flags. Phone still requires fresh dual verification and
`PROJECTED`; code confirmation and banking admission remain separate checks.

After genuine phone consumption, a protected, original-flow consumption routing
marker supports an explicit status-only POST from the pending form. This avoids
treating a request without an OTP as another consume command. Each status request
checks fresh Auth facts and contact versions. It neither reenrolls nor issues or
consumes an OTP. `PROJECTION_PENDING` may render pending; only dual verification
and `PROJECTED` reach the fixed sign-in page. Empty, unknown or duplicate
operations are rejected. Routing notes cannot construct receipt or projection
authority.

After validating the pending marker, transient Auth transport unavailability
retains the status-only retry form. It does not restore OTP controls or grant
completion. Malformed markers and negative or mismatched fresh facts still deny.

## Consequences and validation

Trigger reevaluation with a retained marker depends on Auth availability and its
existing bounded request deadline. Failure blocks progress instead of bypassing
verification. Lifecycle regressions model action removal and trigger reevaluation,
changed bindings and unavailable authority. Browser acceptance must explicitly
observe the phone form after email verification. Source and component evidence
alone do not establish complete registration, migration, or deployment readiness.
