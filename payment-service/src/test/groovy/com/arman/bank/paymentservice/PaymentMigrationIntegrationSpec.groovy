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
        [legacy, owner, 'legacy', '0' * 64, UUID.randomUUID(), UUID.randomUUID(), 'EUR', 125L].eachWithIndex { value, index -> insert.setObject(index + 1, value) }
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
}
