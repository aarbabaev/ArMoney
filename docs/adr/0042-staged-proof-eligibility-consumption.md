# ADR 0042: Staged internal eligibility consumption for proof creation

Status: Source implemented and independently security-reviewed. On 2026-10-16,
the local Auth check passed 766 tests across 43 suites, including 32 staged
PostgreSQL cases, with zero failures, errors or skips in 13m14s. Independent
review and security audits matched all eight tested source hashes. Exact-head
integration CI and runtime acceptance remain pending. This is a staged internal
API, not production positive wiring or feature activation.

## Context

ADR 0041 records immutable internal eligibility evidence. Durable login-proof
creation must consume current evidence rather than treat a copied activation
value as authority after database work or a control-lock wait. A grant and the
public PROJECTED status do not mean ACTIVE.

## Decision

An opaque, store-owned admission capability binds one writer, complete request,
issuer/subject/client/session tuple and database observation anchor. Admission
charges its existing budget first; a separate database transaction captures the
immutable anchor after charging and before provider I/O. Provider observation
occurs outside the proof-write transaction and cannot refresh this anchor.

The staged INTERACTIVE-only creation path consumes explicit current evidence.
It requires the existing trusted password event; observations cannot invent an
interactive event. Under verification, projection and proof controls, every
write branch checks the permanent managed marker, exact association, immutable
grant, both current receipt identities and versions, epoch, complete six-field
projection acknowledgement, recovery barriers and deadlines. After proof,
event and command persistence work, it rereads current evidence and database
time. Changed or expired authority rolls back the whole transaction.

Exact lost-response retries retain the original event, bytes and expiry.
Conflicting payloads fail. Historical proof resolution remains an immutable,
authenticated/admitted binding and expiry lookup; it does not revalidate or
rewrite historical eligibility. Legacy proof behavior is preserved.

## Limits and validation gates

Production routes and configuration retain the UNAVAILABLE supplier and their
enforcement rejection. This slice adds no HTTP observation adapter, provider
endpoint, ACTIVE status, banking session, profile provisioning or notice
dispatch. The existing seven-field verification status remains unchanged.

A future provider contract must bind issuer, subject, client, user session,
canonical contacts, enabled/session-active facts and a trusted server deadline
to the same invocation. Actual first-session cross-request visibility and
negative-fact propagation require independent pinned-provider acceptance before
positive production wiring. Neither synthetic observations nor these source
regressions prove those provider facts.

Required PostgreSQL regressions cover stale admission behind real control locks,
receipt/epoch/acknowledgement changes during inserts, final deadline expiry,
rollback, exact replay and changed-command conflicts, as well as unchanged
historical resolution and all-mode managed banking denial. These local cases
passed on the recorded eight-file patch over `eb792944`; subsequent integration
must retain that source binding and pass its own exact-head checks.
