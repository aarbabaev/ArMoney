package com.arman.bank.sso;

import org.junit.jupiter.api.Test;
import javax.net.ssl.*;
import java.net.*;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

final class BridgeContractTest {
    private static final String KEY = "synthetic-only-bridge-fixture-key-123456789";
    @Test void wireUsesFixedDestinationDedicatedHeaderAndDeadline() {
        var client = new FixtureClient(201, "{\"enrollment_id\":\"synthetic\"}");
        var bridge = new Bridge(URI.create("http://auth-service:8081"), KEY, client);
        assertEquals("synthetic", bridge.post("/internal/enrollments", Map.of("subject", "server-subject")).path("enrollment_id").asText());
        assertEquals(URI.create("http://auth-service:8081/internal/enrollments"), client.request.uri());
        assertEquals("POST", client.request.method());
        assertEquals(KEY, client.request.headers().firstValue("X-ArMoney-SSO-Bridge-Key").orElseThrow());
        assertEquals(Duration.ofSeconds(4), client.request.timeout().orElseThrow());
        assertEquals("application/json", client.request.headers().firstValue("Content-Type").orElseThrow());
    }
    @Test void redirectsAuthenticationFailuresAndMalformedResultsFailClosed() {
        for (int status : List.of(301, 302, 307, 400, 401, 403, 404, 500, 503))
            assertThrows(Bridge.Unavailable.class, () -> new Bridge(URI.create("http://auth-service:8081"), KEY,
                    new FixtureClient(status, "{}")).post("/internal/enrollments", Map.of()));
        for (String body : List.of("null", "[]", "true", "not-json", "{}{}", "{\"state\":\"PENDING\",\"state\":\"ACTIVE\"}",
                "{\"x\":\"" + "x".repeat(12288) + "\"}"))
            assertThrows(Bridge.Unavailable.class, () -> new Bridge(URI.create("http://auth-service:8081"), KEY,
                    new FixtureClient(200, body)).post("/internal/enrollments", Map.of()));
        for (int status : List.of(409, 429))
            assertThrows(Bridge.Rejected.class, () -> new Bridge(URI.create("http://auth-service:8081"), KEY,
                    new FixtureClient(status, "{}")).post("/internal/enrollments", Map.of()));
    }
    @Test void oversizedRequestNeverReachesTransport() {
        var client = new FixtureClient(200, "{}");
        assertThrows(Bridge.Unavailable.class, () -> new Bridge(URI.create("http://auth-service:8081"), KEY, client)
                .post("/internal/enrollments", Map.of("candidate", "x".repeat(12288))));
        assertNull(client.request);
    }
    @Test void transportInterruptionIsPreservedWithoutLeakingItsMessage() {
        var client = new FixtureClient(200, "{}"); client.interrupt = true;
        try {
            var failure = assertThrows(Bridge.Unavailable.class, () -> new Bridge(URI.create("http://auth-service:8081"), KEY, client)
                    .post("/internal/enrollments", Map.of()));
            assertTrue(Thread.currentThread().isInterrupted());
            assertEquals("Verification unavailable", failure.getMessage());
            assertNull(failure.getCause());
        } finally { Thread.interrupted(); }
    }
    @Test void transportTimeoutFailsClosedWithoutForwardingResponseOrPersonalData() {
        var client = new FixtureClient(200, "{}"); client.timeout = true;
        var failure = assertThrows(Bridge.Unavailable.class, () -> new Bridge(URI.create("http://auth-service:8081"), KEY, client)
                .post("/internal/enrollments", Map.of()));
        assertEquals("Verification unavailable", failure.getMessage()); assertNull(failure.getCause());
    }

    /** Synthetic in-process transport; proves client contract, not provider/network runtime behavior. */
    static final class FixtureClient extends HttpClient {
        int status;
        private final String body;
        HttpRequest request;
        boolean interrupt;
        boolean timeout;
        FixtureClient(int status, String body) { this.status = status; this.body = body; }
        @Override public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler) throws java.io.IOException, InterruptedException {
            this.request = request;
            if (interrupt) throw new InterruptedException("synthetic-private-detail");
            if (timeout) throw new HttpTimeoutException("synthetic-private-detail");
            var info = new HttpResponse.ResponseInfo() {
                public int statusCode() { return status; }
                public HttpHeaders headers() { return HttpHeaders.of(Map.of(), (a,b) -> true); }
                public Version version() { return Version.HTTP_1_1; }
            };
            var subscriber = handler.apply(info);
            subscriber.onSubscribe(new Flow.Subscription() { public void request(long n) { } public void cancel() { } });
            subscriber.onNext(List.of(ByteBuffer.wrap(body.getBytes(StandardCharsets.UTF_8)))); subscriber.onComplete();
            T result = subscriber.getBody().toCompletableFuture().join();
            return new HttpResponse<>() {
                public int statusCode() { return status; }
                public HttpRequest request() { return request; }
                public Optional<HttpResponse<T>> previousResponse() { return Optional.empty(); }
                public HttpHeaders headers() { return info.headers(); }
                public T body() { return result; }
                public Optional<SSLSession> sslSession() { return Optional.empty(); }
                public URI uri() { return request.uri(); }
                public Version version() { return Version.HTTP_1_1; }
            };
        }
        public Optional<CookieHandler> cookieHandler() { return Optional.empty(); }
        public Optional<Duration> connectTimeout() { return Optional.of(Duration.ofSeconds(2)); }
        public Redirect followRedirects() { return Redirect.NEVER; }
        public Optional<ProxySelector> proxy() { return Optional.empty(); }
        public SSLContext sslContext() { throw new UnsupportedOperationException(); }
        public SSLParameters sslParameters() { return new SSLParameters(); }
        public Optional<Authenticator> authenticator() { return Optional.empty(); }
        public Version version() { return Version.HTTP_1_1; }
        public Optional<Executor> executor() { return Optional.empty(); }
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest r, HttpResponse.BodyHandler<T> h) {
            try { return CompletableFuture.completedFuture(send(r,h)); }
            catch(Exception failure) { return CompletableFuture.failedFuture(failure); }
        }
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest r, HttpResponse.BodyHandler<T> h, HttpResponse.PushPromiseHandler<T> p) { throw new UnsupportedOperationException(); }
    }
}
