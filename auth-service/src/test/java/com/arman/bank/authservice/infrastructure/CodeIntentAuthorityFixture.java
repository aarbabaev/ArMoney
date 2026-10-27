package com.arman.bank.authservice.infrastructure;

import com.arman.bank.authservice.application.*;
import com.arman.bank.authservice.domain.IdentityVerification;
import com.arman.bank.authservice.domain.IdentityVerification.*;
import com.arman.bank.authservice.domain.IdentityVerification.Flow;
import com.arman.bank.authservice.domain.IdentityVerification.Policy;
import com.arman.bank.authservice.domain.LoginProof;
import com.arman.bank.authservice.domain.Verification;
import com.arman.bank.authservice.domain.Verification.Purpose;
import com.arman.bank.authservice.domain.VerificationProjection;
import com.arman.bank.runtime.Database;
import com.fasterxml.jackson.databind.*;
import io.javalin.Javalin;
import io.javalin.http.Context;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.net.ssl.*;
import jakarta.mail.*;
import jakarta.mail.internet.MimeMessage;

/** TEST ARTIFACT ONLY. Private acceptance controls, never registered by Auth Main.
 * Real OTP/use cases/User ACK; LOCAL SMTP TEST, not Mailtrap/Keycloak/activation evidence.
 * No Write ingress, receipts/authority seed, manufactured Binding or positive session supplier. */
public final class CodeIntentAuthorityFixture implements AutoCloseable {
    static final String PREFIX="/fixture/code-intent-authority/";
    static final String HEADER="X-ArMoney-Code-Intent-Fixture-Provider-Key";
    static final Set<String> PREPARE=Set.of("experiment_handle","command_id","issuer","subject","client_id",
        "provider_root_session_id","provider_tab_id","authentication_session_id");
    static final Set<String> CONTROL=Set.of("experiment_handle","command_id");
    static final long EXPERIMENT=TimeUnit.SECONDS.toNanos(120), SETUP=TimeUnit.SECONDS.toNanos(90), RETAIN=TimeUnit.SECONDS.toNanos(30);
    enum State { PREPARING, READY, FAILED, CANCELLED, EXPIRED }
    record Bound(Instant databaseNow,Instant localDeadline) {}
    interface Work extends AutoCloseable {
        void prepare(Flow flow,Check cancellation) throws Exception;
        Bound readiness(Flow flow,Check cancellation) throws Exception;
        @Override void close();
    }
    @FunctionalInterface interface Factory { Work open(); }
    @FunctionalInterface interface Check { void check(); }
    static final class Denied extends RuntimeException {
        final int status;
        Denied(int status){super("fixture_request_rejected");this.status=status;}
    }
    record Operation(String name,UUID command) {}
    static final class Experiment {
        final String handle;
        final Flow flow;
        final long started;
        final Set<Operation> commands=new HashSet<>();
        State state=State.PREPARING;
        Future<?> preparation;
        Work work;
        boolean busy;
        String stageHandle;
        long stageDeadline;
        Experiment(String handle,Flow flow,long started){this.handle=handle;this.flow=flow;this.started=started;}
        @Override public String toString(){return "FixtureExperiment[redacted]";}
    }
    private final java.util.function.LongSupplier nano;
    private final Semaphore controls=new Semaphore(16);
    private double credits=20;
    private long refilled;
    private final byte[] credential;
    private final Policy policy;
    private final Factory factory;
    private final Map<String,Experiment> experiments=new LinkedHashMap<>();
    private final ThreadPoolExecutor preparation=new ThreadPoolExecutor(1,1,0,TimeUnit.SECONDS,
        new SynchronousQueue<>(),Thread.ofPlatform().name("code-authority-preparation").daemon(true).factory(),new ThreadPoolExecutor.AbortPolicy());
    private final ScheduledExecutorService maintenance=Executors.newSingleThreadScheduledExecutor(
        Thread.ofPlatform().name("code-authority-retention").daemon(true).factory());
    private final SecureRandom random=new SecureRandom();
    private final AtomicBoolean closed=new AtomicBoolean();
    private Javalin listener;

    CodeIntentAuthorityFixture(String credential,Policy policy,Factory factory){this(credential,policy,factory,System::nanoTime);}
    /** Fixture state-clock seam only; never supplied to DB/provider authority or production wiring. */
    CodeIntentAuthorityFixture(String credential,Policy policy,Factory factory,java.util.function.LongSupplier nano){
        this.nano=Objects.requireNonNull(nano);this.refilled=nano.getAsLong();
        this.credential=LoginProofRoutes.credential(credential);
        this.policy=Objects.requireNonNull(policy);this.factory=Objects.requireNonNull(factory);
        maintenance.scheduleWithFixedDelay(this::sweep,1,1,TimeUnit.SECONDS);
    }
    synchronized void start(String host,int port){
        if(closed.get()||listener!=null)throw new Denied(503);
        listener=Javalin.create(config->{
            config.routes.before(ctx->{
                ctx.header("Cache-Control","no-store");
                if(!"POST".equals(ctx.req().getMethod())||!Set.of(PREFIX+"prepare",PREFIX+"state",PREFIX+"readiness",PREFIX+"cancel").contains(ctx.path()))
                    throw new io.javalin.http.HttpResponseException(404,"");
            });
            config.routes.exception(io.javalin.http.HttpResponseException.class,(error,ctx)->ctx.header("Cache-Control","no-store").status(error.getStatus()==404?404:400).result(""));
            config.routes.exception(Exception.class,(error,ctx)->ctx.header("Cache-Control","no-store").status(503).result(""));
            for(String operation:List.of("prepare","state","readiness","cancel"))
                config.routes.post(PREFIX+operation,ctx->handle(ctx,operation));
        });
        try{listener.start(host,port);}catch(RuntimeException|Error failed){try{close();}catch(RuntimeException|Error cleanup){failed.addSuppressed(cleanup);}throw failed;}
    }
    synchronized int port(){if(listener==null)throw new Denied(503);return listener.port();}
    private void handle(Context ctx,String operation){
        ctx.header("Cache-Control","no-store");
        boolean permit=false;
        try{
            CodeIntentHttp.authenticate(ctx,HEADER,credential);
            JsonNode body;
            try(var deadline=LoginProofBodyDeadline.begin(ctx.req())){
                if(!controls.tryAcquire())throw new Denied(429);permit=true;admitControl();
                body=body(ctx,deadline,operation.equals("prepare")?PREPARE:CONTROL);
            }
            String handle=handle(CodeIntentHttp.text(body,"experiment_handle"));
            UUID command=uuid(CodeIntentHttp.text(body,"command_id"));
            Map<String,String> response;
            if(operation.equals("prepare")){
                var flow=new Flow(CodeIntentHttp.text(body,"issuer"),CodeIntentHttp.text(body,"subject"),CodeIntentHttp.text(body,"client_id"),
                    CodeIntentHttp.text(body,"provider_root_session_id"),CodeIntentHttp.text(body,"provider_tab_id"),CodeIntentHttp.text(body,"authentication_session_id"));
                IdentityVerification.validate(flow,policy);
                response=prepare(handle,command,flow);ctx.status(202);
            }else{
                response=switch(operation){case "state"->state(handle,command);case "readiness"->readiness(handle,command);case "cancel"->{cancel(handle,command);yield null;}default->throw new Denied(404);};
                ctx.status(operation.equals("cancel")?204:200);
            }
            if(response!=null)CodeIntentHttp.reply(ctx,operation.equals("prepare")?202:200,response);
        }catch(Denied failure){ctx.status(failure.status).result("");}
        catch(LoginProof.Failure failure){ctx.status(CodeIntentHttp.status(failure.error())).result("");}
        catch(Verification.Failure failure){ctx.status(switch(failure.error()){case BAD_INPUT->400;case BINDING,CONFLICT->409;case RATE_LIMITED->429;case UNAVAILABLE->503;}).result("");}
        catch(Exception failure){ctx.status(503).result("");}
        finally{if(permit)controls.release();}
    }
    private synchronized void admitControl(){
        long now=nano.getAsLong();long elapsed=now-refilled;if(elapsed<0)throw new Denied(503);
        credits=Math.min(20,credits+elapsed/1_000_000_000.0*2);refilled=now;
        if(credits<1)throw new Denied(429);credits-=1;
    }
    private static JsonNode body(Context ctx,LoginProofBodyDeadline deadline,Set<String> fields) throws Exception {
        if(!ctx.queryParamMap().isEmpty())throw new Denied(400);
        var types=Collections.list(ctx.req().getHeaders("Content-Type"));
        if(types.size()!=1||!types.getFirst().matches("(?i)application/json(?:\\s*;\\s*charset=utf-8)?"))throw new Denied(400);
        byte[] bytes=deadline.read(ctx.req().getInputStream(),12289);
        try{
            if(bytes.length>12288)throw new Denied(400);
            JsonNode result;
            try{result=JSON.readTree(strictUtf8(bytes));}catch(IOException invalid){throw new Denied(400);}
            CodeIntentHttp.exact(result,fields);
            for(var value:result){if(!value.isTextual()||value.textValue().isEmpty()||value.textValue().codePoints().anyMatch(cp->Character.isWhitespace(cp)||Character.isSpaceChar(cp)||Character.isISOControl(cp)||(cp>=0xD800&&cp<=0xDFFF)))throw new Denied(400);}
            return result;
        }finally{Arrays.fill(bytes,(byte)0);}
    }
    static String handle(String value){
        if(value==null||!value.matches("[A-Za-z0-9_-]{43}"))throw new Denied(400);
        try{byte[] bytes=Base64.getUrlDecoder().decode(value);if(bytes.length!=32||!Base64.getUrlEncoder().withoutPadding().encodeToString(bytes).equals(value))throw new Denied(400);}
        catch(IllegalArgumentException bad){throw new Denied(400);}
        return value;
    }
    static UUID uuid(String value){
        if(value==null||!value.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))throw new Denied(400);
        return UUID.fromString(value);
    }
    private static boolean terminal(State state){return state==State.CANCELLED||state==State.EXPIRED||state==State.FAILED;}
    private synchronized Experiment find(String handle){
        if(closed.get())throw new Denied(503);
        expireLocked();var experiment=experiments.get(handle);if(experiment==null)throw new Denied(404);return experiment;
    }
    private void reserve(Experiment experiment,String operation,UUID command){
        var key=new Operation(operation,command);
        if(experiment.commands.contains(key))return;
        // Keep one slot for cancellation; no extra copy of Flow/raw HMAC material is retained.
        if(experiment.commands.size()>=(operation.equals("cancel")?16:15))throw new Denied(429);
        experiment.commands.add(key);
    }
    synchronized Map<String,String> prepare(String handle,UUID command,Flow flow){
        handle(handle);Objects.requireNonNull(command);IdentityVerification.validate(flow,policy);
        if(closed.get())throw new Denied(503);expireLocked();
        var existing=experiments.get(handle);
        if(existing!=null){
            if(!existing.flow.equals(flow)||!existing.commands.contains(new Operation("prepare",command)))throw new Denied(409);
            if(terminal(existing.state))throw new Denied(existing.state==State.FAILED?503:410);
            return Map.of("state","ACCEPTED");
        }
        if(experiments.size()>=16||experiments.values().stream().anyMatch(e->!terminal(e.state)))throw new Denied(429);
        var experiment=new Experiment(handle,flow,nano.getAsLong());reserve(experiment,"prepare",command);experiments.put(handle,experiment);
        try{experiment.preparation=preparation.submit(()->run(experiment));}
        catch(RejectedExecutionException busy){experiment.state=State.FAILED;throw new Denied(503);}
        return Map.of("state","ACCEPTED");
    }
    private void run(Experiment experiment){
        Work work=null;boolean attached=false;
        try{
            check(experiment,true);work=factory.open();
            synchronized(this){check(experiment,true);experiment.work=work;attached=true;}
            work.prepare(experiment.flow,()->check(experiment,true));
            synchronized(this){check(experiment,true);experiment.state=State.READY;}
        }catch(Throwable failure){
            if(failure instanceof InterruptedException)Thread.currentThread().interrupt();
            synchronized(this){if(!terminal(experiment.state))experiment.state=State.FAILED;}
        }finally{
            if(work!=null){
                boolean release=!attached;
                synchronized(this){if(attached&&experiment.work==work&&terminal(experiment.state)){experiment.work=null;release=true;}}
                if(release)work.close();
            }
        }
    }
    private synchronized void check(Experiment experiment,boolean setup){
        if(closed.get()||Thread.currentThread().isInterrupted())throw new Denied(503);
        long elapsed=nano.getAsLong()-experiment.started;
        if(elapsed>=EXPERIMENT){experiment.state=State.EXPIRED;throw new Denied(410);}
        if(setup&&elapsed>=SETUP){experiment.state=State.FAILED;throw new Denied(503);}
        if(terminal(experiment.state))throw new Denied(experiment.state==State.FAILED?503:410);
    }
    synchronized Map<String,String> state(String handle,UUID command){
        if(closed.get())throw new Denied(503);
        var experiment=experiments.get(handle);if(experiment==null)throw new Denied(404);
        // Pure observation: no command reservation, remote call, expiration mutation or progression.
        return Map.of("state",nano.getAsLong()-experiment.started>=EXPERIMENT?State.EXPIRED.name():experiment.state.name());
    }
    Map<String,String> readiness(String handle,UUID command){
        Experiment experiment;
        synchronized(this){
            experiment=find(handle);check(experiment,false);
            if(experiment.stageHandle!=null&&nano.getAsLong()>=experiment.stageDeadline){experiment.state=State.EXPIRED;throw new Denied(410);}
            if(experiment.state!=State.READY||experiment.busy)throw new Denied(503);
            reserve(experiment,"readiness",command);experiment.busy=true;
        }
        try{
            long before=nano.getAsLong();
            Bound result=experiment.work.readiness(experiment.flow,()->check(experiment,false));
            synchronized(this){
                check(experiment,false);
                if(result==null||!result.databaseNow().isBefore(result.localDeadline()))throw new Denied(503);
                long available=Math.min(EXPERIMENT-(before-experiment.started),Math.min(TimeUnit.SECONDS.toNanos(30),Duration.between(result.databaseNow(),result.localDeadline()).toNanos()));
                if(available<=0||nano.getAsLong()-before>=available)throw new Denied(503);
                if(experiment.stageHandle==null){byte[] bytes=new byte[32];random.nextBytes(bytes);experiment.stageHandle=Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);Arrays.fill(bytes,(byte)0);experiment.stageDeadline=before+available;}
                experiment.stageDeadline=Math.min(experiment.stageDeadline,before+available);
                if(nano.getAsLong()>=experiment.stageDeadline){experiment.state=State.EXPIRED;throw new Denied(410);}
                return Map.of("stage_handle",experiment.stageHandle);
            }
        }catch(Exception failure){
            synchronized(this){if(!terminal(experiment.state))experiment.state=State.FAILED;}
            if(failure instanceof Denied denied)throw denied;throw new Denied(503);
        }finally{synchronized(this){experiment.busy=false;}}
    }
    void cancel(String handle,UUID command){
        Work work;
        synchronized(this){
            var experiment=find(handle);
            if(experiment.state==State.CANCELLED&&!experiment.commands.contains(new Operation("cancel",command)))throw new Denied(409);
            reserve(experiment,"cancel",command);
            if(experiment.state!=State.EXPIRED)experiment.state=State.CANCELLED;
            if(experiment.preparation!=null)experiment.preparation.cancel(true);
            work=experiment.work;experiment.work=null;experiment.stageHandle=null;
        }
        if(work!=null)work.close();
    }
    private void expireLocked(){
        long now=nano.getAsLong();
        for(var experiment:experiments.values())if(now-experiment.started>=EXPERIMENT&&!terminal(experiment.state)){
            experiment.state=State.EXPIRED;if(experiment.preparation!=null)experiment.preparation.cancel(true);experiment.stageHandle=null;
        }
        experiments.values().removeIf(experiment->now-experiment.started>=EXPERIMENT+RETAIN&&terminal(experiment.state)&&!experiment.busy&&experiment.work==null);
    }
    private void sweep(){
        var detached=new ArrayList<Work>();
        synchronized(this){expireLocked();for(var experiment:experiments.values())if(terminal(experiment.state)&&experiment.work!=null){detached.add(experiment.work);experiment.work=null;}}
        for(var work:detached)try{work.close();}catch(RuntimeException ignored){/* Generic bounded fixture cleanup only. */}
    }
    @Override public void close(){
        if(!closed.compareAndSet(false,true))return;
        Javalin app;var detached=new ArrayList<Work>();
        synchronized(this){
            app=listener;
            for(var experiment:experiments.values()){
                experiment.state=State.CANCELLED;if(experiment.preparation!=null)experiment.preparation.cancel(true);
                if(experiment.work!=null){detached.add(experiment.work);experiment.work=null;}experiment.stageHandle=null;
            }
        }
        maintenance.shutdownNow();preparation.shutdownNow();
        Throwable failure=null;
        for(var work:detached)try{work.close();}catch(RuntimeException|Error cleanup){if(failure==null)failure=cleanup;else failure.addSuppressed(cleanup);}
        try{if(app!=null)app.stop();}catch(RuntimeException|Error cleanup){if(failure==null)failure=cleanup;else failure.addSuppressed(cleanup);}
        finally{Arrays.fill(credential,(byte)0);}
        try{if(!preparation.awaitTermination(5,TimeUnit.SECONDS)&&failure==null)failure=new Denied(503);}
        catch(InterruptedException interrupted){Thread.currentThread().interrupt();if(failure==null)failure=new Denied(503);else failure.addSuppressed(interrupted);}
        if(failure instanceof RuntimeException error)throw error;
        if(failure instanceof Error error)throw error;
    }
    /** Explicit test entry point; root-owned isolated packaging is the only launcher. */
    public static void main(String[] args) throws Exception {
        Map<String,String> env=Map.copyOf(System.getenv());
        if(!"true".equals(env.get("CI"))||!"true".equals(env.get("CODE_SESSION_ACCEPTANCE_ISOLATED")))throw new Denied(503);
        var config=new CodeConfirmationConfiguration(env);
        if(!config.enabled||!"false".equals(env.getOrDefault("AUTH_VERIFICATION_DELIVERY_ENABLED","false")))throw new Denied(503);
        String key=required(env,"AUTH_CODE_INTENT_FIXTURE_PROVIDER_KEY");LoginProofRoutes.credential(key);
        var policy=Policy.defaults(config.scopes().managedIssuers(),config.scopes().managedClients(),Set.of("sso-provider"));
        if(!"http://sso-service:8080".equals(required(env,"SSO_ENROLLMENT_STATUS_ORIGIN"))
            ||!"http://user-service:8080".equals(required(env,"USER_BASE_URL"))
            ||!"http://sms-sandbox:8080".equals(env.getOrDefault("VERIFICATION_SMS_BASE_URL","http://sms-sandbox:8080")))throw new Denied(503);
        var mailEnv=new HashMap<>(env);mailEnv.put("LOGIN_NOTIFICATIONS_ENABLED","true");var mail=new EmailConfiguration(mailEnv);
        String smsSender=required(env,"VERIFICATION_SMS_SEND_TOKEN"),smsReader=required(env,"SMS_SANDBOX_READ_TOKEN");
        if(!smsSender.matches("[A-Za-z0-9_-]{32,128}")||!smsReader.matches("[A-Za-z0-9_-]{32,128}")||smsSender.equals(smsReader))throw new Denied(503);
        Database database=new Database(required(env,"DB_URL"),required(env,"DB_USER"),required(env,"DB_PASSWORD"));
        CodeIntentAuthorityFixture fixture=null;LocalSmtp smtp=null;
        try{
            smtp=new LocalSmtp(mail.smtpUsername,mail.smtpPassword);var localSmtp=smtp;
            fixture=new CodeIntentAuthorityFixture(key,policy,()->new RealWork(database,env,policy,
                URI.create("http://sso-service:8080"),URI.create(env.getOrDefault("VERIFICATION_SMS_BASE_URL","http://sms-sandbox:8080")),localSmtp));
            fixture.start("0.0.0.0",8091);
            var owned=fixture;
            Runtime.getRuntime().addShutdownHook(new Thread(()->closeAll(null,owned::close,localSmtp::close,database::close),"code-authority-fixture-shutdown"));
        }catch(Exception|Error failure){if(fixture!=null)try{fixture.close();}catch(RuntimeException|Error cleanup){failure.addSuppressed(cleanup);}if(smtp!=null)try{smtp.close();}catch(RuntimeException|Error cleanup){failure.addSuppressed(cleanup);}try{database.close();}catch(RuntimeException|Error cleanup){failure.addSuppressed(cleanup);}throw failure;}
    }
    static String required(Map<String,String> env,String name){String value=env.get(name);if(value==null||value.isBlank())throw new Denied(503);return value;}
    static byte[] key(Map<String,String> env,String name){
        String value=required(env,name);byte[] decoded;
        try{decoded=Base64.getDecoder().decode(value);}catch(IllegalArgumentException bad){throw new Denied(503);}
        if(decoded.length!=32||!Base64.getEncoder().encodeToString(decoded).equals(value)){Arrays.fill(decoded,(byte)0);throw new Denied(503);}return decoded;
    }
    private static void closeAll(Throwable primary,Runnable... actions){
        Throwable failure=primary;
        for(var action:actions)try{action.run();}catch(RuntimeException|Error cleanup){if(failure==null)failure=cleanup;else if(failure!=cleanup)failure.addSuppressed(cleanup);}
        if(primary==null){if(failure instanceof RuntimeException error)throw error;if(failure instanceof Error error)throw error;}
    }
    /** Test-only in-module authority owner; supplied pool is borrowed and never closed here.
     * AuthMain alone owns projection dispatch. Main's delivery flag must remain false. */
    static final class RealWork implements Work {
        private final Database database;
        private final HttpVerificationObservations observer;
        private final PostgresIdentityVerificationStore authority;
        private final IdentityVerificationService service;
        private final VerificationDeliveryWorker delivery;
        private final LocalSmtp smtp;
        private final HttpClient reader=HttpCodeSessionObservations.privateClient();
        private final Set<CompletableFuture<?>> reads=ConcurrentHashMap.newKeySet();
        private final URI smsOrigin;
        private final String smsReadKey;
        private final AtomicBoolean closed=new AtomicBoolean();
        private UUID enrollment;
        private String email,phone;
        RealWork(Database database,Map<String,String> env,Policy policy,URI observationOrigin,URI smsOrigin,LocalSmtp smtp){
            this.database=Objects.requireNonNull(database);this.smsOrigin=smsOrigin;this.smtp=Objects.requireNonNull(smtp);
            HttpVerificationObservations foundObserver=null;VerificationDeliveryWorker foundDelivery=null;
            try{
                if(!"http://sms-sandbox:8080".equals(smsOrigin.toString())&&!("http".equals(smsOrigin.getScheme())&&"127.0.0.1".equals(smsOrigin.getHost())&&smsOrigin.getPort()>0))throw new Denied(503);
                smsReadKey=required(env,"SMS_SANDBOX_READ_TOKEN");String sender=required(env,"VERIFICATION_SMS_SEND_TOKEN");
                if(!smsReadKey.matches("[A-Za-z0-9_-]{32,128}")||!sender.matches("[A-Za-z0-9_-]{32,128}")||smsReadKey.equals(sender))throw new Denied(503);
                String keyId=required(env,"VERIFICATION_KEY_ID");
                var crypto=new VerificationCrypto(keyId,Map.of(keyId,key(env,"VERIFICATION_OTP_HMAC_KEY")),Map.of(keyId,key(env,"VERIFICATION_DELIVERY_ENCRYPTION_KEY")));
                byte[] retention=key(env,"AUTH_VERIFICATION_AUTHORITY_RETENTION_KEY");
                var lookup=new PostgresIdentityVerificationStore(database,crypto,retention,policy,null);
                String projectionWriter=required(env,"AUTH_VERIFICATION_PROJECTION_WRITER_ID");
                var projections=new PostgresVerificationProjectionStore(database,VerificationProjection.Policy.defaults(Set.of(projectionWriter)),key(env,"AUTH_VERIFICATION_PROJECTION_RETENTION_KEY"),
                    sql->(identity,now)->lookup.projectionAuthorityInTransaction(sql,identity,now));
                authority=new PostgresIdentityVerificationStore(database,crypto,retention,policy,projections,projectionWriter,true);
                Arrays.fill(retention,(byte)0);
                foundObserver=new HttpVerificationObservations(observationOrigin,required(env,"AUTH_TO_SSO_STATUS_KEY"),policy,HttpCodeSessionObservations.privateClient());observer=foundObserver;
                service=new IdentityVerificationService(authority,observer,policy);
                var mailEnv=new HashMap<>(env);mailEnv.put("LOGIN_NOTIFICATIONS_ENABLED","true");
                var mail=new EmailConfiguration(mailEnv);
                var senderMail=new MailtrapSmtp(mail,"localhost",smtp.port(),smtp.clientTls.getSocketFactory(),10_000);
                boolean loopback="127.0.0.1".equals(smsOrigin.getHost());
                var transport=new VerificationDeliveryTransport(senderMail::send,smsOrigin,sender,loopback);
                foundDelivery=new VerificationDeliveryWorker(new PostgresVerificationStore(database,crypto),crypto,transport,Clock.systemUTC());delivery=foundDelivery;
            }catch(Exception failure){
                if(foundDelivery!=null)foundDelivery.close();if(foundObserver!=null)foundObserver.close();reader.shutdownNow();throw new Denied(503);
            }
        }
        private void check(Check check){check.check();if(closed.get()||Thread.currentThread().isInterrupted())throw new Denied(503);}
        @Override public void prepare(Flow flow,Check cancellation) throws Exception {
            check(cancellation);
            // Contacts come solely from genuine authenticated pretoken observation, never the control DTO.
            Instant admitted=authority.admit("sso-provider",flow);
            var original=observer.observe(flow,admitted);
            IdentityVerification.evidence(flow,original,Instant.now());
            email=original.email();phone=original.phone();smtp.recipient(email);
            var enrolled=service.enroll("sso-provider",new Enrollment(UUID.randomUUID(),flow,phone,email));
            enrollment=enrolled.enrollmentId();if(enrollment==null)throw new Denied(503);
            if(!smsMessages(phone).isEmpty())throw new Denied(503); // Dedicated empty inbox, never accept an older message.
            delivery.start();
            consume(flow,Purpose.REGISTRATION_EMAIL,cancellation);
            consume(flow,Purpose.REGISTRATION_PHONE,cancellation);
            while(true){
                check(cancellation);
                String state=database.transaction(sql->{var row=sql.fetchOne("select state from verification_authorities where enrollment_id=?",enrollment);return row==null?null:row.get(0,String.class);});
                if("PROJECTED".equals(state))break;
                if(!"PROJECTION_PENDING".equals(state))throw new Denied(503);
                Thread.sleep(25);
            }
            // Main's real User HTTP worker must have attached the persisted matching ACK first.
            service.status("sso-provider",enrollment,flow);
            local(flow,cancellation);
        }
        private void consume(Flow flow,Purpose purpose,Check cancellation) throws Exception {
            check(cancellation);
            var challenge=service.issue("sso-provider",new Issue(UUID.randomUUID(),enrollment,flow,purpose,1));
            if(challenge.challengeId()==null)throw new Denied(503);
            while(true){
                check(cancellation);
                String state=database.transaction(sql->{var row=sql.fetchOne("select state from verification_challenges where id=?",challenge.challengeId());return row==null?null:row.get(0,String.class);});
                if("OPEN".equals(state))break;
                if(!"PENDING_DELIVERY".equals(state))throw new Denied(503);
                Thread.sleep(25);
            }
            String code;
            if(purpose==Purpose.REGISTRATION_EMAIL)code=smtp.code(email);
            else{
                var messages=smsMessages(phone);
                if(messages.size()!=1)throw new Denied(503);
                code=otp(messages.getFirst().path("text").textValue());
            }
            check(cancellation);
            var consumed=service.consume("sso-provider",new Consume(UUID.randomUUID(),enrollment,challenge.challengeId(),flow,purpose,1,code));
            if(consumed.receiptId()==null)throw new Denied(503);
        }
        private List<JsonNode> smsMessages(String expected) throws Exception {
            if(closed.get())throw new Denied(503);
            URI endpoint=smsOrigin.resolve("/api/messages?purpose=REGISTRATION_PHONE");
            var body=new Bytes(65536);
            var request=HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(4)).header("Authorization","Bearer "+smsReadKey).GET().build();
            var pending=reader.sendAsync(request,ignored->body);reads.add(pending);byte[] bytes=null;
            try{
                if(closed.get())throw new Denied(503);
                var response=pending.get(4,TimeUnit.SECONDS);bytes=response.body();
                if(response.statusCode()!=200||!response.headers().firstValue("Content-Type").orElse("").split(";",2)[0].strip().equalsIgnoreCase("application/json"))throw new Denied(503);
                JsonNode array=JSON.readTree(strictUtf8(bytes));if(array==null||!array.isArray()||array.size()>1000)throw new Denied(503);
                var matching=new ArrayList<JsonNode>();
                for(var message:array){
                    if(!message.isObject()||message.size()!=5||!message.has("id")||!message.has("destination")||!message.has("text")||!message.has("purpose")||!message.has("createdAt"))throw new Denied(503);
                    if(!message.path("id").isTextual()||!message.path("destination").isTextual()||!message.path("text").isTextual()||!message.path("purpose").isTextual()||!message.path("createdAt").isIntegralNumber())throw new Denied(503);
                    if(expected.equals(message.path("destination").textValue())&&"REGISTRATION_PHONE".equals(message.path("purpose").textValue()))matching.add(message);
                }
                // No cross-machine timestamp comparison. Empty-before-issue + unique destination/purpose
                // + worker's acknowledged OPEN transition establish the controlled capture boundary.
                return matching;
            }finally{reads.remove(pending);body.cancel();pending.cancel(true);if(bytes!=null)Arrays.fill(bytes,(byte)0);}
        }
        @Override public Bound readiness(Flow flow,Check cancellation){
            check(cancellation);if(enrollment==null)throw new Denied(503);
            service.status("sso-provider",enrollment,flow);
            return local(flow,cancellation);
        }
        private Bound local(Flow flow,Check cancellation){
            check(cancellation);
            return database.withSession(session->session.transaction(sql->{
                PostgresVerificationProjectionStore.lockControls(sql);
                Instant deadline=new PostgresBankingEligibilityStore(sql).requireCurrentLocalRegistration(flow.issuer(),flow.subject());
                Instant checked=sql.fetchOne("select clock_timestamp()").get(0,OffsetDateTime.class).toInstant();
                check(cancellation);if(!checked.isBefore(deadline))throw new Denied(503);return new Bound(checked,deadline);
            }));
        }
        @Override public void close(){
            if(!closed.compareAndSet(false,true))return;
            for(var request:reads)request.cancel(true);
            closeAll(null,observer::close,delivery::close,()->smtp.release(email),reader::shutdownNow);
        }
    }
    /** One process-owned SMTP/STARTTLS peer, trust context and finite inbox. No message diagnostics. */
    static final class LocalSmtp implements AutoCloseable {
        private final Path directory,keyStore,passwordFile;
        private final ServerSocket listener;
        private volatile Socket active;
        private final Thread server;
        private final SSLContext serverTls;
        final SSLContext clientTls;
        private final String username,password;
        private final AtomicBoolean closed=new AtomicBoolean();
        private final BlockingQueue<String> messages=new ArrayBlockingQueue<>(1);
        private volatile boolean failed;
        private String recipient;
        LocalSmtp(String username,String password) throws Exception {
            this.username=username;this.password=password;
            directory=Files.createTempDirectory("armoney-local-smtp-test-");keyStore=directory.resolve("ephemeral.p12");passwordFile=directory.resolve("keystore-password");
            ServerSocket socket=null;SSLContext serverContext=null,clientContext=null;
            char[] storePassword=new char[0];
            try{
                byte[] secret=new byte[32];new SecureRandom().nextBytes(secret);storePassword=Base64.getUrlEncoder().withoutPadding().encodeToString(secret).toCharArray();Arrays.fill(secret,(byte)0);
                Files.writeString(passwordFile,new String(storePassword),StandardCharsets.US_ASCII);
                String tool=Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win")?"keytool.exe":"keytool").toString();
                Process child=new ProcessBuilder(tool,"-genkeypair","-alias","fixture","-keyalg","RSA","-keysize","2048","-storetype","PKCS12",
                    "-keystore",keyStore.toString(),"-storepass:file",passwordFile.toString(),"-keypass:file",passwordFile.toString(),
                    "-dname","CN=localhost","-ext","SAN=dns:localhost","-validity","2","-noprompt").redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
                try{if(!child.waitFor(30,TimeUnit.SECONDS)||child.exitValue()!=0)throw new Denied(503);}
                finally{if(child.isAlive()){child.destroyForcibly();if(!child.waitFor(5,TimeUnit.SECONDS))throw new Denied(503);}}
                KeyStore keys=KeyStore.getInstance("PKCS12");try(var input=Files.newInputStream(keyStore)){keys.load(input,storePassword);}
                var km=KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());km.init(keys,storePassword);
                var tm=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());tm.init(keys);
                serverContext=SSLContext.getInstance("TLS");serverContext.init(km.getKeyManagers(),null,null);
                clientContext=SSLContext.getInstance("TLS");clientContext.init(null,tm.getTrustManagers(),null);
                socket=new ServerSocket(0,1,InetAddress.getByName("127.0.0.1"));socket.setSoTimeout(1000);
            }catch(Exception failure){if(socket!=null)socket.close();erase();throw new Denied(503);}
            finally{Arrays.fill(storePassword,'\0');Files.deleteIfExists(passwordFile);}
            listener=socket;serverTls=serverContext;clientTls=clientContext;
            server=Thread.ofPlatform().name("code-authority-local-smtp").daemon(true).start(this::serve);
        }
        int port(){return listener.getLocalPort();}
        synchronized void recipient(String value){if(closed.get()||failed||recipient!=null||value==null)throw new Denied(503);recipient=value;messages.clear();}
        synchronized void release(String value){if(Objects.equals(value,recipient)){recipient=null;messages.clear();}}
        String code(String expected) throws InterruptedException {
            synchronized(this){if(failed||closed.get()||!Objects.equals(expected,recipient))throw new Denied(503);}
            String value=messages.poll(100,TimeUnit.MILLISECONDS);if(value==null||!messages.isEmpty())throw new Denied(503);return value;
        }
        private void serve(){
            while(!closed.get())try{
                active=listener.accept();active.setSoTimeout(5000);conversation(active);
            }catch(SocketTimeoutException idle){/* Owned finite listener wait. */}
            catch(Exception invalid){if(!closed.get())failed=true;}
            finally{Socket socket=active;active=null;if(socket!=null)try{socket.close();}catch(IOException ignored){}}
        }
        private void conversation(Socket socket) throws Exception {
            InputStream input=socket.getInputStream();OutputStream output=socket.getOutputStream();boolean tls=false,auth=false,acceptedRecipient=false;
            reply(output,"220 local SMTP test");
            while(!closed.get()){
                String command=line(input,4096);if(command==null)return;
                if(command.startsWith("EHLO ")){reply(output,"250-localhost");if(!tls)reply(output,"250-STARTTLS");reply(output,"250 AUTH PLAIN");}
                else if(command.equals("STARTTLS")&&!tls){
                    reply(output,"220 Begin TLS");socket=serverTls.getSocketFactory().createSocket(socket,"localhost",port(),true);active=socket;
                    ((SSLSocket)socket).setUseClientMode(false);socket.setSoTimeout(5000);((SSLSocket)socket).startHandshake();input=socket.getInputStream();output=socket.getOutputStream();tls=true;
                }else if(command.startsWith("AUTH PLAIN ")&&tls){
                    byte[] raw=Base64.getDecoder().decode(command.substring(11));
                    try{
                        String credentials=strictUtf8(raw);int first=credentials.indexOf(0),second=credentials.indexOf(0,first+1);
                        if(first<0||second<0||(!credentials.substring(0,first).isEmpty()&&!username.equals(credentials.substring(0,first)))||credentials.indexOf(0,second+1)>=0||!username.equals(credentials.substring(first+1,second))||!password.equals(credentials.substring(second+1)))throw new Denied(503);
                        auth=true;reply(output,"235 Authenticated");
                    }finally{Arrays.fill(raw,(byte)0);}
                }else if(command.startsWith("MAIL FROM:")&&auth)reply(output,"250 Sender accepted");
                else if(command.startsWith("RCPT TO:")&&auth){
                    String expected;synchronized(this){expected=recipient;}
                    if(expected==null||!command.equalsIgnoreCase("RCPT TO:<"+expected+">"))throw new Denied(503);
                    acceptedRecipient=true;reply(output,"250 Recipient accepted");
                }else if(command.equals("DATA")&&auth&&acceptedRecipient){
                    reply(output,"354 Send content");var bytes=new ByteArrayOutputStream();
                    for(int count=0;count<512;count++){
                        String row=line(input,4096);if(row==null)throw new Denied(503);if(row.equals("."))break;
                        if(row.startsWith(".."))row=row.substring(1);byte[] part=(row+"\r\n").getBytes(StandardCharsets.US_ASCII);
                        if(bytes.size()+part.length>65536)throw new Denied(503);bytes.writeBytes(part);if(count==511)throw new Denied(503);
                    }
                    byte[] raw=bytes.toByteArray();
                    try{
                        var message=new MimeMessage(Session.getInstance(new Properties()),new ByteArrayInputStream(raw));
                        if(message.getAllRecipients()==null||message.getAllRecipients().length!=1)throw new Denied(503);
                        String expected;synchronized(this){expected=recipient;}
                        if(!(message.getAllRecipients()[0] instanceof jakarta.mail.internet.InternetAddress address)||!Objects.equals(address.getAddress(),expected))throw new Denied(503);
                        Object content=message.getContent();String text=null;
                        if(content instanceof Multipart multipart){for(int n=0;n<multipart.getCount();n++){var part=multipart.getBodyPart(n);if(part.isMimeType("text/plain")){if(text!=null)throw new Denied(503);text=part.getContent().toString();}}}
                        else if(message.isMimeType("text/plain"))text=content.toString();
                        if(!messages.offer(otp(text)))throw new Denied(503);
                    }finally{Arrays.fill(raw,(byte)0);bytes.reset();}
                    reply(output,"250 Accepted");
                }else if(command.equals("QUIT")){reply(output,"221 Closed");return;}
                else throw new Denied(503);
            }
        }
        private static String line(InputStream input,int max) throws IOException {
            var bytes=new ByteArrayOutputStream();
            for(int size=0;size<=max;size++){int value=input.read();if(value<0)return null;if(value=='\n'){byte[] row=bytes.toByteArray();int length=row.length;if(length==0||row[length-1]!='\r')throw new Denied(503);return new String(row,0,length-1,StandardCharsets.US_ASCII);}if(value>127||value==0)throw new Denied(503);bytes.write(value);}throw new Denied(503);
        }
        private static void reply(OutputStream output,String value) throws IOException {output.write((value+"\r\n").getBytes(StandardCharsets.US_ASCII));output.flush();}
        private void erase() throws IOException {Files.deleteIfExists(passwordFile);Files.deleteIfExists(keyStore);Files.deleteIfExists(directory);}
        @Override public void close(){
            if(!closed.compareAndSet(false,true))return;
            try{listener.close();}catch(IOException ignored){}Socket socket=active;if(socket!=null)try{socket.close();}catch(IOException ignored){}
            server.interrupt();try{server.join(5000);if(server.isAlive())throw new Denied(503);erase();}catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new Denied(503);}catch(IOException invalid){throw new Denied(503);}
            synchronized(this){recipient=null;messages.clear();}
        }
    }
    private static final ObjectMapper JSON=new ObjectMapper().enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    static String strictUtf8(byte[] bytes) throws java.nio.charset.CharacterCodingException {
        return StandardCharsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes)).toString();
    }
    static String otp(String text){
        if(text==null||text.length()>4096)throw new Denied(503);
        var match=java.util.regex.Pattern.compile("Your ArMoney registration verification code is ([0-9]{6})\\. It expires in 5 minutes\\. Do not share this code\\.").matcher(text.strip());
        if(!match.matches())throw new Denied(503);return match.group(1);
    }
    static final class Bytes implements HttpResponse.BodySubscriber<byte[]> {
        private final int limit;
        private final ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        private final CompletableFuture<byte[]> result=new CompletableFuture<>();
        private java.util.concurrent.Flow.Subscription subscription;
        private boolean terminal;
        Bytes(int limit){this.limit=limit;}
        public CompletionStage<byte[]> getBody(){return result;}
        public synchronized void onSubscribe(java.util.concurrent.Flow.Subscription value){
            if(terminal||subscription!=null){value.cancel();return;}
            subscription=value;value.request(1);
        }
        public synchronized void onNext(List<java.nio.ByteBuffer> items){
            if(terminal)return;
            if(subscription==null){fail();return;}
            try{
                for(var item:items){
                    if(item.remaining()>limit-bytes.size())throw new Denied(503);
                    byte[] part=new byte[item.remaining()];item.get(part);
                    try{bytes.writeBytes(part);}finally{Arrays.fill(part,(byte)0);}
                }
                subscription.request(1);
            }catch(RuntimeException failed){fail();}
        }
        public synchronized void onError(Throwable error){if(!terminal)fail();}
        public synchronized void onComplete(){
            if(terminal)return;
            terminal=true;result.complete(bytes.toByteArray());bytes.reset();
        }
        private void fail(){
            terminal=true;
            try{if(subscription!=null)subscription.cancel();}
            finally{bytes.reset();result.completeExceptionally(new Denied(503));}
        }
        synchronized void cancel(){if(!terminal)fail();}
    }
    @Override public String toString(){return "CodeIntentAuthorityFixture[redacted]";}
}
