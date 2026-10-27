package com.arman.bank.authservice.infrastructure;

import java.util.Objects;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** One bounded reconciliation per tick. Durable completion, never a browser cookie, is its input. */
public final class MigrationUserReleaseWorker implements AutoCloseable {
    private final Runnable recover;
    private final AtomicBoolean closed=new AtomicBoolean(),ticking=new AtomicBoolean();
    private ScheduledExecutorService executor;
    MigrationUserReleaseWorker(PostgresMigrationFinalAckStore store){this(store::recoverNextUserRelease);}
    MigrationUserReleaseWorker(Runnable recover){this.recover=Objects.requireNonNull(recover);}
    public synchronized void start(){
        if(closed.get()||executor!=null)throw new IllegalStateException("Migration release lifecycle rejected");
        executor=Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().daemon(true).name("migration-user-release").factory());
        long delay=5000+ThreadLocalRandom.current().nextLong(1001);
        executor.scheduleWithFixedDelay(this::tick,delay,delay,TimeUnit.MILLISECONDS);
    }
    void tick(){
        if(closed.get()||!ticking.compareAndSet(false,true))return;
        try{if(!closed.get()&&!Thread.currentThread().isInterrupted())recover.run();}
        catch(RuntimeException unavailable){/* Original durable completion remains pending for a fair later sweep. */}
        finally{ticking.set(false);}
    }
    @Override public void close(){
        if(!closed.compareAndSet(false,true))return;
        ScheduledExecutorService worker;synchronized(this){worker=executor;executor=null;}
        if(worker!=null){worker.shutdownNow();try{if(!worker.awaitTermination(5,TimeUnit.SECONDS))throw new IllegalStateException("Migration release termination unverified");}
            catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new IllegalStateException("Migration release termination interrupted");}}
    }
}
