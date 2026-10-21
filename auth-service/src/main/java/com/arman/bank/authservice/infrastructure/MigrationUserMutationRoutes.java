package com.arman.bank.authservice.infrastructure;

import com.arman.bank.authservice.domain.LoginProof;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.config.JavalinConfig;
import io.javalin.http.Context;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Private purpose-authenticated route. Selection does not register Main or activate banking. */
public final class MigrationUserMutationRoutes {
    public static final String PATH="/v1/internal/migrations/legacy/user-mutation-suspend",HEADER="X-ArMoney-Migration-User-Mutation-Key";
    private static final ObjectMapper JSON=new ObjectMapper();
    private final MigrationUserRevisionConfiguration configuration;private final PostgresMigrationUserMutationStore store;
    public MigrationUserMutationRoutes(MigrationUserRevisionConfiguration configuration,PostgresMigrationUserMutationStore store){
        this.configuration=Objects.requireNonNull(configuration);if(configuration.enabled()&&store==null)throw new IllegalArgumentException("User mutation route rejected");this.store=store;
    }
    public void configure(JavalinConfig config){if(configuration.enabled())config.routes.post(PATH,this::handle);}
    private void handle(Context ctx){
        ctx.header("Cache-Control","no-store");
        try{
            // Authenticate before protocol/body/scope/admission. Header chooses purpose writer; JSON never does.
            if(Collections.list(ctx.req().getHeaders(HEADER)).size()!=1||!matches(ctx.header(HEADER)))throw new PostgresMigrationUserMutationStore.Rejected(401);
            long started=System.nanoTime();
            if(!"HTTP/1.1".equals(ctx.req().getProtocol()))throw new PostgresMigrationUserMutationStore.Rejected(400);
            try(var deadline=LoginProofBodyDeadline.begin(ctx.req())){
                var result=store.inRequest(configuration.mutationWriter(),request->{
                    try{
                        if(ctx.contentType()==null||!"application/json".equalsIgnoreCase(ctx.contentType().split(";",2)[0].strip()))throw new PostgresMigrationUserMutationStore.Rejected(400);
                        byte[] bytes=deadline.read(ctx.req().getInputStream(),4097);if(bytes.length>4096)throw new PostgresMigrationUserMutationStore.Rejected(400);
                        Map<String,Object> input;try{input=HttpMigrationUserCurrent.parse(bytes);}catch(RuntimeException invalid){throw new PostgresMigrationUserMutationStore.Rejected(400);}
                        PostgresMigrationUserMutationStore.remaining(started);return request.suspend(input,started);
                    }catch(java.io.IOException unavailable){throw new PostgresMigrationUserMutationStore.Rejected(503);}
                });
                PostgresMigrationUserMutationStore.remaining(started);reply(ctx,200,result);
            }
        }catch(PostgresMigrationUserMutationStore.Rejected rejected){error(ctx,rejected.status());}
        catch(LoginProof.Failure rejected){error(ctx,rejected.error()==LoginProof.Error.RATE_LIMITED?429:rejected.error()==LoginProof.Error.BAD_INPUT?400:503);}
        catch(Exception unavailable){error(ctx,503);}
    }
    private boolean matches(String supplied){return supplied!=null&&supplied.matches("[!-~]{32,256}")&&MessageDigest.isEqual(supplied.getBytes(StandardCharsets.US_ASCII),configuration.mutationKey().getBytes(StandardCharsets.US_ASCII));}
    private static void error(Context ctx,int status){if(!Set.of(400,401,409,429,503).contains(status))status=503;reply(ctx,status,Map.of("error",status==409?"conflict":status==503?"unavailable":"rejected"));}
    private static void reply(Context ctx,int status,Object value){try{ctx.status(status).contentType("application/json").result(JSON.writeValueAsBytes(value));}catch(com.fasterxml.jackson.core.JsonProcessingException failure){ctx.status(503).contentType("application/json").result("{\"error\":\"unavailable\"}");}}
}
