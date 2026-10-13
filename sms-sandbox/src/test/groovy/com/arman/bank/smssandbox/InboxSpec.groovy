package com.arman.bank.smssandbox

import spock.lang.Specification
import spock.lang.TempDir
import java.nio.file.Path
import java.time.*
import java.util.concurrent.*

class InboxSpec extends Specification {
    @TempDir Path directory
    def initial = Instant.parse('2026-01-01T00:00:00Z')
    def clock = Clock.fixed(initial, ZoneOffset.UTC)

    def 'messages and exact idempotent replies survive actual store close and reopen'() {
        given:
        def store = new Inbox(directory, clock)
        def first = store.submit('retry-1', '+971501234567', 'Synthetic OTP 123456', 'registration')
        store.close()
        def restarted = new Inbox(directory, clock)

        when:
        def replay = restarted.submit('retry-1', '+971501234567', 'Synthetic OTP 123456', 'registration')

        then:
        replay.replay()
        replay.message() == first.message()
        restarted.list('123456', 'registration').size() == 1
        restarted.list('', 'other').empty

        when:
        restarted.submit('retry-1', '+971501234567', 'Changed payload', 'registration')
        then:
        def error = thrown(Inbox.Rejection)
        error.status == 409
        cleanup:
        restarted?.close()
    }

    def 'clear removes contents durably but prevents resend until original retention expires'() {
        given:
        def store = new Inbox(directory, clock)
        store.submit('clear-1', '+971501234567', 'Private synthetic value', 'login')
        store.clear()
        store.close()
        def restarted = new Inbox(directory, clock)

        expect:
        restarted.list('', '').empty
        !directory.resolve('inbox.json').toFile().text.contains('Private synthetic value')
        !directory.resolve('inbox.json').toFile().text.contains('+971501234567')

        when:
        restarted.submit('clear-1', '+971501234567', 'Private synthetic value', 'login')
        then:
        def error = thrown(Inbox.Rejection)
        error.status == 410

        when:
        restarted.close()
        restarted = new Inbox(directory, Clock.fixed(initial.plus(Duration.ofHours(24)), ZoneOffset.UTC))
        def fresh = restarted.submit('clear-1', '+971501234567', 'Private synthetic value', 'login')
        then:
        !fresh.replay()
        cleanup:
        restarted?.close()
    }

    def 'concurrent retries produce one durable message and exclusive volume lock fails closed'() {
        given:
        def store = new Inbox(directory, clock)
        def pool = Executors.newFixedThreadPool(8)
        when:
        def results = (1..24).collect { pool.submit({ store.submit('same', '+971501234567', 'test', 'login') } as Callable) }
                .collect { it.get(10, TimeUnit.SECONDS) }
        then:
        results.count { !it.replay() } == 1
        results.collect { it.message().id() }.unique().size() == 1
        store.list('', '').size() == 1
        when:
        new Inbox(directory, clock)
        then:
        thrown(java.nio.channels.OverlappingFileLockException)
        cleanup:
        pool?.shutdownNow()
        store?.close()
    }

    def '24 hour retention is applied during restart'() {
        given:
        def store = new Inbox(directory, clock)
        store.submit('expired', '+971501234567', 'expired OTP', 'login')
        store.close()
        when:
        def restarted = new Inbox(directory, Clock.fixed(initial.plus(Duration.ofHours(24)), ZoneOffset.UTC))
        then:
        restarted.list('', '').empty
        !directory.resolve('inbox.json').toFile().text.contains('expired OTP')
        cleanup:
        restarted?.close()
    }

    def 'invalid UAE destination and bounded payloads fail before persistence'() {
        given:
        def store = new Inbox(directory, clock)
        when:
        store.submit(key, destination, body, purpose)
        then:
        def error = thrown(Inbox.Rejection)
        error.status == 400
        store.list('', '').empty
        cleanup:
        store?.close()
        where:
        key       | destination       | body       | purpose
        'key'     | '+15551234567'     | 'test'     | 'login'
        'key'     | '+971511234567'    | 'test'     | 'login'
        'key'     | '0501234567'       | 'test'     | 'login'
        'key'     | '+971501234567'    | 'x' * 2049 | 'login'
        'key'     | '+971501234567'    | ' '        | 'login'
        'key'     | '+971501234567'    | 'test'     | '<script>'
        '../oops' | '+971501234567'    | 'test'     | 'login'
    }

    def 'full capacity rejects new keys without evicting durable retry protection'() {
        given:
        def mapper = new com.fasterxml.jackson.databind.ObjectMapper()
        def entries = (1..1000).collect { new Inbox.Entry("key-$it", 'digest', clock.millis(), null) }
        mapper.writeValue(directory.resolve('inbox.json').toFile(), entries)
        def store = new Inbox(directory, clock)
        when:
        store.submit('new-key', '+971501234567', 'test', 'login')
        then:
        def error = thrown(Inbox.Rejection)
        error.status == 507
        mapper.readTree(directory.resolve('inbox.json').toFile()).size() == 1000
        cleanup:
        store?.close()
    }

    def 'failed snapshot write does not acknowledge or mutate existing inbox'() {
        given:
        def store = new Inbox(directory, clock)
        def first = store.submit('first', '+971501234567', 'kept', 'login')
        java.nio.file.Files.createDirectory(directory.resolve('inbox.tmp'))
        when:
        store.submit('second', '+971501234567', 'must not persist', 'login')
        then:
        thrown(IOException)
        store.list('', '')*.id() == [first.message().id()]
        when:
        store.close()
        store = new Inbox(directory, clock)
        then:
        store.list('', '')*.id() == [first.message().id()]
        cleanup:
        store?.close()
    }
}
