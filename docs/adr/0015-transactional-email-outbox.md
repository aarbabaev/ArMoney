# ADR 0015: Transactional email outbox

## Context

Payment completion and email submission cannot share one transaction. Sending
email before committing payment state can announce a rolled-back result; sending
after commit without durable work can lose the notification on a process crash.

## Decision

Payment-service inserts one email outbox event per terminal owner notification in
the same PostgreSQL transaction as the payment result and in-app notification.
Completed transfers notify the sender and recipient; rejected transfers notify
only the sender. Pending transfers create no email. A unique owner/payment/type
constraint prevents retries from creating additional events. Existing terminal
payments are not backfilled.

A separate polling worker claims bounded work with expiring fenced leases and
performs network calls outside database transactions. It resolves the owner's
email through an authenticated internal auth-service endpoint, freezes the
destination, and submits plain-text English messages through Mailtrap HTTPS API.
Email delivery is disabled by default. Failed delivery never changes financial
state or blocks the payment recovery worker. Retry/backoff and terminal error
states remain durable and inspectable.

Auth owns contact information. Provider email and its verification flag are
separate from password credentials and never link identities by email. Only a
validated provider introspection response can assert email verification. Sandbox
may capture messages addressed to unverified registered emails. Real Sending
requires a verified address; missing or ineligible contacts are explicitly skipped.
There is no new public email-editing or email-verification flow in this slice.

## Consequences

Outbox enqueue is atomic and deduplicated. Provider delivery is at least once:
if Mailtrap accepts a message and the acknowledgement is lost before the database
records success, retry can submit another message. No provider-side exactly-once
guarantee is assumed. A stable event identifier aids correlation, not deduplication.
Provider acceptance is not proof of delivery to the recipient's inbox.

Mailtrap is the only added external integration. Fixed endpoints, TLS validation,
no redirects, bounded calls and sanitized error codes limit the trust boundary.
Recipient email is personal data retained in the outbox; operators must restrict
database access and define retention before production use. No broker, new service,
SMS provider or push provider is introduced. The shared internal key remains a
deployment limitation; it is not per-service authorization.

See [configuration and operation](../email-notifications.md).
