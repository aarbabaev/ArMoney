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
import com.arman.bank.userservice.infrastructure.MigrationBaselineConfiguration;
import com.arman.bank.userservice.infrastructure.PostgresMigrationBaselineStore;
import com.arman.bank.userservice.infrastructure.PrimaryMigrationDecisionClient;
import com.arman.bank.userservice.infrastructure.LiveUserRevisionConfiguration;
import com.arman.bank.userservice.infrastructure.LiveUserCurrentRoutes;
import com.arman.bank.userservice.infrastructure.PostgresLiveUserCurrentStore;
import com.arman.bank.userservice.infrastructure.HttpUserMutationSuspension;
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
        var migration = MigrationBaselineConfiguration.from(System.getenv());
        var live = LiveUserRevisionConfiguration.from(System.getenv());
        var resources = new OwnedResources();
        try {
            var shards = resources.own(ProfileShards.open(System.getenv()));
            var decisionClient = migration.enabled() ? resources.own(new PrimaryMigrationDecisionClient(migration)) : null;
            var mutationClient = live.enabled() ? resources.own(new HttpUserMutationSuspension(live)) : null;
            var currentRoutes = live.enabled()
                ? resources.own(new LiveUserCurrentRoutes(new PostgresLiveUserCurrentStore(shards.primary()), live)) : null;
            var projectionStore = !projection.enabled() ? null : mutationClient == null
                ? new PostgresVerificationProjectionStore(shards.primary())
                : new PostgresVerificationProjectionStore(shards.primary(), mutationClient, live.mutationWriter());
            var routes = new ProfileRoutes(new ProfileService(new PostgresProfiles(shards)), ServiceRuntime.required("INTERNAL_AUTH_KEY"),
                projection, projectionStore,
                migration, migration.enabled() ? new PostgresMigrationBaselineStore(shards, decisionClient, decisionClient) : null);
            java.util.function.BooleanSupplier readiness = shards::ready;
            var metrics = new OperationalMetrics();
            var runtime = ServiceRuntime.start("user-service", Integer.parseInt(System.getenv().getOrDefault("PORT", "8080")), readiness, resources, config -> {
                routes.configure(config, currentRoutes == null ? java.util.List.of() : java.util.List.of(
                    new com.arman.bank.runtime.InternalHttp.DedicatedRoute("POST", LiveUserCurrentRoutes.PATH)));
                if (currentRoutes != null) currentRoutes.configure(config);
                configureMetrics(config, System.getenv(), metrics, readiness);
            });
            Runtime.getRuntime().addShutdownHook(new Thread(runtime::close, "user-service-shutdown"));
        } catch (Exception | Error e) {
            try { resources.close(); } catch (Exception cleanup) { e.addSuppressed(cleanup); }
            throw e;
        }
    }

    /** Own only selected resources; startup failure and runtime shutdown share one reverse cleanup. */
    static final class OwnedResources implements AutoCloseable {
        private final java.util.List<AutoCloseable> owned = new java.util.ArrayList<>();
        private boolean closed;
        synchronized <T extends AutoCloseable> T own(T resource) {
            if (closed) throw new IllegalStateException("User resources closed");
            owned.add(java.util.Objects.requireNonNull(resource));
            return resource;
        }
        @Override public synchronized void close() throws Exception {
            if (closed) return;
            closed = true;
            Exception failure = null;
            for (int index = owned.size() - 1; index >= 0; index--) {
                try { owned.get(index).close(); }
                catch (Exception cleanup) { if (failure == null) failure = cleanup; else failure.addSuppressed(cleanup); }
            }
            if (failure != null) throw failure;
        }
    }
}
