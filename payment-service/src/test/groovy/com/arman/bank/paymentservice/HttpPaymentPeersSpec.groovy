package com.arman.bank.paymentservice

import com.arman.bank.paymentservice.application.*
import com.arman.bank.paymentservice.domain.*
import com.arman.bank.paymentservice.infrastructure.HttpPaymentPeers
import com.arman.bank.runtime.InternalHttp
import com.sun.net.httpserver.HttpServer
import spock.lang.*
import java.time.Instant

class HttpPaymentPeersSpec extends Specification {
    HttpServer server
    HttpPaymentPeers peers
    UUID alice = UUID.randomUUID()
    UUID bob = UUID.randomUUID()
    UUID source = UUID.randomUUID()
    UUID destination = UUID.randomUUID()
    UUID debit = UUID.randomUUID()
    UUID credit = UUID.randomUUID()
    String key = 'payment-test-key-at-least-thirty-two-characters'
    def setup() {
        server = HttpServer.create(new InetSocketAddress('localhost', 0), 0)
        server.start()
        def origin = "http://localhost:${server.address.port}"
        peers = new HttpPaymentPeers(origin, origin, origin, key)
    }
    def cleanup() { peers?.close(); server?.stop(0) }
    Payment payment() {
        def request = new PaymentRequest(source, bob, '+971501234567', 'AED', 125L)
        new Payment(UUID.randomUUID(), alice, request, destination, debit, credit, request.hash(), 'PENDING', null, Instant.now(), Instant.now())
    }
    Map result(Payment p) { [payment_id:p.id().toString(), debit_account_id:debit.toString(), credit_account_id:credit.toString(), currency:'AED', amount_minor:125, outcome:'POSTED'] }
    void route(String path, int status, Object response, Closure inspect = {}) {
        server.createContext(path) { exchange ->
            try {
                inspect(exchange)
                byte[] bytes = InternalHttp.JSON.writeValueAsBytes(response)
                exchange.responseHeaders.set('Content-Type', 'application/json')
                exchange.sendResponseHeaders(status, bytes.length)
                exchange.responseBody.write(bytes)
            } finally { exchange.close() }
        }
    }

    def 'ledger command uses stable payment and trusted requester with complete validated result'() {
        given:
        def p = payment()
        def captured = new java.util.concurrent.atomic.AtomicReference()
        route('/v1/ledger/transfers', 200, result(p)) { x ->
            captured.set([body:InternalHttp.JSON.readTree(x.requestBody), owner:x.requestHeaders.getFirst('X-Identity-Id'), key:x.requestHeaders.getFirst('X-Service-Key')])
        }
        expect:
        peers.post(p) == PaymentPeers.Outcome.POSTED
        captured.get().owner == alice.toString()
        captured.get().key == key
        captured.get().body.get('payment_id').asText() == p.id().toString()
        captured.get().body.get('amount_minor').longValue() == 125L
        captured.get().body.get('currency').asText() == 'AED'
    }

    @Unroll
    def 'ledger mismatch or unrecognized outcome stays uncertain: #field'() {
        given:
        def p = payment()
        route('/v1/ledger/transfers', 200, result(p) + [(field):value])
        when:
        peers.post(p)
        then:
        thrown(IOException)
        where:
        field               | value
        'payment_id'        | UUID.randomUUID().toString()
        'debit_account_id'  | UUID.randomUUID().toString()
        'credit_account_id' | UUID.randomUUID().toString()
        'currency'          | 'USD'
        'currency'          | 'EUR'
        'currency'          | 'GBP'
        'amount_minor'      | 126
        'amount_minor'      | 125.0
        'amount_minor'      | '125'
        'outcome'           | 'PENDING'
        'outcome'           | 'COMPLETED'
        'outcome'           | 'INSUFFICIENT_FUNDS'
    }

    @Unroll
    def 'only recognized complete rejection responses are terminal: #outcome'() {
        given:
        def p = payment()
        route('/v1/ledger/transfers', 409, result(p) + [outcome:outcome])
        expect:
        peers.post(p).name() == outcome
        where:
        outcome << ['INSUFFICIENT_FUNDS', 'INVALID_ACCOUNT', 'BALANCE_LIMIT']
    }

    def 'oversized ledger body is rejected before decoding'() {
        given:
        def p = payment()
        route('/v1/ledger/transfers', 200, result(p) + [padding:'x' * 10000])
        when:
        peers.post(p)
        then:
        thrown(Exception)
    }

    def 'resolve verifies confirmed phone plus both wallet owners currency and readiness'() {
        given:
        def p = payment()
        route('/v1/users/resolve-phone', 200, [identity_id:bob.toString(), display_name:'Bob', phone_number:'+971501234567'])
        route('/v1/internal/wallets/' + source, 200, [id:source.toString(),owner_id:alice.toString(),currency:'AED',status:'ACTIVE',provisioning_status:'READY',ledger_account_id:debit.toString()])
        route('/v1/internal/wallets/by-owner/' + bob + '/currency/AED', 200, [id:destination.toString(),owner_id:bob.toString(),currency:'AED',status:'ACTIVE',provisioning_status:'READY',ledger_account_id:credit.toString()])
        expect:
        peers.resolve(alice, p.request()) == new PaymentPeers.Mapping(destination, debit, credit)
    }

    def 'phone reassigned since confirmation cannot redirect a new payment'() {
        given:
        route('/v1/users/resolve-phone', 200, [identity_id:UUID.randomUUID().toString(),display_name:'Other',phone_number:'+971501234567'])
        when:
        peers.resolve(alice, payment().request())
        then:
        def failure = thrown(PaymentFailure)
        failure.status() == 409
        failure.code() == 'recipient_changed'
    }

    @Unroll
    def 'invalid source wallet is rejected: #field'() {
        given:
        route('/v1/users/resolve-phone', 200, [identity_id:bob.toString(),display_name:'Bob',phone_number:'+971501234567'])
        route('/v1/internal/wallets/' + source, 200, [id:source.toString(),owner_id:alice.toString(),currency:'AED',status:'ACTIVE',provisioning_status:'READY',ledger_account_id:debit.toString()] + [(field):value])
        when:
        peers.resolve(alice, payment().request())
        then:
        def failure = thrown(PaymentFailure)
        failure.status() == status
        where:
        field                 | value                        | status
        'owner_id'            | UUID.randomUUID().toString() | 404
        'id'                  | UUID.randomUUID().toString() | 404
        'currency'            | 'USD'                        | 409
        'currency'            | 'EUR'                        | 409
        'currency'            | 'GBP'                        | 409
        'status'              | 'CLOSED'                     | 409
        'provisioning_status' | 'PENDING'                    | 409
    }
}
