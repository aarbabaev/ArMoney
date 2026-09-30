package com.arman.bank.authservice

import com.arman.bank.runtime.Database
import org.testcontainers.containers.PostgreSQLContainer
import spock.lang.Specification

class DatabaseIntegrationSpec extends Specification {
    def "V3 upgrades populated legacy identities and sessions without changing ownership"() {
        given:
        def postgres = new PostgreSQLContainer('postgres:17.6-alpine')
        postgres.start()
        org.flywaydb.core.Flyway.configure().dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
            .locations('classpath:db/migration').target('2').load().migrate()
        def id = UUID.randomUUID()
        def connection = java.sql.DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password)
        def sql = org.jooq.impl.DSL.using(connection, org.jooq.SQLDialect.POSTGRES)
        sql.execute('insert into identities(id,email,password_hash) values (?,?,?)', id, 'legacy@example.com', 'synthetic-hash')
        sql.execute("insert into sessions(token_hash,identity_id,expires_at) values (?,?,CURRENT_TIMESTAMP + interval '1 hour')", 'a' * 64, id)
        connection.close()

        when:
        def database = new Database(postgres.jdbcUrl, postgres.username, postgres.password)
        def store = new com.arman.bank.authservice.infrastructure.PostgresAuthStore(database)

        then:
        store.findSession('a' * 64, java.time.Instant.now()).orElseThrow().id() == id
        store.findByEmail('legacy@example.com').orElseThrow().identity().id() == id
        store.externalIdentity('https://issuer.example/realm', 'subject').id() != id

        cleanup:
        database?.close()
        connection?.close()
        postgres?.stop()
    }

    def "migrations apply and validate on restart; readiness detects an outage"() {
        given:
        def postgres = new PostgreSQLContainer('postgres:17.6-alpine')
        postgres.start()
        def database = new Database(postgres.jdbcUrl, postgres.username, postgres.password)

        expect:
        database.ready()

        when:
        database.close()
        database = new Database(postgres.jdbcUrl, postgres.username, postgres.password)

        then:
        database.ready()

        when:
        postgres.stop()

        then:
        !database.ready()

        cleanup:
        database?.close()
        postgres?.stop()
    }
}
