package com.arman.bank.authservice.infrastructure;

import com.arman.bank.authservice.application.CodeIntentStore;
import com.arman.bank.authservice.domain.CodeIntent;
import com.arman.bank.authservice.domain.LoginProof;
import com.arman.bank.authservice.domain.LoginProof.*;
import com.arman.bank.authservice.domain.LoginProof.Error;
import com.fasterxml.jackson.databind.*;
import io.javalin.config.JavalinConfig;
import io.javalin.http.Context;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

/** Explicitly constructed private stage transport only. Optional default-off composition; no confirmation. */
public final class CodeIntentRoutes {
    private static final java.util.logging.Logger LOG=java.util.logging.Logger.getLogger(CodeIntentRoutes.class.getName());
    private static final ObjectMapper JSON = new ObjectMapper()
        .enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final Set<String> BINDING = Set.of("code_digest", "code_key_id", "issuer", "subject", "client_id", "user_session_id");
    private final CodeIntentStore store;
    private final Policy policy;
    private final String actor;
    private final byte[] credential;
    private final CodeIntentPairScopes composedScopes;
    public CodeIntentRoutes(CodeIntentStore store, Policy policy, String actor, String credential) {
        this(store,policy,actor,credential,null);
    }
    /** Composed C2 only; standalone constructor preserves its existing independent scope contract. */
    CodeIntentRoutes(CodeIntentStore store,Policy policy,String actor,String credential,CodeIntentPairScopes composedScopes){
        this.composedScopes=composedScopes;
        this.store=Objects.requireNonNull(store);this.policy=Objects.requireNonNull(policy);
        LoginProof.writer(actor,policy);this.actor=actor;
        this.credential=LoginProofRoutes.credential(credential);
    }
    public void configure(JavalinConfig config) { config.routes.post("/internal/sso-code-intents",this::handle); }
    private void handle(Context ctx) {
        ctx.header("Cache-Control","no-store");
        try {
            var headers=ctx.req().getHeaders("X-ArMoney-Login-Proof-Write-Key");
            String supplied=headers.hasMoreElements()?headers.nextElement():null;
            if(supplied==null||headers.hasMoreElements()||supplied.length()>256
                ||!MessageDigest.isEqual(credential,supplied.getBytes(StandardCharsets.UTF_8)))throw new Failure(Error.UNAUTHORIZED);
            CodeIntent.Snapshot result;
            try(var deadline=LoginProofBodyDeadline.begin(ctx.req())) {
                result=store.inRequest(actor,request->{
                    if(!ctx.queryParamMap().isEmpty())throw new Failure(Error.BAD_INPUT);
                    var body=body(ctx,deadline);
                    Kind kind=switch(text(body,"kind")){case "interactive"->Kind.INTERACTIVE;case "reuse"->Kind.REUSE;default->throw new Failure(Error.BAD_INPUT);};
                    var input=new Write(uuid(body,"command_id"),text(body,"code_digest"),text(body,"code_key_id"),text(body,"issuer"),
                        text(body,"subject"),text(body,"client_id"),text(body,"user_session_id"),text(body,"authentication_session_id"),
                        time(body,"issued_at"),time(body,"expires_at"),kind,body.get("event_id").isNull()?null:uuid(body,"event_id"),
                        body.get("password_authenticated_at").isNull()?null:time(body,"password_authenticated_at"));
                    CodeIntent.validate(input,actor,policy);
                    if(composedScopes!=null&&composedScopes.classify(input.issuer(),input.clientId())!=CodeIntentPairScopes.Classification.MANAGED)throw new Failure(Error.CONFLICT);
                    var snapshot=request.stage(input);
                    if(snapshot==null||snapshot.intentId()==null||!input.codeKeyId().equals(snapshot.codeKeyId())
                        ||!input.issuedAt().equals(snapshot.issuedAt())||!input.expiresAt().equals(snapshot.expiresAt()))throw new Failure(Error.UNAVAILABLE);
                    return snapshot;
                });
            }
            if(result==null)throw new Failure(Error.UNAVAILABLE);
            var response=new LinkedHashMap<String,Object>();
            response.put("intent_id",result.intentId());response.put("code_key_id",result.codeKeyId());response.put("state",result.state());
            response.put("issued_at",result.issuedAt().toString());response.put("expires_at",result.expiresAt().toString());
            reply(ctx,200,response);
        } catch(Failure rejected) {
            LOG.warning("armoney_auth_stage_failure category=DOMAIN_"+rejected.error().name());
            int status=switch(rejected.error()){
                case BAD_INPUT->400;case UNAUTHORIZED->401;case CONFLICT->409;case EXPIRED->410;
                case RATE_LIMITED->429;case MISSING,UNAVAILABLE->503;
            };
            if(status==429)ctx.header("Retry-After","60");
            reply(ctx,status,Map.of("error","code_intent_request_rejected"));
        } catch(Exception unavailable){LOG.warning("armoney_auth_stage_failure category=INTERNAL");reply(ctx,503,Map.of("error","code_intent_request_rejected"));}
    }
    private static JsonNode body(Context ctx, LoginProofBodyDeadline deadline) {
        String type = ctx.contentType();
        if (type == null || !type.split(";", 2)[0].strip().equalsIgnoreCase("application/json")) throw new Failure(Error.BAD_INPUT);
        try {
            byte[] bytes = deadline.read(ctx.req().getInputStream(), 12289);
            if (bytes.length > 12288) throw new Failure(Error.BAD_INPUT);
            JsonNode body = JSON.readTree(bytes);
            Set<String> fields = new HashSet<>(BINDING);
            Collections.addAll(fields, "command_id", "authentication_session_id", "issued_at", "expires_at", "kind", "event_id", "password_authenticated_at");
            if (body == null || !body.isObject() || body.size() != fields.size()) throw new Failure(Error.BAD_INPUT);
            var names = body.fieldNames();
            while (names.hasNext()) if (!fields.contains(names.next())) throw new Failure(Error.BAD_INPUT);
            return body;
        } catch (java.io.IOException invalid) { throw new Failure(Error.BAD_INPUT); }
    }
    private static String text(JsonNode body, String field) {
        var value = body.get(field);
        if (value == null || !value.isTextual()) throw new Failure(Error.BAD_INPUT);
        return value.textValue();
    }
    private static UUID uuid(JsonNode body, String field) {
        String value = text(body, field);
        if (!value.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")) throw new Failure(Error.BAD_INPUT);
        return UUID.fromString(value);
    }
    private static Instant time(JsonNode body, String field) {
        String value = text(body, field);
        if (!value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z")) throw new Failure(Error.BAD_INPUT);
        try {
            Instant time = Instant.parse(value);
            if (!time.toString().equals(value)) throw new Failure(Error.BAD_INPUT);
            return time;
        } catch (java.time.DateTimeException invalid) { throw new Failure(Error.BAD_INPUT); }
    }
    private static void reply(Context ctx,int status,Map<String,?> body){
        try{ctx.status(status).contentType("application/json").result(JSON.writeValueAsString(body));}
        catch(java.io.IOException unavailable){throw new IllegalStateException("Stage serialization unavailable");}
    }
}
