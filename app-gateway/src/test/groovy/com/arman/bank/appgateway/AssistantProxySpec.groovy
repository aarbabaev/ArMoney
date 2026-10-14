package com.arman.bank.appgateway

import com.arman.bank.runtime.InternalHttp
import com.arman.bank.runtime.ServiceRuntime
import com.sun.net.httpserver.HttpServer
import spock.lang.Specification
import java.net.http.*
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class AssistantProxySpec extends Specification {
    static final String BANK = 'synthetic-bank-key-with-at-least-32-characters'
    static final String OUT = 'synthetic-gateway-assistant-key-32-characters'
    static final String IN = 'synthetic-assistant-gateway-key-32-characters'
    static final String TOKEN = 'Bearer ' + ('a' * 43)
    String owner = UUID.randomUUID().toString(), wallet = UUID.randomUUID().toString()
    AtomicInteger authStatus = new AtomicInteger(200), authCalls = new AtomicInteger(), backendCalls = new AtomicInteger(), assistantCalls = new AtomicInteger()
    AtomicReference<String> authBody = new AtomicReference<>(), backendBody = new AtomicReference<>()
    Map seen = [:]
    Closure assistantAction
    HttpServer auth, backend, assistant
    AssistantProxy proxy
    def runtime
    HttpClient client

    def setup() {
        authBody.set(InternalHttp.JSON.writeValueAsString([id: owner]))
        backendBody.set(InternalHttp.JSON.writeValueAsString([wallet_id: wallet, currency: 'AED', balance_minor: 12345]))
        auth = server { ex ->
            authCalls.incrementAndGet()
            seen.authToken = ex.requestHeaders.getFirst('Authorization')
            seen.authKey = ex.requestHeaders.getFirst('X-Service-Key')
            respond(ex, authStatus.get(), authBody.get())
        }
        backend = server { ex ->
            backendCalls.incrementAndGet()
            seen.backendPath = ex.requestURI.toString()
            seen.backendOwner = ex.requestHeaders.getFirst('X-Identity-Id')
            seen.backendKey = ex.requestHeaders.getFirst('X-Service-Key')
            seen.backendToken = ex.requestHeaders.getFirst('Authorization')
            respond(ex, 200, backendBody.get())
        }
        assistant = server { ex ->
            assistantCalls.incrementAndGet()
            seen.handle = ex.requestHeaders.getFirst('X-Assistant-Delegation')
            seen.assistantKey = ex.requestHeaders.getFirst('X-Gateway-Assistant-Key')
            seen.assistantToken = ex.requestHeaders.getFirst('Authorization')
            seen.assistantInternalKey = ex.requestHeaders.getFirst('X-Service-Key')
            seen.assistantOwner = ex.requestHeaders.getFirst('X-Identity-Id')
            ex.requestBody.close()
            try { assistantAction(ex) }
            catch (Throwable failure) { seen.assistantFailure = failure; respond(ex, 500, '{"error":"synthetic_failure"}') }
        }
        proxy = new AssistantProxy(uri(auth), uri(backend), uri(backend), BANK, uri(assistant), OUT, IN)
        runtime = ServiceRuntime.start('app-gateway', 0, null, { proxy.configure(it) })
        client = HttpClient.newHttpClient()
        assistantAction = { ex -> respond(ex, 200, reply()) }
    }
    def cleanup() {
        runtime?.close(); proxy?.close(); client?.close()
        auth?.stop(0); backend?.stop(0); assistant?.stop(0)
    }
    static HttpServer server(Closure action) {
        def server = HttpServer.create(new InetSocketAddress('127.0.0.1', 0), 0)
        server.createContext('/') { ex -> action(ex) }; server.start(); server
    }
    static URI uri(HttpServer server) { URI.create("http://127.0.0.1:${server.address.port}") }
    static void respond(ex, int status, String body) {
        ex.responseHeaders.set('Content-Type', 'application/json')
        def bytes = body.getBytes('UTF-8'); ex.sendResponseHeaders(status, bytes.length)
        ex.responseBody.withCloseable { it.write(bytes) }
    }
    static String reply(Map overrides = [:]) {
        InternalHttp.JSON.writeValueAsString([reply: 'Policy evidence is unavailable.', outcome: 'insufficient_evidence',
            citations: [], facts: [wallets: null, last_transaction: null], provenance: []] + overrides)
    }
    def message(String body = '{"message":"My balance"}', List tokens = [TOKEN], String type = 'application/json') {
        def request = HttpRequest.newBuilder(URI.create("http://localhost:${runtime.port()}/v1/assistant/messages"))
            .header('Content-Type', type).header('X-Identity-Id', UUID.randomUUID().toString())
            .header('X-Service-Key', 'forged').header('X-Assistant-Delegation', 'forged')
        tokens.each { request.header('Authorization', it) }
        client.send(request.POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString())
    }
    def tool(String path, String handle = seen.handle, String credential = IN, boolean duplicate = false, String method = 'GET') {
        def request = HttpRequest.newBuilder(URI.create("http://localhost:${runtime.port()}" + path))
            .header('X-Assistant-Gateway-Key', credential).header('X-Assistant-Delegation', handle)
            .header('X-Identity-Id', UUID.randomUUID().toString()).header('X-Service-Key', 'forged')
        if (duplicate) request.header('X-Assistant-Gateway-Key', credential)
        client.send(request.method(method, HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString())
    }

    def 'bearer stays in gateway and financial facts match observed owner balance'() {
        given:
        assistantAction = { ex ->
            def read = tool("/v1/internal/assistant/wallets/${wallet}/balance")
            assert read.statusCode() == 200
            assert read.headers().firstValue('Cache-Control').orElse('') == 'no-store'
            def balance = InternalHttp.JSON.readValue(read.body(), Map)
            respond(ex, 200, reply([reply: 'Your balance is 123.45 AED.', outcome: 'answered', facts: [wallets: [balance], last_transaction: null]]))
        }
        when:
        def response = message()
        then:
        response.statusCode() == 200
        authCalls.get() == 3
        seen.authToken == TOKEN && seen.authKey == BANK
        seen.backendOwner == owner && seen.backendKey == BANK && seen.backendToken == null
        seen.assistantKey == OUT && seen.assistantToken == null && seen.assistantInternalKey == null && seen.assistantOwner == null
        seen.handle ==~ /[A-Za-z0-9_-]{43}/
        seen.handle != TOKEN.substring(7)
        proxy.activeDelegations() == 0
        tool("/v1/internal/assistant/wallets/${wallet}/balance").statusCode() == 401
        backendCalls.get() == 1
    }
    def 'invalid public requests never invoke assistant: #label'() {
        when:
        def response = message(body, tokens, type)
        then:
        response.statusCode() == status
        assistantCalls.get() == 0
        proxy.activeDelegations() == 0
        where:
        label | body | tokens | type | status
        'missing token' | '{"message":"a"}' | [] | 'application/json' | 401
        'duplicate token' | '{"message":"a"}' | [TOKEN, TOKEN] | 'application/json' | 401
        'bad token' | '{"message":"a"}' | ['Bearer invalid'] | 'application/json' | 401
        'blank' | '{"message":"  "}' | [TOKEN] | 'application/json' | 400
        'extra property' | '{"message":"a","owner_id":"forged"}' | [TOKEN] | 'application/json' | 400
        'duplicate property' | '{"message":"a","message":"b"}' | [TOKEN] | 'application/json' | 400
        'trailing object' | '{"message":"a"}{}' | [TOKEN] | 'application/json' | 400
        'wrong type' | '{"message":1}' | [TOKEN] | 'application/json' | 400
        'wrong content type' | '{"message":"a"}' | [TOKEN] | 'text/plain' | 400
        'too many characters' | ('{"message":"' + ('a' * 2001) + '"}') | [TOKEN] | 'application/json' | 400
        'too many bytes' | ('{"message":"' + ('a' * 4096) + '"}') | [TOKEN] | 'application/json' | 413
    }
    def 'revocation or malformed identity suppresses tool and final response: #label'() {
        given:
        assistantAction = { ex ->
            authStatus.set(status); authBody.set(body)
            assert tool('/v1/internal/assistant/wallets').statusCode() == expected
            respond(ex, 200, reply())
        }
        when:
        def response = message()
        then:
        response.statusCode() == expected
        backendCalls.get() == 0
        proxy.activeDelegations() == 0
        where:
        label | status | body | expected
        'revoked' | 401 | '{"error":"invalid_credentials"}' | 401
        'unavailable' | 503 | '{}' | 503
        'malformed' | 200 | '{"id":42}' | 503
        'other owner' | 200 | '{"id":"00000000-0000-0000-0000-000000000001"}' | 503
    }
    def 'key alone cannot impersonate and private surface has no mutations'() {
        given:
        assistantAction = { ex ->
            seen.privateStatuses = [
                tool('/v1/internal/assistant/wallets', 'x' * 43).statusCode(),
                tool('/v1/internal/assistant/wallets', seen.handle, OUT).statusCode(),
                tool('/v1/internal/assistant/wallets', seen.handle, IN, true).statusCode(),
                tool('/v1/internal/assistant/wallets?owner_id=forged').statusCode(),
                tool('/v1/internal/assistant/payments/page?limit=100').statusCode(),
                tool('/v1/internal/assistant/payments', seen.handle, IN, false, 'POST').statusCode(),
                tool('/v1/internal/assistant/wallets', seen.handle, IN, false, 'POST').statusCode()]
            respond(ex, 200, reply())
        }
        expect:
        message().statusCode() == 200
        seen.privateStatuses == [401, 401, 401, 400, 400, 404, 404]
        backendCalls.get() == 0
        authCalls.get() == 2
    }
    def 'delegation read budget is eight and handle is removed after turn'() {
        given:
        backendBody.set('{"wallets":[]}')
        assistantAction = { ex ->
            8.times { assert tool('/v1/internal/assistant/wallets').statusCode() == 200 }
            assert tool('/v1/internal/assistant/wallets').statusCode() == 401
            respond(ex, 200, reply())
        }
        expect:
        message().statusCode() == 200
        backendCalls.get() == 8
        proxy.activeDelegations() == 0
    }
    def 'malformed or fabricated assistant response fails closed: #label'() {
        given:
        assistantAction = { ex -> respond(ex, 200, body) }
        expect:
        message().statusCode() == 503
        proxy.activeDelegations() == 0
        where:
        label | body
        'missing fields' | '{"reply":"hello"}'
        'unknown outcome' | reply([outcome: 'paid'])
        'extra field' | reply([provider_debug: 'private'])
        'fabricated balance' | reply([facts: [wallets: [[wallet_id:'00000000-0000-0000-0000-000000000001',currency:'AED',balance_minor:100]],last_transaction:null]])
        'unread empty history' | reply([outcome: 'empty_history'])
        'too large' | reply([reply: 'a' * 32769])
    }
    def 'revocation after successful tool read suppresses final answer'() {
        given:
        assistantAction = { ex ->
            assert tool("/v1/internal/assistant/wallets/${wallet}/balance").statusCode() == 200
            authStatus.set(401)
            respond(ex, 200, reply())
        }
        expect:
        message().statusCode() == 401
        backendCalls.get() == 1
        proxy.activeDelegations() == 0
    }
    def 'quota is based on verified owner rather than caller identity headers'() {
        expect:
        10.times { assert message().statusCode() == 200 }
        def limited = message()
        limited.statusCode() == 429
        limited.headers().firstValue('Retry-After').orElse('') == '60'
        assistantCalls.get() == 10
        proxy.activeDelegations() == 0
    }
    def 'history projection derives direction and discards private identifiers'() {
        given:
        String payment = UUID.randomUUID().toString()
        backendBody.set(InternalHttp.JSON.writeValueAsString([payments: [[id: payment, requester_id: owner,
            recipient_id: UUID.randomUUID().toString(), source_wallet_id: wallet, destination_wallet_id: UUID.randomUUID().toString(),
            recipient_phone: '+971501234567', currency: 'AED', amount_minor: 100, status: 'COMPLETED', rejection_reason: null,
            created_at: '2026-10-13T10:00:00Z', updated_at: '2026-10-13T10:00:01Z']], next_cursor: null]))
        assistantAction = { ex ->
            def read = tool('/v1/internal/assistant/payments/page?limit=50')
            assert read.statusCode() == 200
            def data = InternalHttp.JSON.readValue(read.body(), Map)
            seen.projected = data.payments[0]
            respond(ex, 200, reply([outcome: 'answered', facts: [wallets: null, last_transaction: data.payments[0]]]))
        }
        expect:
        message().statusCode() == 200
        seen.projected.keySet() == ['id', 'created_at', 'currency', 'amount_minor', 'direction', 'status'] as Set
        seen.projected.direction == 'outgoing'
        seen.backendPath == '/v1/payments/page?limit=50'
    }
    def 'empty visible history is explicit and no transaction is fabricated'() {
        given:
        backendBody.set('{"payments":[],"next_cursor":null}')
        assistantAction = { ex ->
            assert tool('/v1/internal/assistant/payments/page?limit=50').statusCode() == 200
            respond(ex, 200, reply([outcome: 'empty_history']))
        }
        expect:
        message().statusCode() == 200
    }
    def 'expired delegation cannot be used and removals invalidate active handles'() {
        given:
        def registry = new AssistantDelegations()
        def expired = registry.create(TOKEN, UUID.fromString(owner), System.nanoTime() - 1)
        def active = registry.create(TOKEN, UUID.fromString(owner), System.nanoTime() + 1000000000L)
        expect:
        registry.acquire(expired.handle) == null
        registry.acquire(active.handle) == active
        when:
        registry.remove(active)
        then:
        registry.acquire(active.handle) == null
        cleanup:
        registry.clear()
    }
    def 'verified-owner quota windows expire and the owner map fails closed when full'() {
        given:
        def now = new AtomicReference(java.time.Instant.parse('2026-10-13T10:00:00Z'))
        def clock = new FixtureClock(now)
        def quota = new AssistantRateLimit(clock)
        def identity = UUID.fromString(owner)
        expect:
        10.times { assert quota.allow(identity) }
        !quota.allow(identity)
        quota.allow(UUID.randomUUID())
        when:
        now.set(now.get().plusSeconds(60))
        then:
        quota.allow(identity)
        when:
        4095.times { assert quota.allow(UUID.randomUUID()) }
        then:
        !quota.allow(UUID.randomUUID())
        when:
        now.set(now.get().plusSeconds(60))
        then:
        quota.allow(UUID.randomUUID())
    }
    static final class FixtureClock extends java.time.Clock {
        final AtomicReference<java.time.Instant> now
        FixtureClock(AtomicReference<java.time.Instant> now) { this.now = now }
        java.time.Instant instant() { now.get() }
        java.time.ZoneId getZone() { java.time.ZoneOffset.UTC }
        java.time.Clock withZone(java.time.ZoneId zone) { this }
    }
}
