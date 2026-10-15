# Disposable provider observer acceptance

This harness tests the pre-token observer introduced in PR #68. It does not
activate verification in a running ArMoney realm or grant enrollment success.
The production provider package and its three SPI registrations are unchanged.

`compose.sso-provider-acceptance.yaml` defines two Keycloak 26.7.4 nodes, one
disposable PostgreSQL database backed by tmpfs, a private bridge fixture and a
browser runner. No host ports, external networks or persistent data mounts are
declared. Both nodes advertise the same issuer; the browser targets node A and
observer requests target node B directly. The acceptance image contains a
separate test-only session-control JAR. Never deploy this image to an owner realm.

The controls authenticate with a separate synthetic key, default to disabled and
commit a fresh Keycloak transaction before returning from tab/root removal.
Inspection returns only lifecycle metadata. The bridge captures a genuine flow
tuple and calls the real observer, then always returns 503 to the provider. It
never supplies receipts, ACTIVE state or successful banking responses.

The browser scenario checks initial-render sequencing, cross-node committed-state
visibility, exact observer fields, unchanged timestamps and expiry after reads,
actual USER expiry, wrong binding/credentials, tab/root deletion and disabled or
deleted users. A fresh synthetic realm and S256 public client are created only
inside the fixed disposable topology. The script rejects alternate target URLs
before network activity and validates independent bounded keys.

The CI-only lifecycle entry point is `scripts/run-sso-provider-acceptance.sh`.
It requires the existing isolated-daemon guard, allocates a fresh random Compose
project, generates synthetic credentials without printing them, and removes only
that project on exit. It cannot be used against the persistent owner stack.
CI runs it serially in the build job when SSO checks are selected.

Validation so far: the standalone fixture check/JAR build passed three boundary
tests; five offline target/key guard tests passed. Two-node runtime acceptance
is still **UNRUN**. A successful existing SSO smoke test does not establish this
new observer's lifecycle guarantees. Auth observation transport, authority wiring,
ACTIVE transitions and broader registration integration remain separate gates.
