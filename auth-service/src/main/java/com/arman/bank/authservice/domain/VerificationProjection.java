package com.arman.bank.authservice.domain;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.Instant;
import java.util.*;

/** Immutable projection commands; an acknowledgement never grants banking eligibility. */
public final class VerificationProjection {
    private VerificationProjection() {}
    public enum Error { BAD_INPUT, UNAUTHORIZED, CONFLICT, EXPIRED, RATE_LIMITED, UNAVAILABLE }
    public static final class Failure extends RuntimeException {
        private final Error error;
        public Failure(Error error) { super("verification_projection_rejected"); this.error = error; }
        public Error error() { return error; }
    }
    public enum State { PENDING, ACKNOWLEDGED, SUPERSEDED, CONFLICT, DISABLED_BAD_REQUEST, DISABLED_UNAUTHORIZED, EXPIRED_REPLAY, EXPIRED_UNRESOLVED }
    public enum Outcome { ACK, RETRY, RATE_LIMITED, BAD_REQUEST, UNAUTHORIZED, CONFLICT, EXPIRED }
    public record Command(UUID operationId, UUID identityId, int expectedVersion, int version, String canonicalPhone,
                          int phoneContactVersion, long verificationEpoch, String payloadHash) {
        @Override public String toString() { return "VerificationProjectionCommand[redacted]"; }
    }
    public record Ack(UUID operationId, UUID identityId, int version, int phoneContactVersion, long verificationEpoch, String payloadHash) {
        public boolean matches(Command command) {
            return command != null && Objects.equals(operationId, command.operationId()) && Objects.equals(identityId, command.identityId())
                && version == command.version() && phoneContactVersion == command.phoneContactVersion()
                && verificationEpoch == command.verificationEpoch() && Objects.equals(payloadHash, command.payloadHash());
        }
        @Override public String toString() { return "VerificationProjectionAck[redacted]"; }
    }
    /** Only a trusted transaction-bound local supplier may attest these CURRENT reserved values/receipts.
     * No production authority supplier exists in this slice. */
    public record Authority(UUID identityId, UUID enrollmentId, String canonicalPhone, int phoneContactVersion,
                            int emailContactVersion, long verificationEpoch, UUID emailReceiptId, UUID phoneReceiptId) {
        public boolean matches(Command command) {
            return command != null && identityId != null && identityId.equals(command.identityId()) && enrollmentId != null
                && Objects.equals(canonicalPhone, command.canonicalPhone()) && phoneContactVersion == command.phoneContactVersion()
                && emailContactVersion > 0 && verificationEpoch == command.verificationEpoch() && emailReceiptId != null && phoneReceiptId != null;
        }
        @Override public String toString() { return "VerificationProjectionAuthority[redacted]"; }
    }
    public record Status(UUID operationId, State state, Ack acknowledgement, Instant expiresAt) {
        @Override public String toString() { return "VerificationProjectionStatus[redacted]"; }
    }
    public record Claim(String writer, Command command, byte[] requestBody, UUID fence, Instant leaseUntil, Instant expiresAt, int attempts) {
        public Claim { requestBody = requestBody.clone(); }
        @Override public byte[] requestBody() { return requestBody.clone(); }
        @Override public String toString() { return "VerificationProjectionClaim[redacted]"; }
    }
    public record Result(Outcome outcome, Ack acknowledgement) {
        public Result { Objects.requireNonNull(outcome); if ((outcome == Outcome.ACK) != (acknowledgement != null)) throw new IllegalArgumentException("Invalid projection result"); }
        public static Result retry() { return new Result(Outcome.RETRY, null); }
        @Override public String toString() { return "VerificationProjectionResult[redacted]"; }
    }
    public record Policy(Set<String> writers, int maxCommands, int maxTombstones) {
        public Policy {
            writers = Set.copyOf(writers);
            if (writers.isEmpty() || writers.size() > 16 || maxCommands < 1 || maxCommands > 100_000 || maxTombstones < 1 || maxTombstones > 1_000_000)
                throw new IllegalArgumentException("Invalid projection policy");
            for (String writer : writers) if (!writer.matches("[A-Za-z0-9_-]{1,64}")) throw new IllegalArgumentException("Invalid projection writer");
        }
        public static Policy defaults(Set<String> writers) { return new Policy(writers, 100_000, 1_000_000); }
    }
    public static Command create(UUID operation, UUID identity, int expected, int version, String phone, int phoneVersion, long epoch) {
        var command = new Command(operation, identity, expected, version, phone, phoneVersion, epoch, null);
        fields(command);
        return new Command(operation, identity, expected, version, phone, phoneVersion, epoch, hash(command));
    }
    public static void validate(Command command) {
        fields(command);
        if (command.payloadHash() == null || !command.payloadHash().matches("[0-9a-f]{64}") || !command.payloadHash().equals(hash(command))) throw new Failure(Error.BAD_INPUT);
    }
    private static void fields(Command command) {
        if (command == null || command.operationId() == null || command.identityId() == null || command.expectedVersion() < 0
                || command.expectedVersion() == Integer.MAX_VALUE || command.version() != command.expectedVersion() + 1
                || command.phoneContactVersion() < 1 || command.verificationEpoch() < 1 || command.canonicalPhone() == null
                || !command.canonicalPhone().matches("\\+9715[024568][0-9]{7}")) throw new Failure(Error.BAD_INPUT);
    }
    public static String hash(Command command) {
        fields(command);
        try {
            var bytes = new ByteArrayOutputStream(); var output = new DataOutputStream(bytes);
            for (String field : new String[]{"armoney-verification-projection-v1", command.operationId().toString(), command.identityId().toString(),
                    Integer.toString(command.expectedVersion()), Integer.toString(command.version()), command.canonicalPhone(),
                    Integer.toString(command.phoneContactVersion()), Long.toString(command.verificationEpoch())}) {
                byte[] value = field.getBytes(StandardCharsets.UTF_8); output.writeInt(value.length); output.write(value);
            }
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
        } catch (IOException | NoSuchAlgorithmException unavailable) { throw new Failure(Error.UNAVAILABLE); }
    }
    public static void writer(String writer, Policy policy) { if (writer == null || !policy.writers().contains(writer)) throw new Failure(Error.UNAUTHORIZED); }
}
