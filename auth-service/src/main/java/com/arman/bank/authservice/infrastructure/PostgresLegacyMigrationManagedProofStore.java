package com.arman.bank.authservice.infrastructure;

import com.arman.bank.authservice.application.*;
import com.arman.bank.authservice.domain.*;
import com.arman.bank.runtime.Database;
import java.net.URI;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.function.Function;
import org.jooq.DSLContext;
import org.jooq.Record;

/** Genuine managed binding producer. Explicit injection of real exchange and observation adapters required.
 * Never provisions identity/profile, writes a mapping/marker, cuts over sessions or returns a bank credential. */
public final class PostgresLegacyMigrationManagedProofStore implements MigrationManagedProofStore,MigrationBindingSqlPort {
    public record Settings(String actor,String issuer,String clientId,String redirectUri){
        public Settings{if(actor==null||!actor.matches("[A-Za-z0-9_-]{1,64}")||clientId==null||!clientId.matches("[A-Za-z0-9_-]{1,128}"))throw unavailable();endpoint(issuer);endpoint(redirectUri);}
        private static void endpoint(String value){try{URI u=URI.create(value);if(!"https".equals(u.getScheme())||u.getHost()==null||u.getRawUserInfo()!=null||u.getRawQuery()!=null||u.getRawFragment()!=null)throw unavailable();}catch(IllegalArgumentException invalid){throw unavailable();}}
    }
    private final Database database;
    private final LegacyMigrationCrypto custody;
    private final LegacyMigrationManagedProofCrypto crypto;
    private final MigrationManagedProofPort provider;
    private final MigrationManagedProofPort.Observations observations;
    private final Settings settings;
    private final boolean enabled;
    PostgresLegacyMigrationManagedProofStore.Settings settings(){return settings;}
    public PostgresLegacyMigrationManagedProofStore(Database database,LegacyMigrationCrypto custody,LegacyMigrationManagedProofCrypto crypto,MigrationManagedProofPort provider,MigrationManagedProofPort.Observations observations,Settings settings,boolean enabled){
        this.database=Objects.requireNonNull(database);this.custody=Objects.requireNonNull(custody);this.crypto=Objects.requireNonNull(crypto);this.provider=Objects.requireNonNull(provider);this.observations=Objects.requireNonNull(observations);this.settings=Objects.requireNonNull(settings);this.enabled=enabled;
    }
    @Override public Redirect start(UUID migration,String capability,UUID command){
        requireEnabled();if(migration==null||command==null||capability==null||!capability.matches("[A-Za-z0-9_-]{43}"))throw denied();
        return new PostgresLoginRequestScope(database).inRequest(settings.actor(),owned->start(owned,migration,capability,command));
    }
    Redirect start(PostgresLoginRequestScope.Request owned,UUID migration,String capability,UUID command){
        requireEnabled();if(!settings.actor().equals(owned.actor()))throw unavailable();
        Redirect result=owned.transaction(sql->start(sql,migration,capability,command));
        owned.verify();return result;
    }
    /** Local allocation only, composed atomically with the browser command consumption. */
    Redirect start(DSLContext sql,UUID migration,String capability,UUID command){
        requireEnabled();if(migration==null||command==null||capability==null||!capability.matches("[A-Za-z0-9_-]{43}"))throw denied();
                Account account=account(sql,migration,capability,false);
                if(sql.fetchOne("select id from legacy_migration_managed_attempts where migration_id=?",migration)!=null)throw denied();
                if(count(sql,"select count(*) from legacy_migration_managed_attempts")>=10000)throw limited();
                UUID id=UUID.randomUUID(),receipt=UUID.randomUUID();String cookie=crypto.token(),state=crypto.token(),nonce=crypto.token(),verifier=crypto.token();
                Instant created=now(sql),deadline=min(time(account.intent(),"authorization_deadline"),created.plusSeconds(120));
                String challenge=LegacyMigrationManagedProofCrypto.challenge(verifier),key=crypto.keyId();
                var encrypted=custody.encrypt(new LegacyMigrationCrypto.Context(id,"MANAGED_BINDING",settings.issuer(),settings.clientId(),settings.redirectUri(),challenge,created,deadline),verifier);
                if("LEGACY_OIDC_REAUTH".equals(account.intent().get("proof_mode"))&&!settings.issuer().equals(account.intent().get("legacy_issuer")))throw denied();
                String accountHash=accountHash(account,key);
                sql.execute("""
                    insert into legacy_migration_managed_attempts(id,receipt_id,migration_id,owner_id,command_id,retention_key_id,account_proof_mode,account_receipt_hash,owner_generation,mode_generation,email_generation,issuer,client_id,redirect_uri,expected_subject,legacy_issuer,legacy_subject,cookie_hash,state_hash,nonce_hash,pkce_challenge,custody_key_id,custody_nonce,custody_ciphertext,created_at,account_deadline,attempt_deadline)
                    values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,cast(? as timestamptz),cast(? as timestamptz),cast(? as timestamptz))
                    """,id,receipt,migration,account.owner(),command,key,account.intent().get("proof_mode"),accountHash,account.ownerGeneration(),account.modeGeneration(),account.emailGeneration(),settings.issuer(),settings.clientId(),settings.redirectUri(),account.intent().get("legacy_subject"),account.intent().get("legacy_issuer"),account.intent().get("legacy_subject"),
                    secret(key,"browser",cookie),secret(key,"state",state),secret(key,"nonce",nonce),challenge,encrypted.keyId(),encrypted.nonce(),encrypted.ciphertext(),created.toString(),time(account.intent(),"authorization_deadline").toString(),deadline.toString());
                checkAccount(sql,account);live(sql,deadline);return new Redirect(id,cookie,state,nonce,challenge,deadline);
    }
    @Override public Result complete(UUID attemptId,MigrationManagedProofPort.Callback callback){
        requireEnabled();validateCallback(callback);
        return new PostgresLoginRequestScope(database).inRequest(settings.actor(),owned->complete(owned,attemptId,callback));
    }
    Result complete(PostgresLoginRequestScope.Request owned,UUID attemptId,MigrationManagedProofPort.Callback callback){
        requireEnabled();validateCallback(callback);if(!settings.actor().equals(owned.actor()))throw unavailable();
            UUID selected=attemptId==null?owned.transaction(sql->lookupCallback(sql,callback)):attemptId;
            long replayBegan=System.nanoTime();
            UUID completedMigration=owned.transaction(sql->{
                Record original=sql.fetchOne("select * from legacy_migration_managed_attempts where id=?",selected);if(original==null)throw denied();requireBrowser(original,callback);
                Record claim=sql.fetchOne("select callback_hash from legacy_migration_managed_claims where attempt_id=?",selected);
                if(claim!=null&&!MessageDigest.isEqual(claim.get("callback_hash",byte[].class),callbackHash(original,callback)))throw conflict();
                return sql.fetchOne("select receipt_id from legacy_migration_managed_completions where attempt_id=?",selected)==null?null:original.get("migration_id",UUID.class);
            });
            if(completedMigration!=null){
                Instant start=owned.transaction(PostgresLegacyMigrationManagedProofStore::now);
                return withEvidence(owned,completedMigration,start,replayBegan,evidence->owned.transaction(sql->{
                    Account current=account(sql,completedMigration,null,true);
                    requireCurrent(sql,new BindingQuery(current.migration(),current.owner(),evidence.receipt,evidence.reservation,LegacyMigration.ProofMode.valueOf(current.intent().get("proof_mode",String.class)),current.ownerGeneration(),current.modeGeneration(),current.emailGeneration(),time(current.intent(),"authorization_deadline")),evidence);
                    return new Result("BOUND",receiptDeadline(evidence.original));
                }));
            }
            Claimed claimed=owned.transaction(sql->{
                Record route=sql.fetchOne("select migration_id from legacy_migration_managed_attempts where id=?",selected);if(route==null)throw denied();
                Account account=account(sql,route.get("migration_id",UUID.class),null,false);
                Record attempt=sql.fetchOne("select * from legacy_migration_managed_attempts where id=? for update",selected);if(attempt==null)throw denied();
                requireBrowser(attempt,callback);checkAttempt(account,attempt);
                // No code exchange retry: a prior claimed attempt, including unknown completion, is unavailable.
                if(sql.fetchOne("select attempt_id from legacy_migration_managed_claims where attempt_id=?",selected)!=null)throw unavailable();
                Instant start=now(sql);live(sql,time(attempt,"attempt_deadline"));UUID invocation=UUID.randomUUID();
                var backend=backend(sql);long tx=txid(sql);
                byte[] callbackHash=callbackHash(attempt,callback);
                sql.execute("insert into legacy_migration_managed_claims values(?,?,?,cast(? as timestamptz),?,cast(? as timestamptz),?)",selected,invocation,callbackHash,start.toString(),backend.pid(),backend.started().toString(),tx);
                checkAccount(sql,account);return new Claimed(attempt,account,start,backend,tx,callbackHash);
            });
            owned.verify();
            ActiveAttempt active=new ActiveAttempt(owned,claimed,callback);
            final Verified[] verified={null};
            try{
                provider.exchange(active,callback,(claims,facts)->{
                    active.requireLive();if(verified[0]!=null)throw unavailable();
                    verified[0]=verify(active,claims,facts,owned.transaction(PostgresLegacyMigrationManagedProofStore::now));
                });
                active.requireLive();Verified proof=verified[0];if(proof==null)throw unavailable();
                Result result=owned.transaction(sql->{
                    Record ownerRoute=sql.fetchOne("select identity_id from legacy_migration_intents where id=?",active.migrationId());
                    if(ownerRoute==null)throw unavailable();PostgresLegacyMigrationStore.lockOwner(sql,ownerRoute.get("identity_id",UUID.class));
                    // Uniqueness lock for the cryptographically selected target precedes the intent lock.
                    Record existing=sql.fetchOne("select * from legacy_migration_managed_targets where issuer=? and subject=? for update",proof.claims().issuer(),proof.claims().subject());
                    if(existing!=null)throw conflict();
                    Account account=account(sql,active.migrationId(),null,true);
                    checkAttempt(account,active.row());checkAccount(sql,account);active.requireBound(sql);
                    var ownerContacts=sql.fetchOne("select email,registration_phone from identities where id=?",account.owner());
                    if(ownerContacts==null||!Objects.equals(LegacyMigrationContactStore.email(sql,account.intent()),proof.facts().email())||!Objects.equals(ownerContacts.get("registration_phone",String.class),proof.facts().phone()))throw conflict();
                    var mapping=sql.fetchOne("select identity_id from external_identities where issuer=? and subject=?",proof.claims().issuer(),proof.claims().subject());
                    if(mapping!=null&&(!"LEGACY_OIDC_REAUTH".equals(account.intent().get("proof_mode"))||!account.owner().equals(mapping.get("identity_id",UUID.class))||!proof.claims().issuer().equals(account.intent().get("legacy_issuer"))||!proof.claims().subject().equals(account.intent().get("legacy_subject"))))throw conflict();
                    if(count(sql,"select count(*) from legacy_migration_managed_receipts")>=10000)throw limited();
                    Record r=active.row();String key=r.get("retention_key_id",String.class),target=targetHash(r,proof.claims(),key);
                    Instant created=now(sql),deadline=min(active.originalAccountDeadline(),active.originalAttemptDeadline(),proof.claims().tokenExpiresAt());
                    String receiptHash=receiptHash(r,proof.claims(),created,target,key);
                    sql.execute("""
                        insert into legacy_migration_managed_receipts values(?,?,?,?,?,?,?,?,?,?,?,?,?,cast(? as timestamptz),cast(? as timestamptz),cast(? as timestamptz),cast(? as timestamptz),cast(? as timestamptz),cast(? as timestamptz),?,?,?)
                        """,active.receiptId(),active.attemptId(),active.migrationId(),active.ownerId(),r.get("account_proof_mode"),r.get("account_receipt_hash"),account.ownerGeneration(),account.modeGeneration(),account.emailGeneration(),proof.claims().issuer(),proof.claims().subject(),proof.claims().clientId(),proof.claims().userSessionId(),proof.claims().authenticatedAt().toString(),proof.claims().issuedAt().toString(),proof.claims().tokenExpiresAt().toString(),active.originalAccountDeadline().toString(),active.originalAttemptDeadline().toString(),created.toString(),key,target,receiptHash);
                    sql.execute("insert into legacy_migration_managed_targets values(?,?,?,?,?,?,?,?,?,?,cast(? as timestamptz),cast(? as timestamptz))",UUID.randomUUID(),active.migrationId(),active.ownerId(),active.receiptId(),proof.claims().issuer(),proof.claims().subject(),account.ownerGeneration(),account.modeGeneration(),account.emailGeneration(),target,created.toString(),deadline.toString());
                    sql.execute("insert into legacy_migration_managed_completions values(?,?,cast(? as timestamptz))",active.attemptId(),active.receiptId(),now(sql).toString());
                    checkAccount(sql,account);active.requireBound(sql);CodeConfirmation.fresh(active.originalDatabaseStart(),proof.evidenceDeadline(),now(sql));live(sql,deadline);
                    return new Result("BOUND",deadline);
                });owned.verify();return result;
            }catch(RuntimeException failure){throw new MigrationManagedProofStore.Consumed(failure instanceof LegacyMigrationOtp.Failure typed?typed.error():LegacyMigrationOtp.Error.UNAVAILABLE);}finally{active.closed=true;verified[0]=null;}
    }
    public Result complete(MigrationManagedProofPort.Callback callback){return complete(null,callback);}
    /** Browser facade supplies the ORIGINAL restricted ACCOUNT cookie (owner capability).
     * This selector creates no proof/claim and performs no I/O or additional admission. */
    UUID requireBrowserAccount(PostgresLoginRequestScope.Request owned,MigrationManagedProofPort.Callback callback,String originalAccountCapability){
        requireEnabled();validateCallback(callback);
        if(owned==null||!settings.actor().equals(owned.actor())||originalAccountCapability==null||!originalAccountCapability.matches("[A-Za-z0-9_-]{43}"))throw denied();
        owned.check();
        UUID migration=owned.transaction(sql->{
            UUID selected=lookupCallback(sql,callback);
            Record route=sql.fetchOne("select migration_id from legacy_migration_managed_attempts where id=?",selected);
            if(route==null)throw denied();
            Account current=account(sql,route.get("migration_id",UUID.class),originalAccountCapability,false);
            Record attempt=sql.fetchOne("select * from legacy_migration_managed_attempts where id=? for update",selected);
            if(attempt==null||!current.migration().equals(attempt.get("migration_id",UUID.class))||!current.owner().equals(attempt.get("owner_id",UUID.class)))throw denied();
            requireBrowser(attempt,callback);checkAttempt(current,attempt);
            Record claim=sql.fetchOne("select callback_hash from legacy_migration_managed_claims where attempt_id=?",selected);
            if(claim!=null&&!MessageDigest.isEqual(claim.get("callback_hash",byte[].class),callbackHash(attempt,callback)))throw conflict();
            checkAccount(sql,current);live(sql,time(attempt,"attempt_deadline"));return current.migration();
        });owned.verify();return migration;
    }
    private UUID lookupCallback(DSLContext sql,MigrationManagedProofPort.Callback callback){
        UUID result=null;
        for(String key:crypto.keyIds())for(var r:sql.fetch("select id from legacy_migration_managed_attempts where retention_key_id=? and state_hash=? and cookie_hash=?",key,secret(key,"state",callback.returnedState()),secret(key,"browser",callback.initiatingBrowserCookie()))){if(result!=null)throw unavailable();result=r.get("id",UUID.class);}
        if(result==null)throw denied();return result;
    }
    /** Private creator-only current-operation capability; never a provider or browser DTO. */
    public static final class EvidenceUse {
        private final PostgresLoginRequestScope.Request request;private final UUID migration,owner,receipt,reservation;private final Thread thread=Thread.currentThread();
        private final Backend backend;private final long admissionTxid;private final Instant start,received,deadline;private final CodeConfirmation.Facts facts;private final Record original;
        private final long nanos=System.nanoTime();private boolean closed;private long consumptionTxid=-1;private DSLContext context;
        private EvidenceUse(PostgresLoginRequestScope.Request request,Record original,Record reservation,Backend backend,long admissionTxid,Instant start,Instant received,Instant deadline,CodeConfirmation.Facts facts){
            this.request=request;this.original=original;this.migration=original.get("migration_id",UUID.class);this.owner=original.get("owner_id",UUID.class);this.receipt=original.get("receipt_id",UUID.class);this.reservation=reservation.get("reservation_id",UUID.class);this.backend=backend;this.admissionTxid=admissionTxid;this.start=start;this.received=received;this.deadline=deadline;this.facts=facts;
        }
        private void check(DSLContext sql,boolean consume){
            if(closed||Thread.currentThread()!=thread||Thread.currentThread().isInterrupted()||System.nanoTime()-nanos>=4_000_000_000L)throw unavailable();
            request.check();PostgresVerificationProjectionStore.requireLocked(sql);
            if(!backend.equals(PostgresLegacyMigrationManagedProofStore.backend(sql)))throw unavailable();
            long current=txid(sql);
            if(current==admissionTxid)throw unavailable();
            if(consume){if(consumptionTxid<0){consumptionTxid=current;context=sql;}else if(consumptionTxid!=current||context!=sql)throw unavailable();}
            CodeConfirmation.fresh(start,deadline,now(sql));
        }
        /** Original observation lease only. Never a local authority/marker bypass on its own. */
        void requireObservation(DSLContext sql){check(sql,true);}
        Instant deadline(){if(closed||thread!=Thread.currentThread()||Thread.currentThread().isInterrupted())throw unavailable();request.check();return deadline;}
        UUID receiptId(){if(closed||thread!=Thread.currentThread())throw unavailable();return receipt;}
        UUID reservationId(){if(closed||thread!=Thread.currentThread())throw unavailable();return reservation;}
        @Override public String toString(){return "MigrationBindingEvidence[redacted]";}
    }
    private record ObservationAdmission(Record receipt,Record reservation,Backend backend,long txid,Instant start){}
    @Override public <T>T withEvidence(PostgresLoginRequestScope.Request request,UUID migration,Instant originalStart,long began,Function<EvidenceUse,T> action){
        requireEnabled();Objects.requireNonNull(originalStart);
        ObservationAdmission admission=request.transaction(sql->{
            Account account=account(sql,migration,null,true);
            Record receipt=sql.fetchOne("select * from legacy_migration_managed_receipts where migration_id=?",migration);
            Record reservation=sql.fetchOne("select * from legacy_migration_managed_targets where migration_id=?",migration);
            if(receipt==null||reservation==null)throw unavailable();verifyReceipt(sql,receipt);checkReceiptAccount(account,receipt);live(sql,receiptDeadline(receipt));
            if(count(sql,"select count(*) from legacy_migration_managed_observations")>=100000)throw limited();
            Instant start=originalStart;if(now(sql).isBefore(start))throw unavailable();Backend b=backend(sql);long tx=txid(sql);
            sql.execute("insert into legacy_migration_managed_observations values(?,?,?,?,cast(? as timestamptz),?,cast(? as timestamptz),?)",UUID.randomUUID(),migration,receipt.get("receipt_id",UUID.class),reservation.get("reservation_id",UUID.class),start.toString(),b.pid(),b.started().toString(),tx);
            return new ObservationAdmission(receipt,reservation,b,tx,start);
        });request.verify();
        Duration remaining=Duration.ofNanos(4_000_000_000L-(System.nanoTime()-began));if(remaining.isNegative()||remaining.isZero())throw unavailable();
        Record r=admission.receipt();var binding=new LoginProof.Binding(r.get("issuer",String.class),r.get("subject",String.class),r.get("client_id",String.class),r.get("user_session_id",String.class));
        CodeConfirmation.Facts facts=observations.observe(binding,admission.start(),remaining);request.verify();
        Instant received=request.transaction(PostgresLegacyMigrationManagedProofStore::now);
        Instant deadline=min(CodeConfirmation.deadline(binding,facts,admission.start(),received),receiptDeadline(r));
        if(!facts.enabled()||!facts.userSessionActive()||!facts.clientSessionActive()||facts.email()==null||facts.phone()==null)throw denied();
        EvidenceUse use=new EvidenceUse(request,r,admission.reservation(),admission.backend(),admission.txid(),admission.start(),received,deadline,facts);
        try{T result=action.apply(use);request.verify();return result;}finally{use.closed=true;}
    }
    @Override public void lockTarget(DSLContext sql,UUID migration,EvidenceUse evidence){
        if(evidence==null||!migration.equals(evidence.migration))throw unavailable();evidence.check(sql,false);
        Record target=sql.fetchOne("select reservation_id,receipt_id from legacy_migration_managed_targets where migration_id=? for update",migration);
        if(target==null||!evidence.reservation.equals(target.get("reservation_id",UUID.class))||!evidence.receipt.equals(target.get("receipt_id",UUID.class)))throw conflict();
    }
    @Override public String requireCurrent(DSLContext sql,BindingQuery query,EvidenceUse evidence){
        if(query==null||evidence==null)throw unavailable();evidence.check(sql,true);
        if(!query.migrationId().equals(evidence.migration)||!query.ownerId().equals(evidence.owner)||!query.receiptId().equals(evidence.receipt)||!query.reservationId().equals(evidence.reservation))throw conflict();
        Record receipt=sql.fetchOne("select * from legacy_migration_managed_receipts where receipt_id=? and migration_id=?",evidence.receipt,query.migrationId());
        if(receipt==null)throw unavailable();verifyReceipt(sql,receipt);
        if(!query.accountProofMode().name().equals(receipt.get("account_proof_mode"))||query.ownerGeneration()!=receipt.get("owner_generation",Long.class)||query.modeGeneration()!=receipt.get("mode_generation",Long.class)||query.emailGeneration()!=receipt.get("email_generation",Long.class)||!query.originalAccountDeadline().equals(time(receipt,"account_deadline")))throw conflict();
        var identity=sql.fetchOne("select email,registration_phone from identities where id=?",query.ownerId());
        if(identity==null||!Objects.equals(LegacyMigrationContactStore.email(sql,sql.fetchOne("select * from legacy_migration_intents where id=?",query.migrationId())),evidence.facts.email())||!Objects.equals(identity.get("registration_phone",String.class),evidence.facts.phone()))throw conflict();
        Record target=sql.fetchOne("select * from legacy_migration_managed_targets where reservation_id=?",evidence.reservation);
        if(target==null||!Objects.equals(target.get("target_binding_hash"),receipt.get("target_binding_hash"))||!Objects.equals(target.get("migration_id"),query.migrationId())||!Objects.equals(target.get("owner_id"),query.ownerId())||!Objects.equals(target.get("receipt_id"),query.receiptId())||!Objects.equals(target.get("issuer"),receipt.get("issuer"))||!Objects.equals(target.get("subject"),receipt.get("subject"))||!Objects.equals(target.get("owner_generation"),receipt.get("owner_generation"))||!Objects.equals(target.get("mode_generation"),receipt.get("mode_generation"))||!Objects.equals(target.get("email_generation"),receipt.get("email_generation")))throw conflict();
        var mapping=sql.fetchOne("select identity_id from external_identities where issuer=? and subject=?",receipt.get("issuer"),receipt.get("subject"));
        if(mapping!=null&&(!"LEGACY_OIDC_REAUTH".equals(receipt.get("account_proof_mode"))||!query.ownerId().equals(mapping.get("identity_id",UUID.class))))throw conflict();
        live(sql,receiptDeadline(receipt));evidence.check(sql,true);return receipt.get("target_binding_hash",String.class);
    }
    @Override public void emailConsumed(DSLContext sql,BindingQuery query,EvidenceUse evidence,UUID receipt,UUID challenge,UUID command){
        requireCurrent(sql,query,evidence);
        var row=sql.fetchOne("select * from legacy_migration_otp_receipts where id=? and migration_id=? and challenge_id=? and purpose='EMAIL'",receipt,query.migrationId(),challenge);
        if(row==null||command==null)throw conflict();
        var intent=sql.fetchOne("select * from legacy_migration_intents where id=?",query.migrationId());
        LegacyMigrationContactStore.consumed(sql,intent,receipt,challenge,command,crypto);
        requireCurrent(sql,query,evidence);
    }
    private void checkReceiptAccount(Account a,Record r){if(!a.owner().equals(r.get("owner_id",UUID.class))||a.ownerGeneration()!=r.get("owner_generation",Long.class)||a.modeGeneration()!=r.get("mode_generation",Long.class)||a.emailGeneration()!=r.get("email_generation",Long.class))throw conflict();}
    private Instant receiptDeadline(Record r){return min(time(r,"account_deadline"),time(r,"attempt_deadline"),time(r,"token_expires_at"));}
    private void verifyReceipt(DSLContext sql,Record receipt){
        Record attempt=sql.fetchOne("select * from legacy_migration_managed_attempts where id=?",receipt.get("attempt_id",UUID.class));if(attempt==null)throw unavailable();
        var c=new MigrationManagedProofPort.Claims(receipt.get("issuer",String.class),receipt.get("subject",String.class),receipt.get("client_id",String.class),receipt.get("user_session_id",String.class),time(receipt,"authenticated_at"),time(receipt,"issued_at"),time(receipt,"token_expires_at"));
        String key=receipt.get("retention_key_id",String.class),target=targetHash(attempt,c,key),hash=receiptHash(attempt,c,time(receipt,"created_at"),target,key);
        if(!MessageDigest.isEqual(target.getBytes(java.nio.charset.StandardCharsets.US_ASCII),receipt.get("target_binding_hash",String.class).getBytes(java.nio.charset.StandardCharsets.US_ASCII))||!MessageDigest.isEqual(hash.getBytes(java.nio.charset.StandardCharsets.US_ASCII),receipt.get("receipt_hash",String.class).getBytes(java.nio.charset.StandardCharsets.US_ASCII)))throw unavailable();
    }
    private record Account(UUID migration,UUID owner,long ownerGeneration,long modeGeneration,long emailGeneration,Record intent,String accountReceiptHash){}
    /** Route owner nonlocking, then canonical owner -> target -> intent locks. */
    private Account account(DSLContext sql,UUID migration,String capability,boolean target){
        Record routing=sql.fetchOne("select identity_id from legacy_migration_intents where id=?",migration);if(routing==null)throw denied();
        UUID owner=routing.get("identity_id",UUID.class);Record generation=PostgresLegacyMigrationStore.lockOwner(sql,owner);
        Record preliminary=sql.fetchOne("select * from legacy_migration_intents where id=?",migration);if(preliminary==null)throw denied();
        PostgresLegacyMigrationStore.requireModeCurrent(sql,preliminary,generation);
        if(target)sql.fetchOne("select reservation_id from legacy_migration_managed_targets where migration_id=? for update",migration);
        Record intent=sql.fetchOne("select * from legacy_migration_intents where id=? for update",migration);if(intent==null)throw denied();
        if(capability!=null&&!MessageDigest.isEqual(intent.get("capability_hash",byte[].class),custody.capability(intent.get("retention_key_id",String.class),migration,owner,capability)))throw denied();
        PostgresLegacyMigrationStore.requireModeCurrent(sql,intent,generation);
        for(String name:List.of("owner_generation","credential_generation","email_generation"))if(!Objects.equals(intent.get(name,Long.class),generation.get(name,Long.class)))throw conflict();
        if(sql.fetchOne("select identity_id from managed_registration_principals where identity_id=?",owner)!=null)throw denied();
        var authorities=sql.fetch("select enrollment_id from verification_authorities where mapped_identity_id=? or pending_identity_id=?",owner,owner);
        if(!authorities.isEmpty()){
            // Only the exact Auth-owned immutable association record can identify an authenticated migration association.
            // This wave does not manufacture or populate such association records.
            var association=sql.fetchOne("select enrollment_id,owner_generation from legacy_migration_associations where migration_id=?",migration);
            if(authorities.size()!=1||association==null||!authorities.get(0).get("enrollment_id",UUID.class).equals(association.get("enrollment_id",UUID.class))||association.get("owner_generation",Long.class).longValue()!=generation.get("owner_generation",Long.class).longValue())throw denied();
        }
        LegacyMigrationContactStore.verify(sql,intent,crypto);LegacyMigrationContactStore.current(sql,intent,true);
        live(sql,time(intent,"authorization_deadline"));return new Account(migration,owner,generation.get("owner_generation",Long.class),"LEGACY_OIDC_REAUTH".equals(intent.get("proof_mode"))?intent.get("association_generation",Long.class):generation.get("credential_generation",Long.class),generation.get("email_generation",Long.class),intent,"LEGACY_OIDC_REAUTH".equals(intent.get("proof_mode"))?accountReceiptHash(sql,intent):null);
    }
    private void checkAccount(DSLContext sql,Account expected){Account actual=account(sql,expected.migration(),null,false);if(!actual.owner().equals(expected.owner())||actual.ownerGeneration()!=expected.ownerGeneration()||actual.modeGeneration()!=expected.modeGeneration()||actual.emailGeneration()!=expected.emailGeneration())throw conflict();}
    private void checkAttempt(Account account,Record attempt){if(!account.owner().equals(attempt.get("owner_id",UUID.class))||account.ownerGeneration()!=attempt.get("owner_generation",Long.class)||account.modeGeneration()!=attempt.get("mode_generation",Long.class)||account.emailGeneration()!=attempt.get("email_generation",Long.class)||!account.intent().get("proof_mode").equals(attempt.get("account_proof_mode")))throw conflict();}
    private String accountReceiptHash(DSLContext sql,Record intent){
        Record r=sql.fetchOne("select * from legacy_migration_account_receipts where receipt_id=?",intent.get("account_receipt_id",UUID.class));
        if(r==null||!Objects.equals(r.get("owner_id"),intent.get("identity_id"))||!Objects.equals(r.get("issuer"),intent.get("legacy_issuer"))||!Objects.equals(r.get("subject"),intent.get("legacy_subject"))||!Objects.equals(r.get("association_generation"),intent.get("association_generation")))throw unavailable();
        var fields=new ArrayList<String>();fields.add("1");for(String n:List.of("receipt_id","attempt_id","owner_id","proof_mode","issuer","subject","client_id","user_session_id","owner_generation","association_generation","credential_generation","email_generation"))fields.add(r.get(n).toString());
        for(String n:List.of("authenticated_at","issued_at","token_expires_at","challenge_created_at","callback_database_start","account_deadline","attempt_deadline"))fields.add(Long.toString(time(r,n).toEpochMilli()));fields.add(r.get("retention_key_id",String.class));
        String hash=hex(crypto.hash(r.get("retention_key_id",String.class),"armoney-legacy-migration-account-receipt-v1",fields.toArray(String[]::new)));
        if(!MessageDigest.isEqual(hash.getBytes(java.nio.charset.StandardCharsets.US_ASCII),r.get("receipt_hash",String.class).getBytes(java.nio.charset.StandardCharsets.US_ASCII)))throw unavailable();live(sql,time(r,"account_deadline"));return hash;
    }
    private String accountHash(Account a,String key){if("LEGACY_OIDC_REAUTH".equals(a.intent().get("proof_mode")))return a.accountReceiptHash();return hex(crypto.hash(key,"armoney-legacy-migration-managed-account-v1","1",a.migration().toString(),a.owner().toString(),"LOCAL_PASSWORD",Long.toString(a.ownerGeneration()),Long.toString(a.modeGeneration()),Long.toString(a.emailGeneration()),hex(a.intent().get("command_hash",byte[].class)),Long.toString(time(a.intent(),"authorization_deadline").toEpochMilli())));}
    private record Backend(int pid,Instant started){}
    private record Claimed(Record attempt,Account account,Instant start,Backend backend,long txid,byte[] callbackHash){}
    private record Verified(MigrationManagedProofPort.Claims claims,CodeConfirmation.Facts facts,Instant evidenceDeadline){}
    private final class ActiveAttempt implements MigrationManagedProofPort.Attempt {
        private final PostgresLoginRequestScope.Request owned;private final Claimed claimed;private final MigrationManagedProofPort.Callback callback;
        private final Thread thread=Thread.currentThread();private final long nanos=System.nanoTime();private boolean closed,verifierUsed;
        ActiveAttempt(PostgresLoginRequestScope.Request owned,Claimed claimed,MigrationManagedProofPort.Callback callback){this.owned=owned;this.claimed=claimed;this.callback=callback;}
        Record row(){return claimed.attempt();}
        Object value(String key){return claimed.attempt().get(key);}
        public UUID attemptId(){return (UUID)value("id");}public UUID receiptId(){return (UUID)value("receipt_id");}public UUID migrationId(){return (UUID)value("migration_id");}public UUID ownerId(){return (UUID)value("owner_id");}
        public String issuer(){return (String)value("issuer");}public String clientId(){return (String)value("client_id");}public String redirectUri(){return (String)value("redirect_uri");}public String expectedSubject(){return (String)value("expected_subject");}
        public LegacyMigration.ProofMode accountProofMode(){return LegacyMigration.ProofMode.valueOf((String)value("account_proof_mode"));}public String legacyIssuer(){return (String)value("legacy_issuer");}public String legacySubject(){return (String)value("legacy_subject");}public String pkceChallenge(){return (String)value("pkce_challenge");}
        public Instant challengeStartedAt(){return ((OffsetDateTime)value("created_at")).toInstant();}public Instant originalDatabaseStart(){return claimed.start();}public Instant originalAccountDeadline(){return ((OffsetDateTime)value("account_deadline")).toInstant();}public Instant originalAttemptDeadline(){return ((OffsetDateTime)value("attempt_deadline")).toInstant();}
        private void checkLocal(){if(closed||thread!=Thread.currentThread()||Thread.currentThread().isInterrupted()||System.nanoTime()-nanos>=4_000_000_000L)throw unavailable();owned.check();}
        public void requireLive(){checkLocal();owned.verify();checkLocal();}
        public void requireCallback(MigrationManagedProofPort.Callback value){requireLive();if(!MessageDigest.isEqual(claimed.callbackHash(),callbackHash(row(),value)))throw denied();}
        public String originalVerifier(){requireCallback(callback);if(verifierUsed)throw unavailable();verifierUsed=true;return custody.decrypt(new LegacyMigrationCrypto.Context(attemptId(),"MANAGED_BINDING",issuer(),clientId(),redirectUri(),pkceChallenge(),challengeStartedAt(),originalAttemptDeadline()),new LegacyMigrationCrypto.Encrypted((String)value("custody_key_id"),(byte[])value("custody_nonce"),(byte[])value("custody_ciphertext")));}
        public boolean nonceMatches(String nonce){requireLive();return nonce!=null&&MessageDigest.isEqual((byte[])value("nonce_hash"),secret((String)value("retention_key_id"),"nonce",nonce));}
        void requireBound(DSLContext sql){checkLocal();if(!backend(sql).equals(claimed.backend()))throw unavailable();live(sql,min(originalAccountDeadline(),originalAttemptDeadline()));}
    }
    private Verified verify(ActiveAttempt active,MigrationManagedProofPort.Claims c,CodeConfirmation.Facts f,Instant received){
        if(c==null||!active.issuer().equals(c.issuer())||!active.clientId().equals(c.clientId())||!strict(c.subject(),255)||!strict(c.userSessionId(),255)||c.authenticatedAt()==null||c.issuedAt()==null||c.tokenExpiresAt()==null||c.authenticatedAt().isBefore(active.challengeStartedAt())||c.authenticatedAt().isAfter(c.issuedAt())||!c.issuedAt().isBefore(c.tokenExpiresAt()))throw unavailable();
        if(active.expectedSubject()!=null&&!active.expectedSubject().equals(c.subject()))throw denied();
        var binding=new LoginProof.Binding(c.issuer(),c.subject(),c.clientId(),c.userSessionId());Instant deadline=min(CodeConfirmation.deadline(binding,f,active.originalDatabaseStart(),received),active.originalAccountDeadline(),active.originalAttemptDeadline(),c.tokenExpiresAt());
        if(f==null||!f.enabled()||!f.userSessionActive()||!f.clientSessionActive()||f.email()==null||f.phone()==null)throw denied();CodeConfirmation.fresh(active.originalDatabaseStart(),deadline,received);return new Verified(c,f,deadline);
    }
    private String targetHash(Record r,MigrationManagedProofPort.Claims c,String key){return hex(crypto.hash(key,"armoney-legacy-migration-target-v1","1",r.get("migration_id").toString(),r.get("owner_id").toString(),r.get("receipt_id").toString(),c.issuer(),c.subject(),c.clientId(),c.userSessionId(),r.get("owner_generation").toString(),r.get("account_proof_mode").toString(),r.get("mode_generation").toString(),r.get("email_generation").toString(),r.get("account_receipt_hash").toString(),Long.toString(time(r,"account_deadline").toEpochMilli()),Long.toString(time(r,"attempt_deadline").toEpochMilli())));}
    private String receiptHash(Record r,MigrationManagedProofPort.Claims c,Instant created,String target,String key){return hex(crypto.hash(key,"armoney-legacy-migration-managed-receipt-v1","1",r.get("receipt_id").toString(),r.get("id").toString(),r.get("migration_id").toString(),r.get("owner_id").toString(),r.get("account_proof_mode").toString(),r.get("account_receipt_hash").toString(),r.get("owner_generation").toString(),r.get("mode_generation").toString(),r.get("email_generation").toString(),c.issuer(),c.subject(),c.clientId(),c.userSessionId(),Long.toString(c.authenticatedAt().toEpochMilli()),Long.toString(c.issuedAt().toEpochMilli()),Long.toString(c.tokenExpiresAt().toEpochMilli()),Long.toString(time(r,"account_deadline").toEpochMilli()),Long.toString(time(r,"attempt_deadline").toEpochMilli()),Long.toString(created.toEpochMilli()),key,target));}
    private byte[] callbackHash(Record r,MigrationManagedProofPort.Callback c){validateCallback(c);return crypto.hash(r.get("retention_key_id",String.class),"armoney-legacy-migration-managed-callback-v1",r.get("id").toString(),c.providerCode(),c.returnedState(),c.initiatingBrowserCookie());}
    private void requireBrowser(Record r,MigrationManagedProofPort.Callback c){String key=r.get("retention_key_id",String.class);if(!MessageDigest.isEqual(r.get("cookie_hash",byte[].class),secret(key,"browser",c.initiatingBrowserCookie()))||!MessageDigest.isEqual(r.get("state_hash",byte[].class),secret(key,"state",c.returnedState())))throw denied();}
    private byte[] secret(String key,String purpose,String value){return crypto.hash(key,"armoney-legacy-migration-managed-"+purpose+"-v1",value);}
    private static void validateCallback(MigrationManagedProofPort.Callback c){if(c==null||!strict(c.providerCode(),4096)||c.returnedState()==null||!c.returnedState().matches("[A-Za-z0-9_-]{43}")||c.initiatingBrowserCookie()==null||!c.initiatingBrowserCookie().matches("[A-Za-z0-9_-]{43}"))throw denied();}
    private void requireEnabled(){if(!enabled)throw unavailable();}
    private static boolean strict(String s,int length){return s!=null&&!s.isEmpty()&&s.length()<=length&&s.codePoints().noneMatch(c->Character.isWhitespace(c)||Character.isSpaceChar(c)||Character.isISOControl(c)||(c>=0xd800&&c<=0xdfff));}
    private static Instant now(DSLContext sql){return sql.fetchOne("select clock_timestamp()").get(0,OffsetDateTime.class).toInstant();}
    private static Instant time(Record r,String name){return r.get(name,OffsetDateTime.class).toInstant();}
    private static Instant min(Instant... values){return Collections.min(Arrays.asList(values));}
    private static Backend backend(DSLContext sql){var r=sql.fetchOne("select pg_backend_pid() as pid,backend_start from pg_stat_activity where pid=pg_backend_pid()");return new Backend(r.get("pid",Integer.class),r.get("backend_start",OffsetDateTime.class).toInstant());}
    private static long txid(DSLContext sql){return sql.fetchOne("select txid_current()").get(0,Long.class);}
    private static long count(DSLContext sql,String query){return sql.fetchOne(query).get(0,Long.class);}
    private static String hex(byte[] b){return HexFormat.of().formatHex(b);}
    private static void live(DSLContext sql,Instant end){if(!now(sql).isBefore(end))throw unavailable();}
    private static LegacyMigrationOtp.Failure unavailable(){return LegacyMigrationManagedProofCrypto.unavailable();}
    private static LegacyMigrationOtp.Failure denied(){return new LegacyMigrationOtp.Failure(LegacyMigrationOtp.Error.UNAUTHORIZED);}
    private static LegacyMigrationOtp.Failure conflict(){return new LegacyMigrationOtp.Failure(LegacyMigrationOtp.Error.CONFLICT);}
    private static LegacyMigrationOtp.Failure limited(){return new LegacyMigrationOtp.Failure(LegacyMigrationOtp.Error.RATE_LIMITED);}
}
