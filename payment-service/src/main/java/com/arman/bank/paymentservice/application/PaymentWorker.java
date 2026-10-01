package com.arman.bank.paymentservice.application;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** One bounded dispatcher per process. PostgreSQL leases arbitrate across processes. */
public final class PaymentWorker implements AutoCloseable {
    private final PaymentStore store;
    private final PaymentPeers peers;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r -> new Thread(r, "payment-recovery"));
    public PaymentWorker(PaymentStore store, PaymentPeers peers) { this.store = store; this.peers = peers; }
    public void start() { executor.scheduleWithFixedDelay(this::tick, 0, 250, TimeUnit.MILLISECONDS); }
    public void tick() {
        if (closed.get()) return;
        try {
            var candidate = store.claim();
            if (candidate.isEmpty()) return;
            var claim = candidate.get();
            try {
                var outcome = peers.post(claim.payment());
                if (outcome == null) store.retry(claim);
                else store.finish(claim, outcome);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                // Leave the durable lease to expire after process interruption.
            } catch (Exception e) { store.retry(claim); }
        } catch (Exception e) {
            // Database failure must not kill scheduled recovery; never log tokens, phone or bodies.
            System.err.println("Payment recovery deferred: dependency unavailable");
        }
    }
    @Override public void close() {
        closed.set(true);
        executor.shutdownNow();
        try {
            if (!executor.awaitTermination(10, TimeUnit.SECONDS))
                throw new IllegalStateException("Payment worker did not stop");
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException("Payment shutdown interrupted", e); }
    }
}
