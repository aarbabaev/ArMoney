package com.arman.bank.userservice.infrastructure;
import com.arman.bank.runtime.InternalHttp;
import com.arman.bank.userservice.application.ProfileService;
import com.arman.bank.userservice.domain.Profile;
import io.javalin.config.JavalinConfig;
import io.javalin.http.NotFoundResponse;
import java.util.Map;
public final class ProfileRoutes {
    private final ProfileService service;
    private final String key;
    public ProfileRoutes(ProfileService service, String key) { this.service = service; this.key = key; }
    public void configure(JavalinConfig config) {
        InternalHttp.configure(config, key);
        config.routes.put("/v1/users/me", ctx -> {
            var profile = service.save(InternalHttp.owner(ctx), InternalHttp.body(ctx, "display_name").get("display_name").asText());
            InternalHttp.reply(ctx, 200, view(profile));
        });
        config.routes.get("/v1/users/me", ctx -> InternalHttp.reply(ctx, 200,
            view(service.find(InternalHttp.owner(ctx)).orElseThrow(NotFoundResponse::new))));
    }
    private static Map<String, String> view(Profile p) {
        return Map.of("id", p.id().toString(), "identity_id", p.identityId().toString(), "display_name", p.displayName());
    }
}
