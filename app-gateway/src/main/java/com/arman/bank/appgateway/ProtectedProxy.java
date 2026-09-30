package com.arman.bank.appgateway;

import com.arman.bank.runtime.InternalHttp;
import io.javalin.config.JavalinConfig;
import io.javalin.http.Context;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Semaphore;

/** Revalidates the session for every protected request. No client identity headers are forwarded. */
public final class ProtectedProxy implements AutoCloseable {
    private final URI auth, users, wallets;
    private final String key;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2))
        .followRedirects(HttpClient.Redirect.NEVER).build();
    private final Semaphore slots = new Semaphore(16);

    public ProtectedProxy(URI auth, URI users, URI wallets, String key) {
        this.auth = origin(auth); this.users = origin(users); this.wallets = origin(wallets);
        if (key == null || key.length() < 32) throw new IllegalArgumentException("Internal key required");
        this.key = key;
    }
    private static URI origin(URI uri) {
        if (!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme())) || uri.getHost() == null ||
            uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null ||
            !(uri.getPath().isEmpty() || uri.getPath().equals("/"))) throw new IllegalArgumentException("Expected HTTP origin");
        return uri;
    }
    public void configure(JavalinConfig config) {
        config.routes.get("/v1/users/me", ctx -> forward(ctx, users, "/v1/users/me", false));
        config.routes.put("/v1/users/me", ctx -> forward(ctx, users, "/v1/users/me", true));
        config.routes.get("/v1/wallets", ctx -> forward(ctx, wallets, "/v1/wallets", false));
        config.routes.post("/v1/wallets", ctx -> forward(ctx, wallets, "/v1/wallets", true));
    }
    private void forward(Context ctx, URI target, String path, boolean hasBody) throws Exception {
        ctx.header("Cache-Control", "no-store");
        String token = ctx.header("Authorization");
        if (token == null || !token.matches("(?i:Bearer) [A-Za-z0-9_-]{43}")) { error(ctx, 401); return; }
        if (!slots.tryAcquire()) { ctx.header("Retry-After", "1"); error(ctx, 429); return; }
        try {
            var identity = client.send(HttpRequest.newBuilder(auth.resolve("/v1/auth/me")).timeout(Duration.ofSeconds(5))
                .header("X-Service-Key", key).header("Authorization", token).GET().build(), HttpResponse.BodyHandlers.ofString());
            if (identity.statusCode() == 401) { error(ctx, 401); return; }
            if (identity.statusCode() != 200) { error(ctx, 503); return; }
            String owner;
            try {
                var data = InternalHttp.JSON.readTree(identity.body());
                String id = data.path("id").asText();
                owner = UUID.fromString(id).toString();
                if (!owner.equalsIgnoreCase(id)) throw new IllegalArgumentException("Invalid identity");
            } catch (Exception invalidIdentity) { error(ctx, 503); return; }
            var builder = HttpRequest.newBuilder(target.resolve(path)).timeout(Duration.ofSeconds(5))
                .header("X-Service-Key", key).header("X-Identity-Id", owner);
            if (hasBody) {
                String type = ctx.contentType();
                if (type == null || !type.split(";", 2)[0].strip().equalsIgnoreCase("application/json")) { error(ctx, 400); return; }
                builder.header("Content-Type", "application/json");
            }
            var response = client.send(builder.method(ctx.method().name(), hasBody ?
                HttpRequest.BodyPublishers.ofByteArray(ctx.bodyAsBytes()) : HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofString());
            int status = response.statusCode();
            // Downstream authentication failures indicate a service configuration problem, not a valid caller.
            if (status != 200 && status != 400 && status != 404 && status != 413) { error(ctx, 503); return; }
            ctx.status(status).contentType("application/json").result(response.body());
        } catch (java.io.IOException e) { error(ctx, 503); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); error(ctx, 503); }
        finally { slots.release(); }
    }
    private static void error(Context ctx, int code) {
        InternalHttp.reply(ctx, code, Map.of("error", switch(code) {
            case 401 -> "invalid_credentials";
            case 400 -> "invalid_request";
            case 429 -> "server_busy";
            default -> "service_unavailable";
        }));
    }
    @Override public void close() { client.close(); }
}
