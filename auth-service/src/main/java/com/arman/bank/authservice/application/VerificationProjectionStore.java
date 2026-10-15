package com.arman.bank.authservice.application;

import com.arman.bank.authservice.domain.VerificationProjection.*;
import java.time.Instant;
import java.util.*;

public interface VerificationProjectionStore {
    Status allocate(String authenticatedWriter, Command command);
    Status status(String authenticatedWriter, UUID operationId);
    Optional<Claim> claim(String authenticatedWriter);
    boolean finish(Claim claim, Result result);
    void cleanup();
    /** Must lock CURRENT owning-auth authority/receipts in the supplied transaction, never call a peer.
     * Production remains unavailable; synthetic authority exists only in isolated tests. */
    @FunctionalInterface interface AuthorityPort { Authority current(UUID identityId, Instant databaseNow); }
    interface Transport extends AutoCloseable {
        Result send(Claim claim) throws InterruptedException;
        @Override void close();
    }
}
