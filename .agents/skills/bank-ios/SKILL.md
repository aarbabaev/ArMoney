---
name: bank-ios
description: Build or review the native ArMoney SwiftUI iOS 18+ client, gateway integration and Keycloak login.
---

# Native ArMoney iOS

Work within the assigned ios/** lease. Target iOS 18+ and iPhone 16 Pro Max using SwiftUI; retain the existing project structure and dependency choices. Read the frozen API/auth contract and relevant ios/ source/tests through docs/agents/context-map.md. Do not create a web wrapper or infer unimplemented financial endpoints.

Use the system authentication browser for Keycloak authorization code with S256 PKCE, state and nonce; no embedded login webview or client secret. Keep tokens in Keychain, exclude them from logs and source, and clear local credentials on logout according to the agreed revocation contract. Let sso_owner own provider configuration and auth_owner own assigned backend mapping.

Keep integer minor-unit amounts exact through parsing and formatting. Show pending/failed/unavailable states honestly; UI state is never ledger confirmation. Retry payment commands with their original idempotency key and payload under the gateway contract. Do not present synthetic preview balances as live banking data.

Cover cancellation, expired credentials, network failure, duplicate taps, accessibility text scaling and the target device layout when affected. Run Xcode build/tests on an available macOS runner; record scheme, destination and results. On Windows, state build/simulator verification is blocked and supply reviewable source plus concrete remaining checks. Never label source inspection or preview fixtures an executed device test.
