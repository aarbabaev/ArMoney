# Try profiles and wallets in Postman

Use the existing login request to obtain access_token. All requests below use
Authorization: Bearer {{access_token}} against http://localhost:8080.
Gateway adds internal service identity; clients do not supply it.

New password registration requires `POST /v1/auth/register` with
`{"email":"customer@example.test","password":"a-long-unique-password","phone_number":"+971501234567"}`.
Use an available UAE mobile number; login still sends only email and password.
Native SSO registration collects the same number in Keycloak. Creating a profile
copies the immutable registration phone. No SMS is sent and ownership is not
verified; confirm the recipient name before a transfer. See [ADR 0014](adr/0014-uae-registration-phone.md).

1. PUT /v1/users/me with JSON {"display_name":"Demo User"} returns 200 and the profile.
2. GET /v1/users/me returns 200, or 404 if no profile was created.
3. POST /v1/wallets with JSON {"currency":"AED"} returns 202 with PENDING (or 200 if already READY / CLOSED).
4. Repeat step 3: the wallet id is unchanged.
5. Poll GET /v1/wallets: it returns {"wallets":[...]} for the current identity only.
   Wait for provisioning_status=READY and a non-null ledger_account_id.
6. Login as another identity: its profile/wallet collection is independent.
7. Logout and reuse the old token: profile/wallet requests now return 401.

Supported currencies: AED (uppercase). Public balances and transfers are described in [the P2P contract](p2p-contract.md).
Ledger outages retain PENDING work; automatic retries reuse the same wallet UUID.
Do not send owner_id or identity_id in JSON; gateway derives ownership from auth.

## Local upgrade and IDEA

For the base backend, run docker compose up --build -d from the project root.
For an existing SSO deployment, retain the explicit Compose file set described
in [the SSO runbook](sso-and-ios.md). Preserve .env and volumes.
The existing INTERNAL_AUTH_KEY now also configures user-service and wallet-service.
Wallet Flyway V3 adds durable provisioning state; existing auth migrations remain unchanged.
Gateway requires USER_BASE_URL and WALLET_BASE_URL; Compose provides them.
Auth also requires USER_BASE_URL. For an IDEA auth process, use the reachable
host user-service origin (for example http://127.0.0.1:8082), not a Docker-only name.
Before upgrading existing data, review ADR 0014's duplicate-phone and historical
enrollment requirements; never delete users to make a migration pass.

The DB ports below require an ignored loopback compose.override.yaml; base Compose
does not publish them. IDEA does not load .env automatically: configure the run
environment explicitly.
For IDEA user-service, set PORT=8082 and DB_URL=jdbc:postgresql://127.0.0.1:5434/bank.
For IDEA wallet-service, set PORT=8083 and DB_URL=jdbc:postgresql://127.0.0.1:5435/bank.
Both use DB_USER=bank, DB_PASSWORD from LOCAL_DB_PASSWORD and INTERNAL_AUTH_KEY.
Wallet also requires LEDGER_BASE_URL. A host-run ledger can use http://127.0.0.1:8084
(see ledger.md); Docker ledger is internal-only unless explicitly published.
Do not point IDEA at the private Docker hostname from the Windows host.
Keep credentials in ignored .env files. Direct internal calls additionally require
X-Service-Key and X-Identity-Id; public clients should use gateway.
Docker gateway still calls Docker services unless explicitly reconfigured.

python scripts/onboarding-smoke.py creates synthetic users and validates the flow.
The OpenAPI contract served by gateway includes all new routes.
