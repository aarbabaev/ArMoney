package com.arman.bank.authservice.application;

import com.arman.bank.authservice.domain.Identity;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import java.util.UUID;
import static com.arman.bank.authservice.application.AuthFailure.Kind.*;

public final class AuthService {
    public record Session(String accessToken, Instant expiresAt) {
        @Override public String toString() { return "Session[REDACTED]"; }
    }
    private final AuthStore store;
    private final PasswordHasher passwords;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();
    private final String dummyHash;

    public AuthService(AuthStore store, PasswordHasher passwords, Clock clock) {
        this.store = store;
        this.passwords = passwords;
        this.clock = clock;
        dummyHash = passwords.hash(UUID.randomUUID().toString());
    }

    public void register(String email, String password) {
        email = normalizeEmail(email);
        validatePassword(password);
        limit(email);
        // Always hash; duplicate registration does not reveal existence or overwrite credentials.
        store.register(new Identity(UUID.randomUUID(), email), passwords.hash(password));
    }

    public Session login(String email, String password) {
        email = normalizeEmail(email);
        validatePassword(password);
        limit(email);
        var credentials = store.findByEmail(email);
        boolean valid = passwords.verify(password, credentials.map(AuthStore.Credentials::passwordHash).orElse(dummyHash));
        if (!valid || credentials.isEmpty()) throw new AuthFailure(UNAUTHORIZED);
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        var token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        var now = clock.instant();
        var expiry = now.plus(Duration.ofMinutes(30));
        store.saveSession(digest(token), credentials.get().identity().id(), now, expiry);
        return new Session(token, expiry);
    }

    public Identity me(String authorization) {
        return store.findSession(digest(bearer(authorization)), clock.instant())
                .orElseThrow(() -> new AuthFailure(UNAUTHORIZED));
    }

    public void logout(String authorization) {
        // Repeated logout is harmless; no identity is accepted from the client.
        store.revoke(digest(bearer(authorization)));
    }

    private void limit(String email) {
        if (!store.allowAttempt(digest(email), clock.instant())) throw new AuthFailure(RATE_LIMITED);
    }

    public static String normalizeEmail(String email) {
        if (email == null) throw new AuthFailure(BAD_INPUT);
        var normalized = email.strip().toLowerCase(Locale.ROOT);
        if (normalized.length() > 254 || !normalized.matches("[a-z0-9.!#$%&'*+/=?^_`{|}~-]+@[a-z0-9](?:[a-z0-9-]*[a-z0-9])?(?:\\.[a-z0-9](?:[a-z0-9-]*[a-z0-9])?)+"))
            throw new AuthFailure(BAD_INPUT);
        return normalized;
    }

    private static void validatePassword(String password) {
        if (password == null || password.codePointCount(0, password.length()) < 15 || password.length() > 128)
            throw new AuthFailure(BAD_INPUT);
    }

    private static String bearer(String header) {
        if (header == null || !header.matches("(?i:Bearer) [A-Za-z0-9_-]{43}")) throw new AuthFailure(UNAUTHORIZED);
        return header.substring(7);
    }

    public static String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
