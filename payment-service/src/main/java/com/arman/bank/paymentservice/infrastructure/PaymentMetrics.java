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
                    with recovery as (
                      select count(*) as pending, min(created_at) as oldest,
                        bool_or(not isfinite(created_at) or created_at > statement_timestamp()) as invalid_time
                      from payments where status = 'PENDING' and recipient_id is not null
                    ), outbox as (
                      select count(*) as pending, min(created_at) as oldest,
                        bool_or(not isfinite(created_at) or created_at > statement_timestamp()) as invalid_time
                      from email_outbox where status = 'PENDING'
                    )
                    select recovery.pending as recovery, outbox.pending as outbox,
                      case when recovery.pending = 0 then 0
                        when recovery.invalid_time then null
                        else floor(extract(epoch from (statement_timestamp() - recovery.oldest)))::bigint
                      end as recovery_age,
                      case when outbox.pending = 0 then 0
                        when outbox.invalid_time then null
                        else floor(extract(epoch from (statement_timestamp() - outbox.oldest)))::bigint
                      end as outbox_age
                    from recovery cross join outbox
                    """);
            });
            // Include scheduled retries and active leases: these commands remain unresolved.
            metrics.set(OperationalMetrics.Gauge.RECOVERY_BACKLOG, counts.get("recovery", Long.class));
            metrics.set(OperationalMetrics.Gauge.OUTBOX_BACKLOG, counts.get("outbox", Long.class));
            observeAge(OperationalMetrics.Gauge.RECOVERY_OLDEST_PENDING_AGE_SECONDS, counts.get("recovery_age", Long.class));
            observeAge(OperationalMetrics.Gauge.OUTBOX_OLDEST_PENDING_AGE_SECONDS, counts.get("outbox_age", Long.class));
        } catch (RuntimeException unavailable) {
            // Do not retain a stale zero (or expose SQL/connection details) after observation failure.
            metrics.unavailable(OperationalMetrics.Gauge.RECOVERY_BACKLOG);
            metrics.unavailable(OperationalMetrics.Gauge.OUTBOX_BACKLOG);
            metrics.unavailable(OperationalMetrics.Gauge.RECOVERY_OLDEST_PENDING_AGE_SECONDS);
            metrics.unavailable(OperationalMetrics.Gauge.OUTBOX_OLDEST_PENDING_AGE_SECONDS);
        }
    }
    private void observeAge(OperationalMetrics.Gauge gauge, Long seconds) {
        // Invalid source time is unknown, never a clamped healthy zero.
        if (seconds == null) metrics.unavailable(gauge);
        else metrics.set(gauge, seconds);
    }
}
