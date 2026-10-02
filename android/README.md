# ArMoney Android

Native Kotlin/Jetpack Compose client, Android 8/API 26 or later. Open this
directory in Android Studio. Use Java 21 and Android SDK platform 36.
The repository Gradle 8.14.3 wrapper runs this independent build:

```powershell
.\gradlew.bat -p android :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
.\gradlew.bat -p android :app:connectedDebugAndroidTest
```

Create ignored `android/local.properties` with `sdk.dir` if needed and
`armoney.origin=https://YOUR-LAN-HOST` (no trailing slash/path). The default
`https://configure.invalid` deliberately cannot sign in. Never commit credentials
or signing keys. The phone and browser must reach the configured LAN HTTPS origin;
localhost on a phone refers to that phone. The same origin serves the gateway
and `/sso/realms/armoney`. Debug builds trust user-installed local CAs; release
builds trust system CAs only. Certificate and hostname verification stay enabled.

AppAuth constructs browser authorization requests with state and S256 PKCE for
public client `armoney-android`, redirect `com.armoney.android:/oauth/callback`.
A private encrypted pending request survives activity recreation, expires after
ten minutes, and is consumed before exchanging the code. The callback rejects
wrong redirect/state/issuer and repeated parameters. Provider tokens are not
stored. `/v1/auth/sso` exchanges the access token for an opaque bank session;
`/v1/auth/me` establishes the stable owner. Email may be absent.

Wallets, exact-phone recipient review, confirmed transfers, recent history and
details, foreground in-app notifications, profile name/phone and sign-out use
the gateway contract. Amounts use exact signed-64-bit minor units. Balance errors
display unavailable. No funding, SMS or push provider is added. Refresh while
foregrounded or manually; payment status remains pending until the server reports
ledger confirmation.

Keystore AES-GCM encrypts atomic records in no-backup storage. Saved transfer
keys and payloads are scoped to origin and verified identity, and an attempt
marker is persisted before HTTP. Timeouts and unknown responses preserve the
same command for retry. A later refusal cannot erase earlier uncertainty.
Logout preserves pending payments and removes the local bank session, with
best-effort server revocation. Browser SSO may remain active. Uninstalling or
clearing application data destroys local recovery information; consult payment
history before sending a replacement. Unreadable storage fails closed.

JVM tests cover exact money, input/identity, callback and refusal policy.
Instrumentation tests exercise real Android Keystore persistence, scope isolation
and the native sign-in screen. These tests do not constitute real-user browser,
LAN certificate or physical-device acceptance. See [Android contract](../docs/android-contract.md)
and [P2P contract](../docs/p2p-contract.md) for the frozen contract and integration evidence.
