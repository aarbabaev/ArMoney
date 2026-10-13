# ArMoney browser client

Angular 22.2.2, TypeScript 6.0.2 and Node 24.19.0. Dependencies are pinned in
`package.json` and locked by `package-lock.json`; use npm 11.11.0. This is a
browser client for the existing public gateway. It adds no financial backend.

## Development

```powershell
cd web
npm ci
npm test
npm run build
```

Use an owner-provisioned trusted HTTPS certificate covering `localhost`. The
development proxy validates the backend certificate. Supply the issuing CA for
Node and the UI certificate/key explicitly (keep keys outside the repository):

```powershell
$env:NODE_EXTRA_CA_CERTS = 'C:\certificates\local-ca.pem'
npm start -- --ssl-cert C:\certificates\localhost.pem --ssl-key C:\certificates\localhost-key.pem
```

Open `https://localhost:4200`. The development configuration uses the canonical
Keycloak issuer `https://localhost:8443/sso/realms/armoney`; its public client
`armoney-web` must allow the exact development origin and callbacks. The browser
and Node must trust the local CA. No TLS bypass is used. The gateway calls stay
same-origin at `/v1` via the proxy. Production uses the current page origin for
the issuer and all API calls; it defaults to the HTTPS edge at
`https://localhost:8443`. Development binds only localhost.

## Hosting

The Dockerfile builds the SPA and serves static files with Caddy on internal
port 8080. The HTTPS edge must route `/v1/*` to the gateway, `/sso/*` to Keycloak,
and other paths to this service. Static hosting returns `index.html` for deep
links, including `/oauth/callback` and `/oauth/logout`; API paths never fall back
to HTML. No secrets belong in the browser bundle. Caddy uses `/tmp` for runtime
state and is compatible with a read-only container filesystem.

## Authentication and recovery

`oidc-client-ts` performs authorization code sign-in with S256 PKCE, state and
nonce validation. Transaction state and the one-time PKCE verifier use tab
session storage; provider and bank tokens use memory only. The provider token
is exchanged at `/v1/auth/sso`, followed by `/v1/auth/me` owner verification.
Refresh requires sign-in again. There are no password fields, client secrets,
persistent credentials or fabricated sessions.

Sign-out immediately invalidates local identity and asynchronous work, attempts
bank-session revocation independently, then opens provider logout. Revocation
failure still clears the local session. Expiry and HTTP 401 clear local identity.

IndexedDB stores only transfer recovery commands and local demonstration cards,
scoped by origin and verified owner. A transfer's UUID idempotency key, exact
payload, SHA-256 payload hash and attempt marker are committed before submission.
Web Locks serialize owner operations across tabs. A tab reload, sign-out or
different owner does not delete another owner's uncertain command. Retry sends
the same command and key, without re-resolving its recipient or creating a
replacement. Only a matching server `COMPLETED` or `REJECTED` response removes
the command. All failures conservatively preserve it; a command refused before
acceptance may therefore require an operator-assisted investigation rather than
a client discard. Corrupt or unavailable recovery storage disables transfers
without resetting records. Browser storage clearing or profile deletion can
destroy local recovery evidence; investigate uncertain transfers before doing so.

Lossless JSON parsing represents integer wire numbers as `bigint`. Money uses
integer fils and supports the exact positive Java long range; serialization
emits integer JSON tokens. No native JSON parser or floating-point money path is
used for gateway responses or stored financial commands.

## Screen and API parity

| Screen/action                     | Implemented contract or local behavior                                                                                                    |
| --------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------- |
| Browser sign-in / enrollment      | Keycloak public client; bank SSO exchange and verified `/v1/auth/me`; provider owns registration                                          |
| Home                              | Separate authoritative wallet balances, recent loaded activity, account/transfer/inbox/card shortcuts                                     |
| Accounts / create                 | `GET/POST /v1/wallets`, AED only; ACTIVE/CLOSED and PENDING/READY displayed                                                               |
| Balance                           | `GET /v1/wallets/{id}/balance`; failures display unavailable, never zero                                                                  |
| Account details                   | Lifecycle, provisioning, copy UUID with explicit no-IBAN guidance, ready source selection                                                 |
| Account activity                  | Source/destination filter over fetched history; clearly partial rather than full statement                                                |
| Recipient lookup                  | `POST /v1/recipients/resolve`; exact registered UAE phone, non-self recipient, review name/phone                                          |
| Transfer review / confirm / retry | `POST /v1/payments`, requester-scoped idempotency and durable exact command                                                               |
| History                           | `GET /v1/payments/page`, opaque cursor, deduplication, refresh, retained rows on errors, exhausted traversal guard                        |
| Transfer details                  | `GET /v1/payments/{id}`, owner/recipient visibility validated, pending never presented as complete                                        |
| Profile display name              | `GET/PUT /v1/users/me`, immutable owner verification                                                                                      |
| Registered phone                  | Server phone and ownership-attestation status; no public phone-verification API exists                                                    |
| Inbox / read                      | `GET /v1/notifications/page`, `POST /v1/notifications/{id}/read`, immutable notification checks                                           |
| Demonstration cards               | Owner-local VIRTUAL/PHYSICAL, one nonclosed card per wallet/type, ACTIVE/FROZEN/CLOSED; confirmed terminal closure, no HTTP/money/PAN/CVV |
| Assistant                         | Coherent unavailable screen and disabled adapter; no invented endpoint, responses or bank advice                                          |
| Sign-out                          | `/v1/auth/logout`, local clear regardless of busy/error, provider end-session callback                                                    |

The semantic palette matches `docs/android-design.md`: Shark, White and
Cornflower with system light/dark selection, pill actions with 48 px minimum
touch targets, 24 px cards, labelled controls, keyboard focus and text statuses.
Small screens use scrolling navigation and a single content column.

## Tests and limits

`npm test` runs unit/client-contract regressions. `npm run e2e` runs Playwright
against a separate HTTP loopback Angular test target with synthetic bank sessions and mocked gateway data;
these are browser client tests, not financial acceptance. It requires Playwright
Chromium (`npx playwright install chromium`). The test-only bootstrap is excluded from production builds. It does not exercise
OIDC provider transport; production and manual development require trusted
certificates. No test TLS validation bypass is configured. `npm audit --audit-level=high` checks the lock.

Real gateway, provider, PostgreSQL, ledger concurrency, and uncertain-outcome
acceptance remain the repository's independently run integration/Compose checks.
No mock UI test establishes actual money movement. The browser client cannot
issue real cards, perform currency conversion, verify phones, provide an AI
backend, or create funding. IndexedDB data and same-origin scripts are within
the browser origin trust boundary; local commands contain recipient phone data.
