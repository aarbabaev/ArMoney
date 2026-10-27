package com.arman.bank.authservice.application;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Only an authenticated provider response can establish this principal. */
public interface SsoTokens {
    enum MetadataStatus { ABSENT, MALFORMED, PRESENT_VALID_FORMAT }
    enum Malformation { NONE, NULL_VALUE, WRONG_TYPE, INVALID_VALUE, OUT_OF_RANGE }
    /** Format classification only. Values come from authenticated introspection, not proof verification. */
    record Metadata<T>(MetadataStatus status,T value,Malformation malformation) {
        public Metadata {
            Objects.requireNonNull(status);Objects.requireNonNull(malformation);
            boolean valid=switch(status){
                case ABSENT -> value==null&&malformation==Malformation.NONE;
                case MALFORMED -> value==null&&malformation!=Malformation.NONE;
                case PRESENT_VALID_FORMAT -> value!=null&&malformation==Malformation.NONE;
            };
            if(!valid)throw new IllegalArgumentException("Invalid token metadata classification");
        }
        public static <T> Metadata<T> absent(){return new Metadata<>(MetadataStatus.ABSENT,null,Malformation.NONE);}
        public static <T> Metadata<T> malformed(Malformation reason){return new Metadata<>(MetadataStatus.MALFORMED,null,reason);}
        public static <T> Metadata<T> present(T value){return new Metadata<>(MetadataStatus.PRESENT_VALID_FORMAT,value,Malformation.NONE);}
        @Override public String toString(){return "TokenMetadata["+status+","+malformation+",redacted]";}
    }
    record ProofClaims(String kind,UUID eventId,boolean loginNoticeEligible) {
        @Override public String toString(){return "TokenProofClaims[redacted]";}
    }
    record TokenContext(Metadata<String> clientId,Metadata<String> sessionId,Metadata<Instant> issuedAt,
                        Metadata<Instant> expiresAt,Metadata<ProofClaims> proof) {
        public TokenContext {
            Objects.requireNonNull(clientId);Objects.requireNonNull(sessionId);Objects.requireNonNull(issuedAt);
            Objects.requireNonNull(expiresAt);Objects.requireNonNull(proof);
        }
        public static TokenContext absent(){return new TokenContext(Metadata.absent(),Metadata.absent(),Metadata.absent(),Metadata.absent(),Metadata.absent());}
        @Override public String toString(){return "AuthenticatedTokenContext[redacted]";}
    }
    /** Claim format is retained separately; legacy contact normalization never grants managed authority. */
    record Principal(String issuer, String subject, String phoneNumber, String email, boolean emailVerified,
                     boolean emailVerificationClaimKnown,TokenContext tokenContext) {
        public Principal(String issuer,String subject,String phoneNumber,String email,boolean emailVerified,boolean emailVerificationClaimKnown){
            this(issuer,subject,phoneNumber,email,emailVerified,emailVerificationClaimKnown,TokenContext.absent());
        }
        public Principal {
            Objects.requireNonNull(tokenContext);
            try { email = AuthService.normalizeEmail(email); }
            catch (AuthFailure invalid) { email = null; }
            emailVerified = email != null && emailVerified;
        }
        @Override public String toString() { return "Principal[REDACTED]"; }
    }
    Principal verify(String accessToken);
}
