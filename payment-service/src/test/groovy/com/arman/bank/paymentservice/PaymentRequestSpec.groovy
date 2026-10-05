package com.arman.bank.paymentservice

import com.arman.bank.paymentservice.domain.PaymentRequest
import spock.lang.Specification

class PaymentRequestSpec extends Specification {
    def 'AED preserves integer fils and payload hash detects amount changes'() {
        given:
        def source = UUID.randomUUID()
        def recipient = UUID.randomUUID()
        def request = new PaymentRequest(source, recipient, '+971501234567', 'AED', amount)
        expect:
        request.currency() == 'AED'
        request.amountMinor() == amount
        request.hash() == new PaymentRequest(source, recipient, '+971501234567', 'AED', amount).hash()
        request.hash() != new PaymentRequest(source, recipient, '+971501234567', 'AED', amount == 1L ? 2L : 1L).hash()
        where:
        amount << [1L, 100L, 125L, Long.MAX_VALUE]
    }

    def 'unsupported currency is rejected: #currency'() {
        when:
        new PaymentRequest(UUID.randomUUID(), UUID.randomUUID(), '+971501234567', currency, 100L)
        then:
        thrown(IllegalArgumentException)
        where:
        currency << ['USD', 'EUR', 'GBP', 'aed', '', null]
    }
}
