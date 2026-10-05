package com.arman.bank.runtime

import spock.lang.Specification
import java.net.http.*
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean
import java.util.function.BooleanSupplier

class CompositeRuntimeSpec extends Specification {
    def "readiness follows composite dependency state and closes owned resources"() {
        given:
        def healthy = new AtomicBoolean(true)
        def closed = new AtomicInteger()
        def runtime = ServiceRuntime.start('runtime-fixture', 0,
            { healthy.get() } as BooleanSupplier, { closed.incrementAndGet() } as AutoCloseable, {})
        def client = HttpClient.newHttpClient()
        def request = HttpRequest.newBuilder(URI.create("http://localhost:${runtime.port()}/health/ready")).GET().build()
        expect:
        client.send(request, HttpResponse.BodyHandlers.ofString()).statusCode() == 200
        when:
        healthy.set(false)
        def unavailable = client.send(request, HttpResponse.BodyHandlers.ofString())
        then:
        unavailable.statusCode() == 503
        unavailable.body() == '{"status":"DOWN"}'
        when:
        runtime.close()
        then:
        closed.get() == 1
        cleanup:
        client?.close()
    }

    def "readiness exceptions fail closed"() {
        given:
        def runtime = ServiceRuntime.start('runtime-fixture', 0,
            { throw new IllegalStateException('dependency unavailable') } as BooleanSupplier, null, {})
        def client = HttpClient.newHttpClient()
        expect:
        client.send(HttpRequest.newBuilder(URI.create("http://localhost:${runtime.port()}/health/ready")).GET().build(),
            HttpResponse.BodyHandlers.ofString()).statusCode() == 503
        cleanup:
        client?.close()
        runtime?.close()
    }
}
