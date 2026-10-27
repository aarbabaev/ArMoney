package com.arman.bank.authservice.infrastructure

import com.arman.bank.authservice.VerificationAuthorityConfigurationSpec
import com.arman.bank.authservice.domain.IdentityVerification.*
import com.arman.bank.authservice.domain.Verification.Purpose
import com.arman.bank.authservice.domain.LiveUserRevision
import com.arman.bank.authservice.domain.VerificationProjection as P
import com.arman.bank.authservice.application.*
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import com.sun.net.httpserver.HttpServer
import java.time.*
import java.util.concurrent.*
import spock.lang.Specification

/** Two actual installed Main JVMs and separate fresh PG databases in root's isolated runner.
 * Genuine REG persistence with synthetic provider observation and delivery ONLY.
 * Not Keycloak, SNI, Mailtrap inbox, Android or positive MIG banking acceptance. */
class LiveMigrationMainIntegrationSpec extends Specification {
    LiveMigrationMainFixture fixture
    def setup(){LiveMigrationMainFixture.requireIsolation();fixture=new LiveMigrationMainFixture()}
    def cleanup(){fixture?.close()}
    static String encode(Map body){JsonOutput.toJson(body)}
    Map headers(String name,String value){[(name):value,'Content-Type':'application/json']}
    Map selected(){
        def e=LegacyMigrationConfigurationSpec.environment()
        def authority=VerificationAuthorityConfigurationSpec.environment()
        authority.remove('SSO_ISSUER');authority.remove('AUTH_TO_SSO_STATUS_KEY')
        e.putAll(authority)
        e.putAll([
            INTERNAL_AUTH_KEY:LiveMigrationMainFixture.SERVICE,AUTH_MIGRATION_USER_REVISION_ENABLED:'true',
            AUTH_MIGRATION_USER_REVISION_WRITER:LiveMigrationMainFixture.CURRENT_WRITER,
            AUTH_MIGRATION_USER_MUTATION_WRITER_ID:LiveMigrationMainFixture.MUTATION_WRITER,
            AUTH_TO_USER_MIGRATION_USER_CURRENT_KEY:LiveMigrationMainFixture.CURRENT,
            USER_TO_AUTH_MIGRATION_USER_MUTATION_KEY:LiveMigrationMainFixture.MUTATION,
            AUTH_REGISTRATION_AUTHORITY_COMPLETION_ENABLED:'true',AUTH_VERIFICATION_PROJECTION_ENABLED:'true',
            AUTH_VERIFICATION_PROJECTION_WRITER_ID:LiveMigrationMainFixture.PROJECTION_WRITER,
            AUTH_TO_USER_VERIFICATION_PROJECTION_KEY:LiveMigrationMainFixture.PROJECTION,
            AUTH_VERIFICATION_PROJECTION_RETENTION_KEY:VerificationAuthorityConfigurationSpec.encoded(14),
            SSO_VERIFICATION_CLIENT_IDS:'managed',SSO_CLIENT_ID:'legacy',
            SSO_CLIENT_SECRET:'synthetic-main-introspection-secret-unique',
            SSO_INTROSPECTION_URL:'http://127.0.0.1:1/sso/realms/armoney/protocol/openid-connect/token/introspect',
            AUTH_VERIFICATION_DELIVERY_ENABLED:'false'])
        e
    }
    void ordinaryGuards(){
        assert fixture.request(fixture.authOrigin(),'POST','/v1/auth/login',[:],'{invalid').statusCode()==401
        assert fixture.request(fixture.authOrigin(),'POST','/v1/auth/login',headers('X-Service-Key',LiveMigrationMainFixture.SERVICE),'{invalid').statusCode()==400
        assert fixture.request(fixture.userOrigin(),'GET','/v1/users/me').statusCode()==401
        assert fixture.request(fixture.userOrigin(),'GET','/v1/users/me',['X-Service-Key':LiveMigrationMainFixture.SERVICE]).statusCode()==401
        assert fixture.request(fixture.userOrigin(),'GET','/v1/users/me',['X-Service-Key':LiveMigrationMainFixture.SERVICE,'X-Identity-Id':UUID.randomUUID().toString()]).statusCode()==404
    }
    def 'actual default OFF Mains keep private and browser routes unavailable and ordinary guards unchanged'(){
        when:fixture.start(false)
        then:
        ordinaryGuards()
        fixture.request(fixture.authOrigin(),'GET','/migration/legacy').statusCode()==404
        fixture.request(fixture.authOrigin(),'POST',MigrationUserMutationRoutes.PATH,headers(MigrationUserMutationRoutes.HEADER,LiveMigrationMainFixture.MUTATION),'{invalid').statusCode()==401
        fixture.request(fixture.userOrigin(),'POST',HttpMigrationUserCurrent.PATH,headers(HttpMigrationUserCurrent.HEADER,LiveMigrationMainFixture.CURRENT),'{invalid').statusCode()==401
        fixture.authDb.transaction{it.fetchOne('select count(*) from auth_user_mutation_receipts').get(0,Long)}==0L
        fixture.userCount('profiles')==0L
    }
    def 'actual enabled Mains authenticate private purposes and recover genuine REG bootstrap NOT_MIG without fabricated profile'(){
        given:
        def environment=selected()
        def command=genuineRegistration(environment)
        def after=[expected_version:command.expectedVersion() as Long,version:command.version() as Long,
            phone_contact_version:command.phoneContactVersion() as Long,verification_epoch:command.verificationEpoch(),projection_payload_hash:command.payloadHash()]
        def input=LiveUserRevision.suspension(LiveMigrationMainFixture.MUTATION_WRITER,command.operationId(),command.identityId(),null,null,after)
        fixture.start(true,environment)
        when:
        def wrongMutation=fixture.request(fixture.authOrigin(),'POST',MigrationUserMutationRoutes.PATH,headers(MigrationUserMutationRoutes.HEADER,'wrong'),'{invalid')
        def wrongDecision=fixture.request(fixture.authOrigin(),'POST',LegacyMigrationFinalizationRoutes.PREFIX+'decision',headers(LegacyMigrationFinalizationRoutes.HEADER,'wrong'),'{invalid')
        def wrongEvidence=fixture.request(fixture.authOrigin(),'POST',LegacyMigrationFinalizationRoutes.PREFIX+'publication-evidence',headers(LegacyMigrationFinalizationRoutes.HEADER,'wrong'),'{invalid')
        def wrongCurrent=fixture.request(fixture.userOrigin(),'POST',HttpMigrationUserCurrent.PATH,headers(HttpMigrationUserCurrent.HEADER,'wrong'),'{invalid')
        then:
        [wrongMutation,wrongDecision,wrongEvidence,wrongCurrent].every{it.statusCode()==401}
        ordinaryGuards()
        fixture.userCount('profiles')==0L
        fixture.request(fixture.authOrigin(),'POST',LegacyMigrationFinalizationRoutes.PREFIX+'decision',headers(LegacyMigrationFinalizationRoutes.HEADER,environment.USER_TO_AUTH_MIGRATION_DECISION_KEY),'{invalid').statusCode()==400
        fixture.request(fixture.authOrigin(),'POST',LegacyMigrationFinalizationRoutes.PREFIX+'publication-evidence',headers(LegacyMigrationFinalizationRoutes.HEADER,environment.USER_TO_AUTH_MIGRATION_DECISION_KEY),'{invalid').statusCode()==400
        fixture.request(fixture.authOrigin(),'GET','/migration/legacy',['X-ArMoney-Migration-Auth-Key':environment.MIGRATION_GATEWAY_TO_AUTH_KEY,
            'X-ArMoney-Migration-Origin':environment.MIGRATION_PUBLIC_ORIGIN]).statusCode()==200
        when:
        def response=fixture.request(fixture.authOrigin(),'POST',MigrationUserMutationRoutes.PATH,headers(MigrationUserMutationRoutes.HEADER,LiveMigrationMainFixture.MUTATION),encode(input))
        def replay=fixture.request(fixture.authOrigin(),'POST',MigrationUserMutationRoutes.PATH,headers(MigrationUserMutationRoutes.HEADER,LiveMigrationMainFixture.MUTATION),encode(input))
        then:
        response.statusCode()==200
        replay.statusCode()==200
        def parsed=new JsonSlurper().parseText(response.body())
        parsed.outcome=='NOT_MIG' && parsed.binding==null
        new JsonSlurper().parseText(replay.body())==parsed
        fixture.authDb.transaction{it.fetchOne('select count(*) from auth_user_mutation_receipts').get(0,Long)}==1L
        when:
        def projection=[operation_id:command.operationId().toString(),identity_id:command.identityId().toString(),
            expected_version:command.expectedVersion(),version:command.version(),canonical_phone:command.canonicalPhone(),
            phone_contact_version:command.phoneContactVersion(),verification_epoch:command.verificationEpoch(),payload_hash:command.payloadHash()]
        def projected=fixture.request(fixture.userOrigin(),'POST','/v1/internal/verification-projections',headers('X-ArMoney-Verification-Projection-Key',LiveMigrationMainFixture.PROJECTION),encode(projection))
        then:
        projected.statusCode()==200
        fixture.userCount('verification_projection_current')==1L
        fixture.userCount('registration_phone_claims')==1L
        fixture.userCount('profiles')==0L
        when:
        def counts=['registration_phone_claims','profile_directory','profiles','verification_projection_current','migration_baselines'].collectEntries{[(it):fixture.userCount(it)]}
        def unsupported=LiveUserRevision.current(LiveMigrationMainFixture.CURRENT_WRITER,UUID.randomUUID(),command.identityId(),UUID.randomUUID(),UUID.randomUUID(),1L,1L,1L)
        def unavailable=fixture.request(fixture.userOrigin(),'POST',HttpMigrationUserCurrent.PATH,headers(HttpMigrationUserCurrent.HEADER,LiveMigrationMainFixture.CURRENT),encode(unsupported))
        then:
        unavailable.statusCode() in [409,503]
        counts.every{k,v->fixture.userCount(k)==v}
        fixture.authDb.transaction{it.fetchOne('select count(*) from migration_banking_authority_current').get(0,Long)}==0L
    }
    /** Production enrollment/OTP/transport/consume creates authority, never raw SQL authority rows. */
    def genuineRegistration(Map environment){
        String writer='sso-provider',projectionWriter=LiveMigrationMainFixture.PROJECTION_WRITER
        def flow=new Flow(environment.SSO_ISSUER,'synthetic-subject-'+UUID.randomUUID(),'managed','root','tab',Base64.urlEncoder.withoutPadding().encodeToString(([3]*32) as byte[]))
        String phone='+971501234567',email='synthetic-registration@example.test'
        def policy=Policy.defaults([flow.issuer()] as Set,[flow.clientId()] as Set,[writer] as Set)
        def crypto=new VerificationCrypto('fixture-v1',['fixture-v1':([12]*32) as byte[]],['fixture-v1':([13]*32) as byte[]])
        def reader=new PostgresIdentityVerificationStore(fixture.authDb,crypto,([11]*32) as byte[],policy,null)
        def projection=new PostgresVerificationProjectionStore(fixture.authDb,P.Policy.defaults([projectionWriter] as Set),([14]*32) as byte[],
            {sql->{UUID id,Instant now->reader.projectionAuthorityInTransaction(sql,id,now)} as VerificationProjectionStore.AuthorityPort})
        def authority=new PostgresIdentityVerificationStore(fixture.authDb,crypto,([11]*32) as byte[],policy,projection,projectionWriter,true)
        def verification=new IdentityVerificationService(authority,{Flow expected,Instant start->new Observation(expected,phone,email,phone,true,true,start.plusSeconds(600),start)} as IdentityVerificationStore.Observations,policy)
        def inbox=new LinkedBlockingQueue<String>()
        def sms=HttpServer.create(new InetSocketAddress('127.0.0.1',0),0)
        sms.createContext('/api/messages'){exchange->
            try{
                assert exchange.requestHeaders.getFirst('Authorization')=='Bearer synthetic-main-registration-sms-token'
                def message=new JsonSlurper().parse(exchange.requestBody)
                assert message.purpose=='REGISTRATION_PHONE'
                inbox.add(message.text as String)
                byte[] ack=encode([id:UUID.randomUUID().toString(),createdAt:Instant.now().epochSecond,replay:false]).bytes
                exchange.responseHeaders.set('Content-Type','application/json');exchange.sendResponseHeaders(201,ack.length);exchange.responseBody.write(ack)
            }finally{exchange.close()}
        }
        sms.start()
        def transport=new VerificationDeliveryTransport({String recipient,String subject,String text,String html->inbox.add(text);NotificationDelivery.Result.ACCEPTED} as VerificationDeliveryTransport.MailSender,
            URI.create('http://127.0.0.1:'+sms.address.port),'synthetic-main-registration-sms-token',true)
        def worker=new VerificationDeliveryWorker(new PostgresVerificationStore(fixture.authDb,crypto),crypto,transport,Clock.systemUTC())
        try{
            def enrollment=verification.enroll(writer,new Enrollment(UUID.randomUUID(),flow,phone,email));assert enrollment.error()==null
            [Purpose.REGISTRATION_EMAIL,Purpose.REGISTRATION_PHONE].each{purpose->
                def challenge=verification.issue(writer,new Issue(UUID.randomUUID(),enrollment.enrollmentId(),flow,purpose,1));assert challenge.error()==null
                worker.tick()
                String text=inbox.poll(3,TimeUnit.SECONDS);assert text!=null:'Synthetic transport did not deliver'
                def matcher=(text=~/registration verification code is ([0-9]{6})\./);assert matcher.find():'Synthetic delivery format invalid'
                def receipt=verification.consume(writer,new Consume(UUID.randomUUID(),enrollment.enrollmentId(),challenge.challengeId(),flow,purpose,1,matcher.group(1)))
                assert receipt.error()==null && receipt.receiptId()!=null
            }
            projection.claim(projectionWriter).orElseThrow().command()
        }finally{worker.close();sms.stop(0)}
    }
}
