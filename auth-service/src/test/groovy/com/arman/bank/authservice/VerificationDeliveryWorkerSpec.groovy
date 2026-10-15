package com.arman.bank.authservice.infrastructure

import com.arman.bank.authservice.domain.Verification.Purpose
import com.arman.bank.authservice.infrastructure.PostgresVerificationStore.DeliveryLease
import java.time.*
import spock.lang.Specification

/** Synthetic worker ordering; does not prove PostgreSQL fencing or receipt persistence. */
class VerificationDeliveryWorkerSpec extends Specification {
    static final Instant NOW = Instant.parse('2026-10-15T12:00:00Z')
    def crypto = new VerificationCrypto('test', ['test': ([11] * 32) as byte[]], ['test': ([22] * 32) as byte[]])
    def outbox = Mock(VerificationDeliveryWorker.Outbox)
    def sender = Mock(VerificationDeliveryWorker.Sender)
    def worker = new VerificationDeliveryWorker(outbox, crypto, sender, Clock.fixed(NOW, ZoneOffset.UTC))
    def cleanup() { worker.close(); Thread.interrupted() }
    DeliveryLease lease(Instant expiry = NOW.plusSeconds(60), boolean corrupt = false) {
        UUID enrollment = UUID.randomUUID(), challenge = UUID.randomUUID()
        def encrypted = crypto.encrypt(enrollment, challenge, Purpose.REGISTRATION_PHONE, 1, '+971501234567', '123456')
        if (corrupt) encrypted = new VerificationCrypto.Encrypted('test', new byte[12], new byte[20])
        new DeliveryLease(UUID.randomUUID(), UUID.randomUUID(), enrollment, challenge, Purpose.REGISTRATION_PHONE, 1, expiry, encrypted)
    }
    def 'one claimed intent is decrypted after claim and accepted only after concrete sender acknowledgement'() {
        given:
        def delivery = lease()
        when:
        worker.tick()
        then:
        1 * outbox.claim() >> Optional.of(delivery)
        then:
        1 * sender.send(delivery, { it.destination() == '+971501234567' && it.code() == '123456' }) >> true
        then:
        1 * outbox.finish(delivery.deliveryId(), delivery.fence(), true) >> true
        0 * outbox.claim()
    }
    def 'nonaccepted or uncertain send uses fenced failure and never reports acceptance'() {
        given:
        def delivery = lease()
        when:
        worker.tick()
        then:
        1 * outbox.claim() >> Optional.of(delivery)
        1 * sender.send(delivery, _) >> { if (uncertain) throw new IllegalStateException('synthetic'); false }
        1 * outbox.finish(delivery.deliveryId(), delivery.fence(), false) >> true
        where:
        uncertain << [false, true]
    }
    def 'expired or corrupt payload is never sent'() {
        given:
        def delivery = lease(expired ? NOW : NOW.plusSeconds(60), !expired)
        when:
        worker.tick()
        then:
        1 * outbox.claim() >> Optional.of(delivery)
        0 * sender.send(_, _)
        1 * outbox.finish(delivery.deliveryId(), delivery.fence(), false) >> true
        where:
        expired << [true, false]
    }
    def 'interruption retains uncertain lease without acknowledgement and preserves interruption'() {
        given:
        def delivery = lease()
        when:
        worker.tick()
        then:
        1 * outbox.claim() >> Optional.of(delivery)
        1 * sender.send(delivery, _) >> { throw new InterruptedException('synthetic') }
        0 * outbox.finish(_, _, _)
        thrown(InterruptedException)
        Thread.currentThread().isInterrupted()
    }
    def 'empty claim does not invoke a transport; closed worker cannot claim'() {
        when:
        worker.tick()
        worker.close()
        worker.tick()
        then:
        1 * outbox.claim() >> Optional.empty()
        0 * sender.send(_, _)
        1 * sender.close()
    }
}
