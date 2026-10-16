package com.arman.bank.authservice

import com.arman.bank.authservice.infrastructure.AuthRoutes
import com.arman.bank.runtime.OperationalMetrics
import com.arman.bank.runtime.ServiceRuntime
import io.javalin.Javalin
import spock.lang.Specification
import java.net.http.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.function.BooleanSupplier

class AuthMetricsSpec extends Specification {
    static final String METRICS_KEY = 'synthetic-auth-metrics-key-32-characters'
    static final String AUTH_KEY = 'synthetic-auth-business-key-32-characters'

    def "business key alone leaves metrics disabled"() {
        given:
        def runtime = fixture(['INTERNAL_AUTH_KEY': AUTH_KEY], { true } as BooleanSupplier)
        def client = HttpClient.newHttpClient()
        expect:
        request(client, runtime, '/internal/metrics', AUTH_KEY).statusCode() == 404
        cleanup:
        client?.close()
        runtime?.close()
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

    def "scrape requires dedicated key and shares readiness without customer labels or invented measurements"() {
        given:
        // Synthetic readiness only; no database or credential operation is performed.
        def ready = new AtomicBoolean(true)
        def runtime = fixture(['INTERNAL_METRICS_KEY': METRICS_KEY, 'INTERNAL_AUTH_KEY': AUTH_KEY],
            { ready.get() } as BooleanSupplier)
        def client = HttpClient.newHttpClient()
        expect:
        request(client, runtime, '/internal/metrics', AUTH_KEY).statusCode() == 401
        request(client, runtime, '/internal/metrics', null, 'Bearer synthetic-session').statusCode() == 401
        request(client, runtime, '/internal/metrics', null).statusCode() == 401
        request(client, runtime, '/health/ready', null).statusCode() == 200
        request(client, runtime, '/internal/metrics', METRICS_KEY).body().contains('armoney_ready 1\n')
        when:
        ready.set(false)
        def scrape = request(client, runtime, '/internal/metrics', METRICS_KEY)
        then:
        request(client, runtime, '/health/ready', null).statusCode() == 503
        scrape.statusCode() == 200
        scrape.headers().firstValue('Cache-Control').orElse('') == 'no-store'
        scrape.body().contains('armoney_ready 0\n')
        !scrape.body().contains('{')
        !scrape.body().contains('outbox')
        !scrape.body().contains('recovery')
        !scrape.body().contains('replica_lag')
        !scrape.body().contains('synthetic-')
        cleanup:
        client?.close()
        runtime?.close()
    }

    private static ServiceRuntime fixture(Map<String, String> environment, BooleanSupplier readiness) {
        ServiceRuntime.start('auth-service', 0, readiness, null, { config ->
            // Register real auth middleware; metrics must coexist without invoking auth operations.
            new AuthRoutes(null, AUTH_KEY).configure(config)
            Main.configureMetrics(config, environment, new OperationalMetrics(), readiness)
        })
    }

    private static HttpResponse<String> request(HttpClient client, ServiceRuntime runtime, String path,
            String key, String bearer = null) {
        def request = HttpRequest.newBuilder(URI.create("http://localhost:${runtime.port()}${path}")).GET()
        if (key != null) request.header('X-Service-Key', key)
        if (bearer != null) request.header('Authorization', bearer)
        client.send(request.build(), HttpResponse.BodyHandlers.ofString())
    }
}
