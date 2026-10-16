package com.arman.bank.authservice.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Local evidence record, never a credential or a positive banking-session decision. */
public final class BankingEligibility {
    private BankingEligibility() {}
    public record Grant(UUID identityId, UUID enrollmentId, long verificationEpoch,
                        UUID emailCandidateId, UUID phoneCandidateId, UUID emailReceiptId, UUID phoneReceiptId,
                        int emailContactVersion, int phoneContactVersion, UUID projectionOperationId,
                        int projectionRevision, String projectionPayloadHash, Instant createdAt, Instant expiresAt) {
        @Override public String toString() { return "BankingEligibilityGrant[redacted]"; }
    }
    /** Negative observations still require the exact admitted invocation, live flow and fresh DB anchor. */
    public static void fresh(IdentityVerification.Flow flow, IdentityVerification.Observation observation,
                             Instant admittedAt, Instant now) {
        if (observation == null || admittedAt == null || !Objects.equals(flow,observation.flow())
                || !admittedAt.equals(observation.observationStartedAt()) || admittedAt.isAfter(now)
                || admittedAt.isBefore(now.minusSeconds(5)) || observation.sessionExpiresAt() == null
                || !now.isBefore(observation.sessionExpiresAt()))
            throw new Verification.Failure(Verification.Error.UNAVAILABLE);
    }
}
