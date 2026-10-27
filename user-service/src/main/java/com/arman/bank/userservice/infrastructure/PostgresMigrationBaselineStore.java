package com.arman.bank.userservice.infrastructure;

import com.arman.bank.runtime.Database;
import com.arman.bank.userservice.application.*;
import com.arman.bank.userservice.domain.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jooq.DSLContext;
import org.jooq.Record;
import java.util.*;
import static com.arman.bank.userservice.application.MigrationBaselineStore.Rejected;

/** Central User snapshots only. All remote evidence is resolved outside transactions. */
public final class PostgresMigrationBaselineStore implements MigrationBaselineStore {
 private static final ObjectMapper JSON=new ObjectMapper();
 private final ProfileShards shards;
 private final Database database;
 private final MigrationDecisionPort decisions;
 private final MigrationPublicationEvidencePort evidence;
 private final int unresolvedCapacity,retainedCapacity;
 public PostgresMigrationBaselineStore(ProfileShards shards,MigrationDecisionPort decisions,MigrationPublicationEvidencePort evidence) {
  this(shards,decisions,evidence,10000,100000);
 }
 public PostgresMigrationBaselineStore(ProfileShards shards,MigrationDecisionPort decisions,MigrationPublicationEvidencePort evidence,int unresolved,int retained) {
  if(unresolved<1||unresolved>10000||retained<1||retained>100000) throw new IllegalArgumentException();
  this.shards=Objects.requireNonNull(shards); database=shards.primary(); this.decisions=Objects.requireNonNull(decisions); this.evidence=Objects.requireNonNull(evidence);
  unresolvedCapacity=unresolved; retainedCapacity=retained;
 }
 static void lock(DSLContext sql) { sql.fetch("select pg_advisory_xact_lock(725631904128::bigint)"); }
 public static void requireUnreserved(DSLContext sql,UUID owner) {
  if(sql.fetchOne("select migration_id from migration_baselines where identity_id=? and state in ('RESERVED','PUBLISHED')",owner)!=null) throw new Rejected(423);
 }
 private static void writer(String writer) { if(writer==null||!writer.matches("[A-Za-z0-9._-]{1,64}")) throw new IllegalArgumentException(); }
 private static String json(Map<String,Object> m) { try { return JSON.writeValueAsString(m); } catch(Exception e) { throw new Rejected(503); } }
 private static Map<String,Object> map(Object value) { try { return JSON.readValue(value.toString(),new TypeReference<LinkedHashMap<String,Object>>(){}); } catch(Exception e) { throw new Rejected(503); } }
 private static Map<String,Object> immutable(Map<String,Object> m) { return Collections.unmodifiableMap(new LinkedHashMap<>(m)); }
 private static Map<String,Object> retainedCommand(MigrationBaseline.Value c) {
  var fields=new LinkedHashMap<>(c.fields()); fields.remove("canonical_phone"); return fields;
 }
 private MigrationBaseline.Value stageCommand(Record r) {
  var fields=new LinkedHashMap<>(map(r.get("stage_command")));
  String phone=tx(sql->{ var claim=sql.fetchOne("select phone_number from registration_phone_claims where identity_id=?",r.get("identity_id")); if(claim==null) throw new Rejected(409); return claim.get("phone_number",String.class); });
  fields.put("canonical_phone",phone); return new MigrationBaseline.Value(fields);
 }
 private static boolean same(Object a,Object b) {
  if(a instanceof Number x && b instanceof Number y) return x.longValue()==y.longValue(); return Objects.equals(a,b);
 }
 private <T> T tx(java.util.function.Function<DSLContext,T> action) {
  try { return database.transaction(action); }
  catch(Rejected e) { throw e; }
  catch(org.jooq.exception.DataAccessException e) { throw new Rejected("23505".equals(e.sqlState())?409:"P0001".equals(e.sqlState())?423:503); }
 }
 private static Record row(DSLContext sql,UUID migration) { return sql.fetchOne("select * from migration_baselines where migration_id=?",migration); }
 private static void stageMatch(Record r,String writer,MigrationBaseline.Value c) {
  if(!writer.equals(r.get("writer_id"))||!json(map(r.get("stage_command"))).equals(json(retainedCommand(c)))) {
   // JSONB key order differs; compare maps with numeric canonicalization.
   var original=map(r.get("stage_command")); var requested=retainedCommand(c);
   if(!writer.equals(r.get("writer_id"))||original.size()!=requested.size()||original.entrySet().stream().anyMatch(e->!same(e.getValue(),requested.get(e.getKey())))) throw new Rejected(409);
  }
 }
 @Override public Map<String,Object> stage(String writer,MigrationBaseline.Value c) {
  writer(writer); MigrationBaseline.stage(c.fields());
  Map<String,Object> first=tx(sql->{
   lock(sql); Record old=row(sql,c.id("migration_id"));
   if(old!=null) { stageMatch(old,writer,c); return map(old.get("stage_ack")); }
   return snapshot(sql,c);
  });
  if(first.containsKey("stage_ack_hash")) return immutable(first);
  String shard=(String)first.get("shard_id");
  // Own-service shard IO: no central transaction/lock is held.
  boolean exists;
  try { exists=shards.database(shard).transaction(sql->sql.fetchOne("select id from profiles where identity_id=? and id=?",c.id("identity_id"),c.id("profile_id"))!=null); }
  catch(RuntimeException e) { throw new Rejected(503); }
  if(!exists) throw new Rejected(409);
  return tx(sql->{
   lock(sql); Record old=row(sql,c.id("migration_id")); if(old!=null) { stageMatch(old,writer,c); return immutable(map(old.get("stage_ack"))); }
   Map<String,Object> current=snapshot(sql,c);
   if(!first.equals(current)) throw new Rejected(409);
   requireUnreserved(sql,c.id("identity_id"));
   long now=sql.fetchOne("select floor(extract(epoch from clock_timestamp())*1000)::bigint").get(0,Long.class);
   if(now<c.number("created_at")||now>=c.number("authorization_deadline")) throw new Rejected(409);
   if(sql.fetchOne("select count(*) from migration_baselines where state in ('RESERVED','PUBLISHED')").get(0,Long.class)>=unresolvedCapacity
      ||sql.fetchOne("select count(*) from migration_baselines").get(0,Long.class)>=retainedCapacity) throw new Rejected(429);
   sql.execute("insert into migration_contact_counters(identity_id) values(?) on conflict do nothing",c.id("identity_id"));
   Record counter=sql.fetchOne("update migration_contact_counters set generation=generation+1 where identity_id=? returning *",c.id("identity_id"));
   var a=new LinkedHashMap<String,Object>();
   for(String k:List.of("protocol_version","migration_id","stage_operation_id","identity_id","profile_id")) a.put(k,c.fields().get(k));
   a.put("baseline_generation",counter.get("generation",Long.class)); a.put("contact_mutation_revision",counter.get("revision",Long.class));
   for(String k:List.of("projection_present","projection_revision","phone_contact_version","verification_epoch")) a.put(k,current.get(k));
   for(String k:List.of("email_contact_version","email_digest")) a.put(k,c.fields().get(k));
   var baseline=new LinkedHashMap<>(a); baseline.put("shard_id",shard); baseline.put("canonical_phone",c.text("canonical_phone")); baseline.put("policy_version",c.number("policy_version"));
   a.put("baseline_hash",MigrationBaseline.hash("armoney-user-migration-baseline-v1",List.of("identity_id","profile_id","shard_id","baseline_generation","contact_mutation_revision","projection_present","projection_revision","phone_contact_version","verification_epoch","canonical_phone","email_contact_version","email_digest","policy_version"),baseline));
   a.put("authorization_deadline",c.number("authorization_deadline")); a.put("payload_hash",c.text("payload_hash"));
   a.put("stage_ack_hash",MigrationBaseline.hash("armoney-user-migration-stage-ack-v1",MigrationBaseline.ACK.subList(0,MigrationBaseline.ACK.size()-1),a));
   sql.execute("insert into migration_baselines(migration_id,writer_id,stage_operation_id,command_id,identity_id,state,stage_command,stage_ack) values(?,?,?,?,?,'RESERVED',?::jsonb,?::jsonb)",c.id("migration_id"),writer,c.id("stage_operation_id"),c.id("command_id"),c.id("identity_id"),json(retainedCommand(c)),json(a));
   return immutable(a);
  });
 }
 private static Map<String,Object> snapshot(DSLContext sql,MigrationBaseline.Value c) {
  Record claim=sql.fetchOne("select * from registration_phone_claims where identity_id=? for update",c.id("identity_id"));
  Record directory=sql.fetchOne("select * from profile_directory where identity_id=? for update",c.id("identity_id"));
  Record projection=sql.fetchOne("select * from verification_projection_current where identity_id=? for update",c.id("identity_id"));
  if(claim==null||directory==null||!Boolean.TRUE.equals(directory.get("initialized"))||!c.id("profile_id").equals(directory.get("profile_id"))
     ||!c.text("canonical_phone").equals(claim.get("phone_number"))||!c.text("canonical_phone").equals(directory.get("phone_number"))) throw new Rejected(409);
  if(projection!=null&&!c.text("canonical_phone").equals(projection.get("canonical_phone"))) throw new Rejected(409);
  Record counter=sql.fetchOne("select revision from migration_contact_counters where identity_id=?",c.id("identity_id"));
  var m=new LinkedHashMap<String,Object>(); m.put("shard_id",directory.get("shard_id")); m.put("revision",counter==null?0L:counter.get("revision",Long.class));
  m.put("projection_present",projection!=null); m.put("projection_revision",projection==null?null:projection.get("version")); m.put("phone_contact_version",projection==null?null:projection.get("phone_contact_version")); m.put("verification_epoch",projection==null?null:projection.get("verification_epoch"));
  return m;
 }
 private Record load(String writer,MigrationBaseline.Value c) {
  writer(writer); MigrationBaseline.tuple(c.fields());
  return tx(sql->{ Record r=row(sql,c.id("migration_id")); if(r==null||!writer.equals(r.get("writer_id"))) throw new Rejected(503); binding(r,c); return r; });
 }
 private static void binding(Record r,MigrationBaseline.Value c) {
  var s=map(r.get("stage_command"));
  if(!Objects.equals(s.get("stage_operation_id"),c.text("stage_operation_id"))||!Objects.equals(s.get("payload_hash"),c.text("payload_hash"))) throw new Rejected(409);
  if(r.get("publication_command")!=null&&!map(r.get("publication_command")).equals(c.fields())) throw new Rejected(409);
 }
 private Map<String,Object> decision(MigrationBaseline.Value stage,MigrationBaseline.Value c) {
  Map<String,Object> d;
  try { d=decisions.decision(stage); } catch(RuntimeException e) { throw new Rejected(503); }
  try {
   MigrationBaseline.exact(d,List.of("decision_id","migration_id","stage_operation_id","payload_hash","state")); MigrationBaseline.uuid(d.get("decision_id"));
   for(String k:List.of("decision_id","migration_id","stage_operation_id","payload_hash")) if(!Objects.equals(d.get(k),c.fields().get(k))) throw new Rejected(409);
   if(!Set.of("PUBLICATION_AUTHORIZED","COMPLETED","ABORTED").contains(d.get("state"))) throw new Rejected(503);
   return d;
  } catch(IllegalArgumentException e) { throw new Rejected(503); }
 }
 @Override public Map<String,Object> publish(String writer,MigrationBaseline.Value c) {
  Record original=load(writer,c); if(original.get("publication_ack")!=null) return immutable(map(original.get("publication_ack")));
  var s=stageCommand(original); var a=map(original.get("stage_ack"));
  var d=decision(s,c); if("ABORTED".equals(d.get("state"))) throw new Rejected(409);
  if("COMPLETED".equals(d.get("state"))) throw new Rejected(503); // Completion without our durable ACK is inconsistent.
  MigrationPublicationEvidence proof; try { proof=evidence.evidence(s,c); } catch(RuntimeException e) { throw new Rejected(503); }
  var e=proof.fields();
  for(String k:List.of("migration_id","stage_operation_id","identity_id","baseline_hash","stage_ack_hash","email_contact_version","email_digest","payload_hash"))
   if(!same(e.get(k),a.get(k))) throw new Rejected(409);
  if(!Objects.equals(e.get("decision_id"),c.text("decision_id"))||((Number)e.get("policy_version")).longValue()!=s.number("policy_version")) throw new Rejected(409);
  return tx(sql->{
   lock(sql); snapshot(sql,s); Record r=row(sql,c.id("migration_id")); binding(r,c);
   if(r.get("publication_ack")!=null) return immutable(map(r.get("publication_ack")));
   if(!"RESERVED".equals(r.get("state"))) throw new Rejected(409);
   var current=snapshot(sql,s);
   if(((Number)current.get("revision")).longValue()!=((Number)a.get("contact_mutation_revision")).longValue()
      ||!Objects.equals(current.get("projection_present"),a.get("projection_present"))
      ||!same(current.get("projection_revision"),a.get("projection_revision"))
      ||!same(current.get("phone_contact_version"),a.get("phone_contact_version"))
      ||!same(current.get("verification_epoch"),a.get("verification_epoch"))) throw new Rejected(409);
   var f=new LinkedHashMap<String,Object>();
   for(String k:MigrationBaseline.FINAL) if(a.containsKey(k)) f.put(k,a.get(k));
   for(String k:List.of("decision_id","command_id")) f.put(k,c.fields().get(k));
   f.put("publication_revision",1L);
   for(String k:List.of("email_receipt_id","email_receipt_generation","phone_receipt_id","phone_receipt_generation","receipt_snapshot_hash")) f.put(k,e.get(k));
   f.put("final_ack_hash",MigrationBaseline.hash("armoney-user-migration-final-ack-v1",MigrationBaseline.FINAL.subList(0,MigrationBaseline.FINAL.size()-1),f));
   sql.execute("update migration_baselines set state='PUBLISHED',publication_command=?::jsonb,publication_ack=?::jsonb where migration_id=?",json(c.fields()),json(f),c.id("migration_id"));
   sql.execute("update profile_directory set phone_verified=true where identity_id=?",s.id("identity_id"));
   if("primary".equals(current.get("shard_id"))) sql.execute("update profiles set phone_verified=true where identity_id=?",s.id("identity_id"));
   return immutable(f);
  });
 }
 @Override public Map<String,Object> result(String writer,MigrationBaseline.Value c) {
  Record r=load(writer,c); if(r.get("publication_ack")==null) throw new Rejected(503); return immutable(map(r.get("publication_ack")));
 }
 @Override public Map<String,Object> resolve(String writer,MigrationBaseline.Value c) {
  Record original=load(writer,c); var s=new MigrationBaseline.Value(map(original.get("stage_command")));
  var d=decision(s,c); String state=(String)d.get("state");
  return tx(sql->{
   lock(sql); Record r=row(sql,c.id("migration_id")); binding(r,c);
   String old=r.get("state",String.class);
   if("ABORTED".equals(state)&&("RESERVED".equals(old)||"ABORTED".equals(old))) {
    sql.execute("update migration_baselines set state='ABORTED',publication_command=?::jsonb,terminal_at=coalesce(terminal_at,clock_timestamp()) where migration_id=?",json(c.fields()),c.id("migration_id"));
   } else if("COMPLETED".equals(state)&&r.get("publication_ack")!=null) {
    sql.execute("update migration_baselines set state='RELEASED_AFTER_COMPLETION',terminal_at=coalesce(terminal_at,clock_timestamp()) where migration_id=?",c.id("migration_id"));
   } else throw new Rejected(503);
   return Map.of("migration_id",c.text("migration_id"),"decision_id",c.text("decision_id"),"state","ABORTED".equals(state)?"ABORTED":"RELEASED_AFTER_COMPLETION");
  });
 }
}
