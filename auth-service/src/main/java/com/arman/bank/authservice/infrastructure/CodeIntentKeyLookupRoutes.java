package com.arman.bank.authservice.infrastructure;

import com.arman.bank.authservice.application.CodeIntentKeyLookupStore;
import com.arman.bank.authservice.domain.CodeIntentKeyLookup;
import com.arman.bank.authservice.domain.LoginProof;
import io.javalin.config.JavalinConfig;
import io.javalin.http.Context;
import java.util.*;

/** Explicitly constructed C2 key-selection transport. Optional default-off composition; no authority. */
public final class CodeIntentKeyLookupRoutes {
    private final CodeIntentKeyLookupStore store;
    private final LoginProof.Policy policy;
    private final CodeIntentPairScopes scopes;
    private final String actor;
    private final byte[] credential;
    public CodeIntentKeyLookupRoutes(CodeIntentKeyLookupStore store,LoginProof.Policy policy,CodeIntentPairScopes scopes,String actor,String credential){
        this.store=Objects.requireNonNull(store);this.policy=Objects.requireNonNull(policy);this.scopes=Objects.requireNonNull(scopes);
        LoginProof.writer(actor,policy);this.actor=actor;this.credential=LoginProofRoutes.credential(credential);
    }
    public void configure(JavalinConfig config){config.routes.post("/internal/sso-code-intents/key",this::handle);}
    private void handle(Context ctx){
        ctx.header("Cache-Control","no-store");
        try{
            CodeIntentHttp.authenticate(ctx,"X-ArMoney-Code-Intent-Lookup-Key",credential);
            CodeIntentKeyLookup.Result result;
            try(var deadline=LoginProofBodyDeadline.begin(ctx.req())){
                result=store.inRequest(actor,request->{
                    var body=CodeIntentHttp.body(ctx,deadline,Set.of("issuer","subject","client_id","user_session_id","candidates"));
                    var nodes=body.get("candidates");
                    if(nodes==null||!nodes.isArray()||nodes.isEmpty()||nodes.size()>2)throw CodeIntentHttp.failure(LoginProof.Error.BAD_INPUT);
                    var candidates=new ArrayList<CodeIntentKeyLookup.Candidate>();
                    for(var node:nodes){CodeIntentHttp.exact(node,Set.of("code_key_id","code_digest"));candidates.add(new CodeIntentKeyLookup.Candidate(CodeIntentHttp.text(node,"code_key_id"),CodeIntentHttp.text(node,"code_digest")));}
                    var input=new CodeIntentKeyLookup.Input(CodeIntentHttp.text(body,"issuer"),CodeIntentHttp.text(body,"subject"),CodeIntentHttp.text(body,"client_id"),CodeIntentHttp.text(body,"user_session_id"),candidates);
                    CodeIntentKeyLookup.validate(input,policy.codeKeyIds());
                    if(scopes.classify(input.issuer(),input.clientId())!=CodeIntentPairScopes.Classification.MANAGED)throw CodeIntentHttp.failure(LoginProof.Error.CONFLICT);
                    var selected=request.lookup(input);
                    if(selected==null||input.candidates().stream().noneMatch(c->c.codeKeyId().equals(selected.codeKeyId())))throw CodeIntentHttp.failure(LoginProof.Error.UNAVAILABLE);
                    return selected;
                });
            }
            if(result==null)throw CodeIntentHttp.failure(LoginProof.Error.UNAVAILABLE);
            CodeIntentHttp.reply(ctx,200,Map.of("code_key_id",result.codeKeyId()));
        }catch(CodeIntentKeyLookup.Failure denied){CodeIntentHttp.reject(ctx,switch(denied.error()){case BAD_INPUT->400;case UNAUTHORIZED->401;case CONFLICT->409;case MISSING->404;case RATE_LIMITED->429;case UNAVAILABLE->503;});}
        catch(LoginProof.Failure denied){CodeIntentHttp.reject(ctx,CodeIntentHttp.status(denied.error()));}
        catch(Exception unavailable){CodeIntentHttp.reject(ctx,503);}
    }
}
