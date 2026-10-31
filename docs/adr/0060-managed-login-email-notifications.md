# ADR 0060: Managed interactive login email notifications

Status: implemented candidate; integrated validation and deployment pending.
Scope: trusted managed bank-native interactive logins only, not whole-issue #37
completion for historical unmanaged authentication paths.

## Context

Styled login emails require a durable notification intent, not an SMTP call in
the authentication transaction. A provider refresh or ordinary session validation
does not establish a new interactive login. User profile names are presentation
data and cannot determine an email destination.

## Decision

Capture an intent only for a trusted, notice-eligible interactive event when a
new managed banking session and its event consumption commit successfully.
The dedicated `AUTH_LOGIN_NOTIFICATIONS_ENABLED` switch defaults to false and
gates both capture and dispatch. There is no historical backfill. Existing OTP
configuration does not activate login notices.

Auth owns the verified recipient and its contact/authority versions. The outbox
has stable issuer/event deduplication, fenced leases, bounded retries and a finite
delivery lifetime. Check current Auth contact and authority before delivery;
retire stale work rather than send to a replacement address. SMTP and the
optional owner-matched User profile lookup occur outside database transactions.
The profile display name is bounded and HTML-escaped, with a generic greeting
when unavailable. It is not evidence of a verified legal name.

Encrypt recipient and optional name using a domain-separated key derived from
the existing managed banking key ring. Bind ciphertext to immutable event and
authority identifiers. Erase delivery payloads on terminal outcomes or expiry;
retain seven-day pseudonymous owner/event deduplication evidence through the
event replay window. Fresh random 96-bit AES-GCM nonces do not constitute a
lifetime uniqueness guarantee; no permanent nonce registry is introduced.

Messages contain equivalent plain text and inline HTML, the genuine event time
in UTC and a safe sign-in description. They contain no token, password, OTP,
invented device, IP address or location, external asset or tracking resource.

The Keycloak `armoney` email theme separately styles its stock email-verification
message. It preserves the provider-generated action link and expiration, uses a
generic greeting before contact verification, and requires explicit realm
`emailTheme` activation. It does not replace the login-notification producer.

## Limits and verification

Historical/unmanaged login paths without trusted interactive evidence do not
emit notices. Validation, refresh, reused/replayed exchange, failed authentication
and migration-purpose authentication do not create notices. A later genuine
bank-native interactive login after migration is eligible.

SMTP failure does not undo a successful login. A lost acknowledgement after SMTP
acceptance can cause duplicate delivery. A current-contact check cannot make a
remote SMTP operation atomic with a subsequent contact change. Key rotation must
retain keys needed by live pending payloads.
The running worker performs expiry erasure and bounded tombstone cleanup. A
stopped or disabled worker delays physical cleanup; PostgreSQL does not provide
an autonomous timer for this feature.

Real PostgreSQL tests must cover atomic rollback, concurrent deduplication,
restart, fencing and stale contacts. Transport and rendering tests must cover TLS,
provider outage, hostile profile data and sensitive-data redaction. Deployment
and mailbox visual acceptance are separate evidence from source tests.
