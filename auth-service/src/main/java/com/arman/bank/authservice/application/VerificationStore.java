package com.arman.bank.authservice.application;

import com.arman.bank.authservice.domain.Verification.*;

/** Each command and its replay record commit in one owning-database transaction. */
public interface VerificationStore {
    Result enroll(EnrollmentCommand command);
    Result issue(ChallengeCommand command);
    Result consume(ConsumeCommand command);
    Result status(java.util.UUID enrollmentId, Binding binding);
}
