package com.arman.bank.authservice.application;

import java.util.UUID;

/** Idempotently establishes the immutable claimed phone; does not verify possession. */
public interface RegistrationProfiles {
    void provision(UUID identityId, String phoneNumber);
}
