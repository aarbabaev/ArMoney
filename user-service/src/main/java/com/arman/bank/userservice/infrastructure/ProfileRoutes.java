package com.arman.bank.userservice.infrastructure;
import com.arman.bank.runtime.InternalHttp;
import com.arman.bank.userservice.application.ProfileService;
import com.arman.bank.userservice.domain.Profile;
import io.javalin.config.JavalinConfig;
import io.javalin.http.NotFoundResponse;
import java.util.Map;
import java.util.LinkedHashMap;
import com.arman.bank.userservice.application.LookupLimitExceeded;
import com.arman.bank.userservice.application.RegistrationConflict;
import com.arman.bank.userservice.application.MigrationBaselineStore;
public final class ProfileRoutes {
    private final ProfileService service;
    private final String key;
    private final VerificationProjectionRoutes projection;
    private final MigrationBaselineRoutes migration;
    public ProfileRoutes(ProfileService service, String key) {
        this(service, key, new VerificationProjectionConfiguration(false, Map.of()), null);
    }
    public ProfileRoutes(ProfileService service, String key, VerificationProjectionConfiguration configuration,
            com.arman.bank.userservice.application.VerificationProjectionStore store) {
        this(service, key, configuration, store, MigrationBaselineConfiguration.from(Map.of()), null);
    }
    public ProfileRoutes(ProfileService service, String key, VerificationProjectionConfiguration configuration,
            com.arman.bank.userservice.application.VerificationProjectionStore store,
            MigrationBaselineConfiguration migrationConfiguration, MigrationBaselineStore migrationStore) {
        this.service = java.util.Objects.requireNonNull(service);
        this.key = key;
        java.util.Objects.requireNonNull(configuration);
        if (configuration.enabled() && configuration.writers().containsValue(key))
            throw new IllegalArgumentException("Projection credential must be distinct");
        this.projection = configuration.enabled()
            ? new VerificationProjectionRoutes(java.util.Objects.requireNonNull(store), configuration) : null;
        java.util.Objects.requireNonNull(migrationConfiguration);
        if (migrationConfiguration.enabled()) {
            var ordinaryKeys = new java.util.ArrayList<String>();
            ordinaryKeys.add(key);
            ordinaryKeys.addAll(configuration.writers().values());
            var migrationKeys = new java.util.ArrayList<String>();
            migrationConfiguration.stageWriters().values().forEach(migrationKeys::addAll);
            migrationConfiguration.publicationWriters().values().forEach(migrationKeys::addAll);
            migrationKeys.add(migrationConfiguration.decisionKey());
            for (String purposeKey : migrationKeys) for (String ordinaryKey : ordinaryKeys)
                if (!java.util.Collections.disjoint(keyAliases(purposeKey), keyAliases(ordinaryKey)))
                    throw new IllegalArgumentException("Migration credential must be distinct");
        }
        this.migration = migrationConfiguration.enabled()
            ? new MigrationBaselineRoutes(java.util.Objects.requireNonNull(migrationStore), migrationConfiguration) : null;
    }
    private static java.util.Set<String> keyAliases(String key) {
        var aliases = new java.util.HashSet<String>();
        aliases.add(java.util.Base64.getEncoder().encodeToString(key.getBytes(java.nio.charset.StandardCharsets.US_ASCII)));
        for (var decoder : java.util.List.of(java.util.Base64.getDecoder(), java.util.Base64.getUrlDecoder())) {
            try { aliases.add(java.util.Base64.getEncoder().encodeToString(decoder.decode(key))); }
            catch (IllegalArgumentException ignored) { }
        }
        return aliases;
    }
    public void configure(JavalinConfig config) {
        configure(config, java.util.List.of());
    }
    /** Extra exemptions are explicit server configuration; their purpose handler must be registered separately. */
    public void configure(JavalinConfig config, java.util.List<InternalHttp.DedicatedRoute> selected) {
        if (selected == null) throw new IllegalArgumentException("Dedicated routes required");
        var extra = java.util.List.copyOf(selected);
        var allowed = java.util.Set.of(new InternalHttp.DedicatedRoute("POST", LiveUserCurrentRoutes.PATH));
        if (new java.util.HashSet<>(extra).size() != extra.size() || !allowed.containsAll(extra))
            throw new IllegalArgumentException("Unrecognized dedicated route");
        var dedicated = new java.util.ArrayList<InternalHttp.DedicatedRoute>();
        dedicated.addAll(extra);
        if (projection != null) dedicated.add(new InternalHttp.DedicatedRoute("POST", VerificationProjectionRoutes.PATH));
        if (migration != null) for (String action : java.util.List.of("user-stage", "user-publish", "user-publication-result", "user-resolve"))
            dedicated.add(new InternalHttp.DedicatedRoute("POST", MigrationBaselineRoutes.PREFIX + action));
        InternalHttp.configure(config, key, dedicated);
        if (projection != null) projection.configure(config);
        if (migration != null) migration.configure(config);
        config.routes.exception(MigrationBaselineStore.Rejected.class, (e, ctx) -> {
            ctx.header("Cache-Control", "no-store");
            if (e.status() == 429) ctx.header("Retry-After", "60");
            InternalHttp.reply(ctx, e.status(), Map.of("error", e.status() == 503 ? "unavailable" : "rejected"));
        });
        config.routes.exception(RegistrationConflict.class, (e, ctx) ->
            InternalHttp.reply(ctx, 409, Map.of("error", "registration_phone_conflict")));
        config.routes.put("/internal/registrations/me", ctx -> {
            ctx.header("Cache-Control", "no-store");
            String supplied = ctx.header("X-Service-Key");
            if (supplied == null || supplied.length() > 256 || !java.security.MessageDigest.isEqual(
                key.getBytes(java.nio.charset.StandardCharsets.UTF_8), supplied.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                throw new io.javalin.http.UnauthorizedResponse();
            var identity = InternalHttp.owner(ctx);
            service.registerPhone(identity, InternalHttp.body(ctx, "phone_number").get("phone_number").textValue());
            ctx.status(204);
        });
        config.routes.exception(LookupLimitExceeded.class, (e, ctx) -> {
            ctx.header("Retry-After", "60");
            InternalHttp.reply(ctx, 429, Map.of("error", "lookup_limit_exceeded"));
        });
        config.routes.put("/v1/users/me", ctx -> {
            if (java.util.Collections.list(ctx.req().getHeaders("X-Identity-Email")).size() > 1) throw new IllegalArgumentException("Ambiguous identity email");
            var profile = service.save(InternalHttp.owner(ctx), InternalHttp.body(ctx, "display_name").get("display_name").asText(), ctx.header("X-Identity-Email"));
            InternalHttp.reply(ctx, 200, view(profile));
        });
        config.routes.get("/v1/users/me", ctx -> InternalHttp.reply(ctx, 200,
            view(service.find(InternalHttp.owner(ctx)).orElseThrow(NotFoundResponse::new))));
        config.routes.put("/v1/users/me/phone", ctx -> InternalHttp.reply(ctx, 200,
            view(service.changePhone(InternalHttp.owner(ctx), InternalHttp.body(ctx, "phone_number").get("phone_number").textValue())
                .orElseThrow(NotFoundResponse::new))));
        config.routes.post("/v1/users/resolve-phone", ctx -> {
            var profile = service.resolvePhone(InternalHttp.owner(ctx), InternalHttp.body(ctx, "phone_number").get("phone_number").textValue())
                .orElseThrow(NotFoundResponse::new);
            InternalHttp.reply(ctx, 200, Map.of("identity_id", profile.identityId().toString(), "display_name", profile.displayName(), "phone_number", profile.phoneNumber()));
        });
    }
    private static Map<String, Object> view(Profile p) {
        var result = new LinkedHashMap<String, Object>();
        result.put("id", p.id().toString()); result.put("identity_id", p.identityId().toString()); result.put("display_name", p.displayName());
        result.put("phone_number", p.phoneNumber()); result.put("phone_verified", p.phoneVerified());
        return result;
    }
}
