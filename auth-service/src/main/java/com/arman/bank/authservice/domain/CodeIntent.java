package com.arman.bank.authservice.domain;

import java.time.Instant;
import java.util.UUID;

/** An immutable, noncredential recording. STAGED conveys no verification or banking authority. */
public final class CodeIntent {
    private CodeIntent() {}
    public record Snapshot(UUID intentId, String codeKeyId, Instant issuedAt, Instant expiresAt) {
        public String state() { return "STAGED"; }
    }
    public static void validate(LoginProof.Write write, String actor, LoginProof.Policy policy) {
        LoginProof.validate(write, actor, policy);
        if (write.kind() != LoginProof.Kind.INTERACTIVE) throw new LoginProof.Failure(LoginProof.Error.CONFLICT);
    }
}
