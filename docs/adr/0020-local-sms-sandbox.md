# Local-only SMS sandbox persistence and access

Status: accepted for the local synthetic environment.

## Context

Local identity and transaction testing needs an observable SMS sink without
contacting a carrier or an external provider. Captured messages can contain OTPs,
so anonymous inbox access and message logging are inappropriate.

## Decision

A standalone Java service captures messages into a bounded atomic file snapshot
on its own persistent Docker volume. One process owns an exclusive storage lock.
This is a single-instance local testing tool, not a distributed delivery service.
Business services will use an authenticated HTTP send contract; they never read
the sandbox storage directly. OTP validation remains the identity service's job.

Independent sender and reader bearer tokens separate submission from inbox
inspection and clearing. The browser keeps the reader token in memory only.
The optional Compose file publishes only a loopback port and attaches the service
to a dedicated bridge network. No external SMS delivery adapter or fallback exists.

Submission keys are compared with payload hashes. Clearing removes message text
but preserves bounded idempotency tombstones until retention expires. Capacity
exhaustion fails explicitly; clients must not interpret it as successful delivery.

## Consequences

The tool supports local restart persistence and deterministic duplicate handling.
Retention expiration is a documented limit on deduplication; it is not an
exactly-once transport. Sharing its volume between active instances is rejected.
Snapshots and access tokens stay private. Future identity/payment integration
requires separate reviewed contracts and does not follow from deploying the sink.

See [sandbox operations](../sms-sandbox.md) for limits, access and validation.

The bridge permits host ingress and is not an egress firewall. The no-delivery guarantee comes from the absence of provider adapters or outbound message code.
