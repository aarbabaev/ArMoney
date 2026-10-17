package com.arman.bank.runtime;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.jooq.DSLContext;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;
import java.util.function.Function;

public final class Database implements AutoCloseable {
    private final HikariDataSource pool;

    public Database(String url, String user, String password) {
        var config = new HikariConfig();
        config.setJdbcUrl(url);
        config.setUsername(user);
        config.setPassword(password);
        config.setMaximumPoolSize(5);
        config.setConnectionTimeout(3000);
        config.setValidationTimeout(1000);
        pool = new HikariDataSource(config);
        try {
            Flyway.configure().dataSource(pool).locations("classpath:db/migration")
                    .cleanDisabled(true).load().migrate();
        } catch (RuntimeException e) {
            pool.close();
            throw e;
        }
    }

    public boolean ready() {
        try (var connection = pool.getConnection()) {
            return DSL.using(connection, SQLDialect.POSTGRES).selectOne().fetchOne(0, Integer.class) == 1;
        } catch (Exception e) {
            return false;
        }
    }

    public <T> T transaction(java.util.function.Function<org.jooq.DSLContext, T> operation) {
        return DSL.using(pool, SQLDialect.POSTGRES).transactionResult(configuration ->
                operation.apply(DSL.using(configuration)));
    }

    /**
     * Borrows one physical connection for the callback, including separate committed phases.
     * The session and its DSL contexts must not escape their callbacks. Callers must not
     * close connections or manage transactions through the supplied DSL contexts.
     */
    public <T> T withSession(Function<OwnedSession, T> operation) {
        Objects.requireNonNull(operation);
        try (var connection = pool.getConnection()) {
            var session = new OwnedSession(connection);
            try {
                return operation.apply(session);
            } finally {
                session.closed = true;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Database session unavailable", e);
        }
    }

    /** Thread-confined capability valid only inside withSession; DSL contexts are phase-scoped. */
    public final class OwnedSession {
        private final Connection connection;
        private final Connection physical;
        private final Thread owner = Thread.currentThread();
        private boolean closed, invalid, phase;

        private OwnedSession(Connection connection) throws SQLException {
            this.connection = connection;
            this.physical = connection.unwrap(Connection.class);
        }

        private void check() {
            if (Thread.currentThread() != owner || closed || invalid)
                throw new IllegalStateException("Database session is not available to this caller");
        }

        private void enter() {
            check();
            if (phase) throw new IllegalStateException("Nested database session phases are forbidden");
            phase = true;
        }

        public <T> T autocommit(Function<DSLContext, T> operation) {
            Objects.requireNonNull(operation);
            enter();
            try {
                if (!connection.getAutoCommit()) throw new IllegalStateException("Autocommit phase requires no transaction");
                return operation.apply(DSL.using(connection, SQLDialect.POSTGRES));
            } catch (SQLException e) {
                discardAfter(e);
                throw new IllegalStateException("Database session state unavailable", e);
            } finally {
                phase = false;
            }
        }

        public <T> T transaction(Function<DSLContext, T> operation) {
            Objects.requireNonNull(operation);
            enter();
            try {
                if (!connection.getAutoCommit()) throw new IllegalStateException("Nested transactions are forbidden");
                connection.setAutoCommit(false);
                T result;
                try {
                    result = operation.apply(DSL.using(connection, SQLDialect.POSTGRES));
                    check();
                } catch (RuntimeException | Error failure) {
                    if (!invalid) {
                        try {
                            connection.rollback();
                            connection.setAutoCommit(true);
                        } catch (SQLException cleanup) {
                            failure.addSuppressed(cleanup);
                            discardAfter(failure);
                        }
                    }
                    throw failure;
                }
                try {
                    connection.commit();
                } catch (SQLException uncertain) {
                    // A lost commit acknowledgement cannot establish a rollback.
                    discardAfter(uncertain);
                    throw new IllegalStateException("Transaction commit outcome is unknown", uncertain);
                }
                connection.setAutoCommit(true);
                return result;
            } catch (SQLException e) {
                discardAfter(e);
                throw new IllegalStateException("Database session state unavailable", e);
            } finally {
                phase = false;
            }
        }

        private void discardAfter(Throwable failure) {
            try { invalidate(); }
            catch (RuntimeException | Error cleanup) { failure.addSuppressed(cleanup); }
        }

        /** Discards the physical connection, including any uncertain session-level locks. */
        public void invalidate() {
            check();
            invalid = true;
            Throwable failure = null;
            try {
                pool.evictConnection(connection);
            } catch (RuntimeException | Error eviction) {
                failure = eviction;
            }
            // Always terminate the physical connection, even when pool eviction fails.
            // Hikari's logical close alone could recycle a session holding advisory locks.
            try { physical.abort(Runnable::run); }
            catch (SQLException | RuntimeException | Error abort) {
                if (failure == null) failure = abort;
                else failure.addSuppressed(abort);
                try { physical.close(); }
                catch (SQLException | RuntimeException | Error close) {
                    failure.addSuppressed(close);
                    // If physical disposal is uncertain, stop the entire pool rather
                    // than allow any logical close to return this connection for reuse.
                    try { pool.close(); }
                    catch (RuntimeException | Error shutdown) { failure.addSuppressed(shutdown); }
                }
            }
            if (failure instanceof Error error) throw error;
            if (failure instanceof RuntimeException runtime) throw runtime;
            if (failure != null) throw new IllegalStateException("Database connection invalidation failed", failure);
        }
    }

    @Override public void close() { pool.close(); }
}
