package com.arman.bank.sso;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.ws.rs.core.Response;
import org.keycloak.authentication.RequiredActionContext;
import org.keycloak.models.*;
import org.keycloak.models.utils.SessionExpiration;
import org.keycloak.protocol.oidc.utils.*;
import org.keycloak.services.Urls;
import org.keycloak.sessions.AuthenticationSessionModel;
import org.keycloak.services.managers.ClientSessionCode;
import java.security.SecureRandom;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.LongSupplier;

/** Disposable singleton, explicitly installed by fixture bootstrap. All cross-request data is immutable. */
public final class CodeIntentFixtureSupport implements AutoCloseable {
    public record Settings(String controllerKey,String peerKey,String providerKey,String bindingKey,String issuer,String subject,String clientId){
        public Settings{for(String key:new String[]{controllerKey,peerKey,providerKey,bindingKey})credential(key);if(new HashSet<>(Arrays.asList(controllerKey,peerKey,providerKey,bindingKey)).size()!=4)throw new Rejected(503);new CodeIntentPair(issuer,clientId);if(!CodeIntentPair.safe(subject,255)||!java.net.URI.create(issuer).getPath().equals("/sso/realms/armoney")||!Set.of("armoney-ios","armoney-android","armoney-web").contains(clientId))throw new Rejected(503);}
        @Override public String toString(){return "FixtureSettings[REDACTED]";}
    }
    record Flow(String issuer,String subject,String client,String root,String tab,String digest,String event,String password){
        Map<String,Object> fields(){return new LinkedHashMap<>(Map.of("issuer",issuer,"subject",subject,"client_id",client,"provider_root_session_id",root,"provider_tab_id",tab,"authentication_session_id",digest));}
        @Override public String toString(){return "FixtureFlow[REDACTED]";}
    }
    static final class Experiment {
        final String handle=handle();final String subject,client;final UUID prepare=UUID.randomUUID(),stage=distinct(prepare);
        final long originalDeadline;long deadline,setupDeadline;String state="ARMED",stageHandle;Flow flow;boolean permissionClaimed,txCommitted,confirmBusy,stageClaimed,prepareStarted,cancelSent,bindBusy;
        LoginProof.Binding nativeBinding;String codeId;Instant codeExpiry;LoginProof.Write write;LoginProof.Snapshot snapshot;
        final Map<String,Command> commands=new HashMap<>();Future<?> work;InvocationDeadline pending;
        Experiment(String subject,String client,long now){this.subject=subject;this.client=client;originalDeadline=now+TimeUnit.SECONDS.toNanos(120);deadline=originalDeadline;}
    }
    record Command(UUID id,String payload,String result){@Override public String toString(){return "FixtureCommand[REDACTED]";}}
    private static volatile CodeIntentFixtureSupport installed;
    private final Settings settings;private final CodeIntentResources resources;private final CodeIntentFixtureTransport transport;
    private final LongSupplier nanos;private final Clock clock;private final ThreadPoolExecutor worker;
    private final Map<String,Experiment> entries=new LinkedHashMap<>();private boolean closed;
    public static synchronized void install(CodeIntentFixtureSupport support){if(installed!=null)throw new Rejected(503);installed=Objects.requireNonNull(support);}
    static CodeIntentFixtureSupport current(){var value=installed;if(value==null)throw new Rejected(503);return value;}
    public CodeIntentFixtureSupport(Settings settings,CodeIntentResources resources,CodeIntentFixtureTransport transport){this(settings,resources,transport,System::nanoTime,Clock.systemUTC());}
    CodeIntentFixtureSupport(Settings settings,CodeIntentResources resources,CodeIntentFixtureTransport transport,LongSupplier nanos,Clock clock){
        this.settings=Objects.requireNonNull(settings);this.resources=Objects.requireNonNull(resources);this.transport=Objects.requireNonNull(transport);this.nanos=nanos;this.clock=clock;
        worker=new ThreadPoolExecutor(1,1,0,TimeUnit.SECONDS,new SynchronousQueue<>(),r->{var thread=new Thread(r,"armoney-fixture-preparation");thread.setDaemon(true);return thread;},new ThreadPoolExecutor.AbortPolicy());
    }
    static void credential(String value){if(value==null||value.length()<32||value.length()>256||value.chars().anyMatch(c->c<33||c>126))throw new Rejected(503);}
    boolean authenticate(String supplied,boolean peer){String key=peer?settings.peerKey():settings.controllerKey();return supplied!=null&&supplied.length()<=256&&java.security.MessageDigest.isEqual(key.getBytes(java.nio.charset.StandardCharsets.UTF_8),supplied.getBytes(java.nio.charset.StandardCharsets.UTF_8));}
    synchronized String arm(String subject,String client){cleanup();if(closed||entries.size()>=16||entries.values().stream().anyMatch(e->!terminal(e.state)))throw new Rejected(503);if(!settings.subject().equals(subject)||!settings.clientId().equals(client))throw new Rejected(409);var e=new Experiment(subject,client,nanos.getAsLong());entries.put(e.handle,e);return e.handle;}
    synchronized Experiment entry(String handle){cleanup();var e=entries.get(handle);if(e==null)throw new Rejected(404);if(nanos.getAsLong()>=e.deadline&&!terminal(e.state)){fence(e,"EXPIRED");}if(e.state.equals("EXPIRED")||e.state.equals("CANCELLED"))throw new Rejected(410);return e;}
    synchronized String state(String handle){return entry(handle).state;}
    private static boolean terminal(String state){return Set.of("FAILED","CANCELLED","EXPIRED","CONFIRMED").contains(state);}
    private void cleanup(){long now=nanos.getAsLong();for(var e:entries.values())if(!terminal(e.state)&&now>=e.deadline)fence(e,"EXPIRED");entries.values().removeIf(e->terminal(e.state)&&now-e.originalDeadline>=TimeUnit.SECONDS.toNanos(30));}
    public synchronized void maintenance(){cleanup();}
    private void fence(Experiment e,String state){e.state=state;if(e.pending!=null)e.pending.cancelOwnedExchange();if(e.work!=null)e.work.cancel(true);e.snapshot=null;e.write=null;e.flow=null;e.nativeBinding=null;e.codeId=null;e.codeExpiry=null;e.stageHandle=null;}
    String cancel(String handle){Experiment e;boolean notify;synchronized(this){e=entries.get(handle);if(e==null)throw new Rejected(404);if(e.state.equals("CONFIRMED"))return e.state;notify=e.prepareStarted&&!e.cancelSent;e.cancelSent=true;fence(e,"CANCELLED");}
        if(notify)try(var deadline=InvocationDeadline.start()){transport.call(CodeIntentFixtureTransport.Call.CANCEL,association(e),deadline);}
        return "CANCELLED";
    }
    Flow capture(RequiredActionContext context){var auth=context.getAuthenticationSession();var realm=context.getRealm();
        if(auth.getAuthenticatedUser()==null||!auth.getAuthenticatedUser().isEnabled()||!"REQUIRED_ACTIONS".equals(auth.getAction())||!auth.getClient().isPublicClient())throw new Rejected(503);
        String event=auth.getAuthNote(PasswordProofFactory.NOTE),password=auth.getAuthNote(PasswordProofFactory.TIME_NOTE);
        LoginProof.uuid(event);Instant time=LoginProof.time(password);if(time.isAfter(clock.instant())||time.isBefore(clock.instant().minusSeconds(1800)))throw new Rejected(503);
        if(!new ClientSessionCode<AuthenticationSessionModel>(context.getSession(),realm,auth).isActionActive(ClientSessionCode.ActionType.USER))throw new Rejected(410);
        String issuer=Urls.realmIssuer(context.getUriInfo().getBaseUri(),realm.getName());if(!settings.issuer().equals(issuer)||!settings.subject().equals(auth.getAuthenticatedUser().getId())||!settings.clientId().equals(auth.getClient().getClientId()))throw new Rejected(409);
        return new Flow(issuer,auth.getAuthenticatedUser().getId(),auth.getClient().getClientId(),auth.getParentSession().getId(),auth.getTabId(),SessionBinding.derive(settings.bindingKey(),realm.getId(),auth.getParentSession().getId(),auth.getTabId(),auth.getClient().getId()),event,password);
    }
    synchronized String bindFlow(RequiredActionContext context){Flow flow=capture(context);Experiment e=entries.values().stream().filter(x->!terminal(x.state)&&x.subject.equals(flow.subject())&&x.client.equals(flow.client())).findFirst().orElseThrow(()->new Rejected(404));entry(e.handle);
        if(e.flow!=null&&!e.flow.equals(flow))throw new Rejected(409);
        if(e.flow==null){long now=clock.instant().getEpochSecond();int timestamp=context.getAuthenticationSession().getParentSession().getTimestamp();long end=EnrollmentObservation.deadline(timestamp,now,context.getRealm().getAccessCodeLifespanUserAction(),SessionExpiration.getAuthSessionExpiration(context.getRealm(),timestamp));
            e.deadline=Math.min(e.deadline,nanos.getAsLong()+TimeUnit.SECONDS.toNanos(end-now));e.flow=flow;e.state="FLOW_BOUND";}
        return e.handle;
    }
    void prepare(String handle){Experiment e;synchronized(this){e=entry(handle);if(!e.state.equals("FLOW_BOUND"))throw new Rejected(e.state.equals("PREPARING")?503:409);e.state="PREPARING";e.setupDeadline=Math.min(e.deadline,nanos.getAsLong()+TimeUnit.SECONDS.toNanos(90));
        try{e.work=worker.submit(()->prepareWork(e.handle));}catch(RejectedExecutionException busy){fence(e,"FAILED");throw new Rejected(503);}}
    }
    private void prepareWork(String handle){try{Experiment e;long setup;synchronized(this){e=entry(handle);setup=e.setupDeadline;}
        Map<String,Object> payload;synchronized(this){entry(handle);e.prepareStarted=true;payload=e.flow.fields();}payload.put("experiment_handle",handle);payload.put("command_id",e.prepare.toString());
        JsonNode accepted=remote(e,CodeIntentFixtureTransport.Call.PREPARE,payload);LoginProof.fields(accepted,Set.of("state"));if(!"ACCEPTED".equals(LoginProof.text(accepted,"state")))throw new Rejected(503);
        while(true){synchronized(this){entry(handle);if(!e.state.equals("PREPARING")||nanos.getAsLong()>=setup)throw new Rejected(410);}
            JsonNode result=remote(e,CodeIntentFixtureTransport.Call.STATE,association(e));LoginProof.fields(result,Set.of("state"));String state=LoginProof.text(result,"state");
            if(state.equals("READY")){publishReady(e);return;}
            if(!state.equals("PREPARING"))throw new Rejected(503);Thread.sleep(100);
        }
    }catch(Exception failure){synchronized(this){var e=entries.get(handle);if(e!=null&&!terminal(e.state))fence(e,"FAILED");}if(failure instanceof InterruptedException)Thread.currentThread().interrupt();}}
    synchronized void publishReady(Experiment e){requireSetup(e);if(!e.state.equals("PREPARING"))throw new Rejected(503);e.state="READY";}
    private Map<String,Object> association(Experiment e){return Map.of("experiment_handle",e.handle,"command_id",e.prepare.toString());}
    private void requireSetup(Experiment e){entry(e.handle);if(e.setupDeadline<=0||nanos.getAsLong()>=e.setupDeadline)throw new Rejected(410);}
    private synchronized InvocationDeadline deadline(Experiment e,boolean setup){entry(e.handle);long end=e.deadline;if(setup){requireSetup(e);end=Math.min(end,e.setupDeadline);}long originalOrigin=nanos.getAsLong();long remaining=end-originalOrigin;if(remaining<=0)throw new Rejected(410);return new InvocationDeadline(nanos,originalOrigin,Duration.ofNanos(remaining));}
    private JsonNode remote(Experiment e,CodeIntentFixtureTransport.Call operation,Map<String,Object> payload){try(var deadline=deadline(e,true)){synchronized(this){requireSetup(e);e.pending=deadline;}try{JsonNode response=transport.call(operation,payload,deadline);synchronized(this){requireSetup(e);}return response;}finally{synchronized(this){if(e.pending==deadline)e.pending=null;}}}}
    String bindStage(String handle,String stageHandle,UUID command){Experiment e;synchronized(this){e=entry(handle);var previous=e.commands.get("bind");if(previous!=null){if(!previous.id().equals(command)||!previous.payload().equals(stageHandle))throw new Rejected(409);if(!e.state.equals("STAGE_BOUND")||!stageHandle.equals(e.stageHandle)||e.bindBusy)throw new Rejected(503);}else{reserve(e);if(!e.state.equals("READY")||command.equals(e.stage))throw new Rejected(503);e.commands.put("bind",new Command(command,stageHandle,"STAGE_BOUND"));e.state="PREPARING";}requireSetup(e);e.bindBusy=true;}
        try{JsonNode response=remote(e,CodeIntentFixtureTransport.Call.READINESS,association(e));LoginProof.fields(response,Set.of("stage_handle"));if(!stageHandle.equals(LoginProof.text(response,"stage_handle")))throw new Rejected(409);
            synchronized(this){requireSetup(e);if(!Set.of("PREPARING","STAGE_BOUND").contains(e.state))throw new Rejected(503);e.stageHandle=stageHandle;e.state="STAGE_BOUND";e.bindBusy=false;return e.state;}
        }catch(RuntimeException failure){synchronized(this){e.bindBusy=false;if(!terminal(e.state))fence(e,"FAILED");}throw new Rejected(503);}}
    void continueNative(RequiredActionContext context,String handle){Flow actual=capture(context);Experiment e;synchronized(this){e=entry(handle);if(!actual.equals(e.flow)||!e.state.equals("STAGE_BOUND")||e.permissionClaimed||e.bindBusy)throw new Rejected(503);e.permissionClaimed=true;}
        try{JsonNode response=remote(e,CodeIntentFixtureTransport.Call.READINESS,association(e));LoginProof.fields(response,Set.of("stage_handle"));if(!Objects.equals(e.stageHandle,LoginProof.text(response,"stage_handle")))throw new Rejected(503);
            synchronized(this){requireSetup(e);if(!e.state.equals("STAGE_BOUND"))throw new Rejected(503);context.success();}
        }catch(RuntimeException failure){synchronized(this){if(!terminal(e.state))fence(e,"FAILED");}throw new Rejected(503);}}
    private static void reserve(Experiment e){if(e.commands.size()>=16)throw new Rejected(503);}
    public static boolean handles(AuthenticationSessionModel auth){var support=installed;if(support==null)return false;synchronized(support){return support.entries.values().stream().anyMatch(e->e.flow!=null&&e.flow.root().equals(auth.getParentSession().getId())&&e.flow.tab().equals(auth.getTabId()));}}
    public static Response generated(KeycloakSession session,OIDCRedirectUriBuilder builder,AuthenticationSessionModel auth,UserSessionModel user,ClientSessionContext clientContext){return current().stageGenerated(session,builder,auth,user,clientContext);}
    private Response stageGenerated(KeycloakSession session,OIDCRedirectUriBuilder builder,AuthenticationSessionModel auth,UserSessionModel user,ClientSessionContext clientContext){Experiment e;
        synchronized(this){e=entries.values().stream().filter(x->x.flow!=null&&x.flow.root().equals(auth.getParentSession().getId())&&x.flow.tab().equals(auth.getTabId())).findFirst().orElseThrow(()->new Rejected(404));entry(e.handle);if(!e.permissionClaimed||!e.state.equals("STAGE_BOUND")||e.stageClaimed)throw new Rejected(503);e.stageClaimed=true;}
        Response response=builder.build();try{
            String id=ProofLoginProtocol.codeId(response,user.getId(),clientContext.getClientSession().getClient().getId());
            var stored=session.singleUseObjects().get(OAuth2CodeParser.CACHE_KEY_PREFIX+id);if(stored==null)throw new Rejected(503);var code=OAuth2Code.deserializeCode(stored);
            var binding=new LoginProof.Binding(e.flow.issuer(),user.getUser().getId(),auth.getClient().getClientId(),user.getId());
            if(!e.flow.subject().equals(binding.subject())||!e.flow.client().equals(binding.client())||!code.getId().equals(id)||!code.getUserSessionId().equals(user.getId())||!code.getClientUUID().equals(auth.getClient().getId())
                    ||!e.flow.event().equals(auth.getAuthNote(PasswordProofFactory.NOTE))||!e.flow.password().equals(auth.getAuthNote(PasswordProofFactory.TIME_NOTE)))throw new Rejected(503);
            var key=resources.keys().keys().getFirst();var issued=Instant.ofEpochSecond(clock.instant().getEpochSecond());var expiry=Instant.ofEpochSecond(code.getExpiration());
            var write=new LoginProof.Write(e.stage,resources.keys().digest(key,binding,id),key.id(),binding,e.flow.digest(),issued,expiry,"interactive",LoginProof.uuid(e.flow.event()),LoginProof.time(e.flow.password()));
            synchronized(this){entry(e.handle);e.deadline=Math.min(e.deadline,nanos.getAsLong()+Duration.between(clock.instant(),expiry).toNanos());e.write=write;e.nativeBinding=binding;e.codeId=id;e.codeExpiry=expiry;}
            session.getTransactionManager().enlistAfterCompletion(new TxSignal(e.handle));
            try(var deadline=deadline(e,false)){synchronized(this){e.pending=deadline;}resources.client().stage(write,deadline);}
            synchronized(this){entry(e.handle);if(!e.state.equals("STAGE_BOUND"))throw new Rejected(503);e.pending=null;e.state="STAGED";}return response;
        }catch(RuntimeException failure){response.close();synchronized(this){if(!terminal(e.state))fence(e,"FAILED");}throw new Rejected(503);}}
    private static final class TxSignal extends AbstractKeycloakTransaction {private final String id;TxSignal(String id){this.id=id;}protected void commitImpl(){signal(id,true);}protected void rollbackImpl(){signal(id,false);}}
    private static void signal(String id,boolean committed){var support=installed;if(support==null)return;synchronized(support){var e=support.entries.get(id);if(e==null||terminal(e.state))return;if(committed&&e.state.equals("STAGED"))e.txCommitted=true;else support.fence(e,"FAILED");}}
    String confirm(String handle,UUID command){Experiment e;synchronized(this){e=entry(handle);var old=e.commands.get("confirm");if(old!=null){if(!old.id().equals(command))throw new Rejected(409);if(e.state.equals("CONFIRMED"))return e.state;throw new Rejected(503);}reserve(e);if(!e.txCommitted||!e.state.equals("STAGED")||e.confirmBusy||command.equals(e.stage))throw new Rejected(503);e.commands.put("confirm",new Command(command,"","CONFIRMED"));e.confirmBusy=true;}
        try(var deadline=deadline(e,false)){synchronized(this){e.pending=deadline;}
            var membership=transport.call(CodeIntentFixtureTransport.Call.MEMBERSHIP,e.nativeBinding.fields(),deadline);validateMembership(membership,e.nativeBinding);
            synchronized(this){entry(handle);if(!e.state.equals("STAGED"))throw new Rejected(503);}deadline.requireOpen();
            var snapshot=resources.client().lookupAndConfirm(resources.keys(),e.nativeBinding,e.codeId,e.codeExpiry,deadline);
            synchronized(this){entry(handle);deadline.requireOpen();if(!e.state.equals("STAGED"))throw new Rejected(503);e.snapshot=snapshot;e.state="CONFIRMED";e.pending=null;return e.state;}
        }catch(RuntimeException failure){synchronized(this){if(!terminal(e.state))fence(e,"FAILED");}throw new Rejected(503);}}
    static void validateMembership(JsonNode node,LoginProof.Binding binding){LoginProof.fields(node,Set.of("issuer","subject","client_id","user_session_id","user_session_present","client_session_present"));
        for(var field:binding.fields().entrySet())if(!field.getValue().equals(LoginProof.text(node,field.getKey())))throw new Rejected(503);
        if(!node.path("user_session_present").isBoolean()||!node.path("user_session_present").booleanValue()||!node.path("client_session_present").isBoolean()||!node.path("client_session_present").booleanValue())throw new Rejected(503);}
    static String handle(){byte[] bytes=new byte[32];new SecureRandom().nextBytes(bytes);return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);}
    private static UUID distinct(UUID original){UUID value;do{value=UUID.randomUUID();}while(value.equals(original));return value;}
    @Override public void close(){synchronized(this){if(closed)return;closed=true;for(var e:entries.values())if(!terminal(e.state))fence(e,"CANCELLED");}worker.shutdownNow();try{transport.close();}finally{try{resources.close();}finally{synchronized(CodeIntentFixtureSupport.class){if(installed==this)installed=null;}}}}
    static final class Rejected extends IllegalStateException {final int status;Rejected(int status){super("Fixture request unavailable");this.status=status;}}
}
