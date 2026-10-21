package com.arman.bank.authservice.application;

import com.arman.bank.authservice.domain.CodeConfirmation;
import java.time.Instant;
import java.util.UUID;

/** Genuine LEGACY_ACCOUNT signature/exchange purpose. Subject/owner are unknown until authenticated claims.
 * It must never substitute a permissive existing-mapping callback or allocate an identity. */
@FunctionalInterface public interface MigrationLegacyAccountProofPort {
    void exchange(Attempt attempt,MigrationManagedProofPort.Callback callback,VerifiedSink sink);
    interface Attempt {
        UUID attemptId();String issuer();String clientId();String redirectUri();String pkceChallenge();
        Instant challengeStartedAt();Instant originalDatabaseStart();Instant originalAttemptDeadline();
        void requireCallback(MigrationManagedProofPort.Callback callback);
        String originalVerifier();boolean nonceMatches(String signedNonce);void requireLive();
    }
    @FunctionalInterface interface VerifiedSink {
        void accept(MigrationManagedProofPort.Claims claims,CodeConfirmation.Facts facts);
    }
}
