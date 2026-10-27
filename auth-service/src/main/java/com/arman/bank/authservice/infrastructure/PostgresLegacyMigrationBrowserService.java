package com.arman.bank.authservice.infrastructure;

import com.arman.bank.authservice.application.*;
import com.arman.bank.authservice.domain.LegacyMigration;
import com.arman.bank.authservice.domain.LegacyMigrationOtp;
import com.arman.bank.authservice.domain.LoginProof;
import com.arman.bank.authservice.domain.LegacyMigrationBrowserContinuation;
import com.arman.bank.authservice.domain.LegacyMigrationBrowserContinuation.Purpose;
import com.arman.bank.runtime.Database;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.function.Function;
import org.jooq.DSLContext;
import org.jooq.Record;

/** Actual restricted browser use cases. No Main registration/default resources or banking success.
 * Every operation owns one PRIMARY session/global permit and shared admission exactly once. */
public final class PostgresLegacyMigrationBrowserService implements LegacyMigrationBrowserService {
    private final Database database;private final AuthService auth;private final PostgresLegacyMigrationStore local;
    private final PostgresLegacyMigrationAccountProofStore account;private final PostgresLegacyMigrationManagedProofStore managed;
    private final LegacyMigrationManagedProofCrypto crypto;private final URI legacyAuthorization,managedAuthorization;private final String actor;private final boolean enabled;
    private final ContinuationServices continuation;
    private final PostgresLegacyMigrationBrowserCommandStore commands;
    /** Explicit trusted composition only; absent dependencies never create continuation success. */
    public record ContinuationServices(PostgresLegacyMigrationOtpStore otp,LegacyMigrationOtpStore.StagePort stage,
        PostgresLegacyMigrationFinalizationStore journal,PostgresMigrationFinalAckStore publication,
        PostgresMigrationBankingEligibilityStore consumer,LegacyMigrationCrypto accountCrypto){
        public ContinuationServices{Objects.requireNonNull(otp);Objects.requireNonNull(stage);Objects.requireNonNull(journal);Objects.requireNonNull(publication);Objects.requireNonNull(consumer);Objects.requireNonNull(accountCrypto);}
        @Override public String toString(){return "MigrationContinuationServices[redacted]";}
    }
    public PostgresLegacyMigrationBrowserService(Database database,AuthService auth,PostgresLegacyMigrationStore local,PostgresLegacyMigrationAccountProofStore account,PostgresLegacyMigrationManagedProofStore managed,LegacyMigrationManagedProofCrypto crypto,URI legacyAuthorization,URI managedAuthorization,boolean enabled){
        this(database,auth,local,account,managed,crypto,legacyAuthorization,managedAuthorization,enabled,null);
    }
    public PostgresLegacyMigrationBrowserService(Database database,AuthService auth,PostgresLegacyMigrationStore local,PostgresLegacyMigrationAccountProofStore account,PostgresLegacyMigrationManagedProofStore managed,LegacyMigrationManagedProofCrypto crypto,URI legacyAuthorization,URI managedAuthorization,boolean enabled,ContinuationServices continuation){
        this.database=Objects.requireNonNull(database);this.auth=Objects.requireNonNull(auth);this.local=Objects.requireNonNull(local);this.account=Objects.requireNonNull(account);this.managed=Objects.requireNonNull(managed);this.crypto=Objects.requireNonNull(crypto);
        this.continuation=continuation;this.commands=continuation==null?null:new PostgresLegacyMigrationBrowserCommandStore(crypto,continuation.accountCrypto(),managed.settings().actor());
        this.legacyAuthorization=endpoint(legacyAuthorization);this.managedAuthorization=endpoint(managedAuthorization);this.actor=managed.settings().actor();this.enabled=enabled;
        if(!actor.equals(account.settings().actor())||!sameOrigin(legacyAuthorization,URI.create(account.settings().issuer()))||!sameOrigin(managedAuthorization,URI.create(managed.settings().issuer())))throw failure(Kind.UNAVAILABLE,false);
    }
    @Override public Form form(String existingAttemptCookie){return operation(owned->owned.transaction(sql->{
        noActiveAttempt(sql,existingAttemptCookie);if(totalBinders(sql)>=100000)throw failure(Kind.RATE_LIMITED,false);
        UUID command=UUID.randomUUID();String cookie=crypto.token(),csrf=crypto.token(),key=crypto.keyId();Instant start=now(sql),deadline=start.plusSeconds(120);
        sql.execute("insert into legacy_migration_browser_forms values(?,?,?,?,cast(? as timestamptz),cast(? as timestamptz))",command,key,binder(key,"FORM",cookie),csrf(key,"FORM",command,csrf),start.toString(),deadline.toString());
        live(sql,deadline);return new Form(cookie,csrf,command,deadline);
    }));}
    @Override public Account password(String cookie,String token,String login,String password,UUID command){return operation(owned->{
        owned.transaction(sql->{guard(sql,"FORM",cookie,token,command,"LOCAL_PASSWORD");return null;});owned.verify();
        LegacyMigration.Admission admitted=auth.beginLegacyMigration(login,password,command,new AuthService.LegacyLocalAdmission(){
            public boolean allowAttempt(String hash,Instant now){return owned.transaction(sql->PostgresAuthStore.allowAttempt(sql,hash,now));}
            public Optional<AuthStore.Credentials> findByEmail(String email){return owned.transaction(sql->PostgresAuthStore.findByEmail(sql,email));}
            public LegacyMigration.Admission admit(AuthStore.Credentials captured,UUID id){return owned.transaction(sql->local.admitLocal(sql,captured.identity().id(),captured.passwordHash(),captured.credentialGeneration(),captured.ownerGeneration(),id));}
        });owned.verify();return accountBinder(owned,admitted);
    });}
    @Override public Redirect accountOidcStart(String cookie,String token,UUID command,String existingAttemptCookie){return operation(owned->{
        owned.transaction(sql->{noActiveAttempt(sql,existingAttemptCookie);guard(sql,"FORM",cookie,token,command,"LEGACY_ACCOUNT");return null;});owned.verify();
        var attempt=account.start(owned,command);return redirect(attempt,legacyAuthorization,account.settings());
    });}
    @Override public Account accountOidcCallback(MigrationManagedProofPort.Callback callback){return operation(owned->accountBinder(owned,account.complete(owned,null,callback)));}
    @Override public Account emailCandidate(String cookie,String token,UUID command,String email){return operation(owned->owned.transaction(sql->{
        Record row=guard(sql,"ACCOUNT",cookie,token,command,"EMAIL_CANDIDATE");UUID migration=row.get("migration_id",UUID.class);
        var route=sql.fetchOne("select identity_id from legacy_migration_intents where id=?",migration);if(route==null)throw failure(Kind.UNAUTHORIZED,false);
        var generation=PostgresLegacyMigrationStore.lockOwner(sql,route.get("identity_id",UUID.class));
        var intent=sql.fetchOne("select * from legacy_migration_intents where id=? for update",migration);PostgresLegacyMigrationStore.requireModeCurrent(sql,intent,generation);
        for(String field:List.of("owner_generation","credential_generation","email_generation"))if(!Objects.equals(generation.get(field),intent.get(field)))throw failure(Kind.CONFLICT,false);
        if(sql.fetchOne("select identity_id from managed_registration_principals where identity_id=?",intent.get("identity_id",UUID.class))!=null)throw failure(Kind.UNAUTHORIZED,false);
        String key=row.get("retention_key_id",String.class);
        String nextToken=Base64.getUrlEncoder().withoutPadding().encodeToString(crypto.hash(key,"armoney-legacy-migration-email-form-v1","1",migration.toString(),intent.get("identity_id").toString(),"EMAIL_CANDIDATE",command.toString(),HexFormat.of().formatHex(row.get("cookie_hash",byte[].class)),HexFormat.of().formatHex(row.get("csrf_hash",byte[].class)),LegacyMigrationContactStore.stamp(time(intent,"authorization_deadline"))));
        Record prior=LegacyMigrationContactStore.candidate(sql,migration);UUID nextCommand=prior==null?UUID.randomUUID():prior.get("next_command_id",UUID.class);
        Record candidate=LegacyMigrationContactStore.select(sql,intent,command,email,crypto,nextCommand,csrf(key,"ACCOUNT",nextCommand,nextToken));
        if(prior==null)sql.execute("insert into legacy_migration_browser_accounts(command_id,migration_id,retention_key_id,cookie_hash,csrf_hash,created_at,expires_at,csrf_purpose) values(?,?,?,?,?,clock_timestamp(),cast(? as timestamptz),'MANAGED_START')",nextCommand,migration,key,binder(key,"ACCOUNT",cookie),candidate.get("next_csrf_hash",byte[].class),time(intent,"authorization_deadline").toString());
        live(sql,time(intent,"authorization_deadline"));return new Account(cookie,nextToken,nextCommand,time(intent,"authorization_deadline"),false);
    }));}
    @Override public Redirect managedStart(String cookie,String token,UUID command,String existingAttemptCookie){return operation(owned->{
        var attempt=owned.transaction(sql->{
            noActiveAttempt(sql,existingAttemptCookie);
            UUID migration=guard(sql,"ACCOUNT",cookie,token,command,"MANAGED_BINDING").get("migration_id",UUID.class);
            return managed.start(sql,migration,cookie,command);
        });owned.verify();return redirect(attempt,managedAuthorization,managed.settings());
    });}
    @Override public Completion managedCallback(MigrationManagedProofPort.Callback callback){return operation(owned->{var result=managed.complete(owned,null,callback);return new Completion(result.state(),result.expiresAt());});}
    @Override public VerificationPage managedVerification(MigrationManagedProofPort.Callback callback,String cookie){
        requireContinuation();return continuationOperation(owned->{UUID migration=managed.requireBrowserAccount(owned,callback,cookie);managed.complete(owned,null,callback);owned.verify();return commands.begin(owned,migration,cookie);});
    }
    @Override public VerificationPage stage(String cookie,String token,UUID command){return transition(cookie,token,command,null,Set.of(Purpose.STAGE),(owned,c)->{
        continuation.otp().stage(owned,c.migrationId(),cookie,continuation.stage());
        return commands.complete(owned,cookie,c,List.of(next(Purpose.EMAIL_ISSUE)),null);
    });}
    @Override public VerificationPage issue(String cookie,String token,UUID command){return transition(cookie,token,command,null,Set.of(Purpose.EMAIL_ISSUE,Purpose.PHONE_ISSUE),(owned,c)->{
        boolean email=c.form().purpose()==Purpose.EMAIL_ISSUE;
        var result=continuation.otp().issue(owned,c.migrationId(),cookie,c.form().commandId(),email?LegacyMigrationOtp.Purpose.EMAIL:LegacyMigrationOtp.Purpose.PHONE);
        if(result.error()!=null||result.challengeId()==null)return commands.complete(owned,cookie,c,List.of(),Status.RECOVERY_REQUIRED);
        var forms=new ArrayList<LegacyMigrationBrowserContinuation.Form>();
        forms.add(new LegacyMigrationBrowserContinuation.Form(UUID.randomUUID(),email?Purpose.EMAIL_CONSUME:Purpose.PHONE_CONSUME,result.challengeId(),null,result.attemptsRemaining(),null));
        if(result.resendAt()!=null&&result.resendAt().isBefore(commands.deadline(owned,cookie,c.migrationId())))forms.add(new LegacyMigrationBrowserContinuation.Form(UUID.randomUUID(),c.form().purpose(),null,result.resendAt(),0,null));
        return commands.complete(owned,cookie,c,forms,null);
    });}
    @Override public VerificationPage consume(String cookie,String token,UUID command,String code){return transition(cookie,token,command,code,Set.of(Purpose.EMAIL_CONSUME,Purpose.PHONE_CONSUME),(owned,c)->{
        boolean email=c.form().purpose()==Purpose.EMAIL_CONSUME;
        var result=continuation.otp().consume(owned,c.migrationId(),cookie,c.form().commandId(),c.form().challengeId(),email?LegacyMigrationOtp.Purpose.EMAIL:LegacyMigrationOtp.Purpose.PHONE,code);
        if(result.error()==null&&result.receiptId()!=null&&"CONSUMED".equals(result.state()))return commands.complete(owned,cookie,c,List.of(next(email?Purpose.PHONE_ISSUE:Purpose.DECISION)),null);
        var retry=commands.retryConsume(owned,cookie,c);if(retry==null)return commands.complete(owned,cookie,c,List.of(),Status.RECOVERY_REQUIRED);
        var forms=new ArrayList<LegacyMigrationBrowserContinuation.Form>();forms.add(retry);var sibling=commands.issueSibling(owned,cookie,c);if(sibling!=null)forms.add(sibling);return commands.complete(owned,cookie,c,forms,null);
    });}
    @Override public VerificationPage authorizePublication(String cookie,String token,UUID command){return transition(cookie,token,command,null,Set.of(Purpose.DECISION),(owned,c)->{continuation.journal().authorize(owned,c.migrationId(),cookie);return commands.complete(owned,cookie,c,List.of(next(Purpose.PUBLICATION)),null);});}
    @Override public VerificationPage publish(String cookie,String token,UUID command){return transition(cookie,token,command,null,Set.of(Purpose.PUBLICATION),(owned,c)->{continuation.publication().publish(owned,c.migrationId(),cookie);return commands.complete(owned,cookie,c,List.of(next(Purpose.FINAL_PREPARE)),null);});}
    @Override public VerificationPage prepareFinal(String cookie,String token,UUID command){return transition(cookie,token,command,null,Set.of(Purpose.FINAL_PREPARE),(owned,c)->{UUID id=continuation.publication().reserveFinalCommand(owned,c.migrationId(),cookie);return commands.complete(owned,cookie,c,List.of(new LegacyMigrationBrowserContinuation.Form(UUID.randomUUID(),Purpose.FINAL,null,null,0,id)),null);});}
    @Override public VerificationPage finish(String cookie,String token,UUID command){return transition(cookie,token,command,null,Set.of(Purpose.FINAL),(owned,c)->{
        boolean completed=completed(owned,c.migrationId());
        if(!completed){continuation.publication().withFinalDecision(owned,c.migrationId(),cookie,c.form().finalCommandId(),(sql,decision,ack,evidence)->continuation.consumer().promote(sql,decision,ack,evidence));completed=completed(owned,c.migrationId());}
        if(!completed)throw failure(Kind.UNAVAILABLE,false);
        continuation.publication().resolveUser(owned,c.migrationId(),cookie);
        return commands.complete(owned,cookie,c,List.of(),Status.COMPLETED);
    });}
    @Override public VerificationPage status(String cookie){requireContinuation();return continuationOperation(owned->commands.status(owned,cookie));}
    private boolean completed(PostgresLoginRequestScope.Request owned,UUID migration){return owned.transaction(sql->{var journal=PostgresLegacyMigrationFinalizationStore.requireHistoricalJournal(sql,migration,crypto);return PostgresMigrationFinalAckStore.validatedCompletion(sql,journal,crypto,actor).isPresent();});}
    private static LegacyMigrationBrowserContinuation.Form next(Purpose purpose){return new LegacyMigrationBrowserContinuation.Form(UUID.randomUUID(),purpose,null,null,0,null);}
    @FunctionalInterface private interface Transition {VerificationPage apply(PostgresLoginRequestScope.Request owned,PostgresLegacyMigrationBrowserCommandStore.Command command);}
    private VerificationPage transition(String cookie,String token,UUID id,String code,Set<Purpose> purposes,Transition work){
        requireContinuation();return continuationOperation(owned->{var command=commands.inspect(owned,cookie,token,id,purposes);String key=null,commitment=null;
            if(command.form().challengeId()!=null){var proof=continuation.otp().browserCodeCommitment(owned,command.migrationId(),cookie,command.form().challengeId(),command.form().purpose()==Purpose.EMAIL_CONSUME?LegacyMigrationOtp.Purpose.EMAIL:LegacyMigrationOtp.Purpose.PHONE,code);key=proof.otpKeyId();commitment=proof.commitmentLowerHex();}
            if(!commands.claim(owned,cookie,token,command,key,commitment))return commands.replay(owned,cookie,command);owned.verify();return commands.withExecution(owned,cookie,command,()->work.apply(owned,command));
        });
    }
    private void requireContinuation(){if(continuation==null)throw failure(Kind.UNAVAILABLE,false);}
    private VerificationPage continuationOperation(Function<PostgresLoginRequestScope.Request,VerificationPage> work){
        if(!enabled)throw failure(Kind.UNAVAILABLE,false);
        try{return new PostgresLoginRequestScope(database).inRequest(actor,owned->{var result=work.apply(owned);owned.verify();return result;});}
        catch(Failure rejected){throw new Failure(rejected.kind(),false);}
        catch(com.arman.bank.authservice.domain.LegacyMigrationFinalization.Failure rejected){throw failure(Kind.valueOf(rejected.error().name()),false);}
        catch(LegacyMigrationOtp.Failure rejected){throw failure(Kind.valueOf(rejected.error().name()),false);}
        catch(LoginProof.Failure rejected){throw failure(rejected.error()==LoginProof.Error.RATE_LIMITED?Kind.RATE_LIMITED:Kind.UNAVAILABLE,false);}
        catch(MigrationManagedProofStore.Consumed rejected){throw failure(Kind.valueOf(rejected.error().name()),false);}
        catch(PostgresLoginOperationGate.UncertainCommit uncertain){throw failure(Kind.UNAVAILABLE,false);}
    }
    private Account accountBinder(PostgresLoginRequestScope.Request owned,LegacyMigration.Admission admitted){
        Account result=owned.transaction(sql->{
            if(totalBinders(sql)>=100000)throw failure(Kind.RATE_LIMITED,true);
            UUID command=UUID.randomUUID();String token=crypto.token(),key=crypto.keyId();Instant created=now(sql);live(sql,admitted.authorizationDeadline());boolean needsEmail=LegacyMigrationContactStore.candidateRequired(sql,admitted.migrationId());
            sql.execute("insert into legacy_migration_browser_accounts(command_id,migration_id,retention_key_id,cookie_hash,csrf_hash,created_at,expires_at,csrf_purpose) values(?,?,?,?,?,cast(? as timestamptz),cast(? as timestamptz),?)",command,admitted.migrationId(),key,binder(key,"ACCOUNT",admitted.ownerCapability()),csrf(key,needsEmail?"ACCOUNT_EMAIL":"ACCOUNT",command,token),created.toString(),admitted.authorizationDeadline().toString(),needsEmail?"EMAIL_CANDIDATE":"MANAGED_START");
            live(sql,admitted.authorizationDeadline());return new Account(admitted.ownerCapability(),token,command,admitted.authorizationDeadline(),needsEmail);
        });owned.verify();return result;
    }
    private Record guard(DSLContext sql,String role,String cookie,String token,UUID command,String purpose){
        if(command==null||!canonical(cookie)||!canonical(token))throw failure(Kind.UNAUTHORIZED,false);
        // Fixed SQL alternatives only. Neither role nor table is caller-controlled.
        Record row=switch(role){case "FORM"->sql.fetchOne("select * from legacy_migration_browser_forms where command_id=? for update",command);case "ACCOUNT"->sql.fetchOne("select * from legacy_migration_browser_accounts where command_id=? for update",command);default->throw failure(Kind.UNAVAILABLE,false);};
        if(row==null)throw failure(Kind.UNAUTHORIZED,false);String key=row.get("retention_key_id",String.class);
        if(!MessageDigest.isEqual(row.get("cookie_hash",byte[].class),binder(key,role,cookie))||!MessageDigest.isEqual(row.get("csrf_hash",byte[].class),csrf(key,role.equals("ACCOUNT")&&"EMAIL_CANDIDATE".equals(row.get("csrf_purpose"))?"ACCOUNT_EMAIL":role,command,token)))throw failure(Kind.UNAUTHORIZED,false);
        live(sql,time(row,"expires_at"));
        if(role.equals("ACCOUNT")&&!Objects.equals(row.get("csrf_purpose"),purpose.equals("MANAGED_BINDING")?"MANAGED_START":purpose))throw failure(Kind.UNAUTHORIZED,false);
        if(sql.fetchOne("select command_id from legacy_migration_browser_consumptions where command_id=?",command)!=null){
            if(purpose.equals("EMAIL_CANDIDATE")){var candidate=LegacyMigrationContactStore.candidate(sql,row.get("migration_id",UUID.class));if(candidate!=null&&command.equals(candidate.get("command_id",UUID.class)))return row;}
            throw failure(Kind.CONFLICT,true);
        }
        sql.execute("insert into legacy_migration_browser_consumptions values(?,?,cast(? as timestamptz))",command,purpose,now(sql).toString());live(sql,time(row,"expires_at"));return row;
    }
    private void noActiveAttempt(DSLContext sql,String cookie){
        if(cookie==null||cookie.isEmpty())return;if(!canonical(cookie))throw failure(Kind.UNAUTHORIZED,false);
        boolean known=false;
        for(String key:crypto.keyIds()){
            for(var row:sql.fetch("select attempt_deadline from legacy_migration_account_attempts where retention_key_id=? and cookie_hash=?",key,crypto.hash(key,"armoney-legacy-migration-managed-browser-v1","LEGACY_ACCOUNT",cookie))){known=true;if(now(sql).isBefore(time(row,"attempt_deadline")))throw failure(Kind.CONFLICT,false);}
            for(var row:sql.fetch("select attempt_deadline from legacy_migration_managed_attempts where retention_key_id=? and cookie_hash=?",key,crypto.hash(key,"armoney-legacy-migration-managed-browser-v1",cookie))){known=true;if(now(sql).isBefore(time(row,"attempt_deadline")))throw failure(Kind.CONFLICT,false);}
        }
        if(!known)throw failure(Kind.UNAVAILABLE,false); // Unknown original key/attempt is never permission to replace.
    }
    private Redirect redirect(MigrationManagedProofStore.Redirect attempt,URI authorization,PostgresLegacyMigrationManagedProofStore.Settings settings){
        var fields=new LinkedHashMap<String,String>();fields.put("client_id",settings.clientId());fields.put("redirect_uri",settings.redirectUri());fields.put("response_type","code");fields.put("scope","openid");fields.put("state",attempt.state());fields.put("nonce",attempt.nonce());fields.put("code_challenge",attempt.codeChallenge());fields.put("code_challenge_method","S256");fields.put("prompt","login");fields.put("max_age","0");
        var query=new StringJoiner("&");fields.forEach((k,v)->query.add(URLEncoder.encode(k,StandardCharsets.UTF_8)+"="+URLEncoder.encode(v,StandardCharsets.UTF_8)));
        return new Redirect(URI.create(authorization.toASCIIString()+"?"+query),attempt.attemptCookie(),attempt.expiresAt());
    }
    private <T>T operation(Function<PostgresLoginRequestScope.Request,T> work){
        if(!enabled)throw failure(Kind.UNAVAILABLE,false);
        try{return new PostgresLoginRequestScope(database).inRequest(actor,owned->{T result=work.apply(owned);owned.verify();return result;});}
        catch(Failure failure){throw failure;}
        catch(MigrationManagedProofStore.Consumed consumed){throw failure(Kind.valueOf(consumed.error().name()),true);}
        catch(PostgresLoginOperationGate.UncertainCommit uncertain){throw failure(Kind.UNAVAILABLE,true);}
        catch(AuthFailure failure){throw failure(Kind.valueOf(failure.kind().name()),false);}
        catch(LegacyMigrationOtp.Failure failure){throw failure(Kind.valueOf(failure.error().name()),false);}
        catch(LoginProof.Failure failure){throw failure(failure.error()==LoginProof.Error.RATE_LIMITED?Kind.RATE_LIMITED:Kind.UNAVAILABLE,false);}
    }
    private byte[] binder(String key,String role,String value){return crypto.hash(key,"armoney-legacy-migration-browser-cookie-v1",role,value);}
    private byte[] csrf(String key,String role,UUID command,String value){return crypto.hash(key,"armoney-legacy-migration-browser-csrf-v1",role,command.toString(),value);}
    private static long totalBinders(DSLContext sql){return sql.fetchOne("select (select count(*) from legacy_migration_browser_forms)+(select count(*) from legacy_migration_browser_accounts)").get(0,Long.class);}
    private static boolean canonical(String value){return value!=null&&value.matches("[A-Za-z0-9_-]{43}");}
    private static URI endpoint(URI value){if(value==null||!"https".equals(value.getScheme())||value.getHost()==null||value.getRawUserInfo()!=null||value.getRawQuery()!=null||value.getRawFragment()!=null)throw failure(Kind.UNAVAILABLE,false);return value;}
    private static boolean sameOrigin(URI a,URI b){return Objects.equals(a.getScheme(),b.getScheme())&&Objects.equals(a.getRawAuthority(),b.getRawAuthority());}
    private static Instant now(DSLContext sql){return sql.fetchOne("select clock_timestamp()").get(0,OffsetDateTime.class).toInstant();}private static Instant time(Record row,String field){return row.get(field,OffsetDateTime.class).toInstant();}
    private static void live(DSLContext sql,Instant expiry){if(!now(sql).isBefore(expiry))throw failure(Kind.UNAUTHORIZED,true);}
    private static Failure failure(Kind kind,boolean clear){return new Failure(kind,clear);}
}
