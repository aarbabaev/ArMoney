package com.arman.bank.authservice.infrastructure;
import com.arman.bank.authservice.application.LoginNotificationProfiles;
import com.arman.bank.authservice.application.NotificationDelivery.Result;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
/** One owned scheduler, one claim/send in flight; durable fenced recovery survives process restart. */
public final class LoginNotificationWorker implements AutoCloseable {
    @FunctionalInterface interface Sender {Result send(PostgresLoginNotificationOutbox.Claim claim)throws InterruptedException;}
    private final PostgresLoginNotificationOutbox outbox;private final LoginNotificationProfiles profiles;private final Sender sender;
    private final AtomicBoolean active=new AtomicBoolean();private volatile boolean closed;private ScheduledExecutorService executor;
    LoginNotificationWorker(PostgresLoginNotificationOutbox outbox,LoginNotificationProfiles profiles,Sender sender){this.outbox=Objects.requireNonNull(outbox);this.profiles=Objects.requireNonNull(profiles);this.sender=Objects.requireNonNull(sender);}
    synchronized void start(){if(closed||executor!=null)throw new IllegalStateException("Notice worker unavailable");executor=Executors.newSingleThreadScheduledExecutor(r->{var thread=new Thread(r,"login-notification-delivery");thread.setDaemon(true);return thread;});executor.scheduleWithFixedDelay(this::tick,1,2,TimeUnit.SECONDS);}
    void tick(){
        if(closed||!active.compareAndSet(false,true))return;
        PostgresLoginNotificationOutbox.Claim claim=null;
        try{
            if(Thread.currentThread().isInterrupted())return;
            claim=outbox.claim().orElse(null);if(claim==null)return;
            String name=claim.profileFrozen()?claim.displayName():profiles.displayName(claim.owner());
            if(closed||Thread.currentThread().isInterrupted())return;
            var prepared=outbox.prepareSend(claim,name);if(prepared.isEmpty())return;
            if(closed||Thread.currentThread().isInterrupted())return;
            outbox.finish(prepared.get(),sender.send(prepared.get()));
        }catch(InterruptedException interrupted){Thread.currentThread().interrupt();}
        catch(RuntimeException unavailable){if(claim!=null)try{outbox.finish(claim,Result.TRANSIENT_FAILURE);}catch(RuntimeException ignored){/* Original lease remains recoverable. */}}
        finally{active.set(false);}
    }
    @Override public synchronized void close(){if(closed)return;closed=true;if(executor!=null){executor.shutdownNow();try{if(!executor.awaitTermination(20,TimeUnit.SECONDS))throw new IllegalStateException("Notice worker termination unverified");}catch(InterruptedException interrupted){Thread.currentThread().interrupt();}}}
}
