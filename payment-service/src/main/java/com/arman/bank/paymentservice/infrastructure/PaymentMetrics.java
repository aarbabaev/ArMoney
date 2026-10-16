package com.arman.bank.paymentservice.infrastructure;

import com.arman.bank.runtime.Database;
import com.arman.bank.runtime.OperationalMetrics;
import java.util.Objects;

/** Aggregate observations only; never claims work or reads customer payloads. */
public final class PaymentMetrics implements Runnable {
    private final Database database;
    private final OperationalMetrics metrics;

    public PaymentMetrics(Database database, OperationalMetrics metrics) {
        this.database = Objects.requireNonNull(database);
        this.metrics = Objects.requireNonNull(metrics);
    }

    @Override public void run() {
        try {
            var counts = database.transaction(sql -> {
                sql.execute("set transaction read only");
                sql.execute("set local statement_timeout = '2s'");
                return sql.fetchOne("""
                    select
                      (select count(*) from payments where status = 'PENDING'
                        and recipient_id is not null) as recovery,
                      (select count(*) from email_outbox where status = 'PENDING') as outbox
                    """);
            });
            // Include scheduled retries and active leases: these commands remain unresolved.
            metrics.set(OperationalMetrics.Gauge.RECOVERY_BACKLOG, counts.get("recovery", Long.class));
            metrics.set(OperationalMetrics.Gauge.OUTBOX_BACKLOG, counts.get("outbox", Long.class));
        } catch (RuntimeException unavailable) {
            // Do not retain a stale zero (or expose SQL/connection details) after observation failure.
            metrics.unavailable(OperationalMetrics.Gauge.RECOVERY_BACKLOG);
            metrics.unavailable(OperationalMetrics.Gauge.OUTBOX_BACKLOG);
        }
    }
}
