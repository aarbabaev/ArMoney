package com.arman.bank.userservice
import com.arman.bank.runtime.Database
import com.arman.bank.userservice.application.*
import com.arman.bank.userservice.domain.*
import com.arman.bank.userservice.infrastructure.*
import org.testcontainers.containers.PostgreSQLContainer
import spock.lang.Specification

/** User-owned persistence protocol only. Synthetic Auth decision port is NOT
 * genuine Auth FINAL/NOT_MIG acceptance or a bank eligibility assertion. */
class LiveUserCurrentIntegrationSpec extends Specification {
    LiveUserFixture f
    def setup(){f=new LiveUserFixture()}
    def cleanup(){f?.close()}
    def 'actual User publication and release preserve original tuple and current recomputes'(){
        given:def s=f.released()
        def request=LiveUserRevisionSpec.currentRequest(s.owner,s.baseline)
        def store=new PostgresLiveUserCurrentStore(f.database)
        when:def first=store.current(LiveUserRevisionSpec.WRITER,request)
        then:first.current.profile_id==s.stage.profile_id
        first.current.baseline_generation==s.stage.baseline_generation
        first.current.contact_mutation_revision==s.stage.contact_mutation_revision
        first.current.projection_present==false
        first.current.projection_revision==null && first.current.phone_contact_version==null && first.current.verification_epoch==null
        f.profiles.find(s.owner).orElseThrow().phoneVerified()
        when:f.profiles.save(new Profile(UUID.randomUUID(),s.owner,'Independent name'))
        def again=store.current(LiveUserRevisionSpec.WRITER,request)
        then:again.current==first.current
        when:store.current(LiveUserRevisionSpec.WRITER,LiveUserRevisionSpec.currentRequest(UUID.randomUUID(),s.baseline))
        then:thrown(LiveUserCurrentStore.Rejected)
    }
    def 'default constructor cannot mutate released MIG owner and current binds exact decision'(){
        given:def s=f.released()
        when:new PostgresVerificationProjectionStore(f.database).commit('legacy',LiveUserRevisionSpec.command(s.owner))
        then:thrown(VerificationProjectionStore.Rejected)
        f.count('verification_projection_current')==0
        when:new PostgresLiveUserCurrentStore(f.database).current(LiveUserRevisionSpec.WRITER,LiveUserRevisionSpec.currentRequest(s.owner,s.baseline+[decision_id:UUID.randomUUID().toString()]))
        then:thrown(LiveUserCurrentStore.Rejected)
    }
    def 'PUBLISHED fence cannot be treated as released or bypassed by current request'(){
        given:def s=f.released(false)
        when:new PostgresLiveUserCurrentStore(f.database).current(LiveUserRevisionSpec.WRITER,LiveUserRevisionSpec.currentRequest(s.owner,s.baseline))
        then:thrown(LiveUserCurrentStore.Rejected)
        f.count('verification_projection_current')==0
    }
}

/** Concrete User lifecycle, shared only by this slice's isolated User tests. */
final class LiveUserFixture implements AutoCloseable {
    PostgreSQLContainer postgres
    Database database
    PostgresProfiles profiles
    ProfileShards shards
    Map decision=[:],proof=[:]
    PostgresMigrationBaselineStore migrations
    LiveUserFixture(){
        postgres=new PostgreSQLContainer('postgres:17.6-alpine');postgres.start()
        try{
            database=new Database(postgres.jdbcUrl,postgres.username,postgres.password)
            shards=new ProfileShards([primary:database],[:],'primary');profiles=new PostgresProfiles(shards)
            migrations=new PostgresMigrationBaselineStore(shards,{ignored->decision} as MigrationDecisionPort,{s,c->new MigrationPublicationEvidence(proof)} as MigrationPublicationEvidencePort)
        }catch(Throwable failure){close();throw failure}
    }
    Map released(boolean release=true){
        UUID owner=UUID.randomUUID();profiles.registerPhone(owner,'+971501234567')
        def p=profiles.save(new Profile(UUID.randomUUID(),owner,'Synthetic owner'))
        def command=MigrationBaselineSpec.stage(owner,p.id())
        def stage=migrations.stage('auth',MigrationBaseline.stage(command))
        def publication=[migration_id:command.migration_id,decision_id:UUID.randomUUID().toString(),stage_operation_id:command.stage_operation_id,command_id:UUID.randomUUID().toString(),payload_hash:command.payload_hash]
        decision=[decision_id:publication.decision_id,migration_id:command.migration_id,stage_operation_id:command.stage_operation_id,payload_hash:command.payload_hash,state:'PUBLICATION_AUTHORIZED']
        proof=[protocol_version:1L,migration_id:command.migration_id,decision_id:publication.decision_id,stage_operation_id:command.stage_operation_id,identity_id:owner.toString(),baseline_hash:stage.baseline_hash,stage_ack_hash:stage.stage_ack_hash,email_contact_version:command.email_contact_version,email_digest:command.email_digest,email_receipt_id:UUID.randomUUID().toString(),email_receipt_generation:1L,phone_receipt_id:UUID.randomUUID().toString(),phone_receipt_generation:1L,policy_version:command.policy_version,payload_hash:command.payload_hash,state:'PUBLICATION_AUTHORIZED']
        def order=new ArrayList(MigrationPublicationEvidence.FIELDS);order.remove('receipt_snapshot_hash');order.remove('state')
        proof.receipt_snapshot_hash=MigrationBaseline.hash('armoney-legacy-migration-receipts-v1',order,proof)
        def ack=migrations.publish('auth',MigrationBaseline.tuple(publication))
        if(release){decision.state='COMPLETED';migrations.resolve('auth',MigrationBaseline.tuple(publication))}
        [owner:owner,stage:stage,baseline:[migration_id:publication.migration_id,decision_id:publication.decision_id,publication_revision:ack.publication_revision,final_ack_hash:ack.final_ack_hash]]
    }
    long count(String table){assert table in ['verification_projection_current','live_user_projection_commands','verification_projection_operations','registration_phone_claims'];database.transaction{sql->sql.fetchOne('select count(*) from '+table).get(0,Long)}}
    @Override void close(){try{database?.close()}finally{postgres?.stop()}}
}
