package com.arman.bank.authservice;

import com.arman.bank.authservice.application.CodeConfirmationService;
import com.arman.bank.authservice.domain.LoginProof;
import com.arman.bank.authservice.infrastructure.*;
import com.arman.bank.runtime.Database;
import io.javalin.Javalin;
import java.util.*;

/** TEST ONLY explicit C2 adapter process. Not AuthMain/config factory or provider/positive-authority evidence. */
public final class CodeIntentConfirmationHttpFixture {
    public static final String ISSUER="https://issuer.example/realm";
    public static final String LOOKUP_KEY="synthetic-c2-lookup-directional-credential";
    public static final String CONFIRM_KEY="synthetic-c2-confirm-directional-credential";
    private static String material(int value){byte[] bytes=new byte[32];Arrays.fill(bytes,(byte)value);return Base64.getEncoder().encodeToString(bytes);}
    public static void main(String[] args){
        var env=System.getenv();String selected=env.getOrDefault("FIXTURE_C2_ENABLED","false");
        if(!Set.of("true","false").contains(selected))throw new IllegalArgumentException("Invalid fixture switch");
        var database=new Database(env.get("DB_URL"),env.get("DB_USER"),env.get("DB_PASSWORD"));
        Javalin app=null;
        try{
            var policy=LoginProof.Policy.defaults(Set.of(ISSUER),Set.of("armoney-web"),Set.of("old"),Set.of("provider"));
            var scopes=new CodeIntentPairScopes("[{\"issuer\":\""+ISSUER+"\",\"client_id\":\"armoney-web\"}]","[]");
            var keys=new CodeIntentRetentionKeys("retained",Map.of("retained",material(21)));
            var lookup=new PostgresCodeIntentKeyLookupStore(database,policy,scopes,keys);
            var staging=new PostgresCodeIntentStore(database,policy,keys,new CodeIntentDetailCrypto("aead",Map.of("aead",material(22)),Set.of()));
            var proofs=new PostgresLoginProofStore(database,policy,Base64.getDecoder().decode(material(23)),sql->(binding,now)->{throw new LoginProof.Failure(LoginProof.Error.UNAVAILABLE);});
            var service=new CodeConfirmationService(new PostgresCodeConfirmationStore(database,staging,proofs,policy),
                (binding,start)->{throw new LoginProof.Failure(LoginProof.Error.UNAVAILABLE);});
            app=Javalin.create(config->{
                config.routes.get("/fixture-ready",ctx->ctx.result("ready"));
                if("true".equals(selected)){
                    new CodeIntentKeyLookupRoutes(lookup,policy,scopes,"provider",LOOKUP_KEY).configure(config);
                    new CodeConfirmationRoutes(service,policy,scopes,"provider",CONFIRM_KEY).configure(config);
                }
            }).start("127.0.0.1",Integer.parseInt(env.get("PORT")));
            var running=app;
            Runtime.getRuntime().addShutdownHook(new Thread(()->{try{running.stop();}finally{database.close();}},"c2-test-fixture-shutdown"));
        }catch(RuntimeException failed){if(app!=null)app.stop();database.close();throw failed;}
    }
}
