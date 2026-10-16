package com.arman.bank.authservice.application;

import com.arman.bank.authservice.domain.IdentityVerification.*;
import com.arman.bank.authservice.domain.Verification.Result;
import com.arman.bank.authservice.domain.VerificationProjection;
import java.util.UUID;
import java.time.Instant;

public interface IdentityVerificationStore {
    Instant admit(String writer,Flow flow);
    Result enroll(String writer,Enrollment command,Observation observation,Instant startedAt);
    Result status(String writer,UUID enrollment,Flow flow,Observation observation,Instant startedAt);
    Result issue(String writer,Issue command,Observation observation,Instant startedAt);
    Result consume(String writer,Consume command,Observation observation,Instant startedAt);
    VerificationProjection.Status prepare(String writer,UUID commandId,UUID enrollment,Flow flow,Observation observation,Instant startedAt);
    boolean attach(String writer,VerificationProjection.Claim claim,VerificationProjection.Result result);
    void cleanup();
    /** Must obtain committed pre-token evidence using the exact auth DB start time supplied before network I/O.
     * No production supplier is introduced by this staging slice. */
    @FunctionalInterface interface Observations {
        Observation observe(Flow flow,Instant startedAt);
        /** Separately validated status facts may suspend an existing grant; never authorize positive operations. */
        default Observation observeStatus(Flow flow,Instant startedAt){return observe(flow,startedAt);}
    }
}
