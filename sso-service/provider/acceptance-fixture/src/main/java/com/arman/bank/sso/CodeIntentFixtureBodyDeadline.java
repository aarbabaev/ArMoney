package com.arman.bank.sso;

import java.util.concurrent.Semaphore;
import java.util.function.LongSupplier;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpVersion;
import org.jboss.resteasy.reactive.server.core.ResteasyReactiveRequestContext;

/** Disposable HTTP/1.1 body guard. Physical close/unblock remains a runtime acceptance gate. */
final class CodeIntentFixtureBodyDeadline implements AutoCloseable {
    interface Scheduler { Runnable schedule(long millis,Runnable task); }
    private enum State { OPEN, COMPLETE, EXPIRED }
    private static final Semaphore PERMITS=new Semaphore(8);
    private final Semaphore permits;
    private final LongSupplier clock;
    private final long start;
    private final Runnable closeConnection;
    private State state=State.OPEN;
    private Runnable cancelTimer;
    private boolean released;
    private long lastElapsed;

    static CodeIntentFixtureBodyDeadline arm(ResteasyReactiveRequestContext context){
        if(context==null)throw new CodeIntentFixtureSupport.Rejected(503);
        var request=context.serverRequest().unwrap(HttpServerRequest.class);
        if(request==null||request.version()!=HttpVersion.HTTP_1_1)throw new CodeIntentFixtureSupport.Rejected(503);
        var connection=request.connection();if(connection==null)throw new CodeIntentFixtureSupport.Rejected(503);
        // Only the connection is retained by the timer's state; never request/context/session.
        var guard=create(PERMITS,System::nanoTime,()->connection.close(),context::registerTimer);
        try{context.registerCompletionCallback(failure->guard.close());return guard;}
        catch(RuntimeException failure){guard.close();throw new CodeIntentFixtureSupport.Rejected(503);}
    }
    static CodeIntentFixtureBodyDeadline create(Semaphore permits,LongSupplier clock,Runnable closeConnection,Scheduler scheduler){
        if(!permits.tryAcquire())throw new CodeIntentFixtureSupport.Rejected(503);
        var guard=new CodeIntentFixtureBodyDeadline(permits,clock,closeConnection);
        try{
            long remaining=4_000_000_000L-(clock.getAsLong()-guard.start);
            if(remaining<=0)throw new CodeIntentFixtureSupport.Rejected(503);
            Runnable cancel=scheduler.schedule(Math.max(1,(remaining+999_999)/1_000_000),guard::expire);
            if(cancel==null)throw new CodeIntentFixtureSupport.Rejected(503);
            synchronized(guard){if(guard.state==State.OPEN)guard.cancelTimer=cancel;else cancel.run();}
            guard.requireOpen();return guard;
        }catch(RuntimeException failure){guard.close();throw new CodeIntentFixtureSupport.Rejected(503);}
    }
    private CodeIntentFixtureBodyDeadline(Semaphore permits,LongSupplier clock,Runnable closeConnection){this.permits=permits;this.clock=clock;this.closeConnection=closeConnection;start=clock.getAsLong();}
    synchronized void requireOpen(){
        long elapsed=clock.getAsLong()-start;
        if(state==State.EXPIRED||elapsed<lastElapsed||elapsed<0||elapsed>=4_000_000_000L){expire();throw new CodeIntentFixtureSupport.Rejected(503);}
        lastElapsed=elapsed;
    }
    synchronized void bodyComplete(){requireOpen();finish();}
    private synchronized void expire(){
        if(state!=State.OPEN)return;
        state=State.EXPIRED;
        // Serialize transport closure with completion, preventing a stale keep-alive close.
        try{closeConnection.run();}catch(RuntimeException ignored){}finally{release();}
    }
    private void finish(){if(state==State.OPEN){state=State.COMPLETE;release();}}
    private void release(){if(cancelTimer!=null){var cancel=cancelTimer;cancelTimer=null;try{cancel.run();}catch(RuntimeException ignored){}}if(!released){released=true;permits.release();}}
    @Override public synchronized void close(){finish();}
}
