package com.arman.bank.runtime

import spock.lang.Specification
import java.net.http.*
import java.util.concurrent.atomic.AtomicInteger
import java.util.function.BooleanSupplier

class OperationalMetricsHttpSpec extends Specification {
    static final String KEY = 'synthetic-metrics-key-at-least-32-characters'

    def "unauthenticated scrapes cannot invoke probes or disclose metrics"() {
        given:
        def calls = new AtomicInteger()
        def metrics = new OperationalMetrics()
        metrics.set(OperationalMetrics.Gauge.OUTBOX_BACKLOG, 7)
        def runtime = ServiceRuntime.start('metrics-fixture', 0, { true } as BooleanSupplier, null,
            { config -> OperationalMetricsHttp.configure(config, KEY, metrics,
                { calls.incrementAndGet(); true } as BooleanSupplier) })
        def client = HttpClient.newHttpClient()
        when:
        def responses = [null, 'incorrect', 'x' * 257].collect { supplied ->
            def request = HttpRequest.newBuilder(URI.create("http://localhost:${runtime.port()}/internal/metrics")).GET()
            if (supplied != null) request.header('X-Service-Key', supplied)
            client.send(request.build(), HttpResponse.BodyHandlers.ofString())
        }
        then:
        responses.every { it.statusCode() == 401 && it.body() == '{"error":"request_rejected"}' }
        responses.every { it.headers().firstValue('Cache-Control').orElse('') == 'no-store' }
        calls.get() == 0
        cleanup:
        client?.close()
        runtime?.close()
    }

    def "authorized scrape exports technical counters and fail closed readiness"() {
        given:
        def metrics = new OperationalMetrics()
        metrics.increment(OperationalMetrics.Counter.OUTBOX_FAILURES)
        def runtime = ServiceRuntime.start('metrics-fixture', 0, { true } as BooleanSupplier, null,
            { config -> OperationalMetricsHttp.configure(config, KEY, metrics,
                { throw new IllegalStateException('synthetic sensitive probe detail') } as BooleanSupplier) })
        def client = HttpClient.newHttpClient()
        when:
        def response = client.send(HttpRequest.newBuilder(URI.create("http://localhost:${runtime.port()}/internal/metrics"))
            .header('X-Service-Key', KEY).GET().build(), HttpResponse.BodyHandlers.ofString())
        then:
        response.statusCode() == 200
        response.headers().firstValue('Content-Type').orElse('').startsWith('text/plain')
        response.body().contains('armoney_ready 0\n')
        response.body().contains('armoney_outbox_failures_total 1\n')
        !response.body().contains('sensitive')
        !response.body().contains('armoney_recovery_backlog')
        cleanup:
        client?.close()
        runtime?.close()
    }

    def "private scrape is absent until explicitly configured"() {
        given:
        def runtime = ServiceRuntime.start('metrics-fixture', 0, { true } as BooleanSupplier, null, {})
        def client = HttpClient.newHttpClient()
        expect:
        client.send(HttpRequest.newBuilder(URI.create("http://localhost:${runtime.port()}/internal/metrics"))
            .header('X-Service-Key', KEY).GET().build(), HttpResponse.BodyHandlers.ofString()).statusCode() == 404
        cleanup:
        client?.close()
        runtime?.close()
    }

    def "observation callback is authorized and a failed observation removes stale gauges"() {
        given:
        def calls = new AtomicInteger()
        def metrics = new OperationalMetrics()
        metrics.set(OperationalMetrics.Gauge.OUTBOX_BACKLOG, 7)
        def runtime = ServiceRuntime.start('metrics-fixture', 0, { true } as BooleanSupplier, null,
            { config -> OperationalMetricsHttp.configure(config, KEY, metrics, { true } as BooleanSupplier,
                { calls.incrementAndGet(); throw new IllegalStateException('private observation detail') } as Runnable) })
        def client = HttpClient.newHttpClient()
        def endpoint = URI.create("http://localhost:${runtime.port()}/internal/metrics")
        when:
        def rejected = client.send(HttpRequest.newBuilder(endpoint).GET().build(), HttpResponse.BodyHandlers.ofString())
        then:
        rejected.statusCode() == 401
        calls.get() == 0
        when:
        def accepted = client.send(HttpRequest.newBuilder(endpoint).header('X-Service-Key', KEY).GET().build(),
            HttpResponse.BodyHandlers.ofString())
        then:
        accepted.statusCode() == 200
        calls.get() == 1
        accepted.body().contains('armoney_ready 1\n')
        !accepted.body().contains('armoney_outbox_backlog')
        !accepted.body().contains('private observation detail')
        cleanup:
        client?.close()
        runtime?.close()
    }

    def "metrics credentials must fit a printable nonwhitespace HTTP header"() {
        when:
        OperationalMetricsHttp.configure(null, key, new OperationalMetrics(), { true } as BooleanSupplier)
        then:
        thrown(IllegalArgumentException)
        where:
        key << [('x' * 31) + '\n', ('x' * 31) + ' ', ('x' * 31) + '\u007f', ('x' * 31) + '\u00e9']
    }
}
