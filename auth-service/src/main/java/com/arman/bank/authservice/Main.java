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
        var database = new Database(ServiceRuntime.required("DB_URL"), ServiceRuntime.required("DB_USER"), ServiceRuntime.required("DB_PASSWORD"));
        com.arman.bank.authservice.infrastructure.KeycloakTokens sso = null;
        com.arman.bank.authservice.infrastructure.HttpRegistrationProfiles profiles = null;
        com.arman.bank.authservice.infrastructure.VerificationDeliveryWorker verificationDelivery = null;
        com.arman.bank.authservice.infrastructure.VerificationProjectionWorker verificationProjection = null;
        com.arman.bank.authservice.infrastructure.VerificationAuthorityConfiguration.Resources authorityResources = null;
        ServiceRuntime startedRuntime = null;
        try {
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
            authorityResources = authorityConfig.open(database);
            var verificationAuthority = authorityResources;
            if (authorityConfig.completionEnabled && authorityResources == null)
                throw new IllegalStateException("Authority completion resources unavailable");
            verificationProjection = authorityConfig.completionEnabled ? null : projectionConfig.worker(database);
            var standaloneProjection = verificationProjection;
            var projectionWorker = authorityConfig.completionEnabled ? authorityResources.projectionWorker() : standaloneProjection;
            var routes = new AuthRoutes(new AuthService(bankingEligibilityConfig.store(database), new Argon2Passwords(), Clock.systemUTC(), registrationProfiles),
                    serviceKey, provider == null ? token -> {
                        throw new com.arman.bank.authservice.application.AuthFailure(com.arman.bank.authservice.application.AuthFailure.Kind.UNAVAILABLE);
                    } : provider);
            java.util.function.BooleanSupplier readiness = database::ready;
            var metrics = new OperationalMetrics();
            var runtime = ServiceRuntime.start("auth-service", Integer.parseInt(System.getenv().getOrDefault("PORT", "8080")),
                    readiness, database, config -> {
                        routes.configure(config);
                        if (verificationAuthority != null) verificationAuthority.routes().configure(config);
                        configureMetrics(config, System.getenv(), metrics, readiness);
                    });
            startedRuntime = runtime;
            if (verificationWorker != null) verificationWorker.start();
            if (projectionWorker != null) projectionWorker.start();
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try { if (verificationWorker != null) verificationWorker.close(); }
                finally {
                    try { if (standaloneProjection != null) standaloneProjection.close(); }
                    finally {
                        try { if (verificationAuthority != null) verificationAuthority.close(); }
                        finally { runtime.close(); registrationProfiles.close(); if (provider != null) provider.close(); }
                    }
                }
            }, "auth-shutdown"));
        } catch (Exception e) {
            if (authorityResources != null) authorityResources.close();
            if (verificationProjection != null) verificationProjection.close();
            if (verificationDelivery != null) verificationDelivery.close();
            if (profiles != null) profiles.close();
            if (sso != null) sso.close();
            if (startedRuntime != null) startedRuntime.close(); else database.close();
            throw e;
        }
    }
}
