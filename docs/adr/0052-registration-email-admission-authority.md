# ADR 0052: Registration email authority at managed admission

Status: Implemented in the integration source; independent acceptance pending. No activation is implied.

## Context

Auth owns registration OTP receipts. Completing email and phone verification
does not mutate Keycloak's persistent email-verification flag. Isolated browser
acceptance completed registration and native C2 confirmation, then observed a
boolean `email_verified=false` and a rejected banking exchange. Requiring the
provider flag before examining Auth's registration authority prevents a genuinely
verified registration from entering banking.

Writing the provider flag after a remote status read would require supported
concurrency protection against email replacement. A token-only projection would
require another authoritative contact-evidence contract and mapper guarantees.
Neither is needed to use the authority Auth already owns.

## Decision

Retain both the provider verification value and an explicit claim-format bit in
the trusted principal. Only a raw JSON boolean has known format. Legacy/default-off
normalization and admission remain unchanged; managed admission rejects unknown
format before database work. No constructor silently supplies a trusted value.

Carry the provider boolean explicitly into managed admission. Select the source
from current Auth database authority, never from a client field or a routing note.
Only the strict registration source may rely on Auth's current dual-contact
receipts instead of the provider flag. It still requires current candidates and
versions, consumed purpose-correct challenges, projection ACK and completion,
current eligibility grant, exact token contacts and genuine C2 proof. Existing
deadline, epoch, source and authority rechecks remain mandatory.

Migration still requires the provider flag to be true, before operation lookup
or admission writes. Unknown, partial or changed source authority fails closed.
Do not change retained fingerprint/HMAC codecs, existing sessions or legacy login.
No provider contact, token claim, public request schema or database schema changes.

## Validation and limits

Require genuine registration with a typed false flag, current receipts and C2 to
pass. Missing, stale, wrong-purpose or unconsumed receipts, missing ACK/grant/proof,
changed contacts and epochs must deny without banking writes. Genuine migrated
authority must deny false and retain true compatibility. Source mutation and
retry paths must not bypass the policy. Preserve legacy malformed-claim behavior
while rejecting it for managed admission. Component tests do not replace actual
browser/Gateway acceptance or exact-head CI.

The provider records only closed rejection-reason labels for unavailable session
observations. Reason messages contain no bindings, contacts, observation timestamps, token
values or exception details, and never change the public response or admission.

Browser acceptance separates ordinary `openid` banking admission from independently
issued `offline_access` refresh negatives. The positive path requires a retained
online session. Offline artifacts cannot substitute for that session or C2 evidence.
