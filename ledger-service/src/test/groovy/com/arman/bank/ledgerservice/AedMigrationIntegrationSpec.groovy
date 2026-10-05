package com.arman.bank.ledgerservice

import org.flywaydb.core.Flyway
import org.flywaydb.core.api.FlywayException
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.testcontainers.containers.PostgreSQLContainer
import spock.lang.Specification
import spock.lang.Unroll

import java.sql.DriverManager

class AedMigrationIntegrationSpec extends Specification {
    @Unroll
    def "AED migration preserves populated ledger or refuses legacy #currency"() {
        given:
        def pg = new PostgreSQLContainer('postgres:17.6-alpine')
        pg.start()
        Flyway.configure().dataSource(pg.jdbcUrl, pg.username, pg.password).target('2').load().migrate()
        def connection = DriverManager.getConnection(pg.jdbcUrl, pg.username, pg.password)
        def sql = DSL.using(connection, SQLDialect.POSTGRES)
        def debit = UUID.randomUUID()
        def credit = UUID.randomUUID()
        def payment = UUID.randomUUID()
        [debit, credit].each { id ->
            sql.execute("insert into accounts(id,wallet_id,currency,account_kind) values (?,?,?,'CLEARING')", id, UUID.randomUUID(), currency)
        }
        sql.execute('insert into transfers(payment_id,debit_account_id,credit_account_id,currency,amount_minor) values (?,?,?,?,?)',
            payment, debit, credit, currency, 123L)
        sql.execute("insert into transfer_requests(payment_id,requester_id,debit_account_id,credit_account_id,currency,amount_minor,outcome) values (?,?,?,?,?,?,'POSTED')",
            payment, UUID.randomUUID(), debit, credit, currency, 123L)

        when:
        def failure = null
        try { Flyway.configure().dataSource(pg.jdbcUrl, pg.username, pg.password).load().migrate() }
        catch (FlywayException e) { failure = e }

        then:
        if (currency == 'AED') {
            assert failure == null
            assert sql.fetchOne("select count(*) from pg_constraint where conname in ('accounts_aed_only','transfers_aed_only','transfer_requests_aed_only')").get(0, Integer) == 3
        } else {
            assert failure != null
            assert failure.message.contains('AED-only migration refused')
        }
        sql.fetchOne('select currency from transfers where payment_id = ?', payment).get(0, String) == currency
        sql.fetchOne('select balance_minor from accounts where id = ?', debit).get(0, Long) == -123L
        sql.fetchOne('select balance_minor from accounts where id = ?', credit).get(0, Long) == 123L
        sql.fetchOne('select sum(amount_minor) from postings').get(0, BigDecimal) == 0

        cleanup:
        connection?.close()
        pg?.stop()

        where:
        currency << ['AED', 'USD', 'EUR', 'GBP']
    }

    def "AED migration refuses a legacy rejected request even without accounts or journal"() {
        given:
        def pg = new PostgreSQLContainer('postgres:17.6-alpine')
        pg.start()
        Flyway.configure().dataSource(pg.jdbcUrl, pg.username, pg.password).target('2').load().migrate()
        def connection = DriverManager.getConnection(pg.jdbcUrl, pg.username, pg.password)
        def sql = DSL.using(connection, SQLDialect.POSTGRES)
        sql.execute("insert into transfer_requests(payment_id,requester_id,debit_account_id,credit_account_id,currency,amount_minor,outcome) values (?,?,?,?,'USD',1,'INVALID_ACCOUNT')",
            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID())

        when:
        Flyway.configure().dataSource(pg.jdbcUrl, pg.username, pg.password).load().migrate()

        then:
        def failure = thrown(FlywayException)
        failure.message.contains('AED-only migration refused')
        sql.fetchOne('select currency from transfer_requests').get(0, String) == 'USD'

        cleanup:
        connection?.close()
        pg?.stop()
    }
}
