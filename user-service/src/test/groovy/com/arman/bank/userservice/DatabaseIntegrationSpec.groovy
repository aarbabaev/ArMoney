package com.arman.bank.userservice

import com.arman.bank.runtime.Database
import org.testcontainers.containers.PostgreSQLContainer
import spock.lang.Specification

class DatabaseIntegrationSpec extends Specification {
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
    def "V5 upgrade rejects historical duplicate directory phones without choosing or deleting an owner"() {
        given:
        def postgres = new PostgreSQLContainer('postgres:17.6-alpine')
        postgres.start()
        org.flywaydb.core.Flyway.configure().dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
            .locations('classpath:db/migration').target('4').load().migrate()
        def connection = java.sql.DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password)
        def statement = connection.prepareStatement('insert into profiles(id, identity_id, display_name, phone_number) values (?, ?, ?, ?)')
        2.times {
            statement.setObject(1, UUID.randomUUID())
            statement.setObject(2, UUID.randomUUID())
            statement.setString(3, 'Synthetic historical owner')
            statement.setString(4, '+971501234567')
            statement.executeUpdate()
        }
        connection.createStatement().executeUpdate("insert into profile_directory(identity_id, profile_id, shard_id, initialized, phone_number) select identity_id, id, 'primary', true, phone_number from profiles")
        when:
        new Database(postgres.jdbcUrl, postgres.username, postgres.password)
        then:
        thrown(org.flywaydb.core.api.FlywayException)
        when:
        def count = connection.createStatement().executeQuery('select count(*) from profiles')
        count.next()
        then:
        count.getInt(1) == 2
        cleanup:
        statement?.close()
        connection?.close()
        postgres?.stop()
    }
}
