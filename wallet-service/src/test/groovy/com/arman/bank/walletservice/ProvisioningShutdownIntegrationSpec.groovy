package com.arman.bank.walletservice

import com.arman.bank.runtime.Database
import com.arman.bank.walletservice.application.WalletService
import com.arman.bank.walletservice.infrastructure.PostgresWallets
import org.jooq.exception.DataAccessException
import org.testcontainers.containers.PostgreSQLContainer
import spock.lang.Specification
import java.sql.DriverManager
import java.sql.SQLException
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ProvisioningShutdownIntegrationSpec extends Specification {
    def "claim times out on a PostgreSQL table lock and recovers after lock release"() {
        given:
        def postgres = new PostgreSQLContainer('postgres:17.6-alpine')
        postgres.start()
        def db = new Database(postgres.jdbcUrl, postgres.username, postgres.password)
        def store = new PostgresWallets(db)
        def wallet = new WalletService(store).open(UUID.randomUUID(), 'AED')
        def blocker = DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password)
        blocker.autoCommit = false
        def statement = blocker.createStatement()
        statement.execute('LOCK TABLE wallets IN ACCESS EXCLUSIVE MODE')
        def executor = Executors.newSingleThreadExecutor()

        when:
        def pending = executor.submit({ store.claim() } as Callable)
        pending.get(8, TimeUnit.SECONDS)

        then:
        def failure = thrown(ExecutionException)
        failure.cause instanceof DataAccessException
        Throwable cause = failure.cause
        while (cause != null && !(cause instanceof SQLException)) cause = cause.cause
        cause instanceof SQLException
        ((SQLException) cause).SQLState == '55P03' // lock_timeout, rather than the test's deadline

        when:
        blocker.rollback()
        def recovered = executor.submit({ store.claim() } as Callable).get(8, TimeUnit.SECONDS)

        then:
        recovered.present
        recovered.get().wallet().id() == wallet.id()
        recovered.get().attempt() == 1 // failed transaction never consumed a claim

        cleanup:
        blocker?.rollback()
        statement?.close()
        blocker?.close()
        executor?.shutdownNow()
        executor?.awaitTermination(8, TimeUnit.SECONDS)
        db?.close()
        postgres?.stop()
    }
}
