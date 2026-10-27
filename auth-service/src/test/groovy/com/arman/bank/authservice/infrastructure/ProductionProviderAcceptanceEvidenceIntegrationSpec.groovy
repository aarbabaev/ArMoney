package com.arman.bank.authservice.infrastructure

import com.arman.bank.authservice.application.*
import com.arman.bank.authservice.domain.IdentityVerification.*
import com.arman.bank.authservice.domain.Verification
import com.arman.bank.authservice.domain.Verification.Purpose
import com.arman.bank.authservice.domain.VerificationProjection as P
import com.arman.bank.authservice.domain.LoginProof as L
import com.arman.bank.authservice.domain.CodeConfirmation
import com.arman.bank.runtime.Database
import org.testcontainers.containers.PostgreSQLContainer
import spock.lang.Specification
import java.time.*

/** Real PG/Flyway and actual Auth use cases with synthetic observations/delivery ACK.
 * This spec tests read predicates, not SMTP/SMS delivery or genuine provider acceptance. */
class ProductionProviderAcceptanceEvidenceIntegrationSpec extends Specification {
    static final String ISSUER='https://acceptance.example.test/sso/realms/armoney'
    static final String PHONE='+971501234567', EMAIL='fixture@example.test', WRITER='fixture-auth'
    PostgreSQLContainer postgres
    Database database
    VerificationCrypto crypto
    PostgresIdentityVerificationStore authority
    PostgresVerificationProjectionStore projections
    ProductionProviderAcceptanceEvidence evidence
    Flow flow=new Flow(ISSUER,'synthetic-subject','armoney-android','root','tab',Base64.urlEncoder.withoutPadding().encodeToString(([3]*32) as byte[]))
    def setup(){
        postgres=new PostgreSQLContainer('postgres:17.6-alpine');postgres.start()
        database=new Database(postgres.jdbcUrl,postgres.username,postgres.password)
        crypto=new VerificationCrypto('test',[test:([11]*32) as byte[]],[test:([22]*32) as byte[]])
        def policy=Policy.defaults([ISSUER] as Set,['armoney-android'] as Set,[WRITER] as Set)
        def lookup=new PostgresIdentityVerificationStore(database,crypto,([41]*32) as byte[],policy,null)
        projections=new PostgresVerificationProjectionStore(database,P.Policy.defaults([WRITER] as Set),([42]*32) as byte[],
            { sql -> { UUID identity,Instant now -> lookup.projectionAuthorityInTransaction(sql,identity,now) } as VerificationProjectionStore.AuthorityPort })
        authority=new PostgresIdentityVerificationStore(database,crypto,([41]*32) as byte[],policy,projections,WRITER,true)
        evidence=new ProductionProviderAcceptanceEvidence(database,ISSUER,PHONE,EMAIL)
    }
    def cleanup(){database?.close();postgres?.stop()}
    static def deadline(){new ProductionProviderAcceptanceController.Deadline()}
    def observed(Closure work,boolean enabled=true){
        def started=authority.admit(WRITER,flow)
        def observation=new Observation(flow,PHONE,EMAIL,PHONE,enabled,true,started.plusSeconds(600),started)
        work.call(observation,started)
    }
    def enroll(){observed{o,t->authority.enroll(WRITER,new Enrollment(UUID.randomUUID(),flow,PHONE,EMAIL),o,t)}}
    def issue(UUID id,Purpose purpose){observed{o,t->authority.issue(WRITER,new Issue(UUID.randomUUID(),id,flow,purpose,1),o,t)}}
    def consume(UUID id,Purpose purpose){
        def issued=issue(id,purpose)
        def store=new PostgresVerificationStore(database,crypto)
        def lease=store.claimDelivery().orElseThrow()
        // Explicit synthetic delivery fixture only; controller runtime never uses this shortcut.
        def payload=crypto.decrypt(lease.encrypted(),id,lease.challengeId(),purpose,1)
        assert store.finishDelivery(lease.deliveryId(),lease.fence(),true)
        observed{o,t->authority.consume(WRITER,new Consume(UUID.randomUUID(),id,issued.challengeId(),flow,purpose,1,payload.code()),o,t)}
    }
    def projected(){
        def id=enroll().enrollmentId()
        consume(id,Purpose.REGISTRATION_EMAIL);consume(id,Purpose.REGISTRATION_PHONE)
        def claim=projections.claim(WRITER).orElseThrow();def c=claim.command()
        assert authority.attach(WRITER,claim,new P.Result(P.Outcome.ACK,new P.Ack(c.operationId(),c.identityId(),c.version(),c.phoneContactVersion(),c.verificationEpoch(),c.payloadHash())))
        id
    }
    private List<String> confirmedEvents(int count,boolean wholeSeconds=true){
        def policy=L.Policy.defaults([ISSUER] as Set,['armoney-android'] as Set,['fixture-code-v1'] as Set,[WRITER] as Set)
        def staging=new PostgresCodeIntentStore(database,policy,new CodeIntentRetentionKeys('retention-v1',['retention-v1':Base64.encoder.encodeToString(([61]*32) as byte[])]),
            new CodeIntentDetailCrypto('aead-v1',['aead-v1':Base64.encoder.encodeToString(([62]*32) as byte[])],[] as Set))
        def proofs=new PostgresLoginProofStore(database,policy,([51]*32) as byte[],
            {sql -> {binding,now -> throw new L.Failure(L.Error.UNAVAILABLE)} as LoginProofStore.EligibilityPort })
        def core=new PostgresCodeConfirmationStore(database,staging,proofs,policy)
        def service=new CodeConfirmationService(core,{binding,started ->
            // The real provider DTO uses integer epoch seconds; C2 rejects fractional facts.
            Instant observed=Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS)
            if(!wholeSeconds)observed=observed.plusNanos(1)
            new CodeConfirmation.Facts(binding,PHONE,EMAIL,PHONE,true,true,true,observed,observed.plusSeconds(5))
        } as CodeConfirmationObservations)
        staging.cleanup()
        (1..count).collect{
            awaitPairAdmission()
            Instant now=Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS)
            UUID event=UUID.randomUUID()
            def input=new L.Write(UUID.randomUUID(),UUID.randomUUID().toString().replace('-','')*2,'fixture-code-v1',ISSUER,flow.subject(),'armoney-android','synthetic-user-session',
                flow.authenticationSessionId(),now,now.plusSeconds(120),L.Kind.INTERACTIVE,event,now)
            staging.stage(WRITER,input)
            def result=service.confirm(WRITER,new L.Resolve(input.codeDigest(),input.codeKeyId(),input.issuer(),input.subject(),input.clientId(),input.userSessionId()))
            assert !result.deniedCommitted()
            event.toString()
        }
    }
    private void awaitPairAdmission(){
        // Each genuine pair spends one shared admission for stage and one for confirm.
        // Observe natural 2/second refill BEFORE either request; never reset or bypass the budget.
        long limit=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(20)
        // Groovy otherwise spreads the primitive array into scalar varargs instead of one bytea bind.
        Object[] bindings=([L.writerHash(WRITER)] as Object[])
        assert bindings.length==1 && bindings[0] instanceof byte[]
        while(!database.transaction{sql->sql.fetchOne('''
            select coalesce((select least(20,credits+greatest(0,extract(epoch from clock_timestamp()-refilled_at))*2)>=2
            from sso_login_proof_admission where writer_hash=?),true)
            ''',bindings).get(0,Boolean)}){
            assert System.nanoTime()<limit:'synthetic confirmation pair did not receive natural admission refill'
            Thread.sleep(25)
        }
    }
    def 'fresh database remains unchanged and zero outcomes do not manufacture evidence'(){
        when:evidence.requireFresh(deadline());def first=evidence.outcomes(deadline());evidence.requireFresh(deadline())
        then:first==[registration_active:false,projection_acknowledged:false,bank_session_count:0,confirmed_event_ids:[]]
        when:evidence.currentDelivery('REGISTRATION_EMAIL',deadline())
        then:thrown(ProductionProviderAcceptanceController.Rejected)
    }
    def 'existing business rows prohibit companion restart without rewriting or resuming them'(){
        given:def enrolled=enroll()
        when:evidence.requireFresh(deadline())
        then:thrown(ProductionProviderAcceptanceController.Rejected)
        database.transaction{it.fetchOne('select count(*) from verification_enrollments where id=?',enrolled.enrollmentId()).get(0,Long)}==1L
    }
    def 'current delivery metadata requires current OPEN and actual accepted worker state'(){
        given:def id=enroll().enrollmentId();def challenge=issue(id,Purpose.REGISTRATION_EMAIL)
        when:evidence.currentDelivery('REGISTRATION_EMAIL',deadline())
        then:thrown(ProductionProviderAcceptanceController.Rejected)
        when:
        def store=new PostgresVerificationStore(database,crypto);def lease=store.claimDelivery().orElseThrow()
        assert store.finishDelivery(lease.deliveryId(),lease.fence(),true)
        def result=evidence.currentDelivery('REGISTRATION_EMAIL',deadline())
        then:result.deliveryId()==lease.deliveryId();result.challengeId()==challenge.challengeId();result.destination()==EMAIL;result.version()==1
        when:
        database.transaction{sql->sql.execute("update verification_challenges set state='EXPIRED' where id=?",challenge.challengeId())}
        evidence.currentDelivery('REGISTRATION_EMAIL',deadline())
        then:thrown(ProductionProviderAcceptanceController.Rejected)
    }
    def 'PROJECTED acknowledgement alone is not registration eligibility and reads cannot mint it'(){
        given:def id=projected()
        when:def before=evidence.outcomes(deadline())
        then:before.projection_acknowledged;!before.registration_active;before.bank_session_count==0;before.confirmed_event_ids==[]
        database.transaction{it.fetchOne('select count(*) from banking_eligibility_grants').get(0,Long)}==0L
        when:observed{o,t->authority.status(WRITER,id,flow,o,t)};def after=evidence.outcomes(deadline())
        then:after.projection_acknowledged;after.registration_active;after.bank_session_count==0;after.confirmed_event_ids==[]
        database.transaction{it.fetchOne('select count(*) from banking_eligibility_grants').get(0,Long)}==1L
    }
    def 'all persisted session rows count even after their expiry'(){
        given:def id=projected();observed{o,t->authority.status(WRITER,id,flow,o,t)}
        database.transaction{sql->
            def owner=sql.fetchOne('select mapped_identity_id from verification_authorities where enrollment_id=?',id).get(0,UUID)
            // Negative stray-row fixture: no usable session or positive admission is asserted.
            sql.execute("insert into sessions(token_hash,identity_id,created_at,expires_at) values(?,?,clock_timestamp()-interval '2 hours',clock_timestamp()-interval '1 hour')",'a'*64,owner)
        }
        expect:evidence.outcomes(deadline()).bank_session_count==1
    }
    def 'observed provider security mutation invalidates current registration evidence'(){
        given:def id=projected();observed{o,t->authority.status(WRITER,id,flow,o,t)};assert evidence.outcomes(deadline()).registration_active
        when:observed({o,t->authority.status(WRITER,id,flow,o,t)},false)
        then:thrown(Verification.Failure)
        when:def result=evidence.outcomes(deadline())
        then:!result.registration_active;!result.projection_acknowledged;result.bank_session_count==0
    }
    def 'timestamp-bound projection read rejects expired enrollment or authority without changing schema guards'(){
        given:
        def id=projected();observed{o,t->authority.status(WRITER,id,flow,o,t)}
        assert evidence.outcomes(deadline()).registration_active
        when:
        database.transaction{sql->
            if(expired=='enrollment')sql.execute("update verification_enrollments set expires_at=created_at+interval '1 microsecond' where id=?",id)
            // Preserve the authority's exact 86400-second lifetime constraint.
            else sql.execute("update verification_authorities set created_at=created_at-interval '2 days',expires_at=expires_at-interval '2 days' where enrollment_id=?",id)
        }
        def result=evidence.outcomes(deadline())
        then:
        !result.projection_acknowledged
        !result.registration_active
        result.bank_session_count==0
        result.confirmed_event_ids==[]
        where:expired << ['enrollment','authority']
    }
    def 'actual Auth C2 confirmations produce a complete sorted inventory and evidence reads do not change it'(){
        given:
        def id=projected();observed{o,t->authority.status(WRITER,id,flow,o,t)}
        def expected=confirmedEvents(2).sort(false)
        when:def first=evidence.outcomes(deadline());def second=evidence.outcomes(deadline())
        then:
        first.confirmed_event_ids==expected
        second==first
        first.registration_active;first.projection_acknowledged;first.bank_session_count==0
        database.transaction{it.fetchOne('select count(*) from sso_code_confirmation_results').get(0,Long)}==2L
        database.transaction{it.fetchOne('select count(*) from sso_login_events').get(0,Long)}==2L
    }
    def 'seventeenth actual Auth confirmation fails closed rather than truncating the reported inventory'(){
        given:
        def id=projected();observed{o,t->authority.status(WRITER,id,flow,o,t)}
        assert confirmedEvents(17).size()==17
        when:evidence.outcomes(deadline())
        then:thrown(ProductionProviderAcceptanceController.Rejected)
        database.transaction{it.fetchOne('select count(*) from sso_code_confirmation_results').get(0,Long)}==17L
    }
    def 'fractional synthetic observation is rejected before confirmation persistence while whole-second facts succeed'(){
        given:def id=projected();observed{o,t->authority.status(WRITER,id,flow,o,t)}
        when:confirmedEvents(1,false)
        then:thrown(L.Failure)
        database.transaction{it.fetchOne('select count(*) from sso_code_confirmation_results').get(0,Long)}==0L
        database.transaction{it.fetchOne('select count(*) from sso_login_events').get(0,Long)}==0L
        when:def expected=confirmedEvents(1)
        then:evidence.outcomes(deadline()).confirmed_event_ids==expected
    }
}
