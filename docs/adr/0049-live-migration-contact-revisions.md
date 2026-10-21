# ADR 0049: Live contact revisions for migrated identities

Status: Design accepted; implementation and acceptance verification in progress.

## Context

A retained User publication ACK proves the migration's historical publication. It does not prove that the User contact projection still has that revision. The private verification-projection writer can change contacts after a completed migration releases its User baseline. A fresh read alone also leaves a race between observation and use.

## Decision

Migration-backed Auth admission will obtain a bounded, authenticated current User observation outside database transactions, then recheck the same ACTIVE Auth source revision in its final transaction. The expected User tuple contains profile ID, shard, baseline generation, contact mutation revision, projection presence and its nullable revision, phone contact version and verification epoch.

Publication and baseline release preserve the staged tuple. Auth will validate it using retained stage and publication ACKs, the original initial ACTIVE authority's phone, original email and policy facts, and the existing User baseline hash recipe. An authenticated fresh shard is a hash-verified candidate; missing historical inputs deny admission. Publication revision is distinct from projection revision.

Before a relevant User projection mutation commits, User will obtain a durable, idempotent Auth suspension ACK outside its transaction. Auth derives source identity and revision from its own locked state. User then rechecks the original contact tuple, baseline fence and projection command in its final transaction. Auth admission and suspension serialize on the same authority. A crash after suspension may leave authority suspended; neither a timeout nor historical evidence reactivates it.

An immutable User preflight journal will retain the original writer, operation, projection payload hash and canonical suspension request before HTTP. Exact replay precedes capacity admission. The global cap is 100,000 entries, with no TTL, eviction or renewal. This journal provides technical command custody, not a contact reservation or business authority. It must not revive an expired projection operation.

Initial registration can have no claim, directory or projection yet. Only that fully absent branch may use a null prior tuple, and it requires conclusive authenticated NOT_MIG classification plus a final absence CAS and migration-fence check. Partial states and migration-backed null tuples deny. Unavailable classification never means NOT_MIG. Concrete Auth classification predicates require independent review before enablement.

## Current conservative mutation boundary

The current integration does not introduce a new producer for migration-backed contact projection commands. A new mutation for a migrated identity without supported original command custody returns unavailable before an Auth receipt or authority transition, and User performs no write. It is not classified as registration and does not silently reactivate migration authority. Existing authenticated registration commands retain their original authority and command checks.

This is an availability limitation, not evidence that migration suspension races have passed acceptance. Fresh migration-backed admission still requires the authenticated current User tuple and a final check of the same ACTIVE Auth source revision. A future supported migration contact-change flow requires its own reviewed command producer and independent race acceptance before enablement.

## Consequences and verification

No service reads another service's database. Network work shares the original operation budget and occurs outside database transactions. Unknown commit outcomes recover the original command and payload; they do not create a replacement command.

Required isolated PostgreSQL acceptance includes both admission/suspension orderings, projection races, bootstrap absence races, exact replay and conflicts, crash after Auth suspension, uncertain commits, unchanged publication counters, populated upgrades and capacity exhaustion. Transport tests do not establish durable race safety.

This decision does not enable migration completion, claim a successful native login, or change the deployed stack. Runtime readiness remains dependent on the integrated implementation, independent review and actual acceptance evidence.
