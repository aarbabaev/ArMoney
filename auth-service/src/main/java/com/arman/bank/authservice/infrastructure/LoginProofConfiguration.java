package com.arman.bank.authservice.infrastructure;

import com.arman.bank.authservice.domain.LoginProof.*;
import com.arman.bank.runtime.Database;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Off by default. This transport flag cannot supply positive banking eligibility. */
public final class LoginProofConfiguration {
    public final boolean enabled;
    private final String writer, writeKey, resolveKey;
    private final byte[] retentionKey;
    private final Policy policy;
    public LoginProofConfiguration(Map<String, String> environment) {
        boolean enforcement = flag(environment, "AUTH_SSO_LOGIN_PROOF_ENABLED");
        if (enforcement) throw new IllegalArgumentException("Positive login proof enforcement is unavailable");
        enabled = flag(environment, "AUTH_LOGIN_PROOF_BRIDGE_ENABLED");
        if (!enabled) { writer = writeKey = resolveKey = null; retentionKey = null; policy = null; return; }
        writer = required(environment, "SSO_LOGIN_PROOF_WRITER_ID");
        writeKey = required(environment, "SSO_LOGIN_PROOF_WRITE_KEY");
        resolveKey = required(environment, "SSO_LOGIN_PROOF_RESOLVE_KEY");
        LoginProofRoutes.credential(writeKey); LoginProofRoutes.credential(resolveKey);
        String retention = required(environment, "SSO_LOGIN_PROOF_RETENTION_KEY");
        retentionKey = canonicalKey(retention);
        if (sameMaterial(writeKey, resolveKey) || sameMaterial(writeKey, retention) || sameMaterial(resolveKey, retention)) invalidKeys();
        for (String name : List.of("SSO_VERIFICATION_BRIDGE_KEY", "AUTH_TO_SSO_STATUS_KEY", "ARMONEY_PROVIDER_BINDING_KEY",
                "INTERNAL_AUTH_KEY", "SSO_CLIENT_SECRET", "SSO_LOGIN_CODE_HMAC_KEY", "ARMONEY_LOGIN_CODE_HMAC_KEY",
                "VERIFICATION_OTP_HMAC_KEY", "VERIFICATION_DELIVERY_ENCRYPTION_KEY", "AUTH_TO_USER_VERIFICATION_PROJECTION_KEY",
                "SSO_MIGRATION_PROOF_WRITE_KEY", "SSO_MIGRATION_PROOF_RESOLVE_KEY")) {
            String other = environment.get(name);
            if (other != null && (sameMaterial(writeKey, other) || sameMaterial(resolveKey, other) || sameMaterial(retention, other))) invalidKeys();
        }
        policy = Policy.defaults(Set.of(required(environment, "SSO_ISSUER")), entries(environment, "SSO_LOGIN_PROOF_CLIENT_IDS"),
            entries(environment, "SSO_LOGIN_PROOF_CODE_KEY_IDS"), Set.of(writer));
    }
    public LoginProofRoutes routes(Database database) {
        if (!enabled) return null;
        var store = new PostgresLoginProofStore(database, policy, retentionKey, sql -> (binding, now) -> {
            // Authoritative ACTIVE/epoch/projection and provider-observation integration is absent.
            throw new Failure(com.arman.bank.authservice.domain.LoginProof.Error.UNAVAILABLE);
        });
        return new LoginProofRoutes(store, policy, writer, writeKey, resolveKey);
    }
    private static boolean flag(Map<String, String> environment, String name) {
        String value = environment.getOrDefault(name, "false");
        if (!Set.of("true", "false").contains(value)) throw new IllegalArgumentException("Invalid " + name);
        return value.equals("true");
    }
    private static Set<String> entries(Map<String, String> environment, String name) {
        String[] values = required(environment, name).split(",", -1);
        var result = new HashSet<>(Arrays.asList(values));
        if (result.size() != values.length) throw new IllegalArgumentException("Invalid proof allowlist");
        return result;
    }
    private static String required(Map<String, String> environment, String name) {
        String value = environment.get(name);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing " + name);
        return value;
    }
    private static byte[] canonicalKey(String encoded) {
        try {
            byte[] value = Base64.getDecoder().decode(encoded);
            if (value.length != 32 || !Base64.getEncoder().encodeToString(value).equals(encoded)) throw new IllegalArgumentException();
            return value;
        } catch (RuntimeException invalid) { throw new IllegalArgumentException("Invalid proof retention key"); }
    }
    private static boolean sameMaterial(String first, String second) {
        byte[] a = first.getBytes(StandardCharsets.UTF_8), b = second.getBytes(StandardCharsets.UTF_8);
        byte[] decodedA = decodeKey(first), decodedB = decodeKey(second);
        return MessageDigest.isEqual(a, b) || (decodedA != null && MessageDigest.isEqual(decodedA, b))
            || (decodedB != null && MessageDigest.isEqual(a, decodedB))
            || (decodedA != null && decodedB != null && MessageDigest.isEqual(decodedA, decodedB));
    }
    private static byte[] decodeKey(String value) {
        try { return canonicalKey(value); } catch (IllegalArgumentException invalid) { return null; }
    }
    private static void invalidKeys() { throw new IllegalArgumentException("Proof credentials and retention key must be independently provisioned"); }
    @Override public String toString() { return "LoginProofConfiguration[redacted]"; }
}
