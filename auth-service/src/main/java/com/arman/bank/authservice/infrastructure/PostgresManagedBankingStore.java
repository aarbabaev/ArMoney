package com.arman.bank.authservice.infrastructure;

import com.arman.bank.authservice.application.*;
import com.arman.bank.authservice.domain.*;
import com.arman.bank.authservice.domain.ManagedBankingAccess.*;
import com.arman.bank.runtime.Database;
import java.time.*;
import java.util.*;
import java.util.function.Function;
import java.security.MessageDigest;
import org.jooq.DSLContext;
import org.jooq.Record;

/** Separate managed persistence. Generic AuthStore marker barriers remain unconditional.
 * Same owned PRIMARY backend and global permit across committed admission, HTTP and final TX. */
public final class PostgresManagedBankingStore implements ManagedBankingStore {
    private final Database database;
    private final ManagedBankingKeys keys;
    private final ManagedBankingService.PairScope pairs;
    private final String actor;
    private final LegacyMigrationManagedProofCrypto migrationCrypto;
    private final MigrationUserCurrentPort userCurrent;
    public PostgresManagedBankingStore(Database database,ManagedBankingKeys keys,ManagedBankingService.PairScope pairs,String actor){this(database,keys,pairs,actor,null);}
    public PostgresManagedBankingStore(Database database,ManagedBankingKeys keys,ManagedBankingService.PairScope pairs,String actor,LegacyMigrationManagedProofCrypto migrationCrypto){this(database,keys,pairs,actor,migrationCrypto,null);}
    public PostgresManagedBankingStore(Database database,ManagedBankingKeys keys,ManagedBankingService.PairScope pairs,String actor,LegacyMigrationManagedProofCrypto migrationCrypto,MigrationUserCurrentPort userCurrent){
        this.userCurrent=userCurrent;this.migrationCrypto=migrationCrypto;this.database=Objects.requireNonNull(database);this.keys=Objects.requireNonNull(keys);this.pairs=Objects.requireNonNull(pairs);
        if(actor==null||!actor.matches("[A-Za-z0-9_-]{1,64}"))throw new IllegalArgumentException("Invalid managed actor");this.actor=actor;
    }
    @Override public <T>T inRequest(Function<Request,T> action){
        return new PostgresLoginRequestScope(database).inRequest(actor,owned->action.apply(new Scoped(owned)));
    }
    private record Proof(Record row,Record event,Instant deadline) {}
    private record Anchor(Scoped owner,UUID id,LoginProof.Binding binding,Instant startedAt,Instant expiresAt,Instant retainedUntil,
                          PostgresBankingEligibilityStore.ManagedLocal local,UUID event,UUID proof,String key,byte[] fingerprint,String sessionDigest,long monotonicStart)
            implements Admission {
        private Anchor { fingerprint=fingerprint==null?null:fingerprint.clone(); }
        @Override public String toString(){return "ManagedAdmission[redacted]";}
    }
    private final class Scoped implements Request {
        private final PostgresLoginRequestScope.Request owned;
        private boolean admitted,completed;private long monotonicStart;
        Scoped(PostgresLoginRequestScope.Request owned){this.owned=owned;}
        private void begin(){owned.check();if(admitted)unavailable();admitted=true;monotonicStart=System.nanoTime();}
        @Override public Admission admitExchange(Token token){
            begin();validateToken(token);
            Anchor anchor=owned.transaction(sql->{
                var binding=token.binding();requirePair(binding);
                var local=local(sql,binding);var proof=proof(sql,binding,token.eventId(),local);
                Instant start=now(sql);
                if(token.issuedAt().isAfter(start)||token.issuedAt().isBefore(time(proof.row(),"issued_at"))
                        ||!Objects.equals(token.email(),local.email())||!Objects.equals(token.phone(),local.phone()))deny();
                if(sql.fetchOne("select event_id from managed_banking_event_consumptions where issuer=? and event_id=? for update",binding.issuer(),token.eventId())!=null)deny();
                Instant expires=min(start.plusSeconds(1800),token.expiresAt(),proof.deadline(),local.deadline());live(start,expires);
                if(sql.fetchOne("select id from managed_banking_operations where issuer=? and event_id=? and purpose='EXCHANGE' for update",binding.issuer(),token.eventId())!=null)unavailable();
                admissionCapacity(sql);
                String key=keys.currentId();byte[] fingerprint=local.isMigration()?keys.fingerprint(key,token,local.migration().authority().get("decision_id",UUID.class),local.migration().authority().get("classification_generation",Long.class),local.epoch(),local.authorityRevision(),proof.row().get("id",UUID.class)):keys.fingerprint(key,token);
                var result=insertAnchor(sql,binding,local,token.eventId(),proof.row().get("id",UUID.class),start,expires,Collections.max(List.of(token.expiresAt(),time(proof.row(),"retained_until"),time(proof.event(),"retained_until"))).plusSeconds(300),key,fingerprint,null);
                recheck(sql,result);return result;
            });
            owned.verify();return anchor;
        }
        @Override public Admission admitValidation(String digest){
            begin();digest(digest);
            Anchor anchor=owned.transaction(sql->{
                // Immutable nonlocking routing read only. Authority locks always precede the session row lock.
                var routing=sql.fetchOne("select * from managed_banking_sessions where token_hash=?",digest);
                if(routing==null)deny();
                var binding=binding(routing);requirePair(binding);
                var local=local(sql,binding);
                var proof=proof(sql,binding,routing.get("event_id",UUID.class),local);
                var session=sql.fetchOne("select * from managed_banking_sessions where token_hash=? for update",digest);
                requireSession(session,binding,local,proof.row().get("id",UUID.class));
                var consumed=sql.fetchOne("select * from managed_banking_event_consumptions where issuer=? and event_id=? for update",binding.issuer(),session.get("event_id",UUID.class));
                if(consumed==null)unavailable();String key=consumed.get("key_id",String.class);keys.require(key);
                Instant start=now(sql),expires=min(time(session,"expires_at"),proof.deadline(),local.deadline());live(start,expires);
                // Admission capacity is bounded; once admitted a valid negative is exempt from positive caps.
                admissionCapacity(sql);
                var result=insertAnchor(sql,binding,local,session.get("event_id",UUID.class),proof.row().get("id",UUID.class),start,expires,time(consumed,"retained_until"),key,null,digest);
                recheck(sql,result);return result;
            });
            owned.verify();return anchor;
        }
        private Anchor insertAnchor(DSLContext sql,LoginProof.Binding binding,PostgresBankingEligibilityStore.ManagedLocal local,
                                    UUID event,UUID proof,Instant start,Instant expiry,Instant retention,String key,byte[] fingerprint,String sessionDigest){
            if(local.isMigration()&&userCurrent==null)unavailable();
            UUID id=UUID.randomUUID();if(retention.isBefore(expiry.plusSeconds(300)))unavailable();
            if(local.isMigration())insertMigrationOperation(sql,id,binding,local,event,proof,start,expiry,retention,sessionDigest);
            else sql.execute("""
                insert into managed_banking_operations(id,purpose,issuer,subject,client_id,user_session_id,identity_id,event_id,proof_id,authority_stamp,started_at,expires_at,retained_until)
                values(?,?,?,?,?,?,?,?,?,?,cast(? as timestamptz),cast(? as timestamptz),cast(? as timestamptz))
                """,id,sessionDigest==null?"EXCHANGE":"VALIDATE",binding.issuer(),binding.subject(),binding.clientId(),binding.userSessionId(),local.identity(),event,proof,local.stamp(),start.toString(),expiry.toString(),retention.toString());
            return new Anchor(this,id,binding,start,expiry,retention,local,event,proof,key,fingerprint,sessionDigest,monotonicStart);
        }
        @Override public Decision exchange(Admission supplied,CodeConfirmation.Facts facts,String digest){
            digest(digest);return complete(supplied,facts,digest);
        }
        @Override public Decision validate(Admission supplied,CodeConfirmation.Facts facts){return complete(supplied,facts,null);}
        private Decision complete(Admission supplied,CodeConfirmation.Facts facts,String newDigest){
            owned.verify();if(completed)unavailable();completed=true;
            if(!(supplied instanceof Anchor anchor)||anchor.owner()!=this)throw failure(LoginProof.Error.UNAVAILABLE);
            if((newDigest==null)!=(anchor.sessionDigest()!=null))unavailable();
            Instant received=owned.transaction(PostgresManagedBankingStore::now);
            Instant deadline=CodeConfirmation.deadline(anchor.binding(),facts,anchor.startedAt(),received);
            var liveUser=anchor.local().isMigration()?SourceEligibilitySql.observeUser(owned,anchor.local().migration(),anchor.id(),anchor.startedAt(),anchor.monotonicStart(),userCurrent):null;
            Decision result=owned.transaction(sql->{
                if(liveUser!=null)liveUser.require(sql,true,migrationCrypto);
                recheck(sql,anchor);
                var operation=sql.fetchOne("select * from managed_banking_operations where id=? for update",anchor.id());
                if(operation==null||!time(operation,"started_at").equals(anchor.startedAt())
                        ||!MessageDigest.isEqual(operation.get("authority_stamp",byte[].class),anchor.local().stamp())
                        ||sql.fetchOne("select operation_id from managed_banking_completions where operation_id=?",anchor.id())!=null)unavailable();
                requireSource(operation,anchor.local());
                CodeConfirmation.fresh(anchor.startedAt(),deadline,now(sql));
                boolean negative=!facts.enabled()||!facts.userSessionActive()||!facts.clientSessionActive()
                    ||!Objects.equals(facts.username(),anchor.local().phone())||!Objects.equals(facts.phone(),anchor.local().phone())
                    ||!Objects.equals(facts.email(),anchor.local().email());
                if(negative){
                    if(anchor.local().isMigration())SourceEligibilitySql.suspend(sql,anchor.local().migration(),"PROVIDER_NEGATIVE",migrationCrypto);
                    else new PostgresBankingEligibilityStore(sql).invalidateAfterMutation(anchor.local().enrollment());
                    // Own suspension is the only permitted authority transition; compare every other stamp field.
                    requireSuspended(sql,anchor);
                    sql.execute("insert into managed_banking_completions values(?,cast(? as timestamptz),'DENIED')",anchor.id(),now(sql).toString());
                    finalTime(sql,anchor,deadline);if(liveUser!=null)liveUser.require(sql,false,migrationCrypto);return Decision.denied();
                }
                if(newDigest!=null){
                    if(sql.fetchOne("select event_id from managed_banking_event_consumptions where issuer=? and event_id=? for update",anchor.binding().issuer(),anchor.event())!=null)deny();
                    positiveCapacity(sql);
                    var proof=proof(sql,anchor.binding(),anchor.event(),anchor.local());
                    Instant retained=anchor.retainedUntil();
                    sql.execute("""
                        insert into managed_banking_event_consumptions(issuer,event_id,identity_id,operation_id,key_id,fingerprint,retained_until)
                        values(?,?,?,?,?,?,cast(? as timestamptz))
                        """,anchor.binding().issuer(),anchor.event(),anchor.local().identity(),anchor.id(),anchor.key(),anchor.fingerprint(),retained.toString());
                    // A trigger/lock during consumption may have crossed a local bound. Classify
                    // expiry before the next insert, while retaining the final all-writes check.
                    recheck(sql,anchor);finalTime(sql,anchor,deadline);
                    if(anchor.local().isMigration())insertMigrationSession(sql,newDigest,anchor);
                    else sql.execute("""
                        insert into managed_banking_sessions(token_hash,identity_id,issuer,event_id,subject,client_id,user_session_id,verification_epoch,email_contact_version,phone_contact_version,projection_revision,proof_id,created_at,expires_at)
                        values(?,?,?,?,?,?,?,?,?,?,?,?,cast(? as timestamptz),cast(? as timestamptz))
                        """,newDigest,anchor.local().identity(),anchor.binding().issuer(),anchor.event(),anchor.binding().subject(),anchor.binding().clientId(),anchor.binding().userSessionId(),
                        anchor.local().epoch(),anchor.local().emailVersion(),anchor.local().phoneVersion(),anchor.local().projectionRevision(),anchor.proof(),now(sql).toString(),anchor.expiresAt().toString());
                }
                sql.execute("insert into managed_banking_completions values(?,cast(? as timestamptz),'ACCEPTED')",anchor.id(),now(sql).toString());
                recheck(sql,anchor);finalTime(sql,anchor,deadline);
                if(liveUser!=null)liveUser.require(sql,true,migrationCrypto);
                return new Decision(anchor.local().identity(),anchor.expiresAt(),false);
            });
            owned.verify();return result;
        }
    }
    private static void requireSource(Record row,PostgresBankingEligibilityStore.ManagedLocal local){
        if(!local.isMigration()){if(!"REGISTRATION_DUAL_CONTACT".equals(row.get("eligibility_source")))deny();return;}
        var a=local.migration().authority();
        if(!SourceEligibilitySql.MIGRATION.equals(row.get("eligibility_source")))deny();
        for(String field:List.of("classification_generation","source_revision"))if(!Objects.equals(a.get(field),row.get(field)))deny();
        if(!Objects.equals(a.get("decision_id"),row.get("eligibility_source_id"))||!Objects.equals(a.get("decision_id"),row.get("migration_decision_id"))
            ||!Objects.equals(a.get("email_receipt_generation"),row.get("migration_email_receipt_generation"))||!Objects.equals(a.get("phone_receipt_generation"),row.get("migration_phone_receipt_generation"))
            ||!Objects.equals(a.get("verified_contact_generation"),row.get("migration_verified_contact_generation")))deny();
        byte[] stamp=row.get("source_stamp",byte[].class);if(stamp==null||!MessageDigest.isEqual(stamp,local.stamp()))deny();
    }
    private static void insertMigrationOperation(DSLContext sql,UUID id,LoginProof.Binding binding,PostgresBankingEligibilityStore.ManagedLocal local,UUID event,UUID proof,Instant start,Instant expiry,Instant retention,String sessionDigest){
        var a=local.migration().authority();
        sql.execute("""
            insert into managed_banking_operations(id,purpose,issuer,subject,client_id,user_session_id,identity_id,event_id,proof_id,authority_stamp,started_at,expires_at,retained_until,
              eligibility_source,eligibility_source_id,classification_generation,source_revision,source_stamp,migration_decision_id,migration_email_receipt_generation,migration_phone_receipt_generation,migration_verified_contact_generation)
            values(?,?,?,?,?,?,?,?,?,?,cast(? as timestamptz),cast(? as timestamptz),cast(? as timestamptz),?,?,?,?,?,?,?,?,?)
            """,id,sessionDigest==null?"EXCHANGE":"VALIDATE",binding.issuer(),binding.subject(),binding.clientId(),binding.userSessionId(),local.identity(),event,proof,local.stamp(),start.toString(),expiry.toString(),retention.toString(),
            SourceEligibilitySql.MIGRATION,a.get("decision_id"),a.get("classification_generation"),a.get("source_revision"),local.stamp(),a.get("decision_id"),a.get("email_receipt_generation"),a.get("phone_receipt_generation"),a.get("verified_contact_generation"));
    }
    private static void insertMigrationSession(DSLContext sql,String digest,Anchor anchor){
        var local=anchor.local();var a=local.migration().authority();
        sql.execute("""
            insert into managed_banking_sessions(token_hash,identity_id,issuer,event_id,subject,client_id,user_session_id,verification_epoch,email_contact_version,phone_contact_version,projection_revision,proof_id,created_at,expires_at,
              eligibility_source,eligibility_source_id,classification_generation,source_revision,source_stamp,migration_decision_id,migration_email_receipt_generation,migration_phone_receipt_generation,migration_verified_contact_generation)
            values(?,?,?,?,?,?,?,?,null,null,null,?,cast(? as timestamptz),cast(? as timestamptz),?,?,?,?,?,?,?,?,?)
            """,digest,local.identity(),anchor.binding().issuer(),anchor.event(),anchor.binding().subject(),anchor.binding().clientId(),anchor.binding().userSessionId(),local.epoch(),anchor.proof(),now(sql).toString(),anchor.expiresAt().toString(),
            SourceEligibilitySql.MIGRATION,a.get("decision_id"),a.get("classification_generation"),a.get("source_revision"),local.stamp(),a.get("decision_id"),a.get("email_receipt_generation"),a.get("phone_receipt_generation"),a.get("verified_contact_generation"));
    }
    private PostgresBankingEligibilityStore.ManagedLocal local(DSLContext sql,LoginProof.Binding binding){
        try{return new PostgresBankingEligibilityStore(sql,migrationCrypto).requireManagedLocal(binding.issuer(),binding.subject());}
        catch(Verification.Failure error){throw failure(error.error()==Verification.Error.CONFLICT?LoginProof.Error.CONFLICT:LoginProof.Error.UNAVAILABLE);}
    }
    private Proof proof(DSLContext sql,LoginProof.Binding binding,UUID event,PostgresBankingEligibilityStore.ManagedLocal local){
        var rows=sql.fetch("""
            select p.* from sso_login_proofs p join sso_code_confirmation_results r on r.proof_id=p.id
            join sso_code_intents i on i.id=r.intent_id
            where p.issuer=? and p.event_id=? and p.kind='interactive'
              and r.budget_origin_id=i.id and r.payload_hash=i.payload_hash and r.retention_key_id=i.retention_key_id
              and p.code_digest=i.code_digest and p.code_key_id=i.code_key_id and p.expires_at=r.expires_at
            for update of p,r,i
            """,binding.issuer(),event);
        if(rows.size()!=1)deny();var row=rows.get(0);
        var persisted=sql.fetchOne("select * from sso_login_events where issuer=? and event_id=? for update",binding.issuer(),event);
        if(persisted==null||!MessageDigest.isEqual(row.get("binding_hash",byte[].class),LoginProof.bindingHash(binding))
                ||!MessageDigest.isEqual(persisted.get("binding_hash",byte[].class),LoginProof.bindingHash(binding))
                ||!MessageDigest.isEqual(persisted.get("identity_digest",byte[].class),LoginProof.identityHash(local.identity()))
                ||!local.identity().equals(persisted.get("identity_id",UUID.class))
                ||persisted.get("verification_epoch",Long.class)!=local.epoch()
                ||!Objects.equals(persisted.get("email_contact_version",Integer.class),local.emailVersion())
                ||!Objects.equals(persisted.get("phone_contact_version",Integer.class),local.phoneVersion())
                ||!Objects.equals(persisted.get("projection_revision",Integer.class),local.projectionRevision())
                ||!Objects.equals(persisted.get("verified_email"),local.email()))deny();
        requireSource(persisted,local);
        var confirmed=sql.fetchOne("select r.* from sso_code_confirmation_results r where r.proof_id=?",row.get("id",UUID.class));
        if(confirmed==null)deny();requireSource(confirmed,local);
        Instant deadline=min(time(row,"expires_at"),time(persisted,"recipient_expires_at"));live(now(sql),deadline);
        return new Proof(row,persisted,deadline);
    }
    private void recheck(DSLContext sql,Anchor anchor){
        requirePair(anchor.binding());keys.require(anchor.key());
        var current=local(sql,anchor.binding());
        if(!MessageDigest.isEqual(current.stamp(),anchor.local().stamp())||current.epoch()!=anchor.local().epoch())deny();
        var proof=proof(sql,anchor.binding(),anchor.event(),current);
        if(!anchor.proof().equals(proof.row().get("id",UUID.class)))deny();
        if(anchor.sessionDigest()!=null)requireSession(sql.fetchOne("select * from managed_banking_sessions where token_hash=? for update",anchor.sessionDigest()),anchor.binding(),current,anchor.proof());
        live(now(sql),min(anchor.expiresAt(),anchor.local().deadline(),current.deadline(),proof.deadline()));
    }
    private static void requireSession(Record row,LoginProof.Binding binding,PostgresBankingEligibilityStore.ManagedLocal local,UUID proof){
        if(row==null||!binding.equals(binding(row))||!local.identity().equals(row.get("identity_id",UUID.class))||!proof.equals(row.get("proof_id",UUID.class))
                ||row.get("verification_epoch",Long.class)!=local.epoch()||!Objects.equals(row.get("email_contact_version",Integer.class),local.emailVersion())
                ||!Objects.equals(row.get("phone_contact_version",Integer.class),local.phoneVersion())||!Objects.equals(row.get("projection_revision",Integer.class),local.projectionRevision()))deny();
        requireSource(row,local);
    }
    private static void requireSuspended(DSLContext sql,Anchor anchor){
        try{new PostgresBankingEligibilityStore(sql).requireManagedOwnSuspension(anchor.local(),anchor.binding().issuer(),anchor.binding().subject());}
        catch(Verification.Failure invalid){throw failure(LoginProof.Error.UNAVAILABLE);}
    }
    private static void finalTime(DSLContext sql,Anchor anchor,Instant deadline){
        Instant checked=now(sql);CodeConfirmation.fresh(anchor.startedAt(),deadline,checked);live(checked,min(anchor.expiresAt(),anchor.local().deadline()));
    }
    private static void admissionCapacity(DSLContext sql){
        if(sql.fetchOne("select count(*) from managed_banking_operations").get(0,Long.class)>=100000)unavailable();
    }
    private static void positiveCapacity(DSLContext sql){
        var health=sql.fetchOne("select swept_at from managed_banking_cleanup where id=1");
        if(health==null||time(health,"swept_at").isBefore(now(sql).minusSeconds(60)))unavailable();
        if(sql.fetchOne("select count(*) from managed_banking_operations").get(0,Long.class)>=100000
            ||sql.fetchOne("select count(*) from managed_banking_event_consumptions").get(0,Long.class)>=100000)unavailable();
    }
    @Override public void revoke(String digest){
        digest(digest);new PostgresLoginRequestScope(database).inRequest(actor,owned->owned.transaction(sql->sql.execute("delete from managed_banking_sessions where token_hash=?",digest)));
    }
    /** Explicit lifecycle owner schedules this bounded pass. Never delete live or referenced replay protection. */
    public void cleanup(){
        new PostgresLoginRequestScope(database).inRequest(actor,owned->owned.transaction(sql->{
            sql.execute("delete from managed_banking_sessions where token_hash in(select token_hash from managed_banking_sessions where expires_at<clock_timestamp() order by expires_at limit 100)");
            sql.execute("delete from managed_banking_event_consumptions c where (issuer,event_id) in(select issuer,event_id from managed_banking_event_consumptions x where retained_until<clock_timestamp() and not exists(select 1 from managed_banking_sessions s where s.issuer=x.issuer and s.event_id=x.event_id) order by retained_until limit 100)");
            sql.execute("delete from managed_banking_completions c where operation_id in(select o.id from managed_banking_operations o where o.retained_until<clock_timestamp() and not exists(select 1 from managed_banking_event_consumptions e where e.operation_id=o.id) order by o.retained_until limit 100)");
            sql.execute("delete from managed_banking_operations o where id in(select x.id from managed_banking_operations x where retained_until<clock_timestamp() and not exists(select 1 from managed_banking_completions c where c.operation_id=x.id) and not exists(select 1 from managed_banking_event_consumptions e where e.operation_id=x.id) order by retained_until limit 100)");
            sql.execute("insert into managed_banking_cleanup values(1,clock_timestamp()) on conflict(id) do update set swept_at=excluded.swept_at");return null;
        }));
    }
    private void requirePair(LoginProof.Binding binding){if(!pairs.managed(binding.issuer(),binding.clientId()))deny();}
    private static void validateToken(Token token){
        if(token==null||token.binding()==null||token.eventId()==null||token.issuedAt()==null||token.expiresAt()==null||token.bearer()==null
                ||token.bearer().length()>8192||!token.bearer().matches("[A-Za-z0-9._~+/-]+=*")||!token.issuedAt().isBefore(token.expiresAt()))deny();
    }
    private static LoginProof.Binding binding(Record row){return new LoginProof.Binding(row.get("issuer",String.class),row.get("subject",String.class),row.get("client_id",String.class),row.get("user_session_id",String.class));}
    private static void digest(String value){if(value==null||!value.matches("[0-9a-f]{64}"))deny();}
    private static Instant now(DSLContext sql){return sql.fetchOne("select clock_timestamp()").get(0,OffsetDateTime.class).toInstant();}
    private static Instant time(Record row,String field){return row.get(field,OffsetDateTime.class).toInstant();}
    private static Instant min(Instant... values){return Collections.min(Arrays.asList(values));}
    private static void live(Instant now,Instant end){if(!now.isBefore(end))unavailable();}
    private static LoginProof.Failure failure(LoginProof.Error error){return new LoginProof.Failure(error);}
    private static void unavailable(){throw failure(LoginProof.Error.UNAVAILABLE);}
    private static void deny(){throw failure(LoginProof.Error.CONFLICT);}
}
