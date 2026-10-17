package com.arman.bank.paymentservice

import com.arman.bank.paymentservice.application.*
import spock.lang.Specification
import spock.lang.Unroll

/** Synthetic worker observations; these tests do not establish ledger money movement. */
class WorkerMetricsSpec extends Specification {
    @Unroll
    def 'payment tick observes #scenario once and preserves retry behavior'() {
        given:
        def store = Mock(PaymentStore)
        def peers = Mock(PaymentPeers)
        def claim = new PaymentStore.Claim(null, UUID.randomUUID(), 9)
        int failures = 0
        def worker = new PaymentWorker(store, peers, { failures++; if (callbackThrows) throw new IllegalStateException('observer') })
        store.claim() >> { if (scenario == 'claim error') throw new IllegalStateException('store'); Optional.of(claim) }
        peers.post(_) >> { if (scenario in ['dependency error', 'nested error']) throw new IllegalStateException('peer'); if (scenario == 'interrupted') throw new InterruptedException(); outcome }

        when:
        worker.tick()

        then:
        failures == expected
        Thread.currentThread().isInterrupted() == (scenario == 'interrupted')
        retries * store.retry(claim) >> { if (scenario == 'nested error') throw new IllegalStateException('retry') }
        finishes * store.finish(claim, outcome) >> { if (scenario == 'finish error') throw new IllegalStateException('finish'); false }

        cleanup:
        Thread.interrupted()
        worker.close()

        where:
        scenario           | outcome                                 | retries | finishes | expected | callbackThrows
        'posted stale'     | PaymentPeers.Outcome.POSTED             | 0       | 1        | 0        | false
        'business reject'  | PaymentPeers.Outcome.INSUFFICIENT_FUNDS | 0       | 1        | 0        | false
        'invalid account'  | PaymentPeers.Outcome.INVALID_ACCOUNT   | 0       | 1        | 0        | false
        'balance limit'    | PaymentPeers.Outcome.BALANCE_LIMIT     | 0       | 1        | 0        | false
        'uncertain'        | null                                    | 1       | 0        | 1        | false
        'dependency error' | null                                    | 1       | 0        | 1        | false
        'nested error'     | null                                    | 1       | 0        | 1        | false
        'claim error'      | null                                    | 0       | 0        | 1        | false
        'finish error'     | PaymentPeers.Outcome.POSTED             | 1       | 1        | 1        | false
        'interrupted'      | null                                    | 0       | 0        | 0        | false
        'uncertain'        | null                                    | 1       | 0        | 1        | true
    }

    @Unroll
    def 'email tick observes #scenario once and preserves outbox behavior'() {
        given:
        def store = Mock(EmailOutbox)
        def delivery = Mock(EmailDelivery)
        def claim = new EmailOutbox.Claim(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 'PAYMENT_SENT', 125L, UUID.randomUUID(), 9, address, verified)
        int failures = 0
        def worker = new EmailWorker(store, delivery, 'sending', { failures++; if (callbackThrows) throw new IllegalStateException('observer') })
        store.claim('sending') >> { if (scenario == 'claim error') throw new IllegalStateException('store'); Optional.of(claim) }
        delivery.recipient(_) >> { if (scenario == 'recipient error') throw new IllegalStateException('contact'); scenario == 'stale freeze' ? new EmailDelivery.Recipient('synthetic@example.test', true) : null }
        store.freezeRecipient(_, _) >> false
        delivery.send(_, _) >> { if (scenario in ['dependency error', 'nested error']) throw new IllegalStateException('provider'); if (scenario == 'interrupted') throw new InterruptedException(); result }

        when:
        worker.tick()

        then:
        failures == expected
        Thread.currentThread().isInterrupted() == (scenario == 'interrupted')
        retries * store.retry(_, _) >> { if (scenario == 'nested error') throw new IllegalStateException('retry') }
        finishes * store.finish(_, status, _) >> { if (scenario == 'finish error') throw new IllegalStateException('finish'); false }

        cleanup:
        Thread.interrupted()
        worker.close()

        where:
        scenario           | result                                 | address                  | verified | retries | finishes | status    | expected | callbackThrows
        'accepted stale'   | EmailDelivery.Result.ACCEPTED          | 'synthetic@example.test' | true     | 0       | 1        | 'SENT'    | 0        | false
        'rejected'         | EmailDelivery.Result.PERMANENT_FAILURE | 'synthetic@example.test' | true     | 0       | 1        | 'DEAD'    | 1        | false
        'throttled'        | EmailDelivery.Result.RATE_LIMITED      | 'synthetic@example.test' | true     | 1       | 0        | null      | 1        | false
        'transient'        | EmailDelivery.Result.TRANSIENT_FAILURE | 'synthetic@example.test' | true     | 1       | 0        | null      | 1        | false
        'dependency error' | null                                   | 'synthetic@example.test' | true     | 1       | 0        | null      | 1        | false
        'nested error'     | null                                   | 'synthetic@example.test' | true     | 1       | 0        | null      | 1        | false
        'claim error'      | null                                   | 'synthetic@example.test' | true     | 0       | 0        | null      | 1        | false
        'finish error'     | EmailDelivery.Result.ACCEPTED          | 'synthetic@example.test' | true     | 1       | 1        | 'SENT'    | 1        | false
        'interrupted'      | null                                   | 'synthetic@example.test' | true     | 0       | 0        | null      | 0        | false
        'recipient error'  | null                                   | null                     | true     | 1       | 0        | null      | 1        | false
        'missing'          | null                                   | null                     | true     | 0       | 1        | 'SKIPPED' | 0        | false
        'unverified'       | null                                   | 'synthetic@example.test' | false    | 0       | 1        | 'SKIPPED' | 0        | false
        'stale freeze'     | null                                   | null                     | true     | 0       | 0        | null      | 0        | false
        'throttled'        | EmailDelivery.Result.RATE_LIMITED      | 'synthetic@example.test' | true     | 1       | 0        | null      | 1        | true
    }

    def 'empty and closed workers do not observe a failure'() {
        given:
        def payments = Stub(PaymentStore) { claim() >> Optional.empty() }
        def emails = Stub(EmailOutbox) { claim(_) >> Optional.empty() }
        int failures = 0
        def paymentWorker = new PaymentWorker(payments, Stub(PaymentPeers), { failures++ })
        def emailWorker = new EmailWorker(emails, Stub(EmailDelivery), 'sending', { failures++ })
        when:
        paymentWorker.tick()
        emailWorker.tick()
        paymentWorker.close()
        emailWorker.close()
        paymentWorker.tick()
        emailWorker.tick()
        then:
        failures == 0
    }
}
