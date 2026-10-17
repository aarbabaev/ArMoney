# ADR 0026 — Saved logical backup validation

Status: implemented with disposable application recovery acceptance on 2026-10-16
at `960087d863fbd4e57d0c1b78109ae91fe277b64d`. Production recovery and subsequent
integrated-revision delivery gates remain separate.

Saved local logical backups require complete inventory and artifact preflight
before fresh isolated restoration. Existing resources are never restore targets.
New captures record table-content fingerprints to detect mapping/command changes
that row counts alone miss. Checksums do not authenticate executable SQL archives.

This does not alter ledger consistency, schemas or runtime contracts. Recovery
must preserve original commands. Deployments using synchronous replication must
restore quorum before unfencing writes; the application fixture does not test that mode.
Logical captures exclude roles, TLS, external configuration and continuous WAL
history. Optional assistant storage follows the pinned pgvector image policy and
joins the writer fence; runtime roles/grants still need separate private recovery.
The disposable database experiment distinguishes database continuity/timing from
application replay and production RTO/RPO. See
[inventory and dated acceptance](../backup-recovery.md). Production PITR,
automated failover and multi-host HA require a separate validated design.

Disposable staging preserves outer image identity and independently verifies the
exported platform configuration against the imported configuration digest. A
strict private dependency manifest crosses the stopped-runner boundary; references
alone are not trusted as image identity. This accommodates containerd and classic
image-store identity differences without weakening resource ownership. Bounded
streaming avoids a host image-archive spool. The dated experiment restored all
nine synthetic stores and verified legacy/native PKCE sessions, ownership,
original payment replay, immutable reconciled AED postings, and read-only standby
restart/fresh-volume rebuild. It reported zero missing acknowledged fixture
payments and completed ownership-checked cleanup. Fencing/capture took 78.216
seconds; outage through all acceptance took 225.969 seconds, excluding subsequent
cleanup, provisioning/builds and initial fixture setup. Independent QA matched
the private stage/runner receipts and three versioned source hashes.

Earlier staging attempts failed until Windows discovery, exact volume serialization,
cross-store image provenance and private manifest-copy handling were corrected.
This is same-host fixture evidence, not production RTO/RPO, continuous PITR, HA,
promotion, synchronous quorum, the 200-owner baseline, assistant-model or production
TLS recovery. See the runbook for retained limitations and subsequent integration,
CI, review and deployment gates.
