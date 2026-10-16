package com.arman.bank.appgateway

import com.arman.bank.runtime.*
import io.javalin.Javalin
import spock.lang.Specification
import java.net.http.*
import java.util.concurrent.atomic.AtomicInteger
import java.util.function.BooleanSupplier

class GatewayMetricsSpec extends Specification {
    static final String KEY = 'synthetic-gateway-metrics-key-32-characters'

    def "absent dedicated key leaves metrics disabled"() {
        given:
        def calls = new AtomicInteger()
        def runtime = fixture(environment, { calls.incrementAndGet(); true } as BooleanSupplier)
        def client = HttpClient.newHttpClient()
        expect:
        scrape(client, runtime, KEY).statusCode() == 404
        calls.get() == 0
        cleanup:
        client?.close()
        runtime?.close()
        where:
        environment << [[:], ['INTERNAL_AUTH_KEY': KEY]]
    }

    def "explicit invalid metrics key fails configuration"() {
        when:
        Javalin.create { config -> Main.configureMetrics(config,
            ['INTERNAL_METRICS_KEY': key], new OperationalMetrics(), { true } as BooleanSupplier) }
        then:
        thrown(IllegalArgumentException)
        where:
        key << ['', ' ' * 32, 'short', 'x' * 257]
    }

    def "only dedicated key can probe readiness and unknown measurements stay absent"() {
        given:
        def calls = new AtomicInteger()
        def runtime = fixture(['INTERNAL_METRICS_KEY': KEY, 'INTERNAL_AUTH_KEY': 'business-key'],
            { calls.incrementAndGet(); true } as BooleanSupplier)
        def client = HttpClient.newHttpClient()
        when:
        def rejected = [null, 'business-key', 'synthetic-business-service-key-32-characters', 'incorrect'].collect { scrape(client, runtime, it) }
        then:
        rejected.every { it.statusCode() == 401 }
        rejected.every { it.body() == '{"error":"request_rejected"}' }
        rejected.every { it.headers().firstValue('Cache-Control').orElse('') == 'no-store' }
        calls.get() == 0
        when:
        def accepted = scrape(client, runtime, KEY)
        then:
        accepted.statusCode() == 200
        accepted.headers().firstValue('Cache-Control').orElse('') == 'no-store'
        accepted.headers().firstValue('Content-Type').orElse('').contains('version=0.0.4')
        accepted.body().contains('armoney_ready 1\n')
        !accepted.body().contains('backlog')
        !accepted.body().contains('failures_total')
        calls.get() == 1
        cleanup:
        client?.close()
        runtime?.close()
    }

    def "health and metrics preserve the same readiness semantics"() {
        given:
        BooleanSupplier readiness = { if (fails) throw new IllegalStateException('private detail'); ready } as BooleanSupplier
        def runtime = fixture(['INTERNAL_METRICS_KEY': KEY], readiness)
        def client = HttpClient.newHttpClient()
        when:
        def health = client.send(HttpRequest.newBuilder(URI.create("http://localhost:${runtime.port()}/health/ready"))
            .GET().build(), HttpResponse.BodyHandlers.ofString())
        def metrics = scrape(client, runtime, KEY)
        then:
        health.statusCode() == status
        metrics.statusCode() == 200
        metrics.body().contains("armoney_ready ${value}\n")
        !metrics.body().contains('private detail')
        cleanup:
        client?.close()
        runtime?.close()
        where:
        ready | fails | status | value
        true  | false | 200    | 1
        false | false | 503    | 0
        true  | true  | 503    | 0
    }

    def "client bearer and identity do not authorize private metrics or create a public proxy route"() {
        given:
        def runtime = fixture(['INTERNAL_METRICS_KEY': KEY], { true } as BooleanSupplier)
        def client = HttpClient.newHttpClient()
        when:
        def responses = ['/internal/metrics', '/v1/internal/metrics'].collect { path ->
            client.send(HttpRequest.newBuilder(URI.create("http://localhost:${runtime.port()}${path}"))
                .header('Authorization', 'Bearer ' + 'a' * 43)
                .header('X-Identity-Id', UUID.randomUUID().toString()).GET().build(), HttpResponse.BodyHandlers.ofString())
        }
        then:
        responses*.statusCode() == [401, 404]
        cleanup:
        client?.close()
        runtime?.close()
    }

    private static ServiceRuntime fixture(Map<String, String> environment, BooleanSupplier readiness) {
        def origin = URI.create('http://127.0.0.1:1')
        def businessKey = 'synthetic-business-service-key-32-characters'
        def auth = new AuthProxy(origin, businessKey, java.time.Clock.systemUTC())
        def protectedProxy = new ProtectedProxy(origin, origin, origin, origin, businessKey)
        def assistant = new AssistantProxy(origin, origin, origin, businessKey, null, null, null)
        AutoCloseable resources = { try { auth.close() } finally { try { protectedProxy.close() } finally { assistant.close() } } }
        try {
            return ServiceRuntime.start('app-gateway', 0, readiness, resources, { config ->
                auth.configure(config)
                protectedProxy.configure(config)
                assistant.configure(config)
                Main.configureMetrics(config, environment, new OperationalMetrics(), readiness)
            })
        } catch (Exception failure) {
            resources.close()
            throw failure
        }
    }

    private static HttpResponse<String> scrape(HttpClient client, ServiceRuntime runtime, String key) {
        def request = HttpRequest.newBuilder(URI.create("http://localhost:${runtime.port()}/internal/metrics")).GET()
        if (key != null) request.header('X-Service-Key', key)
        client.send(request.build(), HttpResponse.BodyHandlers.ofString())
    }
}
