package com.arman.bank.sso;

import org.junit.jupiter.api.Test;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class CodeIntentFixtureBodyDeadlineTest {
    @Test void expiryClosesOnceReleasesPermitAndRejectsMutation(){
        var permits=new Semaphore(1);var clock=new AtomicLong();var closes=new AtomicInteger();var cancels=new AtomicInteger();var timer=new AtomicReference<Runnable>();
        var guard=CodeIntentFixtureBodyDeadline.create(permits,clock::get,closes::incrementAndGet,(ms,task)->{assertEquals(4000,ms);timer.set(task);return cancels::incrementAndGet;});
        assertEquals(0,permits.availablePermits());clock.set(4_000_000_000L);timer.get().run();timer.get().run();guard.close();
        assertEquals(1,closes.get());assertEquals(1,cancels.get());assertEquals(1,permits.availablePermits());assertThrows(RuntimeException.class,guard::requireOpen);
    }
    @Test void successfulCompletionWinsAgainstStaleTimerAndDoesNotCloseReusedConnection(){
        var permits=new Semaphore(1);var closes=new AtomicInteger();var timer=new AtomicReference<Runnable>();
        var guard=CodeIntentFixtureBodyDeadline.create(permits,()->0L,closes::incrementAndGet,(ms,task)->{timer.set(task);return ()->{};});
        guard.bodyComplete();timer.get().run();guard.close();assertEquals(0,closes.get());assertEquals(1,permits.availablePermits());
    }
    @Test void schedulerFailureAndCapacityFailClosedWithoutLeakingPermit(){
        var permits=new Semaphore(1);assertThrows(RuntimeException.class,()->CodeIntentFixtureBodyDeadline.create(permits,()->0L,()->{},(ms,task)->{throw new IllegalStateException("private");}));assertEquals(1,permits.availablePermits());
        assertThrows(RuntimeException.class,()->CodeIntentFixtureBodyDeadline.create(new Semaphore(0),()->0L,()->{},(ms,task)->{throw new AssertionError("No scheduler after capacity denial");}));
    }
    @Test void dripProgressDoesNotRenewAndBackwardClockDenies(){
        for(boolean backwards:new boolean[]{false,true}){
            var clock=new AtomicLong();var closes=new AtomicInteger();var guard=CodeIntentFixtureBodyDeadline.create(new Semaphore(1),clock::get,closes::incrementAndGet,(ms,task)->()->{});
            clock.set(1_000_000_000L);guard.requireOpen();clock.set(backwards?999_999_999L:4_000_000_000L);
            assertThrows(RuntimeException.class,guard::bodyComplete);assertEquals(1,closes.get());
        }
    }
}
