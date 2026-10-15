package com.arman.bank.authservice.domain;

import java.time.Instant;
import java.util.UUID;

/** Verification identifiers are enrollment identifiers, never banking principals. */
public final class Verification {
    private Verification() {}
    public enum Purpose { REGISTRATION_EMAIL, REGISTRATION_PHONE }
    public enum State { PENDING_CONTACTS, VERIFIED, PROJECTION_PENDING, ACTIVE, BLOCKED_MIGRATION }
    public enum Error { BAD_INPUT, BINDING, CONFLICT, RATE_LIMITED, UNAVAILABLE }
    public static final class Failure extends RuntimeException {
        private final Error error;
        public Failure(Error error) { super("verification_request_rejected"); this.error = error; }
        public Error error() { return error; }
    }
    /** Bounded, contact-free result suitable for the future private bridge. */
    public record Result(UUID enrollmentId, UUID challengeId, UUID receiptId, int contactVersion,
                         String state, Instant expiresAt, Instant resendAt, int attemptsRemaining, Error error,
                         boolean emailVerified, boolean phoneVerified) {
        public Result(UUID enrollmentId, UUID challengeId, UUID receiptId, int contactVersion,
                String state, Instant expiresAt, Instant resendAt, int attemptsRemaining, Error error) {
            this(enrollmentId, challengeId, receiptId, contactVersion, state, expiresAt, resendAt, attemptsRemaining, error, false, false);
        }
        public int version() { return contactVersion; }
        public int emailContactVersion() { return contactVersion; }
        public int phoneContactVersion() { return contactVersion; }
        public static Result rejected(Error error) {
            return new Result(null, null, null, 0, "REJECTED", null, null, 0, error);
        }
    }
    public record Binding(String issuer, String subject, String session) {
        @Override public String toString() { return "Binding[redacted]"; }
    }
    public record EnrollmentCommand(UUID commandId, Binding binding, String phone, String email) {
        @Override public String toString() { return "EnrollmentCommand[redacted]"; }
    }
    public record ChallengeCommand(UUID commandId, UUID enrollmentId, Binding binding,
                                   Purpose purpose, int contactVersion) {
        @Override public String toString() { return "ChallengeCommand[redacted]"; }
    }
    public record ConsumeCommand(UUID commandId, UUID enrollmentId, UUID challengeId, Binding binding,
                                 Purpose purpose, int contactVersion, String code) {
        @Override public String toString() { return "ConsumeCommand[redacted]"; }
    }
}
