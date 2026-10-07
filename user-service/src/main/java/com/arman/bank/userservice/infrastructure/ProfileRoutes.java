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
public final class ProfileRoutes {
    private final ProfileService service;
    private final String key;
    public ProfileRoutes(ProfileService service, String key) { this.service = service; this.key = key; }
    public void configure(JavalinConfig config) {
        InternalHttp.configure(config, key);
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
