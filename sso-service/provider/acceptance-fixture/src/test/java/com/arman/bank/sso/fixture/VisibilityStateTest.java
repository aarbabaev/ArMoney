package com.arman.bank.sso.fixture;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class VisibilityStateTest {
    @Test void oneHoldRequiresExactImmutableTupleAndCancelUnblocksIdempotently() {
        var armed=VisibilityState.arm("realm","subject","client","FAIL");
        try {
            assertEquals(VisibilityState.Boundary.NOT_ENTERED,armed.boundary);
            VisibilityState.boundary(VisibilityState.Boundary.ENTERED);
            assertEquals(VisibilityState.Boundary.ENTERED,VisibilityState.get(armed.token).boundary);
            assertNotEquals(armed.token,armed.nonce);
            assertThrows(IllegalStateException.class,()->VisibilityState.arm("realm","subject","client","COMPLETE"));
            assertThrows(IllegalStateException.class,()->VisibilityState.hold(new VisibilityState.Tuple("realm","other","session","client","internal")));
            var tuple=new VisibilityState.Tuple("realm","subject","session","client","internal");
            assertSame(armed,VisibilityState.hold(tuple));
            assertEquals(VisibilityState.Boundary.HELD,armed.boundary);
            assertEquals(tuple,VisibilityState.get(armed.token).tuple);
            assertThrows(IllegalStateException.class,()->VisibilityState.hold(tuple));
            VisibilityState.release(armed,true); VisibilityState.release(armed,false);
            assertEquals("CANCELLED",armed.state); assertEquals(0,armed.release.getCount());
        } finally { VisibilityState.finish(armed); }
        assertSame(armed,VisibilityState.get(armed.token));
        var next=VisibilityState.arm("realm","subject","client","COMPLETE");
        try {
            assertEquals(VisibilityState.Boundary.NOT_ENTERED,next.boundary);
            VisibilityState.boundary(armed,VisibilityState.Boundary.WAIT_EXPIRED);
            assertEquals(VisibilityState.Boundary.NOT_ENTERED,next.boundary);
            assertThrows(IllegalArgumentException.class,()->VisibilityState.get(armed.token));
        }
        finally { VisibilityState.release(next,true); VisibilityState.finish(next); }
    }
}
