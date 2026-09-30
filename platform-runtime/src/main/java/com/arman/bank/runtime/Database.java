package com.arman.bank.runtime;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;

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

    @Override public void close() { pool.close(); }
}
