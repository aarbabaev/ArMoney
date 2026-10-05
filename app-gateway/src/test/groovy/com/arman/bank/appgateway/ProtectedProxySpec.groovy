package com.arman.bank.appgateway
import com.arman.bank.runtime.ServiceRuntime
import com.arman.bank.runtime.InternalHttp
import com.sun.net.httpserver.HttpServer
import spock.lang.Specification
import java.net.http.*
import java.time.Clock
import java.util.concurrent.atomic.AtomicInteger

class ProtectedProxySpec extends Specification {
    def "gateway derives profile email only from verified identity: #label"() {
        given:
        def key = 'gateway-internal-service-key-32-characters'
        def owner = UUID.randomUUID().toString()
        def calls = new AtomicInteger()
        def seen = [:]
        def auth = HttpServer.create(new InetSocketAddress('127.0.0.1', 0), 0)
        auth.createContext('/v1/auth/me') { ex ->
            def identity = [id: owner]
            if (includeEmail) identity.email = authEmail
            def bytes = InternalHttp.JSON.writeValueAsBytes(identity)
            ex.sendResponseHeaders(200, bytes.length)
            ex.responseBody.withCloseable { it.write(bytes) }
        }
        auth.start()
        def backend = HttpServer.create(new InetSocketAddress('127.0.0.1', 0), 0)
        backend.createContext('/') { ex ->
            calls.incrementAndGet()
            seen.email = ex.requestHeaders.getFirst('X-Identity-Email')
            seen.owner = ex.requestHeaders.getFirst('X-Identity-Id')
            seen.key = ex.requestHeaders.getFirst('X-Service-Key')
            ex.requestBody.close()
            def bytes = '{"ok":true}'.bytes
            ex.sendResponseHeaders(200, bytes.length)
            ex.responseBody.withCloseable { it.write(bytes) }
        }
        backend.start()
        def target = URI.create("http://127.0.0.1:${backend.address.port}")
        def proxy = new ProtectedProxy(URI.create("http://127.0.0.1:${auth.address.port}"), target, target, target, key)
        def runtime = ServiceRuntime.start('app-gateway', 0, null, { proxy.configure(it) })
        def client = HttpClient.newHttpClient()

        when:
        def request = HttpRequest.newBuilder(URI.create("http://localhost:${runtime.port()}" + path))
            .header('Authorization', 'Bearer ' + ('a' * 43))
            .header('X-Identity-Email', 'attacker@example.test')
            .header('X-Identity-Id', UUID.randomUUID().toString())
            .header('X-Service-Key', 'forged')
            .header('Content-Type', 'application/json')
        def body = method == 'GET' ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString('{}')
        def response = client.send(request.method(method, body).build(), HttpResponse.BodyHandlers.ofString())

        then:
        response.statusCode() == expectedStatus
        calls.get() == (expectedStatus == 200 ? 1 : 0)
        seen.email == expectedEmail
        if (expectedStatus == 200) {
            assert seen.owner == owner
            assert seen.key == key
        } else {
            assert response.body() == '{"error":"service_unavailable"}'
        }

        cleanup:
        client?.close(); runtime?.close(); proxy?.close()
        auth?.stop(0); backend?.stop(0)

        where:
        label              | includeEmail | authEmail                     | method | path                    | expectedStatus | expectedEmail
        'normalized'       | true         | '  Alice@Example.TEST  '      | 'PUT'  | '/v1/users/me'          | 200            | 'alice@example.test'
        'null SSO'         | true         | null                          | 'PUT'  | '/v1/users/me'          | 200            | null
        'missing legacy'   | false        | null                          | 'PUT'  | '/v1/users/me'          | 200            | null
        'blank'            | true         | ' '                           | 'PUT'  | '/v1/users/me'          | 503            | null
        'invalid grammar'  | true         | 'alice@localhost'             | 'PUT'  | '/v1/users/me'          | 503            | null
        'header injection' | true         | 'alice@example.test\r\nx: y' | 'PUT'  | '/v1/users/me'          | 503            | null
        'oversized'        | true         | ('a' * 242) + '@example.test'  | 'PUT'  | '/v1/users/me'          | 503            | null
        'maximum length'   | true         | ('a' * 241) + '@example.test'  | 'PUT'  | '/v1/users/me'          | 200            | ('a' * 241) + '@example.test'
        'numeric'          | true         | 42                            | 'PUT'  | '/v1/users/me'          | 503            | null
        'object'           | true         | [value: 'alice@example.test'] | 'PUT'  | '/v1/users/me'          | 503            | null
        'array'            | true         | ['alice@example.test']        | 'PUT'  | '/v1/users/me'          | 503            | null
        'profile read'     | true         | 'alice@example.test'          | 'GET'  | '/v1/users/me'          | 200            | null
        'phone update'     | true         | 'alice@example.test'          | 'PUT'  | '/v1/users/me/phone'    | 200            | null
        'recipient lookup' | true         | 'alice@example.test'          | 'POST' | '/v1/recipients/resolve' | 200           | null
        'wallet create'    | true         | 'alice@example.test'          | 'POST' | '/v1/wallets'           | 200            | null
        'payment history'  | true         | 'alice@example.test'          | 'GET'  | '/v1/payments'          | 200            | null
        'notifications'    | true         | 'alice@example.test'          | 'GET'  | '/v1/notifications'     | 200            | null
        'malformed wallet' | true         | 'invalid'                     | 'GET'  | '/v1/wallets'           | 503            | null
    }

    def "gateway overwrites identity and never forwards a failed or revoked session"() {
        given:
        def key = 'gateway-internal-service-key-32-characters'
        def identity = UUID.randomUUID().toString()
        def authStatus = new AtomicInteger(200)
        def downstreamCalls = new AtomicInteger()
        def seen = [:]
        def auth = HttpServer.create(new InetSocketAddress('127.0.0.1', 0), 0)
        auth.createContext('/v1/auth/me') { ex ->
            seen.authKey = ex.requestHeaders.getFirst('X-Service-Key')
            def bytes = ('{"id":"' + identity + '"}').bytes
            ex.sendResponseHeaders(authStatus.get(), bytes.length)
            ex.responseBody.withCloseable { it.write(bytes) }
        }
        auth.start()
        def backend = HttpServer.create(new InetSocketAddress('127.0.0.1', 0), 0)
        backend.createContext('/') { ex ->
            downstreamCalls.incrementAndGet()
            seen.owner = ex.requestHeaders.getFirst('X-Identity-Id')
            seen.key = ex.requestHeaders.getFirst('X-Service-Key')
            seen.token = ex.requestHeaders.getFirst('Authorization')
            seen.userId = ex.requestHeaders.getFirst('X-User-Id')
            def bytes = '{"ok":true}'.bytes
            ex.sendResponseHeaders(200, bytes.length)
            ex.responseBody.withCloseable { it.write(bytes) }
        }
        backend.start()
        def authUri = URI.create("http://127.0.0.1:${auth.address.port}")
        def backendUri = URI.create("http://127.0.0.1:${backend.address.port}")
        def proxy = new AuthProxy(authUri, key, Clock.systemUTC())
        def protectedProxy = new ProtectedProxy(authUri, backendUri, backendUri, key)
        def runtime = ServiceRuntime.start('app-gateway', 0, null, { config ->
            proxy.configure(config); protectedProxy.configure(config)
        })
        def client = HttpClient.newHttpClient()
        def request = { path, token ->
            def b = HttpRequest.newBuilder(URI.create("http://localhost:${runtime.port()}" + path))
                .header('X-Identity-Id', UUID.randomUUID().toString()).header('X-Service-Key', 'forged')
                .header('X-User-Id', 'forged')
            if (token != null) b.header('Authorization', token)
            client.send(b.GET().build(), HttpResponse.BodyHandlers.ofString())
        }
        expect:
        request('/v1/users/me', null).statusCode() == 401
        downstreamCalls.get() == 0
        request('/v1/users/me', 'Bearer ' + ('a' * 43)).statusCode() == 200
        seen.owner == identity
        seen.key == key
        seen.authKey == key
        seen.token == null
        seen.userId == null
        request('/v1/wallets', 'Bearer ' + ('a' * 43)).statusCode() == 200
        when:
        authStatus.set(401)
        then:
        request('/v1/wallets', 'Bearer ' + ('a' * 43)).statusCode() == 401
        downstreamCalls.get() == 2
        when:
        authStatus.set(503)
        then:
        request('/v1/users/me', 'Bearer ' + ('a' * 43)).statusCode() == 503
        downstreamCalls.get() == 2
        when:
        auth.stop(0)
        then:
        request('/v1/users/me', 'Bearer ' + ('a' * 43)).statusCode() == 503
        downstreamCalls.get() == 2
        cleanup:
        client?.close(); runtime?.close(); proxy?.close(); protectedProxy?.close()
        auth?.stop(0); backend?.stop(0)
    }
    def "wallet provisioning response preserves status and body with verified identity: #upstreamStatus"() {
        given:
        def key = 'gateway-internal-service-key-32-characters'
        def identity = UUID.randomUUID().toString()
        def wallet = UUID.randomUUID().toString()
        def account = UUID.randomUUID().toString()
        def body = InternalHttp.JSON.writeValueAsString([id: wallet, owner_id: identity, currency: 'AED',
            status: 'ACTIVE', provisioning_status: provisioning, ledger_account_id: ready ? account : null])
        def seen = [:]
        def auth = HttpServer.create(new InetSocketAddress('127.0.0.1', 0), 0)
        auth.createContext('/v1/auth/me') { ex ->
            def bytes = ('{"id":"' + identity + '"}').bytes
            ex.sendResponseHeaders(200, bytes.length)
            ex.responseBody.withCloseable { it.write(bytes) }
        }
        auth.start()
        def backend = HttpServer.create(new InetSocketAddress('127.0.0.1', 0), 0)
        backend.createContext('/v1/wallets') { ex ->
            seen.owner = ex.requestHeaders.getFirst('X-Identity-Id')
            seen.key = ex.requestHeaders.getFirst('X-Service-Key')
            seen.token = ex.requestHeaders.getFirst('Authorization')
            seen.body = ex.requestBody.text
            def bytes = body.bytes
            ex.sendResponseHeaders(upstreamStatus, bytes.length)
            ex.responseBody.withCloseable { it.write(bytes) }
        }
        backend.start()
        def authUri = URI.create("http://127.0.0.1:${auth.address.port}")
        def backendUri = URI.create("http://127.0.0.1:${backend.address.port}")
        def proxy = new ProtectedProxy(authUri, backendUri, backendUri, key)
        def runtime = ServiceRuntime.start('app-gateway', 0, null, { config -> proxy.configure(config) })
        def client = HttpClient.newHttpClient()

        when:
        def response = client.send(HttpRequest.newBuilder(URI.create("http://localhost:${runtime.port()}/v1/wallets"))
            .header('Authorization', 'Bearer ' + ('a' * 43)).header('Content-Type', 'application/json')
            .header('X-Identity-Id', UUID.randomUUID().toString()).header('X-Service-Key', 'forged')
            .POST(HttpRequest.BodyPublishers.ofString('{"currency":"AED"}')).build(), HttpResponse.BodyHandlers.ofString())

        then:
        response.statusCode() == upstreamStatus
        response.body() == body
        response.headers().firstValue('Cache-Control').orElse('') == 'no-store'
        seen.owner == identity
        seen.key == key
        seen.token == null
        seen.body == '{"currency":"AED"}'

        when: 'an undocumented 202 is returned for a wallet list'
        def list = client.send(HttpRequest.newBuilder(URI.create("http://localhost:${runtime.port()}/v1/wallets"))
            .header('Authorization', 'Bearer ' + ('a' * 43)).GET().build(), HttpResponse.BodyHandlers.ofString())

        then:
        list.statusCode() == (upstreamStatus == 202 ? 503 : 200)

        cleanup:
        client?.close(); runtime?.close(); proxy?.close()
        auth?.stop(0); backend?.stop(0)

        where:
        upstreamStatus | provisioning | ready
        202            | 'PENDING'    | false
        200            | 'READY'      | true
    }
}
