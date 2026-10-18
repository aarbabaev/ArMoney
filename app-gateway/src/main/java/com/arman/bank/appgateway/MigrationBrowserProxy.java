package com.arman.bank.appgateway;

import io.javalin.config.JavalinConfig;
import io.javalin.http.Context;
import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/** Dedicated browser transport. No account identity, financial command or JSON auth behavior lives here. */
public final class MigrationBrowserProxy implements AutoCloseable {
    private static final Set<String> GET = Set.of("/migration/legacy", "/migration/legacy/account-oidc/callback", "/migration/managed/callback",
        "/migration/legacy/verify/status");
    private static final Set<String> POST = Set.of("/migration/legacy/password", "/migration/legacy/email", "/migration/legacy/account-oidc/start", "/migration/managed/start",
        "/migration/legacy/verify/stage", "/migration/legacy/otp/issue", "/migration/legacy/otp/consume",
        "/migration/legacy/verify/decision", "/migration/legacy/verify/publication", "/migration/legacy/verify/final-command", "/migration/legacy/verify/final");
    private static final Map<String,String> SECURITY = Map.of("Cache-Control", "no-store", "Referrer-Policy", "no-referrer",
        "X-Content-Type-Options", "nosniff", "Content-Security-Policy", "default-src 'none'; form-action 'self'; base-uri 'none'; frame-ancestors 'none'");
    private final MigrationBrowserConfiguration settings;
    private final HttpClient client;
    private final ExecutorService admission = Executors.newVirtualThreadPerTaskExecutor();
    private final Set<CompletableFuture<?>> active = ConcurrentHashMap.newKeySet();
    private final Semaphore slots = new Semaphore(16);
    private final Semaphore readers = new Semaphore(16);
    private final ExecutorService streamClosers = Executors.newFixedThreadPool(16, Thread.ofVirtual().factory());
    private final Set<InboundRead> inbound = ConcurrentHashMap.newKeySet();
    private final Object lifecycle = new Object();
    private final java.util.concurrent.atomic.AtomicBoolean inputCloseFailed = new java.util.concurrent.atomic.AtomicBoolean();
    private volatile boolean closed;

    public MigrationBrowserProxy(MigrationBrowserConfiguration settings) {
        if (settings == null || !settings.enabled()) throw new IllegalArgumentException("Migration browser is disabled");
        this.settings = settings;
        client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).followRedirects(HttpClient.Redirect.NEVER).build();
    }
    public void configure(JavalinConfig config) {
        config.routes.before(ctx -> {
            if (ctx.path().startsWith("/migration/")
                && !(ctx.method().name().equals("GET") && GET.contains(ctx.path())
                    || ctx.method().name().equals("POST") && POST.contains(ctx.path()))) {
                throw new io.javalin.http.HttpResponseException(404, "Migration request rejected");
            }
        });
        for (String path : GET) config.routes.get(path, this::forward);
        for (String path : POST) config.routes.post(path, this::forward);
    }
    private void forward(Context ctx) {
        SECURITY.forEach(ctx::header);
        if (closed) { reject(ctx, 503); return; }
        if (!slots.tryAcquire()) { reject(ctx, 429); return; }
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();

        try {
            if (!ctx.req().getRequestURI().equals(ctx.path())) throw new Rejected(400);
            if (!settings.accepts(single(ctx, "X-ArMoney-Migration-Edge-Key"))
                || !settings.publicOrigin.equals(single(ctx, "X-ArMoney-Migration-Origin"))) throw new Rejected(403);
            boolean post = ctx.method().name().equals("POST");
            var origins = headers(ctx, "Origin");
            if (origins.size() > 1 || post && origins.size() != 1
                || !origins.isEmpty() && !settings.publicOrigin.equals(origins.getFirst())) throw new Rejected(403);
            String query = ctx.req().getQueryString();
            if (query != null && !safe(query, 4096)) throw new Rejected(400);
            var cookies = headers(ctx, "Cookie");
            int cookieBytes = 0;
            for (String cookie : cookies) {
                if (!safe(cookie, 4096)) throw new Rejected(400);
                cookieBytes += cookie.getBytes(StandardCharsets.UTF_8).length;
            }
            if (cookieBytes > 4096) throw new Rejected(413);
            int headerBytes = 0;
            for (String name : Collections.list(ctx.req().getHeaderNames())) {
                for (String value : headers(ctx, name)) {
                    if (!safe(value, 8192)) throw new Rejected(400);
                    headerBytes += name.length() + value.length();
                    if (headerBytes > 16384) throw new Rejected(413);
                }
            }
            String type = single(ctx, "Content-Type");
            if (post && (type == null || !type.matches("(?i)application/x-www-form-urlencoded(?:;\\s*charset=UTF-8)?"))) throw new Rejected(415);
            if (ctx.req().getContentLengthLong() > (post ? 8192 : 0)) throw new Rejected(413);
            byte[] bytes = readBody(ctx.req().getInputStream(), post ? 8192 : 0, deadline);
            URI target = URI.create(settings.authOrigin.toASCIIString() + ctx.path() + (query == null ? "" : "?" + query));
            var request = HttpRequest.newBuilder(target).timeout(Duration.ofNanos(remaining(deadline)))
                .header("X-ArMoney-Migration-Auth-Key", settings.authKey)
                .header("X-ArMoney-Migration-Origin", settings.publicOrigin);
            if (!origins.isEmpty()) request.header("Origin", origins.getFirst());
            for (String cookie : cookies) request.header("Cookie", cookie);
            if (post) request.header("Content-Type", type);
            request.method(post ? "POST" : "GET", post ? HttpRequest.BodyPublishers.ofByteArray(bytes) : HttpRequest.BodyPublishers.noBody());
            var future = client.sendAsync(request.build(), ignored -> new LimitedBody());
            active.add(future);
            try {
                if (closed) throw new Rejected(503);
                var response = future.get(remaining(deadline), TimeUnit.NANOSECONDS);
                copy(ctx, response);
            } finally { active.remove(future); if (!future.isDone()) future.cancel(true); }
        } catch (Rejected rejected) { reject(ctx, rejected.status); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); reject(ctx, 503); }
        catch (ExecutionException failed) { reject(ctx, failed.getCause() instanceof Rejected rejected ? rejected.status : 503); }
        catch (Exception unavailable) { reject(ctx, 503); }
        finally { slots.release(); }
    }
    /** The worker owns only the captured stream, never a servlet request/context. */
    byte[] readBody(InputStream input, int limit, long deadline) throws Exception {
        InboundRead read;
        synchronized (lifecycle) {
            if (closed || !readers.tryAcquire()) {
                // Ownership remains with the servlet when no read has been admitted.
                throw new Rejected(closed ? 503 : 429);
            }
            read = new InboundRead(input);
            inbound.add(read);
            CompletableFuture.allOf(read.readStopped, read.closeStopped).whenComplete((ignored, failure) -> {
                inbound.remove(read); readers.release();
            });
            admission.execute(() -> {
                try {
                    byte[] bytes = input.readNBytes(limit + 1);
                    if (bytes.length > limit) throw new Rejected(413);
                    read.result.complete(bytes);
                } catch (Throwable failure) { read.result.completeExceptionally(failure); }
                finally { read.closeOwned(); read.readStopped.complete(null); }
            });
        }
        // Reserve part of the original budget for closing and joining the reader.
        try { return read.result.get(remaining(deadline - Duration.ofMillis(250).toNanos()), TimeUnit.NANOSECONDS); }
        finally {
            read.closeOwned();
            CompletableFuture.allOf(read.readStopped, read.closeStopped).get(remaining(deadline), TimeUnit.NANOSECONDS);
        }
    }
    private final class InboundRead {
        final InputStream input;
        final CompletableFuture<byte[]> result = new CompletableFuture<>();
        final CompletableFuture<Void> readStopped = new CompletableFuture<>();
        final CompletableFuture<Void> closeStopped = new CompletableFuture<>();
        final java.util.concurrent.atomic.AtomicBoolean closing = new java.util.concurrent.atomic.AtomicBoolean();
        InboundRead(InputStream input) { this.input = input; }
        void closeOwned() {
            synchronized (lifecycle) {
            if (!closing.compareAndSet(false, true)) return;
            streamClosers.execute(() -> {
                try { input.close(); closeStopped.complete(null); }
                catch (Throwable failure) { inputCloseFailed.set(true); closeStopped.completeExceptionally(failure); }
                finally { result.completeExceptionally(new IOException("Migration input closed")); }
            });
            }
        }
    }
    private void copy(Context ctx, HttpResponse<byte[]> response) {
        int total = 0;
        for (var entry : response.headers().map().entrySet()) for (String value : entry.getValue()) {
            if (!safe(value, 8192)) throw new Rejected(503);
            total += entry.getKey().length() + value.length();
            if (total > 16384) throw new Rejected(503);
        }
        var locations = response.headers().allValues("Location");
        if (response.statusCode() >= 300 && response.statusCode() < 400) {
            if (locations.size() != 1) throw new Rejected(503);
            try { settings.requireLocation(locations.getFirst(), ctx.path(), response.statusCode()); }
            catch (IllegalArgumentException invalid) { throw new Rejected(503); }
        } else if (!locations.isEmpty()) throw new Rejected(503);
        for (var entry : SECURITY.entrySet())
            if (!response.headers().allValues(entry.getKey()).equals(List.of(entry.getValue()))) throw new Rejected(503);
        var types = response.headers().allValues("Content-Type");
        if (types.size() != 1 || !types.getFirst().matches("(?i)text/html(?:;\\s*charset=UTF-8)?")) throw new Rejected(503);
        var cookies = response.headers().allValues("Set-Cookie");
        var roles = new HashSet<String>();
        for (String cookie : cookies) {
            if (!cookie.matches("__Secure-ArMoneyMigration(?:Attempt|Form|Account)=(?:[A-Za-z0-9_-]{43})?; Path=/migration/; Secure; HttpOnly; SameSite=Lax; Max-Age=(?:0|[1-9]|[1-9][0-9]|1[01][0-9]|120)")) throw new Rejected(503);
            if (!roles.add(cookie.substring(0, cookie.indexOf('=')))) throw new Rejected(503);
            boolean clear = cookie.endsWith("Max-Age=0");
            if (clear != cookie.contains("=; Path=")) throw new Rejected(503);
            if (!clear) {
                String token = cookie.substring(cookie.indexOf('=') + 1, cookie.indexOf(';'));
                if (!Base64.getUrlEncoder().withoutPadding().encodeToString(Base64.getUrlDecoder().decode(token)).equals(token)) throw new Rejected(503);
            }
        }
        ctx.status(response.statusCode()).contentType(types.getFirst());
        if (!locations.isEmpty()) ctx.header("Location", locations.getFirst());
        for (String cookie : cookies) ctx.res().addHeader("Set-Cookie", cookie);
        ctx.result(response.body());
    }
    private static List<String> headers(Context ctx, String name) { return Collections.list(ctx.req().getHeaders(name)); }
    private static String single(Context ctx, String name) { var values = headers(ctx, name); return values.size() == 1 ? values.getFirst() : null; }
    private static boolean safe(String value, int limit) { return value != null && value.getBytes(StandardCharsets.UTF_8).length <= limit && value.chars().allMatch(c -> c >= 32 && c <= 126); }
    private static long remaining(long deadline) throws TimeoutException { long value = deadline - System.nanoTime(); if (value <= 0) throw new TimeoutException(); return value; }
    private static void reject(Context ctx, int status) { ctx.status(status).contentType("text/plain; charset=utf-8").result("Migration request rejected"); }
    private static final class Rejected extends RuntimeException { final int status; Rejected(int status) { super("Migration request rejected"); this.status = status; } }
    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final HttpResponse.BodySubscriber<byte[]> delegate = HttpResponse.BodySubscribers.ofByteArray();
        private Flow.Subscription subscription; private int size; private boolean failed;
        public CompletionStage<byte[]> getBody() { return delegate.getBody(); }
        public void onSubscribe(Flow.Subscription value) { subscription = value; delegate.onSubscribe(value); }
        public void onNext(List<ByteBuffer> items) {
            if (failed) return;
            for (var item : items) {
                if (item.remaining() > 65536 - size) { failed = true; subscription.cancel(); delegate.onError(new IOException("Migration response rejected")); return; }
                size += item.remaining();
            }
            delegate.onNext(items);
        }
        public void onError(Throwable error) { if (!failed) delegate.onError(error); }
        public void onComplete() { if (!failed) delegate.onComplete(); }
    }
    @Override public void close() {
        synchronized (lifecycle) {
            closed = true;
            inbound.forEach(InboundRead::closeOwned);
            admission.shutdown();
            streamClosers.shutdown();
        }
        active.forEach(future -> future.cancel(true));
        client.shutdownNow();
        long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        try {
            if (!admission.awaitTermination(remaining(deadline), TimeUnit.NANOSECONDS)
                || !streamClosers.awaitTermination(remaining(deadline), TimeUnit.NANOSECONDS)
                || !client.awaitTermination(Duration.ofNanos(remaining(deadline))) || inputCloseFailed.get())
                throw new IllegalStateException("Migration transport termination incomplete");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Migration transport termination interrupted");
        } catch (TimeoutException timeout) {
            throw new IllegalStateException("Migration transport termination incomplete");
        }
    }
}
