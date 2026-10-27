package com.arman.bank.userservice
import com.arman.bank.runtime.Database
import com.arman.bank.userservice.application.*
import com.arman.bank.userservice.domain.*
import com.arman.bank.userservice.infrastructure.*
import org.flywaydb.core.Flyway
import org.testcontainers.containers.PostgreSQLContainer
import spock.lang.Specification
import java.sql.DriverManager

class MigrationBaselineUpgradeIntegrationSpec extends Specification {
 def 'populated V6 upgrade preserves versionless UUID profile name contact and pending verification'() {
  given:
  def pg=new PostgreSQLContainer('postgres:17.6-alpine'); pg.start()
  Database db
  UUID owner=UUID.randomUUID(),profile=UUID.randomUUID()
  Flyway.configure().dataSource(pg.jdbcUrl,pg.username,pg.password).locations('classpath:db/migration').target('6').load().migrate()
  DriverManager.getConnection(pg.jdbcUrl,pg.username,pg.password).withCloseable { connection ->
   // Synthetic pre-upgrade profile only; no authority, receipts, balances or completion rows.
   connection.prepareStatement("insert into profiles(id,identity_id,display_name,phone_number) values(?,?,'Synthetic legacy',?)").withCloseable {
    it.setObject(1,profile); it.setObject(2,owner); it.setString(3,'+971501234567'); it.executeUpdate()
   }
   connection.prepareStatement("insert into profile_directory(identity_id,profile_id,shard_id,initialized,phone_number) values(?,?,'primary',true,?)").withCloseable {
    it.setObject(1,owner); it.setObject(2,profile); it.setString(3,'+971501234567'); it.executeUpdate()
   }
   connection.prepareStatement('insert into registration_phone_claims(identity_id,phone_number) values(?,?)').withCloseable {
    it.setObject(1,owner); it.setString(2,'+971501234567'); it.executeUpdate()
   }
  }
  when:
  db=new Database(pg.jdbcUrl,pg.username,pg.password)
  def shards=new ProfileShards([primary:db],[:],'primary')
  def decisions={ MigrationBaseline.Value ignored -> throw new IllegalStateException() } as MigrationDecisionPort
  def evidence={ MigrationBaseline.Value s,MigrationBaseline.Value c -> throw new IllegalStateException() } as MigrationPublicationEvidencePort
  def store=new PostgresMigrationBaselineStore(shards,decisions,evidence)
  def ack=store.stage('auth',MigrationBaseline.stage(MigrationBaselineSpec.stage(owner,profile)))
  def current=new PostgresProfiles(shards).find(owner).get()
  then:
  ack.baseline_generation==1 && ack.contact_mutation_revision==0
  !ack.projection_present && ack.phone_contact_version==null
  current.id()==profile && current.identityId()==owner
  current.displayName()=='Synthetic legacy'
  current.phoneNumber()=='+971501234567' && !current.phoneVerified()
  db.transaction { sql -> sql.fetchOne('select count(*) from verification_projection_current').get(0,Long) }==0
  cleanup: db?.close(); pg?.stop()
 }
}
