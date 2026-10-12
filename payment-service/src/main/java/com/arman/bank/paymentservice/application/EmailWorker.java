package com.arman.bank.paymentservice.application;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** HTTP runs outside transactions. Acceptance followed by a crash may duplicate mail. */
public final class EmailWorker implements AutoCloseable {
    private final EmailOutbox store;
    private final EmailDelivery delivery;
    private final String mode;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r -> new Thread(r,"email-delivery"));
    public EmailWorker(EmailOutbox store, EmailDelivery delivery, String mode) {
        this.store=store; this.delivery=delivery; this.mode=mode;
    }
    public void start() { executor.scheduleWithFixedDelay(this::tick,0,1000,TimeUnit.MILLISECONDS); }
    public void tick() {
        if (closed.get()) return;
        try {
            var candidate=store.claim(mode);
            if (candidate.isEmpty()) return;
            var c=candidate.get();
            try {
                String address=c.recipientEmail();
                boolean verified=Boolean.TRUE.equals(c.recipientVerified());
                if (address==null) {
                    var recipient=delivery.recipient(c.ownerId());
                    if (recipient==null || recipient.email()==null) { store.finish(c,"SKIPPED","missing_email"); return; }
                    address=recipient.email(); verified=recipient.verified();
                    if (!store.freezeRecipient(c,recipient)) return;
                }
                if (mode.equals("sending") && !verified) { store.finish(c,"SKIPPED","unverified_email"); return; }
                switch (delivery.send(c,address)) {
                    case ACCEPTED -> store.finish(c,"SENT",null);
                    case PERMANENT_FAILURE -> store.finish(c,"DEAD","provider_rejected");
                    case RATE_LIMITED -> store.retry(c,"provider_rate_limited");
                    case TRANSIENT_FAILURE -> store.retry(c,"provider_unavailable");
                }
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
              catch (Exception e) { store.retry(c,"dependency_unavailable"); }
        } catch (Exception e) { System.err.println("Email delivery deferred: dependency unavailable"); }
    }
    @Override public void close() {
        closed.set(true); executor.shutdownNow();
        try { if (!executor.awaitTermination(10,TimeUnit.SECONDS)) throw new IllegalStateException("Email worker did not stop"); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException("Email shutdown interrupted",e); }
    }
}
