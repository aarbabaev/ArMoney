package com.arman.bank.authservice.infrastructure;

import java.util.*;
import java.time.Clock;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Explicitly started delivery owner. Transport is borrowed and never closed here.
 * Network is outside claim/finish transactions; accepted delivery does not create an OTP receipt. */
public final class LegacyMigrationDeliveryWorker implements AutoCloseable {
    interface Claims {
        void expirePayloads();
        Optional<PostgresLegacyMigrationOtpStore.DeliveryLease> claimDelivery();
        Optional<java.time.Instant> validateDeliveryDeadline(PostgresLegacyMigrationOtpStore.DeliveryLease lease);
        boolean finishDelivery(UUID id,UUID fence,boolean accepted);
    }
    interface Sender { boolean send(PostgresLegacyMigrationOtpStore.DeliveryLease lease,LegacyMigrationOtpCrypto.Payload payload)throws InterruptedException; }
    private final Claims store;
    private final LegacyMigrationOtpCrypto crypto;
    private final Sender transport;
    private final Clock clock;
    private final AtomicBoolean closed=new AtomicBoolean();
    private final AtomicBoolean ticking=new AtomicBoolean();
    private ScheduledExecutorService executor;
    public LegacyMigrationDeliveryWorker(PostgresLegacyMigrationOtpStore store,LegacyMigrationOtpCrypto crypto,LegacyMigrationDeliveryTransport transport,Clock clock){
        this(new Claims(){
            public void expirePayloads(){store.expirePayloads();}
            public Optional<PostgresLegacyMigrationOtpStore.DeliveryLease> claimDelivery(){return store.claimDelivery();}
            public Optional<java.time.Instant> validateDeliveryDeadline(PostgresLegacyMigrationOtpStore.DeliveryLease lease){return store.validateDeliveryDeadline(lease);}
            public boolean finishDelivery(UUID id,UUID fence,boolean accepted){return store.finishDelivery(id,fence,accepted);}
        },crypto,Objects.requireNonNull(transport)::send,clock);
        Objects.requireNonNull(store);
    }
    LegacyMigrationDeliveryWorker(Claims store,LegacyMigrationOtpCrypto crypto,Sender transport,Clock clock){this.store=Objects.requireNonNull(store);this.crypto=Objects.requireNonNull(crypto);this.transport=Objects.requireNonNull(transport);this.clock=Objects.requireNonNull(clock);}
    public synchronized void start(){
        if(closed.get()||executor!=null)throw new IllegalStateException("Migration delivery lifecycle rejected");
        executor=Executors.newSingleThreadScheduledExecutor(action->{var thread=new Thread(action,"migration-otp-delivery");thread.setDaemon(true);return thread;});
        executor.scheduleWithFixedDelay(()->{try{tick();}catch(InterruptedException interrupted){Thread.currentThread().interrupt();}catch(RuntimeException unavailable){/* Retain fenced state. Never log code/contact/provider exceptions. */}},1,2,TimeUnit.SECONDS);
    }
    void tick()throws InterruptedException{
        if(closed.get()||!ticking.compareAndSet(false,true))return;
        try {
        store.expirePayloads();var claim=store.claimDelivery();if(claim.isEmpty())return;
        var lease=claim.get();boolean accepted=false;
        try{
            if(!closed.get()&&!Thread.currentThread().isInterrupted()&&clock.instant().isBefore(lease.presendDeadline())){
                var payload=crypto.decrypt(lease.envelope(),lease.encrypted());
                var validated=store.validateDeliveryDeadline(lease);
                if(validated.isPresent()&&!closed.get()&&!Thread.currentThread().isInterrupted()&&clock.instant().isBefore(validated.get())
                    &&clock.instant().isBefore(lease.presendDeadline()))accepted=transport.send(lease,payload);
            }
        }catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw interrupted;}
        catch(RuntimeException unavailable){/* Unknown send never means possession proof. */}
        store.finishDelivery(lease.deliveryId(),lease.fence(),accepted);
        } finally {ticking.set(false);}
    }
    @Override public void close(){
        if(!closed.compareAndSet(false,true))return;
        ScheduledExecutorService worker; synchronized(this){worker=executor;executor=null;}
        if(worker!=null){worker.shutdownNow();try{if(!worker.awaitTermination(5,TimeUnit.SECONDS))throw new IllegalStateException("Migration delivery termination unverified");}catch(InterruptedException interrupted){Thread.currentThread().interrupt();}}
    }
}
