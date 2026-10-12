package com.arman.bank.paymentservice

import com.arman.bank.runtime.Database
import com.arman.bank.paymentservice.infrastructure.PostgresPayments
import org.testcontainers.containers.PostgreSQLContainer
import spock.lang.Specification
import java.sql.DriverManager

class PaymentMigrationIntegrationSpec extends Specification {
    def 'populated V1 upgrades without inventing legacy mappings and validates on restart'() {
        given:
        def postgres = new PostgreSQLContainer('postgres:17.6-alpine')
        postgres.start()
        // Flyway is a runtime dependency: reflection keeps the service independent of its API.
        def flyway = Class.forName('org.flywaydb.core.Flyway').getMethod('configure').invoke(null)
        flyway.dataSource(postgres.jdbcUrl, postgres.username, postgres.password).locations('classpath:db/migration').target('1').load().migrate()
        def owner = UUID.randomUUID()
        def legacy = UUID.randomUUID()
        def connection = DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password)
        def insert = connection.prepareStatement("insert into payments(id,requester_id,idempotency_key,request_hash,source_wallet_id,destination_wallet_id,currency,amount_minor,status) values (?,?,?,?,?,?,?,?,'PENDING')")
        [legacy, owner, 'legacy', '0' * 64, UUID.randomUUID(), UUID.randomUUID(), 'AED', 125L].eachWithIndex { value, index -> insert.setObject(index + 1, value) }
        insert.executeUpdate()
        insert.close()
        connection.close()
        when:
        def db = new Database(postgres.jdbcUrl, postgres.username, postgres.password)
        def store = new PostgresPayments(db)
        then:
        db.transaction { it.fetchOne('select count(*) from payments where id = ? and recipient_id is null and debit_account_id is null and credit_account_id is null', legacy).get(0, Integer) } == 1
        store.claim().empty
        store.history(owner).empty
        store.notifications(owner).empty
        when:
        db.close()
        db = new Database(postgres.jdbcUrl, postgres.username, postgres.password)
        then:
        db.ready()
        new PostgresPayments(db).claim().empty
        cleanup:
        connection?.close()
        db?.close()
        postgres?.stop()
    }

    def 'outbox migration preserves historical notifications without backfill'() {
        given:
        def postgres = new PostgreSQLContainer('postgres:17.6-alpine')
        postgres.start()
        def flyway = Class.forName('org.flywaydb.core.Flyway').getMethod('configure').invoke(null)
        flyway.dataSource(postgres.jdbcUrl, postgres.username, postgres.password).locations('classpath:db/migration').target('3').load().migrate()
        def owner=UUID.randomUUID()
        def payment=UUID.randomUUID()
        def connection=DriverManager.getConnection(postgres.jdbcUrl,postgres.username,postgres.password)
        connection.prepareStatement("insert into payments(id,requester_id,idempotency_key,request_hash,source_wallet_id,destination_wallet_id,currency,amount_minor,status) values (?,?,?,?,?,?,'AED',125,'COMPLETED')").withCloseable { insert ->
            [payment,owner,'historical','0'*64,UUID.randomUUID(),UUID.randomUUID()].eachWithIndex { value,index -> insert.setObject(index+1,value) }
            insert.executeUpdate()
        }
        connection.prepareStatement("insert into notifications(id,owner_id,payment_id,type,currency,amount_minor) values (?,?,?,'PAYMENT_COMPLETED','AED',125)").withCloseable { insert ->
            [UUID.randomUUID(),owner,payment].eachWithIndex { value,index -> insert.setObject(index+1,value) }
            insert.executeUpdate()
        }
        when:
        def db=new Database(postgres.jdbcUrl,postgres.username,postgres.password)
        then:
        db.transaction { it.fetchOne('select count(*) from email_outbox').get(0,Integer) } == 0
        new PostgresPayments(db).notifications(owner).size()==1
        cleanup:
        connection?.close(); db?.close(); postgres?.stop()
    }

    def 'AED migration refuses existing non-AED #source without modifying monetary data'() {
        given:
        def postgres = new PostgreSQLContainer('postgres:17.6-alpine')
        postgres.start()
        def flyway = Class.forName('org.flywaydb.core.Flyway').getMethod('configure').invoke(null)
        flyway.dataSource(postgres.jdbcUrl, postgres.username, postgres.password).locations('classpath:db/migration').target('2').load().migrate()
        def payment = UUID.randomUUID()
        def owner = UUID.randomUUID()
        def connection = DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password)
        connection.prepareStatement("insert into payments(id,requester_id,idempotency_key,request_hash,source_wallet_id,destination_wallet_id,currency,amount_minor,status) values (?,?,?,?,?,?,?,?,'PENDING')").withCloseable { insert ->
            [payment, owner, 'legacy', '0' * 64, UUID.randomUUID(), UUID.randomUUID(), source == 'payments' ? 'EUR' : 'AED', 125L].eachWithIndex { value, index -> insert.setObject(index + 1, value) }
            insert.executeUpdate()
        }
        if (source == 'notifications') {
            connection.prepareStatement("insert into notifications(id,owner_id,payment_id,type,currency,amount_minor) values (?,?,?,'PAYMENT_COMPLETED','EUR',125)").withCloseable { insert ->
                [UUID.randomUUID(), owner, payment].eachWithIndex { value, index -> insert.setObject(index + 1, value) }
                insert.executeUpdate()
            }
        }
        when:
        new Database(postgres.jdbcUrl, postgres.username, postgres.password)
        then:
        def failure = thrown(RuntimeException)
        failure.message.contains('AED-only migration requires no non-AED payments or notifications')
        connection.createStatement().withCloseable { statement ->
            statement.executeQuery('select currency, amount_minor, status from payments').withCloseable { rows ->
                assert rows.next()
                assert rows.getString('currency') == (source == 'payments' ? 'EUR' : 'AED')
                assert rows.getLong('amount_minor') == 125L
                assert rows.getString('status') == 'PENDING'
                assert !rows.next()
            }
            if (source == 'notifications') {
                statement.executeQuery('select currency, amount_minor from notifications').withCloseable { rows ->
                    assert rows.next()
                    assert rows.getString('currency') == 'EUR'
                    assert rows.getLong('amount_minor') == 125L
                    assert !rows.next()
                }
            }
            statement.executeQuery('select max(version::integer) from flyway_schema_history where success').withCloseable { rows ->
                assert rows.next()
                assert rows.getInt(1) == 2
            }
            true
        }
        cleanup:
        connection?.close()
        postgres?.stop()
        where:
        source << ['payments', 'notifications']
    }
}
