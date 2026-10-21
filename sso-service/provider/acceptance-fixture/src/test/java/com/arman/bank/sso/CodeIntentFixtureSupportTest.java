package com.arman.bank.sso;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.lang.reflect.Proxy;
import org.keycloak.authentication.RequiredActionContext;
import org.keycloak.models.*;
import org.keycloak.sessions.*;
import static org.junit.jupiter.api.Assertions.*;

@ResourceLock("code-intent-fixture-install")
class CodeIntentFixtureSupportTest {
    static final String ISSUER="https://identity.fixture/sso/realms/armoney",CONTROL="c".repeat(40),BINDING="b".repeat(40);
    static final class Harness implements AutoCloseable {
        final AtomicLong nanos;final CodeIntentFixtureTransportTest.Fake peer=new CodeIntentFixtureTransportTest.Fake(),production=new CodeIntentFixtureTransportTest.Fake();final CodeIntentFixtureSupport support;
        Harness(){this(new AtomicLong(),TimeUnit.NANOSECONDS::sleep);}
        Harness(AtomicLong nanos,CodeIntentFixtureSupport.Sleeper sleeper){
            this.nanos=nanos;
            var env=new HashMap<String,String>();env.put("ARMONEY_LOGIN_PROOF_ENABLED","true");env.put("ARMONEY_SSO_MANAGED_PAIRS","[{\"issuer\":\""+ISSUER+"\",\"client_id\":\"armoney-ios\"}]");env.put("SSO_ISSUER",ISSUER);env.put("SSO_VERIFICATION_CLIENT_IDS","armoney-ios");env.put("ARMONEY_AUTH_BRIDGE_URL","http://auth-service:8080");env.put("SSO_LOGIN_PROOF_WRITE_KEY","w".repeat(40));env.put("SSO_LOGIN_PROOF_CONFIRM_KEY","f".repeat(40));env.put("SSO_CODE_INTENT_LOOKUP_KEY","l".repeat(40));env.put("ARMONEY_PROVIDER_BINDING_KEY",BINDING);env.put("SSO_LOGIN_PROOF_WRITER_ID","provider");env.put("SSO_CODE_INTENT_ACTORS","provider");env.put("SSO_CODE_INTENT_CODE_KEY_IDS","current");
            var keys=new byte[32];Arrays.fill(keys,(byte)7);
            var resources=CodeIntentResources.open(env,Map.of("current",keys),uri->new CodeIntentHttp(uri,production),null).orElseThrow();
            support=new CodeIntentFixtureSupport(new CodeIntentFixtureSupport.Settings(CONTROL,CodeIntentFixtureTransportTest.PEER,CodeIntentFixtureTransportTest.PROVIDER,BINDING,ISSUER,"subject","armoney-ios"),resources,new CodeIntentFixtureTransport(CodeIntentFixtureTransportTest.PROVIDER,CodeIntentFixtureTransportTest.PEER,peer),nanos::get,Clock.systemUTC(),sleeper);
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
        long original=e.originalDeadline,deadline=e.deadline,setup=e.setupDeadline;
        assertEquals(503,assertThrows(CodeIntentFixtureSupport.Rejected.class,()->h.support.bindStage(handle,e.stage)).status);assertEquals(0,h.peer.calls);
        assertEquals("STAGE_BOUND",h.support.bindStage(handle,command));assertNotEquals(command,e.stage);assertEquals(1,h.peer.calls);assertEquals(stage,e.stageHandle);
        h.nanos.set(1_000_000_000L);
        assertEquals("STAGE_BOUND",h.support.bindStage(handle,command));assertEquals(2,h.peer.calls);assertEquals(stage,e.stageHandle);
        assertEquals(original,e.originalDeadline);assertEquals(deadline,e.deadline);assertEquals(setup,e.setupDeadline);
        assertEquals(1,e.commands.size());assertFalse(e.permissionClaimed);
        assertEquals(409,assertThrows(CodeIntentFixtureSupport.Rejected.class,()->h.support.bindStage(handle,UUID.randomUUID())).status);assertEquals(2,h.peer.calls);
        assertEquals("STAGE_BOUND",h.support.state(handle));assertEquals(2,h.peer.calls);assertEquals(0,h.production.calls);
    }}
    @Test void readinessMismatchFencesFailedAndNeverRetries(){try(var h=new Harness()){
        String handle=h.ready(),stage=CodeIntentFixtureSupport.handle();var command=UUID.randomUUID();
        h.peer.body="{\"stage_handle\":\""+stage+"\"}";assertEquals("STAGE_BOUND",h.support.bindStage(handle,command));
        h.peer.body="{\"stage_handle\":\""+CodeIntentFixtureSupport.handle()+"\"}";
        assertThrows(CodeIntentFixtureSupport.Rejected.class,()->h.support.bindStage(handle,command));assertEquals("FAILED",h.support.state(handle));assertNull(h.support.entry(handle).stageHandle);
        assertThrows(CodeIntentFixtureSupport.Rejected.class,()->h.support.bindStage(handle,command));assertEquals(2,h.peer.calls);
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
        assertEquals("STAGE_BOUND",h.support.bindStage(handle,command));
        h.peer.onSend=()->h.nanos.set(90_000_000_000L);
        assertThrows(CodeIntentFixtureSupport.Rejected.class,()->h.support.bindStage(handle,command));
        assertEquals(2,h.peer.calls);assertEquals("FAILED",h.support.state(handle));
    }}
    @Test void malformedReadinessNeverPinsAHandleOrRetries(){
        for(String response:List.of("{}","{\"stage_handle\":null}","{\"stage_handle\":7}",
                "{\"stage_handle\":\"bad\"}","{\"stage_handle\":\""+"A".repeat(42)+"B\"}",
                "{\"stage_handle\":\""+CodeIntentFixtureSupport.handle()+"\",\"verified\":true}"))try(var h=new Harness()){
            String handle=h.ready();var e=h.support.entry(handle);var command=UUID.randomUUID();h.peer.body=response;
            long original=e.originalDeadline,deadline=e.deadline,setup=e.setupDeadline;
            assertThrows(CodeIntentFixtureSupport.Rejected.class,()->h.support.bindStage(handle,command));
            assertEquals("FAILED",h.support.state(handle));assertNull(e.stageHandle);assertFalse(e.permissionClaimed);
            assertEquals(original,e.originalDeadline);assertEquals(deadline,e.deadline);assertEquals(setup,e.setupDeadline);
            assertThrows(CodeIntentFixtureSupport.Rejected.class,()->h.support.bindStage(handle,command));assertEquals(1,h.peer.calls);
        }
    }
    @Test void cancelExpiryAndLateResponsesNeverPinOrRenew(){
        for(String fault:List.of("cancel","setup","original"))try(var h=new Harness()){
            String handle=h.ready();var e=h.support.entry(handle);var command=UUID.randomUUID();h.peer.body="{\"stage_handle\":\""+CodeIntentFixtureSupport.handle()+"\"}";
            long original=e.originalDeadline,deadline=e.deadline,setup=e.setupDeadline;
            h.peer.onSend=()->{switch(fault){case "cancel"->h.support.cancel(handle);case "setup"->h.nanos.set(setup);case "original"->h.nanos.set(deadline);}};
            assertThrows(CodeIntentFixtureSupport.Rejected.class,()->h.support.bindStage(handle,command));
            assertEquals(fault.equals("cancel")?"CANCELLED":"FAILED",e.state);
            assertNull(e.stageHandle);assertFalse(e.bindBusy);assertFalse(e.permissionClaimed);
            assertEquals(original,e.originalDeadline);assertEquals(deadline,e.deadline);assertEquals(setup,e.setupDeadline);
            assertThrows(CodeIntentFixtureSupport.Rejected.class,()->h.support.bindStage(handle,command));assertEquals(1,h.peer.calls);
        }
    }
    @Test void fullCommandCapacityRejectsBeforeReadiness(){try(var h=new Harness()){
        String handle=h.ready();var e=h.support.entry(handle);
        for(int i=0;i<16;i++)e.commands.put("reserved-"+i,new CodeIntentFixtureSupport.Command(UUID.randomUUID(),"",""));
        assertThrows(CodeIntentFixtureSupport.Rejected.class,()->h.support.bindStage(handle,UUID.randomUUID()));
        assertEquals(16,e.commands.size());assertEquals(0,h.peer.calls);assertFalse(e.bindBusy);assertNull(e.stageHandle);
    }}
    @Test void cancellationAfterSuccessfulBindForbidsReplayAndKeepsOriginalCutoffs(){try(var h=new Harness()){
        String handle=h.ready();var e=h.support.entry(handle);var command=UUID.randomUUID();
        h.peer.body="{\"stage_handle\":\""+CodeIntentFixtureSupport.handle()+"\"}";
        long original=e.originalDeadline,deadline=e.deadline,setup=e.setupDeadline;
        h.support.bindStage(handle,command);assertEquals("CANCELLED",h.support.cancel(handle));
        assertEquals(410,assertThrows(CodeIntentFixtureSupport.Rejected.class,()->h.support.bindStage(handle,command)).status);
        assertEquals(1,h.peer.calls);assertNull(e.stageHandle);assertFalse(e.permissionClaimed);
        assertEquals(original,e.originalDeadline);assertEquals(deadline,e.deadline);assertEquals(setup,e.setupDeadline);
    }}
    @Test void inFlightBindExcludesContinuationAndReplayThenNativePermissionIsConsumedOnce()throws Exception{try(var h=new Harness()){
        String handle=h.ready();var e=h.support.entry(handle);var successes=new AtomicInteger();var context=nativeContext(h,handle,successes);
        String stage=CodeIntentFixtureSupport.handle();h.peer.body="{\"stage_handle\":\""+stage+"\"}";var command=UUID.randomUUID();
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var executor=Executors.newSingleThreadExecutor();
        h.peer.onSend=()->{entered.countDown();await(release);};
        try{
            Future<String> binding=executor.submit(()->h.support.bindStage(handle,command));assertTrue(entered.await(2,TimeUnit.SECONDS));
            assertTrue(e.bindBusy);
            assertEquals(503,assertThrows(CodeIntentFixtureSupport.Rejected.class,()->h.support.bindStage(handle,command)).status);
            assertEquals(409,assertThrows(CodeIntentFixtureSupport.Rejected.class,()->h.support.bindStage(handle,UUID.randomUUID())).status);
            assertThrows(CodeIntentFixtureSupport.Rejected.class,()->h.support.continueNative(context,handle));
            assertEquals(0,successes.get());assertFalse(e.permissionClaimed);assertEquals(1,h.peer.calls);
            release.countDown();assertEquals("STAGE_BOUND",binding.get(2,TimeUnit.SECONDS));h.peer.onSend=()->{};
            h.support.continueNative(context,handle);assertEquals(1,successes.get());assertTrue(e.permissionClaimed);assertEquals(2,h.peer.calls);
            assertThrows(CodeIntentFixtureSupport.Rejected.class,()->h.support.continueNative(context,handle));
            assertEquals(503,assertThrows(CodeIntentFixtureSupport.Rejected.class,()->h.support.bindStage(handle,command)).status);
            assertEquals(1,successes.get());assertEquals(2,h.peer.calls);assertEquals(stage,e.stageHandle);
        }finally{release.countDown();executor.shutdownNow();assertTrue(executor.awaitTermination(2,TimeUnit.SECONDS));}
    }}
    @Test void nativeContinuationRevalidatesPinnedHandleBeforeSuccess(){try(var h=new Harness()){
        String handle=h.ready();var e=h.support.entry(handle);var successes=new AtomicInteger();var context=nativeContext(h,handle,successes);
        h.peer.body="{\"stage_handle\":\""+CodeIntentFixtureSupport.handle()+"\"}";h.support.bindStage(handle,UUID.randomUUID());
        h.peer.body="{\"stage_handle\":\""+CodeIntentFixtureSupport.handle()+"\"}";
        assertThrows(CodeIntentFixtureSupport.Rejected.class,()->h.support.continueNative(context,handle));
        assertEquals(0,successes.get());assertEquals("FAILED",e.state);assertNull(e.stageHandle);assertTrue(e.permissionClaimed);
        assertThrows(CodeIntentFixtureSupport.Rejected.class,()->h.support.continueNative(context,handle));assertEquals(2,h.peer.calls);
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
    @Test void slowPreparationStaysWithinAuthControlBudgetWithReadinessAndCancelHeadroom()throws Exception{
        var nanos=new AtomicLong();var sleeps=new ArrayList<Long>();
        try(var h=new Harness(nanos,delay->{sleeps.add(delay);nanos.addAndGet(delay);})){
            String handle=h.ready();var e=h.support.entry(handle);e.state="FLOW_BOUND";
            long original=e.originalDeadline,deadline=e.deadline;String stage=CodeIntentFixtureSupport.handle();
            var refilled=new AtomicLong();var credits=new AtomicLong(20_000_000_000L);var polls=new ArrayList<Long>();var operations=new HashMap<String,Integer>();
            h.peer.beforeSend=request->{
                long now=nanos.get(),elapsed=now-refilled.getAndSet(now);assertTrue(elapsed>=0);
                long available=Math.min(20_000_000_000L,credits.get()+elapsed*2);
                String operation=request.uri().getPath().substring(request.uri().getPath().lastIndexOf('/')+1);operations.merge(operation,1,Integer::sum);
                if(available<1_000_000_000L){h.peer.status=429;h.peer.body="";return;}
                credits.set(available-1_000_000_000L);h.peer.status=200;
                switch(operation){
                    case "prepare"->{h.peer.status=202;h.peer.body="{\"state\":\"ACCEPTED\"}";}
                    case "state"->{polls.add(now);h.peer.body="{\"state\":\""+(now>=60_000_000_000L?"READY":"PREPARING")+"\"}";}
                    case "readiness"->h.peer.body="{\"stage_handle\":\""+stage+"\"}";
                    case "cancel"->{h.peer.status=204;h.peer.body="";}
                    default->throw new AssertionError("Unexpected fixture operation");
                }
            };
            h.support.prepare(handle);e.work.get(5,TimeUnit.SECONDS);
            assertEquals("READY",h.support.state(handle));assertEquals(61,polls.size());assertEquals(60,sleeps.size());
            for(int i=1;i<polls.size();i++)assertTrue(polls.get(i)-polls.get(i-1)>=1_000_000_000L);
            assertEquals(60_000_000_000L,nanos.get());assertEquals(original,e.originalDeadline);assertEquals(deadline,e.deadline);assertEquals(90_000_000_000L,e.setupDeadline);
            int pureReadCalls=h.peer.calls;assertEquals("READY",h.support.state(handle));assertEquals(pureReadCalls,h.peer.calls);
            var command=UUID.randomUUID();assertEquals("STAGE_BOUND",h.support.bindStage(handle,command));
            assertEquals("STAGE_BOUND",h.support.bindStage(handle,command));assertEquals("CANCELLED",h.support.cancel(handle));
            assertEquals(Map.of("prepare",1,"state",61,"readiness",2,"cancel",1),operations);
            assertTrue(credits.get()>=16_000_000_000L);assertEquals(original,e.originalDeadline);assertEquals(deadline,e.deadline);assertEquals(90_000_000_000L,e.setupDeadline);
        }
    }
    @Test void pacedPreparationStopsAtOriginalSetupOrEarlierExperimentCutoff()throws Exception{
        for(boolean shortExperiment:new boolean[]{false,true}){
            var nanos=new AtomicLong();var sleeps=new ArrayList<Long>();
            try(var h=new Harness(nanos,delay->{sleeps.add(delay);nanos.addAndGet(delay);})){
                String handle=h.ready();var e=h.support.entry(handle);e.state="FLOW_BOUND";
                if(shortExperiment)e.deadline=2_500_000_000L;
                long original=e.originalDeadline,deadline=e.deadline,cutoff=shortExperiment?deadline:90_000_000_000L;
                var polls=new ArrayList<Long>();var prepares=new AtomicInteger();
                h.peer.beforeSend=request->{
                    if(request.uri().getPath().endsWith("/prepare")){prepares.incrementAndGet();h.peer.status=202;h.peer.body="{\"state\":\"ACCEPTED\"}";}
                    else{assertTrue(request.uri().getPath().endsWith("/state"));polls.add(nanos.get());h.peer.status=200;h.peer.body="{\"state\":\"PREPARING\"}";}
                };
                h.support.prepare(handle);awaitPreparation(e);
                assertEquals(shortExperiment?"EXPIRED":"FAILED",e.state);assertEquals(1,prepares.get());assertEquals(cutoff,nanos.get());
                assertTrue(polls.stream().allMatch(time->time<cutoff));assertEquals(shortExperiment?3:90,polls.size());
                assertEquals(shortExperiment?500_000_000L:1_000_000_000L,sleeps.getLast().longValue());
                assertEquals(original,e.originalDeadline);assertEquals(deadline,e.deadline);assertEquals(cutoff,e.setupDeadline);assertNull(e.stageHandle);
            }
        }
    }
    @Test void authRateRejectionFencesWithoutRetryingPrepareOrState()throws Exception{
        var nanos=new AtomicLong();var sleeps=new AtomicInteger();try(var h=new Harness(nanos,delay->sleeps.incrementAndGet())){
            String handle=h.ready();var e=h.support.entry(handle);e.state="FLOW_BOUND";
            h.peer.beforeSend=request->{if(request.uri().getPath().endsWith("/prepare")){h.peer.status=202;h.peer.body="{\"state\":\"ACCEPTED\"}";}else{assertTrue(request.uri().getPath().endsWith("/state"));h.peer.status=429;h.peer.body="";}};
            h.support.prepare(handle);awaitPreparation(e);
            assertEquals("FAILED",h.support.state(handle));assertEquals(2,h.peer.calls);assertEquals(0,sleeps.get());
            assertThrows(CodeIntentFixtureSupport.Rejected.class,()->h.support.prepare(handle));assertEquals(2,h.peer.calls);
        }
    }
    @Test void cancellationInterruptsPacedPreparationWithoutAnotherStatePoll()throws Exception{
        var entered=new CountDownLatch(1);var left=new CountDownLatch(1);var nanos=new AtomicLong();
        try(var h=new Harness(nanos,delay->{entered.countDown();try{new CountDownLatch(1).await(5,TimeUnit.SECONDS);}finally{left.countDown();}})){
            String handle=h.ready();var e=h.support.entry(handle);e.state="FLOW_BOUND";var states=new AtomicInteger();var cancels=new AtomicInteger();
            h.peer.beforeSend=request->{switch(request.uri().getPath().substring(request.uri().getPath().lastIndexOf('/')+1)){
                case "prepare"->{h.peer.status=202;h.peer.body="{\"state\":\"ACCEPTED\"}";}
                case "state"->{states.incrementAndGet();h.peer.status=200;h.peer.body="{\"state\":\"PREPARING\"}";}
                case "cancel"->{cancels.incrementAndGet();h.peer.status=204;h.peer.body="";}
                default->throw new AssertionError("Unexpected fixture operation");
            }};
            h.support.prepare(handle);assertTrue(entered.await(5,TimeUnit.SECONDS));
            assertEquals("CANCELLED",h.support.cancel(handle));assertTrue(left.await(5,TimeUnit.SECONDS));
            assertEquals("CANCELLED",e.state);assertTrue(e.work.isCancelled());assertEquals(1,states.get());assertEquals(1,cancels.get());assertEquals(3,h.peer.calls);
            assertEquals(0,nanos.get());assertEquals(90_000_000_000L,e.setupDeadline);assertNull(e.stageHandle);
        }
    }
    private static void awaitPreparation(CodeIntentFixtureSupport.Experiment e)throws Exception{try{e.work.get(5,TimeUnit.SECONDS);}catch(CancellationException cancelled){assertTrue(e.work.isCancelled());}}
    private static void await(CountDownLatch latch){try{assertTrue(latch.await(2,TimeUnit.SECONDS));}catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new AssertionError(interrupted);}}
    /** Fresh native-context model double; never runtime/password-proof acceptance evidence. */
    private static RequiredActionContext nativeContext(Harness h,String handle,AtomicInteger successes){
        var e=h.support.entry(handle);var flow=e.flow;
        e.flow=new CodeIntentFixtureSupport.Flow(flow.issuer(),flow.subject(),flow.client(),flow.root(),flow.tab(),flow.digest(),flow.event(),java.time.Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS).toString());
        var captured=e.flow;
        var realm=model(RealmModel.class,Map.of("getName","armoney","getId","realm","getAccessCodeLifespanUserAction",300));
        var user=model(UserModel.class,Map.of("getId","subject","isEnabled",true));
        var client=model(ClientModel.class,Map.of("getId","internal","getClientId","armoney-ios","isPublicClient",true));
        var root=model(RootAuthenticationSessionModel.class,Map.of("getId","root","getTimestamp",org.keycloak.common.util.Time.currentTime()));
        var auth=(AuthenticationSessionModel)Proxy.newProxyInstance(AuthenticationSessionModel.class.getClassLoader(),new Class<?>[]{AuthenticationSessionModel.class},(p,m,a)->switch(m.getName()){
            case "getAuthenticatedUser"->user;case "getAction"->"REQUIRED_ACTIONS";case "getClient"->client;case "getParentSession"->root;case "getTabId"->"tab";
            case "getAuthNote"->PasswordProofFactory.NOTE.equals(a[0])?captured.event():PasswordProofFactory.TIME_NOTE.equals(a[0])?captured.password():null;
            default->throw new AssertionError(m.getName());});
        var hostname=model(org.keycloak.urls.HostnameProvider.class,Map.of("getBaseUri",java.net.URI.create("https://identity.fixture/sso/")));
        var owner=model(KeycloakSession.class,Map.of("getProvider",hostname));
        var uri=new KeycloakUriInfo(owner,org.keycloak.urls.UrlType.FRONTEND,null);
        return(RequiredActionContext)Proxy.newProxyInstance(RequiredActionContext.class.getClassLoader(),new Class<?>[]{RequiredActionContext.class},(p,m,a)->switch(m.getName()){
            case "getAuthenticationSession"->auth;case "getRealm"->realm;case "getSession"->null;case "getUriInfo"->uri;
            case "success"->{successes.incrementAndGet();yield null;}default->throw new AssertionError(m.getName());});
    }
    @SuppressWarnings("unchecked")private static <T>T model(Class<T> type,Map<String,Object> values){return(T)Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},(p,m,a)->{if(!values.containsKey(m.getName()))throw new AssertionError(m.getName());return values.get(m.getName());});}
}
