# ADR 0056: OIDC migration authentication-time precision

Status: Implemented in the pending PR #96 recovery patch. On 2026-10-21,
the isolated PostgreSQL red/green regression verified the precision correction;
full release acceptance remains pending.

## Context

OIDC authentication claims use NumericDate values with whole-second precision.
Migration account and managed challenges retain PostgreSQL timestamps with
fractional seconds. Comparing the signed authentication time directly against
the full challenge timestamp can reject a fresh authentication completed in the
same second as the challenge. Existing fixtures that wait for the next second
do not cover this boundary.

## Decision

Compare the authentication time and challenge start at whole-second precision
in both Auth proof stores and the account receipt preservation evidence query.
An authentication from an earlier whole second remains invalid. This adds no
clock-skew allowance and does not extend any challenge, receipt or session
deadline.

Keep signature, issuer, audience, nonce, subject, provider observation, token
age, callback custody and one-shot authorization-code consumption checks.
Preserve existing uncertainty handling and replay rejection. No HTTP or database
schema changes are needed; no migration is added.

## Verification and limits

The regressions derive signed authentication timestamps from actual PostgreSQL
challenge rows. They cover same-second authentication, previous-second and
expired proofs, and replay without a second provider code exchange. Against the
unchanged production code, exactly the two same-second positive cases failed;
the other 26 tests passed, with no errors or skips. After only the two proof-store
comparisons changed, the same 28 tests passed with no failures, errors or skips.
Owned cleanup was verified in both runs. Independent security review verified
the source delta, frozen tests and actual red/green receipts.

The read-only account receipt evidence query uses the same second-precision
lower bound. Fifteen offline guards passed and restoring the old predicate
triggered its regression; those query checks are not PostgreSQL acceptance.

Source review identifies the precision mismatch; it does not by itself establish
the cause of a failed live callback. Complete migration in both legacy modes,
actual delivered email and sandbox SMS codes, preservation through FINAL and
fresh original-owner admission remain separate release gates. No financial
funding or nonzero-balance preservation is claimed.
