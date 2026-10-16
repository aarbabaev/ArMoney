# Internal code-intent staging and request ownership

Status: implemented internal source; default off and not activated for banking.
The frozen [joint contract](CODE-INTENT-CONFIRMATION-DRAFT.md) and
[ADR 0043](../docs/adr/0043-code-intent-confirmation.md) are imported unchanged
from PR #85 revision `230f6dc` (approved contract ancestry `9ffd447`). They define
future confirmation as well as staging; their presence is not an activation claim.

## Implemented boundary

V12 stores non-credential STAGED intents for managed INTERACTIVE bindings. It
does not create ACTIVE authority, proof events, banking sessions or notices.
There is no stage HTTP endpoint, confirmation service or Main cleanup-worker
registration. Construction and explicit worker scheduling remain internal opt-ins.
The existing private proof bridge can be registered through strict default-off
configuration; its positive production eligibility supplier stays unavailable.

First staging requires database admission within issued time plus 30 seconds;
the original code lifetime is at most 120 seconds. Replays retain original time,
identity and payload. At most four command aliases belong to an intent. Immutable
parents remain until original expiry plus seven days; encrypted details are due
for erasure at expiry plus five minutes. Permanent managed markers remain.

Separate versioned HMAC and AES-256-GCM rings each retain at most eight keys.
Only the original authentication-session identifier is encrypted. Its durable
nonce reservation must be acknowledged before encryption, and the final key
generation fence prevents retired-key resurrection. The permanent catalog holds
at most 1,024 key identities; each key has at most 100,000 nonce reservations.
Catalog metadata contains no raw key material or session details.

Shared writer admission has burst 20 and refill two per second. New proof and
intent creation share 1,000 reservations per hour without double charging an
exact replay. Parent and alias storage have separate 100,000-row caps; 80 percent
is an alert threshold, not an admission cutoff. Explicit cleanup wakes every
30 seconds and sweeps at most 25 parents and 100 aliases per transaction. New
staging requires a successful sweep within 60 seconds and no purge overdue by
more than five minutes; replay retains its separate ordering.

## Request and database ownership

The Auth-specific gate permits at most sixteen authorized operations across
processes. One exclusive physical PRIMARY connection spans request admission,
parsing, observation outside SQL transactions and final writes. Capabilities bind
store, writer, payload, callback lifetime and thread. Unscoped or foreign use
fails closed. Cleanup verifies advisory-lock release before pool return, or
invalidates the physical connection. Commit acknowledgement loss remains an
uncertain outcome; it cannot authorize success or establish rollback.

After directional credential authentication, HTTP/1.1 body reading uses one
four-second monotonic budget. A managed scheduler physically closes stalled or
drip-fed transport, with completion/cancellation fencing for keepalive safety.
HTTP/2 and unknown adapters fail closed. This budget does not bound JDBC calls,
control-lock waits or the entire provider/business operation. Existing pool size
five and trusted callback/DSL limitations remain as recorded in
[ADR 0044](../docs/adr/0044-owned-database-sessions.md).

## Validation evidence and remaining gates

At `5878da1`, the full isolated check passed 47 platform and 641 Auth unit cases.
Its 365 Auth integration cases had three failures caused by a polling helper's
null return in Spock, despite its internal lock-zero assertion succeeding.
At `2f8b0ae`, only that helper return changed; all seven real-process cases
passed in a fresh isolated runtime, including the previously unreachable
no-write, all-sixteen-slot and five-connection reuse assertions. Independent QA
and security verified exact source/archive binding, reports and cleanup.

These are complementary dated checks, not a full check on the final integrated
revision. Required current-head CI, independent integrated review, operational
configuration, post-commit confirmation and positive provider/managed-session
acceptance remain separate delivery gates. No production activation or completed
registration/login-notice flow is claimed.
