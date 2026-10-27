package com.arman.bank.authservice.infrastructure;

import com.arman.bank.authservice.application.AuthService;
import com.arman.bank.authservice.domain.LegacyMigrationContact;
import com.arman.bank.authservice.domain.LegacyMigrationOtp;
import java.time.*;
import java.util.*;
import org.jooq.DSLContext;
import org.jooq.Record;

/** Caller-owned owner/intent locks only. No transaction, network, owner lookup by email or positive shortcut. */
final class LegacyMigrationContactStore {
 private LegacyMigrationContactStore(){}
 static void baseline(DSLContext sql,UUID migration,UUID owner){
  var contact=sql.fetchOne("select * from legacy_migration_owner_contacts where identity_id=?",owner);
  sql.execute("insert into legacy_migration_contact_baselines values(?,?,?,?,?)",migration,owner,contact==null?"ABSENT_OWNER_CONTACT":"MIGRATION_OWNER_CONTACT",contact==null?0L:contact.get("generation",Long.class),contact==null?null:contact.get("email_digest",String.class));
 }
 static boolean candidateRequired(DSLContext sql,UUID migration){return sql.fetchOne("select migration_id from legacy_migration_contact_baselines where migration_id=?",migration)!=null&&sql.fetchOne("select migration_id from legacy_migration_email_candidates where migration_id=?",migration)==null;}
 static Record candidate(DSLContext sql,UUID migration){return sql.fetchOne("select * from legacy_migration_email_candidates where migration_id=?",migration);}
 static Record select(DSLContext sql,Record intent,UUID command,String input,LegacyMigrationManagedProofCrypto crypto,UUID nextCommand,byte[] nextCsrf){
  String email=AuthService.normalizeEmail(input);UUID migration=intent.get("id",UUID.class),owner=intent.get("identity_id",UUID.class);
  var baseline=sql.fetchOne("select * from legacy_migration_contact_baselines where migration_id=?",migration);if(baseline==null)throw rejected();
  current(sql,intent,false);Record prior=candidate(sql,migration);
  if(prior!=null){if(!command.equals(prior.get("command_id",UUID.class))||!email.equals(prior.get("canonical_email",String.class)))throw rejected();return prior;}
  if(sql.fetchOne("select count(*) from legacy_migration_email_candidates").get(0,Long.class)>=10000)throw new LegacyMigrationOtp.Failure(LegacyMigrationOtp.Error.RATE_LIMITED);
  var old=sql.fetchOne("select canonical_email from legacy_migration_owner_contacts where identity_id=?",owner);if(old!=null&&!email.equals(old.get(0,String.class)))throw rejected();
  UUID id=UUID.randomUUID();String key=crypto.keyId();Instant deadline=time(intent,"authorization_deadline"),end=time(intent,"created_at").plusSeconds(900);
  String digest=hex(crypto.hash(key,"armoney-legacy-migration-email-candidate-v1","1",migration.toString(),owner.toString(),intent.get("proof_mode",String.class),intent.get("account_receipt_id",UUID.class).toString(),intent.get("owner_generation").toString(),intent.get("association_generation").toString(),baseline.get("source",String.class),baseline.get("baseline_generation").toString(),none(baseline.get("baseline_digest",String.class)),id.toString(),"1",email,stamp(deadline),stamp(end)));
  sql.execute("insert into legacy_migration_email_candidates values(?,?,?,?,?,?,1,?,?,cast(? as timestamptz),cast(? as timestamptz),clock_timestamp(),?,?)",migration,id,owner,intent.get("account_receipt_id",UUID.class),command,email,key,digest,stamp(deadline),stamp(end),nextCommand,nextCsrf);
  current(sql,intent,true);return candidate(sql,migration);
 }
 static void verify(DSLContext sql,Record intent,LegacyMigrationManagedProofCrypto crypto){
  Record c=candidate(sql,intent.get("id",UUID.class));if(c==null)return;
  Record baseline=sql.fetchOne("select * from legacy_migration_contact_baselines where migration_id=?",intent.get("id",UUID.class));if(baseline==null)throw rejected();
  String expected=hex(crypto.hash(c.get("retention_key_id",String.class),"armoney-legacy-migration-email-candidate-v1","1",intent.get("id").toString(),intent.get("identity_id").toString(),intent.get("proof_mode",String.class),intent.get("account_receipt_id").toString(),intent.get("owner_generation").toString(),intent.get("association_generation").toString(),baseline.get("source",String.class),baseline.get("baseline_generation").toString(),none(baseline.get("baseline_digest",String.class)),c.get("candidate_id").toString(),"1",c.get("canonical_email",String.class),stamp(time(intent,"authorization_deadline")),stamp(time(intent,"created_at").plusSeconds(900))));
  if(!expected.equals(c.get("candidate_digest"))||!intent.get("identity_id").equals(c.get("owner_id"))||!intent.get("account_receipt_id").equals(c.get("account_receipt_id")))throw rejected();
  Record t=sql.fetchOne("select * from legacy_migration_contact_transitions where migration_id=?",intent.get("id",UUID.class));
  if(t!=null){Record r=sql.fetchOne("select * from legacy_migration_otp_receipts where id=? and migration_id=? and purpose='EMAIL'",t.get("receipt_id",UUID.class),intent.get("id",UUID.class));if(r==null)throw rejected();
   String hash=transitionHash(intent,baseline,c,r.get("challenge_id",UUID.class),r.get("id",UUID.class),t.get("consume_command_id",UUID.class),t.get("resulting_generation",Long.class),t.get("retention_key_id",String.class),crypto);
   if(!hash.equals(t.get("transition_hash")))throw rejected();
  }
 }
 private static String transitionHash(Record intent,Record baseline,Record candidate,UUID challenge,UUID receipt,UUID command,long generation,String key,LegacyMigrationManagedProofCrypto crypto){return hex(crypto.hash(key,"armoney-legacy-migration-owner-contact-transition-v1","1",intent.get("id").toString(),intent.get("identity_id").toString(),baseline.get("source",String.class),baseline.get("baseline_generation").toString(),none(baseline.get("baseline_digest",String.class)),candidate.get("candidate_id").toString(),"1",candidate.get("candidate_digest",String.class),challenge.toString(),receipt.toString(),command.toString(),Long.toString(generation),stamp(time(intent,"authorization_deadline")),stamp(time(intent,"created_at").plusSeconds(900))));}
 static String email(DSLContext sql,Record intent){
  var baseline=sql.fetchOne("select * from legacy_migration_contact_baselines where migration_id=?",intent.get("id",UUID.class));
  if(baseline==null){var r=sql.fetchOne("select email from identities where id=?",intent.get("identity_id",UUID.class));if(r==null)throw rejected();return AuthService.normalizeEmail(r.get(0,String.class));}
  current(sql,intent,true);return candidate(sql,intent.get("id",UUID.class)).get("canonical_email",String.class);
 }
 static void current(DSLContext sql,Record intent,boolean requireCandidate){
  UUID migration=intent.get("id",UUID.class),owner=intent.get("identity_id",UUID.class);var baseline=sql.fetchOne("select * from legacy_migration_contact_baselines where migration_id=?",migration);if(baseline==null)return;
  Record candidate=candidate(sql,migration);if(requireCandidate&&candidate==null)throw rejected();
  Record contact=sql.fetchOne("select * from legacy_migration_owner_contacts where identity_id=?",owner),transition=sql.fetchOne("select * from legacy_migration_contact_transitions where migration_id=?",migration);
  if(transition!=null){
   if(candidate==null||contact==null||!owner.equals(transition.get("owner_id",UUID.class))||!migration.equals(contact.get("source_migration_id",UUID.class))||!transition.get("receipt_id").equals(contact.get("source_receipt_id"))||!transition.get("resulting_generation").equals(contact.get("generation"))||!candidate.get("canonical_email").equals(contact.get("canonical_email"))||!candidate.get("candidate_digest").equals(contact.get("email_digest")))throw rejected();
   var receipt=sql.fetchOne("select id from legacy_migration_otp_receipts where id=? and migration_id=? and purpose='EMAIL'",transition.get("receipt_id",UUID.class),migration);if(receipt==null)throw rejected();
  }else if("ABSENT_OWNER_CONTACT".equals(baseline.get("source"))){if(contact!=null)throw rejected();}
  else if(contact==null||!baseline.get("baseline_generation").equals(contact.get("generation"))||!baseline.get("baseline_digest").equals(contact.get("email_digest")))throw rejected();
  if(!now(sql).isBefore(time(intent,"authorization_deadline")))throw rejected();
 }
 static void consumed(DSLContext sql,Record intent,UUID receipt,UUID challenge,UUID command,LegacyMigrationManagedProofCrypto crypto){
  UUID migration=intent.get("id",UUID.class),owner=intent.get("identity_id",UUID.class);Record candidate=candidate(sql,migration);if(candidate==null)return;
  current(sql,intent,true);Record baseline=sql.fetchOne("select * from legacy_migration_contact_baselines where migration_id=?",migration);
  if(sql.fetchOne("select migration_id from legacy_migration_contact_transitions where migration_id=?",migration)!=null)throw rejected();
  var old=sql.fetchOne("select * from legacy_migration_owner_contacts where identity_id=?",owner);long generation=old==null?1:old.get("generation",Long.class);
  String key=candidate.get("retention_key_id",String.class);
  String hash=transitionHash(intent,baseline,candidate,challenge,receipt,command,generation,key,crypto);
  // Reuse of an existing verified contact needs no mutation; original baseline remains authoritative.
  if(old!=null)return;
  sql.execute("insert into legacy_migration_owner_contacts values(?,?,?,?,?,?,clock_timestamp())",owner,candidate.get("canonical_email"),candidate.get("candidate_digest"),generation,migration,receipt);
  sql.execute("insert into legacy_migration_contact_transitions values(?,?,?,?,?,?,?,clock_timestamp())",migration,owner,receipt,command,generation,key,hash);
  current(sql,intent,true);
 }
 static String stamp(Instant instant){return LegacyMigrationContact.instant(instant);}
 static String none(String value){return value==null?"NONE":value;}
 static String hex(byte[] bytes){return HexFormat.of().formatHex(bytes);}
 static Instant time(Record r,String key){return r.get(key,OffsetDateTime.class).toInstant();}
 static Instant now(DSLContext sql){return sql.fetchOne("select clock_timestamp()").get(0,OffsetDateTime.class).toInstant();}
 static LegacyMigrationOtp.Failure rejected(){return new LegacyMigrationOtp.Failure(LegacyMigrationOtp.Error.CONFLICT);}
}
