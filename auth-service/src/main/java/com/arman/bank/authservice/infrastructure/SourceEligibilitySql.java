package com.arman.bank.authservice.infrastructure;

import com.arman.bank.authservice.domain.LegacyMigrationFinalization;
import com.arman.bank.authservice.domain.LegacyMigrationContact;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.*;
import org.jooq.DSLContext;
import org.jooq.Record;

/** Caller-owned SQL extraction. Historical provenance is never current authorization. */
final class SourceEligibilitySql {
    static final String MIGRATION="LEGACY_MIGRATION_DUAL_CONTACT";
    private SourceEligibilitySql() {}

    /** Called only after the original journal, ACK, final-command and completion HMACs verify.
     * Intentionally does not inspect the mutable current pointer: security suspension preserves history. */
    static void requireHistoricalCompletion(DSLContext sql,Record completion,Map<String,Object> journal) {
        PostgresVerificationProjectionStore.requireLocked(sql);
        if(completion==null||journal==null)throw unavailable();
        Record authority=sql.fetchOne("select * from migration_banking_authorities where identity_id=? and decision_id=? and verification_epoch=? and source_revision=?",
            completion.get("identity_id"),completion.get("decision_id"),completion.get("verification_epoch"),completion.get("source_revision"));
        if(authority==null||!"ACTIVE".equals(authority.get("state"))||!MIGRATION.equals(authority.get("source_kind"))
            ||number(authority,"verification_epoch")!=1||number(authority,"source_revision")!=1)throw unavailable();
        for(String field:List.of("identity_id","decision_id","migration_id","classification_generation","verification_epoch","source_revision"))same(authority.get(field),completion.get(field));
        same(authority.get("post_cutover_owner_generation"),completion.get("resulting_owner_generation"));
        same(authority.get("current_mode_generation"),completion.get("resulting_mode_generation"));
        same(authority.get("association_generation"),completion.get("association_generation"));
        for(String field:List.of("identity_id","decision_id","migration_id","email_receipt_id","phone_receipt_id","email_receipt_generation","phone_receipt_generation","stage_operation_id","stage_ack_hash","receipt_snapshot_hash","journal_hash","policy_version","email_contact_version","email_digest"))same(authority.get(field),journal.get(field));
        same(authority.get("original_owner_generation"),journal.get("owner_generation"));
        same(authority.get("original_mode_generation"),journal.get("mode_generation"));
        Record source=sql.fetchOne("select * from managed_identity_sources where identity_id=?",authority.get("identity_id"));
        if(source==null||!MIGRATION.equals(source.get("source_kind"))||source.get("registration_enrollment_id")!=null)throw unavailable();
        same(source.get("source_id"),authority.get("decision_id"));same(source.get("migration_decision_id"),authority.get("decision_id"));same(source.get("classification_generation"),authority.get("classification_generation"));
        Record ack=sql.fetchOne("select * from legacy_migration_verified_final_acks where decision_id=? and identity_id=?",authority.get("decision_id"),authority.get("identity_id"));
        Record grant=sql.fetchOne("select * from migration_banking_grants where identity_id=? and verification_epoch=?",authority.get("identity_id"),authority.get("verification_epoch"));
        if(ack==null||grant==null)throw unavailable();
        for(String field:List.of("decision_id","classification_generation","source_revision","policy_version"))same(grant.get(field),authority.get(field));
        for(String field:List.of("migration_id","stage_operation_id","email_receipt_id","phone_receipt_id","email_receipt_generation","phone_receipt_generation","stage_ack_hash","receipt_snapshot_hash","final_ack_hash","publication_revision","email_contact_version"))same(ack.get(field),authority.get(field));
        same(ack.get("baseline_generation"),authority.get("user_baseline_generation"));same(ack.get("contact_mutation_revision"),authority.get("user_contact_mutation_revision"));
        same(ack.get("final_ack_hash"),completion.get("final_ack_hash"));
        Record email=receipt(sql,authority,"EMAIL"),phone=receipt(sql,authority,"PHONE");
        Record stage=sql.fetchOne("select * from legacy_migration_otp_stage_acks where migration_id=?",authority.get("migration_id"));
        if(stage==null)throw unavailable();
        for(String field:List.of("projection_present"))same(stage.get(field),authority.get(field));
        same(stage.get("projection_revision"),authority.get("user_projection_revision"));same(stage.get("phone_contact_version"),authority.get("user_phone_contact_version"));same(stage.get("verification_epoch"),authority.get("user_verification_epoch"));
        Record transition=sql.fetchOne("select * from legacy_migration_contact_transitions where migration_id=?",authority.get("migration_id"));
        if(authority.get("contact_transition_hash")!=null){
            if(transition==null)throw unavailable();same(transition.get("transition_hash"),authority.get("contact_transition_hash"));same(transition.get("resulting_generation"),authority.get("verified_contact_generation"));same(transition.get("receipt_id"),email.get("id"));same(transition.get("owner_id"),authority.get("identity_id"));
        } else if(transition!=null)throw unavailable();
        Record managed=sql.fetchOne("select * from legacy_migration_managed_receipts where receipt_id=?",journal.get("managed_receipt_id"));
        if(managed==null)throw unavailable();same(managed.get("owner_id"),authority.get("identity_id"));same(managed.get("migration_id"),authority.get("migration_id"));same(managed.get("issuer"),authority.get("issuer"));same(managed.get("subject"),authority.get("subject"));
        byte[] computed=stamp(authority,journal,grant,email,phone,transition);
        bytes(computed,authority.get("immutable_provenance_hash",byte[].class));bytes(computed,grant.get("immutable_provenance_hash",byte[].class));bytes(computed,completion.get("authority_stamp",byte[].class));
    }

    record Current(Record authority,Record grant,byte[] stamp,Map<String,Object> journal) {
        Current {stamp=stamp.clone();journal=Collections.unmodifiableMap(new LinkedHashMap<>(journal));}
        @Override public byte[] stamp(){return stamp.clone();}
        @Override public String toString(){return "MigrationSourceCurrent[redacted]";}
    }
    static Current requireCurrent(DSLContext sql,String issuer,String subject,LegacyMigrationManagedProofCrypto crypto) {
        PostgresVerificationProjectionStore.requireLocked(sql);
        if(crypto==null)throw unavailable();
        Record selected=sql.fetchOne("select migration_id from migration_banking_authority_current where issuer=? and subject=?",issuer,subject);
        if(selected==null)throw unavailable();
        Map<String,Object> journal=PostgresLegacyMigrationFinalizationStore.requireHistoricalJournal(sql,selected.get("migration_id",UUID.class),crypto);
        return requireCurrent(sql,issuer,subject,journal,crypto);
    }
    /** The caller supplies the original cryptographically verified journal, never client facts.
     * Historical HMAC validation is repeated here; current pointer/owner checks are additional. */
    private static Current requireCurrent(DSLContext sql,String issuer,String subject,Map<String,Object> verifiedJournal,LegacyMigrationManagedProofCrypto crypto) {
        PostgresVerificationProjectionStore.requireLocked(sql);
        if(crypto==null||verifiedJournal==null)throw unavailable();
        UUID identity=(UUID)verifiedJournal.get("identity_id"),decision=(UUID)verifiedJournal.get("decision_id");
        PostgresLegacyMigrationStore.lockOwner(sql,identity);
        if(sql.fetchOne("select identity_id from managed_registration_principals where identity_id=?",identity)!=null)throw unavailable();
        var authorities=sql.fetch("select enrollment_id,state from verification_authorities where mapped_identity_id=? or pending_identity_id=?",identity,identity);
        if(!authorities.isEmpty()){
            Record original=sql.fetchOne("select enrollment_id,owner_generation from legacy_migration_associations where migration_id=?",verifiedJournal.get("migration_id"));
            if(authorities.size()!=1||original==null||!Objects.equals(authorities.get(0).get("enrollment_id"),original.get("enrollment_id"))||"SUSPENDED".equals(authorities.get(0).get("state")))throw unavailable();
            same(original.get("owner_generation"),verifiedJournal.get("owner_generation"));
        }
        if(PostgresMigrationFinalAckStore.validatedCompletion(sql,verifiedJournal,crypto,(String)verifiedJournal.get("writer_id")).isEmpty())throw unavailable();
        Record pointer=sql.fetchOne("select * from migration_banking_authority_current where identity_id=? for update",identity);
        if(pointer==null||!issuer.equals(pointer.get("issuer"))||!subject.equals(pointer.get("subject"))||!decision.equals(pointer.get("decision_id")))throw unavailable();
        Record a=sql.fetchOne("select * from migration_banking_authorities where identity_id=? and decision_id=? and verification_epoch=? and source_revision=?",identity,decision,pointer.get("current_epoch"),pointer.get("current_source_revision"));
        if(a==null||!"ACTIVE".equals(a.get("state"))||!MIGRATION.equals(a.get("source_kind")))throw unavailable();
        same(pointer.get("classification_generation"),a.get("classification_generation"));
        requireOwnerEvidence(sql,a,verifiedJournal);
        Record grant=sql.fetchOne("select * from migration_banking_grants where identity_id=? and verification_epoch=?",identity,a.get("verification_epoch"));
        if(grant==null)throw unavailable();same(grant.get("source_revision"),a.get("source_revision"));same(grant.get("decision_id"),decision);
        Record email=receipt(sql,a,"EMAIL"),phone=receipt(sql,a,"PHONE");
        Record transition=sql.fetchOne("select * from legacy_migration_contact_transitions where migration_id=?",a.get("migration_id"));
        byte[] stamp=stamp(a,verifiedJournal,grant,email,phone,transition);bytes(stamp,a.get("immutable_provenance_hash",byte[].class));
        return new Current(a,grant,stamp,verifiedJournal);
    }

    /** Private synchronous observation custody. It never creates a lease or accepts caller User facts. */
    static final class UserObservation {
        private final PostgresLoginRequestScope.Request request;private final Thread owner=Thread.currentThread();
        private final Current original;private final Instant databaseStart;private final long monotonicStart;private final int backendPid;private final Instant backendStart;
        private final com.arman.bank.authservice.application.MigrationUserCurrentPort.Observation observation;
        private UserObservation(PostgresLoginRequestScope.Request request,Current original,Instant databaseStart,long monotonicStart,int backendPid,Instant backendStart,
                com.arman.bank.authservice.application.MigrationUserCurrentPort.Observation observation){this.request=request;this.original=original;this.databaseStart=databaseStart;this.monotonicStart=monotonicStart;this.backendPid=backendPid;this.backendStart=backendStart;this.observation=observation;}
        void require(DSLContext sql,boolean currentActive,LegacyMigrationManagedProofCrypto crypto){
            if(Thread.currentThread()!=owner)throw unavailable();request.check();PostgresVerificationProjectionStore.requireLocked(sql);
            var backend=sql.fetchOne("select pid,backend_start from pg_stat_activity where pid=pg_backend_pid()");
            if(backend==null||backend.get("pid",Integer.class)!=backendPid||!backend.get("backend_start",OffsetDateTime.class).toInstant().equals(backendStart))throw unavailable();
            PostgresMigrationUserMutationStore.remaining(monotonicStart);
            Instant now=sql.fetchOne("select clock_timestamp() as now").get("now",OffsetDateTime.class).toInstant();
            if(now.isBefore(databaseStart)||!now.isBefore(databaseStart.plusSeconds(4)))throw unavailable();
            if(currentActive){Current current=requireCurrent(sql,original.authority().get("issuer",String.class),original.authority().get("subject",String.class),crypto);bytes(current.stamp(),original.stamp());}
            requireUserTuple(sql,original,observation.current(),true);
            Instant finalNow=sql.fetchOne("select clock_timestamp() as now").get("now",OffsetDateTime.class).toInstant();
            if(finalNow.isBefore(databaseStart)||!finalNow.isBefore(databaseStart.plusSeconds(4)))throw unavailable();
            PostgresMigrationUserMutationStore.remaining(monotonicStart);
        }
        @Override public String toString(){return "CurrentUserObservation[redacted]";}
    }
    static UserObservation observeUser(PostgresLoginRequestScope.Request owned,Current source,UUID operation,Instant originalStart,long monotonicStart,
            com.arman.bank.authservice.application.MigrationUserCurrentPort port){
        if(port==null||source==null)throw unavailable();owned.verify();PostgresMigrationUserMutationStore.remaining(monotonicStart);
        var backend=owned.transaction(sql->sql.fetchOne("select pid,backend_start from pg_stat_activity where pid=pg_backend_pid()"));
        if(backend==null)throw unavailable();
        Record a=source.authority();var query=new com.arman.bank.authservice.application.MigrationUserCurrentPort.Query(operation,a.get("identity_id",UUID.class),a.get("migration_id",UUID.class),a.get("decision_id",UUID.class),a.get("classification_generation",Long.class),a.get("verification_epoch",Long.class),a.get("source_revision",Long.class));
        long elapsed=System.nanoTime()-monotonicStart;if(elapsed<0||elapsed>=4000000000L)throw unavailable();
        owned.verify();PostgresMigrationUserMutationStore.remaining(monotonicStart);
        elapsed=System.nanoTime()-monotonicStart;if(elapsed<0||elapsed>=4000000000L)throw unavailable();
        var observed=port.observe(query,java.time.Duration.ofNanos(4000000000L-elapsed));owned.verify();
        if(observed==null||!query.equals(observed.query()))throw unavailable();PostgresMigrationUserMutationStore.remaining(monotonicStart);
        return new UserObservation(owned,source,originalStart,monotonicStart,backend.get("pid",Integer.class),backend.get("backend_start",OffsetDateTime.class).toInstant(),observed);
    }
    /** Denial-only classification: historical provenance remains mandatory after suspension. */
    static Current requireMutationSource(DSLContext sql,UUID identity,LegacyMigrationManagedProofCrypto crypto){
        PostgresVerificationProjectionStore.requireLocked(sql);if(crypto==null)throw unavailable();
        PostgresLegacyMigrationStore.lockOwner(sql,identity);
        Record pointer=sql.fetchOne("select * from migration_banking_authority_current where identity_id=? for update",identity);
        if(pointer==null)throw unavailable();
        Record a=sql.fetchOne("select * from migration_banking_authorities where identity_id=? and decision_id=? and verification_epoch=? and source_revision=?",
            identity,pointer.get("decision_id"),pointer.get("current_epoch"),pointer.get("current_source_revision"));
        if(a==null||!MIGRATION.equals(a.get("source_kind")))throw unavailable();
        if("ACTIVE".equals(a.get("state")))return requireCurrent(sql,a.get("issuer",String.class),a.get("subject",String.class),crypto);
        if(!"SUSPENDED".equals(a.get("state")))throw unavailable();
        var journal=PostgresLegacyMigrationFinalizationStore.requireHistoricalJournal(sql,a.get("migration_id",UUID.class),crypto);
        if(PostgresMigrationFinalAckStore.validatedCompletion(sql,journal,crypto,(String)journal.get("writer_id")).isEmpty())throw unavailable();
        same(pointer.get("classification_generation"),a.get("classification_generation"));same(pointer.get("migration_id"),a.get("migration_id"));
        requireOwnerEvidence(sql,a,journal);
        Record initial=sql.fetchOne("select * from migration_banking_authorities where identity_id=? and decision_id=? and verification_epoch=1 and source_revision=1",identity,a.get("decision_id"));
        Record grant=sql.fetchOne("select * from migration_banking_grants where identity_id=? and verification_epoch=1",identity);
        if(initial==null||grant==null||!Boolean.TRUE.equals(sql.fetchOne("select (to_jsonb(a)-array['state','verification_epoch','source_revision','version_created_at','suspension_reason'])=(to_jsonb(b)-array['state','verification_epoch','source_revision','version_created_at','suspension_reason']) as same from migration_banking_authorities a,migration_banking_authorities b where a.identity_id=? and a.decision_id=? and a.verification_epoch=? and a.source_revision=? and b.identity_id=a.identity_id and b.decision_id=a.decision_id and b.verification_epoch=1 and b.source_revision=1",identity,a.get("decision_id"),a.get("verification_epoch"),a.get("source_revision")).get("same",Boolean.class)))throw unavailable();
        return new Current(a,grant,a.get("immutable_provenance_hash",byte[].class),journal);
    }
    /** Auth verifies fresh User's shard by recomputing the ORIGINAL baseline recipe, never a claimed hash. */
    static void requireUserTuple(DSLContext sql,Current source,Map<String,Object> supplied,boolean originalTuple){
        var user=com.arman.bank.authservice.domain.LiveUserRevision.user(supplied);Record a=source.authority();
        Record stage=sql.fetchOne("select * from legacy_migration_otp_stage_acks where migration_id=?",a.get("migration_id"));
        if(stage==null)throw unavailable();
        same(user.get("profile_id"),stage.get("profile_id",UUID.class).toString());same(user.get("baseline_generation"),stage.get("baseline_generation"));
        String computed=baselineHash(a,stage,user.get("shard_id"));if(!computed.equals(stage.get("baseline_hash")))throw unavailable();
        if(originalTuple){same(user.get("contact_mutation_revision"),a.get("user_contact_mutation_revision"));same(user.get("projection_present"),a.get("projection_present"));
            same(user.get("projection_revision"),a.get("user_projection_revision"));same(user.get("phone_contact_version"),a.get("user_phone_contact_version"));same(user.get("verification_epoch"),a.get("user_verification_epoch"));}
    }
    private static String baselineHash(Record a,Record stage,Object shard){
        try{var bytes=new java.io.ByteArrayOutputStream();var out=new java.io.DataOutputStream(bytes);
            for(Object value:new Object[]{"armoney-user-migration-baseline-v1",a.get("identity_id"),stage.get("profile_id"),shard,stage.get("baseline_generation"),stage.get("contact_mutation_revision"),stage.get("projection_present"),stage.get("projection_revision"),stage.get("phone_contact_version"),stage.get("verification_epoch"),a.get("canonical_phone"),a.get("email_contact_version"),a.get("email_digest"),a.get("policy_version")}){
                byte[] encoded=String.valueOf(value).getBytes(StandardCharsets.UTF_8);out.writeInt(encoded.length);out.write(encoded);}
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
        }catch(java.io.IOException|NoSuchAlgorithmException invalid){throw unavailable();}
    }
    /** Pointer-independent original owner/association/contact checks, shared with own suspension. */
    private static void requireOwnerEvidence(DSLContext sql,Record a,Map<String,Object> verifiedJournal){
        UUID identity=a.get("identity_id",UUID.class);String issuer=a.get("issuer",String.class),subject=a.get("subject",String.class);
        PostgresLegacyMigrationStore.lockOwner(sql,identity);
        if(sql.fetchOne("select identity_id from managed_registration_principals where identity_id=?",identity)!=null)throw unavailable();
        var authorities=sql.fetch("select enrollment_id,state from verification_authorities where mapped_identity_id=? or pending_identity_id=?",identity,identity);
        if(!authorities.isEmpty()){
            Record original=sql.fetchOne("select enrollment_id,owner_generation from legacy_migration_associations where migration_id=?",verifiedJournal.get("migration_id"));
            if(authorities.size()!=1||original==null||!Objects.equals(authorities.get(0).get("enrollment_id"),original.get("enrollment_id"))||"SUSPENDED".equals(authorities.get(0).get("state")))throw unavailable();
            same(original.get("owner_generation"),verifiedJournal.get("owner_generation"));
        }
        Record generation=sql.fetchOne("select * from legacy_auth_generations where identity_id=?",identity);
        Record mapping=sql.fetchOne("select * from external_identities where issuer=? and subject=? for update",issuer,subject);
        Record association=sql.fetchOne("select * from legacy_auth_association_generations where issuer=? and subject=? for update",issuer,subject);
        if(generation==null||mapping==null||association==null||!Boolean.TRUE.equals(association.get("active")))throw unavailable();
        same(mapping.get("identity_id"),identity);same(association.get("identity_id"),identity);same(association.get("generation"),a.get("association_generation"));
        same(generation.get("owner_generation"),a.get("post_cutover_owner_generation"));
        if("LOCAL_PASSWORD".equals(verifiedJournal.get("proof_mode")))same(generation.get("credential_generation"),a.get("current_mode_generation"));
        else if("LEGACY_OIDC_REAUTH".equals(verifiedJournal.get("proof_mode")))same(association.get("generation"),a.get("current_mode_generation"));
        else throw unavailable();
        same(generation.get("email_generation"),verifiedJournal.get("email_generation"));
        Record owner=sql.fetchOne("select email,registration_phone from identities where id=?",identity);
        if(owner==null)throw unavailable();same(owner.get("registration_phone"),a.get("canonical_phone"));
        if("CREDENTIAL_EMAIL".equals(a.get("email_source")))same(owner.get("email"),a.get("canonical_email"));
        else {
            Record contact=sql.fetchOne("select * from legacy_migration_owner_contacts where identity_id=?",identity);
            if(contact==null)throw unavailable();same(contact.get("canonical_email"),a.get("canonical_email"));same(contact.get("source_receipt_id"),a.get("email_receipt_id"));same(contact.get("generation"),a.get("verified_contact_generation"));
        }
    }

    /** Original current graph must already have been validated under the shared controls/owner lock.
     * No expiry/provider promise is renewed here; caller performs its final original deadline checks. */
    static void suspend(DSLContext sql,Current original,String reason,LegacyMigrationManagedProofCrypto crypto){
        if(!Set.of("PROVIDER_NEGATIVE","LOCAL_MUTATION").contains(reason))throw unavailable();
        Record before=original.authority();
        Current checked=requireCurrent(sql,before.get("issuer",String.class),before.get("subject",String.class),crypto);
        bytes(checked.stamp(),original.stamp());
        long epoch,revision;try{epoch=Math.addExact(number(before,"verification_epoch"),1);revision=Math.addExact(number(before,"source_revision"),1);}catch(ArithmeticException overflow){throw unavailable();}
        int inserted=sql.execute("""
            insert into migration_banking_authorities
            select (jsonb_populate_record(null::migration_banking_authorities,
              to_jsonb(a)||jsonb_build_object('state','SUSPENDED','verification_epoch',cast(? as bigint),
                'source_revision',cast(? as bigint),'version_created_at',clock_timestamp(),'suspension_reason',cast(? as text)))).*
            from migration_banking_authorities a where identity_id=? and decision_id=? and verification_epoch=? and source_revision=? and state='ACTIVE'
            """,epoch,revision,reason,before.get("identity_id"),before.get("decision_id"),before.get("verification_epoch"),before.get("source_revision"));
        if(inserted!=1||sql.execute("update migration_banking_authority_current set current_epoch=?,current_source_revision=? where identity_id=? and decision_id=? and current_epoch=? and current_source_revision=?",epoch,revision,before.get("identity_id"),before.get("decision_id"),before.get("verification_epoch"),before.get("source_revision"))!=1)throw unavailable();
        requireOwnSuspension(sql,original);
    }
    static void requireOwnSuspension(DSLContext sql,Current original){
        PostgresVerificationProjectionStore.requireLocked(sql);Record before=original.authority();
        Record result=sql.fetchOne("""
            select b.identity_id from migration_banking_authorities b
            join migration_banking_authorities a on a.identity_id=b.identity_id and a.decision_id=b.decision_id
            join migration_banking_authority_current p on p.identity_id=b.identity_id and p.decision_id=b.decision_id
            where a.identity_id=? and a.decision_id=? and a.verification_epoch=? and a.source_revision=?
              and b.verification_epoch=a.verification_epoch+1 and b.source_revision=a.source_revision+1
              and b.state='SUSPENDED' and p.current_epoch=b.verification_epoch and p.current_source_revision=b.source_revision
              and (to_jsonb(a)-array['state','verification_epoch','source_revision','version_created_at','suspension_reason'])=
                  (to_jsonb(b)-array['state','verification_epoch','source_revision','version_created_at','suspension_reason'])
            """,before.get("identity_id"),before.get("decision_id"),before.get("verification_epoch"),before.get("source_revision"));
        if(result==null)throw unavailable();
        requireOwnerEvidence(sql,before,original.journal());
        Record completion=sql.fetchOne("select * from legacy_migration_final_completions where decision_id=?",before.get("decision_id"));
        requireHistoricalCompletion(sql,completion,original.journal());
    }
    private static Record receipt(DSLContext sql,Record authority,String purpose) {
        String prefix=purpose.equals("EMAIL")?"email":"phone";
        Record receipt=sql.fetchOne("select * from legacy_migration_otp_receipts where id=?",authority.get(prefix+"_receipt_id"));
        if(receipt==null||!purpose.equals(receipt.get("purpose")))throw unavailable();
        same(receipt.get("migration_id"),authority.get("migration_id"));same(receipt.get("receipt_generation"),authority.get(prefix+"_receipt_generation"));
        Record challenge=sql.fetchOne("select * from legacy_migration_otp_challenges where id=?",receipt.get("challenge_id"));
        Record candidate=sql.fetchOne("select * from legacy_migration_otp_candidates where id=?",receipt.get("candidate_id"));
        if(challenge==null||candidate==null||!"CONSUMED".equals(challenge.get("state")))throw unavailable();
        same(challenge.get("identity_id"),authority.get("identity_id"));same(challenge.get("migration_id"),authority.get("migration_id"));same(challenge.get("candidate_id"),receipt.get("candidate_id"));same(challenge.get("purpose"),purpose);same(challenge.get("binding_hash"),receipt.get("binding_hash"));
        same(candidate.get("migration_id"),authority.get("migration_id"));same(candidate.get("purpose"),purpose);same(candidate.get("receipt_generation"),receipt.get("receipt_generation"));
        return receipt;
    }

    /** Fixed source-v1 codec; no driver-format strings and no arbitrary map iteration. */
    static byte[] stamp(Record a,Map<String,Object> journal,Record grant,Record email,Record phone,Record transition) {
        var values=new ArrayList<Object>();values.add("armoney-managed-eligibility-source-v1");values.add(1L);
        values.add(MIGRATION);values.add(a.get("decision_id"));
        add(values,a,"identity_id","issuer","subject","classification_generation","source_revision","verification_epoch","state","canonical_email","canonical_phone","email_source","original_owner_generation","post_cutover_owner_generation","original_mode_generation","current_mode_generation","association_generation");
        // Opposite-source slots: enrollment/revision; two candidate-version-receipt triples;
        // projection operation/revision/epoch/hash; command; six ACK fields; three deadlines.
        for(int i=0;i<22;i++)values.add(null);
        add(values,a,"migration_id","decision_id","email_baseline_generation","email_baseline_digest","candidate_id","candidate_version","candidate_digest");
        values.add(transition==null?null:transition.get("migration_id"));add(values,a,"contact_transition_hash","verified_contact_generation");
        add(values,email,"candidate_id","challenge_id","id","receipt_generation");add(values,phone,"candidate_id","challenge_id","id","receipt_generation");
        add(values,a,"user_baseline_generation","user_contact_mutation_revision","projection_present","user_projection_revision","user_phone_contact_version","user_verification_epoch","stage_operation_id","stage_ack_hash","final_ack_hash","publication_revision","receipt_snapshot_hash");
        values.add(journal.get("policy_version"));values.add(journal.get("payload_hash"));values.add(a.get("policy_version"));values.add(grant.get("created_at"));
        try {MessageDigest hash=MessageDigest.getInstance("SHA-256");for(Object value:values){byte[] bytes=scalar(value).getBytes(StandardCharsets.UTF_8);hash.update(ByteBuffer.allocate(4).putInt(bytes.length).array());hash.update(bytes);}return hash.digest();}
        catch(NoSuchAlgorithmException impossible){throw unavailable();}
    }
    private static void add(List<Object> values,Record row,String... names){for(String name:names)values.add(row.get(name));}
    private static String scalar(Object value){
        if(value==null)return "NONE";if(value instanceof OffsetDateTime time)return LegacyMigrationContact.instant(time.toInstant());if(value instanceof Instant time)return LegacyMigrationContact.instant(time);
        if(value instanceof UUID id){if(id.equals(new UUID(0,0)))throw unavailable();return id.toString();}
        if(value instanceof Long||value instanceof Integer||value instanceof Short){long number=((Number)value).longValue();if(number<0)throw unavailable();return Long.toString(number);}
        if(value instanceof Boolean bool)return bool?"true":"false";if(value instanceof String text&&!text.isEmpty())return text;throw unavailable();
    }
    private static long number(Record row,String field){Object value=row.get(field);if(!(value instanceof Number number)||number.longValue()<0)throw unavailable();return number.longValue();}
    private static void same(Object left,Object right){if(left instanceof Number&&right instanceof Number){if(((Number)left).longValue()!=((Number)right).longValue())throw unavailable();}else if(!Objects.equals(left,right))throw unavailable();}
    private static void bytes(byte[] left,byte[] right){if(left==null||right==null||left.length!=32||right.length!=32||!MessageDigest.isEqual(left,right))throw unavailable();}
    private static LegacyMigrationFinalization.Failure unavailable(){return new LegacyMigrationFinalization.Failure(LegacyMigrationFinalization.Error.UNAVAILABLE);}
}
