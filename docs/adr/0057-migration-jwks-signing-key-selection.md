# ADR 0057: Migration JWKS signing-key selection

Status: Implemented in PR #96; focused verification and scoped synthetic LOCAL/historical-SSO live acceptance passed. Exact-head CI, final review and deployment remain separate gates.

## Context

The configured provider publishes a bounded RSA JWKS containing a signing key and an encryption key. Migration verification previously rejected the entire inventory when an entry was not an RS256 signature key. A live callback failed at JWT verification; its separate public-metadata witness reported one signing and one encryption key. These observations do not establish successful JWKS TLS transport or exclude other migration failures.

## Decision

Validate the complete bounded RSA inventory and require globally unique key IDs before selecting keys. Cache only keys with an absent or RS256 algorithm, absent or signature use, and absent or exactly verification operations. Validate key material, metadata, certificate bounds and modulus size even for excluded RSA keys. Reject an empty eligible inventory, unknown key IDs, duplicate IDs and malformed material. Non-RSA inventories remain unsupported and fail closed.

Keep the configured issuer and JWKS endpoint, RS256 signature verification, token claims, nonce, freshness, cache/rate bounds and one-shot callback rules. No HTTP contract, database schema, service ownership or diagram boundary changes.

Auth emits fixed failure-phase markers without identifiers, contacts, tokens, exception text or response bodies. Operator diagnostics are evidence only and grant no authority.

## Verification and limits

The regression uses real RSA signatures with mixed signing/encryption inventories in both orders, cross-purpose duplicate IDs, malformed signing material and unavailable signing keys. Independent RED verification reproduced exactly the two mixed-inventory positive failures in 21 tests; the other 19 passed with zero errors or skips and verified cleanup. Fixed-source GREEN passed all 67 focused unit and PostgreSQL tests with zero failures, errors or skips and verified cleanup; independent review confirmed the frozen sources and actual reports. Subsequent isolated LOCAL and historical-SSO runs passed rebuilt live callbacks, actual delivered email and sandbox SMS codes, original-owner preservation, FINAL and the pinned negative PostgreSQL checks. These synthetic zero-balance results are bound to the source/runtime recorded in PR #96. Exact-head CI, final independent review and deployment remain separate release gates.
