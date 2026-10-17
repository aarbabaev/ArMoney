package com.arman.bank.authservice.infrastructure;

import com.arman.bank.authservice.application.CodeIntentKeyLookupStore;
import com.arman.bank.authservice.domain.CodeIntentKeyLookup;
import com.arman.bank.authservice.domain.CodeIntentKeyLookup.*;
import com.arman.bank.authservice.domain.CodeIntentKeyLookup.Error;
import com.arman.bank.authservice.domain.LoginProof;
import com.arman.bank.runtime.Database;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.function.Function;
import org.jooq.DSLContext;
import org.jooq.Record;

/** Read-only V12 point selection. Only existing technical gate/admission may write.
 * No routes, provider/native-code integration, catalog/decrypt, authority or clock-runtime claim. */
public final class PostgresCodeIntentKeyLookupStore implements CodeIntentKeyLookupStore {
    private final Database database;
    private final LoginProof.Policy policy;
    private final CodeIntentPairScopes scopes;
    private final CodeIntentRetentionKeys keys;
    public PostgresCodeIntentKeyLookupStore(Database database,LoginProof.Policy policy,CodeIntentPairScopes scopes,CodeIntentRetentionKeys keys){
        this.database=Objects.requireNonNull(database);this.policy=Objects.requireNonNull(policy);this.scopes=Objects.requireNonNull(scopes);this.keys=Objects.requireNonNull(keys);
        if(scopes.managed().isEmpty()||!policy.issuers().containsAll(scopes.managedIssuers())||!policy.clients().containsAll(scopes.managedClients()))
            throw new IllegalArgumentException("Invalid code intent key lookup scope");
    }
    @Override public <T>T inRequest(String actor,Function<Request,T> operation){
        try{
            LoginProof.writer(actor,policy);
            return new PostgresLoginRequestScope(database).inRequest(actor,owned->operation.apply(new Request(){
                private boolean used;
                @Override public Result lookup(Input input){
                    try{
                        owned.check();if(used)throw unavailable();used=true;
                        CodeIntentKeyLookup.validate(input,policy.codeKeyIds());
                        if(scopes.classify(input.issuer(),input.clientId())!=CodeIntentPairScopes.Classification.MANAGED)throw new Failure(Error.CONFLICT);
                        return owned.transaction(sql->select(sql,actor,input));
                    }catch(LoginProof.Failure rejected){throw rejected(rejected);}
                }
            }));
        }catch(Failure rejected){throw rejected;}
        catch(LoginProof.Failure rejected){throw rejected(rejected);}
        catch(RuntimeException unavailable){throw unavailable();}
    }
    private Result select(DSLContext sql,String actor,Input input){
        Record match=null;
        for(var candidate:input.candidates()){
            // V12 UNIQUE(code_digest), exactly one indexed point read per offered candidate; no tuple scan/index.
            var row=sql.fetchOne("select id,code_key_id,retention_key_id,actor_hash,binding_hash,expires_at from sso_code_intents where code_digest=?",candidate.codeDigest());
            if(row==null)continue;
            if(!candidate.codeKeyId().equals(row.get("code_key_id",String.class)))throw new Failure(Error.CONFLICT);
            String original=row.get("retention_key_id",String.class);
            byte[] actorHash=keys.hash(original,"armoney-intent-actor-v1",actor);
            byte[] bindingHash=keys.hash(original,"armoney-intent-binding-v1",input.issuer(),input.subject(),input.clientId(),input.userSessionId());
            if(!MessageDigest.isEqual(actorHash,row.get("actor_hash",byte[].class))||!MessageDigest.isEqual(bindingHash,row.get("binding_hash",byte[].class)))throw new Failure(Error.CONFLICT);
            if(!now(sql).isBefore(row.get("expires_at",OffsetDateTime.class).toInstant()))continue;
            if(match!=null&&!match.get("id",UUID.class).equals(row.get("id",UUID.class)))throw unavailable();
            match=row;
        }
        if(match==null)throw new Failure(Error.MISSING);
        // Recheck the ORIGINAL persisted expiry after both reads, without a third point read or renewal.
        if(!now(sql).isBefore(match.get("expires_at",OffsetDateTime.class).toInstant()))throw new Failure(Error.MISSING);
        return new Result(match.get("code_key_id",String.class));
    }
    private static Instant now(DSLContext sql){return sql.fetchOne("select clock_timestamp()").get(0,OffsetDateTime.class).toInstant();}
    private static Failure rejected(LoginProof.Failure rejected){return new Failure(switch(rejected.error()){
        case BAD_INPUT->Error.BAD_INPUT;case UNAUTHORIZED->Error.UNAUTHORIZED;case CONFLICT->Error.CONFLICT;
        case MISSING,EXPIRED->Error.MISSING;case RATE_LIMITED->Error.RATE_LIMITED;case UNAVAILABLE->Error.UNAVAILABLE;
    });}
    private static Failure unavailable(){return new Failure(Error.UNAVAILABLE);}
}
