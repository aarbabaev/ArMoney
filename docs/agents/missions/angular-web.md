# Mission: Angular browser client

Tracking: [issue #44](https://github.com/aarbabaev/ArMoney/issues/44).
Authorized implementation started on 2026-10-13 from main
`112b01d90c247bd774db7fa9f9720edcc0b439f8`, feature/angular-web-client.
The original local snapshot has no Git metadata and remains preserved. An isolated
sibling checkout provides reproducible source and a changed-file manifest; remote
publication uses the authorized GitHub connector if local Git authentication is absent.

## Acceptance and ownership

- Primary bank_orchestrator owns shared documentation, role configuration, edge,
  Compose, smoke and CI integration. Only it operates persistent Docker within
  existing deployment authorization; no funding fixtures on live data.
- web_owner owns web/, actual Android feature audit and browser checks.
- sso_owner owns sso-service/ plus specifically leased KeycloakTokens.java and
  KeycloakTokensSpec.groovy. Preserve native client validation and existing users.
- gateway_owner audits app-gateway/ contracts; changes require an explicit frozen
  contract. Existing bearer APIs remain the SPA boundary.
- Independent qa_integration, security_auditor and bank_reviewer verify the integrated
  revision. Runtime IDs and exact active/idle state are recorded in issue checkpoints.

Browser authentication uses the dedicated armoney-web public code/S256 client,
same-origin routing and an in-memory opaque bank session. Reload requires SSO;
durable unresolved transfer commands survive reload/logout separately from credentials.
See the browser-session ADR and web-client guide for exact trust limits.

Cover wallet creation, balances, details and ready source selection; recipient
confirmation, transfer recovery; owner-scoped cursor history/details/filter; inbox
read state; profile and logout; local demo cards and their terminal closure.
Audit both current main Android and the preserved source snapshot: main includes
cursor pagination, while the snapshot includes account-detail/demo-card screens.
Neither source's documentation alone establishes parity or runtime readiness.

AI work belongs to separate issue #43 and ai-assistant-service. Do not duplicate its
implementation or write its files. Browser integration requires its actual frozen
protected contract, citations and live owner-scoped read tools. An unavailable
placeholder is not end-to-end assistant delivery.

## Delivery gates

Meaningful web unit/component/browser checks, full Gradle check and disposable
Compose/SSO/native regression acceptance are required. Transport fixtures prove
client behavior only; financial acceptance must exercise the real backend.
Required CI must pass on the exact PR head before delivery or reviewer merge.
Deployment remains pending until separately verified; merge alone is not Done.
