package com.arman.bank.sso;

import org.junit.jupiter.api.Test;
import java.net.URI;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.Flow;
import static org.junit.jupiter.api.Assertions.*;

final class ProviderSafetyTest {
    private static final String SYNTHETIC_KEY = "synthetic-only-fixture-key-not-a-secret-001";
    @Test void bindingIsStableAndSeparatesEveryServerSessionComponent() {
        String[] tuple = {"realm", "root", "tab", "client"};
        String expected = SessionBinding.derive(SYNTHETIC_KEY, tuple);
        assertEquals(expected, SessionBinding.derive(SYNTHETIC_KEY, tuple));
        assertTrue(expected.matches("[A-Za-z0-9_-]{43}"));
        for (int index = 0; index < tuple.length; index++) {
            String[] changed = tuple.clone(); changed[index] += "-other";
            assertNotEquals(expected, SessionBinding.derive(SYNTHETIC_KEY, changed));
        }
        assertNotEquals(expected, SessionBinding.derive(SYNTHETIC_KEY + "-rotated", tuple));
    }
    @Test void bindingTupleCannotBeRepartitionedAndRejectsMissingConfiguration() {
        assertNotEquals(SessionBinding.derive(SYNTHETIC_KEY, "ab", "c"), SessionBinding.derive(SYNTHETIC_KEY, "a", "bc"));
        assertThrows(IllegalArgumentException.class, () -> SessionBinding.derive("short", "realm"));
        assertThrows(IllegalArgumentException.class, () -> SessionBinding.derive(SYNTHETIC_KEY, ""));
    }
    @Test void bridgeRejectsUntrustedDestinationsAndHeaderInjection() {
        for (String target : List.of("http://example.test", "https://name:password@example.test", "https://example.test?x=1",
                "https://example.test/#fragment", "https://example.test/base", "http://localhost:8080"))
            assertThrows(IllegalArgumentException.class, () -> new Bridge(URI.create(target), SYNTHETIC_KEY));
        assertThrows(IllegalArgumentException.class, () -> new Bridge(URI.create("http://auth-service:8081"), SYNTHETIC_KEY + "\r\n"));
        assertThrows(IllegalArgumentException.class, () -> new Bridge(URI.create("https://example.test"), "short"));
    }
    @Test void bridgeRejectsPathsBeforeAnyNetworkOperation() {
        Bridge bridge = new Bridge(URI.create("https://example.invalid"), SYNTHETIC_KEY);
        assertThrows(IllegalArgumentException.class, () -> bridge.post("https://attacker.invalid", Map.of()));
        assertThrows(IllegalArgumentException.class, () -> bridge.post("/internal/enrollments/../identities", Map.of()));
        assertThrows(IllegalArgumentException.class, () -> bridge.post("/internal/enrollments?code=123456", Map.of()));
    }
    @Test void responseLimitCancelsAtFirstOversizeChunk() {
        Bridge.LimitedBody body = new Bridge.LimitedBody();
        var subscription = new Subscription(); body.onSubscribe(subscription);
        body.onNext(List.of(ByteBuffer.wrap(new byte[12288])));
        assertFalse(body.getBody().toCompletableFuture().isDone());
        body.onNext(List.of(ByteBuffer.wrap(new byte[1])));
        assertTrue(subscription.cancelled);
        assertTrue(body.getBody().toCompletableFuture().isCompletedExceptionally());
    }
    @Test void exactBoundedBodyCompletesOnlyAtEnd() {
        Bridge.LimitedBody body = new Bridge.LimitedBody(); body.onSubscribe(new Subscription());
        body.onNext(List.of(ByteBuffer.wrap(new byte[10000]), ByteBuffer.wrap(new byte[2288])));
        assertFalse(body.getBody().toCompletableFuture().isDone());
        body.onComplete(); assertEquals(12288, body.getBody().toCompletableFuture().join().length);
    }
    private static final class Subscription implements Flow.Subscription {
        boolean cancelled;
        @Override public void request(long count) { assertEquals(1, count); }
        @Override public void cancel() { cancelled = true; }
    }
}
