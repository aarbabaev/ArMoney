# Authenticated token context

This metadata slice retains authenticated introspection context without enabling
managed sessions, registration activation or proof consumption. Existing mandatory
issuer, client, active-token and expiry validation still precedes optional parsing.

Optional fields expose ABSENT, MALFORMED or PRESENT_VALID_FORMAT classification.
Missing fields remain distinct from explicit null and invalid types. Legacy token
constructors supply absent context. Malformed optional claims do not introduce a
new login rejection for otherwise valid unmanaged legacy identities.

Session identifiers are bounded text without whitespace, controls, formatting
characters or lone surrogates. Valid supplementary Unicode pairs remain allowed.
Issued-at values require nonnegative integral, representable timestamps no later
than the captured current time or token expiry; parsing does not invent a time.

All absent proof claims mean absent proof context. Interactive context requires a
canonical UUID event, literal `interactive` kind and a literal boolean notice
flag; either boolean is valid format. Reuse requires literal `reuse`, false notice
and an absent event field. Explicit null, partial claims, invalid types and
inconsistent combinations are malformed. A valid format is never verified proof,
authorization or notice eligibility.

Permanent managed-principal denial remains enforced during identity lookup,
session creation and session validation in every flag mode. No session binding,
schema change, public API change or positive provider wiring is introduced.

Source review is clear, including the lone-surrogate correction. The full local Auth check passed 847 tests across 43 suites in 14m 14s on
2026-10-17, including PostgreSQL integration, with zero failures, errors or skips.
Independent evidence audit and exact-head CI remain separate gates. No deployment
or activation is established by this result.
