package com.arman.bank.authservice

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
}
