package com.arman.bank.paymentservice

import com.arman.bank.runtime.*
import io.javalin.Javalin
import spock.lang.Specification
import java.net.http.*
import java.util.concurrent.atomic.AtomicInteger
import java.util.function.BooleanSupplier

class PaymentMetricsSpec extends Specification {
    static final String KEY = 'synthetic-payment-metrics-key-32-characters'

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
        !accepted.body().contains('oldest_pending_age_seconds')
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

    def "only authorized scrapes observe backlogs and health does not observe them"() {
        given:
        def calls = new AtomicInteger()
        def measurements = new OperationalMetrics()
        BooleanSupplier readiness = { true } as BooleanSupplier
        def runtime = ServiceRuntime.start('payment-service', 0, readiness, null, { config ->
            Main.configureMetrics(config, ['INTERNAL_METRICS_KEY': KEY], measurements, readiness, {
                calls.incrementAndGet()
                measurements.set(OperationalMetrics.Gauge.RECOVERY_BACKLOG, 3)
                measurements.set(OperationalMetrics.Gauge.OUTBOX_BACKLOG, 5)
                measurements.set(OperationalMetrics.Gauge.RECOVERY_OLDEST_PENDING_AGE_SECONDS, 60)
                measurements.set(OperationalMetrics.Gauge.OUTBOX_OLDEST_PENDING_AGE_SECONDS, 120)
                if (calls.get() > 1) {
                    measurements.unavailable(OperationalMetrics.Gauge.RECOVERY_OLDEST_PENDING_AGE_SECONDS)
                    measurements.set(OperationalMetrics.Gauge.OUTBOX_OLDEST_PENDING_AGE_SECONDS, 0)
                }
            } as Runnable)
        })
        def client = HttpClient.newHttpClient()
        when:
        def rejected = scrape(client, runtime, 'incorrect')
        def health = client.send(HttpRequest.newBuilder(URI.create("http://localhost:${runtime.port()}/health/ready"))
            .GET().build(), HttpResponse.BodyHandlers.ofString())
        then:
        rejected.statusCode() == 401
        health.statusCode() == 200
        calls.get() == 0
        when:
        def accepted = scrape(client, runtime, KEY)
        then:
        calls.get() == 1
        accepted.body().contains('armoney_recovery_backlog 3\n')
        accepted.body().contains('armoney_outbox_backlog 5\n')
        accepted.body().contains('armoney_recovery_oldest_pending_age_seconds 60\n')
        accepted.body().contains('armoney_outbox_oldest_pending_age_seconds 120\n')
        !accepted.body().contains('failures_total')
        !accepted.body().contains('lag_seconds')
        when:
        def unknown = scrape(client, runtime, KEY)
        then:
        unknown.statusCode() == 200
        !unknown.body().contains('armoney_recovery_oldest_pending_age_seconds')
        unknown.body().contains('armoney_outbox_oldest_pending_age_seconds 0\n')
        unknown.body().contains('armoney_recovery_backlog 3\n')
        unknown.body().contains('armoney_outbox_backlog 5\n')
        cleanup:
        client?.close()
        runtime?.close()
    }

    private static ServiceRuntime fixture(Map<String, String> environment, BooleanSupplier readiness) {
        ServiceRuntime.start('payment-service', 0, readiness, null,
            { config -> Main.configureMetrics(config, environment, new OperationalMetrics(), readiness) })
    }

    private static HttpResponse<String> scrape(HttpClient client, ServiceRuntime runtime, String key) {
        def request = HttpRequest.newBuilder(URI.create("http://localhost:${runtime.port()}/internal/metrics")).GET()
        if (key != null) request.header('X-Service-Key', key)
        client.send(request.build(), HttpResponse.BodyHandlers.ofString())
    }
}
