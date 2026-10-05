# ArMoney for iPhone

Native SwiftUI client, iOS 18 minimum, designed for iPhone 16 Pro Max. The checked-in Xcode project uses Swift 6 with complete concurrency checking and Apple frameworks only. No package resolution, CocoaPods or XcodeGen is required.

The native client has four tabs: Wallets, Transfers, Notifications, and Profile. It uses browser SSO, real owner-scoped balances, phone recipient lookup and confirmation, P2P submission, bounded recent history, in-app notifications, profile/phone editing, expiry handling, and sign-out. These screens require the backend revision implementing the [P2P contract](../docs/p2p-contract.md); this source description is not a deployment or device-acceptance claim. Creating a wallet does not create funds.

## Open and configure on a Mac

1. Install Xcode 16 or newer with an iOS 18+ simulator runtime. Open `ios/ArMoney.xcodeproj`, select the shared **ArMoney** scheme and **iPhone 16 Pro Max** destination.
2. Copy `ios/Config/Local.xcconfig.example` to `ios/Config/Local.xcconfig` (ignored by Git). Set `BANK_ORIGIN` to the exact HTTPS edge origin, for example `https:$(SLASH)$(SLASH)bank-host.local:8443`. The slash substitution is necessary because xcconfig treats `//` as a comment. This one origin supplies both `/v1/*` and `/sso/realms/armoney`. It is build configuration, not an arbitrary in-app endpoint selector. Keep Local.xcconfig alongside Base.xcconfig in ios/Config: Base includes it by filename. It does not need to appear in the Xcode navigator, belong to a target or be copied into app resources. Reserved .invalid example hosts are rejected with a configuration error.
3. Make that hostname resolvable and reachable from the Mac and iPhone on the same LAN. The certificate name and the Keycloak issuer must match it. `localhost` on a physical iPhone means the iPhone itself; do not use the Windows backend's `localhost` URL. A simulator can use the Mac's network, but a backend on Windows still needs its reachable LAN hostname. Do not expose the private backend service ports directly.
4. For Caddy's development internal CA, obtain its **public root certificate only** from the backend owner. Verify its fingerprint through a trusted channel. Trust that CA in macOS Keychain Access. Install the public root certificate on the iPhone and enable its full trust in Settings → General → About → Certificate Trust Settings. The simulator has a separate trust store: install the same root there, or use `xcrun simctl keychain booted add-root-cert /path/to/root.crt`. Never distribute the CA private key. The app uses ordinary system TLS validation; no ATS exception or certificate-validation bypass is installed.
5. Run. Allow local network access when prompted. Sign-in opens Apple's authentication browser and can reuse its SSO cookies. Configure providers and test identities on Keycloak, not inside the app.

For a real iPhone, connect/pair it with Xcode, enable Developer Mode when requested, select your Apple ID's **Personal Team** under Signing & Capabilities, and select the phone destination. A free Personal Team supports local development subject to Apple's provisioning limits; distribution requires the appropriate Apple program. If your team needs a unique bundle ID, change the target's bundle identifier while retaining the registered callback scheme. No signing key or team identifier is checked in. Windows cannot build, sign or run the iOS simulator.

## Authentication contract

Keycloak client `armoney-ios` is public, authorization-code only, PKCE S256 required. Exact login redirect: `com.armoney.ios:/oauth/callback`. Exact post-logout redirect: `com.armoney.ios:/oauth/logout`. The app generates a new cryptographic verifier, state and nonce per attempt, checks exact callback/state, rejects duplicate callback parameters, and exchanges the code at the configured issuer's token endpoint. ID tokens are not used for authorization or persisted; the backend independently verifies the access token supplied to `POST /v1/auth/sso`.

The resulting 30-minute opaque bank session is saved using Keychain `WhenUnlockedThisDeviceOnly`, scoped to the configured origin. A separate Keychain item preserves an uncertain payment command, scoped to both that origin and the identity returned by the authenticated bank API; it survives session expiry and sign-out. Tokens never go into UserDefaults, URLs, app logs or source. API/token HTTP uses an ephemeral session, a 20-second request timeout, a 30-second resource timeout, a 1 MiB response limit, and refuses redirects. Browser SSO deliberately uses persistent browser cookies. Expiry or an API 401 clears the session and visible account state while preserving the separate uncertain-command item; a later login reopens browser SSO instead of storing refresh tokens.

Sign-out clears the local session, attempts `/v1/auth/logout`, then opens Keycloak end-session with `client_id`, `post_logout_redirect_uri` and state. Keycloak may request logout confirmation because no ID-token hint is retained. Cancelling browser logout can leave browser SSO active, and the app explains this. If backend revocation cannot be confirmed, the app reports that the opaque server session may remain valid until its expiry.

## Banking screens

- **Wallets:** shows the AED wallet, shows its provisioning state and its real ledger-backed balance, and opens an AED wallet. A failed balance request shows unavailable, never a fabricated zero. AED is the only supported currency; there is no currency selector or foreign exchange.
- **Transfers:** resolves an exact international phone number, displays the recipient's name and number, then requires an explicit confirmation before submitting from a ready wallet in the same currency. Amounts use positive Int64 minor units; decimal input accepts a dot and at most two fractional digits, without floating-point conversion or rounding. Recent history contains at most 100 visible transfers; incoming entries appear only after completion. Details show status, dates, and payment reference. Refresh to obtain a pending payment's latest outcome.
- **Notifications:** shows at most 100 owner-scoped durable transfer notifications and allows marking each as read. Refresh happens on app opening, foreground entry, or manual refresh. These are in-app updates, not OS push notifications or guaranteed background delivery. Notifications do not independently establish that money moved.
- **Profile:** edits the display name and saves an E.164 phone number, shows the saved number's verified/unverified state, and provides identity details and sign-out.

Phone entry must include `+` and the country code, with no spaces or national-number guessing. Saving a phone number **does not verify ownership and sends no SMS**. An operator must check ownership outside the app and use the backend's explicit verification procedure for the exact pending number. There is no verification button or self-service verification endpoint. Changing the number clears verification. Only verified numbers resolve as recipients; the recipient must already have a ready wallet in the transfer currency. See the [backend P2P contract](../docs/p2p-contract.md) for operator and API prerequisites.

There is no public top-up or funding screen, external payment provider, broker, SMS integration, or push service. Balances are never seeded or simulated by the app. Synthetic funding belongs only in disposable acceptance environments, never the user's persistent databases.

## Uncertain transfer recovery

Before the first payment request, the app saves the original idempotency key and canonical command payload in device-only Keychain. Before each HTTP attempt, it durably marks the command as attempted; submission is blocked if that write fails. A crash after this write is treated conservatively as uncertain, even if the request might not have reached the server. Duplicate taps cannot create another command while the first submission is unresolved. The attempt marker survives relaunch, logout, and reauthentication; older records without the marker are treated as already attempted.

If a response is lost, the server returns an unknown/malformed outcome, or credentials expire, the saved command remains. On the next session, the app verifies the authenticated identity before loading that identity's command at the configured origin. Use **Retry original transfer**: it sends the same key and payload. Editing a draft never mutates that command. A different signed-in identity does not see or reuse it. Changing the configured origin creates a separate storage scope.

A matching durable `PENDING`, `COMPLETED`, or `REJECTED` response establishes acceptance and removes the uncertain command; a pending payment remains visible in recent history and can be refreshed by its payment ID. `PENDING` is not financial success. Only `COMPLETED` reports ledger-confirmed completion. The separate command is not automatically discarded on timeout, 401, idempotency conflicts, unknown responses, 5xx, or decoding failure.

Payment error bodies are bounded to 4 KiB and parsed as structured codes. Only a recognized refusal from the **first attempt**, with no prior attempted/uncertain request, can offer **Discard refused request**. Recognized pre-acceptance combinations are: 400 `invalid_request`/`request_rejected`, 404 `not_found`, and 409 `recipient_changed`/`wallet_ineligible`. Discard is explicit and clears recipient confirmation, so a corrected transfer requires a new lookup and review. After any prior attempt, even a recognized 404 or 409 refusal from a retry keeps the command locked: an earlier delayed request may still commit. Retry the original reference or have it investigated; a retry refusal never proves that the earlier attempt failed. A 409 `idempotency_conflict`, unknown code, wrong content type, malformed/oversized body, or network/server failure keeps the original command saved. No local "success" or replacement payment ID is generated to conceal uncertainty.

## Validation

From repository root on macOS:

```sh
xcodebuild -project ios/ArMoney.xcodeproj -scheme ArMoney \
  -destination 'platform=iOS Simulator,name=iPhone 16 Pro Max' \
  -derivedDataPath /tmp/armoney-ios-derived CODE_SIGNING_ALLOWED=YES CODE_SIGN_IDENTITY=- test
```

Use `xcrun simctl list devices available` to find an installed iOS 18+ destination if that exact device is missing. Simulator tests use a local ad-hoc signature (`CODE_SIGN_IDENTITY=-`) so the app carries the identity entitlements required by real Keychain access. No Apple Developer account, distribution certificate, or provisioning profile is required for this simulator run. Do not disable code signing for these tests: an unsigned simulator app can compile and launch while Keychain operations fail. The CI script prints the resulting app entitlements for diagnosis; Keychain failures include only the operation and numeric OSStatus, never item contents. Unit tests cover the RFC 7636 PKCE vector, secure-random shape/uniqueness, callback rejection, origin validation, expiry/JSON decoding, pending/ready wallets, bearer API requests and 401/503/offline failures through an immutable URLProtocol fixture. Additional tests cover exact Int64 money parsing/formatting and overflow, E.164 input, payment JSON, matching durable responses, command serialization with unchanged idempotency key and payload, isolated Keychain checks for identity/origin separation and survival of session clearing, structured refusal classification, durable prior-attempt markers and delayed/lost-original refusal recovery, and SSO identities with null email. Null email is shown as unavailable in Profile; the stable identity UUID still scopes financial state and recovery. They do not claim real provider, complete end-to-end recovery, or device coverage.

The macOS CI job builds the app and runs simulator unit tests; consult the exact revision and job result before claiming it passed. Those tests do not establish interactive simulator layout, real browser SSO or physical-device acceptance. Windows-side HTTPS and discovery checks likewise do not establish device acceptance. Before device acceptance, exercise: browser login cancellation; fresh provider login and browser SSO reuse; profile 404 onboarding; duplicate wallet taps and retry after offline creation; pending-to-ready refresh; expiry/401; offline retry; server plus browser logout; large Dynamic Type and VoiceOver on iPhone 16 Pro Max. Also exercise: unavailable balances; unverified and verified phone states; failed recipient lookup; recipient confirmation; duplicate submit taps; lost payment response followed by app relaunch and reauthentication as the same identity; signing in as a different identity without exposing the previous command; pending-to-terminal history refresh; insufficient funds; notifications and repeated mark-as-read; and foreground refresh. The UI disables commands during an operation, uses backend responses for financial state, and rejects stale asynchronous results after a session changes. New routes follow the frozen P2P contract.

Reference APIs: [Apple authentication sessions](https://developer.apple.com/documentation/authenticationservices/aswebauthenticationsession), [browser cookie behavior](https://developer.apple.com/documentation/authenticationservices/aswebauthenticationsession/prefersephemeralwebbrowsersession), [Keychain accessibility](https://developer.apple.com/documentation/security/ksecattraccessiblewhenunlockedthisdeviceonly), [Keycloak logout](https://www.keycloak.org/docs/latest/server_admin/#_oidc-logout).

## Configuration and simulator troubleshooting

If the app asks for a valid origin, check the file on disk first. From the repository
root on the Mac, create the local file once, then edit its BANK_ORIGIN value:

```sh
cp ios/Config/Local.xcconfig.example ios/Config/Local.xcconfig
```

Do not overwrite an existing local configuration. The value must be the same
HTTPS origin used by the edge certificate and Keycloak issuer, with port 8443,
without `/v1` or `/sso` appended. Keep `https:$(SLASH)$(SLASH)` in xcconfig; literal
`https://` is parsed as a comment. Rebuild after changes: this is a bundled build
setting, not a live setting. To inspect the effective value:

```sh
xcodebuild -project ios/ArMoney.xcodeproj -scheme ArMoney -showBuildSettings | grep BANK_ORIGIN
```

If `xcrun simctl` is unavailable, the selected developer directory may be the
standalone Command Line Tools rather than full Xcode. Check and select the actual
installed Xcode path, then launch Xcode to complete its first-run setup:

```sh
xcode-select -p
sudo xcode-select --switch /Applications/Xcode.app/Contents/Developer
xcrun simctl list devices available
```

Boot the intended simulator before `xcrun simctl keychain booted add-root-cert`.
Trusting the CA only in the Mac keychain does not trust it in the simulator. If
several simulators are booted, use the intended device UUID instead of `booted`.
A certificate error needs correct name/CA trust, never an app TLS bypass. A timeout
needs LAN reachability, the Windows Private-profile firewall and edge binding
checks in [the backend runbook](../docs/sso-and-ios.md). An API 401 requires a fresh
login; wallet PENDING requires ledger recovery/refresh and is not financial success.