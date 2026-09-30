---
name: bank-api
description: Change ArMoney gateway/service HTTP contracts, trusted identity propagation and retry semantics.
---

# HTTP contracts

Read the affected src/main/resources/openapi.yaml and route tests. For gateway routing inspect ProtectedProxy.java and AuthProxy.java under app-gateway/src/main/java/com/arman/bank/appgateway/. Freeze cross-service request/response, identity, status and failure semantics before dependent edits; update OpenAPI with behavior.

The legacy gateway validates opaque sessions; SSO changes must follow the explicitly assigned migration contract and bank-sso skill. Discard client X-Identity-Id and X-Service-Key; forward only verified identity and allowlisted headers. Resource authorization remains requester-scoped. A shared service key does not isolate compromised peers. Ledger APIs remain private unless an explicit architecture change says otherwise.

Use configured destinations, bounded payloads/timeouts and reusable clients; do not follow redirects that leak credentials. Reject malformed upstream identity and distinguish rejection from unavailability. Stable command IDs and payload/requester comparison must survive lost responses; no fresh payment ID or false success on uncertainty.

Verify affected success, spoofed/missing/revoked identity, cross-owner access, malformed/oversized payload and dependency-failure cases. Use real PostgreSQL for persistence guarantees; transport mocks establish only client behavior. Follow bank-testing for test execution and workflow for handoff.
