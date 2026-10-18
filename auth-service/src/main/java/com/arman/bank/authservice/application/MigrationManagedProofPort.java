package com.arman.bank.authservice.application;

import com.arman.bank.authservice.domain.CodeConfirmation;
import com.arman.bank.authservice.domain.LegacyMigration;
import java.time.Instant;
import java.time.Duration;
import com.arman.bank.authservice.domain.LoginProof;
import java.util.UUID;

/** Trusted genuine managed-purpose exchange adapter. No endpoint accepts these capabilities as DTOs.
 * The owning store creates Attempt and VerifiedSink only after acknowledged one-shot admission.
 * Public claim values alone cannot mint a receipt; the private synchronous sink owns that decision. */
@FunctionalInterface public interface MigrationManagedProofPort {
    void exchange(Attempt attempt,Callback callback,VerifiedSink sink);
    record Callback(String providerCode,String returnedState,String initiatingBrowserCookie) {
        @Override public String toString(){return "MigrationProviderCallback[redacted]";}
    }
    interface Attempt {
        UUID attemptId(); UUID receiptId(); UUID migrationId(); UUID ownerId();
        String issuer(); String clientId(); String redirectUri(); String expectedSubject();
        LegacyMigration.ProofMode accountProofMode(); String legacyIssuer(); String legacySubject();
        String pkceChallenge();
        Instant challengeStartedAt(); Instant originalDatabaseStart();
        Instant originalAccountDeadline(); Instant originalAttemptDeadline();
        /** Checks exact committed callback hash and original state/cookie without another claim. */
        void requireCallback(Callback callback);
        /** Original server custody only, once; no callback verifier or current-key fallback. */
        String originalVerifier();
        boolean nonceMatches(String signedNonce);
        /** Same owning thread/backend/one-shot invocation; interruption/deadline fail closed. */
        void requireLive();
    }
    record Claims(String issuer,String subject,String clientId,String userSessionId,
                  Instant authenticatedAt,Instant issuedAt,Instant tokenExpiresAt) {
        @Override public String toString(){return "ManagedMigrationSignedClaims[redacted]";}
    }
    @FunctionalInterface interface Observations {
        CodeConfirmation.Facts observe(LoginProof.Binding binding,Instant originalDatabaseStart,Duration remainingBudget);
    }
    @FunctionalInterface interface VerifiedSink {
        /** Only the real signature/claim adapter calls this, after actual verification and fresh exact12 observation. */
        void accept(Claims claims,CodeConfirmation.Facts facts);
    }
}
