package com.arman.bank.authservice.infrastructure

import com.arman.bank.authservice.domain.LegacyMigrationOtp.Purpose
import spock.lang.Specification
import java.time.*

/** Worker scheduling/cancellation tests only; no synthetic possession receipt or provider acceptance. */
class LegacyMigrationDeliveryWorkerSpec extends Specification {
    static class MutableClock extends Clock {
        Instant value
        ZoneId getZone(){ZoneOffset.UTC}
        Clock withZone(ZoneId ignored){this}
        Instant instant(){value}
    }
    def 'pause across effective account or claim lease deadline never invokes provider despite live OTP'(){
        given:
        def initial=Instant.parse('2026-10-18T00:00:00Z')
        def clock=new MutableClock(value:initial)
        def crypto=new LegacyMigrationOtpCrypto('otp',[otp:([83]*32) as byte[]],[otp:([84]*32) as byte[]])
        def delivery=UUID.randomUUID()
        def envelope=new LegacyMigrationOtpCrypto.Envelope(delivery,UUID.randomUUID(),UUID.randomUUID(),Purpose.EMAIL,'a'*64,'b'*64,initial.plusSeconds(300))
        def lease=new PostgresLegacyMigrationOtpStore.DeliveryLease(delivery,UUID.randomUUID(),envelope,crypto.encrypt(envelope,'synthetic@example.test','123456'),initial.plusSeconds(boundSeconds))
        int validated=0
        int sent=0
        def finished=[]
        def claims=[expirePayloads:{},claimDelivery:{Optional.of(lease)},validateDeliveryDeadline:{claim->validated++;clock.value=initial.plusSeconds(boundSeconds);Optional.of(lease.presendDeadline())},finishDelivery:{id,fence,accepted->finished.add(accepted);true}] as LegacyMigrationDeliveryWorker.Claims
        def sender={claim,payload->sent++;true} as LegacyMigrationDeliveryWorker.Sender
        def worker=new LegacyMigrationDeliveryWorker(claims,crypto,sender,clock)
        when:worker.tick()
        then:
        validated==1
        sent==0
        finished==[false]
        cleanup:worker.close()
        where:
        boundSeconds << [120,30]
    }
    def 'current claim rejection or cancellation after validation withholds transport'(){
        given:
        def initial=Instant.parse('2026-10-18T00:00:00Z')
        def clock=Clock.fixed(initial,ZoneOffset.UTC)
        def crypto=new LegacyMigrationOtpCrypto('otp',[otp:([83]*32) as byte[]],[otp:([84]*32) as byte[]])
        def delivery=UUID.randomUUID()
        def envelope=new LegacyMigrationOtpCrypto.Envelope(delivery,UUID.randomUUID(),UUID.randomUUID(),Purpose.PHONE,'a'*64,'b'*64,initial.plusSeconds(300))
        def lease=new PostgresLegacyMigrationOtpStore.DeliveryLease(delivery,UUID.randomUUID(),envelope,crypto.encrypt(envelope,'+971501234567','123456'),initial.plusSeconds(30))
        int sent=0
        def claims=[expirePayloads:{},claimDelivery:{Optional.of(lease)},validateDeliveryDeadline:{claim->if(cancel)Thread.currentThread().interrupt();reject?Optional.empty():Optional.of(lease.presendDeadline())},finishDelivery:{id,fence,accepted->assert !accepted;true}] as LegacyMigrationDeliveryWorker.Claims
        def sender={claim,payload->sent++;true} as LegacyMigrationDeliveryWorker.Sender
        def worker=new LegacyMigrationDeliveryWorker(claims,crypto,sender,clock)
        when:worker.tick()
        then:sent==0
        cleanup:
        Thread.interrupted()
        worker.close()
        where:
        reject | cancel
        true   | false
        false  | true
    }
    def 'pause after fresh observation reaches four second bound with live thirty second claim and account never invokes provider'(){
        given:
        def initial=Instant.parse('2026-10-18T00:00:00Z')
        def clock=new MutableClock(value:initial)
        def crypto=new LegacyMigrationOtpCrypto('otp',[otp:([83]*32) as byte[]],[otp:([84]*32) as byte[]])
        def delivery=UUID.randomUUID()
        def envelope=new LegacyMigrationOtpCrypto.Envelope(delivery,UUID.randomUUID(),UUID.randomUUID(),Purpose.PHONE,'a'*64,'b'*64,initial.plusSeconds(300))
        def lease=new PostgresLegacyMigrationOtpStore.DeliveryLease(delivery,UUID.randomUUID(),envelope,crypto.encrypt(envelope,'+971501234567','123456'),initial.plusSeconds(30))
        int sent=0
        def claims=[expirePayloads:{},claimDelivery:{Optional.of(lease)},validateDeliveryDeadline:{claim->clock.value=initial.plusSeconds(4);Optional.of(initial.plusSeconds(4))},finishDelivery:{id,fence,accepted->assert !accepted;true}] as LegacyMigrationDeliveryWorker.Claims
        def sender={claim,payload->sent++;true} as LegacyMigrationDeliveryWorker.Sender
        def worker=new LegacyMigrationDeliveryWorker(claims,crypto,sender,clock)
        when:worker.tick()
        then:
        sent==0
        clock.instant().isBefore(lease.presendDeadline())
        clock.instant().isBefore(initial.plusSeconds(120))
        clock.instant().isBefore(envelope.expiresAt())
        cleanup:worker.close()
    }
}
