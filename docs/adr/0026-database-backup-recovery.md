# ADR 0026 — Saved logical backup validation

Status: Partial implementation; operational recovery acceptance pending.

Saved local logical backups require complete inventory and artifact preflight
before fresh isolated restoration. Existing resources are never restore targets.
New captures record table-content fingerprints to detect mapping/command changes
that row counts alone miss. Checksums do not authenticate executable SQL archives.

This does not alter ledger consistency, schemas or runtime contracts. Recovery
must preserve original commands and restore synchronous quorum before writes.
Logical captures exclude roles, TLS, external configuration and continuous WAL
history. Optional assistant storage follows the pinned pgvector image policy and
joins the writer fence; runtime roles/grants still need separate private recovery.
The disposable database experiment distinguishes database continuity/timing from
application replay and production RTO/RPO. See
[inventory and pending acceptance](../backup-recovery.md). Production PITR,
automated failover and multi-host HA require a separate validated design.
