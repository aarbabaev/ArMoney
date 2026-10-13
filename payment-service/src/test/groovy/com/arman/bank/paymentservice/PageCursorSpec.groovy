package com.arman.bank.paymentservice

import com.arman.bank.paymentservice.application.*
import com.arman.bank.paymentservice.infrastructure.PageCursor
import java.time.Instant
import spock.lang.Specification

class PageCursorSpec extends Specification {
    def codec = new PageCursor('synthetic-pagination-key-at-least-32-characters')
    def requester = UUID.randomUUID()
    def position = new PagePosition(Instant.parse('2026-10-13T12:34:56.123456Z'), UUID.randomUUID())

    def 'opaque cursor preserves microsecond precision and uuid across service instances'() {
        when:
        def cursor = codec.encode(requester, false, position)
        then:
        cursor ==~ /[A-Za-z0-9_-]{104}/
        new PageCursor('synthetic-pagination-key-at-least-32-characters').decode(cursor, requester, false) == position
    }

    def 'requester resource and signing key are bound to the cursor'() {
        given:
        def cursor = codec.encode(requester, false, position)
        when:
        codec.decode(cursor, UUID.randomUUID(), false)
        then:
        thrown(PaymentFailure)
        when:
        codec.decode(cursor, requester, true)
        then:
        thrown(PaymentFailure)
        when:
        new PageCursor('rotated-synthetic-key-at-least-32-characters').decode(cursor, requester, false)
        then:
        thrown(PaymentFailure)
    }

    def 'all payload and signature byte mutations fail closed'() {
        given:
        def bytes = Base64.urlDecoder.decode(codec.encode(requester, false, position))
        expect:
        (0..<bytes.length).every { index ->
            def changed = Arrays.copyOf(bytes, bytes.length)
            changed[index] = (byte) (changed[index] ^ 1)
            try { codec.decode(Base64.urlEncoder.withoutPadding().encodeToString(changed), requester, false); false }
            catch (PaymentFailure e) { e.status() == 400 && e.code() == 'invalid_page_request' }
        }
    }

    def 'malformed or oversized cursor is a bounded client failure'() {
        when:
        codec.decode(cursor, requester, false)
        then:
        def e = thrown(PaymentFailure)
        e.status() == 400
        e.code() == 'invalid_page_request'
        where:
        cursor << [null, '', 'x', 'A' * 103, 'A' * 104, 'A' * 513, 'A' * 103 + '=', 'A' * 103 + '.']
    }
}
