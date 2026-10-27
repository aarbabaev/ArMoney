package com.arman.bank.authservice;

import com.arman.bank.authservice.application.AuthService;
import com.arman.bank.authservice.infrastructure.Argon2Passwords;
import com.arman.bank.authservice.infrastructure.AuthRoutes;
import com.arman.bank.authservice.infrastructure.PostgresAuthStore;
import com.arman.bank.runtime.Database;
import com.arman.bank.runtime.ServiceRuntime;
import com.arman.bank.runtime.OperationalMetrics;
import com.arman.bank.runtime.OperationalMetricsHttp;
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
        var deliveryConfig = new com.arman.bank.authservice.infrastructure.VerificationDeliveryConfiguration(System.getenv());
        var projectionConfig = new com.arman.bank.authservice.infrastructure.VerificationProjectionConfiguration(System.getenv());
        var authorityConfig = new com.arman.bank.authservice.infrastructure.VerificationAuthorityConfiguration(System.getenv());
        var bankingEligibilityConfig = new com.arman.bank.authservice.infrastructure.BankingEligibilityConfiguration(System.getenv());
        var loginProofConfig = new com.arman.bank.authservice.infrastructure.LoginProofConfiguration(System.getenv());
        var managedBankingConfig = new com.arman.bank.authservice.infrastructure.ManagedBankingConfiguration(System.getenv());
        var historicalAuthMode = historicalAuthMode(managedBankingConfig, System.getenv());
        var confirmationConfig = new com.arman.bank.authservice.infrastructure.CodeConfirmationConfiguration(System.getenv());
        var migrationConfig = com.arman.bank.authservice.infrastructure.LegacyMigrationConfiguration.from(System.getenv(), java.util.Map.of());
        var revisionConfig = com.arman.bank.authservice.infrastructure.MigrationUserRevisionConfiguration.from(System.getenv());
        var database = new Database(ServiceRuntime.required("DB_URL"), ServiceRuntime.required("DB_USER"), ServiceRuntime.required("DB_PASSWORD"));
        com.arman.bank.authservice.infrastructure.KeycloakTokens sso = null;
        com.arman.bank.authservice.infrastructure.HttpRegistrationProfiles profiles = null;
        com.arman.bank.authservice.infrastructure.VerificationDeliveryWorker verificationDelivery = null;
        com.arman.bank.authservice.infrastructure.VerificationProjectionWorker verificationProjection = null;
        com.arman.bank.authservice.infrastructure.VerificationAuthorityConfiguration.Resources authorityResources = null;
        com.arman.bank.authservice.infrastructure.CodeConfirmationResources confirmationResources = null;
        ManagedResources managedResources = null;
        com.arman.bank.authservice.infrastructure.LegacyMigrationResources migrationResources = null;
        com.arman.bank.authservice.infrastructure.MigrationUserRevisionResources revisionResources = null;
        ServiceRuntime startedRuntime = null;
        try {
            authorityResources = authorityConfig.open(database);
            revisionResources = com.arman.bank.authservice.infrastructure.MigrationUserRevisionResources.open(
                    database, migrationConfig, revisionConfig, authorityResources).orElse(null);
            var revision = revisionResources;
            var migrationCrypto = revision == null ? null : revision.crypto();
            var userCurrent = revision == null ? null : revision.userCurrent();
            confirmationResources = com.arman.bank.authservice.infrastructure.CodeConfirmationResources.open(
                    database, confirmationConfig, migrationCrypto, userCurrent).orElse(null);
            var confirmation = confirmationResources;
            managedResources = openManaged(database, managedBankingConfig, confirmationConfig, System.getenv(), migrationCrypto, userCurrent);
            var managed = managedResources;
            var serviceKey = ServiceRuntime.required("INTERNAL_AUTH_KEY");
            profiles = new com.arman.bank.authservice.infrastructure.HttpRegistrationProfiles(
                    java.net.URI.create(ServiceRuntime.required("USER_BASE_URL")), serviceKey);
            var issuer = System.getenv("SSO_ISSUER");
            if (issuer != null && !issuer.isBlank()) sso = new com.arman.bank.authservice.infrastructure.KeycloakTokens(
                    java.net.URI.create(ServiceRuntime.required("SSO_INTROSPECTION_URL")), issuer,
                    ServiceRuntime.required("SSO_CLIENT_ID"), ServiceRuntime.required("SSO_CLIENT_SECRET"), Clock.systemUTC());
            var provider = sso;
            var registrationProfiles = profiles;
            verificationDelivery = deliveryConfig.worker(database);
            var verificationWorker = verificationDelivery;
            var verificationAuthority = authorityResources;
            if (authorityConfig.completionEnabled && authorityResources == null)
                throw new IllegalStateException("Authority completion resources unavailable");
            verificationProjection = authorityConfig.completionEnabled ? null : projectionConfig.worker(database);
            var standaloneProjection = verificationProjection;
            var projectionWorker = authorityConfig.completionEnabled ? authorityResources.projectionWorker() : standaloneProjection;
            var loginProofRoutes = loginProofConfig.routes(database);
            var auth = authService(bankingEligibilityConfig.store(database), new Argon2Passwords(), Clock.systemUTC(), registrationProfiles,
                    managed, managedBankingConfig, historicalAuthMode);
            migrationResources = com.arman.bank.authservice.infrastructure.LegacyMigrationResources.open(database, auth, migrationConfig).orElse(null);
            var migration = migrationResources;
            var selectedRoutes = new java.util.ArrayList<com.arman.bank.runtime.InternalHttp.DedicatedRoute>();
            if (migration != null) {
                selectedRoutes.add(new com.arman.bank.runtime.InternalHttp.DedicatedRoute("POST", com.arman.bank.authservice.infrastructure.LegacyMigrationFinalizationRoutes.PREFIX + "decision"));
                selectedRoutes.add(new com.arman.bank.runtime.InternalHttp.DedicatedRoute("POST", com.arman.bank.authservice.infrastructure.LegacyMigrationFinalizationRoutes.PREFIX + "publication-evidence"));
            }
            if (revision != null) selectedRoutes.add(new com.arman.bank.runtime.InternalHttp.DedicatedRoute("POST", com.arman.bank.authservice.infrastructure.MigrationUserMutationRoutes.PATH));
            var routes = new AuthRoutes(auth,
                    serviceKey, provider == null ? token -> {
                        throw new com.arman.bank.authservice.application.AuthFailure(com.arman.bank.authservice.application.AuthFailure.Kind.UNAVAILABLE);
                    } : provider);
            java.util.function.BooleanSupplier readiness = database::ready;
            var metrics = new OperationalMetrics();
            var runtime = ServiceRuntime.start("auth-service", Integer.parseInt(System.getenv().getOrDefault("PORT", "8080")),
                    readiness, database, config -> {
                        routes.configure(config, selectedRoutes);
                        if (migration != null) { migration.routes().configure(config); migration.decisionRoutes().configure(config); }
                        if (revision != null) revision.mutationRoutes().configure(config);
                        if (confirmation != null) confirmation.configure(config);
                        if (verificationAuthority != null) verificationAuthority.routes().configure(config);
                        if (loginProofRoutes != null) loginProofRoutes.configure(config);
                        configureMetrics(config, System.getenv(), metrics, readiness);
                    });
            startedRuntime = runtime;
            if (migration != null) migration.startAfterHttpStartup();
            managed.start();
            if (confirmation != null) confirmation.start();
            if (verificationWorker != null) verificationWorker.start();
            if (projectionWorker != null) projectionWorker.start();
            Runtime.getRuntime().addShutdownHook(new Thread(() -> closeResources(null,
                    () -> { if (migration != null) migration.close(); },
                    managed::close,
                    () -> { if (confirmation != null) confirmation.close(); },
                    () -> { if (revision != null) revision.close(); },
                    () -> { if (verificationWorker != null) verificationWorker.close(); },
                    () -> { if (standaloneProjection != null) standaloneProjection.close(); },
                    () -> { if (verificationAuthority != null) verificationAuthority.close(); },
                    runtime::close, registrationProfiles::close,
                    () -> { if (provider != null) provider.close(); }), "auth-shutdown"));
        } catch (Exception | Error failure) {
            var confirmation = confirmationResources;
            var migration = migrationResources;
            var revision = revisionResources;
            var managed = managedResources;
            var authority = authorityResources;
            var projection = verificationProjection;
            var delivery = verificationDelivery;
            var registrationProfiles = profiles;
            var provider = sso;
            var runtime = startedRuntime;
            closeResources(failure,
                    () -> { if (migration != null) migration.close(); },
                    () -> { if (managed != null) managed.close(); },
                    () -> { if (confirmation != null) confirmation.close(); },
                    () -> { if (revision != null) revision.close(); },
                    () -> { if (authority != null) authority.close(); },
                    () -> { if (projection != null) projection.close(); },
                    () -> { if (delivery != null) delivery.close(); },
                    () -> { if (registrationProfiles != null) registrationProfiles.close(); },
                    () -> { if (provider != null) provider.close(); },
                    () -> { if (runtime != null) runtime.close(); else database.close(); });
            throw failure;
        }
    }

    /** Preserve historical SSO only when no classification or managed material was configured.
     * This choice is computed from already validated configuration before any Database allocation. */
    static boolean historicalAuthMode(com.arman.bank.authservice.infrastructure.ManagedBankingConfiguration configuration,
                                      java.util.Map<String,String> environment) {
        return !configuration.enabled && configuration.scopes().managed().isEmpty() && configuration.scopes().legacy().isEmpty()
            && java.util.List.of("AUTH_MANAGED_BANKING_KEY_ID", "AUTH_MANAGED_BANKING_KEY", "AUTH_MANAGED_BANKING_PREVIOUS_KEYS")
                .stream().noneMatch(environment::containsKey);
    }
    static AuthService authService(com.arman.bank.authservice.application.AuthStore store,
            com.arman.bank.authservice.application.PasswordHasher passwords,Clock clock,
            com.arman.bank.authservice.application.RegistrationProfiles profiles,ManagedResources managed,
            com.arman.bank.authservice.infrastructure.ManagedBankingConfiguration configuration,boolean historical) {
        if (historical) {
            if (configuration.enabled || !configuration.scopes().managed().isEmpty() || !configuration.scopes().legacy().isEmpty())
                throw new IllegalArgumentException("Invalid historical Auth selection");
            return new AuthService(store,passwords,clock,profiles);
        }
        return new AuthService(store,passwords,clock,profiles,managed.service(),(issuer,client) ->
            configuration.scopes().classify(issuer,client) == com.arman.bank.authservice.infrastructure.CodeIntentPairScopes.Classification.LEGACY);
    }

    /** Default-off selection allocates no managed DB adapter, HTTP client or scheduler. */
    static ManagedResources openManaged(Database database,
            com.arman.bank.authservice.infrastructure.ManagedBankingConfiguration configuration,
            com.arman.bank.authservice.infrastructure.CodeConfirmationConfiguration confirmation,
            java.util.Map<String,String> environment) {
        return openManaged(database, configuration, confirmation, environment, null, null);
    }

    static ManagedResources openManaged(Database database,
            com.arman.bank.authservice.infrastructure.ManagedBankingConfiguration configuration,
            com.arman.bank.authservice.infrastructure.CodeConfirmationConfiguration confirmation,
            java.util.Map<String,String> environment,
            com.arman.bank.authservice.infrastructure.LegacyMigrationManagedProofCrypto migrationCrypto,
            com.arman.bank.authservice.application.MigrationUserCurrentPort userCurrent) {
        var scope = (com.arman.bank.authservice.application.ManagedBankingService.PairScope)
                (issuer, client) -> configuration.scopes().classify(issuer, client)
                    == com.arman.bank.authservice.infrastructure.CodeIntentPairScopes.Classification.MANAGED;
        if (!configuration.enabled) {
            var unavailable = new com.arman.bank.authservice.application.ManagedBankingStore() {
                @Override public <T>T inRequest(java.util.function.Function<Request,T> operation) {
                    throw new com.arman.bank.authservice.application.AuthFailure(com.arman.bank.authservice.application.AuthFailure.Kind.UNAVAILABLE);
                }
                @Override public void revoke(String digest) {
                    throw new com.arman.bank.authservice.application.AuthFailure(com.arman.bank.authservice.application.AuthFailure.Kind.UNAVAILABLE);
                }
            };
            var service = new com.arman.bank.authservice.application.ManagedBankingService(false, unavailable,
                    (binding, start) -> { throw new com.arman.bank.authservice.application.AuthFailure(com.arman.bank.authservice.application.AuthFailure.Kind.UNAVAILABLE); }, scope);
            return new ManagedResources(service, null, null);
        }
        com.arman.bank.authservice.infrastructure.HttpCodeSessionObservations observer = null;
        try {
            observer = com.arman.bank.authservice.infrastructure.HttpCodeSessionObservations.open(confirmation)
                    .orElseThrow(() -> new IllegalStateException("Managed observer unavailable"));
            var store = new com.arman.bank.authservice.infrastructure.PostgresManagedBankingStore(database,
                    configuration.keys(), scope, environment.get("SSO_LOGIN_PROOF_WRITER_ID"), migrationCrypto, userCurrent);
            var service = new com.arman.bank.authservice.application.ManagedBankingService(true, store, observer, scope);
            var ownedObserver = observer;
            return new ManagedResources(service, store::cleanup, ownedObserver::close);
        } catch (RuntimeException | Error failure) {
            if (observer != null) try { observer.close(); }
            catch (RuntimeException | Error cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    /** Main owns only its selected observer and scheduler; the Database remains externally owned. */
    static final class ManagedResources implements AutoCloseable {
        private final com.arman.bank.authservice.application.ManagedBankingService service;
        private final Runnable cleanup, closeObserver;
        private java.util.concurrent.ScheduledExecutorService executor;
        private boolean closed;
        ManagedResources(com.arman.bank.authservice.application.ManagedBankingService service, Runnable cleanup, Runnable closeObserver) {
            this.service = java.util.Objects.requireNonNull(service);
            this.cleanup = cleanup; this.closeObserver = closeObserver;
            if ((cleanup == null) != (closeObserver == null)) throw new IllegalArgumentException("Incomplete managed resources");
        }
        com.arman.bank.authservice.application.ManagedBankingService service() { return service; }
        synchronized void start() {
            if (closed) throw new IllegalStateException("Managed resources closed");
            if (cleanup == null || executor != null) return;
            executor = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(action -> {
                var thread = new Thread(action, "managed-banking-cleanup"); thread.setDaemon(true); return thread;
            });
            executor.scheduleWithFixedDelay(() -> {
                try { cleanup.run(); }
                catch (RuntimeException unavailable) { /* Missing/stale persisted health denies new allocation. No raw diagnostic. */ }
            }, 0, 10, java.util.concurrent.TimeUnit.SECONDS);
        }
        @Override public synchronized void close() {
            if (closed) return; closed = true;
            var worker = executor; executor = null;
            closeResources(null, () -> {
                if (worker == null) return;
                worker.shutdownNow();
                try {
                    if (!worker.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS))
                        throw new IllegalStateException("Managed cleanup termination unverified");
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt(); throw new IllegalStateException("Managed cleanup termination interrupted");
                }
            }, () -> { if (closeObserver != null) closeObserver.run(); });
        }
    }

    /** Attempt every owned close in order; never replace the original startup failure. */
    private static void closeResources(Throwable primary, Runnable... actions) {
        Throwable failure = primary;
        for (var action : actions) {
            try { action.run(); }
            catch (RuntimeException | Error cleanup) {
                if (failure == null) failure = cleanup;
                else if (failure != cleanup) failure.addSuppressed(cleanup);
            }
        }
        if (primary == null) {
            if (failure instanceof RuntimeException runtime) throw runtime;
            if (failure instanceof Error error) throw error;
        }
    }
}
