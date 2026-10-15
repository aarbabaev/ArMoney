# ADR 0033: Persist disabled SSO login proofs in auth

Status: Accepted for disabled storage and private transport source.

Auth owns immutable login proof/event records and command replay. V7 appends
separate proof, event, command, admission and control tables without changing
published verification migrations or existing identities and sessions. Admission
commits once per authenticated request before parsing; invalid requests consume
the same shared writer budget across instances. Payload conflicts fail closed.

Proof operations use database time, bound issuer/subject/client/provider session,
current transaction-local eligibility and final expiry checks. Exact replay
retains its original acknowledgement while rechecking current eligibility.
Recipients have bounded logical retention and keyed recipient-free tombstones.
Cleanup is bounded; scheduling and monitoring are not supplied by this slice.

Main does not register the new private routes. The configuration defaults off and
its production eligibility adapter is always unavailable. Synthetic positive
fixtures exercise persistence only; they do not supply production authority.
No bank session, notification, ACTIVE state, positive projection, migration flow
or provider proof registration is activated. The
[planned proof contract](../../auth-service/LOGIN-PROOF-CONTRACT.md) records the
remaining integration and acceptance requirements.
