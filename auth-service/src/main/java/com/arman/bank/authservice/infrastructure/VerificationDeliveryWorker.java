package com.arman.bank.authservice.infrastructure;

import java.time.Clock;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import com.arman.bank.authservice.infrastructure.PostgresVerificationStore.DeliveryLease;

/** One serial fenced intent per tick; no network call runs in the claim/finish transaction. */
public final class VerificationDeliveryWorker implements AutoCloseable {
    interface Outbox {
        Optional<DeliveryLease> claim();
        boolean finish(UUID delivery, UUID fence, boolean accepted);
    }
    interface Sender extends AutoCloseable {
        boolean send(DeliveryLease lease, VerificationCrypto.DeliveryPayload payload) throws InterruptedException;
        @Override void close();
    }
    private final Outbox outbox;
    private final VerificationCrypto crypto;
    private final Sender sender;
    private final Clock clock;
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(
        Thread.ofPlatform().name("verification-delivery").daemon(true).factory());
    public VerificationDeliveryWorker(PostgresVerificationStore store, VerificationCrypto crypto,
            VerificationDeliveryTransport transport, Clock clock) {
        this(new Outbox() {
            public Optional<DeliveryLease> claim() { return store.claimDelivery(); }
            public boolean finish(UUID delivery, UUID fence, boolean accepted) { return store.finishDelivery(delivery, fence, accepted); }
        }, crypto, transport, clock);
    }
    VerificationDeliveryWorker(Outbox outbox, VerificationCrypto crypto, Sender sender, Clock clock) {
        this.outbox = Objects.requireNonNull(outbox); this.crypto = Objects.requireNonNull(crypto);
        this.sender = Objects.requireNonNull(sender); this.clock = Objects.requireNonNull(clock);
    }
    public void start() {
        if (closed.get() || !started.compareAndSet(false, true)) throw new IllegalStateException("Verification worker lifecycle rejected");
        executor.scheduleWithFixedDelay(this::safeTick, 1, 2, TimeUnit.SECONDS);
    }
    synchronized void tick() throws InterruptedException {
        if (closed.get()) return;
        var claim = outbox.claim();
        if (claim.isEmpty()) return;
        DeliveryLease lease = claim.get();
        boolean accepted = false;
        try {
            if (!closed.get() && clock.instant().isBefore(lease.expiresAt())) {
                var payload = crypto.decrypt(lease.encrypted(), lease.enrollmentId(), lease.challengeId(), lease.purpose(), lease.contactVersion());
                if (!closed.get() && clock.instant().isBefore(lease.expiresAt())) accepted = sender.send(lease, payload);
            }
        } catch (InterruptedException interrupted) {
            // Preserve the lease for fenced expiry/reclaim; do not infer the send outcome.
            Thread.currentThread().interrupt(); throw interrupted;
        } catch (RuntimeException unavailable) { /* Generic retry only, never log payload/provider exceptions. */ }
        outbox.finish(lease.deliveryId(), lease.fence(), accepted);
    }
    private void safeTick() {
        try { tick(); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        catch (RuntimeException unavailable) { /* Database uncertainty recovers through the existing lease. */ }
    }
    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        executor.shutdownNow();
        sender.close();
        try { executor.awaitTermination(5, TimeUnit.SECONDS); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
    }
}
