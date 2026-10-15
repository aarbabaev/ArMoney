# ADR: Freeze presentation names in transaction email delivery

## Decision

Transaction notices render an ArMoney HTML message and equivalent UTF-8 plain text
from one template. Amounts remain integer fils formatted in AED. Status, payment
and event references are included without implying that email delivery determines
payment success. External images, tracking pixels and remote fonts are absent.

Auth remains authoritative for the notification email destination and its verified
state. The sender may read the same owner's existing user profile through the
internal authenticated API to obtain an optional display name. The response must
match the requested identity; a profile name is user-supplied presentation data,
not a verified legal name. Missing names receive a neutral greeting; no name is
inferred from the email address. HTML content is escaped.

Append-only payment migration V5 adds nullable `recipient_name` and extends the
existing immutable-recipient trigger. Name and destination are captured together
at the first delivery attempt and remain unchanged across retries, including a
captured null name. Existing rows are not backfilled. This preserves the meaning
of a queued message when profiles change later and avoids a second mutable name
lookup during retry.

SMTP uses multipart/alternative, with plain text before HTML. The existing outbox
claims, retry fences, verified-routing policy, SMTP TLS and total deadline remain
in force. Ambiguous acceptance can still produce duplicate emails.

## Evidence and limits

The payment patch was recovered byte-for-byte from the earlier frozen styling
worktree after confirming identical payment baselines and no V5 migration conflict
on main457024e. Historical tests are evidence of that earlier execution, not a new
integrated pass. On 2026-10-15, independent QA ran the current payment module check:
129 unit and 39 PostgreSQL integration tests passed with no failures or skips.
This includes the V4-to-V5 migration, frozen recipient routing and names, and
TLS multipart UTF-8 SMTP delivery. CI, live Mailtrap delivery and deployment
remain unverified. Auth login/registration email
styling belongs to the separate auth notification integration; this payment slice
alone does not complete issue47 or establish deployment readiness.
