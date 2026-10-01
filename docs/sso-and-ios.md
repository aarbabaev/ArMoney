# ArMoney: SSO and native clients

The SwiftUI app targets iOS 18+ and iPhone 16 Pro Max. See [ios/README.md](../ios/README.md)
for Xcode build, signing and app configuration. [ADR 0008](adr/0008-native-ios-and-keycloak-sso.md)
defines identity mapping and logout limitations.

Android uses the same HTTPS edge and realm through its own public client. See
[android/README.md](../android/README.md) for Android Studio, debug CA trust and
local origin configuration, and [SSO client updates](../sso-service/README.md)
for an existing realm. Neither native app contains the private introspection secret.

## Run the optional SSO stack

Keep existing .env database passwords and INTERNAL_AUTH_KEY unchanged. Add independent
random SSO_CLIENT_SECRET (at least 32 characters), SSO_DB_PASSWORD and
SSO_ADMIN_PASSWORD. Never commit .env or enter these values in the mobile app.
The .env.example placeholders must be replaced before use.

For a Mac/iPhone on your LAN, set ARMONEY_HOST to the Windows computer's stable LAN
IPv4 address or resolvable LAN hostname, and ARMONEY_BIND_ADDRESS to the Windows LAN IPv4 address.
For host-only use, both can remain localhost/127.0.0.1 as in the example. Configure
a DHCP reservation or stable local DNS before creating users: changing the issuer
creates a different identity namespace; never silently relink by email.

From the repository directory:

```powershell
docker compose -f compose.yaml -f compose.sso.yaml up --build -d
New-Item -ItemType Directory -Force local-certs | Out-Null
docker compose -f compose.yaml -f compose.sso.yaml cp edge:/data/caddy/pki/authorities/local/root.crt local-certs/armoney-root.crt
```

Use https://YOUR_ARMONEY_HOST:8443 as the app origin. The issuer is that origin
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

The native apps use the system browser and separate public clients: armoney-ios
and armoney-android. Keycloak returns a short-lived provider token, which auth-service
exchanges for an opaque 30-minute ArMoney session. Logout revokes that local session
and offers browser logout; it is not global revocation across all devices. Existing
local sessions survive provider-side logout/disablement until local revocation or
expiry. Refresh tokens and back-channel logout are outside this slice.

## Validation boundaries

The Python SSO smoke script is CI-only: it modifies a disposable realm, creates a
synthetic user and adds an exact loopback redirect for browser automation. That
redirect and the test admin port are not part of the shipped deployment. Browser
certificate bypass exists only in this synthetic CI fixture; HTTP assertions use
the exported CA and both native applications always validate TLS.

Physical iPhone installation requires your Apple signing team and device approval
in Xcode. Neither those credentials nor your certificate trust are configured by CI.

## Windows LAN checks and recovery

Docker Desktop must use Linux containers. Keep the same explicit Compose file set
for startup, inspection and updates; add the ignored `compose.override.yaml` after
`compose.sso.yaml` only when its existing loopback database bindings are needed.
Do not switch project names or delete volumes to fix connectivity. The repository
is ArMoney, while Compose remains `arman-bank` to preserve persistent data.

Run these read-only checks from the repository directory after deployment:

```powershell
docker compose -f compose.yaml -f compose.sso.yaml ps
curl.exe http://127.0.0.1:8080/health/ready
$bankOrigin = 'https://YOUR_ARMONEY_HOST:8443'
curl.exe --cacert local-certs/armoney-root.crt "$bankOrigin/health/ready"
curl.exe --cacert local-certs/armoney-root.crt "$bankOrigin/sso/realms/armoney/.well-known/openid-configuration"
curl.exe --cacert local-certs/armoney-root.crt -o NUL -w '%{http_code}' "$bankOrigin/sso/admin/"
curl.exe --cacert local-certs/armoney-root.crt -o NUL -w '%{http_code}' "$bankOrigin/v1/auth/me"
```

Replace the placeholder with the configured hostname/address, not `localhost` on a
remote Mac. Expected results: gateway readiness UP, discovery `issuer` equal to the
origin plus `/sso/realms/armoney`, admin 404, and unauthenticated `/v1/auth/me` 401.
Gateway readiness checks only the gateway; use service readiness or CI acceptance
for downstream evidence. Do not use `curl -k` as a successful trust check.

If Windows-local checks succeed but another device times out, check the bound LAN
address, Private network profile, a TCP 8443 firewall rule restricted to LocalSubnet,
name resolution and Wi-Fi client isolation. A numeric IP can reach the listener
while omitting TLS SNI. The shipped Caddy global option `default_sni {$ARMONEY_HOST}`
selects the configured certificate for that case. It does not bypass certificate
validation: the certificate must still cover the requested address and its CA must
be trusted. A prior deployment used this as a local workaround; it is now part of
the tracked edge configuration. Deploy the tracked file before assuming the fix is
present. Preserve `edge-data`: recreating the CA requires retrusting its new public
root on every client.

A changed issuer is a changed identity namespace. Prefer a stable address/DNS name
and correct the configuration consistently rather than creating replacement users
or modifying identity mappings to hide a mismatch.

## Evidence scope

The 2026-09-30 local Windows deployment was checked for six backend readiness
responses, HTTPS with the exported CA, the expected discovery issuer, hidden admin
routes and rejection of unauthenticated identity reads. This records backend
connectivity evidence, not a guarantee about later container state. The setup fix
also has an isolated IPv4 TLS check. The repository's disposable CI checks and
macOS simulator tests have their own exact-revision results. Interactive simulator
SSO and physical-iPhone signing, trust, login and wallet flows require separate
Mac/device validation; they are not claimed by these Windows checks.
