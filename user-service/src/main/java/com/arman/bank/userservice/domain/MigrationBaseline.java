package com.arman.bank.userservice.domain;
import java.util.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
/** Private evidence, never banking authority. */
public final class MigrationBaseline {
 private MigrationBaseline() {}
 public static final List<String> STAGE=List.of("protocol_version","migration_id","stage_operation_id","command_id","decision_command_id","identity_id","profile_id","canonical_phone","email_contact_version","email_digest","policy_version","created_at","authorization_deadline","payload_hash");
 public static final List<String> TUPLE=List.of("migration_id","decision_id","stage_operation_id","command_id","payload_hash");
 public static final List<String> ACK=List.of("protocol_version","migration_id","stage_operation_id","identity_id","profile_id","baseline_generation","contact_mutation_revision","projection_present","projection_revision","phone_contact_version","verification_epoch","email_contact_version","email_digest","baseline_hash","authorization_deadline","payload_hash","stage_ack_hash");
 public static final List<String> FINAL=List.of("protocol_version","migration_id","decision_id","stage_operation_id","command_id","identity_id","profile_id","baseline_generation","contact_mutation_revision","baseline_hash","stage_ack_hash","publication_revision","email_contact_version","email_receipt_id","email_receipt_generation","phone_receipt_id","phone_receipt_generation","receipt_snapshot_hash","payload_hash","final_ack_hash");
 public record Value(Map<String,Object> fields) {
  public Value { fields=Collections.unmodifiableMap(new LinkedHashMap<>(fields)); }
  public UUID id(String k) { return uuid(fields.get(k)); }
  public long number(String k) { return integer(fields.get(k),false); }
  public String text(String k) { return (String)fields.get(k); }
  @Override public String toString() { return "MigrationBaseline.Value[redacted]"; }
 }
 public static Value stage(Map<String,Object> f) {
  exact(f,STAGE);
  for(String k:List.of("migration_id","stage_operation_id","command_id","decision_command_id","identity_id","profile_id")) uuid(f.get(k));
  if(integer(f.get("protocol_version"),false)!=1) throw new IllegalArgumentException();
  for(String k:List.of("email_contact_version","policy_version")) integer(f.get(k),false);
  long start=integer(f.get("created_at"),true),end=integer(f.get("authorization_deadline"),true);
  if(end<=start || Math.subtractExact(end,start)>900000) throw new IllegalArgumentException();
  if(!(f.get("canonical_phone") instanceof String phone)||!phone.matches("\\+9715[024568][0-9]{7}")) throw new IllegalArgumentException();
  digest(f.get("email_digest")); digest(f.get("payload_hash"));
  if(!hash("armoney-user-migration-stage-v1",STAGE.subList(0,STAGE.size()-1),f).equals(f.get("payload_hash"))) throw new IllegalArgumentException();
  return new Value(f);
 }
 public static Value tuple(Map<String,Object> f) { exact(f,TUPLE); for(String k:TUPLE.subList(0,4)) uuid(f.get(k)); digest(f.get("payload_hash")); return new Value(f); }
 public static void exact(Map<String,Object> f,List<String> keys) { if(f.size()!=keys.size()||!f.keySet().equals(new HashSet<>(keys))) throw new IllegalArgumentException(); }
 public static UUID uuid(Object v) { if(!(v instanceof String s)) throw new IllegalArgumentException(); UUID id=UUID.fromString(s); if(!id.toString().equals(s)||id.equals(new UUID(0,0))) throw new IllegalArgumentException(); return id; }
 public static long integer(Object v,boolean zero) { if(!(v instanceof Long||v instanceof Integer)) throw new IllegalArgumentException(); long n=((Number)v).longValue(); if(n<(zero?0:1)) throw new IllegalArgumentException(); return n; }
 public static void digest(Object v) { if(!(v instanceof String s)||!s.matches("[0-9a-f]{64}")) throw new IllegalArgumentException(); }
 public static String hash(String domain,List<String> keys,Map<String,Object> f) {
  try { var d=MessageDigest.getInstance("SHA-256"); var values=new ArrayList<Object>(); values.add(domain); keys.forEach(k->values.add(f.get(k)));
   for(Object v:values) { byte[] b=String.valueOf(v).getBytes(StandardCharsets.UTF_8); d.update(ByteBuffer.allocate(4).putInt(b.length).array()); d.update(b); } return HexFormat.of().formatHex(d.digest());
  } catch(java.security.NoSuchAlgorithmException e) { throw new IllegalStateException("Hash unavailable"); }
 }
}
