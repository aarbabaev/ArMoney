package com.arman.bank.authservice;

import com.arman.bank.authservice.infrastructure.CodeIntentConfiguration;
import com.arman.bank.runtime.Database;
import io.javalin.Javalin;

/** Isolated test process for the real stage adapter/factory. This is NOT production AuthMain wiring. */
public final class CodeIntentHttpProcessFixture {
    public static void main(String[] args) {
        var env=System.getenv();
        var database=new Database(env.get("DB_URL"),env.get("DB_USER"),env.get("DB_PASSWORD"));
        CodeIntentConfiguration.Resources resources=null;
        Javalin app=null;
        try {
            resources=new CodeIntentConfiguration(env).open(database).orElse(null);
            var selected=resources;
            app=Javalin.create(config->{
                config.routes.get("/fixture-ready",ctx->ctx.result("ready"));
                if(selected!=null)selected.routes().configure(config);
            }).start("127.0.0.1",Integer.parseInt(env.get("PORT")));
            if(resources!=null)resources.cleanupWorker().start();
            var started=app;
            Runtime.getRuntime().addShutdownHook(new Thread(()->{
                try{started.stop();}finally{try{if(selected!=null)selected.close();}finally{database.close();}}
            },"stage-fixture-shutdown"));
        } catch(RuntimeException failed){
            if(app!=null)app.stop();
            if(resources!=null)resources.close();
            database.close();
            throw failed;
        }
    }
}
