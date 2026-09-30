package com.arman.bank.appgateway
import com.arman.bank.runtime.ServiceRuntime
import com.sun.net.httpserver.HttpServer
import spock.lang.Specification
import java.net.http.*
import java.time.Clock
import java.util.concurrent.atomic.AtomicInteger

class ProtectedProxySpec extends Specification {
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
}
