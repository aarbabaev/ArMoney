package com.arman.bank.ledgerservice.infrastructure;

import com.arman.bank.runtime.OperationalMetrics;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;

/** Primary-side observations of named senders, not proof of read-URL identity or catch-up ETA. */
public final class LedgerReplicaObservation implements AutoCloseable {
    private final Set<String> expected;
    private final HikariDataSource pool;
    private final OperationalMetrics metrics;

    public LedgerReplicaObservation(Map<String, String> environment, OperationalMetrics metrics) {
        this.metrics = Objects.requireNonNull(metrics);
        expected = configuredNames(environment);
        if (expected.isEmpty() || !environment.containsKey("INTERNAL_METRICS_KEY")) {
            pool = null;
            return;
        }
        var config = new HikariConfig();
        config.setJdbcUrl(environment.get("DB_URL"));
        config.setUsername(environment.get("DB_USER"));
        config.setPassword(environment.get("DB_PASSWORD"));
        config.setMaximumPoolSize(1);
        config.setMinimumIdle(0);
        config.setInitializationFailTimeout(-1);
        config.setConnectionTimeout(250);
        config.setValidationTimeout(250);
        config.setReadOnly(true);
        config.addDataSourceProperty("connectTimeout", "1");
        config.addDataSourceProperty("socketTimeout", "1");
        config.addDataSourceProperty("options", "-c statement_timeout=500 -c default_transaction_read_only=on");
        pool = new HikariDataSource(config);
    }

    static Set<String> configuredNames(Map<String, String> environment) {
        String value = environment.get("LEDGER_METRICS_REPLICA_NAMES");
        if (value == null) return Set.of();
        var names = new LinkedHashSet<String>();
        for (String name : value.split(",", -1)) {
            if (name.isEmpty() || name.length() > 64 ||
                    name.chars().anyMatch(c -> c < 33 || c > 126) || !names.add(name))
                throw new IllegalArgumentException("Invalid ledger replica metrics configuration");
        }
        long count = Arrays.stream(environment.getOrDefault("LEDGER_READ_DB_URLS", "").split(","))
                .filter(url -> !url.isBlank()).count();
        if (count != names.size())
            throw new IllegalArgumentException("Invalid ledger replica metrics configuration");
        return Set.copyOf(names);
    }

    public void observe() {
        metrics.unavailable(OperationalMetrics.Gauge.REPLICA_LAG_SECONDS);
        if (pool == null) return;
        try (var connection = pool.getConnection()) {
            var rows = DSL.using(connection, SQLDialect.POSTGRES).fetch("""
                with observation as materialized (
                    select clock_timestamp() as observed_at, pg_is_in_recovery() as recovery
                )
                select o.observed_at, o.recovery, r.application_name, r.state, r.reply_time,
                       r.replay_lsn::text,
                       case when not o.recovery then
                           pg_wal_lsn_diff(pg_current_wal_flush_lsn(), r.replay_lsn) end,
                       extract(epoch from r.replay_lag)
                from observation o left join pg_stat_replication r on true
                """);
            var samples = new ArrayList<Sample>();
            for (var row : rows) {
                var observed = row.get(0, java.time.OffsetDateTime.class);
                var reply = row.get(4, java.time.OffsetDateTime.class);
                samples.add(new Sample(row.get(2, String.class), row.get(1, Boolean.class),
                    row.get(3, String.class), observed == null ? null : observed.toInstant(),
                    reply == null ? null : reply.toInstant(), row.get(5, String.class),
                    row.get(6, BigDecimal.class), row.get(7, BigDecimal.class)));
            }
            var result = classify(expected, samples);
            if (result.isPresent()) metrics.set(OperationalMetrics.Gauge.REPLICA_LAG_SECONDS, result.getAsLong());
        } catch (Exception unavailable) {
            metrics.unavailable(OperationalMetrics.Gauge.REPLICA_LAG_SECONDS);
            // No connection details or private database diagnostics in metrics or logs.
        }
    }

    record Sample(String name, Boolean recovery, String state, Instant observed, Instant reply,
                  String replayLsn, BigDecimal bytes, BigDecimal seconds) {}

    static OptionalLong classify(Set<String> expected, List<Sample> samples) {
        if (expected.isEmpty()) return OptionalLong.empty();
        var seen = new HashSet<String>();
        long maximum = 0;
        for (var sample : samples) {
            if (sample.name() == null || !expected.contains(sample.name())) continue;
            if (!seen.add(sample.name()) || !Boolean.FALSE.equals(sample.recovery()) ||
                    !"streaming".equals(sample.state()) || sample.observed() == null || sample.reply() == null ||
                    sample.reply().isAfter(sample.observed()) ||
                    Duration.between(sample.reply(), sample.observed()).compareTo(Duration.ofSeconds(30)) > 0 ||
                    sample.replayLsn() == null || sample.bytes() == null || sample.bytes().signum() < 0)
                return OptionalLong.empty();
            if (sample.bytes().signum() == 0) continue;
            if (sample.seconds() == null || sample.seconds().signum() <= 0) return OptionalLong.empty();
            try {
                maximum = Math.max(maximum, sample.seconds().setScale(0, RoundingMode.CEILING).longValueExact());
            } catch (ArithmeticException invalid) { return OptionalLong.empty(); }
        }
        return seen.equals(expected) ? OptionalLong.of(maximum) : OptionalLong.empty();
    }

    @Override public void close() { if (pool != null) pool.close(); }
}
