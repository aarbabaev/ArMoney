package com.arman.bank.sso;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@ResourceLock("code-intent-fixture-install")
class CodeIntentFixtureBootstrapTest {
    static final String ISSUER="https://fixture.example/sso/realms/armoney";
    static CodeIntentFixtureSupport.Settings settings(){return new CodeIntentFixtureSupport.Settings("x".repeat(40),"p".repeat(40),"a".repeat(40),"b".repeat(40),ISSUER,"11111111-1111-4111-8111-111111111111","armoney-ios");}
    static Map<String,String> config(){var c=new HashMap<String,String>();
        c.put("ARMONEY_LOGIN_PROOF_ENABLED","true");c.put("SSO_ISSUER",ISSUER);c.put("SSO_VERIFICATION_CLIENT_IDS","armoney-ios");
        c.put("ARMONEY_SSO_MANAGED_PAIRS","[{\"issuer\":\""+ISSUER+"\",\"client_id\":\"armoney-ios\"}]");
        c.put("ARMONEY_AUTH_BRIDGE_URL","http://auth-service:8080");c.put("SSO_LOGIN_PROOF_WRITE_KEY","w".repeat(40));c.put("SSO_CODE_INTENT_LOOKUP_KEY","l".repeat(40));c.put("SSO_LOGIN_PROOF_CONFIRM_KEY","c".repeat(40));c.put("AUTH_TO_SSO_STATUS_KEY","s".repeat(40));c.put("ARMONEY_PROVIDER_BINDING_KEY","b".repeat(40));
        c.put("SSO_CODE_INTENT_CODE_KEY_IDS","current");c.put("SSO_LOGIN_PROOF_WRITER_ID","provider");c.put("SSO_CODE_INTENT_ACTORS","provider");return c;}
    static Map<String,byte[]> materials(){byte[] bytes=new byte[32];Arrays.fill(bytes,(byte)7);return new HashMap<>(Map.of("current",bytes));}
    static final class Fake implements CodeIntentFixtureBootstrap.Factory {
        final List<String> events=new ArrayList<>();String fail;Runnable tick;Map<String,String> copiedConfig;Map<String,byte[]> copiedMaterial;boolean maintenanceFailure;
        void event(String name){events.add(name);if(name.equals(fail))throw new IllegalStateException("synthetic-private-value");}
        public AutoCloseable resources(Map<String,String> c,Map<String,byte[]> m){event("resources");copiedConfig=c;copiedMaterial=m;return ()->event("resources-close");}
        public AutoCloseable transport(CodeIntentFixtureSupport.Settings s){event("transport");return ()->event("transport-close");}
        public CodeIntentFixtureBootstrap.Owner support(CodeIntentFixtureSupport.Settings s,AutoCloseable r,AutoCloseable t){event("support");return new CodeIntentFixtureBootstrap.Owner(){
            public void install(){event("install");}public void maintenance(){event("tick");if(maintenanceFailure)throw new IllegalStateException();}
            public void close(){event("support-close");try{t.close();}catch(Exception e){throw new IllegalStateException();}finally{try{r.close();}catch(Exception e){throw new IllegalStateException();}}}
        };}
        public CodeIntentFixtureBootstrap.Maintenance maintenance(){event("scheduler");return new CodeIntentFixtureBootstrap.Maintenance(){public void start(Runnable task){event("schedule");tick=task;}public void stop(){event("stop");}public boolean await(){event("join");return true;}};}
    }
    @Test void absentFalseAndMalformedAllocateNothing(){var f=new Fake();
        assertTrue(CodeIntentFixtureBootstrap.start(Map.of(),Map.of(),null,f).isEmpty());
        assertTrue(CodeIntentFixtureBootstrap.start(Map.of("ARMONEY_LOGIN_PROOF_ENABLED","false"),Map.of(),null,f).isEmpty());
        assertThrows(IllegalStateException.class,()->CodeIntentFixtureBootstrap.start(Map.of("ARMONEY_LOGIN_PROOF_ENABLED","TRUE"),Map.of(),null,f));assertTrue(f.events.isEmpty());
    }
    @Test void allValidationPrecedesAllocation(){for(String field:List.of("AUTH_TO_SSO_STATUS_KEY","SSO_ISSUER","ARMONEY_AUTH_BRIDGE_URL")){var f=new Fake();var c=config();c.remove(field);assertThrows(IllegalStateException.class,()->CodeIntentFixtureBootstrap.start(c,materials(),settings(),f));assertTrue(f.events.isEmpty());}}
    @Test void retainedMaterialAndFixtureKeysMustBeIndependent(){var f=new Fake();var m=materials();m.put("retained",settings().peerKey().getBytes(java.nio.charset.StandardCharsets.US_ASCII));assertThrows(IllegalStateException.class,()->CodeIntentFixtureBootstrap.start(config(),m,settings(),f));assertTrue(f.events.isEmpty());}
    @Test void copiesBeforeAllocationAndInstallsLastWithOneScheduler(){var f=new Fake();var c=config();var m=materials();try(var owner=CodeIntentFixtureBootstrap.start(c,m,settings(),f).orElseThrow()){
        c.clear();m.get("current")[0]=99;assertEquals(7,f.copiedMaterial.get("current")[0]);assertEquals(ISSUER,f.copiedConfig.get("SSO_ISSUER"));assertThrows(UnsupportedOperationException.class,()->f.copiedConfig.clear());
        assertEquals(List.of("resources","transport","support","scheduler","schedule","install"),f.events);f.tick.run();assertEquals(1,Collections.frequency(f.events,"tick"));
    }assertEquals(1,Collections.frequency(f.events,"support-close"));assertTrue(f.events.indexOf("join")<f.events.indexOf("support-close"));}
    @Test void duplicateOwnerDoesNotAllocateAndCloseIsIdempotent(){var f=new Fake();var owner=CodeIntentFixtureBootstrap.start(config(),materials(),settings(),f).orElseThrow();try{
        var other=new Fake();assertThrows(IllegalStateException.class,()->CodeIntentFixtureBootstrap.start(config(),materials(),settings(),other));assertTrue(other.events.isEmpty());
    }finally{owner.close();owner.close();}assertEquals(1,Collections.frequency(f.events,"support-close"));assertEquals(1,Collections.frequency(f.events,"stop"));}
    @Test void eachPartialFailureClosesOnlyCurrentOwnerAndRedacts(){for(String phase:List.of("resources","transport","support","scheduler","schedule","install")){var f=new Fake();f.fail=phase;
        var failure=assertThrows(IllegalStateException.class,()->CodeIntentFixtureBootstrap.start(config(),materials(),settings(),f));assertFalse(failure.toString().contains("synthetic-private-value"));assertNull(failure.getCause());
        assertEquals(phase.equals("resources")?0:1,Collections.frequency(f.events,"resources-close"));
        assertEquals(List.of("resources","transport").contains(phase)?0:1,Collections.frequency(f.events,"transport-close"));
        assertEquals(List.of("scheduler","schedule","install").contains(phase)?1:0,Collections.frequency(f.events,"support-close"));
    }}
    @Test void maintenanceFailureClosesSupportAndStopsFutureWork(){var f=new Fake();var owner=CodeIntentFixtureBootstrap.start(config(),materials(),settings(),f).orElseThrow();f.maintenanceFailure=true;f.tick.run();f.tick.run();owner.close();assertEquals(1,Collections.frequency(f.events,"tick"));assertEquals(1,Collections.frequency(f.events,"support-close"));assertEquals(1,Collections.frequency(f.events,"stop"));}
    @Test void shutdownFailureStillAttemptsJoinAndSupportCleanup(){var f=new Fake();var owner=CodeIntentFixtureBootstrap.start(config(),materials(),settings(),f).orElseThrow();f.fail="stop";
        var failure=assertThrows(IllegalStateException.class,owner::close);assertNull(failure.getCause());assertFalse(failure.toString().contains("synthetic-private-value"));
        assertTrue(f.events.contains("join"));assertEquals(1,Collections.frequency(f.events,"support-close"));assertEquals(1,Collections.frequency(f.events,"resources-close"));
    }
    @Test void factoryLifecycleNeverInitializesAbsentOwner(){assertDoesNotThrow(CodeIntentFixtureBootstrap::postInit);assertDoesNotThrow(CodeIntentFixtureBootstrap::closeInstalled);}
}
