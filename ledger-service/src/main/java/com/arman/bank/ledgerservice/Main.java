package com.arman.bank.ledgerservice;
import com.arman.bank.runtime.*;
import com.arman.bank.ledgerservice.application.LedgerService;
import com.arman.bank.ledgerservice.infrastructure.*;
public final class Main {
    private Main() {}
    static void configureMetrics(io.javalin.config.JavalinConfig config,
            java.util.Map<String, String> environment, OperationalMetrics metrics,
            java.util.function.BooleanSupplier readiness) {
        var key = environment.get("INTERNAL_METRICS_KEY");
        if (key == null) return;
        if (key.isBlank()) throw new IllegalArgumentException("INTERNAL_METRICS_KEY must not be blank");
        OperationalMetricsHttp.configure(config, key, metrics, readiness);
    }

    public static void main(String[] args) throws Exception {
        var db = new Database(ServiceRuntime.required("DB_URL"), ServiceRuntime.required("DB_USER"), ServiceRuntime.required("DB_PASSWORD"));
        LedgerReads reads = null;
        try {
            reads = new LedgerReads(db, System.getenv().getOrDefault("LEDGER_READ_DB_URLS", ""), ServiceRuntime.required("DB_USER"), ServiceRuntime.required("DB_PASSWORD"),
                Boolean.parseBoolean(System.getenv().getOrDefault("LEDGER_REQUIRE_SYNC_CONFIRMATION", "false")));
            var ownedReads = reads;
            var routes = new LedgerRoutes(new LedgerService(new PostgresLedger(db, reads)), ServiceRuntime.required("INTERNAL_AUTH_KEY"));
            java.util.function.BooleanSupplier readiness = ownedReads::ready;
            var metrics = new OperationalMetrics();
            var runtime = ServiceRuntime.start("ledger-service", Integer.parseInt(System.getenv().getOrDefault("PORT", "8080")), readiness,
                () -> { try { ownedReads.close(); } finally { db.close(); } }, config -> {
                    routes.configure(config);
                    configureMetrics(config, System.getenv(), metrics, readiness);
                });
            Runtime.getRuntime().addShutdownHook(new Thread(runtime::close, "ledger-shutdown"));
        } catch (Exception e) { if (reads != null) reads.close(); db.close(); throw e; }
    }
}
