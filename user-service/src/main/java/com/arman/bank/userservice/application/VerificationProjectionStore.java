package com.arman.bank.userservice.application;

import com.arman.bank.userservice.domain.VerificationProjection;

public interface VerificationProjectionStore {
    VerificationProjection.Ack commit(String writer, VerificationProjection command);
    boolean admit(String writer);
    /** Physically purge at most 100 expired full replay records; never tombstones. */
    int cleanup();
    final class Rejected extends RuntimeException {
        private final int status;
        public Rejected(int status) { super("Projection rejected"); this.status = status; }
        public int status() { return status; }
    }
}
