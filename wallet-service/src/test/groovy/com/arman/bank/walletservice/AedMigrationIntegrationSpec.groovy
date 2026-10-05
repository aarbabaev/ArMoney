package com.arman.bank.walletservice

import org.flywaydb.core.Flyway
import org.flywaydb.core.api.FlywayException
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.jooq.exception.DataAccessException
import org.testcontainers.containers.PostgreSQLContainer
import spock.lang.Specification

class AedMigrationIntegrationSpec extends Specification {
    PostgreSQLContainer postgres
    java.sql.Connection connection
    org.jooq.DSLContext sql

    def setup() {
        postgres = new PostgreSQLContainer('postgres:17.6-alpine')
        postgres.start()
        connection = java.sql.DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password)
        sql = DSL.using(connection, SQLDialect.POSTGRES)
    }

    def cleanup() { connection?.close(); postgres?.stop() }

    def migrate(String target = null) {
        def config = Flyway.configure().dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
            .locations('classpath:db/migration')
        if (target != null) config.target(target)
        config.load().migrate()
    }

    def "fresh schema allows AED and rejects other currencies in SQL"() {
        given:
        migrate()
        sql.execute("insert into wallets(id, owner_id, currency, status) values (?, ?, 'AED', 'ACTIVE')", UUID.randomUUID(), UUID.randomUUID())
        when:
        sql.execute("insert into wallets(id, owner_id, currency, status) values (?, ?, ?, 'ACTIVE')", UUID.randomUUID(), UUID.randomUUID(), currency)
        then:
        thrown(DataAccessException)
        sql.fetchCount(DSL.table('wallets')) == 1
        where:
        currency << ['USD', 'EUR', 'GBP', 'aed', 'XYZ']
    }

    def "populated AED schema retains lifecycle identity and mapping through migration and restart"() {
        given:
        migrate('3')
        // Historical V2 excludes AED. This isolated synthetic fixture represents a
        // pre-cutover AED schema; published migrations themselves remain unchanged.
        sql.execute('alter table wallets drop constraint wallet_supported_currency')
        def active = UUID.randomUUID()
        def closed = UUID.randomUUID()
        def owner = UUID.randomUUID()
        def closedOwner = UUID.randomUUID()
        def account = UUID.randomUUID()
        sql.execute("insert into wallets(id, owner_id, currency, status, provisioning_status, ledger_account_id) values (?, ?, 'AED', 'ACTIVE', 'READY', ?)", active, owner, account)
        sql.execute("insert into wallets(id, owner_id, currency, status) values (?, ?, 'AED', 'CLOSED')", closed, closedOwner)
        sql.execute("alter table wallets add constraint wallet_supported_currency check (currency = 'AED')")
        def before = sql.fetch('select * from wallets order by id')
        when:
        migrate()
        migrate()
        then:
        sql.fetch('select * from wallets order by id') == before
        sql.fetchOne('select ledger_account_id from wallets where id = ?', active).get(0, UUID) == account
        sql.fetchOne('select status from wallets where id = ?', closed).get(0, String) == 'CLOSED'
    }

    def "non AED historical wallet refuses migration without rewriting data"() {
        given:
        migrate('3')
        def id = UUID.randomUUID()
        sql.execute('insert into wallets(id, owner_id, currency, status) values (?, ?, ?, ?)', id, UUID.randomUUID(), currency, status)
        def before = sql.fetch('select * from wallets')
        when:
        migrate()
        then:
        def error = thrown(FlywayException)
        error.message.contains('AED-only migration refused')
        sql.fetch('select * from wallets') == before
        sql.fetchOne('select count(*) from flyway_schema_history where version = ?', '4').get(0, Integer) == 0
        where:
        currency | status
        'USD'    | 'ACTIVE'
        'EUR'    | 'CLOSED'
        'GBP'    | 'ACTIVE'
    }
}
