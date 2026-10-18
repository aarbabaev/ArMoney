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
    /** Durable migration provenance viewed through one finite owning operation; never a registration Grant. */
    public record MigrationGrant(UUID identityId,UUID decisionId,long classificationGeneration,long verificationEpoch,
                                 long sourceRevision,UUID emailReceiptId,UUID phoneReceiptId,long emailReceiptGeneration,
                                 long phoneReceiptGeneration,Long verifiedContactGeneration,Instant expiresAt,String authorityStamp){
        public MigrationGrant {
            if(identityId==null||decisionId==null||emailReceiptId==null||phoneReceiptId==null||emailReceiptId.equals(phoneReceiptId)
                ||classificationGeneration<1||verificationEpoch<1||sourceRevision<1||emailReceiptGeneration<1||phoneReceiptGeneration<1
                ||(verifiedContactGeneration!=null&&verifiedContactGeneration<1)||expiresAt==null||authorityStamp==null||!authorityStamp.matches("[0-9a-f]{64}"))
                throw new IllegalArgumentException("Invalid migration eligibility evidence");
        }
        @Override public String toString(){return "MigrationEligibilityGrant[redacted]";}
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
