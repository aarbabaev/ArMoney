# Auth and OIDC transport inventory

Source-reviewed against available main `af1f7975a4ef888000c88e88fbd83ade919b1776` on 2026-10-16, from
`docs/internal-call-inventory` (worktree base `457024e6`). This is preparatory classification
for #25, not a transport migration or runtime verification. Only committed source
in this revision establishes implemented behavior. No build, provider, Docker,
database or network operation was performed for this inventory.

[ADR 0022](adr/0022-kafka-payment-saga.md) is the #30 architecture gate: public
APIs, admission checks and authoritative reconciliation retain HTTP; gRPC is only
a possible future synchronous adapter. A candidate below is not authorization to
migrate it. Keep the Keycloak/OIDC HTTP boundary and public auth API unchanged.

## Implemented outgoing auth calls

The original introspection and registration adapters remain implemented;
main also contains the optional verification transports listed below.
[Main.java](../auth-service/src/main/java/com/arman/bank/authservice/Main.java)
constructs one reusable client for each enabled adapter and closes it on shutdown
or startup failure. JDBC access is to auth's own database, not a peer-service RPC.

| ID | Caller and destination | Credential and identity boundary | Classification |
| --- | --- | --- | --- |
| AUTH-OUT-01 | `KeycloakTokens.verify`: POST configured `SSO_INTROSPECTION_URL`, ending `/protocol/openid-connect/token/introspect` | Form-encoded confidential `SSO_CLIENT_ID`/`SSO_CLIENT_SECRET`, provider access token and `token_type_hint=access_token`; exact issuer/client/audience/claim checks below | Keep HTTP: authenticated standard OIDC introspection, including when reached on a private container address. Not an internal business-RPC migration target. |
| AUTH-OUT-02 | `HttpRegistrationProfiles.provision`: PUT `USER_BASE_URL` + `/internal/registrations/me` | `X-Service-Key: INTERNAL_AUTH_KEY`; `X-Identity-Id` is the durable auth identity UUID; JSON contains only `phone_number` | Internal synchronous business-call candidate for later architecture review. It remains HTTP now; preserve the ownership, rejection, acknowledgement and retry semantics below. |
| AUTH-OUT-03 | `VerificationDeliveryTransport`: POST fixed `http://sms-sandbox:8080/api/messages` | Dedicated `VERIFICATION_SMS_SEND_TOKEN` Bearer credential; persisted delivery UUID as `Idempotency-Key`; auth-owned payload | Implemented default-off sandbox HTTP delivery, separate from business RPC migration. |
| AUTH-OUT-04 | `VerificationDeliveryTransport` email branch: existing `MailtrapSmtp` | SMTP credentials from validated `EmailConfiguration`; auth-owned recipient and delivery payload | Implemented default-off SMTP delivery, not HTTP/gRPC or verification authority. |
| AUTH-OUT-05 | `HttpVerificationProjection`: POST `USER_BASE_URL` + `/v1/internal/verification-projections` | Dedicated `X-ArMoney-Verification-Projection-Key`; immutable command/identity/revision/contact-version/epoch/payload-hash binding | Implemented default-off worker transport; preserve authoritative matching ACK and recovery. Future internal adapter only under #30 agreement. |
| AUTH-OUT-06 | `HttpVerificationObservations`: POST fixed `/sso/realms/armoney/armoney-status/enrollment` on `SSO_ENROLLMENT_STATUS_ORIGIN` | Dedicated `X-ArMoney-Auth-Status-Key`; six server-derived issuer/subject/client/root/tab/HMAC fields | Implemented default-off pre-token observation, not a receipt or eligibility proof. Keep HTTP for this Keycloak boundary. |

### AUTH-OUT-01: provider introspection

Authoritative source:
[KeycloakTokens.java](../auth-service/src/main/java/com/arman/bank/authservice/infrastructure/KeycloakTokens.java),
[SsoTokens.java](../auth-service/src/main/java/com/arman/bank/authservice/application/SsoTokens.java),
[AuthService.java](../auth-service/src/main/java/com/arman/bank/authservice/application/AuthService.java),
[compose.sso.yaml](../compose.sso.yaml).

- Wiring is optional: absent/blank `SSO_ISSUER` leaves SSO exchange unavailable;
  it does not enable a fallback token decoder. Enabled wiring requires the fixed
  introspection URL, issuer and confidential client configuration. The checked-in
  Compose overlay selects `armoney-auth` and the private HTTP endpoint
  `http://sso-service:8080/sso/realms/armoney/protocol/openid-connect/token/introspect`.
  The public issuer is a separately configured exact HTTPS realm URL. The adapter
  accepts a configured HTTP or HTTPS introspection endpoint, forbids user-info,
  query and fragment, and never follows redirects. It does not claim mTLS.
- The client has a two-second connect timeout, three-second HTTP request timeout
  and three-second `CompletableFuture.get` deadline including response-body
  completion. Eight concurrent slots use nonblocking admission; saturation is
  unavailable. Cancellation occurs on incomplete requests; interruption is
  preserved. The streaming subscriber cancels above **32768 response bytes**.
  Strict JSON rejects duplicates/trailing tokens. The adapter has no independent
  request-body byte cap: its normal caller validates a provider token of at most
  8192 characters and the route caps its inbound SSO JSON body at 12288 bytes.
- Require a literal `active:true`; exact configured `iss`; case-insensitive Bearer
  `token_type`; `aud` containing `armoney-api`; exact issuing `client_id` in
  `armoney-ios`/`armoney-android`/`armoney-web`; matching `azp` when present for
  native clients, and mandatory matching `azp` for `armoney-web`. Subject must be
  nonblank, at most 255 characters and contain no ISO control characters. Require
  integral future `exp` and, if present, integral `nbf` no later than now. Auth
  trusts this authenticated provider response, not a client-decoded JWT or
  client-supplied identity header. Optional phone/email claims remain subject to
  the auth mapping/normalization policy.
- No application retry loop, cache or token-exchange command idempotency exists
  here. Every SSO exchange introspects again. Inactive/missing/nonboolean `active`
  and unacceptable identity/time/client claims become auth `UNAUTHORIZED`.
  Non-200 provider responses, malformed/oversized JSON, deadline, transport or
  interruption failures become `UNAVAILABLE`. Provider 401 is therefore an auth
  availability error, not proof of invalid customer credentials.
- After successful introspection, auth resolves `(issuer, subject)` to its own
  stable identity UUID. It never automatically links by email or phone. New
  enrollment atomically reserves the canonical phone; a collision rejects rather
  than merges identities. That local transaction is separate from the subsequent
  user-service call and local session creation. Source:
  [PostgresAuthStore.java](../auth-service/src/main/java/com/arman/bank/authservice/infrastructure/PostgresAuthStore.java).

### AUTH-OUT-02: durable registration phone projection

Authoritative source:
[HttpRegistrationProfiles.java](../auth-service/src/main/java/com/arman/bank/authservice/infrastructure/HttpRegistrationProfiles.java),
[RegistrationProfiles.java](../auth-service/src/main/java/com/arman/bank/authservice/application/RegistrationProfiles.java),
[AuthService.java](../auth-service/src/main/java/com/arman/bank/authservice/application/AuthService.java),
[user ProfileRoutes.java](../user-service/src/main/java/com/arman/bank/userservice/infrastructure/ProfileRoutes.java),
[user PostgresProfiles.java](../user-service/src/main/java/com/arman/bank/userservice/infrastructure/PostgresProfiles.java).

- `USER_BASE_URL` must be a fixed HTTP(S) origin with no path beyond `/`, user-info,
  query or fragment. The adapter appends the fixed private route. The service key
  is configured server-side, 32..256 characters in this adapter. No redirects are
  followed. The destination checks its service key separately on this private
  route and parses the `X-Identity-Id` UUID as the owner. This UUID is an auth
  principal, never a profile ID selected by a customer. Shared `INTERNAL_AUTH_KEY`
  is a local-development trust limitation, not production peer isolation.
- Two-second connect timeout, three-second request timeout and three-second
  future deadline include response-body consumption. Sixteen nonblocking slots
  bound concurrent calls. The response body is discarded rather than buffered;
  there is no separate counted response-byte ceiling. Accept only status **204**,
  absent/zero `Content-Length`, and no `Transfer-Encoding`. The normal request is
  a single persisted canonical phone plus server-owned UUID headers; the adapter
  itself does not separately cap phone length. The target's shared HTTP parser
  enforces a 4096-byte maximum and the exact textual `phone_number` field.
- Status 409 becomes auth `CONFLICT`; every other nonconforming acknowledgement,
  status, timeout or transport failure becomes `UNAVAILABLE`. There is no adapter
  retry loop or request idempotency-key header. Safe application retries use the
  **same persisted auth identity UUID and immutable phone**. User-service uses a
  transaction/advisory lock and durable phone claims; identical repeats succeed,
  different phone/owner claims conflict. No success is inferred from a timeout.
- Registration first commits the auth identity/phone, then performs this call.
  A retry of the same email/phone returns that durable identity without replacing
  its original password hash. Login and SSO also repeat provisioning before
  issuing a local session when the identity has a phone; historical identities
  without one skip the call. Partial registration is recoverable, not a claimed
  cross-service transaction. The target reserves/projects the phone; this call
  does not create a profile, wallet or verification receipt.

## Implemented optional verification transports and ingress

These are committed source at `af1f7975`, not evidence of enabled production
flags or live provider acceptance. Main constructs separately configured delivery
and projection workers, starts non-null workers, conditionally registers authority
routes and closes clients/workers on shutdown or startup failure.

- [VerificationDeliveryConfiguration.java](../auth-service/src/main/java/com/arman/bank/authservice/infrastructure/VerificationDeliveryConfiguration.java)
  defaults `AUTH_VERIFICATION_DELIVERY_ENABLED=false`. Its encrypted-outbox worker
  uses [VerificationDeliveryTransport.java](../auth-service/src/main/java/com/arman/bank/authservice/infrastructure/VerificationDeliveryTransport.java):
  existing Mailtrap SMTP sandbox validation for email and the fixed private SMS
  origin for phone. SMS forbids redirects and uses two-second connect/four-second
  request-and-body completion bounds with bounded strict acknowledgements. Only
  201/new or 200/replay succeeds. Delivery UUID replay stays stable; durable
  leasing/retry belongs to the worker, and sending creates no OTP receipt.
  This configuration does not enable login notices.
- [VerificationProjectionConfiguration.java](../auth-service/src/main/java/com/arman/bank/authservice/infrastructure/VerificationProjectionConfiguration.java)
  defaults `AUTH_VERIFICATION_PROJECTION_ENABLED=false`.
  [VerificationProjectionWorker.java](../auth-service/src/main/java/com/arman/bank/authservice/infrastructure/VerificationProjectionWorker.java)
  sends persisted/fenced commands through
  [HttpVerificationProjection.java](../auth-service/src/main/java/com/arman/bank/authservice/infrastructure/HttpVerificationProjection.java).
  The adapter forbids redirects, uses two-second connect/four-second total
  future-completion bounds and requires a strict matching 200 ACK. Rejection,
  uncertain outcome and durable worker recovery remain distinct. Its production
  positive authority supplier is unavailable; transport code alone cannot
  allocate positive authority or establish ACTIVE.
- [VerificationAuthorityConfiguration.java](../auth-service/src/main/java/com/arman/bank/authservice/infrastructure/VerificationAuthorityConfiguration.java)
  defaults `AUTH_VERIFICATION_AUTHORITY_BRIDGE_ENABLED=false`. Disabled startup
  creates no observer/store/routes. Opt-in Main wiring validates directional
  keys, issuer/client allowlists and fixed origin, constructs
  [HttpVerificationObservations.java](../auth-service/src/main/java/com/arman/bank/authservice/infrastructure/HttpVerificationObservations.java)
  and registers [VerificationRoutes.java](../auth-service/src/main/java/com/arman/bank/authservice/infrastructure/VerificationRoutes.java)
  enrollment/status/challenge/consume routes. Observation uses two-second
  connect/four-second exchange-and-body bounds, a 12288-byte cap and strict
  twelve-field response with exact tuple/current contacts and literal flags.
  Network observation precedes local locks; unavailable/mismatched/expired
  evidence denies without retry or post-token fallback. Enrollment/status responses
  carry seven fields; challenge responses carry five operation fields and consume
  responses three operation fields, not ACTIVE or a bank session; new-subject staging does not
  materialize a banking identity.

The separate Java 21 / Keycloak 26.7.4
[provider module](../sso-service/provider/README.md) is implemented. Required
actions use [Bridge.java](../sso-service/provider/src/main/java/com/arman/bank/sso/Bridge.java)
to call private auth enrollment/status/challenge/consume with
`X-ArMoney-SSO-Bridge-Key`, a fixed destination, four-second total exchange/body
deadline and 12288-byte cap. Initial challenge renders without authority RPC;
subsequent POST derives context/HMAC from protected server models. Provider
verification defaults off, and delivery remains auth-owned.
[StatusResource.java](../sso-service/provider/src/main/java/com/arman/bank/sso/StatusResource.java)
implements pre-token observation while preserving the separate post-token status
contract. Neither status path supplies receipts or ACTIVE, and neither private
status/bridge/admin path is authorized for public-edge exposure.

## Public OIDC and auth boundaries: keep HTTP

The provider configuration is
[sso-service/armoney-realm.json](../sso-service/armoney-realm.json), documented in
[sso-service/README.md](../sso-service/README.md). The Java provider module does not
replace Keycloak's OIDC server or activate realm flows. The TLS edge
[infra/edge/Caddyfile](../infra/edge/Caddyfile) exposes the realm discovery,
`protocol/openid-connect/*`, hosted `login-actions/*` and resource paths under
`/sso`, proxies to Keycloak, overwrites forwarding protocol/host/port, and enables
no access log. Unmatched edge paths return 404. This does not replace each
provider endpoint's own authentication or authorize publishing administration.

| Flow | Implemented boundary and constraints | Retry/error ownership |
| --- | --- | --- |
| Native browser authorization | Public `armoney-ios` and `armoney-android` clients; `/sso/realms/armoney/protocol/openid-connect/auth`; exact registered app callback; code flow with S256 PKCE, state and nonce. No embedded confidential-client secret. Password, implicit, device, CIBA and service-account grants are disabled for these clients. | Browser/provider owns redirects and authorization errors. Client checks callback/state; this is not an internal RPC. |
| Browser authorization | Dedicated public `armoney-web` client, exact registered HTTPS callbacks and code flow with S256 PKCE. Auth introspection requires mandatory matching web `azp`. | Retain browser/gateway cookie-session and OIDC HTTP contracts; not an internal business-RPC migration target. |
| Native code exchange | HTTPS POST form to the public provider `.../token` endpoint with `grant_type=authorization_code`, public client ID, exact redirect, code and verifier. ID tokens do not serve as bank access evidence. | Provider owns single-use code and PKCE validation. No automatic refresh flow is implemented by either native client. Android consumes its durable callback journal before network exchange; code response loss is not treated as a reusable successful code. |
| Provider token to bank session | Client POST `/v1/auth/sso` through gateway HTTP, carrying only JSON `access_token`; auth then performs AUTH-OUT-01 and AUTH-OUT-02. Source: [AuthRoutes.java](../auth-service/src/main/java/com/arman/bank/authservice/infrastructure/AuthRoutes.java), [gateway AuthProxy.java](../app-gateway/src/main/java/com/arman/bank/appgateway/AuthProxy.java). | Successful repeat exchanges can issue additional independent 30-minute bank sessions; there is no exchange idempotency command. Only opaque-session digests persist. Provider/token and infrastructure errors remain distinct as below. |
| Logout/session validation | `/v1/auth/me` and `/v1/auth/logout` use the local opaque bank session. iOS additionally opens public provider `.../logout` with its client ID and exact post-logout URI; Android attempts local bank-session revocation without provider browser logout. | Provider logout/revocation does not revoke an existing bank session in this revision. Local logout/30-minute expiry remains authoritative; protected bank-session validation is not repeated provider introspection. |

Client source pointers and HTTP limits:

- Android: [BankModel.kt](../android/app/src/main/java/com/armoney/android/BankModel.kt),
  [AuthorizationJournal.kt](../android/app/src/main/java/com/armoney/android/AuthorizationJournal.kt),
  [BankHttp.kt](../android/app/src/main/java/com/armoney/android/BankHttp.kt).
  OAuth/token traffic uses ten-second connect and fifteen-second read timeouts,
  at most 12288 outgoing body bytes and 1048576 response bytes, with no redirects.
  These are connect/read bounds, not a total OAuth exchange deadline.
- iOS: [OAuth.swift](../ios/ArMoney/OAuth.swift),
  [API.swift](../ios/ArMoney/API.swift). The shared URLSession uses twenty-second
  request/thirty-second resource timeouts, denies redirects and streams at most
  1048576 success-body bytes or 4096 error-body bytes. Its OAuth helper accepts a
  provider access token below 65536 characters; the bank still rejects above
  8192. Source presence is not evidence of device delivery or a live realm update.

The auth service requires server `X-Service-Key` on its `/v1/*` routes; the gateway
constructs that header and does not forward client-selected service/identity
headers. [AuthRoutes.java](../auth-service/src/main/java/com/arman/bank/authservice/infrastructure/AuthRoutes.java)
maps business failures to 400 `invalid_request`, 401 `invalid_credentials`,
409 `registration_conflict`, 429 `too_many_attempts` (`Retry-After: 900`) or
503 `service_unavailable`. HTTP-layer failures use generic `request_rejected`.
The two-slot password-hashing admission path separately returns 429 `server_busy`
with `Retry-After: 1`. These contracts must survive any future internal adapter.

Auth also serves private HTTP GET `/v1/internal/identities/{id}/email` for a
service-key holder, returning auth-owned contact metadata and a provider-derived
verification flag, not an OTP receipt. Its payment-service caller is
`MailtrapDelivery.recipient`, using the configured `AUTH_BASE_URL` and server
service key; the [caller-side inventory row](internal-call-inventory.md#notification-delivery-and-external-adapters)
records its request deadline, bounded response, status handling and worker retry
semantics. This is an incoming auth business-call boundary, not another outgoing
auth adapter. Public ingress does not route it through the gateway's auth
allowlist. Do not turn contact lookup, sessions, SQL or OIDC into Kafka events by
classification alone.

## Positive authority and activation remain gated

Verification delivery, projection, observation and optional route wiring are
implemented source, even though they default off. Their presence does not prove
completed possession verification, ACTIVE eligibility, principal materialization,
legacy migration or enforced gates across every credential/session entry point.
Matching projection ACK means PROJECTED staging, not ACTIVE.

Provider proof client/protocol/token/introspection extension source and auth proof
storage also exist. Positive factories remain unavailable and the provider proof
extensions remain unregistered. Immutable eligible-login proof, bank-session/
notice ordering and login notifications must not be presented as active runtime
behavior. No activation or fresh provider/transport acceptance is claimed here.
Future synchronous internal RPC proposals still need #30 architecture agreement
and a frozen contract; public OIDC/auth HTTP compatibility remains preserved.

## Evidence and handoff

Source-reviewed adapter/route/store files, native transport sources, realm/edge
configuration, [KeycloakTokensSpec.groovy](../auth-service/src/test/groovy/com/arman/bank/authservice/KeycloakTokensSpec.groovy)
and [HttpRegistrationProfilesSpec.groovy](../auth-service/src/test/groovy/com/arman/bank/authservice/HttpRegistrationProfilesSpec.groovy).
Existing test source covers claim rejection, malformed/oversized introspection,
slow-provider deadlines, fixed destinations, projection status handling and lost
acknowledgements. **No tests were executed for this documentation-only task**;
these pointers are not a fresh test pass or independent security certification.

Changed path: `docs/auth-transport-inventory.md` only. No runtime, HTTP/schema,
credential, OIDC configuration or deployment behavior changes. Orchestrator-owned
follow-up: link this inventory from the combined #25 classification and relevant
architecture section, preserving implemented/planned distinctions and the #30
migration gate. No shared-file edit was made by this worker.
