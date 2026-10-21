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
import java.util.function.Function;
import org.jooq.DSLContext;
import org.jooq.Record;

/** Dormant internal stage storage. No provider observation, confirmation or production route. */
public final class PostgresCodeIntentStore implements CodeIntentStore {
    private final Database database;
    private final LoginProof.Policy policy;
    private final CodeIntentRetentionKeys keys;
    private final CodeIntentDetailCrypto crypto;
    private final PostgresCodeIntentKeyCatalog catalog;
    private final LegacyMigrationManagedProofCrypto migrationCrypto;
    private final com.arman.bank.authservice.application.MigrationUserCurrentPort userCurrent;
    public PostgresCodeIntentStore(Database database, LoginProof.Policy policy, CodeIntentRetentionKeys keys) {
        this(database,policy,keys,null);
    }
    public PostgresCodeIntentStore(Database database,LoginProof.Policy policy,CodeIntentRetentionKeys keys,CodeIntentDetailCrypto crypto){this(database,policy,keys,crypto,null);}
    public PostgresCodeIntentStore(Database database,LoginProof.Policy policy,CodeIntentRetentionKeys keys,CodeIntentDetailCrypto crypto,LegacyMigrationManagedProofCrypto migrationCrypto){this(database,policy,keys,crypto,migrationCrypto,null);}
    public PostgresCodeIntentStore(Database database,LoginProof.Policy policy,CodeIntentRetentionKeys keys,CodeIntentDetailCrypto crypto,LegacyMigrationManagedProofCrypto migrationCrypto,com.arman.bank.authservice.application.MigrationUserCurrentPort userCurrent){
        this.userCurrent=userCurrent;this.migrationCrypto=migrationCrypto;this.database=Objects.requireNonNull(database); this.policy=Objects.requireNonNull(policy); this.keys=Objects.requireNonNull(keys);
        this.crypto=crypto;this.catalog=crypto==null?null:new PostgresCodeIntentKeyCatalog(crypto);
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
    @Override public <T>T inRequest(String actor,Function<CodeIntentStore.Request,T> operation){
        LoginProof.writer(actor,policy);
        return new PostgresLoginRequestScope(database).inRequest(actor,request -> {
            boolean[] consumed={false};
            return operation.apply(input -> {
                request.check();if(consumed[0])fail(Error.UNAVAILABLE);consumed[0]=true;
                return stage(request,input);
            });
        });
    }
    @Override public CodeIntent.Snapshot stage(String actor,LoginProof.Write input){return inRequest(actor,request->request.stage(input));}
    private CodeIntent.Snapshot stage(PostgresLoginRequestScope.Request request,LoginProof.Write input){
        request.check();long monotonicStart=System.nanoTime(); String actor=request.actor();
        CodeIntent.validate(input,actor,policy);
        try {
            if(crypto==null)fail(Error.UNAVAILABLE);
            boolean admitted=request.transaction(sql->admit(sql,actor,now(sql)));
            if(!admitted)fail(Error.RATE_LIMITED);
            var migration=request.transaction(sql->{
                catalog.synchronize(sql,true);
                if(sql.fetchOne("select identity_id from migration_banking_authority_current where issuer=? and subject=?",input.issuer(),input.subject())==null)return null;
                if(userCurrent==null)fail(Error.UNAVAILABLE);
                return new UserAdmission(SourceEligibilitySql.requireCurrent(sql,input.issuer(),input.subject(),migrationCrypto),now(sql));
            });
            var liveUser=migration==null?null:SourceEligibilitySql.observeUser(request,migration.source(),input.commandId(),migration.startedAt(),monotonicStart,userCurrent);
            Outcome preparation=request.transaction(sql->stage(sql,actor,input,null,null,liveUser));
            if(preparation.snapshot()!=null)return preparation.snapshot();
            Prepared prepared=preparation.prepared();
            request.check();
            // Acknowledged nonce COMMIT precedes encryption; this operation still owns the same connection.
            byte[] encrypted=crypto.encrypt(prepared.reservation().keyId(),prepared.reservation().nonce(),input.authenticationSessionId(),aad(prepared,input));
            return request.transaction(sql->stage(sql,actor,input,prepared,encrypted,liveUser)).snapshot();
        } catch(LoginProof.Failure rejected){throw rejected;}
        catch(RuntimeException unavailable){fail(Error.UNAVAILABLE);return null;}
    }
    private record UserAdmission(SourceEligibilitySql.Current source,Instant startedAt){}
    private record Prepared(UUID id,String retentionKey,byte[] binding,byte[] payload,byte[] authority,Instant admittedAt,PostgresCodeIntentKeyCatalog.Reservation reservation){}
    private record Outcome(CodeIntent.Snapshot snapshot,Prepared prepared){}
    private byte[] aad(Prepared p,LoginProof.Write input){return CodeIntentDetailCrypto.aad(p.id(),p.retentionKey(),p.binding(),p.payload(),input.codeKeyId(),input.issuedAt(),input.expiresAt());}
    private Outcome stage(DSLContext sql,String actor,LoginProof.Write input,Prepared prepared,byte[] encrypted,SourceEligibilitySql.UserObservation liveUser) {
        catalog.synchronize(sql,true);
        if(prepared!=null)catalog.fence(sql,prepared.reservation());
        Instant now=now(sql);
        if(sql.fetchOne("select identity_id from migration_banking_authority_current where issuer=? and subject=?",input.issuer(),input.subject())!=null)
            return stageMigration(sql,actor,input,prepared,encrypted,now,liveUser);
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
            var detail=sql.fetchOne("select * from sso_code_intent_details where intent_id=?",row.get("id",UUID.class));
            if(!time(detail,"erase_at").equals(time(row,"expires_at").plusSeconds(300)))fail(Error.UNAVAILABLE);
            catalog.requireActive(sql,detail.get("encryption_key_id",String.class),detail.get("encryption_generation",Long.class));
            String opaque=crypto.decrypt(detail.get("encryption_key_id",String.class),detail.get("nonce",byte[].class),detail.get("ciphertext",byte[].class),aad(row));
            if(!opaque.equals(input.authenticationSessionId()))fail(Error.UNAVAILABLE);
            finalAuthority(sql,key,authority,identity,stamp,input);
            Instant checked=now(sql);
            LoginProof.unexpired(time(row,"expires_at"),checked);
            LoginProof.unexpired(time(authority,"expires_at"),checked);
            LoginProof.unexpired(time(authority,"enrollment_deadline"),checked);
            return new Outcome(snapshot(row),null);
        }
        LoginProof.fresh(input,now); LoginProof.unexpired(input.expiresAt(),now);
        var health=sql.fetchOne("select swept_at,healthy from sso_code_intent_cleanup where id=1");
        if(health==null||!Boolean.TRUE.equals(health.get("healthy",Boolean.class))||health.get("swept_at")==null
            ||time(health,"swept_at").isBefore(now.minusSeconds(60))) fail(Error.UNAVAILABLE);
        if(count(sql,"sso_code_intents")>=100_000||count(sql,"sso_code_intent_commands")>=100_000) fail(Error.RATE_LIMITED);
        if(prepared==null){
            PostgresLoginCreationBudget.requireAvailable(sql,now,policy.maxHourly());
            var reservation=catalog.reserve(sql);
            return new Outcome(null,new Prepared(UUID.randomUUID(),key,
                keys.hash(key,"armoney-intent-binding-v1",input.issuer(),input.subject(),input.clientId(),input.userSessionId()),payload,stamp,now,reservation));
        }
        same(prepared.payload(),payload);same(prepared.authority(),stamp);
        if(!prepared.retentionKey().equals(key))fail(Error.CONFLICT);
        UUID id=prepared.id();
        PostgresLoginCreationBudget.reserve(sql,id,"INTENT",now,policy.maxHourly());
        sql.execute("""
            insert into sso_code_intents(id,code_digest,code_key_id,retention_key_id,actor_hash,payload_hash,binding_hash,authority_hash,
              issued_at,expires_at,admitted_at,stage_cutoff,retained_until)
            values(?,?,?,?,?,?,?,?,cast(? as timestamptz),cast(? as timestamptz),cast(? as timestamptz),cast(? as timestamptz),cast(? as timestamptz))
            """,id,input.codeDigest(),input.codeKeyId(),key,actorHash,payload,
            keys.hash(key,"armoney-intent-binding-v1",input.issuer(),input.subject(),input.clientId(),input.userSessionId()),stamp,
            input.issuedAt().toString(),input.expiresAt().toString(),prepared.admittedAt().toString(),input.issuedAt().plusSeconds(30).toString(),input.expiresAt().plusSeconds(604800).toString());
        sql.execute("insert into sso_code_intent_details values(?,?,cast(? as timestamptz),cast(? as timestamptz),?,?,?,?)",id,input.eventId(),input.passwordAuthenticatedAt().toString(),input.expiresAt().plusSeconds(300).toString(),prepared.reservation().keyId(),prepared.reservation().generation(),prepared.reservation().nonce(),encrypted);
        alias(sql,id,actorHash,input.commandId());
        finalAuthority(sql,key,authority,identity,stamp,input);
        LoginProof.fresh(input,now(sql)); LoginProof.unexpired(input.expiresAt(),now(sql));
        LoginProof.unexpired(time(authority,"expires_at"),now(sql)); LoginProof.unexpired(time(authority,"enrollment_deadline"),now(sql));
        return new Outcome(new CodeIntent.Snapshot(id,input.codeKeyId(),input.issuedAt(),input.expiresAt()),null);
    }
    /** Closed migration source: never represented by a registration authority/enrollment row. */
    private Outcome stageMigration(DSLContext sql,String actor,LoginProof.Write input,Prepared prepared,byte[] encrypted,Instant now,SourceEligibilitySql.UserObservation liveUser){
        if(liveUser==null)fail(Error.UNAVAILABLE);liveUser.require(sql,true,migrationCrypto);
        var source=SourceEligibilitySql.requireCurrent(sql,input.issuer(),input.subject(),migrationCrypto);
        var row=sql.fetchOne("select * from sso_code_intents where code_digest=? for update",input.codeDigest());
        var commands=sql.fetch("select i.*,c.actor_hash as command_actor from sso_code_intent_commands c join sso_code_intents i on i.id=c.intent_id where c.command_id=?",input.commandId());
        if(commands.size()>16)fail(Error.UNAVAILABLE);
        for(var command:commands)if(MessageDigest.isEqual(command.get("command_actor",byte[].class),actor(command.get("retention_key_id",String.class),actor)))
            if(row==null||!command.get("id",UUID.class).equals(row.get("id",UUID.class)))fail(Error.CONFLICT);
        String key=row==null?keys.currentId():row.get("retention_key_id",String.class);
        byte[] actorHash=actor(key,actor),payload=payload(key,input),stamp=migrationStamp(key,"armoney-intent-authority-v1",source);
        if(row!=null){
            same(row.get("actor_hash",byte[].class),actorHash);same(row.get("payload_hash",byte[].class),payload);same(row.get("authority_hash",byte[].class),stamp);
            LoginProof.unexpired(time(row,"expires_at"),now);
            var detail=sql.fetchOne("select * from sso_code_intent_details where intent_id=?",row.get("id",UUID.class));
            if(detail==null||!time(detail,"erase_at").equals(time(row,"expires_at").plusSeconds(300)))fail(Error.UNAVAILABLE);
            alias(sql,row.get("id",UUID.class),actorHash,input.commandId());
            catalog.requireActive(sql,detail.get("encryption_key_id",String.class),detail.get("encryption_generation",Long.class));
            String original=crypto.decrypt(detail.get("encryption_key_id",String.class),detail.get("nonce",byte[].class),detail.get("ciphertext",byte[].class),aad(row));
            if(!original.equals(input.authenticationSessionId()))fail(Error.UNAVAILABLE);
            same(stamp,migrationStamp(key,"armoney-intent-authority-v1",SourceEligibilitySql.requireCurrent(sql,input.issuer(),input.subject(),migrationCrypto)));
            LoginProof.unexpired(time(row,"expires_at"),now(sql));liveUser.require(sql,true,migrationCrypto);return new Outcome(snapshot(row),null);
        }
        LoginProof.fresh(input,now);LoginProof.unexpired(input.expiresAt(),now);
        var health=sql.fetchOne("select swept_at,healthy from sso_code_intent_cleanup where id=1");
        if(health==null||!Boolean.TRUE.equals(health.get("healthy",Boolean.class))||health.get("swept_at")==null||time(health,"swept_at").isBefore(now.minusSeconds(60)))fail(Error.UNAVAILABLE);
        if(count(sql,"sso_code_intents")>=100_000||count(sql,"sso_code_intent_commands")>=100_000)fail(Error.RATE_LIMITED);
        if(prepared==null){
            PostgresLoginCreationBudget.requireAvailable(sql,now,policy.maxHourly());
            var reserved=new Prepared(UUID.randomUUID(),key,keys.hash(key,"armoney-intent-binding-v1",input.issuer(),input.subject(),input.clientId(),input.userSessionId()),payload,stamp,now,catalog.reserve(sql));
            liveUser.require(sql,true,migrationCrypto);return new Outcome(null,reserved);
        }
        same(prepared.payload(),payload);same(prepared.authority(),stamp);if(!prepared.retentionKey().equals(key))fail(Error.CONFLICT);
        UUID id=prepared.id();PostgresLoginCreationBudget.reserve(sql,id,"INTENT",now,policy.maxHourly());
        sql.execute("""
            insert into sso_code_intents(id,code_digest,code_key_id,retention_key_id,actor_hash,payload_hash,binding_hash,authority_hash,issued_at,expires_at,admitted_at,stage_cutoff,retained_until)
            values(?,?,?,?,?,?,?,?,cast(? as timestamptz),cast(? as timestamptz),cast(? as timestamptz),cast(? as timestamptz),cast(? as timestamptz))
            """,id,input.codeDigest(),input.codeKeyId(),key,actorHash,payload,prepared.binding(),stamp,input.issuedAt().toString(),input.expiresAt().toString(),prepared.admittedAt().toString(),input.issuedAt().plusSeconds(30).toString(),input.expiresAt().plusSeconds(604800).toString());
        sql.execute("insert into sso_code_intent_details values(?,?,cast(? as timestamptz),cast(? as timestamptz),?,?,?,?)",id,input.eventId(),input.passwordAuthenticatedAt().toString(),input.expiresAt().plusSeconds(300).toString(),prepared.reservation().keyId(),prepared.reservation().generation(),prepared.reservation().nonce(),encrypted);
        alias(sql,id,actorHash,input.commandId());
        same(stamp,migrationStamp(key,"armoney-intent-authority-v1",SourceEligibilitySql.requireCurrent(sql,input.issuer(),input.subject(),migrationCrypto)));
        LoginProof.fresh(input,now(sql));LoginProof.unexpired(input.expiresAt(),now(sql));liveUser.require(sql,true,migrationCrypto);return new Outcome(new CodeIntent.Snapshot(id,input.codeKeyId(),input.issuedAt(),input.expiresAt()),null);
    }
    private byte[] migrationStamp(String key,String domain,SourceEligibilitySql.Current source){
        var a=source.authority();return keys.hash(key,domain,SourceEligibilitySql.MIGRATION,a.get("decision_id").toString(),a.get("identity_id").toString(),a.get("classification_generation").toString(),a.get("verification_epoch").toString(),a.get("source_revision").toString(),HexFormat.of().formatHex(source.stamp()));
    }
    private byte[] aad(Record row){return CodeIntentDetailCrypto.aad(row.get("id",UUID.class),row.get("retention_key_id",String.class),row.get("binding_hash",byte[].class),row.get("payload_hash",byte[].class),row.get("code_key_id",String.class),time(row,"issued_at"),time(row,"expires_at"));}
    boolean owns(Database candidate){return database==candidate;}
    byte[] confirmationHash(String key,String domain,String... values){return keys.hash(key,domain,values);}
    record ConfirmationContext(Record intent,Record authority,UUID identity,LoginProof.Write write,Instant localDeadline,SourceEligibilitySql.Current migration){
        ConfirmationContext(Record intent,Record authority,UUID identity,LoginProof.Write write,Instant deadline){this(intent,authority,identity,write,deadline,null);}
        boolean isMigration(){return migration!=null;}
        long epoch(){return isMigration()?migration.authority().get("verification_epoch",Long.class):authority.get("verification_epoch",Long.class);}
        long revision(){return isMigration()?migration.authority().get("source_revision",Long.class):authority.get("revision",Long.class);}
        // Stamp-only views do not perform admission or return confirmation capabilities.
        ConfirmationContext(Record intent,Record authority,UUID identity,LoginProof.Write write){this(intent,authority,identity,write,null);}
    }
    /** Controls already held. Catalog precedes every authority/identity/contact/projection/intent lock. */
    ConfirmationContext confirmationContext(DSLContext sql,String actor,LoginProof.Resolve code){
        return confirmationContext(sql,actor,code,null);
    }
    ConfirmationContext confirmationContext(DSLContext sql,String actor,LoginProof.Resolve code,byte[] expectedStamp){
        PostgresVerificationProjectionStore.requireLocked(sql);LoginProof.writer(actor,policy);LoginProof.validate(code,policy);
        if(catalog==null)fail(Error.UNAVAILABLE);catalog.synchronize(sql,true);
        if(sql.fetchOne("select identity_id from migration_banking_authority_current where issuer=? and subject=?",code.issuer(),code.subject())!=null)
            return migrationConfirmationContext(sql,actor,code,expectedStamp);
        var authority=sql.fetchOne("""
            select a.*,e.expires_at as enrollment_deadline,e.state as enrollment_state from verification_authorities a
            join verification_enrollments e on e.id=a.enrollment_id where e.issuer=? and e.subject=? for update of a,e
            """,code.issuer(),code.subject());
        if(authority==null||!"PROJECTED".equals(authority.get("state"))||!"VERIFIED".equals(authority.get("enrollment_state")))fail(Error.CONFLICT);
        UUID identity=authority.get("mapped_identity_id",UUID.class);
        if(identity==null||!identity.equals(authority.get("pending_identity_id",UUID.class))
            ||sql.fetchOne("select id from identities where id=? for update",identity)==null
            ||sql.fetchOne("select identity_id from managed_registration_principals where identity_id=? and enrollment_id=? for update",identity,authority.get("enrollment_id",UUID.class))==null
            ||sql.fetchOne("select identity_id from external_identities where issuer=? and subject=? and identity_id=? for update",code.issuer(),code.subject(),identity)==null)fail(Error.CONFLICT);
        // Compare the admitted stamp before following any possibly replaced contact/projection IDs.
        if(expectedStamp!=null){
            var original=sql.fetchOne("select * from sso_code_intents where code_digest=?",code.codeDigest());
            if(original==null)fail(Error.MISSING);
            same(expectedStamp,confirmationStamp(new ConfirmationContext(original,authority,identity,null)));
        }
        if(sql.fetchOne("select identity_id from banking_eligibility_grants where identity_id=? and verification_epoch=? and enrollment_id=?",identity,authority.get("verification_epoch",Long.class),authority.get("enrollment_id",UUID.class))==null)fail(Error.CONFLICT);
        UUID email=authority.get("email_candidate_id",UUID.class),phone=authority.get("phone_candidate_id",UUID.class);
        sql.fetch("select id from verification_candidates where id in (?,?) order by purpose,id for update",email,phone);
        sql.fetch("""
            select r.id,c.id from verification_receipts r join verification_challenges c on c.id=r.challenge_id
            where r.candidate_id in (?,?) order by r.purpose,r.id for update of r,c
            """,email,phone);
        var command=sql.fetchOne("select * from auth_verification_projection_commands where operation_id=? for update",authority.get("projection_operation_id",UUID.class));
        if(command==null)fail(Error.CONFLICT);
        sql.fetchOne("select operation_id from auth_verification_projection_tombstones where writer_hash=? and operation_id=? for update",command.get("writer_hash",byte[].class),command.get("operation_id",UUID.class));
        var write=reconstructLocked(sql,actor,code);
        var intent=sql.fetchOne("select * from sso_code_intents where code_digest=?",code.codeDigest());
        String key=intent.get("retention_key_id",String.class);
        same(intent.get("authority_hash",byte[].class),stamp(key,authority,identity));
        Instant localDeadline;
        try{localDeadline=new PostgresBankingEligibilityStore(sql).requireLocalForConfirmation(code.binding());}
        catch(com.arman.bank.authservice.domain.Verification.Failure invalid){
            throw new LoginProof.Failure(invalid.error()==com.arman.bank.authservice.domain.Verification.Error.CONFLICT?Error.CONFLICT:Error.UNAVAILABLE);
        }
        LoginProof.unexpired(time(authority,"expires_at"),now(sql));LoginProof.unexpired(time(authority,"enrollment_deadline"),now(sql));
        return new ConfirmationContext(intent,authority,identity,write,localDeadline);
    }
    private ConfirmationContext migrationConfirmationContext(DSLContext sql,String actor,LoginProof.Resolve code,byte[] expectedStamp){
        var source=SourceEligibilitySql.requireCurrent(sql,code.issuer(),code.subject(),migrationCrypto);
        LoginProof.Write write=reconstructLocked(sql,actor,code);
        Record intent=sql.fetchOne("select * from sso_code_intents where code_digest=?",code.codeDigest());
        String key=intent.get("retention_key_id",String.class);
        same(intent.get("authority_hash",byte[].class),migrationStamp(key,"armoney-intent-authority-v1",source));
        var context=new ConfirmationContext(intent,null,source.authority().get("identity_id",UUID.class),write,write.expiresAt(),source);
        if(expectedStamp!=null)same(expectedStamp,confirmationStamp(context));
        LoginProof.unexpired(write.expiresAt(),now(sql));return context;
    }
    void requireCurrentMigration(DSLContext sql,ConfirmationContext original,byte[] expected){
        if(!original.isMigration())fail(Error.UNAVAILABLE);
        var source=SourceEligibilitySql.requireCurrent(sql,original.write().issuer(),original.write().subject(),migrationCrypto);
        var current=new ConfirmationContext(original.intent(),null,source.authority().get("identity_id",UUID.class),original.write(),original.localDeadline(),source);
        same(expected,confirmationStamp(current));LoginProof.unexpired(original.write().expiresAt(),now(sql));
    }
    byte[] confirmationStamp(ConfirmationContext context){
        if(context.isMigration())return migrationStamp(context.intent().get("retention_key_id",String.class),"armoney-confirmation-authority-v1",context.migration());
        var a=context.authority();String key=context.intent().get("retention_key_id",String.class);
        return keys.hash(key,"armoney-confirmation-authority-v1",context.identity().toString(),a.get("enrollment_id").toString(),a.get("state").toString(),a.get("verification_epoch").toString(),a.get("revision").toString(),
            Objects.toString(a.get("email_candidate_id"),""),Objects.toString(a.get("phone_candidate_id"),""),Objects.toString(a.get("projection_operation_id"),""),a.get("projection_revision").toString(),Objects.toString(a.get("projection_command_id"),""),time(a,"expires_at").toString(),time(a,"enrollment_deadline").toString());
    }
    /** Internal caller-owned reconstruction only. It neither confirms authority nor creates a proof. */
    public LoginProof.Write reconstructInTransaction(DSLContext sql,String actor,LoginProof.Resolve trusted){
        PostgresVerificationProjectionStore.requireLocked(sql);LoginProof.writer(actor,policy);LoginProof.validate(trusted,policy);
        if(catalog==null)fail(Error.UNAVAILABLE);
        catalog.synchronize(sql,true);
        return reconstructLocked(sql,actor,trusted);
    }
    private LoginProof.Write reconstructLocked(DSLContext sql,String actor,LoginProof.Resolve trusted){
        var row=sql.fetchOne("select * from sso_code_intents where code_digest=? for update",trusted.codeDigest());
        if(row==null)fail(Error.MISSING);
        String key=row.get("retention_key_id",String.class);
        same(row.get("actor_hash",byte[].class),actor(key,actor));
        same(row.get("binding_hash",byte[].class),keys.hash(key,"armoney-intent-binding-v1",trusted.issuer(),trusted.subject(),trusted.clientId(),trusted.userSessionId()));
        if(!trusted.codeKeyId().equals(row.get("code_key_id",String.class)))fail(Error.CONFLICT);
        LoginProof.unexpired(time(row,"expires_at"),now(sql));
        var detail=sql.fetchOne("select * from sso_code_intent_details where intent_id=? for update",row.get("id",UUID.class));
        var first=sql.fetchOne("select command_id from sso_code_intent_commands where intent_id=? and alias_slot=1",row.get("id",UUID.class));
        if(detail==null||first==null)fail(Error.UNAVAILABLE);
        if(!time(detail,"erase_at").equals(time(row,"expires_at").plusSeconds(300)))fail(Error.UNAVAILABLE);
        catalog.requireActive(sql,detail.get("encryption_key_id",String.class),detail.get("encryption_generation",Long.class));
        String opaque=crypto.decrypt(detail.get("encryption_key_id",String.class),detail.get("nonce",byte[].class),detail.get("ciphertext",byte[].class),aad(row));
        var write=new LoginProof.Write(first.get("command_id",UUID.class),trusted.codeDigest(),trusted.codeKeyId(),trusted.issuer(),trusted.subject(),trusted.clientId(),trusted.userSessionId(),opaque,time(row,"issued_at"),time(row,"expires_at"),LoginProof.Kind.INTERACTIVE,detail.get("event_id",UUID.class),time(detail,"password_authenticated_at"));
        try{LoginProof.validate(write,actor,policy);}catch(LoginProof.Failure malformed){fail(Error.UNAVAILABLE);}
        if(!MessageDigest.isEqual(row.get("payload_hash",byte[].class),payload(key,write)))fail(Error.UNAVAILABLE);
        LoginProof.unexpired(write.expiresAt(),now(sql));return write;
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
    private static long count(DSLContext sql,String table){return switch(table){
        case "sso_code_intents"->sql.fetchOne("select count(*) as n from sso_code_intents").get("n",Long.class);
        case "sso_code_intent_commands"->sql.fetchOne("select count(*) as n from sso_code_intent_commands").get("n",Long.class);
        case "sso_code_confirmation_anchors"->sql.fetchOne("select count(*) as n from sso_code_confirmation_anchors").get("n",Long.class);
        case "sso_code_confirmation_results"->sql.fetchOne("select count(*) as n from sso_code_confirmation_results").get("n",Long.class);
        case "sso_code_confirmation_anchor_completions"->sql.fetchOne("select count(*) as n from sso_code_confirmation_anchor_completions").get("n",Long.class);
        default->throw new IllegalArgumentException();
    };}
    private static Instant now(DSLContext sql){return time(sql.fetchOne("select clock_timestamp() as now"),"now");}
    private static Instant time(Record r,String field){return r.get(field,OffsetDateTime.class).toInstant();}
    private static void fail(Error error){throw new LoginProof.Failure(error);}
    @Override public int cleanup(){
        int removed=0;
        for(int pass=0;pass<10;pass++)removed+=transaction(true,sql->{
            int deleted=0;
            if(catalog!=null)catalog.synchronize(sql,false);
            // Parent locks precede all child locks. 25 parents imply at most 100 command aliases.
            var victims=sql.fetch("""
                select i.id,i.retained_until from sso_code_intents i
                where i.retained_until<clock_timestamp() or exists
                  (select 1 from sso_code_intent_details d where d.intent_id=i.id and d.erase_at<clock_timestamp())
                  or exists(select 1 from sso_code_confirmation_anchors a where a.intent_id=i.id and a.erase_at<clock_timestamp())
                  or exists(select 1 from sso_code_confirmation_results r where r.intent_id=i.id and r.retained_until<clock_timestamp())
                order by i.retained_until,i.id limit 25 for update of i
                """);
            Instant now=now(sql);
            int completionBudget=100,anchorBudget=100,resultBudget=100;
            for(var victim:victims){
                UUID id=victim.get("id",UUID.class);
                sql.fetch("select intent_id from sso_code_intent_commands where intent_id=? order by command_id for update",id);
                sql.fetch("select intent_id from sso_code_intent_details where intent_id=? for update",id);
                sql.fetch("select id from sso_code_confirmation_anchors where intent_id=? order by generation,id for update",id);
                int completions=sql.execute("""
                    delete from sso_code_confirmation_anchor_completions where anchor_id in
                    (select c.anchor_id from sso_code_confirmation_anchor_completions c join sso_code_confirmation_anchors a on a.id=c.anchor_id
                     where a.intent_id=? and a.erase_at<cast(? as timestamptz) order by a.generation,a.id limit ? for update of c)
                    """,id,now.toString(),completionBudget);completionBudget-=completions;deleted+=completions;
                int anchors=sql.execute("""
                    delete from sso_code_confirmation_anchors where id in
                    (select a.id from sso_code_confirmation_anchors a where a.intent_id=? and a.erase_at<cast(? as timestamptz)
                     and not exists(select 1 from sso_code_confirmation_anchor_completions c where c.anchor_id=a.id)
                     order by a.generation,a.id limit ? for update)
                    """,id,now.toString(),anchorBudget);anchorBudget-=anchors;deleted+=anchors;
                int results=sql.execute("""
                    delete from sso_code_confirmation_results where intent_id in
                    (select intent_id from sso_code_confirmation_results where intent_id=? and retained_until<cast(? as timestamptz) limit ? for update)
                    """,id,now.toString(),resultBudget);resultBudget-=results;deleted+=results;
                deleted+=sql.execute("delete from sso_code_intent_details where intent_id=? and erase_at<cast(? as timestamptz)",id,now.toString());
                if(time(victim,"retained_until").isBefore(now)
                    &&sql.fetchOne("select id from sso_code_confirmation_anchors where intent_id=? limit 1",id)==null
                    &&sql.fetchOne("select intent_id from sso_code_confirmation_results where intent_id=?",id)==null){
                    sql.execute("delete from sso_code_intent_commands where intent_id=?",id);
                    deleted+=sql.execute("delete from sso_code_intents where id=?",id);
                }
            }
            sql.execute("delete from sso_login_creation_reservations where id in (select id from sso_login_creation_reservations where created_at<clock_timestamp()-interval '65 minutes' order by created_at,id limit 100 for update)");
            boolean capacityAlert=count(sql,"sso_code_intents")>=80_000||count(sql,"sso_code_intent_commands")>=80_000
                ||count(sql,"sso_code_confirmation_anchors")>=80000||count(sql,"sso_code_confirmation_results")>=80000||count(sql,"sso_code_confirmation_anchor_completions")>=80000;
            boolean healthy=!sql.fetchExists(sql.selectOne().from("sso_code_intents").where(org.jooq.impl.DSL.condition("retained_until<clock_timestamp()-interval '5 minutes'")))
                &&!sql.fetchExists(sql.selectOne().from("sso_code_intent_details").where(org.jooq.impl.DSL.condition("erase_at<clock_timestamp()-interval '5 minutes'")));
            healthy=healthy&&!sql.fetchExists(sql.selectOne().from("sso_code_confirmation_anchors").where(org.jooq.impl.DSL.condition("erase_at<clock_timestamp()-interval '5 minutes'")))
                &&!sql.fetchExists(sql.selectOne().from("sso_code_confirmation_results").where(org.jooq.impl.DSL.condition("retained_until<clock_timestamp()-interval '5 minutes'")));
            if(catalog!=null)catalog.cleanup(sql);
            sql.execute("update sso_code_intent_cleanup set swept_at=clock_timestamp(),healthy=?,capacity_alert=? where id=1",healthy,capacityAlert);
            return deleted;
        });
        return removed;
    }
}
