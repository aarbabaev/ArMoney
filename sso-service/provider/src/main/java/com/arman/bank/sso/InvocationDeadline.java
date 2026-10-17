package com.arman.bank.sso;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Future;
import java.util.function.LongSupplier;

/** One invocation budget, created before serialization/DNS. No scheduler or worker is allocated.
 * Cancellation requests do not establish physical DNS/HTTP termination.
 */
final class InvocationDeadline implements AutoCloseable {
    private static final long BUDGET=4_000_000_000L;
    private final LongSupplier clock;
    private final long start;
    private long lastElapsed;
    private boolean closed;
    private Registration exchange,body;

    static InvocationDeadline start() { return new InvocationDeadline(System::nanoTime); }
    InvocationDeadline(LongSupplier clock) { this.clock=Objects.requireNonNull(clock); this.start=clock.getAsLong(); }

    Duration remaining() {
        long elapsed;
        boolean invalid;
        synchronized(this) {
            elapsed=clock.getAsLong()-start; // Subtraction tolerates nanoTime signed wrap.
            invalid=closed || elapsed<lastElapsed || elapsed<0 || elapsed>=BUDGET;
            if(!invalid) lastElapsed=elapsed;
        }
        if(invalid) { cancelOwnedExchange(); throw new Expired(); }
        return Duration.ofNanos(BUDGET-elapsed);
    }
    void requireOpen() { remaining(); }

    Registration registerExchange(Future<?> future) {
        Objects.requireNonNull(future);
        return register(()->future.cancel(true),true);
    }
    Registration registerBody(Runnable cancellation) { return register(Objects.requireNonNull(cancellation),false); }

    private Registration register(Runnable cancellation,boolean isExchange) {
        Registration registration=new Registration(cancellation,isExchange);
        try { requireOpen(); }
        catch(Expired expired) { registration.cancel(); throw expired; }
        boolean late,duplicate;
        synchronized(this) {
            long elapsed=clock.getAsLong()-start;
            late=closed || elapsed<lastElapsed || elapsed<0 || elapsed>=BUDGET;
            if(late) closed=true;
            else lastElapsed=elapsed;
            duplicate=!late && (isExchange?exchange:body)!=null;
            if(!late && !duplicate) {
                if(isExchange) exchange=registration; else body=registration;
            }
        }
        if(late) { cancelOwnedExchange(); registration.cancel(); throw new Expired(); }
        if(duplicate) { registration.cancel(); throw new IllegalStateException("Invocation resource already registered"); }
        return registration;
    }

    /** Terminal abort. Cancels only resources registered with this invocation. */
    void cancelOwnedExchange() {
        List<Registration> pending=new ArrayList<>(2);
        synchronized(this) {
            closed=true;
            if(exchange!=null) pending.add(exchange);
            if(body!=null) pending.add(body);
            exchange=null; body=null;
        }
        // Do not invoke external cancellation hooks while holding the owner monitor.
        pending.forEach(Registration::cancel);
    }
    @Override public void close() { cancelOwnedExchange(); }

    final class Registration implements AutoCloseable {
        private final Runnable cancellation;
        private final boolean isExchange;
        private boolean detached,cancelled;
        private Registration(Runnable cancellation,boolean isExchange) { this.cancellation=cancellation; this.isExchange=isExchange; }
        /** Detach only after that exchange/body has completed; never resets the budget. */
        @Override public void close() {
            synchronized(InvocationDeadline.this) {
                detached=true;
                if(isExchange && exchange==this) exchange=null;
                if(!isExchange && body==this) body=null;
            }
        }
        private void cancel() {
            synchronized(InvocationDeadline.this) {
                if(detached||cancelled) return;
                cancelled=true;
            }
            try { cancellation.run(); }
            catch(Throwable ignored) { /* Best-effort cancellation must attempt every owned resource; never expose hook details. */ }
        }
    }
    static final class Expired extends IllegalStateException {
        Expired() { super("Invocation deadline unavailable"); }
    }
}
