package com.arman.bank.authservice.infrastructure;

import com.arman.bank.authservice.application.*;
import com.arman.bank.authservice.domain.*;
import com.arman.bank.runtime.Database;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import org.jooq.DSLContext;
import org.jooq.Record;

/** Genuine unknown-subject legacy account flow. Exact existing mapping is resolved ONLY after real proof.
 * It never calls externalIdentity/provisions contacts/allocates an owner or returns a banking token. */
public final class PostgresLegacyMigrationAccountProofStore {
    private final Database database;private final LegacyMigrationCrypto custody;private final LegacyMigrationManagedProofCrypto crypto;
    private final MigrationLegacyAccountProofPort provider;private final PostgresLegacyMigrationManagedProofStore.Settings settings;private final boolean enabled;
    PostgresLegacyMigrationManagedProofStore.Settings settings(){return settings;}
    public PostgresLegacyMigrationAccountProofStore(Database database,LegacyMigrationCrypto custody,LegacyMigrationManagedProofCrypto crypto,MigrationLegacyAccountProofPort provider,PostgresLegacyMigrationManagedProofStore.Settings settings,boolean enabled){this.database=Objects.requireNonNull(database);this.custody=Objects.requireNonNull(custody);this.crypto=Objects.requireNonNull(crypto);this.provider=Objects.requireNonNull(provider);this.settings=Objects.requireNonNull(settings);this.enabled=enabled;}
    public MigrationManagedProofStore.Redirect start(UUID command){
        requireEnabled();if(command==null)throw denied();
        return new PostgresLoginRequestScope(database).inRequest(settings.actor(),owned->start(owned,command));
    }
    MigrationManagedProofStore.Redirect start(PostgresLoginRequestScope.Request owned,UUID command){
        requireEnabled();if(!settings.actor().equals(owned.actor()))throw unavailable();
            var result=owned.transaction(sql->{
                if(sql.fetchOne("select id from legacy_migration_account_attempts where command_id=?",command)!=null)throw denied();
                if(sql.fetchOne("select (select count(*) from legacy_migration_account_attempts)+(select count(*) from legacy_migration_managed_attempts)").get(0,Long.class)>=10000)throw limited();
                UUID id=UUID.randomUUID(),receipt=UUID.randomUUID();String state=crypto.token(),cookie=crypto.token(),nonce=crypto.token(),verifier=crypto.token(),key=crypto.keyId();
                Instant start=now(sql),deadline=start.plusSeconds(120);String challenge=LegacyMigrationManagedProofCrypto.challenge(verifier);
                var encrypted=custody.encrypt(new LegacyMigrationCrypto.Context(id,"LEGACY_ACCOUNT",settings.issuer(),settings.clientId(),settings.redirectUri(),challenge,start,deadline),verifier);
                sql.execute("""
                    insert into legacy_migration_account_attempts values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,cast(? as timestamptz),cast(? as timestamptz))
                    """,id,receipt,command,key,settings.issuer(),settings.clientId(),settings.redirectUri(),secret(key,"browser",cookie),secret(key,"state",state),secret(key,"nonce",nonce),challenge,encrypted.keyId(),encrypted.nonce(),encrypted.ciphertext(),start.toString(),deadline.toString());
                live(sql,deadline);return new MigrationManagedProofStore.Redirect(id,cookie,state,nonce,challenge,deadline);
            });owned.verify();return result;
    }
    public UUID resolveCallback(MigrationManagedProofPort.Callback callback){
        requireEnabled();validate(callback);
        return new PostgresLoginRequestScope(database).inRequest(settings.actor(),owned->owned.transaction(sql->{
            UUID result=null;for(String key:crypto.keyIds()){
                var found=sql.fetch("select id from legacy_migration_account_attempts where retention_key_id=? and state_hash=? and cookie_hash=?",key,secret(key,"state",callback.returnedState()),secret(key,"browser",callback.initiatingBrowserCookie()));
                for(var row:found){if(result!=null)throw unavailable();result=row.get("id",UUID.class);}
            }if(result==null)throw denied();return result;
        }));
    }
    public LegacyMigration.Admission complete(UUID attemptId,MigrationManagedProofPort.Callback callback){
        requireEnabled();validate(callback);
        return new PostgresLoginRequestScope(database).inRequest(settings.actor(),owned->complete(owned,attemptId,callback));
    }
    LegacyMigration.Admission complete(PostgresLoginRequestScope.Request owned,UUID attemptId,MigrationManagedProofPort.Callback callback){
        requireEnabled();validate(callback);if(!settings.actor().equals(owned.actor()))throw unavailable();
            UUID selected=attemptId==null?owned.transaction(sql->lookupCallback(sql,callback)):attemptId;
            Claim claim=owned.transaction(sql->{
                Record attempt=sql.fetchOne("select * from legacy_migration_account_attempts where id=? for update",selected);if(attempt==null)throw denied();requireBrowser(attempt,callback);
                live(sql,time(attempt,"attempt_deadline"));if(sql.fetchOne("select attempt_id from legacy_migration_account_claims where attempt_id=?",selected)!=null)throw unavailable();
                Instant start=now(sql);var backend=backend(sql);byte[] hash=callbackHash(attempt,callback);
                sql.execute("insert into legacy_migration_account_claims values(?,?,cast(? as timestamptz),?,cast(? as timestamptz),?)",selected,hash,start.toString(),backend.pid(),backend.start().toString(),txid(sql));
                return new Claim(attempt,start,backend,hash);
            });owned.verify();Active active=new Active(owned,claim,callback);final Proof[] captured={null};
            try{
                provider.exchange(active,callback,(claims,facts)->{active.requireLive();if(captured[0]!=null)throw unavailable();captured[0]=verified(active,claims,facts,owned.transaction(PostgresLegacyMigrationAccountProofStore::now));});
                active.requireLive();Proof proof=captured[0];if(proof==null)throw unavailable();
                LegacyMigration.Admission admission=owned.transaction(sql->{
                    // Signed subject is now genuine. Route without authority, then lock exact existing owner/association.
                    var route=sql.fetchOne("select identity_id from external_identities where issuer=? and subject=?",proof.claims().issuer(),proof.claims().subject());if(route==null)throw denied();
                    UUID identity=route.get("identity_id",UUID.class);Record generation=PostgresLegacyMigrationStore.lockOwner(sql,identity);
                    Record association=sql.fetchOne("select * from legacy_auth_association_generations where issuer=? and subject=? for update",proof.claims().issuer(),proof.claims().subject());
                    var current=sql.fetchOne("select identity_id from external_identities where issuer=? and subject=?",proof.claims().issuer(),proof.claims().subject());
                    if(association==null||current==null||!Boolean.TRUE.equals(association.get("active",Boolean.class))||!identity.equals(current.get("identity_id",UUID.class))||!identity.equals(association.get("identity_id",UUID.class)))throw denied();
                    if(PostgresLegacyMigrationStore.pending(sql,identity)!=null||sql.fetchOne("select identity_id from managed_registration_principals where identity_id=?",identity)!=null||sql.fetchOne("select enrollment_id from verification_authorities where mapped_identity_id=? or pending_identity_id=?",identity,identity)!=null)throw denied();
                    var owner=sql.fetchOne("select email,registration_phone from identities where id=?",identity);
                    if(owner==null||owner.get("email",String.class)!=null&&!owner.get("email",String.class).equals(AuthService.normalizeEmail(owner.get("email",String.class)))||!AuthService.canonicalPhone(owner.get("registration_phone",String.class)))throw denied();
                    if(owner.get("email",String.class)!=null&&!owner.get("email",String.class).equals(proof.facts().email())||!owner.get("registration_phone",String.class).equals(proof.facts().phone()))throw denied();
                    if(sql.fetchOne("select count(*) from legacy_migration_intents").get(0,Long.class)>=10000)throw limited();
                    generation=sql.fetchOne("update legacy_auth_generations set email_generation=email_generation+1 where identity_id=? returning *",identity);
                    UUID migration=UUID.randomUUID();String capability=crypto.token();Instant created=now(sql),accountDeadline=min(active.challengeStartedAt().plusSeconds(120),active.originalAttemptDeadline(),proof.claims().tokenExpiresAt());
                    long ownerGen=generation.get("owner_generation",Long.class),credentialGen=generation.get("credential_generation",Long.class),emailGen=generation.get("email_generation",Long.class),associationGen=association.get("generation",Long.class);
                    String key=claim.row().get("retention_key_id",String.class),receiptHash=receiptHash(active,proof,identity,ownerGen,associationGen,credentialGen,emailGen,accountDeadline,key);
                    sql.execute("""
                        insert into legacy_migration_account_receipts values(?,?,?,'LEGACY_OIDC_REAUTH',?,?,?,?,?,?,?,?,cast(? as timestamptz),cast(? as timestamptz),cast(? as timestamptz),cast(? as timestamptz),cast(? as timestamptz),cast(? as timestamptz),cast(? as timestamptz),?,?)
                        """,claim.row().get("receipt_id",UUID.class),selected,identity,proof.claims().issuer(),proof.claims().subject(),proof.claims().clientId(),proof.claims().userSessionId(),ownerGen,associationGen,credentialGen,emailGen,proof.claims().authenticatedAt().toString(),proof.claims().issuedAt().toString(),proof.claims().tokenExpiresAt().toString(),active.challengeStartedAt().toString(),active.originalDatabaseStart().toString(),accountDeadline.toString(),active.originalAttemptDeadline().toString(),key,receiptHash);
                    sql.execute("""
                        insert into legacy_migration_intents(id,identity_id,command_id,retention_key_id,command_hash,capability_hash,owner_generation,credential_generation,email_generation,proof_mode,state,created_at,authorization_deadline,account_receipt_id,legacy_issuer,legacy_subject,association_generation)
                        values(?,?,?,?,?,?,?,?,?,'LEGACY_OIDC_REAUTH','OWNER_BOUND',cast(? as timestamptz),cast(? as timestamptz),?,?,?,?)
                        """,migration,identity,claim.row().get("command_id",UUID.class),custody.digestKeyId(),custody.commandOidc(identity,claim.row().get("command_id",UUID.class),migration,ownerGen,associationGen,credentialGen,emailGen,proof.claims().issuer(),proof.claims().subject(),receiptHash),custody.capability(migration,identity,capability),ownerGen,credentialGen,emailGen,created.toString(),accountDeadline.toString(),claim.row().get("receipt_id",UUID.class),proof.claims().issuer(),proof.claims().subject(),associationGen);
                    if(owner.get("email",String.class)==null)LegacyMigrationContactStore.baseline(sql,migration,identity);
                    PostgresLegacyMigrationStore.snapshotSessions(sql,migration,identity,ownerGen);
                    sql.execute("insert into legacy_migration_account_completions values(?,?,?,cast(? as timestamptz))",selected,migration,claim.row().get("receipt_id",UUID.class),now(sql).toString());
                    var finalGeneration=sql.fetchOne("select * from legacy_auth_generations where identity_id=?",identity);
                    var intent=sql.fetchOne("select * from legacy_migration_intents where id=?",migration);PostgresLegacyMigrationStore.requireModeCurrent(sql,intent,finalGeneration);
                    active.requireBound(sql);CodeConfirmation.fresh(active.originalDatabaseStart(),proof.deadline(),now(sql));live(sql,accountDeadline);
                    return new LegacyMigration.Admission(migration,identity,ownerGen,credentialGen,created,accountDeadline,capability);
                });owned.verify();return admission;
            }catch(RuntimeException failure){throw new MigrationManagedProofStore.Consumed(failure instanceof LegacyMigrationOtp.Failure typed?typed.error():LegacyMigrationOtp.Error.UNAVAILABLE);}finally{active.closed=true;captured[0]=null;}
    }
    public LegacyMigration.Admission complete(MigrationManagedProofPort.Callback callback){return complete(null,callback);}
    private UUID lookupCallback(DSLContext sql,MigrationManagedProofPort.Callback callback){
        UUID result=null;for(String key:crypto.keyIds())for(var r:sql.fetch("select id from legacy_migration_account_attempts where retention_key_id=? and state_hash=? and cookie_hash=?",key,secret(key,"state",callback.returnedState()),secret(key,"browser",callback.initiatingBrowserCookie()))){if(result!=null)throw unavailable();result=r.get("id",UUID.class);}if(result==null)throw denied();return result;
    }
    private record Backend(int pid,Instant start){}
    private record Claim(Record row,Instant start,Backend backend,byte[] callbackHash){}
    private record Proof(MigrationManagedProofPort.Claims claims,CodeConfirmation.Facts facts,Instant deadline){}
    private final class Active implements MigrationLegacyAccountProofPort.Attempt {
        private final PostgresLoginRequestScope.Request owned;private final Claim claim;private final MigrationManagedProofPort.Callback callback;private final Thread thread=Thread.currentThread();private final long began=System.nanoTime();private boolean closed,used;
        Active(PostgresLoginRequestScope.Request owned,Claim claim,MigrationManagedProofPort.Callback callback){this.owned=owned;this.claim=claim;this.callback=callback;}
        public UUID attemptId(){return claim.row().get("id",UUID.class);}public String issuer(){return claim.row().get("issuer",String.class);}public String clientId(){return claim.row().get("client_id",String.class);}public String redirectUri(){return claim.row().get("redirect_uri",String.class);}public String pkceChallenge(){return claim.row().get("pkce_challenge",String.class);}
        public Instant challengeStartedAt(){return time(claim.row(),"created_at");}public Instant originalDatabaseStart(){return claim.start();}public Instant originalAttemptDeadline(){return time(claim.row(),"attempt_deadline");}
        private void checkLocal(){if(closed||thread!=Thread.currentThread()||Thread.currentThread().isInterrupted()||System.nanoTime()-began>=4_000_000_000L)throw unavailable();owned.check();}
        public void requireLive(){checkLocal();owned.verify();checkLocal();}
        public void requireCallback(MigrationManagedProofPort.Callback input){requireLive();if(!MessageDigest.isEqual(claim.callbackHash(),callbackHash(claim.row(),input)))throw denied();}
        public String originalVerifier(){requireCallback(callback);if(used)throw unavailable();used=true;return custody.decrypt(new LegacyMigrationCrypto.Context(attemptId(),"LEGACY_ACCOUNT",issuer(),clientId(),redirectUri(),pkceChallenge(),challengeStartedAt(),originalAttemptDeadline()),new LegacyMigrationCrypto.Encrypted(claim.row().get("custody_key_id",String.class),claim.row().get("custody_nonce",byte[].class),claim.row().get("custody_ciphertext",byte[].class)));}
        public boolean nonceMatches(String signed){requireLive();return signed!=null&&MessageDigest.isEqual(claim.row().get("nonce_hash",byte[].class),secret(claim.row().get("retention_key_id",String.class),"nonce",signed));}
        void requireBound(DSLContext sql){checkLocal();if(!backend(sql).equals(claim.backend()))throw unavailable();live(sql,originalAttemptDeadline());}
    }
    private Proof verified(Active active,MigrationManagedProofPort.Claims c,CodeConfirmation.Facts f,Instant received){
        if(c==null||!active.issuer().equals(c.issuer())||!active.clientId().equals(c.clientId())||c.subject()==null||c.userSessionId()==null||c.authenticatedAt()==null||c.issuedAt()==null||c.tokenExpiresAt()==null||c.authenticatedAt().isBefore(active.challengeStartedAt())||c.authenticatedAt().isAfter(c.issuedAt())||!c.issuedAt().isBefore(c.tokenExpiresAt()))throw unavailable();
        Instant bound=min(CodeConfirmation.deadline(new LoginProof.Binding(c.issuer(),c.subject(),c.clientId(),c.userSessionId()),f,active.originalDatabaseStart(),received),active.originalAttemptDeadline(),c.tokenExpiresAt());
        if(!f.enabled()||!f.userSessionActive()||!f.clientSessionActive()||f.email()==null||f.phone()==null)throw denied();CodeConfirmation.fresh(active.originalDatabaseStart(),bound,received);return new Proof(c,f,bound);
    }
    private String receiptHash(Active a,Proof p,UUID owner,long ownerGen,long associationGen,long credentialGen,long emailGen,Instant deadline,String key){var c=p.claims();return HexFormat.of().formatHex(crypto.hash(key,"armoney-legacy-migration-account-receipt-v1","1",a.claim.row().get("receipt_id").toString(),a.attemptId().toString(),owner.toString(),"LEGACY_OIDC_REAUTH",c.issuer(),c.subject(),c.clientId(),c.userSessionId(),Long.toString(ownerGen),Long.toString(associationGen),Long.toString(credentialGen),Long.toString(emailGen),Long.toString(c.authenticatedAt().toEpochMilli()),Long.toString(c.issuedAt().toEpochMilli()),Long.toString(c.tokenExpiresAt().toEpochMilli()),Long.toString(a.challengeStartedAt().toEpochMilli()),Long.toString(a.originalDatabaseStart().toEpochMilli()),Long.toString(deadline.toEpochMilli()),Long.toString(a.originalAttemptDeadline().toEpochMilli()),key));}
    private byte[] secret(String key,String purpose,String value){return crypto.hash(key,"armoney-legacy-migration-managed-"+purpose+"-v1","LEGACY_ACCOUNT",value);}
    private byte[] callbackHash(Record r,MigrationManagedProofPort.Callback c){validate(c);return crypto.hash(r.get("retention_key_id",String.class),"armoney-legacy-migration-managed-callback-v1","LEGACY_ACCOUNT",r.get("id").toString(),c.providerCode(),c.returnedState(),c.initiatingBrowserCookie());}
    private void requireBrowser(Record r,MigrationManagedProofPort.Callback c){String key=r.get("retention_key_id",String.class);if(!MessageDigest.isEqual(r.get("state_hash",byte[].class),secret(key,"state",c.returnedState()))||!MessageDigest.isEqual(r.get("cookie_hash",byte[].class),secret(key,"browser",c.initiatingBrowserCookie())))throw denied();}
    private static void validate(MigrationManagedProofPort.Callback c){if(c==null||c.providerCode()==null||c.providerCode().isEmpty()||c.providerCode().length()>4096||c.providerCode().codePoints().anyMatch(x->Character.isWhitespace(x)||Character.isSpaceChar(x)||Character.isISOControl(x))||c.returnedState()==null||!c.returnedState().matches("[A-Za-z0-9_-]{43}")||c.initiatingBrowserCookie()==null||!c.initiatingBrowserCookie().matches("[A-Za-z0-9_-]{43}"))throw denied();}
    private void requireEnabled(){if(!enabled)throw unavailable();}
    private static Instant now(DSLContext sql){return sql.fetchOne("select clock_timestamp()").get(0,OffsetDateTime.class).toInstant();}private static Instant time(Record r,String key){return r.get(key,OffsetDateTime.class).toInstant();}private static Instant min(Instant... v){return Collections.min(Arrays.asList(v));}
    private static Backend backend(DSLContext sql){var r=sql.fetchOne("select pg_backend_pid() as pid,backend_start from pg_stat_activity where pid=pg_backend_pid()");return new Backend(r.get("pid",Integer.class),r.get("backend_start",OffsetDateTime.class).toInstant());}private static long txid(DSLContext sql){return sql.fetchOne("select txid_current()").get(0,Long.class);}
    private static void live(DSLContext sql,Instant end){if(!now(sql).isBefore(end))throw unavailable();}private static LegacyMigrationOtp.Failure unavailable(){return LegacyMigrationManagedProofCrypto.unavailable();}private static LegacyMigrationOtp.Failure denied(){return new LegacyMigrationOtp.Failure(LegacyMigrationOtp.Error.UNAUTHORIZED);}private static LegacyMigrationOtp.Failure limited(){return new LegacyMigrationOtp.Failure(LegacyMigrationOtp.Error.RATE_LIMITED);}
}
