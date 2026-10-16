package com.arman.bank.sso.fixture;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class VisibilityStateTest {
    @Test void oneHoldRequiresExactImmutableTupleAndCancelUnblocksIdempotently() {
        var armed=VisibilityState.arm("realm","subject","client","FAIL");
        try {
            assertNotEquals(armed.token,armed.nonce);
            assertThrows(IllegalStateException.class,()->VisibilityState.arm("realm","subject","client","COMPLETE"));
            assertThrows(IllegalStateException.class,()->VisibilityState.hold(new VisibilityState.Tuple("realm","other","session","client","internal")));
            var tuple=new VisibilityState.Tuple("realm","subject","session","client","internal");
            assertSame(armed,VisibilityState.hold(tuple));
            assertEquals(tuple,VisibilityState.get(armed.token).tuple);
            assertThrows(IllegalStateException.class,()->VisibilityState.hold(tuple));
            VisibilityState.release(armed,true); VisibilityState.release(armed,false);
            assertEquals("CANCELLED",armed.state); assertEquals(0,armed.release.getCount());
        } finally { VisibilityState.finish(armed); }
        assertSame(armed,VisibilityState.get(armed.token));
        var next=VisibilityState.arm("realm","subject","client","COMPLETE");
        try { assertThrows(IllegalArgumentException.class,()->VisibilityState.get(armed.token)); }
        finally { VisibilityState.release(next,true); VisibilityState.finish(next); }
    }
}
