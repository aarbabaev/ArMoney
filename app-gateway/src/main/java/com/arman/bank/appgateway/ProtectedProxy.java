package com.arman.bank.appgateway;

import com.arman.bank.runtime.InternalHttp;
import io.javalin.config.JavalinConfig;
import io.javalin.http.Context;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.*;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Semaphore;

/** Revalidates the session for every protected request. No client identity headers are forwarded. */
public final class ProtectedProxy implements AutoCloseable {
    private final URI auth, users, wallets, payments;
    private final String key;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2))
        .followRedirects(HttpClient.Redirect.NEVER).build();
    private final Semaphore slots = new Semaphore(16);

    public ProtectedProxy(URI auth, URI users, URI wallets, String key) {
        this(auth, users, wallets, null, key);
    }
    public ProtectedProxy(URI auth, URI users, URI wallets, URI payments, String key) {
        this.auth = origin(auth); this.users = origin(users); this.wallets = origin(wallets);
        this.payments = payments == null ? null : origin(payments);
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
        config.routes.put("/v1/users/me/phone", ctx -> forward(ctx, users, "/v1/users/me/phone", true));
        config.routes.post("/v1/recipients/resolve", ctx -> forward(ctx, users, "/v1/users/resolve-phone", true));
        config.routes.get("/v1/wallets", ctx -> forward(ctx, wallets, "/v1/wallets", false));
        config.routes.post("/v1/wallets", ctx -> forward(ctx, wallets, "/v1/wallets", true));
        config.routes.get("/v1/wallets/{id}/balance", ctx -> forward(ctx, wallets, "/v1/wallets/" + id(ctx) + "/balance", false));
        if (payments != null) {
            config.routes.post("/v1/payments", ctx -> forward(ctx, payments, "/v1/payments", true));
            config.routes.get("/v1/payments", ctx -> forward(ctx, payments, "/v1/payments", false));
            config.routes.get("/v1/payments/{id}", ctx -> forward(ctx, payments, "/v1/payments/" + id(ctx), false));
            config.routes.get("/v1/notifications", ctx -> forward(ctx, payments, "/v1/notifications", false));
            config.routes.post("/v1/notifications/{id}/read", ctx -> forward(ctx, payments, "/v1/notifications/" + id(ctx) + "/read", false));
        }
    }
    private static String id(Context ctx) {
        String raw = ctx.pathParam("id");
        try {
            String id = UUID.fromString(raw).toString();
            if (!id.equalsIgnoreCase(raw)) throw new IllegalArgumentException();
            return id;
        } catch (IllegalArgumentException e) { throw new io.javalin.http.BadRequestResponse(); }
    }
    private void forward(Context ctx, URI target, String path, boolean hasBody) throws Exception {
        ctx.header("Cache-Control", "no-store");
        String token = ctx.header("Authorization");
        if (token == null || !token.matches("(?i:Bearer) [A-Za-z0-9_-]{43}")) { error(ctx, 401); return; }
        if (!slots.tryAcquire()) { ctx.header("Retry-After", "1"); error(ctx, 429); return; }
        try {
            if (ctx.bodyAsBytes().length > 4096) { error(ctx, 413); return; }
            if (!hasBody && ctx.bodyAsBytes().length != 0) { error(ctx, 400); return; }
            boolean createPayment = path.equals("/v1/payments") && ctx.method().name().equals("POST");
            String idempotencyKey = ctx.header("Idempotency-Key");
            if (createPayment && (idempotencyKey == null || !idempotencyKey.matches("[A-Za-z0-9_-]{1,128}"))) { error(ctx, 400); return; }
            var identity = send(HttpRequest.newBuilder(auth.resolve("/v1/auth/me")).timeout(Duration.ofSeconds(5))
                .header("X-Service-Key", key).header("Authorization", token).GET().build(), 4096, 6);
            if (identity.statusCode() == 401) { error(ctx, 401); return; }
            if (identity.statusCode() != 200) { error(ctx, 503); return; }
            String owner;
            try {
                var data = InternalHttp.JSON.readTree(identity.body());
                String id = data.path("id").asText();
                owner = UUID.fromString(id).toString();
                if (!owner.equalsIgnoreCase(id)) throw new IllegalArgumentException("Invalid identity");
            } catch (Exception invalidIdentity) { error(ctx, 503); return; }
            var builder = HttpRequest.newBuilder(target.resolve(path)).timeout(Duration.ofSeconds(createPayment ? 20 : 8))
                .header("X-Service-Key", key).header("X-Identity-Id", owner);
            if (createPayment) builder.header("Idempotency-Key", idempotencyKey);
            if (hasBody) {
                String type = ctx.contentType();
                if (type == null || !type.split(";", 2)[0].strip().equalsIgnoreCase("application/json")) { error(ctx, 400); return; }
                builder.header("Content-Type", "application/json");
            }
            var response = send(builder.method(ctx.method().name(), hasBody ?
                HttpRequest.BodyPublishers.ofByteArray(ctx.bodyAsBytes()) : HttpRequest.BodyPublishers.noBody()).build(), 131072, createPayment ? 21 : 9);
            int status = response.statusCode();
            // Downstream authentication failures indicate a service configuration problem, not a valid caller.
            boolean pending = status == 202 && (path.equals("/v1/wallets") || createPayment) && ctx.method().name().equals("POST");
            if (status != 200 && !pending && status != 400 && status != 404 && status != 409 && status != 413 && status != 429) { error(ctx, 503); return; }
            if (status == 429) ctx.header("Retry-After", "60");
            ctx.status(status).contentType("application/json").result(new String(response.body(), StandardCharsets.UTF_8));
        } catch (java.io.IOException e) { error(ctx, 503); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); error(ctx, 503); }
        finally { slots.release(); }
    }
    private static void error(Context ctx, int code) {
        InternalHttp.reply(ctx, code, Map.of("error", switch(code) {
            case 401 -> "invalid_credentials";
            case 400 -> "invalid_request";
            case 429 -> "server_busy";
            case 413 -> "request_too_large";
            default -> "service_unavailable";
        }));
    }
    private HttpResponse<byte[]> send(HttpRequest request, int limit, int seconds) throws IOException, InterruptedException {
        var future = client.sendAsync(request, response -> new LimitedBody(limit));
        try { return future.get(seconds, TimeUnit.SECONDS); }
        catch (ExecutionException | TimeoutException e) { future.cancel(true); throw new IOException("Upstream unavailable"); }
        catch (InterruptedException e) { future.cancel(true); throw e; }
    }
    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final HttpResponse.BodySubscriber<byte[]> delegate = HttpResponse.BodySubscribers.ofByteArray();
        private final int limit;
        private Flow.Subscription subscription;
        private int size;
        private boolean failed;
        LimitedBody(int limit) { this.limit = limit; }
        public CompletionStage<byte[]> getBody() { return delegate.getBody(); }
        public void onSubscribe(Flow.Subscription value) { subscription = value; delegate.onSubscribe(value); }
        public void onNext(List<ByteBuffer> items) {
            if (failed) return;
            for (var item : items) {
                if (item.remaining() > limit - size) {
                    failed = true; subscription.cancel(); delegate.onError(new IOException("Upstream response exceeds limit")); return;
                }
                size += item.remaining();
            }
            delegate.onNext(items);
        }
        public void onError(Throwable error) { if (!failed) delegate.onError(error); }
        public void onComplete() { if (!failed) delegate.onComplete(); }
    }
    @Override public void close() { client.close(); }
}
