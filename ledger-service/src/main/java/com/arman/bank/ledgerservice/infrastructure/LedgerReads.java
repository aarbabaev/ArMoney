package com.arman.bank.ledgerservice.infrastructure;

import com.arman.bank.runtime.Database;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/** Optional read pools. Never constructs Database: standbys must never run Flyway. */
public final class LedgerReads implements AutoCloseable {
    private final Database primary;
    private final List<HikariDataSource> replicas = new ArrayList<>();
    private final AtomicInteger next = new AtomicInteger();
    private Identity pinned;
    private record Identity(String system, Integer timeline, String database) {}
    private record Fence(Identity identity, String lsn) {}

    public LedgerReads(Database primary, String urls, String user, String password) {
        this.primary = primary;
        try {
            for (String url : urls.split(",")) {
                if (url.isBlank()) continue;
                var config = new HikariConfig();
                config.setJdbcUrl(url.trim());
                config.setUsername(user);
                config.setPassword(password);
                config.setMaximumPoolSize(2);
                config.setMinimumIdle(0);
                config.setReadOnly(true);
                config.setConnectionTimeout(300);
                config.setValidationTimeout(250);
                config.setInitializationFailTimeout(-1);
                config.addDataSourceProperty("connectTimeout", "1");
                config.addDataSourceProperty("socketTimeout", "1");
                config.addDataSourceProperty("options", "-c statement_timeout=250");
                replicas.add(new HikariDataSource(config));
            }
        } catch (RuntimeException failure) { close(); throw failure; }
    }

    public <T> T read(Function<DSLContext, T> operation) {
        if (replicas.isEmpty()) return primary.transaction(operation);
        Fence fence;
        try {
            fence = primary.transaction(sql -> {
                var row = sql.fetchOne("select s.system_identifier::text, substring(pg_catalog.pg_walfile_name(pg_catalog.pg_current_wal_lsn()), 1, 8), pg_catalog.current_database(), pg_catalog.pg_current_wal_flush_lsn()::text as lsn from pg_catalog.pg_control_system() s where not pg_catalog.pg_is_in_recovery()");
                if (row == null) throw new IllegalStateException("Ledger primary is in recovery");
                return new Fence(new Identity(row.get(0, String.class), Integer.parseUnsignedInt(row.get(1, String.class), 16), row.get(2, String.class)), row.get(3, String.class));
            });
            synchronized (this) {
                if (pinned == null) pinned = fence.identity();
                if (!pinned.equals(fence.identity())) return primary.transaction(operation);
            }
        } catch (RuntimeException unavailable) { return primary.transaction(operation); }
        // One selected standby per request; alternating selections provide round robin without
        // multiplying the wait by the number of unavailable endpoints.
        var pool = replicas.get(Math.floorMod(next.getAndIncrement(), replicas.size()));
        try (var connection = pool.getConnection()) {
            connection.setAutoCommit(false);
            var sql = DSL.using(connection, SQLDialect.POSTGRES);
            long deadline = System.nanoTime() + 250_000_000L;
            while (true) {
                var state = sql.fetchOne("select s.system_identifier::text, w.received_tli, pg_catalog.current_database(), pg_catalog.pg_is_in_recovery(), coalesce(pg_catalog.pg_last_wal_replay_lsn() >= cast(? as pg_lsn), false) from pg_catalog.pg_control_system() s cross join pg_catalog.pg_stat_wal_receiver w where w.status = 'streaming'", fence.lsn());
                if (state == null || !eligible(fence.identity(), state.get(0, String.class), state.get(1, Integer.class), state.get(2, String.class), state.get(3, Boolean.class))) break;
                if (Boolean.TRUE.equals(state.get(4, Boolean.class))) {
                    T result = operation.apply(sql);
                    // Recheck recovery/identity after the account query, before returning its result.
                    var after = sql.fetchOne("select s.system_identifier::text, w.received_tli, pg_catalog.current_database(), pg_catalog.pg_is_in_recovery() from pg_catalog.pg_control_system() s cross join pg_catalog.pg_stat_wal_receiver w where w.status = 'streaming'");
                    if (after == null || !eligible(fence.identity(), after.get(0, String.class), after.get(1, Integer.class), after.get(2, String.class), after.get(3, Boolean.class))) break;
                    connection.commit();
                    return result;
                }
                if (System.nanoTime() >= deadline) break;
                Thread.sleep(10);
            }
            connection.rollback();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (Exception unavailable) {
            // Do not log JDBC URLs, credentials or account data. Primary remains authoritative.
        }
        return primary.transaction(operation);
    }

    public boolean ready() {
        try { return primary.transaction(LedgerReads::writablePrimary); }
        catch (RuntimeException unavailable) { return false; }
    }

    static boolean writablePrimary(DSLContext sql) {
        return Boolean.TRUE.equals(sql.fetchOne("select not pg_catalog.pg_is_in_recovery()").get(0, Boolean.class));
    }

    private static boolean eligible(Identity expected, String system, Integer timeline, String database, Boolean recovery) {
        return Boolean.TRUE.equals(recovery) && expected.equals(new Identity(system, timeline, database));
    }

    @Override public void close() { replicas.forEach(HikariDataSource::close); }
}
