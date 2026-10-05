# Android implementation contract

Native Kotlin/Jetpack Compose in a separate android/ Gradle build. Functional
scope matches the iOS slice: browser SSO, wallets/open/balances, exact phone
recipient confirmation, P2P/history/details, in-app notifications/read, profile
name/immutable registration phone/verification status and account/sign-out. See p2p-contract.md and the
gateway OpenAPI. No new external provider or backend financial behavior.

Android 8/API26 minimum; compile/target36. AGP8.13.2, Kotlin2.2.21, Compose compiler
plugin matching Kotlin; use existing Gradle8.14.3 wrapper via root -p android.
Origin comes from a local ignored property, defaults to an invalid HTTPS placeholder.
Android Studio opens android/. Build outputs/local.properties/signing material are ignored.

SSO client armoney-android is public, authorization code only with S256 PKCE;
redirect exactly com.armoney.android:/oauth/callback and logout URI
com.armoney.android:/oauth/logout. Audience armoney-api (and armoney-auth for
introspection). Existing issuer, token exchange /v1/auth/sso, opaque bank session,
issuer+subject identity mapping and local logout semantics remain unchanged.
Auth accepts only armoney-ios or armoney-android, with matching azp when present.
Browser uses the same LAN HTTPS origin as the gateway; no localhost on a phone.

Use AppAuth Android for browser flow, strict state/redirect handling and PKCE.
Never persist provider refresh tokens or embed a client secret. Pending authorization
must survive activity recreation without accepting an unsolicited/replayed callback.
Bank session and pending immutable payment command use Keystore AES-GCM encryption
in app-private no-backup storage. An attempt marker is durable BEFORE a POST. Known
pre-acceptance refusal is discardable only without a prior uncertain attempt. Store
by origin+verified identity; logout preserves uncertain payment intent. Fail closed
on unreadable storage. Async responses from an old session must not update a new one.

HTTPS certificate validation stays enabled. Debug-only network security config
may trust a user-installed local CA; release uses system roots and no cleartext.
No generic trust-all manager, hostname bypass, WebView login or analytics/push SDK.

Acceptance: assemble debug APK, lint, JVM tests for exact Long money/phone/identity/
payment recovery, HTTP contracts and OAuth checks; emulator tests for actual Keystore
persistence/owner isolation and a native screen smoke. Disposable Keycloak browser
tests must exercise both clients and preserve existing iOS acceptance. Physical
device and LAN certificate/browser validation are separate evidence.

Currency policy: AED only for new wallets and transfers, with amounts in integer
fils (100 fils = 1 AED). There is no currency selector or FX. Existing encrypted
uncertain commands retain their original currency, key and payload; the client
never relabels or discards them to satisfy the new policy. See ADR 0011.
