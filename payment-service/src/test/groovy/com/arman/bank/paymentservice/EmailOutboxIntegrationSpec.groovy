package com.arman.bank.paymentservice

import com.arman.bank.runtime.Database
import com.arman.bank.paymentservice.application.*
import com.arman.bank.paymentservice.domain.*
import com.arman.bank.paymentservice.infrastructure.*
import org.testcontainers.containers.PostgreSQLContainer
import spock.lang.*
import java.util.concurrent.*

/** Synthetic real PostgreSQL outbox evidence, not proof of ledger money movement. */
class EmailOutboxIntegrationSpec extends Specification {
    @Shared PostgreSQLContainer postgres
    @Shared Database db
    PostgresPayments payments
    PostgresEmailOutbox outbox
    UUID alice=UUID.randomUUID()
    UUID bob=UUID.randomUUID()
    def setupSpec() {
        postgres=new PostgreSQLContainer('postgres:17.6-alpine'); postgres.start()
        db=new Database(postgres.jdbcUrl,postgres.username,postgres.password)
    }
    def cleanupSpec() { db?.close(); postgres?.stop() }
    def setup() {
        db.transaction { it.execute('truncate email_outbox,notifications,payments') }
        payments=new PostgresPayments(db); outbox=new PostgresEmailOutbox(db)
    }
    def pending() {
        payments.create(alice,UUID.randomUUID().toString(),
            new PaymentRequest(UUID.randomUUID(),bob,'+971501234567','AED',125L),
            new PaymentPeers.Mapping(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID()))
    }
    def terminal(outcome=PaymentPeers.Outcome.INSUFFICIENT_FUNDS) {
        def p=pending(); assert payments.finish(payments.claim().get(),outcome); p
    }
    def rows() { db.transaction { it.fetch('select * from email_outbox order by created_at,id') } }
    def due() { db.transaction { it.execute("update email_outbox set next_attempt_at=clock_timestamp()-interval '1 second' where status='PENDING'") } }
    def expire(c) { db.transaction { it.execute("update email_outbox set lease_until=clock_timestamp()-interval '1 second' where id=?",c.id()) } }

    def 'pending creates no email and terminal replay emits one event per notification'() {
        given:
        def p=pending()
        expect:
        rows().empty
        when:
        def c=payments.claim().get()
        assert payments.finish(c,PaymentPeers.Outcome.POSTED)
        then:
        !payments.finish(c,PaymentPeers.Outcome.POSTED)
        rows()*.get('type').toSet()==['PAYMENT_COMPLETED','PAYMENT_RECEIVED'].toSet()
        rows()*.get('status').toSet()==['PENDING'].toSet()
        rows()*.get('attempts').toSet()==[0].toSet()
        rows()*.get('owner_id').toSet()==[alice,bob].toSet()
        payments.visible(alice,p.id()).get().status()=='COMPLETED'
    }

    def 'outbox insertion failure rolls back terminal payment and in app notifications together'() {
        given:
        def p=pending()
        def c=payments.claim().get()
        db.transaction {
            it.execute("CREATE FUNCTION fail_email() RETURNS trigger LANGUAGE plpgsql AS 'BEGIN RAISE EXCEPTION ''synthetic failure''; END'")
            it.execute('CREATE TRIGGER reject_email BEFORE INSERT ON email_outbox FOR EACH ROW EXECUTE FUNCTION fail_email()')
        }
        when:
        payments.finish(c,PaymentPeers.Outcome.POSTED)
        then:
        thrown(org.jooq.exception.DataAccessException)
        payments.visible(alice,p.id()).get().status()=='PENDING'
        payments.notifications(alice).empty
        payments.notifications(bob).empty
        rows().empty
        cleanup:
        db.transaction { it.execute('DROP TRIGGER IF EXISTS reject_email ON email_outbox'); it.execute('DROP FUNCTION IF EXISTS fail_email()') }
    }

    def 'concurrent claims exclude each other and restart fences stale updates and recipient changes'() {
        given:
        terminal()
        def pool=Executors.newFixedThreadPool(4)
        when:
        def claims=(1..4).collect { pool.submit({ outbox.claim('sandbox') } as Callable) }.collect { it.get(10,TimeUnit.SECONDS) }.findAll { it.present }
        then:
        claims.size()==1
        when:
        def old=claims.first().get()
        assert outbox.freezeRecipient(old,new EmailDelivery.Recipient('first@example.test',false))
        expire(old)
        def fresh=new PostgresEmailOutbox(db).claim('sandbox').get()
        then:
        fresh.token()!=old.token()
        fresh.recipientEmail()=='first@example.test'
        fresh.attempts()==2
        !outbox.finish(old,'SENT',null)
        !outbox.freezeRecipient(old,new EmailDelivery.Recipient('other@example.test',true))
        when:
        outbox.retry(old,'stale')
        then:
        rows().first().get('lease_token')==fresh.token()
        when:
        db.transaction { it.execute('update email_outbox set recipient_email=? where id=?','other@example.test',fresh.id()) }
        then:
        thrown(org.jooq.exception.DataAccessException)
        when:
        outbox.retry(fresh,'provider_unavailable')
        assert outbox.claim('sandbox').empty
        due()
        then:
        outbox.claim('sending').empty
        outbox.claim('sandbox').present
        cleanup:
        pool.shutdownNow(); pool.awaitTermination(5,TimeUnit.SECONDS)
    }

    def 'provider failure is durable but cannot roll back terminal financial state'() {
        given:
        def p=terminal(PaymentPeers.Outcome.POSTED)
        def seen=[]
        def restartDelivery=Mock(EmailDelivery)
        def delivery=Stub(EmailDelivery) {
            recipient(_) >> new EmailDelivery.Recipient('owner@example.test',false)
            send(_,_) >> { c, address -> seen << address; EmailDelivery.Result.TRANSIENT_FAILURE }
        }
        def worker=new EmailWorker(outbox,delivery,'sandbox')
        when:
        worker.tick(); worker.tick()
        then:
        rows().every { it.get('status')=='PENDING' && it.get('attempts')==1 && it.get('error_code')=='provider_unavailable' }
        payments.visible(alice,p.id()).get().status()=='COMPLETED'
        payments.notifications(alice).size()==1
        payments.notifications(bob).size()==1
        when:
        worker.close(); due()
        def restarted=new EmailWorker(new PostgresEmailOutbox(db),restartDelivery,'sandbox')
        restarted.tick()
        then:
        0 * restartDelivery.recipient(_)
        1 * restartDelivery.send(_, 'owner@example.test') >> EmailDelivery.Result.ACCEPTED
        rows().count { it.get('status')=='SENT' }==1
        cleanup:
        worker?.close(); restarted?.close()
    }

    def 'missing and unverified addresses are distinct skips in sending mode'() {
        given:
        terminal()
        def delivery=Mock(EmailDelivery)
        def worker=new EmailWorker(outbox,delivery,'sending')
        when:
        worker.tick()
        then:
        1 * delivery.recipient(alice) >> new EmailDelivery.Recipient(address,false)
        0 * delivery.send(_,_)
        rows().first().get('status')=='SKIPPED'
        rows().first().get('error_code')==code
        cleanup:
        worker?.close()
        where:
        address | code
        null | 'missing_email'
        'owner@example.test' | 'unverified_email'
    }

    def 'bounded attempts dead letter even after final attempt crashes'() {
        given:
        terminal()
        when:
        (1..8).each { n ->
            def c=outbox.claim('sandbox').get()
            assert c.attempts()==n
            expire(c)
        }
        then:
        outbox.claim('sandbox').empty
        rows().first().get('status')=='DEAD'
        rows().first().get('error_code')=='attempts_exhausted'
    }

    def 'worker persists permanent rate limit and timeout classifications'() {
        given:
        terminal()
        def delivery=Stub(EmailDelivery) {
            recipient(_) >> new EmailDelivery.Recipient('owner@example.test',true)
            send(_,_) >> { if (timeout) throw new java.net.http.HttpTimeoutException('synthetic'); result }
        }
        def worker=new EmailWorker(outbox,delivery,'sending')
        when:
        worker.tick()
        then:
        rows().first().get('status')==status
        rows().first().get('error_code')==code
        cleanup:
        worker?.close()
        where:
        result | timeout | status | code
        EmailDelivery.Result.PERMANENT_FAILURE | false | 'DEAD' | 'provider_rejected'
        EmailDelivery.Result.RATE_LIMITED | false | 'PENDING' | 'provider_rate_limited'
        EmailDelivery.Result.TRANSIENT_FAILURE | true | 'PENDING' | 'dependency_unavailable'
    }
}
