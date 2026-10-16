package com.arman.bank.authservice.infrastructure;

import com.arman.bank.authservice.application.CodeIntentStore;
import com.arman.bank.authservice.domain.CodeIntent;
import com.arman.bank.authservice.domain.LoginProof;
import com.arman.bank.authservice.domain.LoginProof.Error;
import com.arman.bank.runtime.Database;
import java.math.BigDecimal;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.concurrent.Semaphore;
import java.util.function.Function;
import org.jooq.DSLContext;
import org.jooq.Record;

/** Dormant internal stage storage. No provider observation, confirmation or production route. */
public final class PostgresCodeIntentStore implements CodeIntentStore {
    private final Database database;
    private final LoginProof.Policy policy;
    private final CodeIntentRetentionKeys keys;
    private final Semaphore inFlight = new Semaphore(16);
    public PostgresCodeIntentStore(Database database, LoginProof.Policy policy, CodeIntentRetentionKeys keys) {
        this.database=Objects.requireNonNull(database); this.policy=Objects.requireNonNull(policy); this.keys=Objects.requireNonNull(keys);
    }
    private <T> T transaction(Function<DSLContext,T> action) { return transaction(false,action); }
    private <T> T transaction(boolean bounded,Function<DSLContext,T> action) {
        return database.transaction(sql -> {
            if(bounded)sql.execute("set local lock_timeout='1s'");
            try { PostgresVerificationProjectionStore.lockControls(sql); }
            catch (com.arman.bank.authservice.domain.VerificationProjection.Failure failed) { fail(Error.UNAVAILABLE); }
            return action.apply(sql);
        });
    }
    @Override public CodeIntent.Snapshot stage(String actor, LoginProof.Write input) {
        CodeIntent.validate(input,actor,policy);
        if(!inFlight.tryAcquire()) throw new LoginProof.Failure(Error.RATE_LIMITED);
        try {
            // Admission is committed separately, including rejected requests; never refunded by rollback.
            boolean admitted=transaction(sql -> admit(sql,actor,now(sql)));
            if(!admitted) fail(Error.RATE_LIMITED);
            return transaction(sql -> stage(sql,actor,input));
        } finally { inFlight.release(); }
    }
    private CodeIntent.Snapshot stage(DSLContext sql,String actor,LoginProof.Write input) {
        Instant now=now(sql);
        var authority=sql.fetchOne("""
            select a.*,e.issuer,e.subject,e.expires_at as enrollment_deadline from verification_authorities a
            join verification_enrollments e on e.id=a.enrollment_id
            where e.issuer=? and e.subject=? for update of a,e
            """,input.issuer(),input.subject());
        if(authority==null||Set.of("SUSPENDED","BLOCKED_MIGRATION").contains(authority.get("state",String.class))) fail(Error.CONFLICT);
        LoginProof.unexpired(time(authority,"expires_at"),now); LoginProof.unexpired(time(authority,"enrollment_deadline"),now);
        UUID identity=authority.get("mapped_identity_id",UUID.class);
        if(identity==null||sql.fetchOne("select id from identities where id=? for update",identity)==null)fail(Error.CONFLICT);
        var marker=sql.fetchOne("select identity_id from managed_registration_principals where identity_id=? and enrollment_id=? for update",identity,authority.get("enrollment_id",UUID.class));
        var mapping=sql.fetchOne("select identity_id from external_identities where issuer=? and subject=? for update",input.issuer(),input.subject());
        if(identity==null||marker==null||mapping==null||!identity.equals(mapping.get("identity_id",UUID.class))) fail(Error.CONFLICT);
        var row=sql.fetchOne("select * from sso_code_intents where code_digest=? for update",input.codeDigest());
        // Command IDs are bounded by at most sixteen configured actor scopes. Never locate by a new-key hash alone.
        var commands=sql.fetch("select i.*,c.actor_hash as command_actor from sso_code_intent_commands c join sso_code_intents i on i.id=c.intent_id where c.command_id=?",input.commandId());
        if(commands.size()>16) fail(Error.UNAVAILABLE);
        for(var command:commands) {
            String key=command.get("retention_key_id",String.class);
            if(MessageDigest.isEqual(command.get("command_actor",byte[].class),actor(key,actor))) {
                if(row==null||!command.get("id",UUID.class).equals(row.get("id",UUID.class))) fail(Error.CONFLICT);
            }
        }
        String key=row==null?keys.currentId():row.get("retention_key_id",String.class);
        byte[] actorHash=actor(key,actor),payload=payload(key,input),stamp=stamp(key,authority,identity);
        if(row!=null) {
            same(row.get("actor_hash",byte[].class),actorHash); same(row.get("payload_hash",byte[].class),payload);
            same(row.get("authority_hash",byte[].class),stamp);
            LoginProof.unexpired(time(row,"expires_at"),now);
            if(sql.fetchOne("select intent_id from sso_code_intent_details where intent_id=?",row.get("id",UUID.class))==null) fail(Error.UNAVAILABLE);
            alias(sql,row.get("id",UUID.class),actorHash,input.commandId());
            finalAuthority(sql,key,authority,identity,stamp,input);
            LoginProof.unexpired(time(row,"expires_at"),now(sql));
            LoginProof.unexpired(time(authority,"expires_at"),now(sql)); LoginProof.unexpired(time(authority,"enrollment_deadline"),now(sql));
            return snapshot(row);
        }
        LoginProof.fresh(input,now); LoginProof.unexpired(input.expiresAt(),now);
        var health=sql.fetchOne("select swept_at,healthy from sso_code_intent_cleanup where id=1");
        if(health==null||!Boolean.TRUE.equals(health.get("healthy",Boolean.class))||health.get("swept_at")==null
            ||time(health,"swept_at").isBefore(now.minusSeconds(60))) fail(Error.UNAVAILABLE);
        if(count(sql,"sso_code_intents")>=100_000||count(sql,"sso_code_intent_commands")>=100_000) fail(Error.RATE_LIMITED);
        UUID id=UUID.randomUUID();
        PostgresLoginCreationBudget.reserve(sql,id,"INTENT",now,policy.maxHourly());
        sql.execute("""
            insert into sso_code_intents(id,code_digest,code_key_id,retention_key_id,actor_hash,payload_hash,binding_hash,authority_hash,
              issued_at,expires_at,admitted_at,stage_cutoff,retained_until)
            values(?,?,?,?,?,?,?,?,cast(? as timestamptz),cast(? as timestamptz),cast(? as timestamptz),cast(? as timestamptz),cast(? as timestamptz))
            """,id,input.codeDigest(),input.codeKeyId(),key,actorHash,payload,
            keys.hash(key,"armoney-intent-binding-v1",input.issuer(),input.subject(),input.clientId(),input.userSessionId()),stamp,
            input.issuedAt().toString(),input.expiresAt().toString(),now.toString(),input.issuedAt().plusSeconds(30).toString(),input.expiresAt().plusSeconds(604800).toString());
        sql.execute("insert into sso_code_intent_details values(?,?,cast(? as timestamptz),cast(? as timestamptz))",id,input.eventId(),input.passwordAuthenticatedAt().toString(),input.expiresAt().plusSeconds(300).toString());
        alias(sql,id,actorHash,input.commandId());
        finalAuthority(sql,key,authority,identity,stamp,input);
        LoginProof.fresh(input,now(sql)); LoginProof.unexpired(input.expiresAt(),now(sql));
        LoginProof.unexpired(time(authority,"expires_at"),now(sql)); LoginProof.unexpired(time(authority,"enrollment_deadline"),now(sql));
        return new CodeIntent.Snapshot(id,input.codeKeyId(),input.issuedAt(),input.expiresAt());
    }
    private void finalAuthority(DSLContext sql,String key,Record original,UUID identity,byte[] expected,LoginProof.Write input){
        var current=sql.fetchOne("select a.*,e.expires_at as enrollment_deadline from verification_authorities a join verification_enrollments e on e.id=a.enrollment_id where a.enrollment_id=?",original.get("enrollment_id",UUID.class));
        if(current==null||!identity.equals(current.get("mapped_identity_id",UUID.class)))fail(Error.CONFLICT);
        same(expected,stamp(key,current,identity));
        var mapping=sql.fetchOne("select identity_id from external_identities where issuer=? and subject=?",input.issuer(),input.subject());
        if(mapping==null||!identity.equals(mapping.get("identity_id",UUID.class)))fail(Error.CONFLICT);
    }
    private void alias(DSLContext sql,UUID id,byte[] actor,UUID command) {
        var previous=sql.fetchOne("select intent_id from sso_code_intent_commands where actor_hash=? and command_id=?",actor,command);
        if(previous!=null){if(!id.equals(previous.get("intent_id",UUID.class)))fail(Error.CONFLICT);return;}
        long aliases=sql.fetchOne("select count(*) as n from sso_code_intent_commands where intent_id=?",id).get("n",Long.class);
        if(aliases>=4
            ||count(sql,"sso_code_intent_commands")>=100_000)fail(Error.RATE_LIMITED);
        sql.execute("insert into sso_code_intent_commands values(?,?,?,?)",actor,command,id,aliases+1);
    }
    private boolean admit(DSLContext sql,String actor,Instant now) {
        if(!PostgresLoginAdmission.admit(sql,actor,now))return false;
        migrateActors(sql);
        var rows=sql.fetch("select * from sso_code_intent_admission for update");
        if(rows.size()>16)fail(Error.UNAVAILABLE);
        Record found=null;
        for(var row:rows)if(MessageDigest.isEqual(row.get("actor_hash",byte[].class),actor(row.get("retention_key_id",String.class),actor))){found=row;break;}
        String key=found==null?keys.currentId():found.get("retention_key_id",String.class);
        byte[] hash=actor(key,actor);
        BigDecimal credits=BigDecimal.valueOf(20);
        if(found==null){if(rows.size()>=16)return false;sql.execute("insert into sso_code_intent_admission values(?,?,20,cast(? as timestamptz))",hash,key,now.toString());}
        else credits=found.get("credits",BigDecimal.class).add(BigDecimal.valueOf(Math.max(0,Duration.between(time(found,"refilled_at"),now).toNanos()),9).multiply(BigDecimal.valueOf(2))).min(credits);
        boolean allowed=credits.compareTo(BigDecimal.ONE)>=0;
        sql.execute("update sso_code_intent_admission set credits=?,refilled_at=cast(? as timestamptz) where actor_hash=?",allowed?credits.subtract(BigDecimal.ONE):credits,now.toString(),hash);
        return allowed;
    }
    private void migrateActors(DSLContext sql){
        PostgresVerificationProjectionStore.requireLocked(sql);
        var rows=sql.fetch("select * from sso_code_intent_admission order by actor_hash for update");
        if(rows.size()>16)fail(Error.UNAVAILABLE);
        var owners=new HashMap<String,Record>();
        for(var row:rows){
            String original=row.get("retention_key_id",String.class),owner=null;
            for(String configured:policy.writers()){
                if(MessageDigest.isEqual(row.get("actor_hash",byte[].class),actor(original,configured))){
                    if(owner!=null)fail(Error.UNAVAILABLE);
                    owner=configured;
                }
            }
            if(owner==null||owners.putIfAbsent(owner,row)!=null)fail(Error.UNAVAILABLE);
        }
        for(var entry:owners.entrySet()){
            Record row=entry.getValue();
            byte[] updated=actor(keys.currentId(),entry.getKey());
            if(!keys.currentId().equals(row.get("retention_key_id",String.class))){
                // Mutable abuse state only: preserve every credit and original refill instant exactly.
                sql.execute("update sso_code_intent_admission set actor_hash=?,retention_key_id=? where actor_hash=?",updated,keys.currentId(),row.get("actor_hash",byte[].class));
            }
        }
    }
    private byte[] actor(String key,String actor){return keys.hash(key,"armoney-intent-actor-v1",actor);}
    private byte[] payload(String key,LoginProof.Write w){return keys.hash(key,"armoney-intent-payload-v1",w.codeDigest(),w.codeKeyId(),w.issuer(),w.subject(),w.clientId(),w.userSessionId(),w.authenticationSessionId(),w.issuedAt().toString(),w.expiresAt().toString(),w.kind().wire(),w.eventId().toString(),w.passwordAuthenticatedAt().toString());}
    private byte[] stamp(String key,Record a,UUID identity){return keys.hash(key,"armoney-intent-authority-v1",identity.toString(),a.get("enrollment_id").toString(),a.get("state").toString(),a.get("verification_epoch").toString(),a.get("revision").toString(),Objects.toString(a.get("email_candidate_id"),""),Objects.toString(a.get("phone_candidate_id"),""),Objects.toString(a.get("projection_operation_id"),""),a.get("projection_revision").toString(),Objects.toString(a.get("projection_command_id"),""),time(a,"expires_at").toString(),time(a,"enrollment_deadline").toString());}
    private static void same(byte[] a,byte[] b){if(!MessageDigest.isEqual(a,b))fail(Error.CONFLICT);}
    private static CodeIntent.Snapshot snapshot(Record row){return new CodeIntent.Snapshot(row.get("id",UUID.class),row.get("code_key_id",String.class),time(row,"issued_at"),time(row,"expires_at"));}
    private static long count(DSLContext sql,String table){return switch(table){case "sso_code_intents"->sql.fetchOne("select count(*) as n from sso_code_intents").get("n",Long.class);case "sso_code_intent_commands"->sql.fetchOne("select count(*) as n from sso_code_intent_commands").get("n",Long.class);default->throw new IllegalArgumentException();};}
    private static Instant now(DSLContext sql){return time(sql.fetchOne("select clock_timestamp() as now"),"now");}
    private static Instant time(Record r,String field){return r.get(field,OffsetDateTime.class).toInstant();}
    private static void fail(Error error){throw new LoginProof.Failure(error);}
    @Override public int cleanup(){
        int removed=0;
        for(int pass=0;pass<10;pass++)removed+=transaction(true,sql->{
            int deleted=0;
            // Parent locks precede all child locks. 25 parents imply at most 100 command aliases.
            var victims=sql.fetch("""
                select i.id,i.retained_until from sso_code_intents i
                where i.retained_until<clock_timestamp() or exists
                  (select 1 from sso_code_intent_details d where d.intent_id=i.id and d.erase_at<clock_timestamp())
                order by i.retained_until,i.id limit 25 for update of i
                """);
            Instant now=now(sql);
            for(var victim:victims){
                UUID id=victim.get("id",UUID.class);
                sql.fetch("select intent_id from sso_code_intent_commands where intent_id=? order by command_id for update",id);
                sql.fetch("select intent_id from sso_code_intent_details where intent_id=? for update",id);
                deleted+=sql.execute("delete from sso_code_intent_details where intent_id=? and erase_at<cast(? as timestamptz)",id,now.toString());
                if(time(victim,"retained_until").isBefore(now)){
                    sql.execute("delete from sso_code_intent_commands where intent_id=?",id);
                    deleted+=sql.execute("delete from sso_code_intents where id=?",id);
                }
            }
            sql.execute("delete from sso_login_creation_reservations where id in (select id from sso_login_creation_reservations where created_at<clock_timestamp()-interval '65 minutes' order by created_at,id limit 100 for update)");
            boolean capacityAlert=count(sql,"sso_code_intents")>=80_000||count(sql,"sso_code_intent_commands")>=80_000;
            boolean healthy=!sql.fetchExists(sql.selectOne().from("sso_code_intents").where(org.jooq.impl.DSL.condition("retained_until<clock_timestamp()-interval '5 minutes'")))
                &&!sql.fetchExists(sql.selectOne().from("sso_code_intent_details").where(org.jooq.impl.DSL.condition("erase_at<clock_timestamp()-interval '5 minutes'")));
            sql.execute("update sso_code_intent_cleanup set swept_at=clock_timestamp(),healthy=?,capacity_alert=? where id=1",healthy,capacityAlert);
            return deleted;
        });
        return removed;
    }
}
