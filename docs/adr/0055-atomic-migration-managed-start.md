# ADR 0055: Atomic migration managed-start allocation

Status: Implemented in the pending PR #96 recovery patch. On 2026-10-21, the
isolated Contact and ManagedProof PostgreSQL suites passed 22 tests with no
failures, errors or skips; owned cleanup was verified. Full release acceptance
remains pending.

## Context

The migration browser previously committed consumption of its account form
command before allocating the managed identity-provider attempt. A local
allocation failure could return an unavailable response with the original form
already consumed and no attempt available to continue.

## Decision

Consume the existing account command and allocate the managed attempt in one
Auth-owned PostgreSQL transaction. Reuse the existing request scope, actor,
account capability, command identifier and deadlines. The allocation performs
only local database and cryptographic work; it makes no provider request while
holding the transaction. Generate the redirect only after commit and request
custody verification.

A confirmed rollback leaves the original command usable with its existing
cookie, CSRF proof and deadline. Successful replay still fails closed. An
uncertain commit result does not authorize replacing the command or extending
proof validity. Claimed authorization-code callbacks remain one-shot and are
not automatically retried.

## Verification and limits

The regression uses real PostgreSQL, a composed local-password browser flow and
an injected allocation-insert failure. It checks that both allocation and form
consumption roll back, the exact original form can subsequently succeed, and
the legacy owner and existing session remain unchanged. This scoped regression
does not establish full pre-FINAL migration, node replacement, provider delivery
or deployment readiness.

## Existing redirect contract

A separate actual Javalin/PostgreSQL regression reproduced a managed-start 303
with `text/plain`, which the gateway rejects because migration responses require
HTML. The redirect helper now declares `text/html; charset=utf-8`, including for
its empty response body. Endpoint, status, body, destination, cookies and schema
are unchanged. This corrects a response header to the gateway's existing
contract. The original actual HTTP regression failed on `text/plain`; the same
header assertion passed after the fix in the 22-test green run. Full gateway
migration acceptance remains pending.

A separate complete-migration browser fixture retains the original pre-FINAL
receipt contract and follows the returned forms through FINAL. Fourteen offline
protocol regressions passed, including original-session continuity before FINAL,
revocation afterward, separate early managed-admission denial and strict form
commands. These tests do not establish actual delivery or full migration: trusted
read-only snapshots, real admission and native-confirmation clients, and actual
delivered-code adapters still require integration and runtime verification.
