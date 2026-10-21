package com.arman.bank.authservice.infrastructure;

import com.arman.bank.authservice.application.LegacyMigrationBrowserService.Action;
import com.arman.bank.authservice.application.LegacyMigrationBrowserService.ActionForm;
import com.arman.bank.authservice.application.LegacyMigrationBrowserService.ActionBundle;
import com.arman.bank.authservice.application.LegacyMigrationBrowserService.ReadOnlyStatus;
import com.arman.bank.authservice.application.LegacyMigrationBrowserService.Status;
import com.arman.bank.authservice.application.LegacyMigrationBrowserService.VerificationPage;
import com.arman.bank.authservice.application.LegacyMigrationBrowserService.Kind;
import com.arman.bank.authservice.application.LegacyMigrationBrowserService.Failure;
import com.arman.bank.authservice.domain.LegacyMigrationBrowserContinuation;
import com.arman.bank.authservice.domain.LegacyMigrationBrowserContinuation.*;
import org.jooq.DSLContext;
import org.jooq.Record;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;

/** Immutable browser orchestration journal; never migration, receipt or banking authority. */
final class PostgresLegacyMigrationBrowserCommandStore {
    private static final String TOKEN="armoney-legacy-migration-browser-continuation-token-v1",COMMAND="armoney-legacy-migration-browser-continuation-command-v2";
    private final LegacyMigrationManagedProofCrypto crypto;
    private final LegacyMigrationCrypto accountCrypto;
    private final String actor;
    PostgresLegacyMigrationBrowserCommandStore(LegacyMigrationManagedProofCrypto crypto,LegacyMigrationCrypto accountCrypto,String actor){
        this.crypto=Objects.requireNonNull(crypto);this.accountCrypto=Objects.requireNonNull(accountCrypto);if(actor==null||!actor.matches("[A-Za-z0-9_-]{1,64}"))throw denied(Kind.UNAVAILABLE);this.actor=actor;
    }
    record Command(UUID migrationId,Form form,UUID completedBundle){@Override public String toString(){return "MigrationBrowserCommand[redacted]";}}
    private void owned(PostgresLoginRequestScope.Request owned){if(owned==null)throw denied(Kind.UNAVAILABLE);owned.check();if(!actor.equals(owned.actor()))throw denied(Kind.UNAVAILABLE);}
    VerificationPage begin(PostgresLoginRequestScope.Request owned,UUID migration,String cookie){
        owned(owned);return owned.transaction(sql->{
            Record intent=intent(sql,migration,cookie);Record existing=sql.fetchOne("select * from legacy_migration_browser_continuations where migration_id=?",migration);
            if(existing!=null){authenticate(existing,cookie);return latest(sql,existing);}
            live(sql,time(intent,"authorization_deadline"));
            int slots=sql.fetchOne("select reserved_slots from legacy_migration_browser_capacity where singleton=true for update").get(0,Integer.class);
            if(slots>LegacyMigrationBrowserContinuation.GLOBAL_SLOTS-LegacyMigrationBrowserContinuation.FLOW_SLOTS)throw denied(Kind.RATE_LIMITED);
            String key=crypto.keyId();sql.execute("update legacy_migration_browser_capacity set reserved_slots=reserved_slots+32 where singleton=true");
            sql.execute("insert into legacy_migration_browser_continuations values(?,?,?,?,?,cast(? as timestamptz),cast(? as timestamptz),32,clock_timestamp())",migration,intent.get("identity_id",UUID.class),actor,key,cookieHash(key,cookie),time(intent,"authorization_deadline").toString(),time(intent,"created_at").plusSeconds(900).toString());
            Record flow=flow(sql,migration,cookie);Form initial=new Form(UUID.randomUUID(),Purpose.STAGE,null,null,0,null);insertForm(sql,flow,null,initial);return createBundle(sql,flow,List.of(initial),null);
        });
    }
    Command inspect(PostgresLoginRequestScope.Request owned,String cookie,String token,UUID command,Set<Purpose> permitted){
        owned(owned);return owned.transaction(sql->{Record row=command(sql,command);Record flow=flow(sql,row.get("migration_id",UUID.class),cookie);validateToken(flow,row,token);if(!permitted.contains(Purpose.valueOf(row.get("purpose",String.class))))throw denied(Kind.UNAUTHORIZED);Record result=sql.fetchOne("select bundle_id from legacy_migration_browser_results where command_id=?",command);return new Command(flow.get("migration_id",UUID.class),form(row),result==null?null:result.get("bundle_id",UUID.class));});
    }
    VerificationPage replay(PostgresLoginRequestScope.Request owned,String cookie,Command command){owned(owned);return owned.transaction(sql->{Record flow=flow(sql,command.migrationId(),cookie);Record result=sql.fetchOne("select bundle_id from legacy_migration_browser_results where command_id=?",command.form().commandId());if(result==null)throw denied(Kind.UNAVAILABLE);return bundle(sql,flow,result.get("bundle_id",UUID.class));});}
    boolean claim(PostgresLoginRequestScope.Request owned,String cookie,String token,Command command,String otpKey,String commitment){
        owned(owned);return owned.transaction(sql->{Record flow=flow(sql,command.migrationId(),cookie);Record row=command(sql,command.form().commandId());validateToken(flow,row,token);byte[] payload=payload(flow,row,otpKey,commitment);
            Record old=sql.fetchOne("select * from legacy_migration_browser_claims where command_id=?",row.get("command_id"));
            if(old!=null){if(!MessageDigest.isEqual(payload,old.get("payload_hash",byte[].class)))throw denied(Kind.CONFLICT);if(sql.fetchOne("select command_id from legacy_migration_browser_results where command_id=?",row.get("command_id"))!=null)return false;live(sql,time(flow,"account_deadline"));return true;}
            live(sql,time(flow,"account_deadline"));
            Purpose purpose=command.form().purpose();
            if((purpose==Purpose.EMAIL_ISSUE||purpose==Purpose.PHONE_ISSUE)&&sql.fetchOne("select id from legacy_migration_otp_receipts where migration_id=? and purpose=?",command.migrationId(),purpose==Purpose.EMAIL_ISSUE?"EMAIL":"PHONE")!=null)throw denied(Kind.CONFLICT);
            Record current=sql.fetchOne("select bundle_id from legacy_migration_browser_bundles where migration_id=? order by sequence desc limit 1",command.migrationId());
            if(current==null||sql.fetchOne("select command_id from legacy_migration_browser_bundle_forms where bundle_id=? and command_id=?",current.get("bundle_id"),row.get("command_id"))==null)throw denied(Kind.CONFLICT);
            if(row.get("eligible_at")!=null&&now(sql).isBefore(time(row,"eligible_at")))throw denied(Kind.RATE_LIMITED);
            int used=sql.fetchOne("select count(*) from legacy_migration_browser_commands where migration_id=?",command.migrationId()).get(0,Long.class).intValue();
            int required=command.form().purpose()==Purpose.EMAIL_ISSUE||command.form().purpose()==Purpose.PHONE_ISSUE?2:1;
            if(used>32-required)throw denied(Kind.RATE_LIMITED);
            if(sql.fetchOne("select c.command_id from legacy_migration_browser_claims c join legacy_migration_browser_commands k using(command_id) left join legacy_migration_browser_results r using(command_id) where k.migration_id=? and r.command_id is null",command.migrationId())!=null)throw denied(Kind.UNAVAILABLE);
            sql.execute("insert into legacy_migration_browser_claims values(?,?,?,clock_timestamp())",row.get("command_id"),otpKey,payload);return true;
        });
    }
    VerificationPage complete(PostgresLoginRequestScope.Request owned,String cookie,Command command,List<Form> next,Status status){
        owned(owned);LegacyMigrationBrowserContinuation.bundle(next);return owned.transaction(sql->{Record flow=flow(sql,command.migrationId(),cookie);Record prior=sql.fetchOne("select bundle_id from legacy_migration_browser_results where command_id=?",command.form().commandId());if(prior!=null)return bundle(sql,flow,prior.get("bundle_id",UUID.class));
            if(sql.fetchOne("select command_id from legacy_migration_browser_claims where command_id=?",command.form().commandId())==null)throw denied(Kind.UNAVAILABLE);
            for(Form f:next){Record existing=sql.fetchOne("select * from legacy_migration_browser_commands where command_id=?",f.commandId());if(existing==null)insertForm(sql,flow,command.form().commandId(),f);else if(!flow.get("migration_id").equals(existing.get("migration_id"))||!form(existing).equals(f))throw denied(Kind.CONFLICT);}
            VerificationPage page=createBundle(sql,flow,next,status);UUID bundleId=sql.fetchOne("select bundle_id from legacy_migration_browser_bundles where migration_id=? order by sequence desc limit 1",command.migrationId()).get(0,UUID.class);
            sql.execute("insert into legacy_migration_browser_results values(?,?,clock_timestamp())",command.form().commandId(),bundleId);return page;
        });
    }
    <T>T withExecution(PostgresLoginRequestScope.Request owned,String cookie,Command command,Supplier<T> work){
        owned(owned);UUID fence=UUID.randomUUID();
        owned.transaction(sql->{
            flow(sql,command.migrationId(),cookie);
            Record prior=sql.fetchOne("select * from legacy_migration_browser_executions where command_id=? for update",command.form().commandId());
            Record backend=sql.fetchOne("select a.pid,a.backend_start,l.objid::bigint as slot from pg_stat_activity a join pg_locks l on l.pid=a.pid where a.pid=pg_backend_pid() and l.locktype='advisory' and l.database=(select oid from pg_database where datname=current_database()) and l.classid=cast(? as oid) and l.objsubid=2 and l.granted and l.mode='ExclusiveLock'",0x41524D4F);
            boolean reusedBorrow=prior!=null&&backend!=null&&Objects.equals(prior.get("backend_pid"),backend.get("pid"))&&time(prior,"backend_start").equals(time(backend,"backend_start"))&&prior.get("permit_slot",Integer.class).longValue()==backend.get("slot",Long.class)&&!owned.requestId().equals(prior.get("request_id",UUID.class));
            if(prior!=null&&Boolean.TRUE.equals(prior.get("active"))&&!reusedBorrow&&sql.fetchOne("select 1 from pg_stat_activity a join pg_locks l on l.pid=a.pid where a.pid=? and a.backend_start=cast(? as timestamptz) and l.locktype='advisory' and l.database=(select oid from pg_database where datname=current_database()) and l.classid=cast(? as oid) and l.objid=cast(? as oid) and l.objsubid=2 and l.granted and l.mode='ExclusiveLock'",prior.get("backend_pid"),time(prior,"backend_start").toString(),0x41524D4F,prior.get("permit_slot"))!=null)throw denied(Kind.UNAVAILABLE);
            if(backend==null)throw denied(Kind.UNAVAILABLE);
            sql.execute("insert into legacy_migration_browser_executions values(?,?,?,?,cast(? as timestamptz),?,true) on conflict(command_id) do update set fence=excluded.fence,request_id=excluded.request_id,backend_pid=excluded.backend_pid,backend_start=excluded.backend_start,permit_slot=excluded.permit_slot,active=true",command.form().commandId(),fence,owned.requestId(),backend.get("pid",Integer.class),time(backend,"backend_start").toString(),backend.get("slot",Long.class).intValue());return null;
        });
        Throwable primary=null;
        try{owned.verify();return work.get();}
        catch(RuntimeException|Error failure){primary=failure;throw failure;}
        finally{
            try{owned.transaction(sql->{if(sql.execute("update legacy_migration_browser_executions set active=false where command_id=? and fence=? and backend_pid=pg_backend_pid()",command.form().commandId(),fence)!=1)throw denied(Kind.UNAVAILABLE);return null;});owned.verify();}
            catch(RuntimeException|Error release){if(primary==null)throw release;primary.addSuppressed(release);}
        }
    }
    Form issueSibling(PostgresLoginRequestScope.Request owned,String cookie,Command command){owned(owned);return owned.transaction(sql->{Record flow=flow(sql,command.migrationId(),cookie);Record b=sql.fetchOne("select bundle_id from legacy_migration_browser_bundles where migration_id=? order by sequence desc limit 1",command.migrationId());if(b==null)return null;String p=command.form().purpose()==Purpose.EMAIL_CONSUME?"EMAIL_ISSUE":"PHONE_ISSUE";Record r=sql.fetchOne("select k.* from legacy_migration_browser_commands k join legacy_migration_browser_bundle_forms f using(command_id) where f.bundle_id=? and k.purpose=?",b.get("bundle_id"),p);return r==null?null:form(r);});}
    Form retryConsume(PostgresLoginRequestScope.Request owned,String cookie,Command command){owned(owned);return owned.transaction(sql->{flow(sql,command.migrationId(),cookie);Record r=sql.fetchOne("select state,attempts,expires_at from legacy_migration_otp_challenges where id=? and migration_id=?",command.form().challengeId(),command.migrationId());if(r==null||!Set.of("OPEN","PENDING_DELIVERY").contains(r.get("state"))||!now(sql).isBefore(time(r,"expires_at")))return null;int left=5-r.get("attempts",Integer.class);return left<=0?null:new Form(UUID.randomUUID(),command.form().purpose(),command.form().challengeId(),null,left,null);});}
    Instant deadline(PostgresLoginRequestScope.Request owned,String cookie,UUID migration){owned(owned);return owned.transaction(sql->time(flow(sql,migration,cookie),"account_deadline"));}
    VerificationPage status(PostgresLoginRequestScope.Request owned,String cookie){
        owned(owned);return owned.transaction(sql->{if(!canonical(cookie))throw denied(Kind.UNAUTHORIZED);Record found=null;for(String key:crypto.keyIds()){Record r=sql.fetchOne("select * from legacy_migration_browser_continuations where retention_key_id=? and cookie_hash=?",key,cookieHash(key,cookie));if(r!=null){if(found!=null)throw denied(Kind.UNAVAILABLE);found=r;}}if(found==null)throw denied(Kind.UNAUTHORIZED);authenticate(found,cookie);return latest(sql,found);});
    }
    private Record flow(DSLContext sql,UUID migration,String cookie){Record i=intent(sql,migration,cookie);Record r=sql.fetchOne("select * from legacy_migration_browser_continuations where migration_id=?",migration);if(r==null||!i.get("identity_id").equals(r.get("identity_id")))throw denied(Kind.UNAUTHORIZED);authenticate(r,cookie);return r;}
    private Record intent(DSLContext sql,UUID migration,String cookie){if(migration==null||!canonical(cookie))throw denied(Kind.UNAUTHORIZED);Record i=sql.fetchOne("select * from legacy_migration_intents where id=?",migration);if(i==null)throw denied(Kind.UNAUTHORIZED);PostgresLegacyMigrationStore.lockOwner(sql,i.get("identity_id",UUID.class));i=sql.fetchOne("select * from legacy_migration_intents where id=? for update",migration);if(!MessageDigest.isEqual(i.get("capability_hash",byte[].class),accountCrypto.capability(i.get("retention_key_id",String.class),migration,i.get("identity_id",UUID.class),cookie)))throw denied(Kind.UNAUTHORIZED);return i;}
    private void authenticate(Record flow,String cookie){if(!actor.equals(flow.get("actor"))||!canonical(cookie)||!MessageDigest.isEqual(flow.get("cookie_hash",byte[].class),cookieHash(flow.get("retention_key_id",String.class),cookie)))throw denied(Kind.UNAUTHORIZED);}
    private byte[] cookieHash(String key,String cookie){return crypto.hash(key,"armoney-legacy-migration-browser-cookie-v1","ACCOUNT",cookie);}
    private Record command(DSLContext sql,UUID command){if(command==null)throw denied(Kind.UNAUTHORIZED);Record r=sql.fetchOne("select * from legacy_migration_browser_commands where command_id=?",command);if(r==null)throw denied(Kind.UNAUTHORIZED);return r;}
    private String token(Record flow,Record command){return token(flow,command.get("command_id",UUID.class),command.get("purpose",String.class),command.get("prior_command_id",UUID.class));}
    private String token(Record flow,UUID id,String purpose,UUID prior){return Base64.getUrlEncoder().withoutPadding().encodeToString(crypto.hash(flow.get("retention_key_id",String.class),TOKEN,"1",flow.get("migration_id").toString(),flow.get("identity_id").toString(),id.toString(),purpose,hex(flow.get("cookie_hash",byte[].class)),none(prior),stamp(time(flow,"account_deadline"))));}
    private byte[] csrf(Record flow,UUID id,String purpose,String value){return crypto.hash(flow.get("retention_key_id",String.class),"armoney-legacy-migration-browser-csrf-v1","CONTINUATION_"+purpose,id.toString(),value);}
    private void validateToken(Record flow,Record row,String supplied){if(!canonical(supplied)||!MessageDigest.isEqual(row.get("csrf_hash",byte[].class),csrf(flow,row.get("command_id",UUID.class),row.get("purpose",String.class),supplied))||!MessageDigest.isEqual(token(flow,row).getBytes(java.nio.charset.StandardCharsets.US_ASCII),supplied.getBytes(java.nio.charset.StandardCharsets.US_ASCII)))throw denied(Kind.UNAUTHORIZED);}
    private byte[] payload(Record flow,Record row,String otpKey,String commitment){boolean consume=row.get("challenge_id")!=null;if(consume!=(otpKey!=null&&commitment!=null)||otpKey!=null&&!otpKey.matches("[A-Za-z0-9_-]{1,32}")||commitment!=null&&!commitment.matches("[0-9a-f]{64}"))throw denied(Kind.BAD_INPUT);return crypto.hash(flow.get("retention_key_id",String.class),COMMAND,"2",actor,flow.get("migration_id").toString(),flow.get("identity_id").toString(),row.get("command_id").toString(),row.get("purpose",String.class),none(row.get("challenge_id")),hex(flow.get("cookie_hash",byte[].class)),none(otpKey),none(commitment),stamp(time(flow,"account_deadline")),stamp(time(flow,"intent_deadline")));}
    private void insertForm(DSLContext sql,Record flow,UUID prior,Form form){int used=sql.fetchOne("select count(*) from legacy_migration_browser_commands where migration_id=?",flow.get("migration_id")).get(0,Long.class).intValue();if(used>=32)throw denied(Kind.RATE_LIMITED);sql.execute("insert into legacy_migration_browser_commands values(?,?,?,?,?,?,cast(? as timestamptz),?,?,?)",form.commandId(),flow.get("migration_id"),used+1,prior,form.purpose().name(),form.challengeId(),form.eligibleAt()==null?null:form.eligibleAt().toString(),form.attemptsRemaining(),form.finalCommandId(),csrf(flow,form.commandId(),form.purpose().name(),token(flow,form.commandId(),form.purpose().name(),prior)));}
    private VerificationPage createBundle(DSLContext sql,Record flow,List<Form> forms,Status status){LegacyMigrationBrowserContinuation.bundle(forms);if(forms.isEmpty()!=(status!=null))throw denied(Kind.UNAVAILABLE);int sequence=sql.fetchOne("select count(*) from legacy_migration_browser_bundles where migration_id=?",flow.get("migration_id")).get(0,Long.class).intValue()+1;UUID id=UUID.randomUUID();sql.execute("insert into legacy_migration_browser_bundles values(?,?,?,?)",id,flow.get("migration_id"),sequence,status==null?null:status.name());for(int n=0;n<forms.size();n++)sql.execute("insert into legacy_migration_browser_bundle_forms values(?,?,?)",id,n,forms.get(n).commandId());return bundle(sql,flow,id);}
    private VerificationPage latest(DSLContext sql,Record flow){Record pending=sql.fetchOne("select c.command_id from legacy_migration_browser_claims c join legacy_migration_browser_commands k using(command_id) left join legacy_migration_browser_results r using(command_id) where k.migration_id=? and r.command_id is null",flow.get("migration_id"));if(pending!=null)return new ReadOnlyStatus(Status.PENDING_RESULT,time(flow,"account_deadline"));Record b=sql.fetchOne("select bundle_id from legacy_migration_browser_bundles where migration_id=? order by sequence desc limit 1",flow.get("migration_id"));if(b==null)throw denied(Kind.UNAVAILABLE);return bundle(sql,flow,b.get("bundle_id",UUID.class));}
    private VerificationPage bundle(DSLContext sql,Record flow,UUID id){Record b=sql.fetchOne("select * from legacy_migration_browser_bundles where bundle_id=? and migration_id=?",id,flow.get("migration_id"));if(b==null)throw denied(Kind.UNAVAILABLE);if(!now(sql).isBefore(time(flow,"account_deadline")))return expiredStatus(sql,flow);if(b.get("status")!=null)return new ReadOnlyStatus(Status.valueOf(b.get("status",String.class)),time(flow,"account_deadline"));List<ActionForm> forms=new ArrayList<>();for(Record r:sql.fetch("select k.* from legacy_migration_browser_commands k join legacy_migration_browser_bundle_forms f using(command_id) where f.bundle_id=? order by f.ordinal",id))forms.add(new ActionForm(Action.valueOf(r.get("purpose",String.class)),token(flow,r),r.get("command_id",UUID.class),r.get("eligible_at")==null?null:time(r,"eligible_at"),r.get("attempts_remaining",Integer.class)));return new ActionBundle(id,forms,time(flow,"account_deadline"));}
    private ReadOnlyStatus expiredStatus(DSLContext sql,Record flow){
        Record latest=sql.fetchOne("select status from legacy_migration_browser_bundles where migration_id=? order by sequence desc limit 1",flow.get("migration_id"));
        Status state=Status.RECOVERY_REQUIRED;
        if(latest!=null&&"COMPLETED".equals(latest.get("status")))state=Status.COMPLETED;
        else if(sql.fetchOne("select c.command_id from legacy_migration_browser_claims c join legacy_migration_browser_commands k using(command_id) left join legacy_migration_browser_results r using(command_id) where k.migration_id=? and r.command_id is null",flow.get("migration_id"))!=null)state=Status.PENDING_RESULT;
        return new ReadOnlyStatus(state,time(flow,"account_deadline"));
    }
    private static Form form(Record r){return new Form(r.get("command_id",UUID.class),Purpose.valueOf(r.get("purpose",String.class)),r.get("challenge_id",UUID.class),r.get("eligible_at")==null?null:time(r,"eligible_at"),r.get("attempts_remaining",Integer.class),r.get("final_command_id",UUID.class));}
    private static String none(Object v){return v==null?"NONE":v.toString();}private static String hex(byte[] b){return HexFormat.of().formatHex(b);}private static String stamp(Instant i){return i.truncatedTo(java.time.temporal.ChronoUnit.MICROS).toString();}private static Instant now(DSLContext sql){return sql.fetchOne("select clock_timestamp()").get(0,OffsetDateTime.class).toInstant();}private static Instant time(Record r,String k){return r.get(k,OffsetDateTime.class).toInstant();}private static void live(DSLContext sql,Instant deadline){if(!now(sql).isBefore(deadline))throw denied(Kind.UNAUTHORIZED);}private static boolean canonical(String v){return v!=null&&v.matches("[A-Za-z0-9_-]{43}");}private static Failure denied(Kind kind){return new Failure(kind,false);}
}
