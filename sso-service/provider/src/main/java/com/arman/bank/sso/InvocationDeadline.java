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
    private final long budget;
    private final long originalOrigin,originalBudget;
    private long lastElapsed;
    private boolean closed;
    private Registration exchange,body;

    static InvocationDeadline start() { return new InvocationDeadline(System::nanoTime); }
    static InvocationDeadline startCapped(Duration remaining) { return new InvocationDeadline(System::nanoTime,remaining); }
    InvocationDeadline(LongSupplier clock) { this(clock,Duration.ofNanos(BUDGET)); }
    InvocationDeadline(LongSupplier clock,Duration remaining) { this(clock,0,remaining,false); }
    /** Retains the caller's original sample: construction pauses consume that budget. */
    InvocationDeadline(LongSupplier clock,long originalOrigin,Duration remaining) { this(clock,originalOrigin,remaining,true); }
    private InvocationDeadline(LongSupplier clock,long originalOrigin,Duration remaining,boolean retainOrigin) {
        this.clock=Objects.requireNonNull(clock);
        if(remaining==null||remaining.isNegative()||remaining.isZero())throw new IllegalArgumentException("Invalid invocation budget");
        final long limit;try{limit=remaining.toNanos();}catch(ArithmeticException overflow){throw new IllegalArgumentException("Invalid invocation budget");}
        this.budget=Math.min(BUDGET,limit);this.start=clock.getAsLong();
        this.originalOrigin=retainOrigin?originalOrigin:start;this.originalBudget=Math.min(BUDGET,limit);
        long originalElapsed=start-this.originalOrigin;
        if(originalElapsed<0||originalElapsed>=originalBudget)throw new Expired();
    }

    Duration remaining() {
        long elapsed,originalRemaining;
        boolean invalid;
        synchronized(this) {
            elapsed=clock.getAsLong()-start; // Subtraction tolerates nanoTime signed wrap.
            long originalElapsed=(start-originalOrigin)+elapsed;
            originalRemaining=originalBudget-originalElapsed;
            invalid=closed || elapsed<lastElapsed || elapsed<0 || elapsed>=budget || originalElapsed<0 || originalRemaining<=0;
            if(!invalid) lastElapsed=elapsed;
        }
        if(invalid) { cancelOwnedExchange(); throw new Expired(); }
        return Duration.ofNanos(Math.min(budget-elapsed,originalRemaining));
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
            long originalElapsed=(start-originalOrigin)+elapsed;
            late=closed || elapsed<lastElapsed || elapsed<0 || elapsed>=budget || originalElapsed<0 || originalElapsed>=originalBudget;
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
