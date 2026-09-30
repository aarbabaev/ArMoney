# ArMoney for iPhone

Native SwiftUI client, iOS 18 minimum, designed for iPhone 16 Pro Max. The checked-in Xcode project uses Swift 6 with complete concurrency checking and Apple frameworks only. No package resolution, CocoaPods or XcodeGen is required.

Implemented: browser SSO, profile creation/editing, owner-scoped wallet listing and creation in EUR/USD/GBP, actual PENDING/READY provisioning status, expiry handling and sign-out. There is no balance, payment or P2P UI because the public backend does not expose those capabilities. Creating a wallet does not create funds.

## Open and configure on a Mac

1. Install Xcode 16 or newer with an iOS 18+ simulator runtime. Open `ios/ArMoney.xcodeproj`, select the shared **ArMoney** scheme and **iPhone 16 Pro Max** destination.
2. Copy `ios/Config/Local.xcconfig.example` to `ios/Config/Local.xcconfig` (ignored by Git). Set `BANK_ORIGIN` to the exact HTTPS edge origin, for example `https:$(SLASH)$(SLASH)bank-host.local:8443`. The slash substitution is necessary because xcconfig treats `//` as a comment. This one origin supplies both `/v1/*` and `/sso/realms/armoney`. It is build configuration, not an arbitrary in-app endpoint selector.
3. Make that hostname resolvable and reachable from the Mac and iPhone on the same LAN. The certificate name and the Keycloak issuer must match it. `localhost` on a physical iPhone means the iPhone itself; do not use the Windows backend's `localhost` URL. A simulator can use the Mac's network, but a backend on Windows still needs its reachable LAN hostname. Do not expose the private backend service ports directly.
4. For Caddy's development internal CA, obtain its **public root certificate only** from the backend owner. Verify its fingerprint through a trusted channel. Trust that CA in macOS Keychain Access. Install the public root certificate on the iPhone and enable its full trust in Settings → General → About → Certificate Trust Settings. The simulator has a separate trust store: install the same root there, or use `xcrun simctl keychain booted add-root-cert /path/to/root.crt`. Never distribute the CA private key. The app uses ordinary system TLS validation; no ATS exception or certificate-validation bypass is installed.
5. Run. Allow local network access when prompted. Sign-in opens Apple's authentication browser and can reuse its SSO cookies. Configure providers and test identities on Keycloak, not inside the app.

For a real iPhone, connect/pair it with Xcode, enable Developer Mode when requested, select your Apple ID's **Personal Team** under Signing & Capabilities, and select the phone destination. A free Personal Team supports local development subject to Apple's provisioning limits; distribution requires the appropriate Apple program. If your team needs a unique bundle ID, change the target's bundle identifier while retaining the registered callback scheme. No signing key or team identifier is checked in. Windows cannot build, sign or run the iOS simulator.

## Authentication contract

Keycloak client `armoney-ios` is public, authorization-code only, PKCE S256 required. Exact login redirect: `com.armoney.ios:/oauth/callback`. Exact post-logout redirect: `com.armoney.ios:/oauth/logout`. The app generates a new cryptographic verifier, state and nonce per attempt, checks exact callback/state, rejects duplicate callback parameters, and exchanges the code at the configured issuer's token endpoint. ID tokens are not used for authorization or persisted; the backend independently verifies the access token supplied to `POST /v1/auth/sso`.

Only the resulting 30-minute opaque bank session is saved, using Keychain `WhenUnlockedThisDeviceOnly`, scoped to the configured origin. Tokens never go into UserDefaults, URLs, app logs or source. API/token HTTP uses an ephemeral session, a 20-second request timeout, a 30-second resource timeout, a 1 MiB response limit, and refuses redirects. Browser SSO deliberately uses persistent browser cookies. Expiry or an API 401 clears local state; a later login reopens browser SSO instead of storing refresh tokens.

Sign-out clears the local session, attempts `/v1/auth/logout`, then opens Keycloak end-session with `client_id`, `post_logout_redirect_uri` and state. Keycloak may request logout confirmation because no ID-token hint is retained. Cancelling browser logout can leave browser SSO active, and the app explains this. If backend revocation cannot be confirmed, the app reports that the opaque server session may remain valid until its expiry.

## Validation

From repository root on macOS:

```sh
xcodebuild -project ios/ArMoney.xcodeproj -scheme ArMoney \
  -destination 'platform=iOS Simulator,name=iPhone 16 Pro Max' \
  -derivedDataPath /tmp/armoney-ios-derived CODE_SIGNING_ALLOWED=NO test
```

Use `xcrun simctl list devices available` to find an installed iOS 18+ destination if that exact device is missing. Simulator tests do not require signing. Unit tests cover the RFC 7636 PKCE vector, secure-random shape/uniqueness, callback rejection, origin validation, expiry/JSON decoding, pending/ready wallets, bearer API requests and 401/503/offline failures through an immutable URLProtocol fixture. They do not claim real provider or device coverage.

Source was authored on Windows; local Xcode build, simulator layout and physical-device SSO remain unverified until the macOS CI/device checks run. Before device acceptance, exercise: browser login cancellation; fresh provider login and browser SSO reuse; profile 404 onboarding; duplicate wallet taps and retry after offline creation; pending-to-ready refresh; expiry/401; offline retry; server plus browser logout; large Dynamic Type and VoiceOver on iPhone 16 Pro Max. The UI disables commands during an operation and uses real backend responses for wallet state. Public endpoints are unchanged apart from the agreed SSO exchange.

Reference APIs: [Apple authentication sessions](https://developer.apple.com/documentation/authenticationservices/aswebauthenticationsession), [browser cookie behavior](https://developer.apple.com/documentation/authenticationservices/aswebauthenticationsession/prefersephemeralwebbrowsersession), [Keychain accessibility](https://developer.apple.com/documentation/security/ksecattraccessiblewhenunlockedthisdeviceonly), [Keycloak logout](https://www.keycloak.org/docs/latest/server_admin/#_oidc-logout).
