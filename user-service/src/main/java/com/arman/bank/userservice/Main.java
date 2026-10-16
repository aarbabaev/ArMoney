package com.arman.bank.userservice;
import com.arman.bank.userservice.infrastructure.ProfileShards;
import com.arman.bank.runtime.ServiceRuntime;
import com.arman.bank.runtime.OperationalMetrics;
import com.arman.bank.runtime.OperationalMetricsHttp;
import com.arman.bank.userservice.application.ProfileService;
import com.arman.bank.userservice.infrastructure.ProfileRoutes;
import com.arman.bank.userservice.infrastructure.PostgresProfiles;
import com.arman.bank.userservice.infrastructure.VerificationProjectionConfiguration;
import com.arman.bank.userservice.infrastructure.PostgresVerificationProjectionStore;
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
        var projection = VerificationProjectionConfiguration.from(System.getenv());
        var shards = ProfileShards.open(System.getenv());
        try {
            var routes = new ProfileRoutes(new ProfileService(new PostgresProfiles(shards)), ServiceRuntime.required("INTERNAL_AUTH_KEY"),
                projection, projection.enabled() ? new PostgresVerificationProjectionStore(shards.primary()) : null);
            java.util.function.BooleanSupplier readiness = shards::ready;
            var metrics = new OperationalMetrics();
            var runtime = ServiceRuntime.start("user-service", Integer.parseInt(System.getenv().getOrDefault("PORT", "8080")), readiness, shards, config -> {
                routes.configure(config);
                configureMetrics(config, System.getenv(), metrics, readiness);
            });
            Runtime.getRuntime().addShutdownHook(new Thread(runtime::close, "user-service-shutdown"));
        } catch (Exception e) { shards.close(); throw e; }
    }
}
