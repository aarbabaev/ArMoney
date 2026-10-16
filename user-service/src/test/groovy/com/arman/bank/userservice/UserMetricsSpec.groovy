package com.arman.bank.userservice

import com.arman.bank.runtime.OperationalMetrics
import com.arman.bank.runtime.ServiceRuntime
import io.javalin.Javalin
import spock.lang.Specification
import java.net.http.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.function.BooleanSupplier

class UserMetricsSpec extends Specification {
    static final String KEY = 'synthetic-user-metrics-key-32-characters'

    def "business auth key alone does not enable user metrics"() {
        given:
        def runtime = fixture(['INTERNAL_AUTH_KEY': KEY], { true } as BooleanSupplier)
        def client = HttpClient.newHttpClient()
        expect:
        request(client, runtime, '/internal/metrics', KEY).statusCode() == 404
        cleanup:
        client?.close()
        runtime?.close()
    }

    def "explicit invalid metrics key prevents user metrics startup"() {
        when:
        Javalin.create { config -> Main.configureMetrics(config,
            ['INTERNAL_METRICS_KEY': key], new OperationalMetrics(), { true } as BooleanSupplier) }
        then:
        thrown(IllegalArgumentException)
        where:
        key << ['', ' ' * 32, 'short', 'x' * 257]
    }

    def "private scrape follows changing aggregate readiness without exposing shard labels"() {
        given:
        // Synthetic aggregate probe; actual database/shard behavior belongs to integration tests.
        def ready = new AtomicBoolean(true)
        def runtime = fixture(['INTERNAL_METRICS_KEY': KEY, 'INTERNAL_AUTH_KEY': 'business-key'],
            { ready.get() } as BooleanSupplier)
        def client = HttpClient.newHttpClient()
        expect:
        request(client, runtime, '/internal/metrics', 'business-key').statusCode() == 401
        request(client, runtime, '/internal/metrics', null).statusCode() == 401
        request(client, runtime, '/health/ready', null).statusCode() == 200
        request(client, runtime, '/internal/metrics', KEY).body().contains('armoney_ready 1\n')
        when:
        ready.set(false)
        def health = request(client, runtime, '/health/ready', null)
        def scrape = request(client, runtime, '/internal/metrics', KEY)
        then:
        health.statusCode() == 503
        scrape.statusCode() == 200
        scrape.body().contains('armoney_ready 0\n')
        !scrape.body().contains('{')
        !scrape.body().contains('backlog')
        !scrape.body().contains('replica_lag')
        cleanup:
        client?.close()
        runtime?.close()
    }

    private static ServiceRuntime fixture(Map<String, String> environment, BooleanSupplier readiness) {
        ServiceRuntime.start('user-service', 0, readiness, null,
            { config -> Main.configureMetrics(config, environment, new OperationalMetrics(), readiness) })
    }

    private static HttpResponse<String> request(HttpClient client, ServiceRuntime runtime, String path, String key) {
        def request = HttpRequest.newBuilder(URI.create("http://localhost:${runtime.port()}${path}")).GET()
        if (key != null) request.header('X-Service-Key', key)
        client.send(request.build(), HttpResponse.BodyHandlers.ofString())
    }
}
