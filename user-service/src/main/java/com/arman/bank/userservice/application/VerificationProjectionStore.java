package com.arman.bank.userservice.application;

import com.arman.bank.userservice.domain.VerificationProjection;

public interface VerificationProjectionStore {
    VerificationProjection.Ack commit(String writer, VerificationProjection command);
    /** Compatibility seam. The original authenticated ingress clock is never reset.
     * Owning durable implementations override this to check after their final writes.
     */
    default VerificationProjection.Ack commit(String writer,VerificationProjection command,long originalStartedNanos) {
        long elapsed=System.nanoTime()-originalStartedNanos;
        if(elapsed<0||elapsed>=4_000_000_000L)throw new Rejected(503);
        var result=commit(writer,command);
        elapsed=System.nanoTime()-originalStartedNanos;
        if(elapsed<0||elapsed>=4_000_000_000L)throw new Rejected(503);
        return result;
    }
    boolean admit(String writer);
    /** Physically purge at most 100 expired full replay records; never tombstones. */
    int cleanup();
    final class Rejected extends RuntimeException {
        private final int status;
        public Rejected(int status) { super("Projection rejected"); this.status = status; }
        public int status() { return status; }
    }
}
