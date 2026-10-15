# Auth verification observation integration

This is implemented, default-disabled source; it is not activation or deployment evidence. See [ADR 0037](adr/0037-auth-verification-observation.md) and the [authority contract](verification-authority-contract.md).

## Configuration and ownership

`AUTH_VERIFICATION_AUTHORITY_BRIDGE_ENABLED=false` creates no observer client, staged repository or private enrollment routes. The new mode rejects simultaneous legacy `AUTH_VERIFICATION_BRIDGE_ENABLED` activation. Delivery, login proof, projection and eligibility enforcement remain separate. No realm or provider configuration is changed.

Enabled configuration requires `SSO_ENROLLMENT_STATUS_ORIGIN`, `SSO_ISSUER`, comma-separated exact `SSO_VERIFICATION_CLIENT_IDS`, `SSO_VERIFICATION_BRIDGE_KEY`, `AUTH_TO_SSO_STATUS_KEY`, canonical 32-byte base64 `AUTH_VERIFICATION_AUTHORITY_RETENTION_KEY`, and the existing `VERIFICATION_KEY_ID`, `VERIFICATION_OTP_HMAC_KEY` and `VERIFICATION_DELIVERY_ENCRYPTION_KEY`. Credentials and cryptographic roles require distinct material. The writer is server-fixed as `sso-provider`. No secret values belong in this document.

Main owns the resource returned by `VerificationAuthorityConfiguration.open(Database)`. It registers routes only for non-null resources and closes the owned observer on startup failure and shutdown before closing its database. Production projection/proof eligibility remains unavailable; staged UUIDs do not create bank identities or external mappings.

## Provider observation

Auth sends POST to exactly `/sso/realms/armoney/armoney-status/enrollment` on the configured origin, authenticating with `X-ArMoney-Auth-Status-Key`. Caller-selected URLs, redirects, retries, user information, query strings, fragments and unexpected base paths are rejected. HTTPS uses normal certificate/hostname validation; development HTTP is restricted to `http://sso-service:8080`.

The exact six request fields are `issuer`, `subject`, `client_id`, `provider_root_session_id`, `provider_tab_id` and `authentication_session_id`. Only HTTP 200 with exactly twelve response fields is accepted: those same six fields plus `username`, `email`, `phone_number`, `enabled`, `authentication_session_active` and `authentication_session_expires_at`. Bindings must match exactly; booleans must be literal true and contacts, HMAC and timestamps must be canonical. Duplicate, unknown or trailing JSON is rejected.

The service-owned Auth database admission timestamp travels unchanged through the observer and repository. Response time never replaces it. A two-second connection timeout and four-second overall exchange/body deadline apply with a 12 KiB bound. Interruptions cancel work and remain interruptions. Dependency errors become unavailable responses without logging sensitive provider details.

## Private Auth routes

The authority mode exposes the existing four paths, authenticated by `X-ArMoney-SSO-Bridge-Key`. It requires the complete six-field binding in bounded JSON and never falls back to legacy three-field parsing. No gateway route is added.

| Method and path | Additional body fields | Exact success response fields |
| --- | --- | --- |
| POST `/internal/enrollments` | `command_id`, `phone_number`, `email` | `enrollment_id`, `version`, `state`, `email_contact_version`, `phone_contact_version`, `email_verified`, `phone_verified` |
| POST `/internal/enrollments/{id}/status` | None | Same seven enrollment fields |
| POST `/internal/enrollments/{id}/challenges` | `command_id`, `purpose`, `contact_version` | `challenge_id`, `state`, `expires_at`, `resend_at`, `attempts_remaining` |
| POST `/internal/enrollments/{id}/challenges/{challenge}/consume` | `command_id`, `purpose`, `contact_version`, `code` | `receipt_id`, `contact_version`, `state` |

Issuing a challenge preserves `PENDING_DELIVERY` and the original challenge expiry, rather than the enrollment's 24-hour lifetime. Consumption preserves `CONSUMED` and the requested purpose-specific contact version independently of enrollment revision. Neither response advertises ACTIVE. Retried commands retain their original operation result without granting fresh authority.

Independent focused tests passed 84 transport/configuration/route cases and two public-path PostgreSQL operation cases. The full Auth check passed 599 tests across 34 suites with zero failures, errors or skips. YAML parsing passed after documentation corrections; this is not a complete OpenAPI semantic-validation claim. Exact-head CI and deployment remain pending. Provider observation on two actual instances, committed-state visibility, removal and expiry remain separately required before activation. This slice does not complete registration, legacy migration or all-entry session enforcement.
