package com.arman.bank.authservice.domain;

import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.Instant;
import java.util.*;

/** Code digests and immutable snapshots only; no redeemable code, password or bearer token. */
public final class LoginProof {
    private LoginProof() {}
    public enum Kind {
        INTERACTIVE("interactive"), REUSE("reuse");
        private final String wire;
        Kind(String wire) { this.wire = wire; }
        public String wire() { return wire; }
    }
    public enum Error { BAD_INPUT, UNAUTHORIZED, CONFLICT, EXPIRED, MISSING, RATE_LIMITED, UNAVAILABLE }
    public static final class Failure extends RuntimeException {
        private final Error error;
        public Failure(Error error) { super("login_proof_request_rejected"); this.error = error; }
        public Error error() { return error; }
    }
    public record Binding(String issuer, String subject, String clientId, String userSessionId) {
        @Override public String toString() { return "LoginProofBinding[redacted]"; }
    }
    public record Write(UUID commandId, String codeDigest, String codeKeyId, String issuer, String subject,
                        String clientId, String userSessionId, String authenticationSessionId, Instant issuedAt,
                        Instant expiresAt, Kind kind, UUID eventId, Instant passwordAuthenticatedAt) {
        public Binding binding() { return new Binding(issuer, subject, clientId, userSessionId); }
        @Override public String toString() { return "LoginProofWrite[redacted]"; }
    }
    public record Resolve(String codeDigest, String codeKeyId, String issuer, String subject, String clientId, String userSessionId) {
        public Binding binding() { return new Binding(issuer, subject, clientId, userSessionId); }
        @Override public String toString() { return "LoginProofResolve[redacted]"; }
    }
    public record Snapshot(UUID proofId, String codeKeyId, UUID eventId, Kind kind, boolean loginNoticeEligible,
                           Instant issuedAt, Instant expiresAt) {
        public String state() { return "RECORDED"; }
    }
    /** Only a trusted owning-transaction adapter may supply this evidence. Not an HTTP DTO.
     * The supplier must lock local authority and validate all six projection ACK fields.
     * No production positive supplier is implemented in this slice. */
    public record Activation(Binding binding, UUID identityId, String state, boolean emailReceipt, boolean phoneReceipt,
                             int emailContactVersion, int phoneContactVersion, long verificationEpoch,
                             UUID projectionOperationId, int projectionRevision, int projectionPhoneContactVersion,
                             long projectionEpoch, String projectionPayloadHash, boolean projectionAcknowledged,
                             String verifiedEmail, boolean providerSessionActive, boolean providerContactsMatch,
                             Instant observationStartedAt) {
        public boolean completeFor(Binding expected, Instant now) {
            return expected != null && expected.equals(binding) && identityId != null && "ACTIVE".equals(state)
                && emailReceipt && phoneReceipt && emailContactVersion > 0 && phoneContactVersion > 0 && verificationEpoch > 0
                && projectionOperationId != null && projectionRevision > 0 && projectionPhoneContactVersion == phoneContactVersion
                && projectionEpoch == verificationEpoch && projectionPayloadHash != null && projectionPayloadHash.matches("[0-9a-f]{64}")
                && projectionAcknowledged && canonicalEmail(verifiedEmail) && providerSessionActive && providerContactsMatch
                && observationStartedAt != null && !observationStartedAt.isAfter(now) && !observationStartedAt.isBefore(now.minusSeconds(5));
        }
        @Override public String toString() { return "LoginProofActivation[redacted]"; }
    }
    /** Internal staged facts from a trusted port, never a provider HTTP DTO or ACTIVE assertion. */
    public record Observation(Binding binding,String username,String email,String phone,boolean enabled,
                              boolean sessionActive,Instant sessionDeadline,Instant observationStartedAt) {
        @Override public String toString(){return "LoginProofObservation[redacted]";}
    }
    public record CurrentEvidence(Binding binding,BankingEligibility.Grant grant,String verifiedEmail,
                                  Instant observationStartedAt,Instant validUntil) {
        public boolean completeFor(Binding expected,Instant now){
            return expected!=null&&expected.equals(binding)&&grant!=null&&canonicalEmail(verifiedEmail)
                &&observationStartedAt!=null&&!observationStartedAt.isAfter(now)
                &&!observationStartedAt.isBefore(now.minusSeconds(5))&&validUntil!=null&&now.isBefore(validUntil)
                &&now.isBefore(grant.expiresAt());
        }
        @Override public String toString(){return "LoginProofCurrentEvidence[redacted]";}
    }
    public static void observation(Binding binding,Observation facts,Instant admittedAt,Instant now){
        if(facts==null||admittedAt==null||!binding.equals(facts.binding())||!admittedAt.equals(facts.observationStartedAt())
                ||admittedAt.isAfter(now)||admittedAt.isBefore(now.minusSeconds(5))||facts.sessionDeadline()==null
                ||!now.isBefore(facts.sessionDeadline()))fail(Error.UNAVAILABLE);
        if(!facts.enabled()||!facts.sessionActive()||!canonicalEmail(facts.email())||facts.phone()==null
                ||!facts.phone().matches("\\+9715[024568][0-9]{7}")||!facts.phone().equals(facts.username()))fail(Error.CONFLICT);
    }
    public record Policy(Set<String> issuers, Set<String> clients, Set<String> codeKeyIds, Set<String> writers,
                         int maxProofs, int maxCommands, int maxHourly) {
        public Policy {
            issuers = Set.copyOf(issuers); clients = Set.copyOf(clients); codeKeyIds = Set.copyOf(codeKeyIds); writers = Set.copyOf(writers);
            if (issuers.isEmpty() || issuers.size() > 16 || clients.isEmpty() || clients.size() > 32 || codeKeyIds.isEmpty()
                    || codeKeyIds.size() > 2 || writers.isEmpty() || writers.size() > 16 || maxProofs < 1 || maxProofs > 100_000
                    || maxCommands < 1 || maxCommands > 100_000 || maxHourly < 1 || maxHourly > 1_000) invalid();
            for (String issuer : issuers) {
                var uri = URI.create(issuer);
                if (!bounded(issuer, 512) || !"https".equals(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null
                        || uri.getQuery() != null || uri.getFragment() != null) invalid();
            }
            for (String value : clients) if (!value.matches("[A-Za-z0-9_-]{1,128}") || value.equals("armoney-migration")) invalid();
            for (String value : codeKeyIds) if (!value.matches("[A-Za-z0-9_-]{1,32}")) invalid();
            for (String value : writers) if (!value.matches("[A-Za-z0-9_-]{1,64}")) invalid();
        }
        public static Policy defaults(Set<String> issuers, Set<String> clients, Set<String> keys, Set<String> writers) {
            return new Policy(issuers, clients, keys, writers, 100_000, 100_000, 1_000);
        }
        private static void invalid() { throw new IllegalArgumentException("Invalid login proof policy"); }
    }
    public static void writer(String writer, Policy policy) {
        if (writer == null || !policy.writers().contains(writer)) fail(Error.UNAUTHORIZED);
    }
    public static void validate(Write input, String writer, Policy policy) {
        writer(writer, policy);
        if (input == null || input.commandId() == null || input.kind() == null) fail(Error.BAD_INPUT);
        validate(input.codeDigest(), input.codeKeyId(), input.binding(), policy);
        if (!bounded(input.authenticationSessionId(), 255) || !seconds(input.issuedAt()) || !seconds(input.expiresAt())
                || !input.issuedAt().isBefore(input.expiresAt()) || input.expiresAt().isAfter(input.issuedAt().plusSeconds(120))) fail(Error.BAD_INPUT);
        if (input.kind() == Kind.INTERACTIVE) {
            if (input.eventId() == null || !seconds(input.passwordAuthenticatedAt()) || input.passwordAuthenticatedAt().isAfter(input.issuedAt())
                    || input.passwordAuthenticatedAt().isBefore(input.issuedAt().minusSeconds(1800))) fail(Error.BAD_INPUT);
        } else if (input.eventId() != null || input.passwordAuthenticatedAt() != null) fail(Error.BAD_INPUT);
    }
    public static void validate(Resolve input, Policy policy) {
        if (input == null) fail(Error.BAD_INPUT);
        validate(input.codeDigest(), input.codeKeyId(), input.binding(), policy);
    }
    private static void validate(String digest, String key, Binding binding, Policy policy) {
        if (binding == null || !bounded(binding.issuer(), 512) || !bounded(binding.subject(), 255)
                || !bounded(binding.clientId(), 128) || !bounded(binding.userSessionId(), 255)) fail(Error.BAD_INPUT);
        if (!policy.issuers().contains(binding.issuer()) || !policy.clients().contains(binding.clientId())) fail(Error.CONFLICT);
        if (digest == null || !digest.matches("[0-9a-f]{64}") || key == null || !key.matches("[A-Za-z0-9_-]{1,32}")) fail(Error.BAD_INPUT);
        if (!policy.codeKeyIds().contains(key)) fail(Error.CONFLICT);
    }
    public static void unexpired(Instant expiry, Instant now) { if (!now.isBefore(expiry)) fail(Error.EXPIRED); }
    public static void fresh(Write input, Instant now) {
        if (input.issuedAt().isBefore(now.minusSeconds(30)) || input.issuedAt().isAfter(now.plusSeconds(30))) fail(Error.EXPIRED);
    }
    public static byte[] payloadHash(Write input) {
        return hash("armoney-login-proof-payload-v1", input.codeDigest(), input.codeKeyId(), input.issuer(), input.subject(), input.clientId(),
            input.userSessionId(), input.authenticationSessionId(), input.issuedAt().toString(), input.expiresAt().toString(), input.kind().wire(),
            text(input.eventId()), text(input.passwordAuthenticatedAt()));
    }
    public static byte[] bindingHash(Binding binding) { return hash("armoney-login-proof-binding-v1", binding.issuer(), binding.subject(), binding.clientId(), binding.userSessionId()); }
    public static byte[] authenticationHash(String value) { return hash("armoney-login-proof-authentication-v1", value); }
    public static byte[] requesterHash(String writer, String issuer, String client) { return hash("armoney-login-proof-requester-v1", writer, issuer, client); }
    public static byte[] writerHash(String writer) { return hash("armoney-login-proof-writer-v1", writer); }
    public static byte[] identityHash(UUID identity) { return hash("armoney-login-proof-identity-v1", identity.toString()); }
    private static byte[] hash(String... fields) {
        try { return MessageDigest.getInstance("SHA-256").digest(tuple(fields)); }
        catch (NoSuchAlgorithmException unavailable) { throw new Failure(Error.UNAVAILABLE); }
    }
    private static byte[] tuple(String... fields) {
        try {
            var bytes = new ByteArrayOutputStream(); var output = new DataOutputStream(bytes);
            for (String field : fields) { byte[] value = field.getBytes(StandardCharsets.UTF_8); output.writeInt(value.length); output.write(value); }
            return bytes.toByteArray();
        } catch (IOException unavailable) { throw new Failure(Error.UNAVAILABLE); }
    }
    private static String text(Object value) { return value == null ? "" : value.toString(); }
    private static boolean bounded(String value, int max) {
        return value != null && !value.isBlank() && value.length() <= max
            && value.codePoints().noneMatch(c -> Character.isWhitespace(c) || Character.isSpaceChar(c) || Character.isISOControl(c) || (c >= 0xd800 && c <= 0xdfff));
    }
    private static boolean seconds(Instant value) { return value != null && value.getNano() == 0 && value.getEpochSecond() >= 0 && value.getEpochSecond() <= 253402300799L; }
    private static boolean canonicalEmail(String value) {
        return value != null && value.length() <= 254 && value.equals(value.strip().toLowerCase(Locale.ROOT))
            && value.matches("[a-z0-9.!#$%&'*+/=?^_`{|}~-]+@[a-z0-9](?:[a-z0-9-]*[a-z0-9])?(?:\\.[a-z0-9](?:[a-z0-9-]*[a-z0-9])?)+");
    }
    private static void fail(Error error) { throw new Failure(error); }
}
