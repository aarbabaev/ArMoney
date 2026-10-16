package com.arman.bank.authservice.infrastructure;

import com.arman.bank.authservice.application.VerificationProjectionStore;
import com.arman.bank.authservice.application.VerificationProjectionStore.Transport;
import com.arman.bank.authservice.domain.VerificationProjection.Result;
import java.time.*;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** One fenced command per serial tick; cleanup precedes claims and all I/O follows claim commit. */
public final class VerificationProjectionWorker implements AutoCloseable {
    private final VerificationProjectionStore store;
    private final Transport transport;
    private final String writer;
    private final Clock clock;
    private final java.util.function.BiFunction<com.arman.bank.authservice.domain.VerificationProjection.Claim,Result,Boolean> finish;
    private final AtomicBoolean started=new AtomicBoolean(),closed=new AtomicBoolean();
    private final ScheduledExecutorService executor=Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().name("verification-projection").daemon(true).factory());
    private Instant nextCleanup=Instant.MIN;
    public VerificationProjectionWorker(VerificationProjectionStore store,Transport transport,String writer,Clock clock){
        this(store,transport,writer,clock,store::finish);
    }
    public VerificationProjectionWorker(VerificationProjectionStore store,Transport transport,String writer,Clock clock,
            java.util.function.BiFunction<com.arman.bank.authservice.domain.VerificationProjection.Claim,Result,Boolean> finish){
        this.store=Objects.requireNonNull(store);this.transport=Objects.requireNonNull(transport);this.writer=Objects.requireNonNull(writer);this.clock=Objects.requireNonNull(clock);
        this.finish=Objects.requireNonNull(finish);
    }
    public void start(){if(closed.get()||!started.compareAndSet(false,true))throw new IllegalStateException("Projection worker lifecycle rejected");executor.scheduleWithFixedDelay(this::safeTick,1,2,TimeUnit.SECONDS);}
    synchronized void tick() throws InterruptedException {
        if(closed.get())return;
        if(!clock.instant().isBefore(nextCleanup)){store.cleanup();nextCleanup=clock.instant().plusSeconds(30);}
        var claim=store.claim(writer); if(claim.isEmpty())return;
        var item=claim.get(); Result result=Result.retry();
        try {if(!closed.get()&&clock.instant().isBefore(item.expiresAt())&&clock.instant().isBefore(item.leaseUntil()))result=transport.send(item);}
        catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw interrupted;}
        catch(RuntimeException unavailable){/* Persist uncertainty, never provider/contact data. */}
        finish.apply(item,result);
    }
    private void safeTick(){try{tick();}catch(InterruptedException interrupted){Thread.currentThread().interrupt();}catch(RuntimeException unavailable){/* Fenced lease recovers database uncertainty. */}}
    @Override public void close(){if(!closed.compareAndSet(false,true))return;executor.shutdownNow();transport.close();try{executor.awaitTermination(5,TimeUnit.SECONDS);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();}}
}
