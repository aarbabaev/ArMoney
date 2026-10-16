package com.arman.bank.authservice.infrastructure;

import com.arman.bank.authservice.application.CodeIntentStore;
import java.util.Objects;
import java.util.concurrent.*;

/** Explicitly started owned worker; constructing disabled configuration creates no thread. */
public final class CodeIntentCleanupWorker implements AutoCloseable {
    private final CodeIntentStore store;
    private ScheduledExecutorService executor;
    public CodeIntentCleanupWorker(CodeIntentStore store){this.store=Objects.requireNonNull(store);}
    public synchronized void start(){
        if(executor!=null)return;
        executor=Executors.newSingleThreadScheduledExecutor(r->{var thread=new Thread(r,"code-intent-cleanup");thread.setDaemon(true);return thread;});
        executor.scheduleWithFixedDelay(()->{try{store.cleanup();}catch(RuntimeException unavailable){/* Stale DB health closes new admission. No sensitive diagnostic. */}},0,30,TimeUnit.SECONDS);
    }
    @Override public synchronized void close(){if(executor!=null){executor.shutdownNow();executor=null;}}
}
