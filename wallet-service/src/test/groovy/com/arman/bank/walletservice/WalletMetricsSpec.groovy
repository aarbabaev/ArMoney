package com.arman.bank.walletservice

import com.arman.bank.runtime.OperationalMetrics
import com.arman.bank.runtime.ServiceRuntime
import io.javalin.Javalin
import spock.lang.Specification
import java.net.http.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.function.BooleanSupplier

class WalletMetricsSpec extends Specification {
    static final String KEY = 'synthetic-wallet-metrics-key-32-characters'

    def "business key alone does not enable metrics"() {
        given:
        def runtime = fixture(['INTERNAL_AUTH_KEY': KEY], { true } as BooleanSupplier)
        def client = HttpClient.newHttpClient()
        expect:
        get(client, runtime, '/internal/metrics', KEY).statusCode() == 404
        cleanup:
        client?.close()
        runtime?.close()
    }

    def "explicit invalid dedicated key fails configuration"() {
        when:
        Javalin.create { config -> Main.configureMetrics(config,
            ['INTERNAL_METRICS_KEY': key], new OperationalMetrics(), { true } as BooleanSupplier) }
        then:
        thrown(IllegalArgumentException)
        where:
        key << ['', ' ' * 32, 'short', 'x' * 257]
    }

    def "private scrape uses dedicated key and tracks the existing readiness probe"() {
        given:
        def ready = new AtomicBoolean(true)
        def runtime = fixture(['INTERNAL_METRICS_KEY': KEY, 'INTERNAL_AUTH_KEY': 'business-key'],
            { ready.get() } as BooleanSupplier)
        def client = HttpClient.newHttpClient()
        expect:
        [null, 'business-key'].every { get(client, runtime, '/internal/metrics', it).statusCode() == 401 }
        get(client, runtime, '/health/ready', null).statusCode() == 200
        get(client, runtime, '/internal/metrics', KEY).body().contains('armoney_ready 1\n')
        when:
        ready.set(false)
        def scrape = get(client, runtime, '/internal/metrics', KEY)
        then:
        get(client, runtime, '/health/ready', null).statusCode() == 503
        scrape.statusCode() == 200
        scrape.headers().firstValue('Cache-Control').orElse('') == 'no-store'
        scrape.body().readLines().findAll { !it.startsWith('#') } == ['armoney_ready 0']
        cleanup:
        client?.close()
        runtime?.close()
    }

    private static ServiceRuntime fixture(Map<String, String> environment, BooleanSupplier readiness) {
        ServiceRuntime.start('wallet-service', 0, readiness, null,
            { config -> Main.configureMetrics(config, environment, new OperationalMetrics(), readiness) })
    }

    private static HttpResponse<String> get(HttpClient client, ServiceRuntime runtime, String path, String key) {
        def request = HttpRequest.newBuilder(URI.create("http://localhost:${runtime.port()}${path}")).GET()
        if (key != null) request.header('X-Service-Key', key)
        client.send(request.build(), HttpResponse.BodyHandlers.ofString())
    }
}
