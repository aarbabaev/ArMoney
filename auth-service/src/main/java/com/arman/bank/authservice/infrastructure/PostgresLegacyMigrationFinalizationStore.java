package com.arman.bank.authservice.infrastructure;

import com.arman.bank.authservice.application.LegacyMigrationFinalizationStore;
import com.arman.bank.authservice.domain.LegacyMigrationFinalization;
import com.arman.bank.authservice.domain.LegacyMigrationFinalization.*;
import com.arman.bank.authservice.domain.LegacyMigrationFinalization.Error;
import com.arman.bank.runtime.Database;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.function.Function;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.impl.DSL;

/** Irreversible publication authorization only. No final authority, registration or session mutation. */
public final class PostgresLegacyMigrationFinalizationStore implements LegacyMigrationFinalizationStore {
    private static final String TABLE="legacy_migration_publication_decisions";
    private static final ObjectMapper JSON=new ObjectMapper();
    private final Database database;
    private final PostgresLegacyMigrationOtpStore otp;
    private final MigrationBindingSqlPort binding;
    private final LegacyMigrationManagedProofCrypto crypto;
    private final String actor;
    private final boolean enabled;
    public PostgresLegacyMigrationFinalizationStore(Database database,PostgresLegacyMigrationOtpStore otp,MigrationBindingSqlPort binding,LegacyMigrationManagedProofCrypto crypto,String actor,boolean enabled){
        this.database=Objects.requireNonNull(database);this.otp=Objects.requireNonNull(otp);this.binding=Objects.requireNonNull(binding);this.crypto=Objects.requireNonNull(crypto);
        if(actor==null||!actor.matches("[A-Za-z0-9_-]{1,64}"))throw new IllegalArgumentException("Invalid migration actor");this.actor=actor;this.enabled=enabled;
    }
    private void requireEnabled(){if(!enabled)throw new Failure(Error.UNAVAILABLE);}
    @Override public <T>T inRequest(UUID migration,String capability,Function<Request,T> action){
        requireEnabled();LegacyMigrationFinalization.id(migration);if(capability==null||!capability.matches("[A-Za-z0-9_-]{43}"))throw new Failure(Error.UNAUTHORIZED);Objects.requireNonNull(action);
        return new PostgresLoginRequestScope(database).inRequest(actor,owned->inOwnedRequest(owned,migration,capability,action));
    }
    <T>T inOwnedRequest(PostgresLoginRequestScope.Request owned,UUID migration,String capability,Function<Request,T> action){
        requireEnabled();LegacyMigrationFinalization.id(migration);if(capability==null||!capability.matches("[A-Za-z0-9_-]{43}"))throw new Failure(Error.UNAUTHORIZED);
        if(owned==null)throw new Failure(Error.UNAVAILABLE);owned.check();if(!actor.equals(owned.actor()))throw new Failure(Error.UNAVAILABLE);Objects.requireNonNull(action);
        Scoped scoped=new Scoped(owned,migration,capability);
        try{T result=action.apply(scoped);scoped.check();owned.verify();return result;}finally{scoped.closed=true;}
    }
    Decision authorize(PostgresLoginRequestScope.Request owned,UUID migration,String capability){return inOwnedRequest(owned,migration,capability,Request::authorize);}
    private final class Scoped implements Request {
        private final PostgresLoginRequestScope.Request owned;
        private final UUID migration;
        private final String capability;
        private final Thread thread=Thread.currentThread();
        private final Instant originalStart;
        private final long started=System.nanoTime();
        private boolean used,closed;
        Scoped(PostgresLoginRequestScope.Request owned,UUID migration,String capability){this.owned=owned;this.migration=migration;this.capability=capability;originalStart=owned.transaction(PostgresLegacyMigrationFinalizationStore::now);}
        void check(){owned.check();if(closed||thread!=Thread.currentThread()||Thread.currentThread().isInterrupted())throw new Failure(Error.UNAVAILABLE);}
        @Override public Decision authorize(){
            check();if(used)throw new Failure(Error.UNAVAILABLE);used=true;
            Decision result=binding.withEvidence(owned,migration,originalStart,started,evidence->owned.transaction(sql->{
                check();var snapshot=otp.requireFinalization(sql,migration,capability,evidence);snapshot.requireCurrent(sql);
                Record prior=sql.fetchOne("select * from legacy_migration_publication_decisions where migration_id=?",migration);
                if(prior!=null){if(!actor.equals(prior.get("writer_id",String.class)))throw new Failure(Error.CONFLICT);Decision replay=verified(prior).decision();snapshot.requireCurrent(sql);check();return replay;}
                if(sql.fetchOne("select count(*) from legacy_migration_publication_decisions").get(0,Long.class)>=10000)throw new Failure(Error.RATE_LIMITED);
                Record intent=sql.fetchOne("select * from legacy_migration_intents where id=?",migration);
                Record managed=sql.fetchOne("select * from legacy_migration_managed_receipts where receipt_id=? and migration_id=?",evidence.receiptId(),migration);
                if(intent==null||managed==null)throw new Failure(Error.UNAVAILABLE);
                var ack=snapshot.stageAcknowledgement();var stage=snapshot.stageRequest();var row=new LinkedHashMap<String,Object>();
                row.put("decision_id",UUID.randomUUID());row.put("migration_id",migration);row.put("identity_id",snapshot.seed().identityId());row.put("stage_operation_id",snapshot.seed().stageOperationId());row.put("decision_command_id",snapshot.seed().decisionCommandId());row.put("publication_command_id",UUID.randomUUID());row.put("writer_id",actor);
                row.put("owner_generation",snapshot.ownerGeneration());row.put("mode_generation",snapshot.modeGeneration());row.put("email_generation",snapshot.emailGeneration());row.put("proof_mode",intent.get("proof_mode",String.class));row.put("binding_version",LegacyMigrationContactStore.candidate(sql,migration)==null?1:2);
                row.put("managed_receipt_id",managed.get("receipt_id",UUID.class));row.put("managed_receipt_hash",managed.get("receipt_hash",String.class));row.put("target_binding_hash",snapshot.targetBindingHash());row.put("baseline_hash",ack.get("baseline_hash"));row.put("stage_ack_hash",ack.get("stage_ack_hash"));row.put("binding_hash",snapshot.bindingHash());
                row.put("email_receipt_id",snapshot.emailReceiptId());row.put("email_receipt_generation",snapshot.emailReceiptGeneration());row.put("phone_receipt_id",snapshot.phoneReceiptId());row.put("phone_receipt_generation",snapshot.phoneReceiptGeneration());row.put("email_contact_version",LegacyMigrationFinalization.number(ack.get("email_contact_version"),false));row.put("email_digest",ack.get("email_digest"));row.put("policy_version",LegacyMigrationFinalization.number(stage.get("policy_version"),false));row.put("payload_hash",ack.get("payload_hash"));
                row.put("original_account_deadline",normalized(snapshot.seed().accountDeadline()));row.put("original_intent_deadline",normalized(snapshot.seed().intentDeadline()));row.put("created_at",normalized(now(sql)));
                row.put("receipt_snapshot_hash",LegacyMigrationFinalization.receiptHash(evidenceFields(row)));row.put("retention_key_id",managed.get("retention_key_id",String.class));row.put("journal_hash",journalHash(row));row.put("state",LegacyMigrationFinalization.STATE);
                Evidence proof=new Evidence(evidenceFields(row));Decision decision=new Decision(decisionFields(row));row.put("evidence_json",json(proof.fields()));row.put("decision_json",json(decision.fields()));
                snapshot.requireCurrent(sql);validateJournal(row);
                List<String> names=new ArrayList<>(row.keySet());Object[] values=names.stream().map(k->{Object value=row.get(k);return value instanceof Instant instant?OffsetDateTime.ofInstant(instant,ZoneOffset.UTC):value;}).toArray();
                sql.insertInto(DSL.table(TABLE)).columns(names.stream().map(DSL::field).toArray(org.jooq.Field[]::new)).values(values).execute();
                snapshot.requireCurrent(sql);check();return decision;
            }));owned.verify();check();return result;
        }
    }
    /** Technical retained journal read; never fresh account/provider or final authority. */
    JournalSnapshot requireJournal(DSLContext sql,UUID migration){
        requireEnabled();Map<String,Object> fields=requireHistoricalJournal(sql,migration,crypto);
        if(!actor.equals(fields.get("writer_id")))throw new Failure(Error.UNAVAILABLE);
        return new JournalSnapshot(sql,fields);
    }
    /** Shared original-journal codec for source validators. Data only, no current authority. */
    static Map<String,Object> requireHistoricalJournal(DSLContext sql,UUID migration,LegacyMigrationManagedProofCrypto crypto){
        PostgresVerificationProjectionStore.requireLocked(sql);LegacyMigrationFinalization.id(migration);Objects.requireNonNull(crypto);
        Record row=sql.fetchOne("select * from legacy_migration_publication_decisions where migration_id=?",migration);if(row==null)throw new Failure(Error.UNAVAILABLE);
        verified(row,crypto);var fields=new LinkedHashMap<String,Object>();for(var entry:row.intoMap().entrySet()){Object value=entry.getValue();if(value instanceof OffsetDateTime time)value=time.toInstant();fields.put(entry.getKey(),value);}
        return Collections.unmodifiableMap(fields);
    }    static final class JournalSnapshot {
        private final DSLContext sql;private final long transaction;private final int pid;private final OffsetDateTime backendStart;private final Thread thread=Thread.currentThread();private final Map<String,Object> fields;
        private JournalSnapshot(DSLContext sql,Map<String,Object> fields){this.sql=sql;this.fields=fields;var identity=sql.fetchOne("select txid_current(),pg_backend_pid(),backend_start from pg_stat_activity where pid=pg_backend_pid()");transaction=identity.get(0,Long.class);pid=identity.get(1,Integer.class);backendStart=identity.get(2,OffsetDateTime.class);}
        void requireCurrent(DSLContext current){
            if(current!=sql||Thread.currentThread()!=thread||Thread.currentThread().isInterrupted())throw new Failure(Error.UNAVAILABLE);PostgresVerificationProjectionStore.requireLocked(current);
            var identity=current.fetchOne("select txid_current(),pg_backend_pid(),backend_start from pg_stat_activity where pid=pg_backend_pid()");
            if(identity==null||transaction!=identity.get(0,Long.class)||pid!=identity.get(1,Integer.class)||!backendStart.equals(identity.get(2,OffsetDateTime.class)))throw new Failure(Error.UNAVAILABLE);
        }
        Map<String,Object> fields(){requireCurrent(sql);return fields;}
        @Override public String toString(){return "MigrationJournalSnapshot[redacted]";}
    }
    private record Verified(Decision decision,Evidence evidence){}
    @Override public Decision decision(DecisionQuery query){Objects.requireNonNull(query);return read(query.migrationId(),null,query.stageOperationId(),query.commandId(),query.payloadHash()).decision();}
    @Override public Evidence evidence(EvidenceQuery query){Objects.requireNonNull(query);return read(query.migrationId(),query.decisionId(),query.stageOperationId(),query.commandId(),query.payloadHash()).evidence();}
    private Verified read(UUID migration,UUID decision,UUID stage,UUID command,String payload){
        requireEnabled();return new PostgresLoginRequestScope(database).inRequest(actor,owned->{Verified result=owned.transaction(sql->{
            Record row=sql.fetchOne("select * from legacy_migration_publication_decisions where migration_id=?",migration);if(row==null)throw new Failure(Error.UNAVAILABLE);
            if(!actor.equals(row.get("writer_id",String.class))||decision!=null&&!decision.equals(row.get("decision_id",UUID.class))||!stage.equals(row.get("stage_operation_id",UUID.class))||!command.equals(row.get("decision_command_id",UUID.class))||!payload.equals(row.get("payload_hash",String.class)))throw new Failure(Error.CONFLICT);
            Verified original=verified(row);
            var journal=requireJournal(sql,migration);journal.requireCurrent(sql);
            var completion=PostgresMigrationFinalAckStore.validatedCompletion(sql,journal.fields(),crypto,actor);
            if(completion.isEmpty())return original;
            var decisionFields=new LinkedHashMap<>(original.decision().fields());decisionFields.put("state","COMPLETED");
            var evidenceFields=new LinkedHashMap<>(original.evidence().fields());evidenceFields.put("state","COMPLETED");
            return new Verified(new Decision(decisionFields),new Evidence(evidenceFields));
        });owned.verify();return result;});
    }
    private Verified verified(Record record){return verified(record,crypto);}
    private static Verified verified(Record record,LegacyMigrationManagedProofCrypto crypto){
        try{
            var row=new LinkedHashMap<String,Object>();for(String key:LegacyMigrationFinalization.JOURNAL){Object value=record.get(key);if(value instanceof OffsetDateTime time)value=time.toInstant();row.put(key,value);}row.put("retention_key_id",record.get("retention_key_id",String.class));validateJournal(row);
            if(!LegacyMigrationFinalization.STATE.equals(record.get("state",String.class))||!MessageDigest.isEqual(journalHash(row,crypto).getBytes(java.nio.charset.StandardCharsets.US_ASCII),record.get("journal_hash",String.class).getBytes(java.nio.charset.StandardCharsets.US_ASCII)))throw new Failure(Error.UNAVAILABLE);
            Evidence evidence=new Evidence(evidenceFields(row));Decision decision=new Decision(decisionFields(row));if(!json(evidence.fields()).equals(record.get("evidence_json",String.class))||!json(decision.fields()).equals(record.get("decision_json",String.class)))throw new Failure(Error.UNAVAILABLE);return new Verified(decision,evidence);
        }catch(RuntimeException corrupt){throw new Failure(Error.UNAVAILABLE);}
    }
    private static void validateJournal(Map<String,Object> row){
        for(String key:List.of("decision_id","migration_id","identity_id","stage_operation_id","decision_command_id","publication_command_id","managed_receipt_id","email_receipt_id","phone_receipt_id"))LegacyMigrationFinalization.id((UUID)row.get(key));
        for(String key:List.of("owner_generation","mode_generation","email_generation"))LegacyMigrationFinalization.number(row.get(key),true);
        for(String key:List.of("email_receipt_generation","phone_receipt_generation","email_contact_version","policy_version"))LegacyMigrationFinalization.number(row.get(key),false);
        for(String key:List.of("managed_receipt_hash","target_binding_hash","baseline_hash","stage_ack_hash","binding_hash","email_digest","payload_hash","receipt_snapshot_hash"))LegacyMigrationFinalization.digest(row.get(key));
        if(!(row.get("writer_id") instanceof String writer)||!writer.matches("[A-Za-z0-9_-]{1,64}")||!(row.get("retention_key_id") instanceof String key)||!key.matches("[A-Za-z0-9_-]{1,32}")||!Set.of("LOCAL_PASSWORD","LEGACY_OIDC_REAUTH").contains(row.get("proof_mode"))||!Set.of(1,2).contains(row.get("binding_version"))||row.get("email_receipt_id").equals(row.get("phone_receipt_id"))||row.get("decision_command_id").equals(row.get("publication_command_id")))throw new Failure(Error.UNAVAILABLE);
        Instant created=(Instant)row.get("created_at");if(!created.isBefore((Instant)row.get("original_account_deadline"))||!created.isBefore((Instant)row.get("original_intent_deadline")))throw new Failure(Error.UNAVAILABLE);
    }
    private String journalHash(Map<String,Object> row){return journalHash(row,crypto);}
    private static String journalHash(Map<String,Object> row,LegacyMigrationManagedProofCrypto crypto){var fields=new ArrayList<String>();fields.add("1");for(String key:LegacyMigrationFinalization.JOURNAL){Object value=row.get(key);fields.add(value instanceof Instant instant?LegacyMigrationFinalization.instant(instant):String.valueOf(value));}return HexFormat.of().formatHex(crypto.hash((String)row.get("retention_key_id"),LegacyMigrationFinalization.JOURNAL_DOMAIN,fields.toArray(String[]::new)));}
    private static Map<String,Object> evidenceFields(Map<String,Object> row){
        var fields=new LinkedHashMap<String,Object>();for(String key:LegacyMigrationFinalization.EVIDENCE){Object value=switch(key){case "protocol_version"->1L;case "state"->LegacyMigrationFinalization.STATE;default->row.get(key);};fields.put(key,value instanceof UUID id?id.toString():value);}return fields;
    }
    private static Map<String,Object> decisionFields(Map<String,Object> row){var fields=new LinkedHashMap<String,Object>();for(String key:LegacyMigrationFinalization.DECISION){Object value=key.equals("state")?LegacyMigrationFinalization.STATE:row.get(key);fields.put(key,value instanceof UUID id?id.toString():value);}return fields;}
    private static String json(Map<String,Object> value){try{String result=JSON.writeValueAsString(value);if(result.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>12288)throw new Failure(Error.UNAVAILABLE);return result;}catch(com.fasterxml.jackson.core.JsonProcessingException invalid){throw new Failure(Error.UNAVAILABLE);}}
    private static Instant normalized(Instant value){return value.truncatedTo(java.time.temporal.ChronoUnit.MICROS);}
    private static Instant now(DSLContext sql){return sql.fetchOne("select clock_timestamp()").get(0,OffsetDateTime.class).toInstant();}
    @Override public String toString(){return "MigrationFinalizationStore[redacted]";}
}
