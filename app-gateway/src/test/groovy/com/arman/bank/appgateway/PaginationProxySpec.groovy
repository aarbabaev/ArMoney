package com.arman.bank.appgateway

import com.arman.bank.runtime.ServiceRuntime
import com.sun.net.httpserver.HttpServer
import spock.lang.Specification
import java.net.http.*
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class PaginationProxySpec extends Specification {
    def 'page routes allow only bounded canonical query values and retain the identity boundary'() {
        given:
        def owner = UUID.randomUUID().toString()
        def key = 'synthetic-pagination-service-key-32-characters'
        def authStatus = new AtomicInteger(200)
        def authBody = new AtomicReference('{"id":"' + owner + '"}')
        def downstreamStatus = new AtomicInteger(200)
        def calls = new AtomicInteger()
        def seen = [:]
        def auth = HttpServer.create(new InetSocketAddress('127.0.0.1', 0), 0)
        auth.createContext('/') { ex ->
            def body = authBody.get().bytes
            ex.sendResponseHeaders(authStatus.get(), body.length)
            ex.responseBody.withCloseable { it.write(body) }
        }
        auth.start()
        def backend = HttpServer.create(new InetSocketAddress('127.0.0.1', 0), 0)
        backend.createContext('/') { ex ->
            calls.incrementAndGet()
            seen.path = ex.requestURI.path
            seen.query = ex.requestURI.rawQuery
            seen.owner = ex.requestHeaders.getFirst('X-Identity-Id')
            seen.key = ex.requestHeaders.getFirst('X-Service-Key')
            seen.authorization = ex.requestHeaders.getFirst('Authorization')
            seen.email = ex.requestHeaders.getFirst('X-Identity-Email')
            seen.idempotency = ex.requestHeaders.getFirst('Idempotency-Key')
            def body = (downstreamStatus.get() == 400 ? '{"error":"invalid_page_request"}' :
                '{"' + (seen.path.startsWith('/v1/payments') ? 'payments' : 'notifications') + '":[],"next_cursor":null}').bytes
            ex.sendResponseHeaders(downstreamStatus.get(), body.length)
            ex.responseBody.withCloseable { it.write(body) }
        }
        backend.start()
        def target = URI.create("http://127.0.0.1:${backend.address.port}")
        def proxy = new ProtectedProxy(URI.create("http://127.0.0.1:${auth.address.port}"), target, target, target, key)
        def runtime = ServiceRuntime.start('app-gateway', 0, null, { proxy.configure(it) })
        def client = HttpClient.newHttpClient()
        def call = { path, token = true ->
            def request = HttpRequest.newBuilder(URI.create("http://localhost:${runtime.port()}" + path))
                .header('X-Identity-Id', UUID.randomUUID().toString()).header('X-Service-Key', 'forged')
                .header('X-Identity-Email', 'attacker@example.test').header('Idempotency-Key', 'irrelevant')
            if (token) request.header('Authorization', 'Bearer ' + ('a' * 43))
            client.send(request.GET().build(), HttpResponse.BodyHandlers.ofString())
        }

        expect:
        ['/v1/payments/page', '/v1/notifications/page'].each { path ->
            [null, 'limit=1', 'limit=100', 'cursor=abc_-123', 'cursor=' + ('a' * 512), 'cursor=abc&limit=50'].each { query ->
                def response = call(path + (query == null ? '' : '?' + query))
                assert response.statusCode() == 200
                assert response.body().contains('"next_cursor":null')
                assert response.headers().firstValue('Cache-Control').orElse('') == 'no-store'
                assert seen.path == path
                assert seen.query == (query == 'cursor=abc&limit=50' ? 'limit=50&cursor=abc' : query)
                assert seen.owner == owner && seen.key == key
                assert seen.authorization == null && seen.email == null && seen.idempotency == null
            }
            ['limit=0', 'limit=101', 'limit=01', 'limit=-1', 'limit=1.0', 'limit=', 'cursor=',
             'cursor=' + ('a' * 513), 'cursor=' + ('a' * 1025), 'limit=1&limit=2',
             'cursor=a&cursor=b', 'owner_id=forged', 'limit=10&X-Identity-Id=forged',
             'cursor=a%26owner_id%3Dforged', 'cursor=a+b', 'cursor=a/b', 'cursor=a=b',
             'limit=%31', 'limit=10&', '&limit=10', 'cursor=a;limit=10'].each { query ->
                def previousCalls = calls.get()
                def response = call(path + '?' + query)
                assert response.statusCode() == 400
                assert response.body() == '{"error":"invalid_page_request"}'
                assert calls.get() == previousCalls
            }
        }

        when: 'legacy lists still discard queries'
        def legacy = call('/v1/payments?limit=1&ignored=legacy')
        then:
        legacy.statusCode() == 200
        seen.path == '/v1/payments' && seen.query == null

        when: 'the payment service rejects a tampered cursor'
        downstreamStatus.set(400)
        def rejected = call('/v1/payments/page?cursor=tampered')
        then:
        rejected.statusCode() == 400
        rejected.body() == '{"error":"invalid_page_request"}'

        when:
        def before = calls.get()
        then:
        call('/v1/payments/page?limit=50', false).statusCode() == 401
        calls.get() == before

        when:
        authStatus.set(401)
        then:
        call('/v1/notifications/page?limit=50').statusCode() == 401
        calls.get() == before

        when:
        authStatus.set(503)
        then:
        call('/v1/payments/page?limit=50').statusCode() == 503
        calls.get() == before

        when:
        authStatus.set(200)
        authBody.set('{"id":"malformed"}')
        then:
        call('/v1/notifications/page?cursor=abc').statusCode() == 503
        calls.get() == before

        cleanup:
        client?.close(); runtime?.close(); proxy?.close()
        auth?.stop(0); backend?.stop(0)
    }
}
