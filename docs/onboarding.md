# Try profiles and wallets in Postman

Use the existing login request to obtain access_token. All requests below use
Authorization: Bearer {{access_token}} against http://localhost:8080.
Gateway adds internal service identity; clients do not supply it.

1. PUT /v1/users/me with JSON {"display_name":"Arman"} returns 200 and the profile.
2. GET /v1/users/me returns 200, or 404 if no profile was created.
3. POST /v1/wallets with JSON {"currency":"EUR"} returns 200 and wallet metadata.
4. Repeat step 3: the wallet id is unchanged.
5. GET /v1/wallets returns {"wallets":[...]} for the current identity only.
6. Login as another identity: its profile/wallet collection is independent.
7. Logout and reuse the old token: profile/wallet requests now return 401.

Supported currencies: EUR, USD, GBP (uppercase). No balance or transfer API yet.
Do not send owner_id or identity_id in JSON; gateway derives ownership from auth.

## Local upgrade and IDEA

Run docker compose up --build -d from the project root. Preserve .env and volumes.
The existing INTERNAL_AUTH_KEY now also configures user-service and wallet-service.
Flyway adds V2 to those databases; existing auth migrations remain unchanged.
Gateway requires USER_BASE_URL and WALLET_BASE_URL; Compose provides them.

For IDEA user-service, set PORT=8082 and DB_URL=jdbc:postgresql://127.0.0.1:5434/bank.
For IDEA wallet-service, set PORT=8083 and DB_URL=jdbc:postgresql://127.0.0.1:5435/bank.
Both use DB_USER=bank, DB_PASSWORD from LOCAL_DB_PASSWORD and INTERNAL_AUTH_KEY.
Keep credentials in ignored .env files. Direct internal calls additionally require
X-Service-Key and X-Identity-Id; public clients should use gateway.
Docker gateway still calls Docker services unless explicitly reconfigured.

python scripts/onboarding-smoke.py creates synthetic users and validates the flow.
The OpenAPI contract served by gateway includes all new routes.
