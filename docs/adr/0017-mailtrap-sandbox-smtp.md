# ADR 0017: Mailtrap Sandbox SMTP transport

## Context

Sandbox inbox credentials are supplied as SMTP username/password. Existing delivery
uses Mailtrap HTTP API; a transport choice must not change financial state or
outbox guarantees.

## Decision

Keep HTTP API as the backward-compatible default. Add `MAILTRAP_TRANSPORT=smtp`
only for `MAILTRAP_MODE=sandbox`, targeting `sandbox.smtp.mailtrap.io` on a supported
STARTTLS port. Require TLS and server identity verification before authentication.
Use Eclipse Angus Mail instead of maintaining a custom SMTP client. Credentials
remain local deployment configuration and are never written to logs or Git.

The existing email worker resolves contacts and dispatches outside database
transactions. Recipient and Sandbox/Sending mode remain frozen for retries. SMTP
acceptance is mapped to the same durable outcomes; transient failures retain
bounded retries, permanent rejection remains inspectable. No new schema, API,
provider, or financial behavior is introduced.

## Consequences

SMTP does not require an API token or inbox ID. Real SMTP sending and implicit TLS
on port 465 are outside this slice. TLS validation cannot be disabled in production
configuration. Lost acknowledgement may still cause duplicate submission; no
exactly-once delivery claim or mailbox-delivery guarantee is made.
