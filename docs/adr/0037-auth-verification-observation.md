# ADR 0037: default-disabled authentication-session observation bridge

Status: default-disabled source implemented and locally tested; exact-head CI and deployment pending.

Auth requires a current, server-owned authentication-session observation before changing staged registration state. Add a bounded HTTP adapter to the existing provider observation protocol and explicitly wire it through `IdentityVerificationService`. Provider observations do not certify contact possession, identify an auth principal, reserve an epoch or grant banking eligibility.

`AUTH_VERIFICATION_AUTHORITY_BRIDGE_ENABLED` defaults false independently of delivery, proof and projection flags. Disabled configuration creates no observer client, authority repository or routes. Enabling the legacy verification bridge at the same time is rejected. Explicit legacy route construction remains compatible with existing tests; authority construction accepts only the complete six-field binding, without automatic fallback.

Use a fixed provider path on an operator-configured origin. Normal HTTPS certificate and hostname verification is required; development HTTP is limited to `sso-service:8080`. A package-private loopback seam is for isolated tests. Incoming bridge credentials, outgoing observation credentials and authority-retention key material must be separate from each other and other business, proof, code, OTP and delivery credentials.

The service supplies the admission database timestamp before network I/O. The adapter preserves it exactly and validates a strict twelve-field response with an exact echoed binding. Two-second connect, four-second complete exchange/body deadline, 12 KiB response bound, no redirects and no retries limit dependency work. Invalid, stale or unavailable observations fail closed. No contact, code, credential or raw provider exception is logged.

Operation results retain their own state, purpose-specific contact version and expiry. Enrollment/status use seven fields; issue uses five and consume uses three. This repairs an unwired response decorator that previously replaced operation values with enrollment state, revision and expiry.

The staged repository remains unable to materialize principals or obtain positive projection/proof eligibility. No ACTIVE state, bank session, notification, provider flow, client or required action is enabled by this slice. Current legacy banking sessions remain on their existing compatibility path. Isolated PostgreSQL response regressions, transport and lifecycle checks, independent review, exact-head CI and deployment evidence are required. Separate two-node provider committed-state, removal and expiry acceptance remains an activation prerequisite.

Validation: independent focused tests passed (84 unit/transport/route cases and two real PostgreSQL operation cases). The complete Auth check subsequently passed 599 tests in 34 suites with zero failures, errors or skips using the local Docker API compatibility JVM argument. An earlier run failed Docker initialization and remains recorded separately. OpenAPI YAML parsed successfully after documentation corrections. These results do not establish exact-head CI, live bridge activation or a successful two-node provider acceptance run.
