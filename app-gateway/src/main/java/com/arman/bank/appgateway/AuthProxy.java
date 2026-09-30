package com.arman.bank.appgateway;

import io.javalin.config.JavalinConfig;
import io.javalin.http.Context;
import io.javalin.http.HttpResponseException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.Semaphore;

/** Fixed route allowlist; never forwards client-supplied identity or service credentials. */
public final class AuthProxy implements AutoCloseable {
    private final URI upstream;
    private final String serviceKey;
    private final Clock clock;
    private final HttpClient client;
    private final Semaphore requests = new Semaphore(16);
    private long window;
    private int attempts;

    public AuthProxy(URI upstream, String serviceKey, Clock clock) {
        if (!("http".equals(upstream.getScheme()) || "https".equals(upstream.getScheme())) ||
                upstream.getHost() == null || upstream.getUserInfo() != null || upstream.getQuery() != null ||
                upstream.getFragment() != null || !(upstream.getPath().isEmpty() || upstream.getPath().equals("/")))
            throw new IllegalArgumentException("AUTH_BASE_URL must be an HTTP origin");
        if (serviceKey == null || serviceKey.length() < 32) throw new IllegalArgumentException("INTERNAL_AUTH_KEY must have at least 32 characters");
        this.upstream = upstream;
        this.serviceKey = serviceKey;
        this.clock = clock;
        client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).followRedirects(HttpClient.Redirect.NEVER).build();
    }

    public void configure(JavalinConfig config) {
        config.http.maxRequestSize = 12288;
        config.routes.before(ctx -> {
            if (ctx.path().startsWith("/v1/")) {
                ctx.header("Cache-Control", "no-store");
                if (!ctx.path().equals("/v1/auth/sso") && ctx.bodyAsBytes().length > 4096)
                    throw new io.javalin.http.HttpResponseException(413, "Request too large");
            }
        });
        config.routes.exception(HttpResponseException.class, (error, ctx) -> error(ctx, error.getStatus(), "request_rejected"));
        config.routes.exception(Exception.class, (exception, ctx) -> error(ctx, 503, "service_unavailable"));
        config.routes.post("/v1/auth/register", ctx -> forward(ctx, "/v1/auth/register", true));
        config.routes.post("/v1/auth/sso", ctx -> forward(ctx, "/v1/auth/sso", true));
        config.routes.post("/v1/auth/login", ctx -> forward(ctx, "/v1/auth/login", true));
        config.routes.get("/v1/auth/me", ctx -> forward(ctx, "/v1/auth/me", false));
        config.routes.post("/v1/auth/logout", ctx -> forward(ctx, "/v1/auth/logout", false));
    }

    private synchronized boolean allowAttempt() {
        long current = clock.instant().getEpochSecond() / 60;
        if (current != window) { window = current; attempts = 0; }
        return ++attempts <= 60;
    }

    private void forward(Context ctx, String path, boolean credentials) throws Exception {
        if (credentials && !allowAttempt()) {
            ctx.header("Retry-After", "60");
            error(ctx, 429, "too_many_attempts");
            return;
        }
        if (!requests.tryAcquire()) {
            ctx.header("Retry-After", "1");
            error(ctx, 429, "server_busy");
            return;
        }
        try {
            var request = HttpRequest.newBuilder(upstream.resolve(path)).timeout(Duration.ofSeconds(5))
                    .header("X-Service-Key", serviceKey);
            String authorization = ctx.header("Authorization");
            if (authorization != null) {
                if (authorization.length() > 128) { error(ctx, 401, "invalid_credentials"); return; }
                request.header("Authorization", authorization);
            }
            if (credentials) {
                var type = ctx.contentType();
                if (type == null || !type.split(";", 2)[0].strip().equalsIgnoreCase("application/json")) {
                    error(ctx, 400, "invalid_request"); return;
                }
                request.header("Content-Type", "application/json");
            }
            request.method(ctx.method().name(), credentials ? HttpRequest.BodyPublishers.ofByteArray(ctx.bodyAsBytes())
                    : HttpRequest.BodyPublishers.noBody());
            var response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
            response.headers().firstValue("Retry-After").ifPresent(value -> ctx.header("Retry-After", value));
            ctx.status(response.statusCode());
            if (response.statusCode() != 204) ctx.contentType("application/json").result(response.body());
        } catch (java.io.IOException e) {
            error(ctx, 503, "auth_unavailable");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            error(ctx, 503, "auth_unavailable");
        } finally { requests.release(); }
    }

    private static void error(Context ctx, int status, String code) {
        ctx.status(status).contentType("application/json").result("{\"error\":\"" + code + "\"}");
    }

    @Override public void close() { client.close(); }
}
