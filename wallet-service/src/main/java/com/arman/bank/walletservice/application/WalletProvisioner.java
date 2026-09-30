package com.arman.bank.walletservice.application;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
/** Durable SQL claims fence stale workers. No database transaction spans HTTP. */
public final class WalletProvisioner implements AutoCloseable {
    private static final System.Logger LOG = System.getLogger(WalletProvisioner.class.getName());
    private final ProvisioningStore store;
    private final LedgerAccounts ledger;
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(task -> {
        var thread = new Thread(task, "wallet-provisioning");
        thread.setDaemon(true);
        return thread;
    });
    public WalletProvisioner(ProvisioningStore store, LedgerAccounts ledger) {
        this.store = store;
        this.ledger = ledger;
    }
    public void start() {
        executor.scheduleWithFixedDelay(() -> {
            try { runOnce(); }
            catch (RuntimeException e) { LOG.log(System.Logger.Level.WARNING, "Wallet provisioning database operation failed; will retry"); }
        }, 0, 1, TimeUnit.SECONDS);
    }
    /** At most ten claims per tick; failed rows move behind other due rows. */
    public int runOnce() {
        int processed = 0;
        while (processed < 10 && !Thread.currentThread().isInterrupted()) {
            var next = store.claim();
            if (next.isEmpty()) break;
            var claim = next.get();
            processed++;
            try {
                var account = ledger.provision(claim.wallet());
                store.complete(claim, account);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break; // The durable lease recovers this uncertain outcome.
            } catch (Exception e) {
                store.retry(claim, Math.min(60, 1 << Math.min(6, claim.attempt())));
                LOG.log(System.Logger.Level.WARNING, "Ledger account provisioning attempt failed; durable retry scheduled");
            }
        }
        return processed;
    }
    @Override public void close() {
        executor.shutdownNow();
        // Join before the caller closes the HTTP client or database.
        try {
            if (!executor.awaitTermination(10, TimeUnit.SECONDS))
                throw new IllegalStateException("Provisioning worker did not stop; database must remain open until process termination; lease will recover");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while stopping provisioning; database must remain open", e);
        }
    }
}
