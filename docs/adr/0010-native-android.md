# ADR 0010: Native Android client

Status: accepted design; runtime evidence is recorded in the delivery PR.

## Decision

Build a Kotlin/Jetpack Compose Android application in android/ with the same
gateway contracts and financial behavior as the SwiftUI client. It is a separate
Gradle build, so backend tasks do not require an Android SDK. Reuse the pinned
repository wrapper via `./gradlew -p android`; target API36 with minimum API26.
AGP8.13.2 and Kotlin2.2.21 are pinned, with the matching Compose compiler plugin.
See [AGP compatibility](https://developer.android.com/build/releases/agp-8-13-0-release-notes)
and [Compose compiler guidance](https://developer.android.com/jetpack/androidx/releases/compose-kotlin).

Keycloak gains a separate public armoney-android client, restricted to code flow,
S256 PKCE and exact com.armoney.android:/oauth/callback redirect. Browser login
uses [AppAuth](https://github.com/openid/AppAuth-Android), never a WebView or a
client secret. The same issuer/subject maps to the same bank identity across
iOS and Android. Auth accepts only the two named native clients and their matching
authorized-party claim when present; audience, issuer, active status and time
validation remain required. Existing realms require an explicit client update;
startup import does not overwrite them.

The bank session remains opaque and lasts 30 minutes. Logout revokes that session,
not all provider sessions or sessions on another device. Provider refresh tokens
are not retained. Browser callback state is bound to the pending authorization
and consumed once. The HTTP client does not follow credential-bearing redirects.

Bank sessions and uncertain payment commands use Keystore-backed AES-GCM encryption
in app-private storage excluded from backup. Payment intent is scoped to configured
origin and verified identity and survives sign-out/process death. An attempt marker
must be durably saved before HTTP; a refusal on a later attempt cannot prove that an
earlier request did not commit. Amounts use checked Long minor units. The backend
remains the financial authority; unavailable balances are never shown as zero.

Debug network configuration may trust a user-installed CA for the existing local
HTTPS edge. Release uses system trust roots. Neither build permits cleartext,
arbitrary trust managers or hostname-verification bypasses. This follows Android's
[network security configuration](https://developer.android.com/privacy-and-security/security-config).
No analytics, push service, SMS service or other external provider is introduced.

## Verification and limits

JVM tests cover exact amounts, phone syntax, malformed responses, identity and
uncertain-command handling. Emulator tests exercise actual Android Keystore and
native screens. CI builds a debug APK and runs lint, JVM and instrumentation tests;
browser acceptance covers both Keycloak clients with disposable identities.
Backend regression/Compose tests preserve existing behavior. Physical Android
device and LAN certificate/browser testing are separate from emulator evidence.
CI APKs use an invalid placeholder origin and are not production-signed releases.

The client inherits current product limits: operator-attested phone numbers,
latest-100 history/inbox, foreground/manual notifications, no funding API or FX.
