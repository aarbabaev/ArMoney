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

Both provider JARs require an empty `META-INF/beans.xml` archive marker and
`@Provider` annotations for Keycloak REST resource discovery. Explicit
`bean-discovery-mode="none"` excludes the archive from the pinned Quarkus index.
Packaging prerequisite tests cover the markers and endpoint annotations; actual
routing still requires the rebuilt two-node acceptance image.

Validation (2026-10-16): exact source `86c42d511e7a5fee95d9e0087bd3b436a03acd20`
passed all seven checks in [CI run 38877887316](https://github.com/aarbabaev/ArMoney/actions/runs/38877887316).
Independent review of build job `116904320077` verified cross-node observation,
read-only timestamp/expiry checks, wrong HMAC/subject/key rejection, committed tab
and root removal with observer 503 denials, real USER expiry, and user disable/delete.
The lifecycle reached COMPLETE at 10:12:38 UTC; owned-project absence and unique-image
removal passed at 10:13:03 UTC. Earlier HTTP 405 and removal failures are historical
and are superseded by this exact-source acceptance evidence. This disposable proof
does not activate registration or establish production deployment or mailbox delivery.
