# ADR 0032: Package the disabled Keycloak verification provider separately

Status: Accepted for disabled source packaging; runtime integration remains pending.

The provider is a standalone Java 21 build pinned to Keycloak 26.7.4. It must not
introduce service dependencies into the bank's root Gradle graph. CI runs its
checks and package audit whenever SSO is affected. Packaging records actual input,
dependency and output hashes and checks the three allowlisted SPI descriptors.
Provider scripts, resources and themes use LF in checkouts as well as Git blobs.

The image recipe does not change Compose, import a realm or activate a flow.
Proof protocol, token policy and introspection factories remain unregistered.
The status endpoint provides observations, not account eligibility. Auth remains
the authority for verification receipts and future eligibility decisions.

Existing web/native OIDC contracts remain unchanged. The planned
[proof contract](../../auth-service/LOGIN-PROOF-CONTRACT.md) records missing
projection, ACTIVE authority and all-entry enforcement. Standalone tests and a
package audit do not establish runtime enforcement, provider delivery or a
completed registration flow. Those require separate integrated acceptance before
activation.
