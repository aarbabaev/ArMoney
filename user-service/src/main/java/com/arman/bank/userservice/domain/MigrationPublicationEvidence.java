package com.arman.bank.userservice.domain;
import java.util.*;
public record MigrationPublicationEvidence(Map<String,Object> fields) {
 public static final List<String> FIELDS=List.of("protocol_version","migration_id","decision_id","stage_operation_id","identity_id","baseline_hash","stage_ack_hash","email_contact_version","email_digest","email_receipt_id","email_receipt_generation","phone_receipt_id","phone_receipt_generation","receipt_snapshot_hash","policy_version","payload_hash","state");
 public MigrationPublicationEvidence {
  MigrationBaseline.exact(fields,FIELDS);
  for(String k:List.of("migration_id","decision_id","stage_operation_id","identity_id","email_receipt_id","phone_receipt_id")) MigrationBaseline.uuid(fields.get(k));
  for(String k:List.of("protocol_version","email_contact_version","email_receipt_generation","phone_receipt_generation","policy_version")) MigrationBaseline.integer(fields.get(k),false);
  if(((Number)fields.get("protocol_version")).longValue()!=1||!Set.of("PUBLICATION_AUTHORIZED","COMPLETED").contains(fields.get("state"))) throw new IllegalArgumentException();
  for(String k:List.of("baseline_hash","stage_ack_hash","email_digest","receipt_snapshot_hash","payload_hash")) MigrationBaseline.digest(fields.get(k));
  var order=new ArrayList<>(FIELDS); order.remove("receipt_snapshot_hash"); order.remove("state");
  if(!MigrationBaseline.hash("armoney-legacy-migration-receipts-v1",order,fields).equals(fields.get("receipt_snapshot_hash"))) throw new IllegalArgumentException();
  fields=Collections.unmodifiableMap(new LinkedHashMap<>(fields));
 }
 @Override public String toString() { return "MigrationPublicationEvidence[redacted]"; }
}
