package com.arman.bank.ledgerservice.infrastructure

import com.arman.bank.runtime.OperationalMetrics
import java.time.Instant
import spock.lang.Specification

class LedgerReplicaObservationSpec extends Specification {
    static final Instant NOW = Instant.parse('2026-10-16T00:00:00Z')

    def "only complete fresh streaming observations can publish the maximum delay"() {
        expect:
        LedgerReplicaObservation.classify(['one', 'two'] as Set, [sample('one', 0, null), sample('two', 1, 1.01)]).asLong == 2
        LedgerReplicaObservation.classify(['one'] as Set, [sample('one', 0, null)]).asLong == 0
        LedgerReplicaObservation.classify(['one'] as Set, [sample('one', 0, 9)]).asLong == 0
        LedgerReplicaObservation.classify(['one'] as Set, [sample('other', 1, 100), sample('one', 1, 0.01)]).asLong == 1
    }

    def "incomplete invalid or uncertain samples remain unknown"() {
        expect:
        LedgerReplicaObservation.classify(expected as Set, rows).isEmpty()
        where:
        expected       | rows
        []             | [sample('one', 0, null)]
        ['one']        | []
        ['one', 'two'] | [sample('one', 0, null)]
        ['one']        | [sample('one', 0, null), sample('one', 0, null)]
        ['one']        | [sample('one', 1, null)]
        ['one']        | [sample('one', 1, 0)]
        ['one']        | [sample('one', 1, -1)]
        ['one']        | [sample('one', -1, 1)]
        ['one']        | [sample('one', null, 1)]
        ['one']        | [sample('one', 1, new BigDecimal('9223372036854775808'))]
        ['one']        | [sample('one', 0, null, [recovery: true])]
        ['one']        | [sample('one', 0, null, [recovery: null])]
        ['one']        | [sample('one', 0, null, [state: 'catchup'])]
        ['one']        | [sample('one', 0, null, [reply: NOW.minusSeconds(31)])]
        ['one']        | [sample('one', 0, null, [reply: NOW.plusSeconds(1)])]
        ['one']        | [sample('one', 0, null, [reply: null])]
        ['one']        | [sample('one', 0, null, [observed: null])]
        ['one']        | [sample('one', 0, null, [replayLsn: null])]
    }

    def "expected names require strict distinct ASCII members matching configured endpoints"() {
        when:
        LedgerReplicaObservation.configuredNames(['LEDGER_METRICS_REPLICA_NAMES': names, 'LEDGER_READ_DB_URLS': urls])
        then:
        thrown(IllegalArgumentException)
        where:
        names          | urls
        ''             | ''
        'one,'         | 'a,b'
        'one,one'      | 'a,b'
        ' one'         | 'a'
        'one\ntwo'     | 'a'
        '\u00e9'       | 'a'
        'x' * 65       | 'a'
        'one'          | 'a,b'
        'one'          | ''
    }

    def "absent names or disabled metrics allocate no pool and remove prior zero"() {
        given:
        def metrics = new OperationalMetrics()
        metrics.set(OperationalMetrics.Gauge.REPLICA_LAG_SECONDS, 0)
        def observer = new LedgerReplicaObservation(environment, metrics)
        when:
        observer.observe()
        then:
        !metrics.render().contains('replica_lag')
        cleanup:
        observer.close()
        where:
        environment << [[:], [INTERNAL_METRICS_KEY: 'synthetic'],
            [LEDGER_METRICS_REPLICA_NAMES: 'one', LEDGER_READ_DB_URLS: 'unused']]
    }

    private static LedgerReplicaObservation.Sample sample(String name, Number bytes, Number seconds, Map overrides = [:]) {
        def values = [name: name, recovery: false, state: 'streaming', observed: NOW, reply: NOW,
                      replayLsn: '0/123', bytes: bytes == null ? null : new BigDecimal(bytes.toString()),
                      seconds: seconds == null ? null : new BigDecimal(seconds.toString())] + overrides
        new LedgerReplicaObservation.Sample(values.name, values.recovery, values.state, values.observed,
            values.reply, values.replayLsn, values.bytes, values.seconds)
    }
}
