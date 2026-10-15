package com.arman.bank.authservice.infrastructure;

import com.arman.bank.authservice.application.VerificationService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Private bridge rollout is independent of banking eligibility enforcement. */
public final class VerificationConfiguration {
    public final boolean enabled;
    private final String bridgeKey;
    private final String issuer;
    private final VerificationCrypto crypto;
    public VerificationConfiguration(Map<String, String> environment) {
        String flag = environment.getOrDefault("AUTH_VERIFICATION_BRIDGE_ENABLED", "false");
        if (!Set.of("true", "false").contains(flag)) invalid("AUTH_VERIFICATION_BRIDGE_ENABLED");
        enabled = flag.equals("true");
        if (!enabled) { bridgeKey = issuer = null; crypto = null; return; }
        bridgeKey = required(environment, "SSO_VERIFICATION_BRIDGE_KEY");
        if (bridgeKey.length() < 32 || bridgeKey.length() > 256 || bridgeKey.chars().anyMatch(c -> c < 33 || c > 126))
            invalid("SSO_VERIFICATION_BRIDGE_KEY");
        String businessKey = environment.get("INTERNAL_AUTH_KEY");
        if (businessKey != null && MessageDigest.isEqual(bridgeKey.getBytes(StandardCharsets.UTF_8), businessKey.getBytes(StandardCharsets.UTF_8)))
            throw new IllegalArgumentException("Verification bridge requires a dedicated credential");
        issuer = required(environment, "SSO_ISSUER");
        var uri = java.net.URI.create(issuer);
        if (!"https".equals(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null
                || uri.getQuery() != null || uri.getFragment() != null || issuer.length() > 512) invalid("SSO_ISSUER");
        String id = required(environment, "VERIFICATION_KEY_ID");
        crypto = new VerificationCrypto(id,
            Map.of(id, key(environment, "VERIFICATION_OTP_HMAC_KEY")),
            Map.of(id, key(environment, "VERIFICATION_DELIVERY_ENCRYPTION_KEY")));
    }
    public VerificationRoutes routes(com.arman.bank.runtime.Database database) {
        if (!enabled) return null;
        return new VerificationRoutes(new VerificationService(new PostgresVerificationStore(database, crypto), Set.of(issuer)), bridgeKey);
    }
    private static String required(Map<String, String> environment, String name) {
        String value = environment.get(name);
        if (value == null || value.isBlank()) invalid(name);
        return value;
    }
    private static byte[] key(Map<String, String> environment, String name) {
        try {
            byte[] value = Base64.getDecoder().decode(required(environment, name));
            if (value.length != 32) invalid(name);
            return value;
        } catch (IllegalArgumentException failure) { throw new IllegalArgumentException("Invalid " + name); }
    }
    private static void invalid(String name) { throw new IllegalArgumentException("Invalid " + name); }
    @Override public String toString() { return "VerificationConfiguration[redacted]"; }
}
