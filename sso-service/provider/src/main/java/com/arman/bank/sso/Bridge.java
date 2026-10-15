package com.arman.bank.sso;

import com.fasterxml.jackson.databind.JsonNode;
import org.keycloak.util.JsonSerialization;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.*;

/** Fixed private destination. No redirects, response-body logging or caller-selected paths. */
final class Bridge {
    private static final int LIMIT = 12288;
    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2)).followRedirects(HttpClient.Redirect.NEVER).build();
    private final URI base;
    private final String key;
    private final HttpClient client;

    Bridge(URI base, String key) {
        this(base, key, CLIENT);
    }
    Bridge(URI base, String key, HttpClient client) {
        if (base == null || base.getHost() == null || base.getUserInfo() != null
                || base.getQuery() != null || base.getFragment() != null
                || !("https".equals(base.getScheme()) || ("http".equals(base.getScheme())
                && "auth-service".equals(base.getHost())))
                || !(base.getPath().isEmpty() || "/".equals(base.getPath()))
                || key == null || key.length() < 32 || key.length() > 512
                || key.chars().anyMatch(c -> c < 33 || c > 126)) throw new IllegalArgumentException("Invalid bridge configuration");
        this.base = base;
        this.key = key;
        this.client = client;
    }

    static boolean enabled() { return "true".equals(System.getenv("ARMONEY_VERIFICATION_ENABLED")); }
    static Bridge configured() {
        return new Bridge(URI.create(required("ARMONEY_AUTH_BRIDGE_URL")), required("SSO_VERIFICATION_BRIDGE_KEY"));
    }
    static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException("Missing provider configuration");
        return value;
    }

    JsonNode enrollment(String path, Map<String, Object> body) {
        return EnrollmentObservation.enrollment(post(path, body));
    }

    JsonNode post(String path, Map<String, Object> body) {
        if (!path.matches("/internal/enrollments(?:/[0-9a-f-]{36}(?:/status|/challenges(?:/[0-9a-f-]{36}/consume)?)?)?"))
            throw new IllegalArgumentException("Invalid bridge path");
        try {
            byte[] payload = JsonSerialization.writeValueAsBytes(body);
            if (payload.length > LIMIT) throw new Unavailable();
            var request = HttpRequest.newBuilder(base.resolve(path)).timeout(Duration.ofSeconds(4))
                    .header("Content-Type", "application/json")
                    .header("X-ArMoney-SSO-Bridge-Key", key)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(payload)).build();
            var response = DeadlineHttp.send(client,request);
                byte[] bytes = response.body();
                if (response.statusCode() == 409 || response.statusCode() == 429) throw new Rejected();
                if (response.statusCode() != 200 && response.statusCode() != 201) throw new Unavailable();
                JsonNode result = strictJson(bytes);
                if (result == null || !result.isObject()) throw new Unavailable();
                return result;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new Unavailable();
        } catch (Rejected | Unavailable known) { throw known; }
        catch (Exception failure) { throw new Unavailable(); }
    }
    static JsonNode strictJson(byte[] bytes) throws java.io.IOException {
        return JsonSerialization.mapper.readerFor(JsonNode.class)
                .with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .with(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .readValue(bytes);
    }
    /** Bounded subscriber with explicit cancellation by the total exchange deadline. */
    static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private Flow.Subscription subscription;
        private boolean cancelled;
        @Override public CompletionStage<byte[]> getBody() { return result; }
        @Override public synchronized void onSubscribe(Flow.Subscription value) {
            if(subscription!=null || cancelled) { value.cancel(); return; }
            subscription = value; value.request(1);
        }
        synchronized void cancel() {
            cancelled=true; if(subscription!=null) subscription.cancel();
            result.completeExceptionally(new Unavailable());
        }
        @Override public synchronized void onNext(List<ByteBuffer> values) {
            if(cancelled || result.isDone()) return;
            for (ByteBuffer value : values) {
                if (value.remaining() > LIMIT - bytes.size()) {
                    cancel(); return;
                }
                byte[] chunk = new byte[value.remaining()]; value.get(chunk); bytes.writeBytes(chunk);
            }
            subscription.request(1);
        }
        @Override public synchronized void onError(Throwable failure) { cancel(); }
        @Override public synchronized void onComplete() { if(!cancelled) result.complete(bytes.toByteArray()); }
    }
    static final class Unavailable extends RuntimeException { Unavailable() { super("Verification unavailable"); } }
    static final class Rejected extends RuntimeException { Rejected() { super("Verification not accepted"); } }
}
