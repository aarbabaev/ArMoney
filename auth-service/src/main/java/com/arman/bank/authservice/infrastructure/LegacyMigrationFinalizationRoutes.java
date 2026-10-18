package com.arman.bank.authservice.infrastructure;

import com.arman.bank.authservice.application.LegacyMigrationFinalizationStore;
import com.arman.bank.authservice.domain.LegacyMigrationFinalization;
import com.arman.bank.authservice.domain.LegacyMigrationFinalization.*;
import com.arman.bank.authservice.domain.LegacyMigrationFinalization.Error;
import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.*;
import io.javalin.config.JavalinConfig;
import io.javalin.http.Context;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.Semaphore;

/** PRIMARY internal readback only. Explicit construction does not register routes in Main. */
public final class LegacyMigrationFinalizationRoutes {
    public static final String PREFIX="/v1/internal/migrations/legacy/",HEADER="X-ArMoney-Migration-Decision-Key";
    private static final ObjectMapper JSON=new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final LegacyMigrationFinalizationStore store;
    private final byte[] key;
    private final boolean enabled;
    private final Semaphore calls=new Semaphore(16);
    private long window=System.nanoTime();private int budget;
    public LegacyMigrationFinalizationRoutes(LegacyMigrationFinalizationStore store,String decisionKey,boolean enabled){
        this.store=Objects.requireNonNull(store);if(decisionKey==null||!decisionKey.matches("[!-~]{32,256}"))throw new IllegalArgumentException("Invalid migration decision credential");key=decisionKey.getBytes(StandardCharsets.US_ASCII);this.enabled=enabled;
    }
    public void configure(JavalinConfig config){if(!enabled)return;config.routes.post(PREFIX+"decision",ctx->handle(ctx,false));config.routes.post(PREFIX+"publication-evidence",ctx->handle(ctx,true));}
    private synchronized boolean admit(){long now=System.nanoTime();if(now-window>=60_000_000_000L){window=now;budget=0;}return ++budget<=120;}
    private void authenticate(Context ctx){if(Collections.list(ctx.req().getHeaders(HEADER)).size()!=1)throw new Failure(Error.UNAUTHORIZED);String supplied=ctx.header(HEADER);if(supplied==null||!supplied.matches("[!-~]{32,256}")||!MessageDigest.isEqual(key,supplied.getBytes(StandardCharsets.US_ASCII)))throw new Failure(Error.UNAUTHORIZED);}
    private void handle(Context ctx,boolean evidence){
        boolean acquired=false;ctx.header("Cache-Control","no-store");
        try{
            authenticate(ctx);acquired=calls.tryAcquire();if(!acquired||!admit())throw new Failure(Error.RATE_LIMITED);
            if(ctx.contentType()==null||!ctx.contentType().split(";",2)[0].strip().equalsIgnoreCase("application/json"))throw new Failure(Error.BAD_INPUT);
            Map<String,Object> body;
            try(var deadline=LoginProofBodyDeadline.begin(ctx.req())){body=parse(deadline.read(ctx.req().getInputStream(),12289));}
            LegacyMigrationFinalization.exact(body,evidence?LegacyMigrationFinalization.EVIDENCE_QUERY:LegacyMigrationFinalization.DECISION_QUERY);
            var migration=LegacyMigrationFinalization.uuid(body.get("migration_id"));var stage=LegacyMigrationFinalization.uuid(body.get("stage_operation_id"));var command=LegacyMigrationFinalization.uuid(body.get("command_id"));LegacyMigrationFinalization.digest(body.get("payload_hash"));
            Object result=evidence?store.evidence(new EvidenceQuery(migration,LegacyMigrationFinalization.uuid(body.get("decision_id")),stage,command,(String)body.get("payload_hash"))).fields():store.decision(new DecisionQuery(migration,stage,command,(String)body.get("payload_hash"))).fields();
            reply(ctx,200,result);
        }catch(Failure failure){int status=switch(failure.error()){case BAD_INPUT->400;case UNAUTHORIZED->401;case CONFLICT->409;case RATE_LIMITED->429;case UNAVAILABLE->503;};if(status==429)ctx.header("Retry-After","60");reply(ctx,status,Map.of("error",status==503?"unavailable":"rejected"));}
        catch(Exception failure){reply(ctx,503,Map.of("error","unavailable"));}
        finally{if(acquired)calls.release();}
    }
    static Map<String,Object> parse(byte[] bytes){
        if(bytes==null||bytes.length>12288)throw new Failure(Error.BAD_INPUT);
        try(var parser=JSON.getFactory().createParser(bytes)){
            while(parser.nextToken()!=null)if(parser.currentToken().isNumeric()){if(parser.currentToken()!=JsonToken.VALUE_NUMBER_INT||!parser.getText().matches("0|[1-9][0-9]*"))throw new Failure(Error.BAD_INPUT);parser.getLongValue();}
            Map<String,Object> value=JSON.readValue(bytes,new TypeReference<LinkedHashMap<String,Object>>(){});if(value==null)throw new Failure(Error.BAD_INPUT);return value;
        }catch(Exception invalid){throw new Failure(Error.BAD_INPUT);}
    }
    private static void reply(Context ctx,int status,Object body){try{byte[] bytes=JSON.writeValueAsBytes(body);if(bytes.length>12288)throw new Failure(Error.UNAVAILABLE);ctx.status(status).contentType("application/json").result(bytes);}catch(com.fasterxml.jackson.core.JsonProcessingException failure){throw new Failure(Error.UNAVAILABLE);}}
    @Override public String toString(){return "MigrationFinalizationRoutes[redacted]";}
}
