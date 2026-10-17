package com.arman.bank.paymentservice

import com.arman.bank.runtime.*
import com.arman.bank.paymentservice.application.*
import com.arman.bank.paymentservice.domain.*
import com.arman.bank.paymentservice.infrastructure.*
import org.testcontainers.containers.PostgreSQLContainer
import spock.lang.*

/** Disposable synthetic state observations; no ledger posting or financial success is asserted. */
class PaymentMetricsIntegrationSpec extends Specification {
    @Shared PostgreSQLContainer postgres
    @Shared Database db

    def setupSpec() {
        postgres = new PostgreSQLContainer('postgres:17.6-alpine')
        postgres.start()
        db = new Database(postgres.jdbcUrl, postgres.username, postgres.password)
    }
    def cleanupSpec() { db?.close(); postgres?.stop() }
    def setup() { db.transaction { it.execute('truncate email_outbox,notifications,payments') } }

    def pending(PostgresPayments payments) {
        payments.create(UUID.randomUUID(), UUID.randomUUID().toString(),
            new PaymentRequest(UUID.randomUUID(), UUID.randomUUID(), '+971501234567', 'AED', 125L),
            new PaymentPeers.Mapping(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()))
    }

    def 'counts unresolved mapped payments and all pending email including leased and delayed work'() {
        given:
        def payments = new PostgresPayments(db)
        // A durable rejection generates one notification/outbox entry, without any ledger success.
        4.times {
            pending(payments)
            assert payments.finish(payments.claim().get(), PaymentPeers.Outcome.INSUFFICIENT_FUNDS)
        }
        db.transaction { sql ->
            def ids = sql.fetch('select id from email_outbox order by id')*.get('id')
            ['SENT', 'SKIPPED', 'DEAD'].eachWithIndex { status, i ->
                sql.execute('update email_outbox set status=? where id=?', status, ids[i])
            }
            sql.execute("update email_outbox set next_attempt_at=clock_timestamp()+interval '1 hour', lease_token=?, lease_until=clock_timestamp()+interval '1 minute' where status='PENDING'", UUID.randomUUID())
        }
        3.times { pending(payments) }
        db.transaction { sql ->
            sql.execute("update payments set next_attempt_at=clock_timestamp()+interval '1 hour', lease_token=?, lease_until=clock_timestamp()+interval '1 minute' where status='PENDING'", UUID.randomUUID())
            // Legacy unmapped pending commands are not recoverable by the worker.
            sql.execute("insert into payments(id,requester_id,idempotency_key,request_hash,source_wallet_id,destination_wallet_id,currency,amount_minor,status) values (?,?,?,?,?,?,'AED',125,'PENDING')",
                UUID.randomUUID(), UUID.randomUUID(), 'legacy-synthetic', 'a' * 64, UUID.randomUUID(), UUID.randomUUID())
        }
        def metrics = new OperationalMetrics()
        when:
        new PaymentMetrics(db, metrics).run()
        then:
        metrics.render().contains('armoney_recovery_backlog 3\n')
        metrics.render().contains('armoney_outbox_backlog 1\n')
        !metrics.render().contains('failures_total')
        !metrics.render().contains('lag_seconds')
        db.transaction { it.fetchOne("select count(*) from payments where status='PENDING'").get(0, Long) } == 4L
        db.transaction { it.fetchOne("select count(*) from email_outbox where status='PENDING'").get(0, Long) } == 1L
    }

    def 'empty database observes zero but unavailable source removes stale gauges'() {
        given:
        def metrics = new OperationalMetrics()
        def observation = new PaymentMetrics(db, metrics)
        when:
        observation.run()
        then:
        metrics.render().contains('armoney_recovery_backlog 0\n')
        metrics.render().contains('armoney_outbox_backlog 0\n')
        metrics.render().contains('armoney_recovery_oldest_pending_age_seconds 0\n')
        metrics.render().contains('armoney_outbox_oldest_pending_age_seconds 0\n')
        when:
        paymentAt(timestamp('121 seconds'))
        emailAt(timestamp('241 seconds'))
        observation.run()
        assert values(metrics).armoney_recovery_oldest_pending_age_seconds > 0L
        assert values(metrics).armoney_outbox_oldest_pending_age_seconds > 0L
        db.transaction { it.execute('alter table email_outbox rename to unavailable_email_outbox') }
        observation.run()
        then:
        !metrics.render().contains('backlog')
        !metrics.render().contains('oldest_pending_age_seconds')
        cleanup:
        db.transaction { it.execute('alter table unavailable_email_outbox rename to email_outbox') }
    }

    // Insert synthetic timestamps directly: production payment created_at is immutable.
    def timestamp(String interval) {
        db.transaction { it.fetchOne('select (statement_timestamp() - cast(? as interval))::text', interval).get(0, String) }
    }

    UUID paymentAt(String created, boolean mapped = true, String status = 'PENDING') {
        def id = UUID.randomUUID()
        db.transaction { sql ->
            sql.execute("""
                insert into payments(id,requester_id,idempotency_key,request_hash,source_wallet_id,
                  destination_wallet_id,currency,amount_minor,status,recipient_id,recipient_phone,
                  debit_account_id,credit_account_id,rejection_reason,created_at)
                values (?,?,?,?,?,?,'AED',125,?,?,?,?,?,?,cast(? as timestamptz))
                """, id, UUID.randomUUID(), UUID.randomUUID().toString(), 'a' * 64,
                UUID.randomUUID(), UUID.randomUUID(), status, mapped ? UUID.randomUUID() : null,
                mapped ? '+971501234567' : null, mapped ? UUID.randomUUID() : null,
                mapped ? UUID.randomUUID() : null, status == 'REJECTED' ? 'INSUFFICIENT_FUNDS' : null, created)
        }
        id
    }

    UUID emailAt(String created, String status = 'PENDING', int attempts = 0) {
        def payment = paymentAt(timestamp('1 second'), true, 'REJECTED')
        def owner = UUID.randomUUID()
        def id = UUID.randomUUID()
        db.transaction { sql ->
            sql.execute("""
                insert into notifications(id,owner_id,payment_id,type,currency,amount_minor)
                values (?,?,?,'PAYMENT_REJECTED','AED',125)
                """, UUID.randomUUID(), owner, payment)
            sql.execute("""
                insert into email_outbox(id,owner_id,payment_id,type,amount_minor,status,attempts,created_at)
                values (?,?,?,'PAYMENT_REJECTED',125,?,?,cast(? as timestamptz))
                """, id, owner, payment, status, attempts, created)
        }
        id
    }

    def values(OperationalMetrics metrics) {
        metrics.render().readLines().findAll { !it.startsWith('#') }.collectEntries {
            def pair = it.split(' ')
            [(pair[0]): Long.parseLong(pair[1])]
        }
    }

    def snapshot() {
        db.transaction { sql ->
            ['payments', 'email_outbox', 'notifications'].collectEntries { table ->
                [(table): sql.fetch('select row_to_json(r)::text as row from ' + table + ' r order by id')*.get('row')]
            }
        }
    }

    def 'oldest unresolved ages include leased delayed and exhausted rows without changing any durable state'() {
        given:
        paymentAt(timestamp('121 seconds'))
        paymentAt(timestamp('20 seconds'))
        paymentAt('-infinity', false)
        paymentAt('-infinity', true, 'REJECTED')
        // Synthetic terminal-state exclusion only; this fixture asserts no ledger posting.
        paymentAt(timestamp('10 days'), true, 'COMPLETED')
        emailAt(timestamp('241 seconds'), 'PENDING', 8)
        emailAt(timestamp('30 seconds'))
        ['SENT', 'SKIPPED', 'DEAD'].each { emailAt('-infinity', it) }
        db.transaction { sql ->
            sql.execute("update payments set next_attempt_at=statement_timestamp()+interval '1 hour', lease_token=?, lease_until=statement_timestamp()+interval '1 minute', attempts=4 where status='PENDING'", UUID.randomUUID())
            sql.execute("update email_outbox set next_attempt_at=statement_timestamp()+interval '1 hour', lease_token=?, lease_until=statement_timestamp()+interval '1 minute' where status='PENDING'", UUID.randomUUID())
        }
        def before = snapshot()
        def metrics = new OperationalMetrics()
        when:
        new PaymentMetrics(db, metrics).run()
        def observed = values(metrics)
        then:
        observed.armoney_recovery_backlog == 2L
        observed.armoney_outbox_backlog == 2L
        observed.armoney_recovery_oldest_pending_age_seconds in 121L..151L
        observed.armoney_outbox_oldest_pending_age_seconds in 241L..271L
        snapshot() == before
    }

    def 'claim and retry preserve durable age origin despite a new delay and attempts'() {
        given:
        def paymentId = paymentAt(timestamp('121 seconds'))
        def emailId = emailAt(timestamp('241 seconds'))
        def origins = {
            db.transaction { sql ->
                [sql.fetchOne('select created_at::text from payments where id=?', paymentId).get(0, String),
                 sql.fetchOne('select created_at::text from email_outbox where id=?', emailId).get(0, String)]
            }
        }
        def originalCreatedAt = origins()
        def payments = new PostgresPayments(db)
        def outbox = new PostgresEmailOutbox(db)
        def paymentClaim = payments.claim().orElseThrow()
        def emailClaim = outbox.claim('sending').orElseThrow()
        when:
        payments.retry(paymentClaim)
        outbox.retry(emailClaim, 'synthetic_retry')
        def retriedCreatedAt = origins()
        def before = snapshot()
        def metrics = new OperationalMetrics()
        new PaymentMetrics(db, metrics).run()
        def observed = values(metrics)
        then:
        retriedCreatedAt == originalCreatedAt
        observed.armoney_recovery_backlog == 1L
        observed.armoney_outbox_backlog == 1L
        observed.armoney_recovery_oldest_pending_age_seconds in 121L..151L
        observed.armoney_outbox_oldest_pending_age_seconds in 241L..271L
        db.transaction { it.fetchOne("select attempts from payments where status='PENDING'").get(0, Integer) } == 1
        db.transaction { it.fetchOne('select attempts from email_outbox').get(0, Integer) } == 1
        snapshot() == before
    }

    def 'age floors fractional elapsed seconds using the database clock'() {
        given:
        def sharedCreatedAt = timestamp('121.9 seconds')
        def id = paymentAt(sharedCreatedAt)
        emailAt(sharedCreatedAt)
        def metrics = new OperationalMetrics()
        def created = db.transaction { it.fetchOne('select created_at from payments where id=?', id).get(0, java.time.OffsetDateTime) }.toInstant()
        def before = db.transaction { it.fetchOne('select statement_timestamp()').get(0, java.time.OffsetDateTime) }.toInstant()
        when:
        new PaymentMetrics(db, metrics).run()
        def after = db.transaction { it.fetchOne('select statement_timestamp()').get(0, java.time.OffsetDateTime) }.toInstant()
        def seconds = values(metrics).armoney_recovery_oldest_pending_age_seconds
        then:
        seconds == values(metrics).armoney_outbox_oldest_pending_age_seconds
        seconds >= java.time.Duration.between(created, before).seconds
        seconds <= java.time.Duration.between(created, after).seconds
    }

    @Unroll
    def '#queue age becomes unknown for #invalid even with older valid work and stale samples are removed'() {
        given:
        paymentAt(timestamp('121 seconds'))
        emailAt(timestamp('241 seconds'))
        def metrics = new OperationalMetrics()
        def observation = new PaymentMetrics(db, metrics)
        observation.run()
        assert values(metrics).armoney_recovery_oldest_pending_age_seconds != null
        assert values(metrics).armoney_outbox_oldest_pending_age_seconds != null
        def created = invalid == 'future' ? timestamp('-1 hour') : invalid
        if (queue == 'recovery') paymentAt(created)
        else emailAt(created)
        def before = snapshot()
        when:
        observation.run()
        def observed = values(metrics)
        then:
        observed.armoney_recovery_backlog == (queue == 'recovery' ? 2L : 1L)
        observed.armoney_outbox_backlog == (queue == 'outbox' ? 2L : 1L)
        !observed.containsKey('armoney_' + queue + '_oldest_pending_age_seconds')
        observed.containsKey('armoney_' + (queue == 'recovery' ? 'outbox' : 'recovery') + '_oldest_pending_age_seconds')
        snapshot() == before
        where:
        [queue, invalid] << [['recovery', 'outbox'], ['future', 'infinity', '-infinity']].combinations()
    }

    @Unroll
    def '#queue with only future work is unknown rather than an empty zero'() {
        given:
        def created = timestamp('-1 hour')
        if (queue == 'recovery') paymentAt(created)
        else emailAt(created)
        def metrics = new OperationalMetrics()
        when:
        new PaymentMetrics(db, metrics).run()
        def observed = values(metrics)
        then:
        !observed.containsKey('armoney_' + queue + '_oldest_pending_age_seconds')
        observed['armoney_' + queue + '_backlog'] == 1L
        observed['armoney_' + (queue == 'recovery' ? 'outbox' : 'recovery') + '_oldest_pending_age_seconds'] == 0L
        where:
        queue << ['recovery', 'outbox']
    }
}
