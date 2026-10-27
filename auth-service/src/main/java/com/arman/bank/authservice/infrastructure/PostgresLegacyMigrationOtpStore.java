package com.arman.bank.authservice.infrastructure;

import com.arman.bank.authservice.application.*;
import com.arman.bank.authservice.domain.LegacyMigrationOtp;
import com.arman.bank.authservice.domain.LegacyMigrationOtp.*;
import com.arman.bank.authservice.domain.LegacyMigrationOtp.Error;
import com.arman.bank.runtime.Database;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import java.util.function.*;
import java.time.*;
import java.security.MessageDigest;
import org.jooq.DSLContext;
import org.jooq.Record;

/** Initial-only migration challenge store; no registration receipts, recovery, decision or final authority.
 * No genuine provider-binding/User stage adapter is supplied by its default constructor. */
public final class PostgresLegacyMigrationOtpStore implements LegacyMigrationOtpStore {
    private static final ObjectMapper JSON=new ObjectMapper();
    private final Database database;
    private final LegacyMigrationCrypto accountCrypto;
    private final LegacyMigrationOtpCrypto crypto;
    private final MigrationBindingSqlPort binding;
    private final String actor;
    private final boolean enabled;
    public PostgresLegacyMigrationOtpStore(Database database,LegacyMigrationCrypto accountCrypto,LegacyMigrationOtpCrypto crypto,String actor){
        this(database,accountCrypto,crypto,actor,false,MigrationBindingSqlPort.unavailable());
    }
    public PostgresLegacyMigrationOtpStore(Database database,LegacyMigrationCrypto accountCrypto,LegacyMigrationOtpCrypto crypto,String actor,boolean enabled,MigrationBindingSqlPort binding){
        this.database=Objects.requireNonNull(database);this.accountCrypto=Objects.requireNonNull(accountCrypto);this.crypto=Objects.requireNonNull(crypto);this.binding=Objects.requireNonNull(binding);
        if(actor==null||!actor.matches("[A-Za-z0-9_-]{1,64}"))throw new IllegalArgumentException("Invalid migration actor");this.actor=actor;this.enabled=enabled;
    }
    private record Local(Record intent,Record generation,String email,String phone,String target,Instant now,PostgresLegacyMigrationManagedProofStore.EvidenceUse evidence){}
    @Override public <T>T inRequest(UUID migration,String capability,Function<Request,T> action){
        if(!enabled)fail(Error.UNAVAILABLE);
        if(migration==null||capability==null||!capability.matches("[A-Za-z0-9_-]{43}"))fail(Error.UNAUTHORIZED);
        return new PostgresLoginRequestScope(database).inRequest(actor,owned->inOwnedRequest(owned,migration,capability,action));
    }
    /** Reuses the caller's admitted PRIMARY session; never borrows or charges another request. */
    <T>T inOwnedRequest(PostgresLoginRequestScope.Request owned,UUID migration,String capability,Function<Request,T> action){
        if(!enabled)fail(Error.UNAVAILABLE);
        if(migration==null||capability==null||!capability.matches("[A-Za-z0-9_-]{43}"))fail(Error.UNAUTHORIZED);
        if(owned==null)fail(Error.UNAVAILABLE);owned.check();if(!actor.equals(owned.actor()))fail(Error.UNAVAILABLE);Objects.requireNonNull(action);
        Scoped scoped=new Scoped(owned,migration,capability);
        try{T result=action.apply(scoped);scoped.check();owned.verify();return result;}
        finally{scoped.closed=true;scoped.currentEvidence=null;}
    }
    void stage(PostgresLoginRequestScope.Request owned,UUID migration,String capability,LegacyMigrationOtpStore.StagePort stage){
        if(stage==null)fail(Error.UNAVAILABLE);
        inOwnedRequest(owned,migration,capability,request->{Seed seed=request.stageSeed();owned.verify();StageExchange exchange=stage.stage(seed);owned.verify();request.attachStage(exchange);return null;});
    }
    Result issue(PostgresLoginRequestScope.Request owned,UUID migration,String capability,UUID command,Purpose purpose){return inOwnedRequest(owned,migration,capability,request->request.issue(command,purpose));}
    Result consume(PostgresLoginRequestScope.Request owned,UUID migration,String capability,UUID command,UUID challenge,Purpose purpose,String code){return inOwnedRequest(owned,migration,capability,request->request.consume(command,challenge,purpose,code));}
    record BrowserCodeCommitment(String otpKeyId,String commitmentLowerHex){@Override public String toString(){return "MigrationBrowserCodeCommitment[redacted]";}}
    /** Payload comparison only. This cannot supply evidence or authorize OTP consumption. */
    BrowserCodeCommitment browserCodeCommitment(PostgresLoginRequestScope.Request owned,UUID migration,String capability,UUID challenge,Purpose purpose,String code){
        if(!enabled)fail(Error.UNAVAILABLE);if(owned==null)fail(Error.UNAVAILABLE);owned.check();if(!actor.equals(owned.actor()))fail(Error.UNAVAILABLE);
        if(migration==null||challenge==null||purpose==null||code==null||!code.matches("[0-9]{6}"))fail(Error.BAD_INPUT);
        if(capability==null||!capability.matches("[A-Za-z0-9_-]{43}"))fail(Error.UNAUTHORIZED);
        BrowserCodeCommitment result=owned.transaction(sql->{
            Record intent=sql.fetchOne("select identity_id,retention_key_id,capability_hash from legacy_migration_intents where id=?",migration);
            if(intent==null)fail(Error.UNAUTHORIZED);
            UUID owner=intent.get("identity_id",UUID.class);PostgresLegacyMigrationStore.lockOwner(sql,owner);
            if(!MessageDigest.isEqual(intent.get("capability_hash",byte[].class),accountCrypto.capability(intent.get("retention_key_id",String.class),migration,owner,capability)))fail(Error.UNAUTHORIZED);
            Record row=sql.fetchOne("select key_id from legacy_migration_otp_challenges where id=? and migration_id=? and identity_id=? and purpose=?",challenge,migration,owner,purpose.name());
            if(row==null)fail(Error.UNAUTHORIZED);String key=row.get("key_id",String.class);
            return new BrowserCodeCommitment(key,hex(crypto.hash(key,"armoney-legacy-migration-otp-code-commitment-v1",code)));
        });owned.verify();return result;
    }
    private final class Scoped implements Request {
        private final PostgresLoginRequestScope.Request owned;
        private final UUID migration;
        private final String capability;
        private final long startedNanos=System.nanoTime();
        private final Instant originalStart;
        private final Thread thread=Thread.currentThread();private boolean closed;
        private PostgresLegacyMigrationManagedProofStore.EvidenceUse currentEvidence;
        Scoped(PostgresLoginRequestScope.Request owned,UUID migration,String capability){this.owned=owned;this.migration=migration;this.capability=capability;this.originalStart=owned.transaction(PostgresLegacyMigrationOtpStore::now);}
        private void check(){owned.check();if(closed||thread!=Thread.currentThread())fail(Error.UNAVAILABLE);}
        private <T>T transaction(Function<DSLContext,T> action){check();return binding.withEvidence(owned,migration,originalStart,startedNanos,evidence->{check();currentEvidence=evidence;try{T result=owned.transaction(action);check();return result;}finally{currentEvidence=null;}});}
        @Override public Seed stageSeed(){
            check();
            Seed result=transaction(sql->{
                Local local=local(sql,migration,capability,currentEvidence);Record row=sql.fetchOne("select * from legacy_migration_otp_stages where migration_id=? for update",migration);
                if(row==null){
                    if(count(sql,"select count(*) from legacy_migration_otp_stages")>=10000)fail(Error.RATE_LIMITED);
                    UUID evidence=UUID.randomUUID();
                    String key=crypto.keyId();var selectedEmail=LegacyMigrationContactStore.candidate(sql,migration);String emailDigest=selectedEmail==null?hex(crypto.hash(key,"armoney-legacy-migration-otp-contact-v1",local.intent().get("identity_id").toString(),migration.toString(),evidence.toString(),"EMAIL",local.email())):selectedEmail.get("candidate_digest",String.class);
                    Instant created=time(local.intent(),"created_at"),deadline=created.plusSeconds(900);
                    sql.execute("""
                        insert into legacy_migration_otp_stages(migration_id,evidence_generation_id,stage_operation_id,command_id,decision_command_id,retention_key_id,target_binding_hash,email_digest,created_at,intent_deadline)
                        values(?,?,?,?,?,?,?,?,cast(? as timestamptz),cast(? as timestamptz))
                        """,migration,evidence,UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),key,local.target(),emailDigest,created.toString(),deadline.toString());
                    row=sql.fetchOne("select * from legacy_migration_otp_stages where migration_id=?",migration);
                }
                requireStageBaseline(sql,local,row);Seed seed=seed(sql,local,row);finalLocal(sql,local);return seed;
            });owned.verify();return result;
        }
        @Override public void attachStage(StageExchange exchange){
            check();
            owned.verify();transaction(sql->{
                Local local=local(sql,migration,capability,currentEvidence);Record row=requiredStage(sql,local);Seed seed=seed(sql,local,row);
                LegacyMigrationOtp.validateStage(seed,exchange);var ack=exchange.acknowledgement();
                Record original=sql.fetchOne("select * from legacy_migration_otp_stage_acks where migration_id=?",migration);
                String encoded=json(orderedAck(ack));
                if(original!=null){if(!original.get("ack_json",String.class).equals(encoded))fail(Error.CONFLICT);finalLocal(sql,local);return null;}
                String bindingHash=bindingHash(sql,local,row,ack);
                sql.execute("""
                    insert into legacy_migration_otp_stage_acks(migration_id,profile_id,baseline_generation,contact_mutation_revision,projection_present,projection_revision,phone_contact_version,verification_epoch,stage_ack_hash,baseline_hash,payload_hash,binding_hash,ack_json,binding_version)
                    values(?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                    """,migration,LegacyMigrationOtp.uuid(ack.get("profile_id")),LegacyMigrationOtp.number(ack.get("baseline_generation"),false),LegacyMigrationOtp.number(ack.get("contact_mutation_revision"),true),ack.get("projection_present"),
                    ack.get("projection_revision"),ack.get("phone_contact_version"),ack.get("verification_epoch"),ack.get("stage_ack_hash"),ack.get("baseline_hash"),ack.get("payload_hash"),bindingHash,encoded,LegacyMigrationContactStore.candidate(sql,migration)==null?1:2);
                for(Purpose purpose:Purpose.values()){
                    String contact=purpose==Purpose.EMAIL?local.email():local.phone();
                    String digest=hex(crypto.hash(row.get("retention_key_id",String.class),"armoney-legacy-migration-otp-contact-v1",local.intent().get("identity_id").toString(),migration.toString(),row.get("evidence_generation_id").toString(),purpose.name(),contact));
                    sql.execute("insert into legacy_migration_otp_candidates values(?,?,?,1,?)",UUID.randomUUID(),migration,purpose.name(),digest);
                }
                finalLocal(sql,local);return null;
            });owned.verify();
        }
        @Override public Result issue(UUID command,Purpose purpose){
            check();
            validate(command,purpose);
            return transaction(sql->{
                Local local=local(sql,migration,capability,currentEvidence);Record stage=requiredStage(sql,local),ack=requiredAck(sql,local,stage);
                return command(sql,local,ack,command,"issue",purpose,null,null,()->{
                    if(sql.fetchOne("select id from legacy_migration_otp_receipts where migration_id=? and purpose=?",migration,purpose.name())!=null)return Result.rejected(Error.CONFLICT);
                    var candidate=sql.fetchOne("select * from legacy_migration_otp_candidates where migration_id=? and purpose=? for update",migration,purpose.name());
                    if(candidate==null)fail(Error.UNAVAILABLE);
                    var recent=sql.fetchOne("""
                        select max(created_at) as last,count(*) filter(where created_at>cast(? as timestamptz)) as hourly,
                         count(*) filter(where created_at>cast(? as timestamptz)) as daily from legacy_migration_otp_challenges where identity_id=? and purpose=?
                        """,local.now().minusSeconds(3600).toString(),local.now().minusSeconds(86400).toString(),local.intent().get("identity_id",UUID.class),purpose.name());
                    if((recent.get("last")!=null&&local.now().isBefore(time(recent,"last").plusSeconds(60)))||recent.get("hourly",Long.class)>=3||recent.get("daily",Long.class)>=10
                        ||!PostgresOtpIssuanceBudget.available(sql,local.now())||count(sql,"select count(*) from legacy_migration_otp_outbox")>=100000)return Result.rejected(Error.RATE_LIMITED);
                    UUID challenge=UUID.randomUUID(),delivery=UUID.randomUUID();Instant expiry=min(local.now().plusSeconds(300),time(stage,"intent_deadline"));
                    if(!local.now().isBefore(expiry))return Result.rejected(Error.CONFLICT);
                    PostgresOtpIssuanceBudget.reserve(sql,challenge,"MIGRATION",local.now());
                    sql.execute("update legacy_migration_otp_outbox set state='EXPIRED',ciphertext=null,nonce=null,fence=null where migration_id=? and challenge_id in(select id from legacy_migration_otp_challenges where migration_id=? and purpose=? and state in('PENDING_DELIVERY','OPEN'))",migration,migration,purpose.name());
                    sql.execute("update legacy_migration_otp_challenges set state='SUPERSEDED',digest=null where migration_id=? and purpose=? and state in('PENDING_DELIVERY','OPEN')",migration,purpose.name());
                    var envelope=new LegacyMigrationOtpCrypto.Envelope(delivery,challenge,candidate.get("id",UUID.class),purpose,ack.get("binding_hash",String.class),candidate.get("contact_digest",String.class),expiry);
                    String code=crypto.code();String key=crypto.keyId();
                    sql.execute("""
                        insert into legacy_migration_otp_challenges(id,migration_id,candidate_id,identity_id,purpose,binding_hash,key_id,digest,state,created_at,expires_at,resend_at)
                        values(?,?,?,?,?,?,?,?,'PENDING_DELIVERY',cast(? as timestamptz),cast(? as timestamptz),cast(? as timestamptz))
                        """,challenge,migration,candidate.get("id",UUID.class),local.intent().get("identity_id",UUID.class),purpose.name(),ack.get("binding_hash"),key,crypto.codeDigest(key,envelope,1,code),local.now().toString(),expiry.toString(),local.now().plusSeconds(60).toString());
                    boolean inserted=false;
                    for(int attempt=0;attempt<3&&!inserted;attempt++){
                        var encrypted=crypto.encrypt(envelope,purpose==Purpose.EMAIL?local.email():local.phone(),code);
                        inserted=sql.fetchOne("""
                            insert into legacy_migration_otp_outbox(id,challenge_id,migration_id,key_id,nonce,ciphertext,state,available_at,expires_at)
                            values(?,?,?,?,?,?,'PENDING',cast(? as timestamptz),cast(? as timestamptz)) on conflict(key_id,nonce) do nothing returning id
                            """,delivery,challenge,migration,encrypted.keyId(),encrypted.nonce(),encrypted.ciphertext(),local.now().toString(),expiry.toString())!=null;
                    }
                    if(!inserted)fail(Error.UNAVAILABLE);
                    finalLocal(sql,local);live(sql,expiry);return new Result(challenge,null,"PENDING_DELIVERY",expiry,local.now().plusSeconds(60),5,null);
                });
            });
        }
        @Override public Result consume(UUID command,UUID challenge,Purpose purpose,String code){
            check();
            validate(command,purpose);if(challenge==null||code==null||!code.matches("[0-9]{6}"))fail(Error.BAD_INPUT);
            return transaction(sql->{
                Local local=local(sql,migration,capability,currentEvidence);Record stage=requiredStage(sql,local),ack=requiredAck(sql,local,stage);
                return command(sql,local,ack,command,"consume",purpose,challenge,code,()->{
                    Record row=sql.fetchOne("select * from legacy_migration_otp_challenges where id=? and migration_id=? and purpose=? for update",challenge,migration,purpose.name());
                    if(row==null||!Objects.equals(row.get("binding_hash"),ack.get("binding_hash")))return Result.rejected(Error.CONFLICT);
                    if(!now(sql).isBefore(time(row,"expires_at"))){retire(sql,challenge,"EXPIRED");return Result.rejected(Error.CONFLICT);}
                    if(!"OPEN".equals(row.get("state")))return Result.rejected(Error.CONFLICT);
                    Record candidate=sql.fetchOne("select * from legacy_migration_otp_candidates where id=? and migration_id=? and purpose=?",row.get("candidate_id",UUID.class),migration,purpose.name());
                    if(candidate==null)fail(Error.CONFLICT);
                    Record delivery=sql.fetchOne("select id from legacy_migration_otp_outbox where challenge_id=?",challenge);if(delivery==null)fail(Error.UNAVAILABLE);
                    var envelope=new LegacyMigrationOtpCrypto.Envelope(delivery.get("id",UUID.class),challenge,candidate.get("id",UUID.class),purpose,row.get("binding_hash",String.class),candidate.get("contact_digest",String.class),time(row,"expires_at"));
                    byte[] supplied=crypto.codeDigest(row.get("key_id",String.class),envelope,1,code);
                    if(!MessageDigest.isEqual(row.get("digest",byte[].class),supplied)){
                        int attempts=Math.addExact(row.get("attempts",Integer.class),1);
                        sql.execute("update legacy_migration_otp_challenges set attempts=?,state=?,digest=case when ?=5 then null else digest end where id=?",attempts,attempts==5?"LOCKED":"OPEN",attempts,challenge);
                        if(attempts==5)retirePayload(sql,challenge,"FAILED");finalLocal(sql,local);return Result.rejected(Error.CONFLICT);
                    }
                    UUID receipt=UUID.randomUUID();
                    sql.execute("insert into legacy_migration_otp_receipts values(?,?,?,?,?,1,?,cast(? as timestamptz))",receipt,migration,challenge,candidate.get("id",UUID.class),purpose.name(),ack.get("binding_hash"),now(sql).toString());
                    if(purpose==Purpose.EMAIL)binding.emailConsumed(sql,bindingQuery(local),currentEvidence,receipt,challenge,command);
                    sql.execute("update legacy_migration_otp_challenges set state='CONSUMED',digest=null where id=?",challenge);retirePayload(sql,challenge,"ACCEPTED");
                    finalLocal(sql,local);live(sql,time(row,"expires_at"));return new Result(challenge,receipt,"CONSUMED",time(row,"expires_at"),null,5-row.get("attempts",Integer.class),null);
                });
            });
        }
    }
    /** Caller owns this exact PRIMARY transaction and the original current-evidence capability. */
    FinalizationSnapshot requireFinalization(DSLContext sql,UUID migration,String capability,PostgresLegacyMigrationManagedProofStore.EvidenceUse evidence){
        if(!enabled||sql==null||migration==null||capability==null||evidence==null)throw new Failure(Error.UNAVAILABLE);
        PostgresVerificationProjectionStore.requireLocked(sql);
        Local local=local(sql,migration,capability,evidence);Record stage=requiredStage(sql,local),ack=requiredAck(sql,local,stage);Seed seed=seed(sql,local,stage);
        Map<String,Object> acknowledged;
        try{acknowledged=JSON.readValue(ack.get("ack_json",String.class),new com.fasterxml.jackson.core.type.TypeReference<LinkedHashMap<String,Object>>(){});}catch(Exception invalid){throw new Failure(Error.UNAVAILABLE);}
        var request=new LinkedHashMap<String,Object>();
        request.put("protocol_version",1);request.put("migration_id",migration.toString());request.put("stage_operation_id",seed.stageOperationId().toString());request.put("command_id",seed.commandId().toString());request.put("decision_command_id",seed.decisionCommandId().toString());request.put("identity_id",seed.identityId().toString());request.put("profile_id",acknowledged.get("profile_id"));request.put("canonical_phone",seed.phone());request.put("email_contact_version",seed.emailVersion());request.put("email_digest",seed.emailDigest());request.put("policy_version",seed.policyVersion());request.put("created_at",seed.createdAt().toEpochMilli());request.put("authorization_deadline",seed.intentDeadline().toEpochMilli());request.put("payload_hash",acknowledged.get("payload_hash"));
        LegacyMigrationOtp.validateStage(seed,new StageExchange(request,acknowledged));
        if(!bindingHash(sql,local,stage,acknowledged).equals(ack.get("binding_hash")))fail(Error.CONFLICT);
        Record email=finalReceipt(sql,local,ack,Purpose.EMAIL),phone=finalReceipt(sql,local,ack,Purpose.PHONE);
        if(email.get("id").equals(phone.get("id"))||email.get("challenge_id").equals(phone.get("challenge_id"))||email.get("candidate_id").equals(phone.get("candidate_id")))fail(Error.CONFLICT);
        var baseline=sql.fetchOne("select * from legacy_migration_contact_baselines where migration_id=?",migration);
        var candidate=LegacyMigrationContactStore.candidate(sql,migration);
        var transition=sql.fetchOne("select * from legacy_migration_contact_transitions where migration_id=?",migration);
        if(baseline!=null&&"ABSENT_OWNER_CONTACT".equals(baseline.get("source"))&&(transition==null||!email.get("id").equals(transition.get("receipt_id"))))fail(Error.CONFLICT);
        Instant deadline=min(evidence.deadline(),time(local.intent(),"authorization_deadline"),seed.intentDeadline(),time(email,"challenge_expiry"),time(phone,"challenge_expiry"));
        finalLocal(sql,local);live(sql,deadline);
        long tx=sql.fetchOne("select txid_current()").get(0,Long.class);Thread owner=Thread.currentThread();
        java.util.function.Consumer<DSLContext> guard=current->{
            if(current!=sql||Thread.currentThread()!=owner||Thread.currentThread().isInterrupted()||current.fetchOne("select txid_current()").get(0,Long.class).longValue()!=tx)fail(Error.UNAVAILABLE);
            requireFinalization(current,migration,capability,evidence);live(current,deadline);
        };
        return new FinalizationSnapshot(seed,request,acknowledged,ack.get("binding_hash",String.class),email,phone,local,
            baseline==null?"CREDENTIAL_EMAIL":baseline.get("source",String.class),baseline==null?local.intent().get("email_generation",Long.class):baseline.get("baseline_generation",Long.class),baseline==null?seed.emailDigest():baseline.get("baseline_digest",String.class),candidate==null?null:candidate.get("candidate_digest",String.class),transition==null?null:transition.get("transition_hash",String.class),deadline,guard);
    }
    private Record finalReceipt(DSLContext sql,Local local,Record ack,Purpose purpose){
        UUID migration=local.intent().get("id",UUID.class);
        var rows=sql.fetch("""
            select r.*,c.expires_at as challenge_expiry from legacy_migration_otp_receipts r
            join legacy_migration_otp_challenges c on c.id=r.challenge_id
            join legacy_migration_otp_candidates p on p.id=r.candidate_id
            where r.migration_id=? and r.purpose=? and c.migration_id=r.migration_id and p.migration_id=r.migration_id
            and c.candidate_id=r.candidate_id and c.purpose=r.purpose and p.purpose=r.purpose
            and c.identity_id=? and c.state='CONSUMED' and c.digest is null
            and c.binding_hash=r.binding_hash and r.binding_hash=? and r.receipt_generation=p.receipt_generation and r.receipt_generation=1
            for update of r,c,p
            """,migration,purpose.name(),local.intent().get("identity_id",UUID.class),ack.get("binding_hash"));
        if(rows.size()!=1)fail(Error.CONFLICT);Record receipt=rows.get(0);live(sql,time(receipt,"challenge_expiry"));return receipt;
    }
    /** Private constructor: immutable scalar snapshot plus original caller-owned validation guard. */
    static final class FinalizationSnapshot {
        private final Seed seed;private final Map<String,Object> request,ack;private final String binding,source,baselineDigest,candidateDigest,transitionHash,target;
        private final UUID emailReceipt,emailChallenge,emailCandidate,phoneReceipt,phoneChallenge,phoneCandidate;
        private final long emailReceiptGeneration,phoneReceiptGeneration,ownerGeneration,modeGeneration,emailGeneration,baselineGeneration;
        private final Instant deadline;private final java.util.function.Consumer<DSLContext> guard;
        private FinalizationSnapshot(Seed seed,Map<String,Object> request,Map<String,Object> ack,String binding,Record email,Record phone,Local local,String source,long baselineGeneration,String baselineDigest,String candidateDigest,String transitionHash,Instant deadline,java.util.function.Consumer<DSLContext> guard){
            this.seed=seed;this.request=Collections.unmodifiableMap(new LinkedHashMap<>(request));this.ack=Collections.unmodifiableMap(new LinkedHashMap<>(ack));this.binding=binding;
            emailReceipt=email.get("id",UUID.class);emailChallenge=email.get("challenge_id",UUID.class);emailCandidate=email.get("candidate_id",UUID.class);phoneReceipt=phone.get("id",UUID.class);phoneChallenge=phone.get("challenge_id",UUID.class);phoneCandidate=phone.get("candidate_id",UUID.class);emailReceiptGeneration=email.get("receipt_generation",Long.class);phoneReceiptGeneration=phone.get("receipt_generation",Long.class);
            ownerGeneration=local.intent().get("owner_generation",Long.class);modeGeneration="LEGACY_OIDC_REAUTH".equals(local.intent().get("proof_mode"))?local.intent().get("association_generation",Long.class):local.intent().get("credential_generation",Long.class);emailGeneration=local.intent().get("email_generation",Long.class);target=local.target();this.source=source;this.baselineGeneration=baselineGeneration;this.baselineDigest=baselineDigest;this.candidateDigest=candidateDigest;this.transitionHash=transitionHash;this.deadline=deadline;this.guard=guard;
        }
        Seed seed(){return seed;}Map<String,Object> stageRequest(){return request;}Map<String,Object> stageAcknowledgement(){return ack;}String bindingHash(){return binding;}
        UUID emailReceiptId(){return emailReceipt;}UUID emailChallengeId(){return emailChallenge;}UUID emailCandidateId(){return emailCandidate;}long emailReceiptGeneration(){return emailReceiptGeneration;}
        UUID phoneReceiptId(){return phoneReceipt;}UUID phoneChallengeId(){return phoneChallenge;}UUID phoneCandidateId(){return phoneCandidate;}long phoneReceiptGeneration(){return phoneReceiptGeneration;}
        long ownerGeneration(){return ownerGeneration;}long modeGeneration(){return modeGeneration;}long emailGeneration(){return emailGeneration;}String targetBindingHash(){return target;}
        String source(){return source;}long baselineGeneration(){return baselineGeneration;}String baselineDigest(){return baselineDigest;}String candidateDigest(){return candidateDigest;}String contactTransitionHash(){return transitionHash;}Instant deadline(){return deadline;}
        void requireCurrent(DSLContext sql){guard.accept(sql);}
        void requireOwnPromotion(DSLContext sql,PostgresMigrationBankingEligibilityStore.Promotion promotion){
            if(promotion==null)throw new Failure(Error.UNAVAILABLE);
            promotion.requireOriginalSnapshot(sql,this);promotion.requireCurrent(sql);
        }
        @Override public String toString(){return "MigrationFinalizationSnapshot[redacted]";}
    }
    private Local local(DSLContext sql,UUID migration,String capability,PostgresLegacyMigrationManagedProofStore.EvidenceUse evidence){
        var routing=sql.fetchOne("select identity_id from legacy_migration_intents where id=?",migration);if(routing==null)fail(Error.UNAUTHORIZED);
        UUID owner=routing.get("identity_id",UUID.class);Record generation=PostgresLegacyMigrationStore.lockOwner(sql,owner);
        Record preliminary=sql.fetchOne("select * from legacy_migration_intents where id=?",migration);if(preliminary==null)fail(Error.UNAUTHORIZED);
        PostgresLegacyMigrationStore.requireModeCurrent(sql,preliminary,generation);
        binding.lockTarget(sql,migration,evidence);
        Record intent=sql.fetchOne("select * from legacy_migration_intents where id=? for update",migration);if(intent==null)fail(Error.UNAUTHORIZED);
        if(capability!=null&&!MessageDigest.isEqual(intent.get("capability_hash",byte[].class),accountCrypto.capability(intent.get("retention_key_id",String.class),migration,owner,capability)))fail(Error.UNAUTHORIZED);
        for(String column:List.of("owner_generation","credential_generation","email_generation"))if(!Objects.equals(generation.get(column,Long.class),intent.get(column,Long.class)))fail(Error.CONFLICT);
        if(sql.fetchOne("select identity_id from managed_registration_principals where identity_id=?",owner)!=null)fail(Error.UNAUTHORIZED);
        Record identity=sql.fetchOne("select email,registration_phone from identities where id=?",owner);
        String email=LegacyMigrationContactStore.email(sql,intent),phone=identity.get("registration_phone",String.class);
        if(!Objects.equals(email,AuthService.normalizeEmail(email))||!AuthService.canonicalPhone(phone))fail(Error.CONFLICT);
        Instant now=now(sql);live(sql,time(intent,"authorization_deadline"));
        String target=binding.requireCurrent(sql,new MigrationBindingSqlPort.BindingQuery(migration,owner,evidence.receiptId(),evidence.reservationId(),com.arman.bank.authservice.domain.LegacyMigration.ProofMode.valueOf(intent.get("proof_mode",String.class)),generation.get("owner_generation",Long.class),"LEGACY_OIDC_REAUTH".equals(intent.get("proof_mode"))?intent.get("association_generation",Long.class):generation.get("credential_generation",Long.class),generation.get("email_generation",Long.class),time(intent,"authorization_deadline")),evidence);
        LegacyMigrationOtp.digest(target);return new Local(intent,generation,email,phone,target,now,evidence);
    }
    private MigrationBindingSqlPort.BindingQuery bindingQuery(Local local){var i=local.intent();return new MigrationBindingSqlPort.BindingQuery(i.get("id",UUID.class),i.get("identity_id",UUID.class),local.evidence().receiptId(),local.evidence().reservationId(),com.arman.bank.authservice.domain.LegacyMigration.ProofMode.valueOf(i.get("proof_mode",String.class)),i.get("owner_generation",Long.class),"LEGACY_OIDC_REAUTH".equals(i.get("proof_mode"))?i.get("association_generation",Long.class):i.get("credential_generation",Long.class),i.get("email_generation",Long.class),time(i,"authorization_deadline"));}
    private Record requiredStage(DSLContext sql,Local local){var stage=sql.fetchOne("select * from legacy_migration_otp_stages where migration_id=? for update",local.intent().get("id",UUID.class));if(stage==null)throw new Failure(Error.UNAVAILABLE);requireStageBaseline(sql,local,stage);return stage;}
    private void requireStageBaseline(DSLContext sql,Local local,Record stage){if(!local.target().equals(stage.get("target_binding_hash")))fail(Error.CONFLICT);var selectedEmail=LegacyMigrationContactStore.candidate(sql,local.intent().get("id",UUID.class));String digest=selectedEmail==null?hex(crypto.hash(stage.get("retention_key_id",String.class),"armoney-legacy-migration-otp-contact-v1",local.intent().get("identity_id").toString(),local.intent().get("id").toString(),stage.get("evidence_generation_id").toString(),"EMAIL",local.email())):selectedEmail.get("candidate_digest",String.class);if(!digest.equals(stage.get("email_digest")))fail(Error.CONFLICT);}
    private Record requiredAck(DSLContext sql,Local local,Record stage){Record ack=sql.fetchOne("select * from legacy_migration_otp_stage_acks where migration_id=?",local.intent().get("id",UUID.class));if(ack==null)throw new Failure(Error.UNAVAILABLE);if(ack.get("binding_version",Integer.class)!=(LegacyMigrationContactStore.candidate(sql,local.intent().get("id",UUID.class))==null?1:2))fail(Error.CONFLICT);live(sql,time(stage,"intent_deadline"));return ack;}
    private Seed seed(DSLContext sql,Local local,Record stage){return new Seed(local.intent().get("id",UUID.class),stage.get("stage_operation_id",UUID.class),stage.get("command_id",UUID.class),stage.get("decision_command_id",UUID.class),local.intent().get("identity_id",UUID.class),local.phone(),LegacyMigrationContactStore.candidate(sql,local.intent().get("id",UUID.class))==null?local.intent().get("email_generation",Long.class):1L,stage.get("email_digest",String.class),1,time(stage,"created_at"),time(stage,"intent_deadline"),time(local.intent(),"authorization_deadline"));}
    private String bindingHash(DSLContext sql,Local local,Record stage,Map<String,Object> ack){
        var fields=new ArrayList<String>();fields.add("1");fields.add(local.intent().get("id").toString());fields.add(stage.get("evidence_generation_id").toString());fields.add(local.intent().get("identity_id").toString());
        for(String key:List.of("profile_id"))fields.add(String.valueOf(ack.get(key)));
        fields.add(stage.get("stage_operation_id").toString());
        for(String key:List.of("stage_ack_hash","baseline_hash","baseline_generation","contact_mutation_revision","projection_present","projection_revision","phone_contact_version","verification_epoch"))fields.add(String.valueOf(ack.get(key)));
        fields.add(local.intent().get("email_generation").toString());fields.add(stage.get("email_digest").toString());fields.add(local.intent().get("owner_generation").toString());fields.add(local.intent().get("proof_mode").toString());fields.add(("LEGACY_OIDC_REAUTH".equals(local.intent().get("proof_mode"))?local.intent().get("association_generation"):local.intent().get("credential_generation")).toString());fields.add(local.target());fields.add("1");fields.add(Long.toString(time(stage,"intent_deadline").toEpochMilli()));fields.add(String.valueOf(ack.get("authorization_deadline")));
        String v1=hex(crypto.hash(stage.get("retention_key_id",String.class),"armoney-legacy-migration-otp-binding-v1",fields.toArray(String[]::new)));
        Record candidate=LegacyMigrationContactStore.candidate(sql,local.intent().get("id",UUID.class));if(candidate==null)return v1;
        Record baseline=sql.fetchOne("select * from legacy_migration_contact_baselines where migration_id=?",local.intent().get("id",UUID.class));if(baseline==null)fail(Error.CONFLICT);
        return hex(crypto.hash(stage.get("retention_key_id",String.class),"armoney-legacy-migration-otp-binding-v2","2",v1,baseline.get("source",String.class),baseline.get("baseline_generation").toString(),LegacyMigrationContactStore.none(baseline.get("baseline_digest",String.class)),candidate.get("candidate_id").toString(),"1",candidate.get("candidate_digest",String.class)));
    }
    private Result command(DSLContext sql,Local local,Record ack,UUID id,String operation,Purpose purpose,UUID challenge,String code,Supplier<Result> action){
        UUID migration=local.intent().get("id",UUID.class);Record original=sql.fetchOne("select * from legacy_migration_otp_commands where migration_id=? and writer=? and command_id=?",migration,actor,id);
        String key=original==null?crypto.keyId():original.get("key_id",String.class);
        String commitment=code==null?"":hex(crypto.hash(key,"armoney-legacy-migration-otp-code-commitment-v1",code));
        byte[] payload=crypto.hash(key,"armoney-legacy-migration-otp-command-v1",actor,migration.toString(),id.toString(),operation,ack.get("binding_hash",String.class),purpose.name(),challenge==null?"":challenge.toString(),commitment);
        if(original!=null){if(!MessageDigest.isEqual(payload,original.get("payload_hash",byte[].class)))fail(Error.CONFLICT);Result replay=decode(original.get("result_json",String.class));finalLocal(sql,local);if(replay.error()==null&&replay.expiresAt()!=null)live(sql,replay.expiresAt());return replay;}
        if(count(sql,"select count(*) from legacy_migration_otp_commands")>=100000)fail(Error.RATE_LIMITED);
        Result result=action.get();
        sql.execute("insert into legacy_migration_otp_commands values(?,?,?,?,?,?,cast(? as timestamptz))",migration,actor,id,key,payload,encode(result),now(sql).toString());
        finalLocal(sql,local);if(result.error()==null&&result.expiresAt()!=null)live(sql,result.expiresAt());return result;
    }
    private void finalLocal(DSLContext sql,Local expected){Local current=local(sql,expected.intent().get("id",UUID.class),null,expected.evidence());if(!current.target().equals(expected.target()))fail(Error.CONFLICT);live(sql,time(expected.intent(),"authorization_deadline"));}
    private static void retire(DSLContext sql,UUID challenge,String state){sql.execute("update legacy_migration_otp_challenges set state=?,digest=null where id=?",state,challenge);retirePayload(sql,challenge,(state.equals("LOCKED")||state.equals("DELIVERY_FAILED"))?"FAILED":"EXPIRED");}
    private static void retirePayload(DSLContext sql,UUID challenge,String state){sql.execute("update legacy_migration_otp_outbox set state=?,ciphertext=null,nonce=null,fence=null where challenge_id=?",state,challenge);}
    public record DeliveryLease(UUID deliveryId,UUID fence,LegacyMigrationOtpCrypto.Envelope envelope,LegacyMigrationOtpCrypto.Encrypted encrypted,Instant presendDeadline) {
        @Override public String toString(){return "MigrationOtpDelivery[redacted]";}
    }
    private <T>T deliveryTransaction(PostgresLoginRequestScope.Request owned,UUID deliveryId,BiFunction<DSLContext,PostgresLegacyMigrationManagedProofStore.EvidenceUse,T> action){
        long began=System.nanoTime();
        var route=owned.transaction(sql->{
            Record row=sql.fetchOne("select migration_id from legacy_migration_otp_outbox where id=?",deliveryId);
            return row==null?null:row.get("migration_id",UUID.class);
        });
        if(route==null)return owned.transaction(sql->action.apply(sql,null));
        Instant start=owned.transaction(PostgresLegacyMigrationOtpStore::now);
        return binding.withEvidence(owned,route,start,began,evidence->owned.transaction(sql->action.apply(sql,evidence)));
    }
    record DeliveryRoute(UUID deliveryId,UUID migrationId){}
    /** One eligible identity is chosen once. Routing is not authority and is rechecked under canonical locks. */
    static DeliveryRoute eligibleDelivery(DSLContext sql){
        var route=sql.fetchOne("""
            select d.id,d.migration_id from legacy_migration_otp_outbox d join legacy_migration_otp_challenges c on c.id=d.challenge_id
             join legacy_migration_intents i on i.id=d.migration_id
             join legacy_migration_otp_stages s on s.migration_id=d.migration_id
             join legacy_migration_managed_receipts r on r.migration_id=d.migration_id
            where c.state='PENDING_DELIVERY' and d.expires_at>clock_timestamp() and d.attempts<3
             and i.authorization_deadline>clock_timestamp() and s.intent_deadline>clock_timestamp()
             and r.account_deadline>clock_timestamp() and r.attempt_deadline>clock_timestamp() and r.token_expires_at>clock_timestamp()
             and ((d.state in('PENDING','RETRY_WAIT') and d.available_at<=clock_timestamp()) or (d.state='LEASED' and d.lease_until<=clock_timestamp()))
            order by d.available_at,d.id limit 1
            """);
        return route==null?null:new DeliveryRoute(route.get("id",UUID.class),route.get("migration_id",UUID.class));
    }
    public Optional<DeliveryLease> claimDelivery(){
        if(!enabled)return Optional.empty();
        return new PostgresLoginRequestScope(database).inRequest(actor,owned->{
            long began=System.nanoTime();
            DeliveryRoute route=owned.transaction(PostgresLegacyMigrationOtpStore::eligibleDelivery);
            if(route==null)return Optional.empty();
            Instant start=owned.transaction(PostgresLegacyMigrationOtpStore::now);
            return binding.withEvidence(owned,route.migrationId(),start,began,evidence->owned.transaction(sql->{
            Local current=local(sql,route.migrationId(),null,evidence);Record stage=requiredStage(sql,current),ack=requiredAck(sql,current,stage);
            var candidate=sql.fetchOne("select c.* from legacy_migration_otp_candidates c join legacy_migration_otp_challenges x on x.candidate_id=c.id join legacy_migration_otp_outbox d on d.challenge_id=x.id where d.id=? for update of c skip locked",route.deliveryId());
            var challenge=sql.fetchOne("select c.* from legacy_migration_otp_challenges c join legacy_migration_otp_outbox d on d.challenge_id=c.id where d.id=? for update of c skip locked",route.deliveryId());
            var delivery=sql.fetchOne("select * from legacy_migration_otp_outbox where id=? for update skip locked",route.deliveryId());
            if(candidate==null||challenge==null||delivery==null||!"PENDING_DELIVERY".equals(challenge.get("state"))||delivery.get("attempts",Integer.class)>=3||!route.migrationId().equals(delivery.get("migration_id",UUID.class)))return Optional.empty();
            Instant checked=now(sql);
            boolean available=(Set.of("PENDING","RETRY_WAIT").contains(delivery.get("state",String.class))&&!checked.isBefore(time(delivery,"available_at")))
                ||("LEASED".equals(delivery.get("state"))&&delivery.get("lease_until")!=null&&!checked.isBefore(time(delivery,"lease_until")));
            if(!available||!checked.isBefore(time(delivery,"expires_at")))return Optional.empty();
            UUID fence=UUID.randomUUID();
            Instant leaseUntil=min(checked.plusSeconds(30),time(delivery,"expires_at"));
            Instant presendDeadline=min(time(current.intent(),"authorization_deadline"),time(stage,"intent_deadline"),time(delivery,"expires_at"),leaseUntil);
            live(sql,presendDeadline);
            sql.execute("update legacy_migration_otp_outbox set state='LEASED',attempts=attempts+1,fence=?,lease_until=cast(? as timestamptz) where id=?",fence,leaseUntil.toString(),delivery.get("id",UUID.class));
            var envelope=new LegacyMigrationOtpCrypto.Envelope(delivery.get("id",UUID.class),challenge.get("id",UUID.class),candidate.get("id",UUID.class),Purpose.valueOf(challenge.get("purpose",String.class)),ack.get("binding_hash",String.class),candidate.get("contact_digest",String.class),time(delivery,"expires_at"));
            var encrypted=new LegacyMigrationOtpCrypto.Encrypted(delivery.get("key_id",String.class),delivery.get("nonce",byte[].class),delivery.get("ciphertext",byte[].class));
            finalLocal(sql,current);return Optional.of(new DeliveryLease(delivery.get("id",UUID.class),fence,envelope,encrypted,presendDeadline));
            }));
        });
    }
    /** Fresh claim/binding validation immediately before network I/O; never performs I/O in this transaction. */
    public boolean validateDelivery(DeliveryLease lease){return validateDeliveryDeadline(lease).isPresent();}
    public Optional<Instant> validateDeliveryDeadline(DeliveryLease lease){
        if(!enabled||lease==null||Thread.currentThread().isInterrupted())return Optional.empty();
        return new PostgresLoginRequestScope(database).inRequest(actor,owned->deliveryTransaction(owned,lease.deliveryId(),(sql,evidence)->{
            var routing=sql.fetchOne("select migration_id,challenge_id from legacy_migration_otp_outbox where id=?",lease.deliveryId());if(routing==null)return Optional.empty();
            Local current=local(sql,routing.get("migration_id",UUID.class),null,evidence);
            Record stage=requiredStage(sql,current),ack=requiredAck(sql,current,stage);
            Record candidate=sql.fetchOne("select * from legacy_migration_otp_candidates where id=? for update",lease.envelope().candidateId());
            Record challenge=sql.fetchOne("select * from legacy_migration_otp_challenges where id=? for update",routing.get("challenge_id",UUID.class));
            Record delivery=sql.fetchOne("select * from legacy_migration_otp_outbox where id=? for update",lease.deliveryId());
            if(candidate==null||challenge==null||delivery==null||!"LEASED".equals(delivery.get("state"))||!"PENDING_DELIVERY".equals(challenge.get("state"))
                ||!lease.fence().equals(delivery.get("fence",UUID.class))||!lease.envelope().challengeId().equals(challenge.get("id",UUID.class))
                ||!candidate.get("id",UUID.class).equals(challenge.get("candidate_id",UUID.class))
                ||!lease.envelope().purpose().name().equals(challenge.get("purpose",String.class))
                ||!lease.envelope().bindingHash().equals(ack.get("binding_hash",String.class))
                ||!lease.envelope().bindingHash().equals(challenge.get("binding_hash",String.class))
                ||!lease.envelope().contactDigest().equals(candidate.get("contact_digest",String.class))
                ||!lease.envelope().expiresAt().equals(time(delivery,"expires_at"))
                ||!lease.encrypted().keyId().equals(delivery.get("key_id",String.class))
                ||!Arrays.equals(lease.encrypted().nonce(),delivery.get("nonce",byte[].class))
                ||!Arrays.equals(lease.encrypted().ciphertext(),delivery.get("ciphertext",byte[].class)))return Optional.empty();
            Instant bound=min(time(current.intent(),"authorization_deadline"),time(stage,"intent_deadline"),time(delivery,"expires_at"),time(delivery,"lease_until"));
            if(!bound.equals(lease.presendDeadline()))return Optional.empty();
            finalLocal(sql,current);live(sql,bound);return Optional.of(min(bound,evidence.deadline()));
        }));
    }
    public boolean finishDelivery(UUID id,UUID fence,boolean accepted){
        if(!enabled||id==null||fence==null)return false;
        return new PostgresLoginRequestScope(database).inRequest(actor,owned->deliveryTransaction(owned,id,(sql,evidence)->{
            var routing=sql.fetchOne("select migration_id,challenge_id from legacy_migration_otp_outbox where id=?",id);if(routing==null)return false;
            Local current=local(sql,routing.get("migration_id",UUID.class),null,evidence);
            Record challenge=sql.fetchOne("select * from legacy_migration_otp_challenges where id=? for update",routing.get("challenge_id",UUID.class));
            Record delivery=sql.fetchOne("select * from legacy_migration_otp_outbox where id=? for update",id);
            if(delivery==null||challenge==null||!"LEASED".equals(delivery.get("state"))||!fence.equals(delivery.get("fence",UUID.class))
                ||!now(sql).isBefore(time(delivery,"lease_until"))||!now(sql).isBefore(time(delivery,"expires_at"))||!"PENDING_DELIVERY".equals(challenge.get("state")))return false;
            if(accepted){retirePayload(sql,challenge.get("id",UUID.class),"ACCEPTED");sql.execute("update legacy_migration_otp_challenges set state='OPEN' where id=?",challenge.get("id",UUID.class));}
            else if(delivery.get("attempts",Integer.class)>=3)retire(sql,challenge.get("id",UUID.class),"DELIVERY_FAILED");
            else sql.execute("update legacy_migration_otp_outbox set state='RETRY_WAIT',fence=null,available_at=cast(? as timestamptz) where id=?",now(sql).plusSeconds(delivery.get("attempts",Integer.class)==1?5:15).toString(),id);
            finalLocal(sql,current);live(sql,time(delivery,"expires_at"));requireOriginalDeliveryLease(sql,time(delivery,"lease_until"));return true;
        }));
    }
    /** Final completion fence: the originally read lease is never renewed by completion work. */
    static void requireOriginalDeliveryLease(DSLContext sql,Instant originalLeaseUntil){live(sql,originalLeaseUntil);}
    /** Bounded negative-only erasure. Unresolved stage/receipt/reservation rows are never age-evicted. */
    public void expirePayloads(){
        if(!enabled)return;
        new PostgresLoginRequestScope(database).inRequest(actor,owned->owned.transaction(sql->{
            var victims=sql.fetch("select id,identity_id from legacy_migration_otp_challenges where (expires_at<=clock_timestamp() and state in('PENDING_DELIVERY','OPEN')) or (state='PENDING_DELIVERY' and id in(select challenge_id from legacy_migration_otp_outbox where state='LEASED' and attempts=3 and lease_until<=clock_timestamp())) order by identity_id,id limit 100");
            for(var victim:victims){PostgresLegacyMigrationStore.lockOwner(sql,victim.get("identity_id",UUID.class));retire(sql,victim.get("id",UUID.class),"EXPIRED");}
            return null;
        }));
    }
    private static void validate(UUID command,Purpose purpose){if(command==null||purpose==null)fail(Error.BAD_INPUT);}
    private static Map<String,Object> orderedAck(Map<String,Object> ack){var result=new LinkedHashMap<String,Object>();for(String key:LegacyMigrationOtp.ACK)result.put(key,ack.get(key));return result;}
    private static String encode(Result r){var map=new LinkedHashMap<String,Object>();map.put("challenge",r.challengeId()==null?null:r.challengeId().toString());map.put("receipt",r.receiptId()==null?null:r.receiptId().toString());map.put("state",r.state());map.put("expiry",r.expiresAt()==null?null:r.expiresAt().toString());map.put("resend",r.resendAt()==null?null:r.resendAt().toString());map.put("attempts",r.attemptsRemaining());map.put("error",r.error()==null?null:r.error().name());return json(map);}
    private static Result decode(String value){try{var n=JSON.readTree(value);return new Result(n.get("challenge").isNull()?null:UUID.fromString(n.get("challenge").asText()),n.get("receipt").isNull()?null:UUID.fromString(n.get("receipt").asText()),n.get("state").asText(),n.get("expiry").isNull()?null:Instant.parse(n.get("expiry").asText()),n.get("resend").isNull()?null:Instant.parse(n.get("resend").asText()),n.get("attempts").intValue(),n.get("error").isNull()?null:Error.valueOf(n.get("error").asText()));}catch(Exception bad){throw new Failure(Error.UNAVAILABLE);}}
    private static String json(Object value){try{return JSON.writeValueAsString(value);}catch(Exception bad){throw new Failure(Error.UNAVAILABLE);}}
    private static Instant time(Record row,String field){return row.get(field,OffsetDateTime.class).toInstant();}
    private static Instant now(DSLContext sql){return sql.fetchOne("select clock_timestamp()").get(0,OffsetDateTime.class).toInstant();}
    private static Instant min(Instant... values){return Collections.min(Arrays.asList(values));}
    private static void live(DSLContext sql,Instant deadline){if(!now(sql).isBefore(deadline))fail(Error.CONFLICT);}
    private static long count(DSLContext sql,String query){return sql.fetchOne(query).get(0,Long.class);}
    private static String hex(byte[] bytes){return HexFormat.of().formatHex(bytes);}
    private static void fail(Error error){throw new Failure(error);}
}
