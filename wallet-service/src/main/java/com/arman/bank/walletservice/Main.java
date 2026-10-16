package com.arman.bank.walletservice;
import com.arman.bank.runtime.Database;
import com.arman.bank.runtime.OperationalMetrics;
import com.arman.bank.runtime.OperationalMetricsHttp;
import com.arman.bank.runtime.ServiceRuntime;
import com.arman.bank.walletservice.application.WalletService;
import com.arman.bank.walletservice.application.WalletProvisioner;
import com.arman.bank.walletservice.infrastructure.*;
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
        String key = ServiceRuntime.required("INTERNAL_AUTH_KEY");
        var ledger = new HttpLedgerAccounts(ServiceRuntime.required("LEDGER_BASE_URL"), key);
        Database database = null;
        WalletProvisioner provisioner = null;
        ServiceRuntime runtime = null;
        try {
            database = new Database(ServiceRuntime.required("DB_URL"), ServiceRuntime.required("DB_USER"), ServiceRuntime.required("DB_PASSWORD"));
            var store = new PostgresWallets(database);
            provisioner = new WalletProvisioner(store, ledger);
            var routes = new WalletRoutes(new WalletService(store), key, ledger);
            var readinessDatabase = database;
            java.util.function.BooleanSupplier readiness = () -> readinessDatabase == null || readinessDatabase.ready();
            var metrics = new OperationalMetrics();
            runtime = ServiceRuntime.start("wallet-service", Integer.parseInt(System.getenv().getOrDefault("PORT", "8080")),
                    readiness, database, config -> {
                        routes.configure(config);
                        configureMetrics(config, System.getenv(), metrics, readiness);
                    });
            var worker = provisioner;
            var running = runtime;
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                worker.close();
                try { ledger.close(); } finally { running.close(); }
            }, "wallet-service-shutdown"));
            provisioner.start();
        } catch (Exception e) {
            if (provisioner != null) provisioner.close();
            ledger.close();
            if (runtime != null) runtime.close();
            else if (database != null) database.close();
            throw e;
        }
    }
}
