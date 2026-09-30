package com.arman.bank.ledgerservice

import com.arman.bank.runtime.Database
import org.testcontainers.containers.PostgreSQLContainer
import spock.lang.Specification
import java.sql.DriverManager
import java.sql.SQLException

class LedgerIntegrationSpec extends Specification {
    def "PostgreSQL enforces paired postings currency and immutable transfer identity"() {
        given:
        def postgres = new PostgreSQLContainer('postgres:17.6-alpine')
        postgres.start()
        def database = new Database(postgres.jdbcUrl, postgres.username, postgres.password)
        def connection = DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password)
        def a = UUID.randomUUID()
        def b = UUID.randomUUID()
        def c = UUID.randomUUID()
        def payment = UUID.randomUUID()
        [a, b, c].each { id ->
            def statement = connection.prepareStatement('insert into accounts values (?, ?, ?)')
            try {
                statement.setObject(1, id)
                statement.setObject(2, UUID.randomUUID())
                statement.setString(3, id == c ? 'USD' : 'EUR')
                statement.executeUpdate()
            } finally { statement.close() }
        }
        def insert = { UUID id, UUID debit, UUID credit, long amount ->
            def statement = connection.prepareStatement('insert into transfers (payment_id, debit_account_id, credit_account_id, currency, amount_minor) values (?, ?, ?, ?, ?)')
            try {
                statement.setObject(1, id)
                statement.setObject(2, debit)
                statement.setObject(3, credit)
                statement.setString(4, 'EUR')
                statement.setLong(5, amount)
                statement.executeUpdate()
            } finally { statement.close() }
        }

        when:
        insert(payment, a, b, 123L)
        def query = connection.createStatement()
        def result = query.executeQuery('select count(*), sum(amount_minor) from postings')
        result.next()

        then:
        result.getInt(1) == 2
        result.getBigDecimal(2) == 0

        when:
        insert(payment, a, b, 123L)
        then:
        thrown(SQLException)

        when:
        insert(UUID.randomUUID(), a, c, 123L)
        then:
        thrown(SQLException)

        when:
        insert(UUID.randomUUID(), a, a, 123L)
        then:
        thrown(SQLException)

        when:
        insert(UUID.randomUUID(), a, b, 0L)
        then:
        thrown(SQLException)

        when:
        query.executeUpdate('update transfers set amount_minor = 99')
        then:
        thrown(SQLException)

        when:
        query.executeUpdate('delete from transfers')
        then:
        thrown(SQLException)

        cleanup:
        result?.close()
        query?.close()
        connection?.close()
        database?.close()
        postgres?.stop()
    }
}
