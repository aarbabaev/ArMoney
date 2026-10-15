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

    /** Server-owned exact route whose handler must authenticate and bound its own raw body stream. */
    public record DedicatedRoute(String method, String path) {
        public DedicatedRoute {
            if (method == null || !Set.of("GET", "POST", "PUT", "PATCH", "DELETE").contains(method)
                || path == null || !path.matches("/v1/[A-Za-z0-9_-]+(?:/[A-Za-z0-9_-]+)*"))
                throw new IllegalArgumentException("Invalid dedicated route");
        }
    }

    public static void configure(JavalinConfig config, String key) {
        configure(config, key, java.util.List.of());
    }

    /** Exemptions are configuration, never inferred from request headers or path prefixes. */
    public static void configure(JavalinConfig config, String key, java.util.List<DedicatedRoute> dedicatedRoutes) {
        if (key == null || key.length() < 32) throw new IllegalArgumentException("Internal service key must have at least 32 characters");
        if (dedicatedRoutes == null) throw new IllegalArgumentException("Dedicated routes required");
        var routes = java.util.List.copyOf(dedicatedRoutes);
        if (new java.util.HashSet<>(routes).size() != routes.size()) throw new IllegalArgumentException("Duplicate dedicated route");
        byte[] expected = key.getBytes(StandardCharsets.UTF_8);
        config.http.maxRequestSize = 4096;
        config.routes.before(ctx -> {
            if (!ctx.path().startsWith("/v1/")) return;
            ctx.header("Cache-Control", "no-store");
            // path() is the raw servlet request URI in pinned Javalin, not a decoded routing match.
            if (routes.stream().anyMatch(route -> route.method().equals(ctx.req().getMethod())
                && route.path().equals(ctx.path()))) return;
            String supplied = ctx.header("X-Service-Key");
            if (supplied == null || supplied.length() > 256 ||
                !MessageDigest.isEqual(expected, supplied.getBytes(StandardCharsets.UTF_8))) throw new UnauthorizedResponse();
            owner(ctx);
            // Cached by Javalin; enforce even unknown-length/chunked bodies before ordinary handlers.
            if (!routes.isEmpty()) ctx.bodyAsBytes();
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
