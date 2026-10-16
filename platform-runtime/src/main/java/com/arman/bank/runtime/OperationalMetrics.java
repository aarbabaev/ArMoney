package com.arman.bank.runtime;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/** Bounded technical measurements. Callers supply no labels or customer data. */
public final class OperationalMetrics {
    public enum Gauge {
        READY("armoney_ready", "Whether the configured readiness probe currently succeeds."),
        RECOVERY_BACKLOG("armoney_recovery_backlog", "Commands waiting for operational recovery."),
        REPLICA_LAG_SECONDS("armoney_replica_lag_seconds", "Observed replication lag in seconds."),
        OUTBOX_BACKLOG("armoney_outbox_backlog", "Outbox records waiting for delivery.");

        private final String name;
        private final String description;
        Gauge(String name, String description) { this.name = name; this.description = description; }
    }

    public enum Counter {
        RECOVERY_FAILURES("armoney_recovery_failures_total", "Failed recovery attempts."),
        OUTBOX_FAILURES("armoney_outbox_failures_total", "Failed outbox delivery attempts.");

        private final String name;
        private final String description;
        Counter(String name, String description) { this.name = name; this.description = description; }
    }

    private final Map<Gauge, AtomicLong> gauges = new EnumMap<>(Gauge.class);
    private final Map<Counter, AtomicLong> counters = new EnumMap<>(Counter.class);

    /** Unknown measurements are omitted rather than presented as healthy zeroes. */
    public synchronized void set(Gauge gauge, long value) {
        if (gauge == null || value < 0 || (gauge == Gauge.READY && value > 1))
            throw new IllegalArgumentException("Invalid operational gauge");
        gauges.computeIfAbsent(gauge, ignored -> new AtomicLong()).set(value);
    }

    public synchronized void increment(Counter counter) {
        if (counter == null) throw new IllegalArgumentException("Missing operational counter");
        counters.computeIfAbsent(counter, ignored -> new AtomicLong())
                .updateAndGet(value -> value == Long.MAX_VALUE ? value : value + 1);
    }

    /** Drop a stale observation when its source is unavailable. */
    public synchronized void unavailable(Gauge gauge) {
        if (gauge == null) throw new IllegalArgumentException("Missing operational gauge");
        gauges.remove(gauge);
    }

    public synchronized String render() {
        var output = new StringBuilder();
        gauges.forEach((metric, value) -> append(output, metric.name, metric.description, "gauge", value.get()));
        counters.forEach((metric, value) -> append(output, metric.name, metric.description, "counter", value.get()));
        return output.toString();
    }

    private static void append(StringBuilder output, String name, String description, String type, long value) {
        output.append("# HELP ").append(name).append(' ').append(description).append('\n')
                .append("# TYPE ").append(name).append(' ').append(type).append('\n')
                .append(name).append(' ').append(value).append('\n');
    }
}
