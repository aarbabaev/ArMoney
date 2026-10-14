package com.arman.bank.smssandbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.type.LogicalType;
import io.javalin.Javalin;
import io.javalin.http.Context;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.Map;
import java.util.concurrent.*;

public final class Main implements AutoCloseable {
    private final Inbox inbox;
    private final Javalin app;
    private final ScheduledExecutorService maintenance = Executors.newSingleThreadScheduledExecutor();
    private volatile boolean healthy = true;
    private long window = System.nanoTime();
    private int requests;
    private final ObjectMapper json = strictRequestMapper();
    public record Request(String destination, String text, String purpose) {}

    public Main(Path directory, String sendToken, String readToken, int port) throws Exception {
        validateTokens(sendToken, readToken);
        inbox = new Inbox(directory, Clock.systemUTC());
        app = Javalin.create(config -> {
            config.http.maxRequestSize = 8192;
            config.routes.before(ctx -> {
                ctx.header("Cache-Control", "no-store");
                ctx.header("X-Content-Type-Options", "nosniff");
                ctx.header("Referrer-Policy", "no-referrer");
                ctx.header("Content-Security-Policy", "default-src 'none'; script-src 'self'; style-src 'self'; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'");
                if (ctx.path().startsWith("/api/")) {
                    if (!permit()) throw new Inbox.Rejection(429, "Rate limit exceeded");
                    String expected = ctx.method().name().equals("POST") ? sendToken : readToken;
                    if (!authorized(ctx.header("Authorization"), expected)) throw new Inbox.Rejection(401, "Unauthorized");
                    if (!healthy) throw new Inbox.Rejection(503, "Storage unavailable");
                }
            });
            config.routes.get("/health/live", ctx -> ctx.json(Map.of("status", "UP")));
            config.routes.get("/health/ready", ctx -> ctx.status(healthy ? 200 : 503).json(Map.of("status", healthy ? "UP" : "DOWN")));
            config.routes.get("/", ctx -> resource(ctx, "/index.html", "text/html"));
            config.routes.get("/inbox.js", ctx -> resource(ctx, "/inbox.js", "text/javascript"));
            config.routes.get("/inbox.css", ctx -> resource(ctx, "/inbox.css", "text/css"));
            config.routes.post("/api/messages", ctx -> {
                String body = ctx.body();
                Request request;
                try { request = json.readValue(body, Request.class); }
                catch (Exception invalid) { throw new Inbox.Rejection(400, "Invalid JSON body"); }
                if (request == null) throw new Inbox.Rejection(400, "Invalid JSON body");
                var submitted = inbox.submit(ctx.header("Idempotency-Key"), request.destination(), request.text(), request.purpose());
                // Sender receives metadata only; it cannot use this endpoint to retrieve an OTP.
                ctx.status(submitted.replay() ? 200 : 201).json(Map.of("id", submitted.message().id(),
                        "createdAt", submitted.message().createdAt(), "replay", submitted.replay()));
            });
            config.routes.get("/api/messages", ctx -> ctx.json(inbox.list(ctx.queryParam("q"), ctx.queryParam("purpose"))));
            config.routes.delete("/api/messages", ctx -> {
                if (!"true".equals(ctx.header("X-Confirm-Clear"))) throw new Inbox.Rejection(400, "Clear confirmation required");
                inbox.clear();
                ctx.status(204);
            });
            config.routes.exception(Inbox.Rejection.class, (failure, ctx) -> ctx.status(failure.status).json(Map.of("error", failure.getMessage())));
            config.routes.exception(Exception.class, (failure, ctx) -> {
                // Deliberately omit exception and request data from logs and responses.
                healthy = false;
                ctx.status(503).json(Map.of("error", "Storage unavailable"));
            });
        });
        try { app.start(port); }
        catch (Exception failure) { inbox.close(); maintenance.shutdownNow(); throw failure; }
        maintenance.scheduleWithFixedDelay(() -> {
            try { inbox.prune(); } catch (Exception failure) { healthy = false; }
        }, 1, 1, TimeUnit.MINUTES);
    }

    private synchronized boolean permit() {
        long now = System.nanoTime();
        if (now - window >= TimeUnit.MINUTES.toNanos(1)) { window = now; requests = 0; }
        if (requests >= 120) return false;
        requests++;
        return true;
    }
    private static ObjectMapper strictRequestMapper() {
        var mapper = new ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        var strings = mapper.coercionConfigFor(LogicalType.Textual);
        for (var shape : new CoercionInputShape[]{CoercionInputShape.Integer, CoercionInputShape.Float,
                CoercionInputShape.Boolean}) strings.setCoercion(shape, CoercionAction.Fail);
        return mapper;
    }
    private static boolean authorized(String header, String expected) {
        if (header == null || header.length() > 1024) return false;
        return MessageDigest.isEqual(header.getBytes(StandardCharsets.UTF_8), ("Bearer " + expected).getBytes(StandardCharsets.UTF_8));
    }
    private static void validateTokens(String sender, String reader) {
        if (sender == null || reader == null || !sender.matches("[A-Za-z0-9_-]{32,128}")
                || !reader.matches("[A-Za-z0-9_-]{32,128}") || sender.equals(reader))
            throw new IllegalArgumentException("Distinct sandbox sender and reader tokens of 32-128 URL-safe characters required");
    }
    private void resource(Context context, String path, String type) throws Exception {
        try (var stream = Main.class.getResourceAsStream(path)) {
            context.contentType(type).result(new String(java.util.Objects.requireNonNull(stream).readAllBytes(), StandardCharsets.UTF_8));
        }
    }
    public int port() { return app.port(); }
    @Override public void close() throws Exception {
        maintenance.shutdownNow();
        app.stop();
        inbox.close();
    }
    public static void main(String[] args) throws Exception {
        if (args.length == 1 && args[0].equals("health")) {
            int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "8080"));
            try (var client = java.net.http.HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(2)).build()) {
                var request = java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:" + port + "/health/ready"))
                        .timeout(java.time.Duration.ofSeconds(3)).GET().build();
                System.exit(client.send(request, java.net.http.HttpResponse.BodyHandlers.discarding()).statusCode() == 200 ? 0 : 1);
            } catch (Exception unavailable) { System.exit(1); }
            return;
        }
        var runtime = new Main(Path.of(System.getenv().getOrDefault("SMS_SANDBOX_DATA_DIR", "/data")),
                System.getenv("SMS_SANDBOX_SEND_TOKEN"), System.getenv("SMS_SANDBOX_READ_TOKEN"),
                Integer.parseInt(System.getenv().getOrDefault("PORT", "8080")));
        Runtime.getRuntime().addShutdownHook(new Thread(() -> { try { runtime.close(); } catch (Exception ignored) {} }));
    }
}
