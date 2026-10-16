package com.arman.bank.authservice.application;

import com.arman.bank.authservice.domain.BankingEligibility.Grant;
import com.arman.bank.authservice.domain.IdentityVerification.Flow;
import com.arman.bank.authservice.domain.IdentityVerification.Observation;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Bound to the caller's already locked auth transaction; never performs HTTP or opens a transaction.
 * The owning enrollment adapter must authorize the current flow before recording, and recheck it at commit.
 * A returned record grants no banking access; future consumers need fresh current evidence. */
public interface BankingEligibilityStore {
    Optional<Grant> record(UUID enrollmentId, Flow flow, Observation observation, Instant admittedAt);
    boolean invalidateIfChanged(UUID enrollmentId, Flow flow, Observation observation, Instant admittedAt);
    void invalidateAfterMutation(UUID enrollmentId);
}
