---
name: bank-android
description: Build or review the native ArMoney Kotlin Android client, gateway integration and Keycloak login.
---

# Native Android client

Read the assigned android/ files, docs/p2p-contract.md and gateway OpenAPI.
Android is a separate Gradle build; backend checks must not require an Android SDK.
Use Kotlin and Jetpack Compose. Keep the same financial/API semantics as iOS,
without treating its platform-specific implementation as an Android design.

Use browser authorization code with S256 PKCE and a public Keycloak client; no
embedded secret or WebView login. Validate callback state and exact redirect.
Use only configured HTTPS endpoints, bounded responses/timeouts and no credential
redirects. Debug builds may trust installed user CAs for local HTTPS; release
must not silently enable cleartext or trust arbitrary user certificates.

Persist bank sessions and uncertain payment commands using Android Keystore-backed
authenticated encryption, app-private storage and disabled backup. Scope financial
intent by origin and verified identity. Persist the attempt marker before HTTP;
a later refusal cannot disprove an earlier uncertain commit. Preserve key/payload
across process death and sign-out. Never turn unavailable balances into zero.

Use Long minor units with checked decimal parsing, not floating point. Guard async
results against session changes; a nullable SSO email is valid. Keep notifications
in-app/foreground, without adding external providers. Test exact money, callback
validation, HTTP failure/retry state and secure-storage persistence. Separate JVM,
emulator, browser and physical-device evidence in the handoff. Obtain the shared
build lease before Gradle; do not operate Docker or change a live realm.
