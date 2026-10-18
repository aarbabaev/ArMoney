package com.arman.bank.authservice.domain;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

/** Publication evidence only. Never registration authority or a banking/session grant. */
public final class LegacyMigrationFinalization {
    private LegacyMigrationFinalization() {}
    public static final String STATE="PUBLICATION_AUTHORIZED";
    public static final Set<String> READ_STATES=Set.of(STATE,"COMPLETED");
    public static final String JOURNAL_DOMAIN="armoney-legacy-migration-publication-journal-v1";
    public static final List<String> DECISION_QUERY=List.of("migration_id","stage_operation_id","command_id","payload_hash");
    public static final List<String> EVIDENCE_QUERY=List.of("migration_id","decision_id","stage_operation_id","command_id","payload_hash");
    public static final List<String> DECISION=List.of("decision_id","migration_id","stage_operation_id","payload_hash","state");
    public static final List<String> EVIDENCE=List.of("protocol_version","migration_id","decision_id","stage_operation_id","identity_id","baseline_hash","stage_ack_hash","email_contact_version","email_digest","email_receipt_id","email_receipt_generation","phone_receipt_id","phone_receipt_generation","receipt_snapshot_hash","policy_version","payload_hash","state");
    public static final List<String> JOURNAL=List.of("decision_id","migration_id","identity_id","stage_operation_id","decision_command_id","publication_command_id","writer_id","owner_generation","mode_generation","email_generation","proof_mode","binding_version","managed_receipt_id","managed_receipt_hash","target_binding_hash","baseline_hash","stage_ack_hash","binding_hash","email_receipt_id","email_receipt_generation","phone_receipt_id","phone_receipt_generation","email_contact_version","email_digest","policy_version","payload_hash","receipt_snapshot_hash","original_account_deadline","original_intent_deadline","created_at");
    public enum Error { BAD_INPUT, UNAUTHORIZED, CONFLICT, RATE_LIMITED, UNAVAILABLE }
    public static final class Failure extends RuntimeException {
        private final Error error;
        public Failure(Error error){super("Migration publication unavailable",null,false,false);this.error=Objects.requireNonNull(error);}
        public Error error(){return error;}
    }
    public record DecisionQuery(UUID migrationId,UUID stageOperationId,UUID commandId,String payloadHash) {
        public DecisionQuery {id(migrationId);id(stageOperationId);id(commandId);digest(payloadHash);}
        @Override public String toString(){return "MigrationDecisionQuery[redacted]";}
    }
    public record EvidenceQuery(UUID migrationId,UUID decisionId,UUID stageOperationId,UUID commandId,String payloadHash) {
        public EvidenceQuery {id(migrationId);id(decisionId);id(stageOperationId);id(commandId);digest(payloadHash);}
        @Override public String toString(){return "MigrationEvidenceQuery[redacted]";}
    }
    public record Decision(Map<String,Object> fields) {
        public Decision {exact(fields,DECISION);for(String k:List.of("decision_id","migration_id","stage_operation_id"))uuid(fields.get(k));digest(fields.get("payload_hash"));if(!(fields.get("state") instanceof String state)||!READ_STATES.contains(state))bad();fields=immutable(fields);}
        @Override public String toString(){return "MigrationPublicationDecision[redacted]";}
    }
    public record Evidence(Map<String,Object> fields) {
        public Evidence {exact(fields,EVIDENCE);for(String k:List.of("migration_id","decision_id","stage_operation_id","identity_id","email_receipt_id","phone_receipt_id"))uuid(fields.get(k));for(String k:List.of("protocol_version","email_contact_version","email_receipt_generation","phone_receipt_generation","policy_version"))number(fields.get(k),false);if(number(fields.get("protocol_version"),false)!=1||!(fields.get("state") instanceof String state)||!READ_STATES.contains(state)||fields.get("email_receipt_id").equals(fields.get("phone_receipt_id")))bad();for(String k:List.of("baseline_hash","stage_ack_hash","email_digest","receipt_snapshot_hash","payload_hash"))digest(fields.get(k));if(!receiptHash(fields).equals(fields.get("receipt_snapshot_hash")))bad();fields=immutable(fields);}
        @Override public String toString(){return "MigrationPublicationEvidence[redacted]";}
    }
    public static Map<String,Object> immutable(Map<String,Object> fields){return Collections.unmodifiableMap(new LinkedHashMap<>(fields));}
    public static void exact(Map<String,Object> fields,List<String> keys){if(fields==null||fields.size()!=keys.size()||!fields.keySet().equals(new HashSet<>(keys)))bad();}
    public static UUID uuid(Object value){if(!(value instanceof String))throw new Failure(Error.BAD_INPUT);try{UUID id=UUID.fromString((String)value);id(id);if(!id.toString().equals(value))bad();return id;}catch(IllegalArgumentException bad){throw new Failure(Error.BAD_INPUT);}}
    public static void id(UUID id){if(id==null||id.equals(new UUID(0,0)))bad();}
    public static void digest(Object value){if(!(value instanceof String text)||!text.matches("[0-9a-f]{64}"))bad();}
    public static long number(Object value,boolean zero){if(!(value instanceof Integer||value instanceof Long))throw new Failure(Error.BAD_INPUT);long n=((Number)value).longValue();if(n<(zero?0:1))bad();return n;}
    public static String instant(Instant value){if(value==null)throw new Failure(Error.BAD_INPUT);return value.truncatedTo(ChronoUnit.MICROS).toString();}
    public static String receiptHash(Map<String,Object> fields){
        try{var hash=MessageDigest.getInstance("SHA-256");var ordered=new ArrayList<String>();ordered.add("armoney-legacy-migration-receipts-v1");for(String key:EVIDENCE)if(!key.equals("receipt_snapshot_hash")&&!key.equals("state"))ordered.add(String.valueOf(fields.get(key)));for(String value:ordered){byte[] bytes=value.getBytes(StandardCharsets.UTF_8);hash.update(ByteBuffer.allocate(4).putInt(bytes.length).array());hash.update(bytes);}return HexFormat.of().formatHex(hash.digest());}catch(java.security.NoSuchAlgorithmException unavailable){throw new Failure(Error.UNAVAILABLE);}
    }
    private static void bad(){throw new Failure(Error.BAD_INPUT);}
}
