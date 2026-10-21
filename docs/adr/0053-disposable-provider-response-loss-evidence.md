# ADR 0053: Disposable provider response-loss evidence

Status: Implemented disposable test composition with scoped production-image
response-loss evidence. Complete migration and activation remain pending.

## Context

Code-intent STAGE and CONFIRM can commit before a transport response is lost.
The original provider deadline and Auth confirmation replay exclusion are security
boundaries. A consumed OAuth code cannot be made reusable by replaying an Auth
request from a test helper. Such a replay would test Auth idempotency, not native
provider recovery.

## Decision

Use a separate, explicitly owned disposable topology for response-loss tests.
The fixed internal name `auth-service` identifies a transparent fixture peer;
actual Auth Main runs as `auth-backend`. Only the existing SSO verification and
code-intent POST paths traverse the peer. Gateway calls actual Auth directly.
The external issuer and original application payloads remain unchanged.

The peer may drop one authenticated STAGE response and one CONFIRM response,
each only after reading a complete bounded 200 from actual Auth. It cannot mint
authority, select an arbitrary destination, replay a command or change a response
into success. KEY and enrollment calls are never faulted. Arming uses an
independent fixture credential, never a production transport credential.

Only selected original request binding fields and identifiers from actual
responses may remain in exclusive private ephemeral selector files. Never retain
full requests, passwords, tokens, authorization codes, PKCE verifiers or transport
keys. Public receipts contain fixed labels, booleans and bounded counts.

A separate fixed-query read-only adapter verifies original retained evidence,
absence of banking writes from the failed attempt, and linkage of a fresh
confirmation event to its actual banking consumption and session. Database reads
require read-only transactions, one-second statement limits and a caller-enforced
three-second subprocess deadline. Private snapshots and digests are not public
receipts. Root owns the outer process deadline and verified resource cleanup.

The disposable Docker daemon requires a private cgroup namespace with bounded
controller delegation before creating resource-limited nested containers. Preserve
the daemon's aggregate limits and the peer's memory, CPU and PID limits; do not
remove limits to work around failed nested container creation. Admission checks
must verify private namespace custody, pinned image, ownership, no published
ports and only fresh owned volumes before starting the runner. Treat an empty
Docker port-binding mapping as empty by counting properties, rather than relying
on PowerShell object truthiness. This fixture setup does not change workstation
security policy, expose the host daemon or touch the persistent application stack.


## Acceptance and limits

The original uncertain attempt must withhold its code or token and must not
create a banking session. An original committed confirmation may remain immutable;
it is not permission to fabricate a token or relax the four-second replay guard.
Recovery uses a genuine fresh login and authorization code with distinct proof.
Do not claim transparent recovery of the original consumed OAuth code.

The isolated production-image run passed the ten baseline browser cases and
both actual STAGE/CONFIRM response-loss cases, with independent evidence review
and verified cleanup. Each uncertain original attempt withheld credentials and
banking writes; recovery used a fresh login. This does not establish transparent
recovery of the original consumed code. A separate scoped run passed master and
disabled-provider stock browser checks, unchanged eleven-table Auth row snapshots
and unchanged STAGE/CONFIRM transport counters, then restored the original profile.
Independent review verified the sanitized evidence and cleanup. Master routing is
confined to the disposable edge; before/after snapshots are not a historical audit
of transient writes. A separate 2026-10-21 fresh-image run passed scoped node
replacement with actual two-to-one-to-two membership, survivor continuity and a
new replacement identity without realm reimport. Genuine original-code redemption
on the survivor and replay denial after rejoin completed in 14.78 seconds within
the original 60-second TTL. Independent security review verified the receipts and
owned cleanup. Complete migration requires separate acceptance; this scoped result
does not establish whole-migration success. This topology does not activate a
persistent deployment or change production transport or authority contracts.
