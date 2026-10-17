package com.arman.bank.runtime

import org.testcontainers.containers.PostgreSQLContainer
import spock.lang.Shared
import spock.lang.Specification
import com.zaxxer.hikari.HikariDataSource

import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.SQLException

import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class DatabaseSessionIntegrationSpec extends Specification {
    @Shared PostgreSQLContainer postgres = new PostgreSQLContainer('postgres:17.6-alpine')
    Database database

    def setupSpec() { postgres.start() }
    def cleanupSpec() { postgres.stop() }
    def setup() {
        database = new Database(postgres.jdbcUrl, postgres.username, postgres.password)
        database.transaction { sql ->
            sql.execute('create table if not exists session_fixture (id integer primary key)')
            sql.execute('delete from session_fixture')
        }
    }
    def cleanup() { database?.close() }

    static int pid(sql) { sql.fetchValue('select pg_backend_pid()') }
    int count() { database.transaction { it.fetchValue('select count(*) from session_fixture') } }

    def 'autocommit and committed phases retain one backend and survive session return'() {
        when:
        def phases = database.withSession { session ->
            def first = session.autocommit { sql ->
                sql.execute('insert into session_fixture values (?)', 1)
                pid(sql)
            }
            def second = session.transaction { sql ->
                sql.execute('insert into session_fixture values (?)', 2)
                pid(sql)
            }
            def third = session.autocommit { sql ->
                assert sql.fetchValue('select count(*) from session_fixture') == 2
                assert sql.connectionResult { it.autoCommit }
                pid(sql)
            }
            [first, second, third]
        }
        then:
        phases.toSet().size() == 1
        count() == 2
        database.ready()
    }

    def 'callback failure rolls back only its transaction and session remains usable'() {
        when:
        database.withSession { session ->
            session.autocommit { it.execute('insert into session_fixture values (?)', 1) }
            def before = session.autocommit { pid(it) }
            try {
                session.transaction { sql ->
                    assert pid(sql) == before
                    sql.execute('insert into session_fixture values (?)', 2)
                    throw new IllegalArgumentException('synthetic rejection')
                }
                assert false
            } catch (IllegalArgumentException expected) {
                assert expected.message == 'synthetic rejection'
            }
            session.autocommit { sql ->
                assert pid(sql) == before
                assert sql.connectionResult { it.autoCommit }
                assert sql.fetchValue('select count(*) from session_fixture') == 1
            }
            session.transaction { it.execute('insert into session_fixture values (?)', 3) }
        }
        then:
        count() == 2
    }

    def 'nested phases are rejected and containing transaction rolls back: #nested'() {
        when:
        database.withSession { session ->
            session.transaction { sql ->
                sql.execute('insert into session_fixture values (?)', 1)
                if (nested == 'transaction') session.transaction { 1 }
                else session.autocommit { 1 }
            }
        }
        then:
        thrown(IllegalStateException)
        count() == 0
        where:
        nested << ['transaction', 'autocommit']
    }

    def 'autocommit callback cannot start another session phase'() {
        when:
        database.withSession { session -> session.autocommit { session.transaction { 1 } } }
        then:
        thrown(IllegalStateException)
    }

    def 'all session operations reject a different thread: #operation'() {
        given:
        def executor = Executors.newSingleThreadExecutor()
        when:
        def failure = database.withSession { session ->
            executor.submit({ ->
                try {
                    if (operation == 'transaction') session.transaction { 1 }
                    else if (operation == 'autocommit') session.autocommit { 1 }
                    else session.invalidate()
                    return null
                } catch (IllegalStateException expected) { return expected }
            } as Callable).get(5, TimeUnit.SECONDS)
        }
        then:
        failure instanceof IllegalStateException
        database.ready()
        cleanup:
        executor.shutdownNow()
        assert executor.awaitTermination(5, TimeUnit.SECONDS)
        where:
        operation << ['transaction', 'autocommit', 'invalidate']
    }

    def 'returned session rejects every operation: #operation'() {
        given:
        def retained = new AtomicReference<Database.OwnedSession>()
        database.withSession { session -> retained.set(session) }
        when:
        if (operation == 'transaction') retained.get().transaction { 1 }
        else if (operation == 'autocommit') retained.get().autocommit { 1 }
        else retained.get().invalidate()
        then:
        thrown(IllegalStateException)
        where:
        operation << ['transaction', 'autocommit', 'invalidate']
    }

    def 'invalidation discards backend and releases session advisory lock before reuse'() {
        given:
        int lock = 782413
        int previous = 0
        when:
        database.withSession { session ->
            previous = session.autocommit { sql ->
                sql.execute('select pg_advisory_lock(?)', lock)
                pid(sql)
            }
            session.invalidate()
            for (operation in ['autocommit', 'transaction', 'invalidate']) {
                try {
                    if (operation == 'autocommit') session.autocommit { 1 }
                    else if (operation == 'transaction') session.transaction { 1 }
                    else session.invalidate()
                    assert false
                } catch (IllegalStateException expected) { /* denied */ }
            }
        }
        then:
        database.withSession { session ->
            session.autocommit { sql ->
                assert pid(sql) != previous
                assert sql.fetchValue('select pg_try_advisory_lock(?)', lock)
                assert sql.fetchValue('select pg_advisory_unlock(?)', lock)
                true
            }
        }
    }

    def 'original transaction API preserves commit and rollback behavior'() {
        given:
        database.transaction { it.execute('insert into session_fixture values (?)', 1) }
        when:
        database.transaction { sql ->
            sql.execute('insert into session_fixture values (?)', 2)
            throw new IllegalArgumentException('synthetic rejection')
        }
        then:
        thrown(IllegalArgumentException)
        count() == 1
    }

    def 'real PostgreSQL commit fault reports uncertainty without claiming rollback: #mode'() {
        given:
        def faults = installFaultPool(mode)
        def retained = new AtomicReference<Database.OwnedSession>()
        int previous = 0
        when:
        database.withSession { session ->
            retained.set(session)
            session.transaction { sql ->
                previous = pid(sql)
                sql.execute('insert into session_fixture values (?)', 41)
            }
        }
        then:
        def failure = thrown(IllegalStateException)
        failure.message == 'Transaction commit outcome is unknown'
        failure.cause instanceof SQLException
        failure.cause.SQLState == '08007'
        faults.commitAttempts == 1
        faults.rollbackAttempts == 0
        faults.evictionAttempts == 1
        when:
        faults.mode = 'none'
        then:
        count() == durableRows
        database.withSession { session -> session.autocommit { pid(it) != previous } }
        when:
        retained.get().autocommit { 1 }
        then:
        thrown(IllegalStateException)
        where:
        mode | durableRows
        'after-commit' | 1
        'before-commit' | 0
    }

    def 'throwing Hikari eviction still physically aborts and releases advisory lock'() {
        given:
        def faults = installFaultPool('evict-failure')
        int previous = 0
        int lock = 782414
        when:
        database.withSession { session ->
            session.autocommit { sql ->
                previous = pid(sql)
                sql.execute('select pg_advisory_lock(?)', lock)
            }
            session.invalidate()
        }
        then:
        def failure = thrown(IllegalStateException)
        failure.is(faults.evictionFailure)
        faults.evictionAttempts == 1
        when:
        faults.mode = 'none'
        then:
        database.withSession { session -> session.autocommit { sql ->
            assert pid(sql) != previous
            assert sql.fetchValue('select count(*) from pg_stat_activity where pid = ?', previous) == 0
            assert sql.fetchValue('select pg_try_advisory_lock(?)', lock)
            assert sql.fetchValue('select pg_advisory_unlock(?)', lock)
            true
        } }
    }

    /** Test-only JDBC facade: all SQL runs on the real isolated PostgreSQL backend.
     * The commit exception models acknowledgement loss; it is not a network-fault claim.
     */
    FaultPool installFaultPool(String mode) {
        def field = Database.getDeclaredField('pool')
        field.accessible = true
        def facade = new FaultPool((HikariDataSource) field.get(database), mode)
        field.set(database, facade)
        facade
    }

    static class FaultPool extends HikariDataSource {
        final HikariDataSource delegate
        final Map<Connection, Connection> connections = new IdentityHashMap<>()
        final IllegalStateException evictionFailure = new IllegalStateException('synthetic eviction failure')
        String mode
        int commitAttempts, rollbackAttempts, evictionAttempts

        FaultPool(HikariDataSource delegate, String mode) { this.delegate = delegate; this.mode = mode }

        @Override Connection getConnection() {
            Connection real = delegate.connection
            Connection wrapped = (Connection) Proxy.newProxyInstance(Connection.classLoader, [Connection] as Class[],
                { proxy, method, arguments ->
                    if (method.name == 'commit') {
                        commitAttempts++
                        if (mode == 'before-commit') throw new SQLException('synthetic pre-commit failure', '08007')
                        if (mode == 'after-commit') {
                            real.commit()
                            throw new SQLException('synthetic lost commit acknowledgement', '08007')
                        }
                    }
                    if (method.name == 'rollback') rollbackAttempts++
                    try { method.invoke(real, arguments) }
                    catch (InvocationTargetException failure) { throw failure.cause }
                } as java.lang.reflect.InvocationHandler)
            connections.put(wrapped, real)
            wrapped
        }

        @Override void evictConnection(Connection connection) {
            evictionAttempts++
            if (mode == 'evict-failure') throw evictionFailure
            delegate.evictConnection(connections.get(connection) ?: connection)
        }

        @Override void close() { delegate.close() }
    }

    def 'pool still admits five simultaneously owned connections and rejects a sixth'() {
        given:
        def executor = Executors.newFixedThreadPool(5)
        def admitted = new CountDownLatch(5)
        def release = new CountDownLatch(1)
        def futures = (1..5).collect {
            executor.submit({ -> database.withSession { session ->
                int backend = session.autocommit { pid(it) }
                admitted.countDown()
                assert release.await(12, TimeUnit.SECONDS)
                backend
            } } as Callable)
        }
        assert admitted.await(8, TimeUnit.SECONDS)
        when:
        database.withSession { 1 }
        then:
        thrown(IllegalStateException)
        when:
        release.countDown()
        def pids = futures.collect { it.get(5, TimeUnit.SECONDS) }
        then:
        pids.toSet().size() == 5
        database.ready()
        cleanup:
        release.countDown()
        executor.shutdownNow()
        assert executor.awaitTermination(5, TimeUnit.SECONDS)
    }
}
