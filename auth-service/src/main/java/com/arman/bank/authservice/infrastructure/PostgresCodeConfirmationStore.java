package com.arman.bank.authservice.infrastructure;

import com.arman.bank.authservice.application.CodeConfirmationStore;
import com.arman.bank.authservice.domain.CodeConfirmation;
import com.arman.bank.authservice.domain.LoginProof;
import com.arman.bank.authservice.domain.LoginProof.Error;
import com.arman.bank.runtime.Database;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.function.Function;
import org.jooq.DSLContext;
import org.jooq.Record;

/** Dormant internal core. No routes, transport, factory, ACTIVE/session/notice or runtime clock claim. */
public final class PostgresCodeConfirmationStore implements CodeConfirmationStore {
    private final Database database;
    private final PostgresCodeIntentStore intents;
    private final PostgresLoginProofStore proofs;
    private final LoginProof.Policy policy;
    private final LegacyMigrationManagedProofCrypto migrationCrypto;
    private final com.arman.bank.authservice.application.MigrationUserCurrentPort userCurrent;
    public PostgresCodeConfirmationStore(Database database,PostgresCodeIntentStore intents,PostgresLoginProofStore proofs,LoginProof.Policy policy){this(database,intents,proofs,policy,null);}
    public PostgresCodeConfirmationStore(Database database,PostgresCodeIntentStore intents,PostgresLoginProofStore proofs,LoginProof.Policy policy,LegacyMigrationManagedProofCrypto migrationCrypto){this(database,intents,proofs,policy,migrationCrypto,null);}
    public PostgresCodeConfirmationStore(Database database,PostgresCodeIntentStore intents,PostgresLoginProofStore proofs,LoginProof.Policy policy,LegacyMigrationManagedProofCrypto migrationCrypto,com.arman.bank.authservice.application.MigrationUserCurrentPort userCurrent){
        this.userCurrent=userCurrent;this.migrationCrypto=migrationCrypto;this.database=Objects.requireNonNull(database);this.intents=Objects.requireNonNull(intents);this.proofs=Objects.requireNonNull(proofs);this.policy=Objects.requireNonNull(policy);
        if(!intents.owns(database)||!proofs.owns(database))throw new IllegalArgumentException("Confirmation requires one owning database");
    }
    @Override public <T>T inRequest(String actor,Function<Request,T> action){
        LoginProof.writer(actor,policy);
        return new PostgresLoginRequestScope(database).inRequest(actor,owned->action.apply(new Scoped(owned)));
    }
    private record Anchored(Scoped owner,UUID id,UUID intent,String key,LoginProof.Resolve code,LoginProof.Write write,
                            Instant startedAt,byte[] stamp,byte[] business,boolean replay,SourceEligibilitySql.Current migration,long monotonicStart) implements Admission {
        private Anchored{stamp=stamp.clone();business=business.clone();}
        @Override public LoginProof.Binding binding(){return code.binding();}
        @Override public String toString(){return "CodeConfirmationAdmission[redacted]";}
    }
    private final class Scoped implements Request {
        private final PostgresLoginRequestScope.Request owned;
        private boolean admitted,completed;
        private Scoped(PostgresLoginRequestScope.Request owned){this.owned=owned;}
        @Override public Admission admit(LoginProof.Resolve code){
            owned.check();if(admitted)throw CodeConfirmation.unavailable();admitted=true;long monotonicStart=System.nanoTime();LoginProof.validate(code,policy);
            var anchored=owned.transaction(sql->{
                var context=intents.confirmationContext(sql,owned.actor(),code);
                if(context.isMigration()&&userCurrent==null)throw CodeConfirmation.unavailable();
                var intent=context.intent();UUID id=intent.get("id",UUID.class);String key=intent.get("retention_key_id",String.class);
                var anchors=sql.fetch("select * from sso_code_confirmation_anchors where intent_id=? order by generation for update",id);
                var result=sql.fetchOne("select * from sso_code_confirmation_results where intent_id=? for update",id);
                int generation=0;boolean replay=result!=null;
                if(!replay){if(!anchors.isEmpty())throw CodeConfirmation.unavailable();}
                else{
                    verifyResult(result,context,code,owned.actor());
                    for(var prior:anchors){
                        // FIRST's immutable result is completion evidence, never permission to overlap its window.
                        if(now(sql).isBefore(time(prior,"started_at").plusSeconds(4))
                            ||("REPLAY".equals(prior.get("purpose",String.class))
                                &&sql.fetchOne("select anchor_id from sso_code_confirmation_anchor_completions where anchor_id=?",prior.get("id",UUID.class))==null))throw CodeConfirmation.unavailable();
                        generation=Math.max(generation,prior.get("generation",Short.class)+1);
                    }
                    generation=Math.max(1,generation);
                }
                if(generation>31||anchors.size()>=32||count(sql,"sso_code_confirmation_anchors")>=100000)throw CodeConfirmation.unavailable();
                Instant start=now(sql);LoginProof.unexpired(context.write().expiresAt(),start);
                UUID anchor=UUID.randomUUID();byte[] stamp=intents.confirmationStamp(context),business=business(key,id,owned.actor(),code);
                if(context.isMigration())insertMigrationAnchor(sql,context,anchor,id,key,owned.actor(),code,business,replay,generation,start,stamp);
                else sql.execute("""
                    insert into sso_code_confirmation_anchors(id,intent_id,retention_key_id,actor_hash,binding_hash,confirmation_hash,purpose,generation,started_at,authority_hash,verification_epoch,authority_revision,expires_at,erase_at)
                    values(?,?,?,?,?,?,?,?,cast(? as timestamptz),?,?,?,cast(? as timestamptz),cast(? as timestamptz))
                    """,anchor,id,key,actor(key,owned.actor()),binding(key,code),business,replay?"REPLAY":"FIRST",generation,start.toString(),stamp,
                    context.epoch(),context.revision(),context.write().expiresAt().toString(),context.write().expiresAt().plusSeconds(300).toString());
                checkStamp(sql,context,stamp);localLive(context.localDeadline(),now(sql));LoginProof.unexpired(context.write().expiresAt(),now(sql));
                return new Anchored(this,anchor,id,key,code,context.write(),start,stamp,business,replay,context.migration(),monotonicStart);
            });
            owned.verify();
            return anchored;
        }
        @Override public CodeConfirmation.Outcome complete(Admission supplied,CodeConfirmation.Facts facts){
            owned.check();if(completed)throw CodeConfirmation.unavailable();completed=true;
            if(!(supplied instanceof Anchored anchor)||anchor.owner()!=this)throw CodeConfirmation.unavailable();
            // Receive time is captured once, before final lock waits. No resampling/renewal.
            Instant received=owned.transaction(PostgresCodeConfirmationStore::now);
            Instant deadline=CodeConfirmation.deadline(anchor.binding(),facts,anchor.startedAt(),received);
            var liveUser=anchor.migration()==null?null:SourceEligibilitySql.observeUser(owned,anchor.migration(),anchor.id(),anchor.startedAt(),anchor.monotonicStart(),userCurrent);
            return owned.transaction(sql->{
                var context=intents.confirmationContext(sql,owned.actor(),anchor.code(),anchor.stamp());
                if(liveUser!=null)liveUser.require(sql,true,migrationCrypto);
                same(anchor.stamp(),intents.confirmationStamp(context));
                var persisted=sql.fetchOne("select * from sso_code_confirmation_anchors where id=? and intent_id=? for update",anchor.id(),anchor.intent());
                if(persisted==null||!time(persisted,"started_at").equals(anchor.startedAt()))throw CodeConfirmation.unavailable();
                same(persisted.get("authority_hash",byte[].class),anchor.stamp());same(persisted.get("confirmation_hash",byte[].class),anchor.business());
                CodeConfirmation.fresh(anchor.startedAt(),deadline,now(sql));
                boolean negative=!facts.enabled()||!facts.userSessionActive()||!facts.clientSessionActive()
                    ||!Objects.equals(facts.username(),facts.phone())||!contacts(sql,context,facts);
                if(negative){
                    if(context.isMigration())SourceEligibilitySql.suspend(sql,context.migration(),"PROVIDER_NEGATIVE",migrationCrypto);
                    else new PostgresBankingEligibilityStore(sql).invalidateAfterMutation(context.authority().get("enrollment_id",UUID.class));
                    checkDenial(sql,context);
                    Instant finalNow=now(sql);
                    localLive(context.localDeadline(),finalNow);
                    CodeConfirmation.fresh(anchor.startedAt(),deadline,finalNow);
                    LoginProof.unexpired(anchor.write().expiresAt(),finalNow);
                    if(liveUser!=null)liveUser.require(sql,false,migrationCrypto);
                    return CodeConfirmation.Outcome.denied();
                }
                var result=sql.fetchOne("select * from sso_code_confirmation_results where intent_id=? for update",anchor.intent());
                if(anchor.replay()&&result==null)throw CodeConfirmation.unavailable();
                if(result!=null)verifyResult(result,context,anchor.code(),owned.actor());
                else{
                    var health=sql.fetchOne("select healthy,swept_at from sso_code_intent_cleanup where id=1");
                    if(health==null||!Boolean.TRUE.equals(health.get("healthy",Boolean.class))||health.get("swept_at")==null
                        ||time(health,"swept_at").isBefore(now(sql).minusSeconds(60)))throw CodeConfirmation.unavailable();
                    if(count(sql,"sso_code_confirmation_results")>=100000)throw new LoginProof.Failure(Error.RATE_LIMITED);
                    if(sql.fetchOne("select id from sso_login_creation_reservations where id=? and kind='INTENT'",anchor.intent())==null)throw CodeConfirmation.unavailable();
                }
                var observation=new LoginProof.Observation(anchor.binding(),facts.username(),facts.email(),facts.phone(),facts.enabled(),facts.userSessionActive()&&facts.clientSessionActive(),deadline,anchor.startedAt());
                var snapshot=proofs.confirmInTransaction(sql,owned.actor(),anchor.write(),result==null?null:result.get("proof_id",UUID.class),()->{
                    checkStamp(sql,context,anchor.stamp());CodeConfirmation.fresh(anchor.startedAt(),deadline,now(sql));
                    try{return context.isMigration()?new PostgresBankingEligibilityStore(sql,migrationCrypto).requireMigrationForProof(anchor.binding(),facts,anchor.startedAt(),liveUser):new PostgresBankingEligibilityStore(sql).requireForProof(anchor.binding(),observation,anchor.startedAt());}
                    catch(com.arman.bank.authservice.domain.Verification.Failure denied){throw new LoginProof.Failure(denied.error()==com.arman.bank.authservice.domain.Verification.Error.CONFLICT?Error.CONFLICT:Error.UNAVAILABLE);}
                });
                if(result==null&&context.isMigration())insertMigrationResult(sql,context,anchor,snapshot,owned.actor());
                else if(result==null)sql.execute("""
                    insert into sso_code_confirmation_results(intent_id,proof_id,budget_origin_id,retention_key_id,actor_hash,binding_hash,payload_hash,confirmation_hash,expires_at,retained_until)
                    values(?,?,?,?,?,?,?,?,cast(? as timestamptz),cast(? as timestamptz))
                    """,anchor.intent(),snapshot.proofId(),anchor.intent(),anchor.key(),actor(anchor.key(),owned.actor()),binding(anchor.key(),anchor.code()),
                    context.intent().get("payload_hash",byte[].class),anchor.business(),anchor.write().expiresAt().toString(),anchor.write().expiresAt().plusSeconds(604800).toString());
                if(anchor.replay())sql.execute("insert into sso_code_confirmation_anchor_completions values(?,?,cast(? as timestamptz))",anchor.id(),snapshot.proofId(),now(sql).toString());
                // Includes result/completion inserts: no late trigger/expiry can escape final reread.
                checkStamp(sql,context,anchor.stamp());CodeConfirmation.fresh(anchor.startedAt(),deadline,now(sql));
                if(context.isMigration())new PostgresBankingEligibilityStore(sql,migrationCrypto).requireMigrationForProof(anchor.binding(),facts,anchor.startedAt(),liveUser);
                else new PostgresBankingEligibilityStore(sql).requireForProof(anchor.binding(),observation,anchor.startedAt());
                LoginProof.unexpired(anchor.write().expiresAt(),now(sql));
                if(liveUser!=null)liveUser.require(sql,true,migrationCrypto);
                return CodeConfirmation.Outcome.confirmed(snapshot);
            });
        }
    }
    private void insertMigrationAnchor(DSLContext sql,PostgresCodeIntentStore.ConfirmationContext context,UUID anchor,UUID intent,String key,String writer,LoginProof.Resolve code,byte[] business,boolean replay,int generation,Instant start,byte[] stamp){
        var a=context.migration().authority();
        sql.execute("""
            insert into sso_code_confirmation_anchors(id,intent_id,retention_key_id,actor_hash,binding_hash,confirmation_hash,purpose,generation,started_at,authority_hash,verification_epoch,authority_revision,expires_at,erase_at,
              eligibility_source,eligibility_source_id,classification_generation,source_revision,source_stamp,migration_decision_id,migration_email_receipt_generation,migration_phone_receipt_generation,migration_verified_contact_generation)
            values(?,?,?,?,?,?,?,?,cast(? as timestamptz),?,?,?,cast(? as timestamptz),cast(? as timestamptz),?,?,?,?,?,?,?,?,?)
            """,anchor,intent,key,actor(key,writer),binding(key,code),business,replay?"REPLAY":"FIRST",generation,start.toString(),stamp,context.epoch(),context.revision(),context.write().expiresAt().toString(),context.write().expiresAt().plusSeconds(300).toString(),
            SourceEligibilitySql.MIGRATION,a.get("decision_id"),a.get("classification_generation"),a.get("source_revision"),context.migration().stamp(),a.get("decision_id"),a.get("email_receipt_generation"),a.get("phone_receipt_generation"),a.get("verified_contact_generation"));
    }
    private void insertMigrationResult(DSLContext sql,PostgresCodeIntentStore.ConfirmationContext context,Anchored anchor,LoginProof.Snapshot proof,String writer){
        var a=context.migration().authority();
        sql.execute("""
            insert into sso_code_confirmation_results(intent_id,proof_id,budget_origin_id,retention_key_id,actor_hash,binding_hash,payload_hash,confirmation_hash,expires_at,retained_until,
              eligibility_source,eligibility_source_id,classification_generation,source_revision,source_stamp,migration_decision_id,migration_email_receipt_generation,migration_phone_receipt_generation,migration_verified_contact_generation)
            values(?,?,?,?,?,?,?,?,cast(? as timestamptz),cast(? as timestamptz),?,?,?,?,?,?,?,?,?)
            """,anchor.intent(),proof.proofId(),anchor.intent(),anchor.key(),actor(anchor.key(),writer),binding(anchor.key(),anchor.code()),context.intent().get("payload_hash",byte[].class),anchor.business(),anchor.write().expiresAt().toString(),anchor.write().expiresAt().plusSeconds(604800).toString(),
            SourceEligibilitySql.MIGRATION,a.get("decision_id"),a.get("classification_generation"),a.get("source_revision"),context.migration().stamp(),a.get("decision_id"),a.get("email_receipt_generation"),a.get("phone_receipt_generation"),a.get("verified_contact_generation"));
    }
    private boolean contacts(DSLContext sql,PostgresCodeIntentStore.ConfirmationContext context,CodeConfirmation.Facts facts){
        if(context.isMigration())return Objects.equals(context.migration().authority().get("canonical_email"),facts.email())&&Objects.equals(context.migration().authority().get("canonical_phone"),facts.phone());
        var rows=sql.fetch("select id,canonical_value from verification_candidates where id in (?,?)",context.authority().get("email_candidate_id",UUID.class),context.authority().get("phone_candidate_id",UUID.class));
        String email=null,phone=null;
        for(var row:rows){if(row.get("id").equals(context.authority().get("email_candidate_id")))email=row.get("canonical_value",String.class);else phone=row.get("canonical_value",String.class);}
        return email!=null&&phone!=null&&email.equals(facts.email())&&phone.equals(facts.phone());
    }
    private void verifyResult(Record row,PostgresCodeIntentStore.ConfirmationContext context,LoginProof.Resolve code,String actor){
        String key=context.intent().get("retention_key_id",String.class);UUID id=context.intent().get("id",UUID.class);
        if(!key.equals(row.get("retention_key_id"))||!id.equals(row.get("budget_origin_id")))throw new LoginProof.Failure(Error.CONFLICT);
        same(row.get("actor_hash",byte[].class),actor(key,actor));same(row.get("binding_hash",byte[].class),binding(key,code));
        same(row.get("confirmation_hash",byte[].class),business(key,id,actor,code));same(row.get("payload_hash",byte[].class),context.intent().get("payload_hash",byte[].class));
        if(context.isMigration()){var a=context.migration().authority();if(!SourceEligibilitySql.MIGRATION.equals(row.get("eligibility_source"))||!a.get("decision_id").equals(row.get("eligibility_source_id"))||!a.get("classification_generation").equals(row.get("classification_generation"))||!a.get("source_revision").equals(row.get("source_revision")))throw CodeConfirmation.unavailable();same(row.get("source_stamp",byte[].class),context.migration().stamp());}
        else if(!"REGISTRATION_DUAL_CONTACT".equals(row.get("eligibility_source")))throw CodeConfirmation.unavailable();
    }
    private void checkStamp(DSLContext sql,PostgresCodeIntentStore.ConfirmationContext original,byte[] expected){
        if(original.isMigration()){intents.requireCurrentMigration(sql,original,expected);return;}
        var current=sql.fetchOne("select a.*,e.expires_at as enrollment_deadline from verification_authorities a join verification_enrollments e on e.id=a.enrollment_id where a.enrollment_id=?",original.authority().get("enrollment_id",UUID.class));
        if(current==null||!original.identity().equals(current.get("mapped_identity_id",UUID.class))||!original.identity().equals(current.get("pending_identity_id",UUID.class)))throw new LoginProof.Failure(Error.CONFLICT);
        same(expected,intents.confirmationStamp(new PostgresCodeIntentStore.ConfirmationContext(original.intent(),current,original.identity(),original.write())));
        if(sql.fetchOne("select identity_id from managed_registration_principals where identity_id=? and enrollment_id=?",original.identity(),current.get("enrollment_id",UUID.class))==null
            ||sql.fetchOne("select identity_id from external_identities where issuer=? and subject=? and identity_id=?",original.write().issuer(),original.write().subject(),original.identity())==null)throw new LoginProof.Failure(Error.CONFLICT);
        LoginProof.unexpired(time(current,"expires_at"),now(sql));LoginProof.unexpired(time(current,"enrollment_deadline"),now(sql));
    }
    private void checkDenial(DSLContext sql,PostgresCodeIntentStore.ConfirmationContext original){
        if(original.isMigration()){SourceEligibilitySql.requireOwnSuspension(sql,original.migration());return;}
        var before=original.authority();
        var after=sql.fetchOne("select a.*,e.expires_at as enrollment_deadline from verification_authorities a join verification_enrollments e on e.id=a.enrollment_id where a.enrollment_id=?",before.get("enrollment_id",UUID.class));
        if(after==null||!"SUSPENDED".equals(after.get("state"))||after.get("verification_epoch",Long.class)!=Math.addExact(before.get("verification_epoch",Long.class),1)
            ||after.get("revision",Integer.class)!=Math.addExact(before.get("revision",Integer.class),1))throw new LoginProof.Failure(Error.CONFLICT);
        for(String field:List.of("mapped_identity_id","pending_identity_id","email_candidate_id","phone_candidate_id","projection_operation_id","projection_revision","projection_command_id","expires_at","enrollment_deadline"))
            if(!Objects.equals(before.get(field),after.get(field)))throw new LoginProof.Failure(Error.CONFLICT);
        if(sql.fetchOne("select identity_id from managed_registration_principals where identity_id=? and enrollment_id=?",original.identity(),before.get("enrollment_id",UUID.class))==null
            ||sql.fetchOne("select identity_id from external_identities where issuer=? and subject=? and identity_id=?",original.write().issuer(),original.write().subject(),original.identity())==null)throw new LoginProof.Failure(Error.CONFLICT);
        LoginProof.unexpired(time(after,"expires_at"),now(sql));LoginProof.unexpired(time(after,"enrollment_deadline"),now(sql));
    }
    private byte[] actor(String key,String actor){return intents.confirmationHash(key,"armoney-confirmation-anchor-actor-v1",actor);}
    private byte[] binding(String key,LoginProof.Resolve c){return intents.confirmationHash(key,"armoney-confirmation-binding-v1",c.codeDigest(),c.codeKeyId(),c.issuer(),c.subject(),c.clientId(),c.userSessionId());}
    private byte[] business(String key,UUID intent,String actor,LoginProof.Resolve c){return intents.confirmationHash(key,"armoney-code-confirmation-v1",actor,intent.toString(),c.codeDigest(),c.codeKeyId(),c.issuer(),c.subject(),c.clientId(),c.userSessionId());}
    private static void same(byte[] a,byte[] b){if(a==null||b==null||!MessageDigest.isEqual(a,b))throw new LoginProof.Failure(Error.CONFLICT);}
    private static void localLive(Instant deadline,Instant checked){if(deadline==null||!checked.isBefore(deadline))throw CodeConfirmation.unavailable();}
    private static Instant now(DSLContext sql){return sql.fetchOne("select clock_timestamp()").get(0,OffsetDateTime.class).toInstant();}
    private static Instant time(Record row,String field){return row.get(field,OffsetDateTime.class).toInstant();}
    private static long count(DSLContext sql,String table){return sql.fetchOne("select count(*) from "+table).get(0,Long.class);}
}
