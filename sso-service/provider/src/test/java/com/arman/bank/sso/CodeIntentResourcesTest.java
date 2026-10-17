package com.arman.bank.sso;

import org.junit.jupiter.api.Test;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class CodeIntentResourcesTest {
    private static Map<String,String> enabled() {
        var env=new HashMap<String,String>();
        env.put("ARMONEY_LOGIN_PROOF_ENABLED","true"); env.put("SSO_ISSUER","https://issuer.example/sso/realms/armoney");
        env.put("ARMONEY_SSO_MANAGED_PAIRS","[{\"issuer\":\"https://issuer.example/sso/realms/armoney\",\"client_id\":\"armoney-ios\"}]");
        env.put("SSO_VERIFICATION_CLIENT_IDS","armoney-ios"); env.put("ARMONEY_AUTH_BRIDGE_URL","http://auth-service:8081");
        env.put("SSO_LOGIN_PROOF_WRITE_KEY","w".repeat(40)); env.put("SSO_CODE_INTENT_LOOKUP_KEY","l".repeat(40)); env.put("SSO_LOGIN_PROOF_CONFIRM_KEY","c".repeat(40));
        env.put("ARMONEY_PROVIDER_BINDING_KEY","b".repeat(40)); env.put("SSO_CODE_INTENT_CODE_KEY_IDS","current,previous");
        env.put("SSO_LOGIN_PROOF_WRITER_ID","provider"); env.put("SSO_CODE_INTENT_ACTORS","provider"); return env;
    }
    private static Map<String,byte[]> material() { byte[] current=new byte[32]; Arrays.fill(current,(byte)7); return Map.of("current",current,"previous",new byte[32]); }
    @Test void disabledAndInvalidConfigurationAllocateNoHttp() {
        var calls=new AtomicInteger();
        java.util.function.Function<java.net.URI,CodeIntentHttp> forbidden=uri->{calls.incrementAndGet();throw new AssertionError("Unexpected allocation");};
        assertTrue(CodeIntentResources.open(Map.of(),Map.of(),forbidden,null).isEmpty());
        assertThrows(IllegalArgumentException.class,()->CodeIntentResources.open(Map.of("ARMONEY_SSO_MANAGED_PAIRS","invalid"),Map.of(),forbidden,null));
        assertThrows(IllegalArgumentException.class,()->CodeIntentResources.open(enabled(),Map.of(),forbidden,null));
        assertEquals(0,calls.get());
    }
    @Test void oneOwnedTransportAndBothCodeKeysAreReleasedIdempotently() {
        var fake=new CodeIntentHttpTest.Fake(); var allocations=new AtomicInteger(); var env=enabled(); var local=material();
        var resources=CodeIntentResources.open(env,local,uri->{allocations.incrementAndGet();return new CodeIntentHttp(uri,fake,true);},null).orElseThrow();
        assertEquals(1,allocations.get()); assertNotNull(resources.client()); assertEquals(2,resources.keys().keys().size());
        local.get("current")[0]=99; assertEquals(7,resources.keys().keys().getFirst().bytes()[0]);
        assertEquals("CodeIntentResources[REDACTED]",resources.toString());
        resources.close(); resources.close(); assertEquals(1,fake.shutdowns);
        assertThrows(IllegalStateException.class,resources::client); assertThrows(IllegalStateException.class,resources::keys);
    }
    @Test void failureAfterHttpAllocationClosesOwnerWithoutLeakingFactoryException() {
        var fake=new CodeIntentHttpTest.Fake();
        var failure=assertThrows(IllegalArgumentException.class,()->CodeIntentResources.open(enabled(),material(),
                uri->new CodeIntentHttp(uri,fake,true),(transport,configuration)->{throw new IllegalStateException("synthetic-secret-url");}));
        assertEquals(1,fake.shutdowns); assertNull(failure.getCause()); assertFalse(failure.toString().contains("synthetic-secret-url"));
    }
    @Test void injectedCallerOwnedHttpIsNotShutDownByResourceOwner() {
        var fake=new CodeIntentHttpTest.Fake();
        var resources=CodeIntentResources.open(enabled(),material(),uri->new CodeIntentHttp(uri,fake),null).orElseThrow();
        resources.close(); assertEquals(0,fake.shutdowns);
    }
}
