# ArMoney: SSO and native iOS

The SwiftUI app targets iOS 18+ and iPhone 16 Pro Max. See [ios/README.md](../ios/README.md)
for Xcode build, signing and app configuration. [ADR 0008](adr/0008-native-ios-and-keycloak-sso.md)
defines identity mapping and logout limitations.

## Run the optional SSO stack

Keep existing .env database passwords and INTERNAL_AUTH_KEY unchanged. Add independent
random SSO_CLIENT_SECRET (at least 32 characters), SSO_DB_PASSWORD and
SSO_ADMIN_PASSWORD. Never commit .env or enter these values in the mobile app.
The .env.example placeholders must be replaced before use.

For a Mac/iPhone on your LAN, set ARMONEY_HOST to the Windows computer's stable LAN
IPv4 address (for example 192.168.1.50), and ARMONEY_BIND_ADDRESS to that same address.
For host-only use, both can remain localhost/127.0.0.1 as in the example. Configure
a DHCP reservation or stable local DNS before creating users: changing the issuer
creates a different identity namespace; never silently relink by email.

From the repository directory:

```powershell
docker compose -f compose.yaml -f compose.sso.yaml up --build -d
New-Item -ItemType Directory -Force local-certs | Out-Null
docker compose -f compose.yaml -f compose.sso.yaml cp edge:/data/caddy/pki/authorities/local/root.crt local-certs/armoney-root.crt
```

Use https://YOUR_WINDOWS_LAN_IP:8443 as the app origin. The issuer is that origin
plus /sso/realms/armoney. Explicit -f arguments do not automatically include the
user's compose.override.yaml; add it explicitly if you also need existing loopback
DataGrip mappings. Never include compose.sso-ci.yaml outside disposable CI.

Allow TCP 8443 only on the Windows Private network profile, scoped to LocalSubnet.
Do not open database ports or configure router port forwarding. Ensure Wi-Fi client
isolation is disabled and the Windows computer remains awake while using the app.

## Trust the local HTTPS certificate

Export only root.crt, never the CA private key in the edge-data volume. Transfer
the public certificate securely to the Mac/iPhone. On macOS import it into Keychain
Access and trust it for SSL. On iPhone install the certificate profile, then enable
full trust under Settings > General > About > Certificate Trust Settings. The app
also requests Local Network permission. Simulator trust can be installed with:

```sh
xcrun simctl keychain booted add-root-cert /absolute/path/armoney-root.crt
```

Trust this development CA only on your own devices and remove that trust when it
is no longer needed. The app has no accept-all-TLS delegate or HTTP fallback.

## Users and administration

Keycloak owns SSO credentials and its own database. Its admin console is deliberately
not exposed through the edge; use the container's kcadm tool through docker compose
exec for administration (see [Keycloak administration](https://www.keycloak.org/docs/latest/server_admin/index.html#admin-cli)).
Realm self-registration is configured in the checked-in realm; use the browser
registration link if enabled. SSO users are new identities; existing password users
are not automatically linked even if their email matches. Do not recreate funded
identities to work around migration needs.

Realm import runs only when the realm is absent. Editing its JSON or .env client
secret does not update an existing imported realm; apply deliberate admin changes
and update auth-service credentials together. Never delete the SSO database or
other volumes to rotate a secret.

## Session behavior

The native app uses the system authentication browser and a public client named
armoney-ios. Keycloak returns a short-lived provider token, which auth-service
exchanges for an opaque 30-minute ArMoney session. Logout revokes that local session
and offers browser logout; it is not global revocation across all devices. Existing
local sessions survive provider-side logout/disablement until local revocation or
expiry. Refresh tokens and back-channel logout are outside this slice.

## Validation boundaries

The Python SSO smoke script is CI-only: it modifies a disposable realm, creates a
synthetic user and adds an exact loopback redirect for browser automation. That
redirect and the test admin port are not part of the shipped deployment. Browser
certificate bypass exists only in this synthetic CI fixture; HTTP assertions use
the exported CA and the iOS application always validates TLS.

Physical iPhone installation requires your Apple signing team and device approval
in Xcode. Neither those credentials nor your certificate trust are configured by CI.
