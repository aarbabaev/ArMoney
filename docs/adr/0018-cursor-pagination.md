# ADR 0018: Owner-scoped cursor pagination

## Decision

Add separate payment history and notification `/page` routes while retaining
legacy newest-100 endpoints. Use PostgreSQL keyset pagination ordered by immutable
`created_at DESC, id DESC`, fetching one extra row to determine continuation.
Existing indexes support the access pattern; no database migration is required.

The payment service issues a versioned HMAC-SHA256 continuation token bound to
resource, authenticated owner and last returned key. A domain separator prevents
cross-purpose reuse of the existing internal service key. Validate token syntax,
size, signature and resource/owner on every request, while always enforcing SQL
owner predicates. The gateway forwards only allowlisted validated parameters and
revalidates the bank session independently. Rotating the service key invalidates
outstanding cursors without altering data.

## Consequences

Offset pagination can repeat or skip entries when newer records arrive. A strict
key boundary avoids this shift and timestamp ties are disambiguated by UUID.
This does not hold a database snapshot across requests: pending incoming payments
can become visible later, and mutable status/read fields can change. Refresh from
the first page to discover newer or newly visible entries above the boundary.

Android keeps page state and presents load-more, retry and refresh. Financial
commands, balances, idempotency, worker claims and outbox delivery are unchanged.
No iOS implementation change is required. PostgreSQL traversal/ownership tests,
gateway validation tests and Android state tests cover the new contract; visual
emulator validation remains an explicit owner-requested activity.
