# ADR 0058: Recoverable User reservation release after migration FINAL

Status: Implemented in source; independent runtime verification in progress. No activation authorization is implied.

## Context

Auth FINAL atomically preserves the owner, promotes migration banking authority and revokes old sessions. User still retains a published migration reservation until its existing `user-resolve` operation verifies Auth's completed decision. Reporting browser completion without resolving that reservation leaves fresh User-current observations unavailable. A test-only manual resolution cannot establish production readiness.

## Decision

After verified Auth FINAL commits, Auth calls User's fixed PRIMARY `user-resolve` endpoint outside an Auth database transaction. It reuses the original retained publication tuple: `migration_id`, `decision_id`, `stage_operation_id`, publication `command_id` and `payload_hash`, under the original writer. User independently verifies the retained publication acknowledgement and Auth PRIMARY's authenticated completed decision. The exact bounded success response must bind the same migration and decision and state `RELEASED_AFTER_COMPLETION`.

Auth records an immutable release acknowledgement bound by HMAC to the owner, writer, complete original tuple and validated publication/completion provenance. This acknowledgement confirms reservation release only. It does not grant banking authority or replace fresh contact, source-generation, suspension, revocation or provider checks. Browser completion and cached completed results require a valid durable release acknowledgement; status reads do not perform recovery writes.

A bounded worker starts only after Auth's HTTP decision endpoint is serving, because User resolution calls that endpoint back. It discovers verified historical completions without a valid release acknowledgement, including records completed before this change, and repeats the identical resolution after response loss, restart or browser-cookie expiry. It uses one in-flight operation, a small batch, fair selection, pacing with jitter and a 60-second cooldown on User rate limiting. Shutdown must bound outstanding work and close it before its transport resources.

## Consequences and limits

Auth FINAL and User release are separate commits, not a distributed atomic transaction. An unavailable User or a lost response leaves FINAL committed and recovery pending. Recovery must not repeat promotion, mint a replacement publication command, obtain new OTP authority, abort a published baseline, restore old sessions or fabricate a successful response. The original completion, publication journal and required verification keys must remain available while acknowledgement recovery is pending. The acknowledgement's HMAC verification key and bound journal/completion provenance must also remain available for the entire supported acknowledgement and cached-result readback lifetime after release succeeds; clearing the pending queue does not authorize their deletion. Publication credential rotation must preserve the immutable writer identity; the existing User current/previous-key protocol remains unchanged.

Append-only Auth V23 stores durable release acknowledgements. Default-off `LegacyMigrationResources` owns `MigrationUserReleaseWorker` and starts it after HTTP startup. This changes no User endpoint, reservation fence or financial record. Required verification includes genuine LOCAL and historical-SSO browser completion without a manual release helper; actual User-current and fresh native admission; post-FINAL outage and lost-response recovery; restart and expired-cookie recovery; invalid tuple, writer, response and acknowledgement rejection; concurrent replay; immutable acknowledgement enforcement; and owner/profile/wallet/account/balance/history preservation. Component evidence does not establish full runtime or deployment readiness.
