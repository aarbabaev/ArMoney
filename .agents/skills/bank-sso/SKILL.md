---
name: bank-sso
description: Configure or integrate ArMoney Keycloak OIDC with authorization code and PKCE, identity mapping and session migration.
---

# Keycloak integration

Keycloak owns the OIDC authorization server. Work in assigned sso-service/** files; auth-service adapter edits require an explicit file lease. Root Compose/CI/build belongs to the orchestrator. Read the assigned contract and relevant provider/auth/gateway source; do not implement a replacement OIDC server.

Freeze issuer, audience/client, exact redirect/logout URIs, S256 PKCE, state/nonce, scopes and stable bank identity mapping before dependent iOS/gateway edits. Native clients are public and have no embedded secret. Map issuer plus subject to the bank principal; do not identify an account by mutable email or trust client-supplied owner IDs. Preserve existing identities only through an explicit migration path.

Use maintained OIDC libraries and pinned provider versions. Verify signatures against trusted issuer keys with an algorithm allowlist, issuer, intended audience, time claims and applicable nonce/authorized-party checks. Never accept ID tokens as API access tokens by accident. Define bounded key refresh/rotation behavior and fail closed on invalid or unavailable identity validation.

Define logout, expiry, refresh/revocation and legacy session compatibility explicitly. Keep realm exports/config secret-free; provision secrets outside version control. Do not change a running realm, expose admin credentials or seed live users under this skill.

Exercise invalid issuer/audience/signature, expired tokens, redirect mismatch, state/nonce/PKCE failures, replay, key rotation, logout and cross-owner access as relevant. Distinguish configured, source-reviewed and end-to-end verified behavior. Use disposable fixtures and the build lease; hand off runtime/ADR/docs changes and blocked checks through the workflow.
