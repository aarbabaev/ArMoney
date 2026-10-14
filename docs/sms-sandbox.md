# Local SMS sandbox

This development-only service records synthetic SMS messages in a durable local
inbox. It has no provider SDK, provider configuration, delivery adapter, or outbound
SMS capability. The health CLI accesses only its own loopback readiness endpoint.
Auth-service OTP generation, verification, expiry, attempt budgets and account
state transitions are outside this transport. No auth-service integration is
included in this slice.

## Access and startup

The optional `compose.sms.yaml` service exposes its UI at
`http://127.0.0.1:8026`. It uses a dedicated Docker bridge network and a dedicated
named data volume. Do not change the loopback binding or connect it to public
ingress. Plain HTTP is suitable only for this local development use.

Set separate randomly generated `SMS_SANDBOX_SEND_TOKEN` and
`SMS_SANDBOX_READ_TOKEN` values in your untracked environment. Each must contain
32–128 URL-safe characters (`A-Z`, `a-z`, `0-9`, `_`, `-`); startup rejects missing,
weak or equal values. Never commit or print these credentials. Reader access
allows viewing every sandbox message, including synthetic OTPs, and clearing
the inbox. Sender access permits submissions only. These are two local service
roles, not individual user accounts or production authorization.

From the checkout with a configured private `.env`:

```sh
docker compose -p arman-bank --env-file .env -f compose.sms.yaml up -d --build
```

Use the actual private environment-file path when it lives outside the checkout.
This optional file starts only the sandbox; it does not change the banking service
configuration or enable SMS producers. Retain the named volume across upgrades.

The web shell contains no message data. Enter the reader token to load the
inbox, use Search for destination/text/purpose and Purpose for an exact filter,
and select Refresh to reload. The token stays in JavaScript memory and is erased
from the input after unlocking; no local storage or cookies are used. Lock or
leave the page to remove visible messages and the token. Clear requires an
explicit browser confirmation and clears the entire inbox, irrespective of the
current filter. Messages are rendered with DOM `textContent`; a restrictive CSP
blocks inline script and framing. Responses disable caching. Do not enter real
customer messages or production OTPs into this fixture.

## HTTP contract

All `/api/` requests require `Authorization: Bearer <role token>`.

| Operation | Credential | Behavior |
| --- | --- | --- |
| `POST /api/messages` | Sender | JSON `{destination,text,purpose}` plus `Idempotency-Key`; 201 for first submission, 200 for exact replay; response `{id,createdAt,replay}` contains no message text |
| `GET /api/messages?q=&purpose=` | Reader | Newest-first JSON array of `{id,destination,text,purpose,createdAt}`; optional case-insensitive substring search and exact purpose filter |
| `DELETE /api/messages` | Reader | Requires `X-Confirm-Clear: true`; 204 after durable clear |
| `GET /health/live`, `GET /health/ready` | None | Status metadata only; readiness fails after a storage failure |

`createdAt` is Unix epoch milliseconds; IDs are UUIDs. Destination must be a
canonical UAE mobile number matching `+9715[024568]` followed by seven digits.
Text is nonblank and at most 2,048 Java characters. Purpose is 1–64 ASCII letters,
digits, underscores or hyphens. Keys are 1–128 ASCII letters, digits, `.`, `_`,
`:`, or `-`. Body size is at most 8 KiB; search is at most 128 characters. Invalid
input returns 400, missing/wrong-role authentication 401, payload conflicts 409,
oversized requests 413, the shared API rate limit 429, storage faults 503 and
capacity exhaustion 507. Error responses never include message contents.

## Durability, retention and limits

A single process holds an exclusive lock on `/data/inbox.lock`. The store writes
a complete bounded JSON snapshot to a temporary file, forces its bytes to disk,
then atomically replaces `/data/inbox.json` before acknowledging success.
Unsupported atomic replacement fails closed. The Docker image runs as a
non-root user with a private writable data directory. Restarting the container
preserves messages and idempotency records when the named volume is retained.
This is a single-process fixture store, not a replicated transactional database;
atomic rename is not a guarantee against every filesystem or sudden host power
failure. Volume snapshots/backups can retain cleared data; this is not secure
physical erasure or encrypted-at-rest storage.

The 1,000-entry hard capacity includes cleared-message tombstones. Capacity is
rejected, never silently evicted. Clearing removes destination/text/purpose but
retains the key, payload SHA-256 and original timestamp. Exact replay of a cleared
message returns 410; changed payload returns 409. A tombstone digest can permit
offline guessing of low-entropy content by a local volume administrator; the
volume remains sensitive. All entries expire 24 hours after their first
submission. Pruning runs at startup, on API store operations, and once per minute
while running. After expiry, a key may be used again and creates a new message.
During downtime physical expiration waits for the next startup. No OTP validity
decision is implied by message retention.

The fixed-size global API budget is 120 requests per minute, including rejected
authentication and reads/deletes; it resets on restart. Health and static files
do not consume that budget. There is no per-source map to grow without bound.
The GET result is bounded by store capacity and message-size limits. There is no
pagination or individual-message deletion in this initial local fixture.

## Validation

Run `./gradlew :sms-sandbox:test` for real temporary-filesystem restart,
idempotency, concurrent retry, lock, clear/retention, HTTP authentication,
payload, rate-limit and inert-rendering contract checks. These tests start real
Javalin HTTP servers and never send SMS. They do not replace independent browser
XSS execution checks. Runtime changes also require repository `./gradlew check`
and disposable CI Compose acceptance. Never run tests against the owner's
persistent volume. The CLI `sms-sandbox health` exits successfully only when the
fixed loopback readiness endpoint responds successfully within its timeout.

The disposable Compose acceptance should start the sandbox with synthetic
credentials, submit and read a message, restart the container, verify the same
ID and idempotent replay, reject wrong-role/anonymous access, clear the inbox,
and verify the 410 tombstone behavior. Project-specific cleanup must target only
the disposable CI project and its volume.

The bridge is not an outbound firewall. No provider transport or external SMS delivery path exists in the service; loopback binding restricts ingress.
