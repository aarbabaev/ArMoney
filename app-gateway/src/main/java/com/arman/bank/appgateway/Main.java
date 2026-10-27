package com.arman.bank.appgateway;
import com.arman.bank.runtime.ServiceRuntime;
import com.arman.bank.runtime.OperationalMetrics;
import com.arman.bank.runtime.OperationalMetricsHttp;
import java.net.URI;
import java.time.Clock;
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
        var auth = URI.create(ServiceRuntime.required("AUTH_BASE_URL"));
        var users = URI.create(ServiceRuntime.required("USER_BASE_URL"));
        var wallets = URI.create(ServiceRuntime.required("WALLET_BASE_URL"));
        var payments = URI.create(ServiceRuntime.required("PAYMENT_BASE_URL"));
        var key = ServiceRuntime.required("INTERNAL_AUTH_KEY");
        var migrationSettings = MigrationBrowserConfiguration.from(System.getenv(), auth);
        MigrationBrowserProxy migration = null;
        try {
        if (migrationSettings.enabled()) migration = new MigrationBrowserProxy(migrationSettings);
        final var migrationProxy = migration;
        String assistantUrl = System.getenv("ASSISTANT_URL");
        var assistantProxy = new AssistantProxy(auth, wallets, payments, key,
            assistantUrl == null || assistantUrl.isBlank() ? null : URI.create(assistantUrl),
            System.getenv("GATEWAY_ASSISTANT_KEY"), System.getenv("ASSISTANT_GATEWAY_KEY"));
        var proxy = new AuthProxy(auth, key, Clock.systemUTC());
        try {
            var protectedProxy = new ProtectedProxy(auth, users, wallets, payments, key);
            try {
                java.util.function.BooleanSupplier readiness = () -> true;
                var metrics = new OperationalMetrics();
                var runtime = ServiceRuntime.start("app-gateway", Integer.parseInt(System.getenv().getOrDefault("PORT", "8080")),
                    readiness, null, config -> {
                        proxy.configure(config); protectedProxy.configure(config); assistantProxy.configure(config);
                        if (migrationProxy != null) migrationProxy.configure(config);
                        configureMetrics(config, System.getenv(), metrics, readiness);
                    });
                Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                    try { runtime.close(); } finally { try { proxy.close(); } finally { try { protectedProxy.close(); } finally { try { assistantProxy.close(); } finally { if (migrationProxy != null) migrationProxy.close(); } } } }
                }, "gateway-shutdown"));
            } catch (Exception e) { protectedProxy.close(); throw e; }
        } catch (Exception e) { try { proxy.close(); } finally { assistantProxy.close(); } throw e; }
        } catch (Exception e) { if (migration != null) migration.close(); throw e; }
    }
}

