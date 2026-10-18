package com.arman.bank.authservice.infrastructure;

import com.fasterxml.jackson.databind.*;
import io.javalin.Javalin;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Disposable test artifact only. Never registered by AuthMain. Capabilities are declarative. */
public final class ProductionProviderAcceptanceController implements AutoCloseable {
    static final String PREFIX="/fixture/production-provider/", HEADER="X-ArMoney-Production-Acceptance-Key";
    static final List<String> OPERATIONS=List.of("capabilities","outcomes","read_delivery","remove_policy","restore_policy");
    static final ObjectMapper JSON=new ObjectMapper().enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    static final class Rejected extends RuntimeException {
        final int status;
        Rejected(int status){super("production_acceptance_rejected");this.status=status;}
    }
    static final class Deadline {
        private final long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(4);
        long remaining(){long left=end-System.nanoTime();if(left<=0||Thread.currentThread().isInterrupted())throw new Rejected(503);return left;}
        long millis(){return Math.max(1,TimeUnit.NANOSECONDS.toMillis(remaining()));}
    }
    interface Operations {
        Map<String,?> delivery(String purpose,Deadline deadline) throws Exception;
        Map<String,?> outcomes(Deadline deadline) throws Exception;
        void removePolicy(Deadline deadline) throws Exception;
        void restorePolicy(Deadline deadline) throws Exception;
    }
    private final byte[] key;
    private final String username;
    private final Operations operations;
    private final Semaphore controls=new Semaphore(16);
    private final ThreadPoolExecutor executor=new ThreadPoolExecutor(0,16,10,TimeUnit.SECONDS,new SynchronousQueue<>(),
        Thread.ofPlatform().daemon(true).name("production-acceptance-control-",0).factory(),new ThreadPoolExecutor.AbortPolicy());
    private final AtomicBoolean closed=new AtomicBoolean();
    private double credits=20;
    private long refilled=System.nanoTime();
    private int requests;
    private Javalin listener;
    ProductionProviderAcceptanceController(String key,String username,Operations operations){
        this.key=LoginProofRoutes.credential(key);this.username=Objects.requireNonNull(username);this.operations=Objects.requireNonNull(operations);
        if(!username.matches("\\+9715[024568][0-9]{7}"))throw new Rejected(503);
    }
    void start(String host,int port){
        if(closed.get()||listener!=null)throw new Rejected(503);
        listener=Javalin.create(config->{
            config.http.maxRequestSize=2048;
            config.routes.before(ctx->{ctx.header("Cache-Control","no-store");
                if(!"POST".equals(ctx.req().getMethod())||OPERATIONS.stream().noneMatch(op->ctx.path().equals(PREFIX+op)))
                    throw new io.javalin.http.HttpResponseException(404,"");});
            config.routes.exception(Exception.class,(error,ctx)->ctx.status(error instanceof io.javalin.http.HttpResponseException e&&e.getStatus()==404?404:503).result(""));
            for(String operation:OPERATIONS)config.routes.post(PREFIX+operation,ctx->{
                boolean acquired=false;Future<Map<String,?>> pending=null;byte[] bytes=null;
                try{
                    CodeIntentHttp.authenticate(ctx,HEADER,key); // Must precede body reads and work admission.
                    Deadline deadline=new Deadline();
                    if(closed.get()||!controls.tryAcquire())throw new Rejected(429);acquired=true;admit();
                    if(!ctx.queryParamMap().isEmpty())throw new Rejected(400);
                    var types=Collections.list(ctx.req().getHeaders("Content-Type"));
                    if(types.size()!=1||!types.getFirst().matches("(?i)application/json(?:\\s*;\\s*charset=utf-8)?"))throw new Rejected(400);
                    try(var bodyDeadline=LoginProofBodyDeadline.begin(ctx.req())){bytes=bodyDeadline.read(ctx.req().getInputStream(),2049);}
                    if(bytes.length>2048)throw new Rejected(400);
                    JsonNode body;
                    try{body=JSON.readTree(ProductionProviderAcceptanceTransport.strictUtf8(bytes));}catch(Exception invalid){throw new Rejected(400);}
                    Set<String> fields=switch(operation){case "read_delivery"->Set.of("username","purpose");case "outcomes"->Set.of("username");default->Set.of();};
                    CodeIntentHttp.exact(body,fields);
                    if(fields.contains("username")&&(!body.path("username").isTextual()||!username.equals(body.path("username").textValue())))throw new Rejected(400);
                    String purpose=fields.contains("purpose")?body.path("purpose").asText(""):"";
                    if(fields.contains("purpose")&&(!body.path("purpose").isTextual()||!Set.of("REGISTRATION_EMAIL","REGISTRATION_PHONE").contains(purpose)))throw new Rejected(400);
                    pending=executor.submit(()->{deadline.remaining();return switch(operation){
                        case "capabilities"->Map.of("version",2,"production_provider_only",true,"operations",OPERATIONS);
                        case "read_delivery"->operations.delivery(purpose,deadline);
                        case "outcomes"->outcomes(operations.outcomes(deadline));
                        case "remove_policy"->{operations.removePolicy(deadline);yield Map.of("state","disabled");}
                        case "restore_policy"->{operations.restorePolicy(deadline);yield Map.of("state","restored");}
                        default->throw new Rejected(404);
                    };});
                    Map<String,?> result=pending.get(deadline.remaining(),TimeUnit.NANOSECONDS);deadline.remaining();
                    CodeIntentHttp.reply(ctx,200,result);
                }catch(Rejected rejected){ctx.status(rejected.status).result("");}
                catch(com.arman.bank.authservice.domain.LoginProof.Failure rejected){ctx.status(CodeIntentHttp.status(rejected.error())).result("");}
                catch(Exception unavailable){ctx.status(503).result("");}
                finally{if(pending!=null)pending.cancel(true);if(bytes!=null)Arrays.fill(bytes,(byte)0);if(acquired)controls.release();}
            });
        });
        try{listener.start(host,port);}catch(RuntimeException failure){close();throw new Rejected(503);}
    }
    private synchronized void admit(){long now=System.nanoTime();credits=Math.min(20,credits+(now-refilled)/1_000_000_000.0*2);refilled=now;
        if(credits<1||requests>=512)throw new Rejected(429);credits--;requests++;}
    private static Map<String,?> outcomes(Map<String,?> result){
        if(result==null||!result.keySet().equals(Set.of("registration_active","projection_acknowledged","bank_session_count","confirmed_event_ids"))
            ||!(result.get("registration_active") instanceof Boolean)||!(result.get("projection_acknowledged") instanceof Boolean)
            ||!(result.get("bank_session_count") instanceof Integer count)||count<0||!(result.get("confirmed_event_ids") instanceof List<?> events))throw new Rejected(503);
        var output=new LinkedHashMap<String,Object>(result);output.put("confirmed_event_ids",ProductionProviderAcceptanceEvidence.confirmedEventIds(events));
        return Collections.unmodifiableMap(output);
    }
    int port(){if(listener==null)throw new Rejected(503);return listener.port();}
    @Override public void close(){if(!closed.compareAndSet(false,true))return;try{if(listener!=null)listener.stop();}finally{executor.shutdownNow();Arrays.fill(key,(byte)0);}}
    @Override public String toString(){return "ProductionProviderAcceptanceController[redacted]";}
}
