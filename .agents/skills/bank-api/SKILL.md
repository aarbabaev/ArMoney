---
name: bank-api
description: Implement or review Arman Bank Javalin HTTP contracts and service clients with OpenAPI, trusted identity, bounded requests and stable retry semantics.
---

# HTTP contracts and service clients

This instruction-only skill installs nothing. Paths are repository-relative. Read the affected service's src/main/resources/openapi.yaml, routes and HTTP tests. For public routing, read app-gateway/src/main/java/com/arman/bank/appgateway/ProtectedProxy.java and AuthProxy.java. Agree cross-service request/response shapes, ownership and failure behavior with the orchestrator before dependent implementations diverge.

Update route behavior and OpenAPI together, including authentication, integer amount constraints, status codes and errors. Validate payload types, sizes, currency, UUIDs and required fields at the edge. Distinguish a business rejection from temporary unavailability. Do not claim every documented endpoint is public: ledger currently exposes private service APIs only. Route additions and shared runtime changes belong to their assigned owners.

The gateway validates sessions and supplies verified identity. Never trust client-provided X-Identity-Id or X-Service-Key, and never forward arbitrary client headers downstream. Protected resources must be requester-scoped even when the caller knows another resource ID. Internal service-key authentication does not prove that an arbitrary end user is authorized; preserve the trusted caller/identity contract and fail closed on missing or invalid authentication. The current shared-key trust model is not per-service privilege isolation.

Service destinations come from validated configuration, not request-controlled URLs. Use reusable clients, explicit connection and request timeouts, bounded concurrency and request/response size limits appropriate to the endpoint; inspect existing runtime limits before adding another layer. Avoid automatic redirects that could forward credentials. Handle malformed upstream responses, timeout, interruption and unavailable peers without returning false success or exposing internals. Do not log tokens, passwords, full bodies or personal data.

For commands that may commit before a response is lost, use a stable command ID, compare the original requester and payload on replay, and provide recovery by lookup/retry with that same ID. Client idempotency keys belong to the authenticated requester. Do not mark a payment completed before ledger confirmation or turn an uncertain outcome into a fresh transfer.

Verify success plus missing/expired identity, spoofed headers, cross-owner access, malformed/oversized payloads and downstream failure as relevant to the change. Test response-loss retries where commands can commit. Contract tests may simulate transport failures; database guarantees still require real PostgreSQL integration tests. Hand off OpenAPI changes, compatibility impact and actual test evidence to the orchestrator and independent QA.
