package com.arman.bank.authservice.infrastructure;

import com.arman.bank.authservice.domain.LegacyMigrationFinalization;
import java.time.*;
import java.util.*;
import java.util.function.Consumer;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.impl.DSL;

/** Migration-specific consumer. Constructor allocates no resources and owns no Database.
 * Promotion requires genuine caller-owned decision/ACK/evidence capabilities and validates its exact own cutover graph.
 * Constructor presence is not runtime activation or proof of independently verified consumer readiness. */
public final class PostgresMigrationBankingEligibilityStore {
    private final LegacyMigrationManagedProofCrypto crypto;
    public PostgresMigrationBankingEligibilityStore(LegacyMigrationManagedProofCrypto crypto){this.crypto=Objects.requireNonNull(crypto);}
    Promotion promote(DSLContext sql,PostgresMigrationFinalAckStore.FinalDecisionUse decision,
                      PostgresMigrationFinalAckStore.VerifiedFinalAckUse ack,PostgresLegacyMigrationManagedProofStore.EvidenceUse evidence){
        PostgresVerificationProjectionStore.requireLocked(sql);
        if(decision==null||ack==null||evidence==null)throw unavailable();
        decision.requireCurrent(sql);ack.requireCurrent(sql);evidence.requireObservation(sql);
        var snapshot=decision.snapshot();snapshot.requireCurrent(sql);var journal=decision.journal();
        UUID identity=(UUID)journal.get("identity_id"),migration=(UUID)journal.get("migration_id"),id=(UUID)journal.get("decision_id");
        if(!migration.equals(snapshot.seed().migrationId())||!identity.equals(snapshot.seed().identityId()))throw unavailable();
        Record generation=PostgresLegacyMigrationStore.lockOwner(sql,identity);
        Record managed=sql.fetchOne("select * from legacy_migration_managed_receipts where receipt_id=?",journal.get("managed_receipt_id"));
        if(managed==null||!identity.equals(managed.get("owner_id"))||!migration.equals(managed.get("migration_id")))throw unavailable();
        String issuer=managed.get("issuer",String.class),subject=managed.get("subject",String.class);
        if(sql.fetchOne("select identity_id from managed_registration_principals where identity_id=? union all select identity_id from managed_identity_sources where identity_id=? limit 1",identity,identity)!=null)throw unavailable();
        if(sql.fetchOne("select count(*) from migration_banking_authority_current").get(0,Long.class)>=100000
            ||sql.fetchOne("select count(*) from migration_banking_grants").get(0,Long.class)>=100000)throw unavailable();
        Map<String,String> original=originalGraph(sql,migration);
        Record mapping=sql.fetchOne("select * from external_identities where issuer=? and subject=? for update",issuer,subject);
        if(mapping!=null&&!identity.equals(mapping.get("identity_id")))throw unavailable();
        if("LEGACY_OIDC_REAUTH".equals(journal.get("proof_mode"))&&mapping==null)throw unavailable();
        if(mapping==null)sql.execute("insert into external_identities(issuer,subject,identity_id) values(?,?,?)",issuer,subject,identity);
        long oldOwner=generation.get("owner_generation",Long.class),newOwner;
        try{newOwner=Math.addExact(oldOwner,1);}catch(ArithmeticException overflow){throw unavailable();}
        if(oldOwner!=snapshot.ownerGeneration()||sql.execute("update legacy_auth_generations set owner_generation=? where identity_id=? and owner_generation=?",newOwner,identity,oldOwner)!=1)throw unavailable();
        Record post=sql.fetchOne("select * from legacy_auth_generations where identity_id=?",identity);
        Record association=sql.fetchOne("select * from legacy_auth_association_generations where issuer=? and subject=?",issuer,subject);
        if(post==null||association==null||!Boolean.TRUE.equals(association.get("active"))||!identity.equals(association.get("identity_id")))throw unavailable();
        if(post.get("owner_generation",Long.class).longValue()!=newOwner||!Objects.equals(post.get("credential_generation"),generation.get("credential_generation"))||!Objects.equals(post.get("email_generation"),generation.get("email_generation")))throw unavailable();
        long associationGeneration=association.get("generation",Long.class);
        long mode="LOCAL_PASSWORD".equals(journal.get("proof_mode"))?post.get("credential_generation",Long.class):associationGeneration;
        Instant created=normalized(now(sql));
        var marker=new LinkedHashMap<String,Object>();marker.put("identity_id",identity);marker.put("source_kind",SourceEligibilitySql.MIGRATION);marker.put("source_id",id);marker.put("classification_generation",1L);marker.put("registration_enrollment_id",null);marker.put("migration_decision_id",id);marker.put("created_at",created);insert(sql,"managed_identity_sources",marker);
        var a=authority(sql,snapshot,journal,ack.acknowledgement(),managed,post,associationGeneration,mode,created);
        var grant=new LinkedHashMap<String,Object>();grant.put("identity_id",identity);grant.put("verification_epoch",1L);grant.put("decision_id",id);grant.put("classification_generation",1L);grant.put("source_revision",1L);grant.put("policy_version",journal.get("policy_version"));grant.put("created_at",created);
        Record email=sql.fetchOne("select * from legacy_migration_otp_receipts where id=?",snapshot.emailReceiptId());
        Record phone=sql.fetchOne("select * from legacy_migration_otp_receipts where id=?",snapshot.phoneReceiptId());
        Record transition=sql.fetchOne("select * from legacy_migration_contact_transitions where migration_id=?",migration);
        byte[] stamp=SourceEligibilitySql.stamp(record(sql,a),journal,record(sql,grant),email,phone,transition);
        a.put("immutable_provenance_hash",stamp);grant.put("immutable_provenance_hash",stamp);
        insert(sql,"migration_banking_authorities",a);insert(sql,"migration_banking_grants",grant);
        var pointer=new LinkedHashMap<String,Object>();for(String field:List.of("identity_id","decision_id","migration_id","issuer","subject","classification_generation"))pointer.put(field,a.get(field));pointer.put("current_epoch",1L);pointer.put("current_source_revision",1L);insert(sql,"migration_banking_authority_current",pointer);
        sql.execute("delete from sessions where identity_id=?",identity);
        var promotion=new Promotion(sql,snapshot,evidence,id,identity,migration,1L,newOwner,mode,associationGeneration,1L,1L,stamp,(String)journal.get("retention_key_id"),
            current->{
                evidence.requireObservation(current);ack.requireCurrent(current);
                if(!original.equals(originalGraph(current,migration)))throw unavailable();
                requireOwnRow(current,"managed_identity_sources","identity_id",identity,marker);
                requireOwnRow(current,"migration_banking_authority_current","identity_id",identity,pointer);
                Record actual=current.fetchOne("select * from migration_banking_authorities where identity_id=? and decision_id=? and verification_epoch=1 and source_revision=1",identity,id);
                if(actual==null||!normalizedMap(a).equals(normalizedMap(actual.intoMap())))throw unavailable();
                Record storedGrant=current.fetchOne("select * from migration_banking_grants where identity_id=? and verification_epoch=1",identity);
                if(storedGrant==null||!normalizedMap(grant).equals(normalizedMap(storedGrant.intoMap())))throw unavailable();
                Record g=current.fetchOne("select * from legacy_auth_generations where identity_id=?",identity);
                Record link=current.fetchOne("select * from legacy_auth_association_generations where issuer=? and subject=?",issuer,subject);
                if(g==null||!normalizedMap(post.intoMap()).equals(normalizedMap(g.intoMap()))||link==null||!normalizedMap(association.intoMap()).equals(normalizedMap(link.intoMap())))throw unavailable();
                Record exact=current.fetchOne("select identity_id from external_identities where issuer=? and subject=?",issuer,subject);
                if(exact==null||!identity.equals(exact.get("identity_id"))||current.fetchOne("select token_hash from sessions where identity_id=? limit 1",identity)!=null)throw unavailable();
                if(!now(current).isBefore(snapshot.deadline())||!now(current).isBefore(snapshot.seed().accountDeadline())||!now(current).isBefore(snapshot.seed().intentDeadline()))throw unavailable();
            });
        promotion.requireCurrent(sql);return promotion;
    }
    void requireOwnPromotion(DSLContext sql,Promotion promotion,PostgresMigrationFinalAckStore.FinalDecisionUse decision,
                             PostgresMigrationFinalAckStore.VerifiedFinalAckUse ack,PostgresLegacyMigrationManagedProofStore.EvidenceUse evidence){
        if(promotion==null||decision==null||ack==null||evidence==null)throw unavailable();
        evidence.requireObservation(sql);promotion.requireOriginalSnapshot(sql,decision.snapshot());promotion.requireCurrent(sql);
    }
    private static Map<String,Object> authority(DSLContext sql,PostgresLegacyMigrationOtpStore.FinalizationSnapshot snapshot,Map<String,Object> j,Map<String,Object> ack,Record managed,Record generation,long association,long mode,Instant created){
        UUID migration=(UUID)j.get("migration_id");Record candidate=sql.fetchOne("select * from legacy_migration_email_candidates where migration_id=?",migration);
        Record contact=sql.fetchOne("select * from legacy_migration_owner_contacts where identity_id=?",j.get("identity_id"));
        Record transition=sql.fetchOne("select * from legacy_migration_contact_transitions where migration_id=?",migration);
        Record owner=sql.fetchOne("select email,registration_phone from identities where id=?",j.get("identity_id"));
        if(owner==null)throw unavailable();String email=candidate==null?owner.get("email",String.class):candidate.get("canonical_email",String.class);
        if(email==null||!snapshot.seed().phone().equals(owner.get("registration_phone")))throw unavailable();
        var a=new LinkedHashMap<String,Object>();for(String field:List.of("identity_id","decision_id","migration_id","policy_version","email_receipt_id","phone_receipt_id","email_receipt_generation","phone_receipt_generation","stage_operation_id","stage_ack_hash","receipt_snapshot_hash","journal_hash","email_contact_version","email_digest"))a.put(field,j.get(field));
        a.put("issuer",managed.get("issuer"));a.put("subject",managed.get("subject"));a.put("source_kind",SourceEligibilitySql.MIGRATION);a.put("classification_generation",1L);a.put("state","ACTIVE");a.put("verification_epoch",1L);a.put("source_revision",1L);
        a.put("original_owner_generation",j.get("owner_generation"));a.put("post_cutover_owner_generation",generation.get("owner_generation"));a.put("original_mode_generation",j.get("mode_generation"));a.put("current_mode_generation",mode);a.put("association_generation",association);
        a.put("email_source",snapshot.source());a.put("email_baseline_generation",snapshot.baselineGeneration());a.put("email_baseline_digest",snapshot.baselineDigest());
        a.put("candidate_id",candidate==null?null:candidate.get("candidate_id"));a.put("candidate_version",candidate==null?null:candidate.get("candidate_version"));a.put("candidate_digest",candidate==null?null:candidate.get("candidate_digest"));a.put("contact_transition_hash",transition==null?null:transition.get("transition_hash"));a.put("verified_contact_generation",contact==null?null:contact.get("generation"));
        a.put("canonical_email",email);a.put("canonical_phone",snapshot.seed().phone());a.put("final_ack_hash",ack.get("final_ack_hash"));a.put("publication_revision",ack.get("publication_revision"));
        var stage=snapshot.stageAcknowledgement();a.put("user_baseline_generation",stage.get("baseline_generation"));a.put("user_contact_mutation_revision",stage.get("contact_mutation_revision"));a.put("projection_present",stage.get("projection_present"));a.put("user_projection_revision",stage.get("projection_revision"));a.put("user_phone_contact_version",stage.get("phone_contact_version"));a.put("user_verification_epoch",stage.get("verification_epoch"));
        a.put("created_at",created);a.put("version_created_at",created);a.put("suspension_reason",null);return a;
    }
    private static final List<String> GRAPH_QUERIES=List.of(
        "select to_jsonb(t)::text from legacy_migration_intents t where id=?",
        "select to_jsonb(t)::text from legacy_migration_otp_stages t where migration_id=?",
        "select to_jsonb(t)::text from legacy_migration_otp_stage_acks t where migration_id=?",
        "select jsonb_agg(to_jsonb(t) order by purpose)::text from legacy_migration_otp_receipts t where migration_id=?",
        "select jsonb_agg(to_jsonb(t) order by id)::text from legacy_migration_otp_challenges t where id in(select challenge_id from legacy_migration_otp_receipts where migration_id=?)",
        "select jsonb_agg(to_jsonb(t) order by purpose)::text from legacy_migration_otp_candidates t where migration_id=?",
        "select to_jsonb(t)::text from legacy_migration_email_candidates t where migration_id=?",
        "select to_jsonb(t)::text from legacy_migration_contact_baselines t where migration_id=?",
        "select to_jsonb(t)::text from legacy_migration_contact_transitions t where migration_id=?",
        "select to_jsonb(t)::text from legacy_migration_owner_contacts t where identity_id=(select identity_id from legacy_migration_intents where id=?)",
        "select to_jsonb(t)::text from legacy_migration_managed_receipts t where migration_id=?",
        "select to_jsonb(t)::text from legacy_migration_managed_targets t where migration_id=?",
        "select to_jsonb(t)::text from legacy_migration_publication_decisions t where migration_id=?",
        "select to_jsonb(t)::text from legacy_migration_verified_final_acks t where migration_id=?",
        "select to_jsonb(t)::text from legacy_migration_final_commands t where migration_id=?",
        "select to_jsonb(t)::text from identities t where id=(select identity_id from legacy_migration_intents where id=?)");
    private static Map<String,String> originalGraph(DSLContext sql,UUID migration){var result=new LinkedHashMap<String,String>();for(String query:GRAPH_QUERIES){Record row=sql.fetchOne(query,migration);result.put(query,row==null?"NONE":Objects.toString(row.get(0,String.class),"NONE"));}return Collections.unmodifiableMap(result);}
    private static Map<String,Object> normalizedMap(Map<String,Object> row){var result=new TreeMap<String,Object>();row.forEach((key,value)->result.put(key,value instanceof byte[] bytes?HexFormat.of().formatHex(bytes):value instanceof OffsetDateTime time?normalized(time.toInstant()):value instanceof Instant time?normalized(time):(value instanceof Byte||value instanceof Short||value instanceof Integer||value instanceof Long)?((Number)value).longValue():value));return result;}
    private static void requireOwnRow(DSLContext sql,String table,String column,UUID id,Map<String,Object> expected){
        String query=switch(table){case "managed_identity_sources"->"select * from managed_identity_sources where identity_id=?";case "migration_banking_authority_current"->"select * from migration_banking_authority_current where identity_id=?";default->throw unavailable();};
        Record row=sql.fetchOne(query,id);if(row==null||!normalizedMap(expected).equals(normalizedMap(row.intoMap())))throw unavailable();
    }
    private static Record record(DSLContext sql,Map<String,Object> values){Record row=sql.newRecord(values.keySet().stream().map(name->DSL.field(DSL.name(name),Object.class)).toArray(org.jooq.Field[]::new));row.fromMap(values);return row;}
    private static void insert(DSLContext sql,String table,Map<String,Object> values){if(!Set.of("managed_identity_sources","migration_banking_authorities","migration_banking_grants","migration_banking_authority_current").contains(table))throw unavailable();Object[] data=values.values().stream().map(v->v instanceof Instant time?OffsetDateTime.ofInstant(time,ZoneOffset.UTC):v).toArray();if(sql.insertInto(DSL.table(DSL.name(table))).columns(values.keySet().stream().map(name->DSL.field(DSL.name(name))).toArray(org.jooq.Field[]::new)).values(data).execute()!=1)throw unavailable();}
    private static Instant now(DSLContext sql){return sql.fetchOne("select clock_timestamp()").get(0,OffsetDateTime.class).toInstant();}
    private static Instant normalized(Instant time){return time.truncatedTo(java.time.temporal.ChronoUnit.MICROS);}
    /** No public/positive factory. Only genuine source promotion may construct this scoped type. */
    static final class Promotion {
        private final DSLContext sql;private final Thread owner=Thread.currentThread();private final long tx;
        private final PostgresLegacyMigrationOtpStore.FinalizationSnapshot snapshot;
        private final PostgresLegacyMigrationManagedProofStore.EvidenceUse evidence;
        private final UUID decision,identity,migration;private final long classification,ownerGeneration,modeGeneration,association,epoch,revision;
        private final byte[] stamp;private final String key;private final Consumer<DSLContext> validator;
        private Promotion(DSLContext sql,PostgresLegacyMigrationOtpStore.FinalizationSnapshot snapshot,PostgresLegacyMigrationManagedProofStore.EvidenceUse evidence,
                          UUID decision,UUID identity,UUID migration,long classification,long ownerGeneration,long modeGeneration,long association,long epoch,long revision,byte[] stamp,String key,Consumer<DSLContext> validator){
            this.sql=sql;this.snapshot=snapshot;this.evidence=evidence;tx=sql.fetchOne("select txid_current()").get(0,Long.class);this.decision=decision;this.identity=identity;this.migration=migration;this.classification=classification;this.ownerGeneration=ownerGeneration;this.modeGeneration=modeGeneration;this.association=association;this.epoch=epoch;this.revision=revision;this.stamp=stamp.clone();this.key=key;this.validator=validator;
        }
        private void base(DSLContext current){if(current!=sql||Thread.currentThread()!=owner||Thread.currentThread().isInterrupted())throw unavailable();evidence.requireObservation(current);if(tx!=current.fetchOne("select txid_current()").get(0,Long.class).longValue())throw unavailable();}
        void requireCurrent(DSLContext current){base(current);validator.accept(current);}
        void requireOriginalSnapshot(DSLContext current,PostgresLegacyMigrationOtpStore.FinalizationSnapshot original){base(current);if(original!=snapshot)throw unavailable();}
        String sourceKind(){base(sql);return "LEGACY_MIGRATION_DUAL_CONTACT";}UUID sourceId(){base(sql);return decision;}UUID identityId(){base(sql);return identity;}UUID migrationId(){base(sql);return migration;}
        long classificationGeneration(){base(sql);return classification;}long resultingOwnerGeneration(){base(sql);return ownerGeneration;}long resultingModeGeneration(){base(sql);return modeGeneration;}long associationGeneration(){base(sql);return association;}long verificationEpoch(){base(sql);return epoch;}long sourceRevision(){base(sql);return revision;}
        byte[] authorityStamp(){base(sql);return stamp.clone();}String retentionKeyId(){base(sql);return key;}
        @Override public String toString(){return "MigrationPromotion[redacted]";}
    }
    private static LegacyMigrationFinalization.Failure unavailable(){return new LegacyMigrationFinalization.Failure(LegacyMigrationFinalization.Error.UNAVAILABLE);}
    @Override public String toString(){return "PostgresMigrationBankingEligibilityStore[redacted]";}
}
