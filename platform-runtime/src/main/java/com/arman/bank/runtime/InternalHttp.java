package com.arman.bank.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.config.JavalinConfig;
import io.javalin.http.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Set;
import java.util.UUID;

/** Technical HTTP plumbing for private service endpoints; contains no business model. */
public final class InternalHttp {
    public static final ObjectMapper JSON = new ObjectMapper()
        .enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
        .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private InternalHttp() {}

    public static void configure(JavalinConfig config, String key) {
        if (key == null || key.length() < 32) throw new IllegalArgumentException("Internal service key must have at least 32 characters");
        byte[] expected = key.getBytes(StandardCharsets.UTF_8);
        config.http.maxRequestSize = 4096;
        config.routes.before(ctx -> {
            if (!ctx.path().startsWith("/v1/")) return;
            ctx.header("Cache-Control", "no-store");
            String supplied = ctx.header("X-Service-Key");
            if (supplied == null || supplied.length() > 256 ||
                !MessageDigest.isEqual(expected, supplied.getBytes(StandardCharsets.UTF_8))) throw new UnauthorizedResponse();
            owner(ctx);
        });
        config.routes.exception(HttpResponseException.class, (e, ctx) -> reply(ctx, e.getStatus(), java.util.Map.of("error", "request_rejected")));
        config.routes.exception(IllegalArgumentException.class, (e, ctx) -> reply(ctx, 400, java.util.Map.of("error", "invalid_request")));
        config.routes.exception(Exception.class, (e, ctx) -> reply(ctx, 503, java.util.Map.of("error", "service_unavailable")));
    }

    public static UUID owner(Context ctx) {
        String value = ctx.header("X-Identity-Id");
        if (value == null || !value.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"))
            throw new UnauthorizedResponse();
        return UUID.fromString(value);
    }

    public static JsonNode body(Context ctx, String... names) {
        String type = ctx.contentType();
        if (type == null || !type.split(";", 2)[0].strip().equalsIgnoreCase("application/json")) throw new BadRequestResponse();
        try {
            JsonNode result = JSON.readTree(ctx.body());
            if (result == null || !result.isObject() || result.size() != names.length) throw new BadRequestResponse();
            for (String name : names) if (!result.hasNonNull(name) || !result.get(name).isTextual()) throw new BadRequestResponse();
            return result;
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) { throw new BadRequestResponse(); }
    }

    public static void reply(Context ctx, int status, Object value) {
        try { ctx.status(status).contentType("application/json").result(JSON.writeValueAsString(value)); }
        catch (com.fasterxml.jackson.core.JsonProcessingException e) { throw new IllegalStateException("Response serialization failed"); }
    }
}
