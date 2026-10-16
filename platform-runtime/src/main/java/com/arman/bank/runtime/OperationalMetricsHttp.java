package com.arman.bank.runtime;

import io.javalin.config.JavalinConfig;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Objects;
import java.util.function.BooleanSupplier;

/** Opt-in private scrape adapter; it never registers a public gateway route. */
public final class OperationalMetricsHttp {
    private OperationalMetricsHttp() {}

    public static void configure(JavalinConfig config, String key,
            OperationalMetrics metrics, BooleanSupplier readiness) {
        configure(config, key, metrics, readiness, () -> {});
    }

    public static void configure(JavalinConfig config, String key,
            OperationalMetrics metrics, BooleanSupplier readiness, Runnable observations) {
        if (key == null || key.length() < 32 || key.length() > 256 ||
                key.chars().anyMatch(character -> character <= 32 || character >= 127))
            throw new IllegalArgumentException("Metrics service key must have 32 to 256 characters");
        Objects.requireNonNull(config);
        Objects.requireNonNull(metrics);
        Objects.requireNonNull(readiness);
        Objects.requireNonNull(observations);
        byte[] expected = key.getBytes(StandardCharsets.UTF_8);
        config.routes.get("/internal/metrics", ctx -> {
            ctx.header("Cache-Control", "no-store");
            String supplied = ctx.header("X-Service-Key");
            if (supplied == null || supplied.length() > 256 ||
                    !MessageDigest.isEqual(expected, supplied.getBytes(StandardCharsets.UTF_8))) {
                ctx.status(401).contentType("application/json")
                        .result("{\"error\":\"request_rejected\"}");
                return;
            }
            try { observations.run(); }
            catch (RuntimeException unavailable) {
                for (var gauge : OperationalMetrics.Gauge.values())
                    if (gauge != OperationalMetrics.Gauge.READY) metrics.unavailable(gauge);
            }
            boolean ready;
            try { ready = readiness.getAsBoolean(); }
            catch (RuntimeException unavailable) { ready = false; }
            metrics.set(OperationalMetrics.Gauge.READY, ready ? 1 : 0);
            ctx.contentType("text/plain; version=0.0.4; charset=utf-8").result(metrics.render());
        });
    }
}
