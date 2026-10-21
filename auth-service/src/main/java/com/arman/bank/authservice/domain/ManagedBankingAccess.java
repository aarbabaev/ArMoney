package com.arman.bank.authservice.domain;

import java.time.Instant;
import java.util.UUID;

/** Internal authority only. Never an ACTIVE assertion or browser supplied identity. */
public final class ManagedBankingAccess {
    private ManagedBankingAccess() {}
    public record Token(LoginProof.Binding binding, UUID eventId, Instant issuedAt, Instant expiresAt,
                        String email, String phone, String bearer, boolean providerEmailVerified) {
        @Override public String toString(){return "ManagedToken[redacted]";}
    }
    public record Session(String accessToken, Instant expiresAt) {
        @Override public String toString(){return "ManagedSession[redacted]";}
    }
    public record Decision(UUID identityId, Instant expiresAt, boolean deniedCommitted) {
        public Decision {
            if(deniedCommitted ? identityId!=null||expiresAt!=null : identityId==null||expiresAt==null)
                throw new IllegalArgumentException("Invalid managed decision");
        }
        public static Decision denied(){return new Decision(null,null,true);}
        @Override public String toString(){return "ManagedDecision[redacted]";}
    }
}
