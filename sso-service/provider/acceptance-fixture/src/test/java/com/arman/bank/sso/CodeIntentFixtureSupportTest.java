package com.arman.bank.sso;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;

@ResourceLock("code-intent-fixture-install")
class CodeIntentFixtureSupportTest {
    static final String ISSUER="https://identity.fixture/sso/realms/armoney",CONTROL="c".repeat(40),BINDING="b".repeat(40);
    static final class Harness implements AutoCloseable {
        final AtomicLong nanos=new AtomicLong();final CodeIntentFixtureTransportTest.Fake peer=new CodeIntentFixtureTransportTest.Fake(),production=new CodeIntentFixtureTransportTest.Fake();final CodeIntentFixtureSupport support;
        Harness(){
            var env=new HashMap<String,String>();env.put("ARMONEY_LOGIN_PROOF_ENABLED","true");env.put("ARMONEY_SSO_MANAGED_PAIRS","[{\"issuer\":\""+ISSUER+"\",\"client_id\":\"armoney-ios\"}]");env.put("SSO_ISSUER",ISSUER);env.put("SSO_VERIFICATION_CLIENT_IDS","armoney-ios");env.put("ARMONEY_AUTH_BRIDGE_URL","http://auth-service:8080");env.put("SSO_LOGIN_PROOF_WRITE_KEY","w".repeat(40));env.put("SSO_LOGIN_PROOF_CONFIRM_KEY","f".repeat(40));env.put("SSO_CODE_INTENT_LOOKUP_KEY","l".repeat(40));env.put("ARMONEY_PROVIDER_BINDING_KEY",BINDING);env.put("SSO_LOGIN_PROOF_WRITER_ID","provider");env.put("SSO_CODE_INTENT_ACTORS","provider");env.put("SSO_CODE_INTENT_CODE_KEY_IDS","current");
            var keys=new byte[32];Arrays.fill(keys,(byte)7);
            var resources=CodeIntentResources.open(env,Map.of("current",keys),uri->new CodeIntentHttp(uri,production),null).orElseThrow();
            support=new CodeIntentFixtureSupport(new CodeIntentFixtureSupport.Settings(CONTROL,CodeIntentFixtureTransportTest.PEER,CodeIntentFixtureTransportTest.PROVIDER,BINDING,ISSUER,"subject","armoney-ios"),resources,new CodeIntentFixtureTransport(CodeIntentFixtureTransportTest.PROVIDER,CodeIntentFixtureTransportTest.PEER,peer),nanos::get,Clock.systemUTC());
        }
        String ready(){String handle=support.arm("subject","armoney-ios");var e=support.entry(handle);e.flow=new CodeIntentFixtureSupport.Flow(ISSUER,"subject","armoney-ios","root","tab",SessionBinding.derive(BINDING,"realm","root","tab","internal"),"11111111-1111-4111-8111-111111111111","2026-10-18T00:00:00Z");e.setupDeadline=90_000_000_000L;e.state="READY";return handle;}
        public void close(){support.close();}
    }
    @Test void handlesAreCanonicalSingleExperimentAndStateIsPure(){try(var h=new Harness()){
        String handle=h.support.arm("subject","armoney-ios");assertEquals(32,SessionBinding.decode(handle).length);assertEquals("ARMED",h.support.state(handle));
        assertThrows(CodeIntentFixtureSupport.Rejected.class,()->h.support.arm("subject","armoney-ios"));assertEquals(0,h.peer.calls);assertEquals(0,h.production.calls);
        assertEquals("CANCELLED",h.support.cancel(handle));assertEquals("CANCELLED",h.support.cancel(handle));
    }}
    @Test void bindIsOneShotAndControlUuidIsNotStageWriteUuid(){try(var h=new Harness()){
        String handle=h.ready(),stage=CodeIntentFixtureSupport.handle();var e=h.support.entry(handle);var command=UUID.randomUUID();
        h.peer.body="{\"stage_handle\":\""+stage+"\"}";
        assertEquals("STAGE_BOUND",h.support.bindStage(handle,stage,command));assertNotEquals(command,e.stage);assertEquals(1,h.peer.calls);
        assertEquals("STAGE_BOUND",h.support.bindStage(handle,stage,command));assertEquals(2,h.peer.calls);
        assertThrows(CodeIntentFixtureSupport.Rejected.class,()->h.support.bindStage(handle,CodeIntentFixtureSupport.handle(),command));
    }}
    @Test void readinessMismatchFencesFailedAndNeverRetries(){try(var h=new Harness()){
        String handle=h.ready();h.peer.body="{\"stage_handle\":\""+CodeIntentFixtureSupport.handle()+"\"}";var command=UUID.randomUUID();String stage=CodeIntentFixtureSupport.handle();
        assertThrows(CodeIntentFixtureSupport.Rejected.class,()->h.support.bindStage(handle,stage,command));assertEquals("FAILED",h.support.state(handle));
        assertThrows(CodeIntentFixtureSupport.Rejected.class,()->h.support.bindStage(handle,stage,command));assertEquals(1,h.peer.calls);
    }}
    @Test void missingTxOrMembershipNeverCallsAuthConfirmation(){try(var h=new Harness()){
        String handle=h.ready();var e=h.support.entry(handle);e.state="STAGED";
        assertThrows(CodeIntentFixtureSupport.Rejected.class,()->h.support.confirm(handle,UUID.randomUUID()));assertEquals(0,h.peer.calls);
        e.txCommitted=true;e.nativeBinding=new LoginProof.Binding(ISSUER,"subject","armoney-ios","native");
        h.peer.body="{\"issuer\":\""+ISSUER+"\",\"subject\":\"subject\",\"client_id\":\"armoney-ios\",\"user_session_id\":\"native\",\"user_session_present\":true,\"client_session_present\":false}";
        var command=UUID.randomUUID();assertThrows(CodeIntentFixtureSupport.Rejected.class,()->h.support.confirm(handle,command));assertEquals(1,h.peer.calls);assertEquals(0,h.production.calls);
        assertThrows(CodeIntentFixtureSupport.Rejected.class,()->h.support.confirm(handle,command));assertEquals(1,h.peer.calls);
    }}
    @Test void originalExpiryDoesNotSlideAndTerminalCapacityIsBounded(){try(var h=new Harness()){
        String handle=h.support.arm("subject","armoney-ios");h.nanos.set(120_000_000_000L);assertThrows(CodeIntentFixtureSupport.Rejected.class,()->h.support.state(handle));
        for(int i=0;i<15;i++)h.support.cancel(h.support.arm("subject","armoney-ios"));
        assertThrows(CodeIntentFixtureSupport.Rejected.class,()->h.support.arm("subject","armoney-ios"));
    }}
    @Test void originalExpiryBetweenMembershipAndLookupOrLookupAndConfirmStopsNextCall(){
        for(boolean expireOnMembership:new boolean[]{true,false})try(var h=new Harness()){
            String handle=h.ready();var e=h.support.entry(handle);e.state="STAGED";e.txCommitted=true;e.deadline=200_000_000L;
            e.nativeBinding=new LoginProof.Binding(ISSUER,"subject","armoney-ios","native");e.codeId="11111111-1111-4111-8111-111111111111";e.codeExpiry=java.time.Instant.now().plusSeconds(60);
            h.peer.body="{\"issuer\":\""+ISSUER+"\",\"subject\":\"subject\",\"client_id\":\"armoney-ios\",\"user_session_id\":\"native\",\"user_session_present\":true,\"client_session_present\":true}";
            h.production.body="{\"code_key_id\":\"current\"}";
            if(expireOnMembership)h.peer.onSend=()->h.nanos.set(200_000_000L);else h.production.onSend=()->h.nanos.set(200_000_000L);
            assertThrows(CodeIntentFixtureSupport.Rejected.class,()->h.support.confirm(handle,UUID.randomUUID()));
            assertEquals(1,h.peer.calls);assertEquals(expireOnMembership?0:1,h.production.calls);
            if(!expireOnMembership)assertEquals("/internal/sso-code-intents/key",h.production.request.uri().getPath());
        }
    }
    @Test void replayReadinessIsFreshAndCannotAcceptResponseAfterOriginalSetupWindow(){try(var h=new Harness()){
        String handle=h.ready(),stage=CodeIntentFixtureSupport.handle();var command=UUID.randomUUID();h.peer.body="{\"stage_handle\":\""+stage+"\"}";
        assertEquals("STAGE_BOUND",h.support.bindStage(handle,stage,command));
        h.peer.onSend=()->h.nanos.set(90_000_000_000L);
        assertThrows(CodeIntentFixtureSupport.Rejected.class,()->h.support.bindStage(handle,stage,command));
        assertEquals(2,h.peer.calls);assertEquals("FAILED",h.support.state(handle));
    }}
    @Test void readyPublicationRechecksSetupAfterEarlierSuccessfulResponseCheck(){try(var h=new Harness()){
        String handle=h.ready();var e=h.support.entry(handle);e.state="PREPARING";
        h.nanos.set(89_999_999_999L);assertEquals("PREPARING",h.support.state(handle));
        h.nanos.set(90_000_000_000L);
        assertThrows(CodeIntentFixtureSupport.Rejected.class,()->h.support.publishReady(e));
        assertEquals("PREPARING",e.state);assertEquals(0,h.peer.calls);
    }}
    @Test void preparationDoesNotAcceptLateReadyState()throws Exception{try(var h=new Harness()){
        String handle=h.ready();var e=h.support.entry(handle);e.state="FLOW_BOUND";
        h.peer.beforeSend=request->{if(request.uri().getPath().endsWith("/prepare")){h.peer.status=202;h.peer.body="{\"state\":\"ACCEPTED\"}";}else{h.peer.status=200;h.peer.body="{\"state\":\"READY\"}";h.peer.onSend=()->h.nanos.set(90_000_000_000L);}};
        h.support.prepare(handle);try{e.work.get(2,java.util.concurrent.TimeUnit.SECONDS);}catch(java.util.concurrent.CancellationException expected){}
        assertEquals("FAILED",h.support.state(handle));assertEquals(2,h.peer.calls);
    }}
}
