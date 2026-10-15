package com.arman.bank.userservice.domain;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

/** A central projection command; never a bank eligibility decision. */
public record VerificationProjection(UUID operationId, UUID identityId, int expectedVersion, int version,
        String canonicalPhone, int phoneContactVersion, long verificationEpoch, String payloadHash) {
    public VerificationProjection {
        if (operationId == null || identityId == null || expectedVersion < 0 || expectedVersion == Integer.MAX_VALUE
                || version != expectedVersion + 1 || phoneContactVersion < 1 || verificationEpoch < 1
                || canonicalPhone == null || !canonicalPhone.matches("\\+9715[024568][0-9]{7}")
                || payloadHash == null || !payloadHash.matches("[0-9a-f]{64}")
                || !payloadHash.equals(hash(operationId, identityId, expectedVersion, version, canonicalPhone,
                    phoneContactVersion, verificationEpoch))) throw new IllegalArgumentException("Invalid projection");
    }

    public static String hash(UUID operationId, UUID identityId, int expectedVersion, int version,
            String phone, int contactVersion, long epoch) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String field : new String[] {"armoney-verification-projection-v1", operationId.toString(),
                    identityId.toString(), Integer.toString(expectedVersion), Integer.toString(version), phone,
                    Integer.toString(contactVersion), Long.toString(epoch)}) {
                byte[] bytes = field.getBytes(StandardCharsets.UTF_8);
                digest.update(ByteBuffer.allocate(4).putInt(bytes.length).array());
                digest.update(bytes);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException("SHA-256 unavailable"); }
    }

    public Ack ack() { return new Ack(operationId, identityId, version, phoneContactVersion, verificationEpoch, payloadHash); }
    @Override public String toString() { return "VerificationProjection[redacted]"; }
    public record Ack(UUID operationId, UUID identityId, int version, int phoneContactVersion,
            long verificationEpoch, String payloadHash) {
        @Override public String toString() { return "VerificationProjection.Ack[redacted]"; }
    }
}
