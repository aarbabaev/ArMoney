package com.arman.bank.sso;

import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;

class InvocationDeadlineTest {
    @Test void oneStartCoversSerializationSelectionAndBothCallsWithoutRenewal() {
        var clock=new AtomicLong(100); var deadline=new InvocationDeadline(clock::get);
        clock.addAndGet(1_000_000_000); assertEquals(Duration.ofSeconds(3),deadline.remaining());
        var lookup=new CompletableFuture<>(); var registration=deadline.registerExchange(lookup);
        lookup.complete(null); registration.close();
        clock.addAndGet(2_000_000_000); assertEquals(Duration.ofSeconds(1),deadline.remaining());
        var confirm=new CompletableFuture<>(); deadline.registerExchange(confirm);
        clock.addAndGet(1_000_000_000);
        assertThrows(InvocationDeadline.Expired.class,deadline::requireOpen);
        assertTrue(confirm.isCancelled()); assertFalse(lookup.isCancelled());
    }
    @Test void equalityExpiresAndLateFutureAndBodyAreCancelled() {
        var clock=new AtomicLong(); var deadline=new InvocationDeadline(clock::get);
        clock.set(4_000_000_000L);
        var future=new CompletableFuture<>();
        assertThrows(InvocationDeadline.Expired.class,()->deadline.registerExchange(future));
        assertTrue(future.isCancelled());
        var cancellations=new AtomicInteger();
        assertThrows(InvocationDeadline.Expired.class,()->deadline.registerBody(cancellations::incrementAndGet));
        assertEquals(1,cancellations.get());
    }
    @Test void closeIsIdempotentAndDoesNotCancelAnotherInvocation() {
        var clock=new AtomicLong(); var first=new InvocationDeadline(clock::get); var other=new InvocationDeadline(clock::get);
        var owned=new CompletableFuture<>(); var unrelated=new CompletableFuture<>(); var count=new AtomicInteger();
        first.registerExchange(owned); first.registerBody(count::incrementAndGet); other.registerExchange(unrelated);
        first.close(); first.close(); first.cancelOwnedExchange();
        assertTrue(owned.isCancelled()); assertFalse(unrelated.isCancelled()); assertEquals(1,count.get());
        assertThrows(InvocationDeadline.Expired.class,first::remaining); other.requireOpen(); other.close();
    }
    @Test void failingCancellationDoesNotPreventOtherOwnedCancellation() {
        var deadline=new InvocationDeadline(()->0L); var future=new CompletableFuture<>();
        deadline.registerBody(()->{throw new IllegalStateException("private detail");}); deadline.registerExchange(future);
        assertDoesNotThrow(deadline::close); assertTrue(future.isCancelled());
    }
    @Test void completedBodyDetachesButDuplicateActiveRegistrationRejects() {
        var deadline=new InvocationDeadline(()->0L); var count=new AtomicInteger();
        var completed=deadline.registerBody(count::incrementAndGet); completed.close();
        deadline.registerBody(count::incrementAndGet);
        assertThrows(IllegalStateException.class,()->deadline.registerBody(count::incrementAndGet));
        deadline.close(); assertEquals(2,count.get()); // Rejected incoming resource is cancelled too.
    }
    @Test void reentrantThrowingHookCannotSkipOtherResourcesOrRenewOwner() {
        var deadline=new InvocationDeadline(()->0L); var future=new CompletableFuture<>(); var late=new AtomicInteger();
        deadline.registerBody(()->{
            deadline.close();
            assertThrows(InvocationDeadline.Expired.class,()->deadline.registerBody(late::incrementAndGet));
            throw new AssertionError("private callback detail");
        });
        deadline.registerExchange(future);
        assertDoesNotThrow(deadline::close); assertTrue(future.isCancelled()); assertEquals(1,late.get());
        assertThrows(InvocationDeadline.Expired.class,deadline::requireOpen);
    }
    @Test void nanoTimeWrapWorksButBackwardClockFailsClosed() {
        var clock=new AtomicLong(Long.MAX_VALUE-10); var deadline=new InvocationDeadline(clock::get);
        clock.addAndGet(20); assertEquals(Duration.ofNanos(3_999_999_980L),deadline.remaining());
        clock.decrementAndGet(); assertThrows(InvocationDeadline.Expired.class,deadline::requireOpen);
        assertThrows(InvocationDeadline.Expired.class,deadline::requireOpen);
    }
    @Test void expiryOrBackwardJumpBetweenPrecheckAndPublicationCancelsIncomingAndOwned() {
        for(long publicationTime:new long[]{4_000_000_000L,99L}) {
            var calls=new AtomicInteger(); var activated=new java.util.concurrent.atomic.AtomicBoolean();
            var deadline=new InvocationDeadline(()->{
                if(!activated.get()) return 100L;
                return calls.incrementAndGet()==1?200L:publicationTime==99L?99L:4_000_000_100L;
            });
            var existing=new CompletableFuture<>(); deadline.registerExchange(existing);
            var incoming=new AtomicInteger(); activated.set(true);
            assertThrows(InvocationDeadline.Expired.class,()->deadline.registerBody(incoming::incrementAndGet));
            assertTrue(existing.isCancelled()); assertEquals(1,incoming.get());
            assertThrows(InvocationDeadline.Expired.class,deadline::requireOpen);
            deadline.close(); assertEquals(1,incoming.get());
        }
    }
}
