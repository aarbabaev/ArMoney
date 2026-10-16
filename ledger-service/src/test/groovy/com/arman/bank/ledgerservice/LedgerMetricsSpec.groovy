package com.arman.bank.ledgerservice

import com.arman.bank.runtime.*
import io.javalin.Javalin
import spock.lang.Specification
import java.net.http.*
import java.util.concurrent.atomic.AtomicInteger
import java.util.function.BooleanSupplier

class LedgerMetricsSpec extends Specification {
    static final String KEY = 'synthetic-ledger-metrics-key-32-characters'

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
        def rejected = [null, 'business-key', 'incorrect'].collect { scrape(client, runtime, it) }
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
        !accepted.body().contains('replica_lag')
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

    private static ServiceRuntime fixture(Map<String, String> environment, BooleanSupplier readiness) {
        ServiceRuntime.start('ledger-service', 0, readiness, null,
            { config -> Main.configureMetrics(config, environment, new OperationalMetrics(), readiness) })
    }

    def "observation runs only after authentication and removes a previous zero on failure"() {
        given:
        def calls = new AtomicInteger()
        def metrics = new OperationalMetrics()
        def runtime = ServiceRuntime.start('ledger-service', 0, { true } as BooleanSupplier, null,
            { config -> Main.configureMetrics(config, ['INTERNAL_METRICS_KEY': KEY], metrics,
                { true } as BooleanSupplier, {
                    if (calls.incrementAndGet() == 1) metrics.set(OperationalMetrics.Gauge.REPLICA_LAG_SECONDS, 0)
                    else throw new IllegalStateException('synthetic observation failure')
                } as Runnable) })
        def client = HttpClient.newHttpClient()
        expect:
        scrape(client, runtime, 'incorrect').statusCode() == 401
        calls.get() == 0
        scrape(client, runtime, KEY).body().contains('armoney_replica_lag_seconds 0\n')
        def unavailable = scrape(client, runtime, KEY)
        unavailable.statusCode() == 200
        unavailable.body().contains('armoney_ready 1\n')
        !unavailable.body().contains('replica_lag')
        calls.get() == 2
        cleanup:
        client?.close()
        runtime?.close()
    }

    private static HttpResponse<String> scrape(HttpClient client, ServiceRuntime runtime, String key) {
        def request = HttpRequest.newBuilder(URI.create("http://localhost:${runtime.port()}/internal/metrics")).GET()
        if (key != null) request.header('X-Service-Key', key)
        client.send(request.build(), HttpResponse.BodyHandlers.ofString())
    }
}
