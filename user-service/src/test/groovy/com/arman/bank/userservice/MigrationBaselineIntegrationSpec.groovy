package com.arman.bank.userservice
import com.arman.bank.runtime.Database
import com.arman.bank.userservice.application.*
import com.arman.bank.userservice.domain.*
import com.arman.bank.userservice.infrastructure.*
import org.testcontainers.containers.PostgreSQLContainer
import spock.lang.Specification
import java.sql.DriverManager
import java.util.concurrent.*

/** Synthetic isolated PostgreSQL, including a real second User-owned profile database. */
class MigrationBaselineIntegrationSpec extends Specification {
 PostgreSQLContainer postgres
 Database primary,secondary
 ProfileShards shards
 PostgresProfiles profiles
 PostgresMigrationBaselineStore store
 Map decision=[:]
 Map proof=[:]
 def setup() {
  postgres=new PostgreSQLContainer('postgres:17.6-alpine'); postgres.start()
  primary=new Database(postgres.jdbcUrl,postgres.username,postgres.password)
  DriverManager.getConnection(postgres.jdbcUrl,postgres.username,postgres.password).withCloseable { c ->
   c.createStatement().withCloseable { it.execute('create database synthetic_profile_shard') }
  }
  secondary=new Database(postgres.jdbcUrl.replace('/test','/synthetic_profile_shard'),postgres.username,postgres.password)
  shards=new ProfileShards([primary:primary,secondary:secondary],['ab':'secondary'],'primary')
  profiles=new PostgresProfiles(shards)
  def decisions={ MigrationBaseline.Value ignored -> if(decision.isEmpty()) throw new IllegalStateException(); decision } as MigrationDecisionPort
  def evidence={ MigrationBaseline.Value s,MigrationBaseline.Value c -> new MigrationPublicationEvidence(proof) } as MigrationPublicationEvidencePort
  store=new PostgresMigrationBaselineStore(shards,decisions,evidence)
 }
 def cleanup() { shards?.close(); postgres?.stop() }
 Map fixture(boolean shard=false) {
  UUID owner=UUID.randomUUID()
  profiles.registerPhone(owner,'+971501234567')
  def profile=profiles.save(new Profile(UUID.randomUUID(),owner,'Synthetic owner'),shard?'ab@example.test':null)
  MigrationBaselineSpec.stage(owner,profile.id())
 }
 Map tuple(Map s) { [migration_id:s.migration_id,decision_id:UUID.randomUUID().toString(),stage_operation_id:s.stage_operation_id,command_id:UUID.randomUUID().toString(),payload_hash:s.payload_hash] }
 void authorize(Map s,Map ack,Map c) {
  decision=[decision_id:c.decision_id,migration_id:s.migration_id,stage_operation_id:s.stage_operation_id,payload_hash:s.payload_hash,state:'PUBLICATION_AUTHORIZED']
  proof=[protocol_version:1,migration_id:s.migration_id,decision_id:c.decision_id,stage_operation_id:s.stage_operation_id,
   identity_id:s.identity_id,baseline_hash:ack.baseline_hash,stage_ack_hash:ack.stage_ack_hash,email_contact_version:s.email_contact_version,
   email_digest:s.email_digest,email_receipt_id:UUID.randomUUID().toString(),email_receipt_generation:1,
   phone_receipt_id:UUID.randomUUID().toString(),phone_receipt_generation:1,policy_version:s.policy_version,payload_hash:s.payload_hash,state:'PUBLICATION_AUTHORIZED']
  def order=new ArrayList(MigrationPublicationEvidence.FIELDS); order.remove('receipt_snapshot_hash'); order.remove('state')
  proof.receipt_snapshot_hash=MigrationBaseline.hash('armoney-legacy-migration-receipts-v1',order,proof)
 }
 def 'all-shard versionless stage is immutable generation not authority and preserves independent name changes'() {
  given: def s=fixture(true); def c=MigrationBaseline.stage(s)
  when: def a=store.stage('auth',c)
  then:
  a.baseline_generation>0
  a.projection_present==false
  a.projection_revision==null && a.phone_contact_version==null && a.verification_epoch==null
  count('verification_projection_current')==0
  !primary.transaction { sql -> sql.fetchOne('select stage_command::text from migration_baselines').get(0,String) }.contains(s.canonical_phone)
  when:
  profiles.save(new Profile(UUID.randomUUID(),UUID.fromString(s.identity_id),'New independent name'),'ab@example.test')
  def again=store.stage('auth',c)
  then:
  again==a
  profiles.find(UUID.fromString(s.identity_id)).get().displayName()=='New independent name'
  when: profiles.registerPhone(UUID.fromString(s.identity_id),s.canonical_phone)
  then:
  def e=thrown(MigrationBaselineStore.Rejected); e.status()==423
 }
 def 'publication replay retrieves same ACK without normal projection or shard contact mutation'() {
  given:
  def s=fixture(true); def a=store.stage('auth',MigrationBaseline.stage(s)); def c=tuple(s); authorize(s,a,c)
  when: def ack=store.publish('auth',MigrationBaseline.tuple(c))
  then:
  ack.publication_revision==1
  profiles.find(UUID.fromString(s.identity_id)).get().phoneVerified()
  count('verification_projection_current')==0
  secondary.transaction { sql -> !sql.fetchOne('select phone_verified from profiles where identity_id=?',UUID.fromString(s.identity_id)).get(0,Boolean) }
  when:
  def replay=store.publish('auth',MigrationBaseline.tuple(c))
  def result=store.result('auth',MigrationBaseline.tuple(c))
  then: replay==ack && result==ack
  when:
  decision.state='COMPLETED'
  def resolved=store.resolve('auth',MigrationBaseline.tuple(c))
  then: resolved.state=='RELEASED_AFTER_COMPLETION'
  count('profiles')==0 // profile remains only in its existing shard
 }
 def 'unknown or absent PRIMARY decision never releases and authoritative abort releases only reserved snapshot'() {
  given: def s=fixture(); def a=store.stage('auth',MigrationBaseline.stage(s)); def c=tuple(s)
  when: store.resolve('auth',MigrationBaseline.tuple(c))
  then:
  thrown(MigrationBaselineStore.Rejected)
  primary.transaction { sql -> sql.fetchOne('select state from migration_baselines').get(0,String) }=='RESERVED'
  when:
  decision=[decision_id:c.decision_id,migration_id:s.migration_id,stage_operation_id:s.stage_operation_id,payload_hash:s.payload_hash,state:'ABORTED']
  def result=store.resolve('auth',MigrationBaseline.tuple(c))
  then:
  result.state=='ABORTED'
  !profiles.find(UUID.fromString(s.identity_id)).get().phoneVerified()
 }
 def 'direct contact mutation and same-value projection commands are fenced but independent name update succeeds'() {
  given: def s=fixture(); store.stage('auth',MigrationBaseline.stage(s))
  when: primary.transaction { sql -> sql.execute('update registration_phone_claims set phone_number=phone_number where identity_id=?',UUID.fromString(s.identity_id)) }
  then: thrown(org.jooq.exception.DataAccessException)
  when: profiles.verifyPendingPhone(UUID.fromString(s.identity_id),s.canonical_phone,'synthetic','synthetic-evidence')
  then: thrown(MigrationBaselineStore.Rejected)
  when: profiles.save(new Profile(UUID.randomUUID(),UUID.fromString(s.identity_id),'Updated name'))
  then: profiles.find(UUID.fromString(s.identity_id)).get().displayName()=='Updated name'
 }
 def 'parallel exact stage requests return one durable generation and changed payload conflicts'() {
  given: def s=fixture(); def executor=Executors.newFixedThreadPool(2)
  when:
  def tasks=(0..<2).collect { executor.submit({ -> store.stage('auth',MigrationBaseline.stage(s)) } as Callable) }
  def results=tasks.collect { it.get(15,TimeUnit.SECONDS) }
  then: results[0]==results[1] && count('migration_baselines')==1
  when:
  def changed=s+[email_contact_version:8]
  changed.payload_hash=MigrationBaseline.hash('armoney-user-migration-stage-v1',MigrationBaseline.STAGE[0..-2],changed)
  store.stage('auth',MigrationBaseline.stage(changed))
  then: thrown(MigrationBaselineStore.Rejected)
  cleanup: executor.shutdownNow()
 }
 def 'expired authorization never releases reservation and delayed authoritative publication remains recoverable'() {
  given:
  def s=fixture()
  s.authorization_deadline=System.currentTimeMillis()+1500L
  s.payload_hash=MigrationBaseline.hash('armoney-user-migration-stage-v1',MigrationBaseline.STAGE[0..-2],s)
  def a=store.stage('auth',MigrationBaseline.stage(s)); def c=tuple(s); authorize(s,a,c)
  when:
  Thread.sleep(1600)
  store.resolve('auth',MigrationBaseline.tuple(c))
  then:
  thrown(MigrationBaselineStore.Rejected)
  when: profiles.registerPhone(UUID.fromString(s.identity_id),s.canonical_phone)
  then: thrown(MigrationBaselineStore.Rejected)
  when: def ack=store.publish('auth',MigrationBaseline.tuple(c))
  then:
  ack.stage_ack_hash==a.stage_ack_hash
  ack.publication_revision==1
 }
 def 'forged receipt hash wrong decision and cross-writer cannot publish or allocate an owner'() {
  given: def s=fixture(); def a=store.stage('auth',MigrationBaseline.stage(s)); def c=tuple(s); authorize(s,a,c)
  when: store.publish('foreign',MigrationBaseline.tuple(c))
  then: thrown(MigrationBaselineStore.Rejected)
  when:
  proof.receipt_snapshot_hash='0'*64
  store.publish('auth',MigrationBaseline.tuple(c))
  then:
  thrown(MigrationBaselineStore.Rejected)
  !profiles.find(UUID.fromString(s.identity_id)).get().phoneVerified()
  count('verification_projection_current')==0
  when:
  authorize(s,a,c); decision.decision_id=UUID.randomUUID().toString()
  store.publish('auth',MigrationBaseline.tuple(c))
  then:
  thrown(MigrationBaselineStore.Rejected)
  count('migration_baselines')==1
 }
 long count(String table) {
  assert table in ['migration_baselines','profiles','verification_projection_current']
  primary.transaction { sql -> sql.fetchOne('select count(*) from '+table).get(0,Long) }
 }
}
