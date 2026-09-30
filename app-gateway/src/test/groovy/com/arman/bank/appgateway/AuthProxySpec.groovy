package com.arman.bank.appgateway

import com.arman.bank.runtime.ServiceRuntime
import com.sun.net.httpserver.HttpServer
import spock.lang.Specification
import java.net.http.*
import java.time.Clock
import java.util.concurrent.atomic.AtomicReference

class AuthProxySpec extends Specification {
    def "gateway forwards only allowed auth routes and replaces untrusted service identity"() {
        given:
        def captured = new AtomicReference()
        def backend = HttpServer.create(new InetSocketAddress('127.0.0.1', 0), 0)
        backend.createContext('/v1/auth/me') { exchange ->
            captured.set([key: exchange.requestHeaders.getFirst('X-Service-Key'),
                auth: exchange.requestHeaders.getFirst('Authorization'),
                user: exchange.requestHeaders.getFirst('X-User-Id')])
            def bytes = '{"id":"test"}'.getBytes('UTF-8')
            exchange.sendResponseHeaders(200, bytes.length)
            exchange.responseBody.write(bytes)
            exchange.close()
        }
        backend.start()
        def proxy = new AuthProxy(URI.create("http://localhost:${backend.address.port}"), 'expected-service-key-at-least-32-chars', Clock.systemUTC())
        def runtime = ServiceRuntime.start('app-gateway', 0, null, proxy::configure)
        def client = HttpClient.newHttpClient()
        def origin = "http://localhost:${runtime.port()}"

        when:
        def response = client.send(HttpRequest.newBuilder(URI.create(origin + '/v1/auth/me'))
            .header('Authorization', 'Bearer ' + 'a' * 43).header('X-Service-Key', 'attacker')
            .header('X-User-Id', 'victim').build(), HttpResponse.BodyHandlers.ofString())

        then:
        response.statusCode() == 200
        response.headers().firstValue('Cache-Control').orElse('') == 'no-store'
        captured.get().key == 'expected-service-key-at-least-32-chars'
        captured.get().auth == 'Bearer ' + 'a' * 43
        captured.get().user == null
        client.send(HttpRequest.newBuilder(URI.create(origin + '/v1/admin')).build(),
            HttpResponse.BodyHandlers.ofString()).statusCode() == 404

        when:
        backend.stop(0)
        def unavailable = client.send(HttpRequest.newBuilder(URI.create(origin + '/v1/auth/me')).build(),
            HttpResponse.BodyHandlers.ofString())

        then:
        unavailable.statusCode() == 503
        !unavailable.body().contains('Exception')

        cleanup:
        client?.close()
        runtime?.close()
        proxy?.close()
        backend?.stop(0)
    }

    def "rejects invalid upstream configuration"() {
        when:
        new AuthProxy(URI.create('http://localhost:8080/arbitrary/path'), 'expected-service-key-at-least-32-chars', Clock.systemUTC())
        then:
        thrown(IllegalArgumentException)
    }

    def "SSO exchange admits bounded provider token but preserves legacy body limit"() {
        given:
        def captured = new AtomicReference()
        def backend = HttpServer.create(new InetSocketAddress('127.0.0.1', 0), 0)
        backend.createContext('/v1/auth/sso') { ex ->
            captured.set([body: ex.requestBody.text, key: ex.requestHeaders.getFirst('X-Service-Key'),
                          owner: ex.requestHeaders.getFirst('X-Identity-Id')])
            byte[] bytes = '{"access_token":"session"}'.bytes
            ex.sendResponseHeaders(200, bytes.length)
            ex.responseBody.withCloseable { it.write(bytes) }
        }
        backend.start()
        def key = 'expected-service-key-at-least-32-chars'
        def proxy = new AuthProxy(URI.create("http://localhost:${backend.address.port}"), key, Clock.systemUTC())
        def runtime = ServiceRuntime.start('app-gateway', 0, null, proxy::configure)
        def client = HttpClient.newHttpClient()
        def send = { path, payload -> client.send(HttpRequest.newBuilder(URI.create("http://localhost:${runtime.port()}" + path))
            .header('Content-Type', 'application/json').header('X-Service-Key', 'forged')
            .header('X-Identity-Id', UUID.randomUUID().toString())
            .POST(HttpRequest.BodyPublishers.ofString(payload)).build(), HttpResponse.BodyHandlers.ofString()) }
        def body = '{"access_token":"' + ('a' * 6000) + '"}'

        expect:
        send('/v1/auth/sso', body).statusCode() == 200
        captured.get() == [body: body, key: key, owner: null]
        send('/v1/auth/login', body).statusCode() == 413
        send('/v1/wallets', body).statusCode() == 413
        send('/v1/auth/sso', 'a' * 12289).statusCode() == 413

        cleanup:
        client?.close(); runtime?.close(); proxy?.close(); backend?.stop(0)
    }
}
