package com.arman.bank.runtime

import spock.lang.Specification
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class OperationalMetricsSpec extends Specification {
    def "unknown and unavailable observations are absent instead of healthy zeroes"() {
        given:
        def metrics = new OperationalMetrics()
        expect:
        metrics.render() == ''
        when:
        metrics.set(OperationalMetrics.Gauge.REPLICA_LAG_SECONDS, 17)
        then:
        metrics.render().contains('armoney_replica_lag_seconds 17\n')
        when:
        metrics.unavailable(OperationalMetrics.Gauge.REPLICA_LAG_SECONDS)
        then:
        metrics.render() == ''
    }

    def "negative gauges and nonboolean readiness are rejected"() {
        when:
        new OperationalMetrics().set(gauge, value)
        then:
        thrown(IllegalArgumentException)
        where:
        gauge                                      | value
        OperationalMetrics.Gauge.OUTBOX_BACKLOG     | -1L
        OperationalMetrics.Gauge.READY              | 2L
    }

    def "concurrent failure increments retain all observations"() {
        given:
        def metrics = new OperationalMetrics()
        def pool = Executors.newFixedThreadPool(4)
        when:
        def tasks = (1..4).collect {
            pool.submit({ (1..250).each { metrics.increment(OperationalMetrics.Counter.OUTBOX_FAILURES) } } as Runnable)
        }
        tasks.each { it.get(10, TimeUnit.SECONDS) }
        then:
        metrics.render().contains('armoney_outbox_failures_total 1000\n')
        cleanup:
        pool.shutdownNow()
        pool.awaitTermination(10, TimeUnit.SECONDS)
    }
}
