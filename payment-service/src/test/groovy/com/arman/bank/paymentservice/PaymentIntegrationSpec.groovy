package com.arman.bank.paymentservice

import com.arman.bank.runtime.Database
import com.arman.bank.paymentservice.application.*
import com.arman.bank.paymentservice.domain.*
import com.arman.bank.paymentservice.infrastructure.PostgresPayments
import org.testcontainers.containers.PostgreSQLContainer
import spock.lang.*
import java.util.concurrent.*

/** Real PostgreSQL proves orchestration persistence, not ledger money movement (covered in ledger/Compose). */
class PaymentIntegrationSpec extends Specification {
    @Shared PostgreSQLContainer postgres
    @Shared Database db
    PostgresPayments store
    UUID alice = UUID.randomUUID()
    UUID bob = UUID.randomUUID()
    PaymentRequest request = new PaymentRequest(UUID.randomUUID(), bob, '+971501234567', 'AED', 125L)
    PaymentPeers.Mapping mapping = new PaymentPeers.Mapping(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID())

    def setupSpec() {
        postgres = new PostgreSQLContainer('postgres:17.6-alpine')
        postgres.start()
        db = new Database(postgres.jdbcUrl, postgres.username, postgres.password)
    }
    def cleanupSpec() { db?.close(); postgres?.stop() }
    def setup() {
        db.transaction { it.execute('truncate email_outbox, notifications, payments') }
        store = new PostgresPayments(db)
    }
    def expire(PaymentStore.Claim claim) {
        db.transaction { it.execute("update payments set lease_until = clock_timestamp() - interval '1 second' where id = ?", claim.payment().id()) }
    }

    def 'concurrent same requester and key reserve one immutable payment mapping'() {
        given:
        def pool = Executors.newFixedThreadPool(8)
        def start = new CountDownLatch(1)
        when:
        def calls = (1..8).collect {
            pool.submit({ start.await(); store.create(alice, 'same-key', request, mapping) } as Callable<Payment>)
        }
        start.countDown()
        def results = calls.collect { it.get(10, TimeUnit.SECONDS) }
        then:
        results*.id().toSet().size() == 1
        db.transaction { it.fetchOne('select count(*) from payments').get(0, Integer) } == 1
        store.create(alice, 'same-key', request, new PaymentPeers.Mapping(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID())).destinationWalletId() == mapping.destinationWalletId()
        when:
        store.create(alice, 'same-key', new PaymentRequest(request.sourceWalletId(), bob, request.recipientPhone(), 'AED', 126L), mapping)
        then:
        def failure = thrown(PaymentFailure)
        failure.status() == 409
        failure.code() == 'idempotency_conflict'
        when:
        def other = store.create(UUID.randomUUID(), 'same-key', request, mapping)
        then:
        other.id() != results.first().id()
        cleanup:
        pool.shutdownNow()
        pool.awaitTermination(5, TimeUnit.SECONDS)
    }

    def 'durable replay is available with peers down and changed confirmed recipient conflicts before network'() {
        given:
        def peers = Mock(PaymentPeers)
        def service = new PaymentService(store, peers)
        def first = store.create(alice, 'replay', request, mapping)
        expect:
        service.submit(alice, 'replay', request).id() == first.id()
        when:
        service.submit(alice, 'replay', new PaymentRequest(request.sourceWalletId(), UUID.randomUUID(), request.recipientPhone(), 'AED', 125L))
        then:
        thrown(PaymentFailure)
        0 * peers._
    }

    def 'concurrent durable claims exclude each other and stale worker cannot terminate reclaimed payment'() {
        given:
        def payment = store.create(alice, 'claim', request, mapping)
        def pool = Executors.newFixedThreadPool(4)
        when:
        def claims = (1..4).collect { pool.submit({ store.claim() } as Callable) }.collect { it.get(10, TimeUnit.SECONDS) }.findAll { it.present }
        then:
        claims.size() == 1
        when:
        def old = claims.first().get()
        expire(old)
        def fresh = new PostgresPayments(db).claim().get()
        then:
        fresh.payment().id() == payment.id()
        fresh.token() != old.token()
        !store.finish(old, PaymentPeers.Outcome.POSTED)
        store.notifications(alice).empty
        store.finish(fresh, PaymentPeers.Outcome.POSTED)
        !store.finish(fresh, PaymentPeers.Outcome.POSTED)
        store.notifications(alice)*.type() == ['PAYMENT_COMPLETED']
        store.notifications(bob)*.type() == ['PAYMENT_RECEIVED']
        cleanup:
        pool.shutdownNow()
        pool.awaitTermination(5, TimeUnit.SECONDS)
    }

    def 'terminal payment and notifications roll back together on notification insertion failure'() {
        given:
        def payment = store.create(alice, 'atomic', request, mapping)
        def claim = store.claim().get()
        db.transaction {
            it.execute("CREATE FUNCTION fail_notification() RETURNS trigger LANGUAGE plpgsql AS 'BEGIN RAISE EXCEPTION ''test notification failure''; END'")
            it.execute('CREATE TRIGGER reject_notification BEFORE INSERT ON notifications FOR EACH ROW EXECUTE FUNCTION fail_notification()')
        }
        when:
        store.finish(claim, PaymentPeers.Outcome.POSTED)
        then:
        thrown(org.jooq.exception.DataAccessException)
        store.visible(alice, payment.id()).get().status() == 'PENDING'
        store.notifications(alice).empty
        store.notifications(bob).empty
        when:
        db.transaction { it.execute('DROP TRIGGER reject_notification ON notifications'); it.execute('DROP FUNCTION fail_notification()') }
        then:
        store.finish(claim, PaymentPeers.Outcome.POSTED)
        store.notifications(alice).size() == 1
        store.notifications(bob).size() == 1
        cleanup:
        db.transaction { it.execute('DROP TRIGGER IF EXISTS reject_notification ON notifications'); it.execute('DROP FUNCTION IF EXISTS fail_notification()') }
    }

    def 'uncertain ledger response remains pending and restart retries the original command before notifications'() {
        given:
        def payment = store.create(alice, 'uncertain', request, mapping)
        def firstPeers = Stub(PaymentPeers) { post(_) >> { throw new IOException('response lost after remote commit') } }
        def firstWorker = new PaymentWorker(store, firstPeers)
        when:
        firstWorker.tick()
        firstWorker.close()
        then:
        store.visible(alice, payment.id()).get().status() == 'PENDING'
        store.notifications(alice).empty
        when:
        db.transaction { it.execute("update payments set next_attempt_at = clock_timestamp() - interval '1 second' where id = ?", payment.id()) }
        def observed = new java.util.concurrent.atomic.AtomicReference<Payment>()
        def peers = Stub(PaymentPeers) { post(_) >> { Payment p -> observed.set(p); PaymentPeers.Outcome.POSTED } }
        def restarted = new PaymentWorker(new PostgresPayments(db), peers)
        restarted.tick()
        then:
        observed.get().id() == payment.id()
        observed.get().debitAccountId() == mapping.debitAccountId()
        observed.get().request() == request
        store.visible(alice, payment.id()).get().status() == 'COMPLETED'
        store.notifications(alice).size() == 1
        store.notifications(bob).size() == 1
        cleanup:
        restarted?.close()
        firstWorker?.close()
    }

    def 'rejection notifies sender only and notification read is owner scoped and idempotent'() {
        given:
        def p = store.create(alice, 'rejected', request, mapping)
        when:
        store.finish(store.claim().get(), PaymentPeers.Outcome.INSUFFICIENT_FUNDS)
        then:
        store.visible(alice, p.id()).get().rejectionReason() == 'INSUFFICIENT_FUNDS'
        store.visible(bob, p.id()).empty
        store.history(bob).empty
        store.notifications(bob).empty
        when:
        def notification = store.notifications(alice).first()
        def firstRead = store.readNotification(alice, notification.id()).get()
        then:
        firstRead.type() == 'PAYMENT_REJECTED'
        firstRead.readAt() != null
        store.readNotification(alice, notification.id()).get().readAt() == firstRead.readAt()
        store.readNotification(bob, notification.id()).empty
        when:
        db.transaction { it.execute("update payments set status = 'COMPLETED', rejection_reason = null where id = ?", p.id()) }
        then:
        thrown(org.jooq.exception.DataAccessException)
    }

    def 'legacy unresolved scaffold rows remain invisible unclaimable and cannot be remapped'() {
        given:
        def id = UUID.randomUUID()
        db.transaction { it.execute("""
            insert into payments(id,requester_id,idempotency_key,request_hash,source_wallet_id,destination_wallet_id,currency,amount_minor,status)
            values (?,?,?,?,?,?,?,?,'PENDING')
            """, id, alice, 'legacy', request.hash(), request.sourceWalletId(), mapping.destinationWalletId(), 'AED', 125L) }
        expect:
        store.claim().empty
        store.history(alice).empty
        store.visible(alice, id).empty
        when:
        store.findKey(alice, 'legacy', request)
        then:
        def error = thrown(PaymentFailure)
        error.status() == 409
        when:
        db.transaction { it.execute('update payments set recipient_id = ?, recipient_phone = ?, debit_account_id = ?, credit_account_id = ? where id = ?',
                bob, request.recipientPhone(), mapping.debitAccountId(), mapping.creditAccountId(), id) }
        then:
        thrown(org.jooq.exception.DataAccessException)
    }

    def 'history and notifications are bounded to the newest hundred visible records'() {
        given:
        (1..102).each {
            store.create(alice, 'history-' + it, request, mapping)
            assert store.finish(store.claim().get(), PaymentPeers.Outcome.POSTED)
        }
        expect:
        store.history(alice).size() == 100
        store.history(bob).size() == 100
        store.history(UUID.randomUUID()).empty
        store.notifications(alice).size() == 100
        store.notifications(bob).size() == 100
        store.history(alice).first().createdAt() >= store.history(alice).last().createdAt()
    }

    def 'new non-UAE transfers fail before peer resolution but historical commands remain replayable'() {
        given:
        def old = new PaymentRequest(request.sourceWalletId(), bob, '+15551234567', 'AED', 125L)
        def accepted = store.create(alice, 'historical', old, mapping)
        def peers = Mock(PaymentPeers)
        def service = new PaymentService(store, peers)
        expect:
        service.submit(alice, 'historical', old).id() == accepted.id()
        service.payment(alice, accepted.id()).request().recipientPhone() == old.recipientPhone()
        when:
        service.submit(alice, 'new-foreign', old)
        then:
        def failure = thrown(PaymentFailure)
        failure.status() == 400
        0 * peers._
        store.history(alice).size() == 1
    }

    def 'new transfer rejects unsupported UAE mobile prefix #phone'() {
        given:
        def peers = Mock(PaymentPeers)
        def service = new PaymentService(store, peers)
        when:
        service.submit(alice, 'invalid-phone', new PaymentRequest(request.sourceWalletId(), bob, phone, 'AED', 125L))
        then:
        def failure = thrown(PaymentFailure)
        failure.status() == 400
        0 * peers._
        store.history(alice).empty
        where:
        phone << ['+971570000001', '+971510000001', '+97141234567', '+9715012345678']
    }

    def 'database rejects non-AED payment and notification currency: #currency'() {
        given:
        def payment = store.create(alice, 'valid-aed', request, mapping)
        when:
        db.transaction { it.execute("""
            insert into payments(id,requester_id,idempotency_key,request_hash,source_wallet_id,destination_wallet_id,currency,amount_minor,status)
            values (?,?,?,?,?,?,?,?,'PENDING')
            """, UUID.randomUUID(), alice, 'unsupported', request.hash(), request.sourceWalletId(), mapping.destinationWalletId(), currency, 125L) }
        then:
        thrown(org.jooq.exception.DataAccessException)
        when:
        db.transaction { it.execute("""
            insert into notifications(id,owner_id,payment_id,type,currency,amount_minor)
            values (?,?,?,'PAYMENT_COMPLETED',?,?)
            """, UUID.randomUUID(), alice, payment.id(), currency, 125L) }
        then:
        thrown(org.jooq.exception.DataAccessException)
        db.transaction { it.fetchOne('select count(*) from payments').get(0, Integer) } == 1
        store.notifications(alice).empty
        where:
        currency << ['USD', 'EUR', 'GBP']
    }
}
