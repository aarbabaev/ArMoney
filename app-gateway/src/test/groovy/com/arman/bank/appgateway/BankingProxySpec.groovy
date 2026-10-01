package com.arman.bank.appgateway

import com.arman.bank.runtime.ServiceRuntime
import com.sun.net.httpserver.HttpServer
import spock.lang.Specification
import java.net.http.*
import java.util.concurrent.atomic.AtomicInteger

class BankingProxySpec extends Specification {
    def 'banking routes preserve trusted identity and only the payment command key'() {
        given:
        def owner = UUID.randomUUID().toString()
        def id = UUID.randomUUID().toString()
        def key = 'private-gateway-key-with-at-least-32-chars'
        def seen = [:]
        def status = new AtomicInteger(200)
        def bytes = new AtomicInteger(0)
        def auth = HttpServer.create(new InetSocketAddress('127.0.0.1', 0), 0)
        auth.createContext('/') { ex ->
            def body = ('{"id":"' + owner + '"}').bytes
            ex.sendResponseHeaders(200, body.length)
            ex.responseBody.withCloseable { it.write(body) }
        }
        auth.start()
        def downstream = HttpServer.create(new InetSocketAddress('127.0.0.1', 0), 0)
        downstream.createContext('/') { ex ->
            seen.path = ex.requestURI.path
            seen.owner = ex.requestHeaders.getFirst('X-Identity-Id')
            seen.key = ex.requestHeaders.getFirst('X-Service-Key')
            seen.token = ex.requestHeaders.getFirst('Authorization')
            seen.idempotency = ex.requestHeaders.getFirst('Idempotency-Key')
            def body = bytes.get() ? ('x' * bytes.get()).bytes : '{"status":"PENDING"}'.bytes
            ex.sendResponseHeaders(status.get(), body.length)
            try { ex.responseBody.withCloseable { it.write(body) } } catch (IOException ignored) { }
        }
        downstream.start()
        def target = URI.create("http://127.0.0.1:${downstream.address.port}")
        def proxy = new ProtectedProxy(URI.create("http://127.0.0.1:${auth.address.port}"), target, target, target, key)
        def runtime = ServiceRuntime.start('app-gateway', 0, null, { proxy.configure(it) })
        def client = HttpClient.newHttpClient()
        def call = { method, path, idem = 'original-key', token = true ->
            def request = HttpRequest.newBuilder(URI.create("http://localhost:${runtime.port()}" + path))
                .header('X-Identity-Id', UUID.randomUUID().toString()).header('X-Service-Key', 'forged')
                .header('Content-Type', 'application/json')
            if (token) request.header('Authorization', 'Bearer ' + ('a' * 43))
            if (idem != null) request.header('Idempotency-Key', idem)
            client.send(request.method(method, HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString())
        }
        expect:
        call('POST', '/v1/payments', null).statusCode() == 400
        call('POST', '/v1/payments', 'bad key').statusCode() == 400
        call('POST', '/v1/payments', 'original-key', false).statusCode() == 401
        when:
        status.set(202)
        then:
        call('POST', '/v1/payments').statusCode() == 202
        seen.idempotency == 'original-key'
        seen.owner == owner && seen.key == key && seen.token == null
        call('GET', '/v1/payments').statusCode() == 503
        when:
        status.set(409)
        then:
        call('POST', '/v1/payments').statusCode() == 409
        when:
        status.set(200)
        then:
        call('POST', '/v1/recipients/resolve').statusCode() == 200
        seen.path == '/v1/users/resolve-phone' && seen.idempotency == null
        call('PUT', '/v1/users/me/phone').statusCode() == 200
        call('GET', "/v1/wallets/${id}/balance").statusCode() == 200
        call('GET', "/v1/payments/${id}").statusCode() == 200
        call('GET', '/v1/notifications').statusCode() == 200
        call('POST', "/v1/notifications/${id}/read").statusCode() == 200
        call('GET', "/v1/internal/wallets/${id}").statusCode() == 404
        call('POST', '/v1/users/resolve-phone').statusCode() == 404
        call('GET', '/v1/payments/not-a-uuid').statusCode() == 400
        when:
        bytes.set(131073)
        then:
        call('GET', '/v1/payments').statusCode() == 503
        cleanup:
        client?.close(); runtime?.close(); proxy?.close()
        auth?.stop(0); downstream?.stop(0)
    }
}
