package com.arman.bank.authservice.infrastructure;

import com.arman.bank.authservice.application.AuthFailure;
import com.arman.bank.authservice.application.AuthService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.config.JavalinConfig;
import io.javalin.http.Context;
import io.javalin.http.HttpResponseException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.concurrent.Semaphore;
import static com.arman.bank.authservice.application.AuthFailure.Kind.BAD_INPUT;

public final class AuthRoutes {
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final AuthService service;
    private final com.arman.bank.authservice.application.SsoTokens sso;
    private final byte[] serviceKey;
    private final Semaphore hashingSlots = new Semaphore(2);

    public AuthRoutes(AuthService service, String serviceKey) {
        this(service, serviceKey, token -> { throw new AuthFailure(AuthFailure.Kind.UNAVAILABLE); });
    }

    public AuthRoutes(AuthService service, String serviceKey, com.arman.bank.authservice.application.SsoTokens sso) {
        this.sso = sso;
        if (serviceKey == null || serviceKey.length() < 32) throw new IllegalArgumentException("INTERNAL_AUTH_KEY must have at least 32 characters");
        this.service = service;
        this.serviceKey = serviceKey.getBytes(StandardCharsets.UTF_8);
    }

    public void configure(JavalinConfig config) {
        config.http.maxRequestSize = 12288;
        config.routes.before(ctx -> {
            if (ctx.path().startsWith("/v1/")) {
                ctx.header("Cache-Control", "no-store");
                var supplied = ctx.header("X-Service-Key");
                if (supplied == null || supplied.length() > 256 ||
                        !MessageDigest.isEqual(serviceKey, supplied.getBytes(StandardCharsets.UTF_8)))
                    throw new io.javalin.http.UnauthorizedResponse();
            }
        });
        config.routes.exception(AuthFailure.class, (error, ctx) -> {
            int status = switch (error.kind()) {
                case BAD_INPUT -> 400;
                case UNAUTHORIZED -> 401;
                case CONFLICT -> 409;
                case RATE_LIMITED -> 429;
                case UNAVAILABLE -> 503;
            };
            if (status == 429) ctx.header("Retry-After", "900");
            respond(ctx, status, Map.of("error", switch (status) {
                case 400 -> "invalid_request";
                case 409 -> "registration_conflict";
                case 429 -> "too_many_attempts";
                case 503 -> "service_unavailable";
                default -> "invalid_credentials";
            }));
        });
        config.routes.exception(HttpResponseException.class, (error, ctx) ->
                respond(ctx, error.getStatus(), Map.of("error", "request_rejected")));
        config.routes.exception(Exception.class, (error, ctx) ->
                respond(ctx, 503, Map.of("error", "service_unavailable")));
        config.routes.post("/v1/auth/register", ctx -> withHashSlot(ctx, () -> {
            var body = credentials(ctx, true);
            service.register(body.get("email").asText(), body.get("password").asText(), body.get("phone_number").textValue());
            respond(ctx, 202, Map.of("message", "registration_processed"));
        }));
        config.routes.post("/v1/auth/login", ctx -> withHashSlot(ctx, () -> {
            var body = credentials(ctx, false);
            var session = service.login(body.get("email").asText(), body.get("password").asText());
            respond(ctx, 200, Map.of("access_token", session.accessToken(), "token_type", "Bearer",
                    "expires_in", 1800, "expires_at", session.expiresAt().toString()));
        }));
        config.routes.post("/v1/auth/sso", ctx -> {
            var body = com.arman.bank.runtime.InternalHttp.body(ctx, "access_token");
            var session = service.sso(body.get("access_token").textValue(), sso);
            respond(ctx, 200, Map.of("access_token", session.accessToken(), "token_type", "Bearer",
                    "expires_in", 1800, "expires_at", session.expiresAt().toString()));
        });
        config.routes.get("/v1/auth/me", ctx -> {
            var identity = service.me(ctx.header("Authorization"));
            var response = new java.util.LinkedHashMap<String, Object>();
            response.put("id", identity.id().toString());
            response.put("email", identity.email());
            respond(ctx, 200, response);
        });
        config.routes.post("/v1/auth/logout", ctx -> {
            service.logout(ctx.header("Authorization"));
            ctx.status(204);
        });
    }

    private void withHashSlot(Context ctx, Runnable action) {
        if (!hashingSlots.tryAcquire()) {
            ctx.header("Retry-After", "1");
            respond(ctx, 429, Map.of("error", "server_busy"));
            return;
        }
        try { action.run(); } finally { hashingSlots.release(); }
    }

    private static JsonNode credentials(Context ctx, boolean registration) {
        if (ctx.bodyAsBytes().length > 4096) throw new io.javalin.http.HttpResponseException(413, "Request too large");
        var type = ctx.contentType();
        if (type == null || !type.split(";", 2)[0].strip().equalsIgnoreCase("application/json"))
            throw new AuthFailure(BAD_INPUT);
        try {
            var body = JSON.readTree(ctx.body());
            if (body == null || !body.isObject() || body.size() != (registration ? 3 : 2) ||
                    !body.hasNonNull("email") || !body.get("email").isTextual() ||
                    !body.hasNonNull("password") || !body.get("password").isTextual() ||
                    (registration && (!body.hasNonNull("phone_number") || !body.get("phone_number").isTextual())))
                throw new AuthFailure(BAD_INPUT);
            return body;
        } catch (JsonProcessingException e) { throw new AuthFailure(BAD_INPUT); }
    }

    private static void respond(Context ctx, int status, Map<String, ?> body) {
        try { ctx.status(status).contentType("application/json").result(JSON.writeValueAsString(body)); }
        catch (JsonProcessingException e) { throw new IllegalStateException("Response serialization failed"); }
    }
}
