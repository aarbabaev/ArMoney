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
        when:
        db.transaction { it.execute('alter table email_outbox rename to unavailable_email_outbox') }
        observation.run()
        then:
        !metrics.render().contains('backlog')
        cleanup:
        db.transaction { it.execute('alter table unavailable_email_outbox rename to email_outbox') }
    }
}
