package com.arman.bank.authservice.infrastructure;

import com.arman.bank.authservice.application.CodeConfirmationService;
import com.arman.bank.authservice.domain.LoginProof;
import io.javalin.config.JavalinConfig;
import io.javalin.http.Context;
import java.util.*;

/** Explicit current confirmation only. No historical resolver, provider transport or Main registration. */
public final class CodeConfirmationRoutes {
    private final CodeConfirmationService service;
    private final LoginProof.Policy policy;
    private final CodeIntentPairScopes scopes;
    private final String actor;
    private final byte[] credential;
    public CodeConfirmationRoutes(CodeConfirmationService service,LoginProof.Policy policy,CodeIntentPairScopes scopes,String actor,String credential){
        this.service=Objects.requireNonNull(service);this.policy=Objects.requireNonNull(policy);this.scopes=Objects.requireNonNull(scopes);
        LoginProof.writer(actor,policy);this.actor=actor;this.credential=LoginProofRoutes.credential(credential);
    }
    public void configure(JavalinConfig config){config.routes.post("/internal/sso-code-intents/confirm",this::handle);}
    private void handle(Context ctx){
        ctx.header("Cache-Control","no-store");
        try{
            CodeIntentHttp.authenticate(ctx,"X-ArMoney-Login-Proof-Confirm-Key",credential);
            com.arman.bank.authservice.domain.CodeConfirmation.Outcome outcome;
            try(var deadline=LoginProofBodyDeadline.begin(ctx.req())){
                outcome=service.inRequest(actor,request->{
                    var body=CodeIntentHttp.body(ctx,deadline,Set.of("code_digest","code_key_id","issuer","subject","client_id","user_session_id"));
                    var input=new LoginProof.Resolve(CodeIntentHttp.text(body,"code_digest"),CodeIntentHttp.text(body,"code_key_id"),CodeIntentHttp.text(body,"issuer"),CodeIntentHttp.text(body,"subject"),CodeIntentHttp.text(body,"client_id"),CodeIntentHttp.text(body,"user_session_id"));
                    LoginProof.validate(input,policy);
                    if(scopes.classify(input.issuer(),input.clientId())!=CodeIntentPairScopes.Classification.MANAGED)throw CodeIntentHttp.failure(LoginProof.Error.CONFLICT);
                    var confirmed=request.confirm(input);
                    if(confirmed==null)throw CodeIntentHttp.failure(LoginProof.Error.UNAVAILABLE);
                    if(!confirmed.deniedCommitted())validate(confirmed.snapshot(),input);
                    return confirmed;
                });
            }
            // The owning store transaction/scope has returned; this denial cannot roll back its suspension.
            if(outcome==null)throw CodeIntentHttp.failure(LoginProof.Error.UNAVAILABLE);
            if(outcome.deniedCommitted()){CodeIntentHttp.reject(ctx,409);return;}
            var snapshot=outcome.snapshot();
            CodeIntentHttp.reply(ctx,200,Map.of("proof_id",snapshot.proofId().toString(),"event_id",snapshot.eventId().toString(),"kind",snapshot.kind().wire(),
                "login_notice_eligible",snapshot.loginNoticeEligible(),"issued_at",snapshot.issuedAt().toString(),"expires_at",snapshot.expiresAt().toString()));
        }catch(LoginProof.Failure denied){CodeIntentHttp.reject(ctx,CodeIntentHttp.status(denied.error()));}
        catch(Exception unavailable){CodeIntentHttp.reject(ctx,503);}
    }
    private static void validate(LoginProof.Snapshot snapshot,LoginProof.Resolve input){
        if(snapshot==null||snapshot.proofId()==null||!input.codeKeyId().equals(snapshot.codeKeyId())||snapshot.kind()!=LoginProof.Kind.INTERACTIVE
            ||snapshot.eventId()==null||!snapshot.loginNoticeEligible()||snapshot.issuedAt()==null||snapshot.expiresAt()==null
            ||snapshot.issuedAt().getNano()!=0||snapshot.expiresAt().getNano()!=0||!snapshot.issuedAt().isBefore(snapshot.expiresAt())
            ||snapshot.expiresAt().isAfter(snapshot.issuedAt().plusSeconds(120)))throw CodeIntentHttp.failure(LoginProof.Error.UNAVAILABLE);
    }
}
