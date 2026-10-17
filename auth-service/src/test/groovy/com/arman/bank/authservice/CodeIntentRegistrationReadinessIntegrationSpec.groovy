package com.arman.bank.authservice.infrastructure

import com.arman.bank.authservice.application.*
import com.arman.bank.authservice.domain.IdentityVerification.*
import com.arman.bank.authservice.domain.Verification
import com.arman.bank.authservice.domain.Verification.Purpose
import com.arman.bank.authservice.domain.VerificationProjection as P
import com.arman.bank.authservice.domain.LoginProof as L
import com.arman.bank.runtime.Database
import com.sun.net.httpserver.HttpServer
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.testcontainers.containers.PostgreSQLContainer
import spock.lang.*
import java.net.http.*
import java.nio.file.*
import java.time.*
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger

/** Local predicate equivalence only: synthetic provider observations and synthetic mail/SMS
 * receivers fed by real delivery worker/transport and real OTP consume. User ACK comes from
 * actual isolated UserMain/PG over HTTP. No seeded authority/receipt/grant, fabricated ACK,
 * Keycloak/password/SMTP/TLS/carrier acceptance or session authorization is claimed. */
@com.arman.bank.authservice.RequireAcceptanceExecution
class CodeIntentRegistrationReadinessIntegrationSpec extends Specification {
    static final String ISSUER='https://issuer.example/sso/realms/armoney'
    static final String WRITER='readiness-fixture'
    static final String PROJECTION_KEY='synthetic-readiness-projection-directional-key'
    static final String SMS_KEY='synthetic_readiness_sms_sender_credential'
    @Shared PostgreSQLContainer authPg
    @Shared PostgreSQLContainer userPg
    @Shared Database database
    @Shared Process user
    @Shared Path userLog
    @Shared int userPort
    @Shared HttpClient client
    @Shared AtomicInteger sequence=new AtomicInteger()
    VerificationCrypto crypto
    PostgresIdentityVerificationStore authority
    IdentityVerificationService verification
    PostgresVerificationProjectionStore projections
    VerificationDeliveryWorker deliveryWorker
    HttpServer sms
    ExecutorService smsExecutor
    BlockingQueue<String> emailInbox=new LinkedBlockingQueue<>()
    BlockingQueue<Map> smsInbox=new LinkedBlockingQueue<>()
    Flow flow
    String email
    String phone
    String writer

    def setupSpec(){
        authPg=new PostgreSQLContainer('postgres:17.6-alpine')
        userPg=new PostgreSQLContainer('postgres:17.6-alpine')
        authPg.start()
        userPg.start()
        database=new Database(authPg.jdbcUrl,authPg.username,authPg.password)
        client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()
        new ServerSocket(0,0,InetAddress.loopbackAddress).withCloseable{userPort=it.localPort}
        Path lib=Path.of('..','user-service','build','install','user-service','lib').toAbsolutePath().normalize()
        assert Files.isDirectory(lib):'Real User ACK requires user-service:installDist; no synthetic fallback'
        String java=Path.of(System.getProperty('java.home'),'bin',System.getProperty('os.name').toLowerCase().contains('win')?'java.exe':'java').toString()
        def builder=new ProcessBuilder(java,'-Xms32m','-Xmx256m','-cp',lib.toString()+File.separator+'*','com.arman.bank.userservice.Main')
        def env=builder.environment()
        env.keySet().removeIf{it.startsWith('USER_')||it.startsWith('AUTH_')||it.startsWith('SSO_')||it.startsWith('ARMONEY_')||it.startsWith('INTERNAL_')}
        env.putAll([DB_URL:userPg.jdbcUrl,DB_USER:userPg.username,DB_PASSWORD:userPg.password,PORT:userPort.toString(),
            INTERNAL_AUTH_KEY:'synthetic-readiness-user-internal-credential',USER_VERIFICATION_PROJECTION_ENABLED:'true',
            USER_VERIFICATION_PROJECTION_WRITER_ID:WRITER,AUTH_TO_USER_VERIFICATION_PROJECTION_KEY:PROJECTION_KEY])
        userLog=Files.createTempFile('armoney-readiness-real-user-','.log')
        user=builder.redirectErrorStream(true).redirectOutput(userLog.toFile()).start()
        long limit=System.nanoTime()+TimeUnit.SECONDS.toNanos(30)
        boolean ready=false
        while(!ready&&user.alive&&System.nanoTime()<limit){
            try{ready=client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:${userPort}/health/ready")).timeout(Duration.ofSeconds(2)).GET().build(),HttpResponse.BodyHandlers.discarding()).statusCode()==200}
            catch(IOException ignored){}
            if(!ready)Thread.sleep(25)
        }
        assert ready:"Real UserMain unavailable; private synthetic log: ${userLog}"
    }
    def cleanupSpec(){
        if(user!=null){user.destroy();if(!user.waitFor(8,TimeUnit.SECONDS)){user.destroyForcibly();assert user.waitFor(5,TimeUnit.SECONDS)}}
        client?.close()
        database?.close()
        authPg?.stop()
        userPg?.stop()
    }
    def setup(){
        int n=sequence.incrementAndGet()
        writer=WRITER+'-'+n
        phone='+97150'+String.format('%07d',n)
        email='readiness-'+UUID.randomUUID()+'@example.test'
        flow=new Flow(ISSUER,'subject-'+UUID.randomUUID(),'armoney-web','root-'+n,'tab-'+n,
            Base64.urlEncoder.withoutPadding().encodeToString(([3]*32) as byte[]))
        crypto=new VerificationCrypto('test',[test:([11]*32) as byte[]],[test:([22]*32) as byte[]])
        def reader=new PostgresIdentityVerificationStore(database,crypto,([41]*32) as byte[],policy(),null)
        projections=new PostgresVerificationProjectionStore(database,P.Policy.defaults([writer] as Set),([42]*32) as byte[],
            {sql->{UUID identity,Instant now->reader.projectionAuthorityInTransaction(sql,identity,now)} as VerificationProjectionStore.AuthorityPort})
        authority=new PostgresIdentityVerificationStore(database,crypto,([41]*32) as byte[],policy(),projections,writer,true)
        verification=new IdentityVerificationService(authority,{Flow expected,Instant start->
            new Observation(expected,phone,email,phone,true,true,start.plusSeconds(600),start)
        } as IdentityVerificationStore.Observations,policy())
        smsExecutor=Executors.newSingleThreadExecutor()
        sms=HttpServer.create(new InetSocketAddress('127.0.0.1',0),0)
        sms.executor=smsExecutor
        sms.createContext('/api/messages',{exchange->
            try{
                assert exchange.requestMethod=='POST'
                assert exchange.requestHeaders.getFirst('Authorization')=='Bearer '+SMS_KEY
                def message=new JsonSlurper().parse(exchange.requestBody)
                assert message.destination==phone
                assert message.purpose=='REGISTRATION_PHONE'
                // Acceptance follows actual capture; no database delivery/receipt write by this receiver.
                smsInbox.add(message)
                byte[] ack=JsonOutput.toJson([id:UUID.randomUUID().toString(),createdAt:Instant.now().epochSecond,replay:false]).getBytes('UTF-8')
                exchange.responseHeaders.set('Content-Type','application/json')
                exchange.sendResponseHeaders(201,ack.length)
                exchange.responseBody.write(ack)
            }finally{exchange.close()}
        } as com.sun.net.httpserver.HttpHandler)
        sms.start()
        def transport=new VerificationDeliveryTransport({String recipient,String subject,String text,String html->
            assert recipient==email
            emailInbox.add(text)
            com.arman.bank.authservice.application.NotificationDelivery.Result.ACCEPTED
        } as VerificationDeliveryTransport.MailSender,URI.create('http://127.0.0.1:'+sms.address.port),SMS_KEY,true)
        deliveryWorker=new VerificationDeliveryWorker(new PostgresVerificationStore(database,crypto),crypto,transport,Clock.systemUTC())
    }
    def cleanup(){
        deliveryWorker?.close()
        sms?.stop(0)
        smsExecutor?.shutdownNow()
        assert smsExecutor==null||smsExecutor.awaitTermination(5,TimeUnit.SECONDS)
        emailInbox.clear()
        smsInbox.clear()
    }
    Policy policy(){Policy.defaults([ISSUER] as Set,['armoney-web'] as Set,[writer] as Set)}
    def enroll(){verification.enroll(writer,new Enrollment(UUID.randomUUID(),flow,phone,email))}
    def consume(UUID enrollment,Purpose purpose){
        def challenge=verification.issue(writer,new Issue(UUID.randomUUID(),enrollment,flow,purpose,1))
        assert challenge.error()==null
        deliveryWorker.tick()
        String text=purpose==Purpose.REGISTRATION_EMAIL?emailInbox.poll(3,TimeUnit.SECONDS):smsInbox.poll(3,TimeUnit.SECONDS)?.text
        assert text!=null:'Real generated OTP transport capture missing'
        def matcher=(text=~/registration verification code is ([0-9]{6})\./)
        if(!matcher.find())throw new AssertionError('Accepted generated OTP message shape differs')
        String code=matcher.group(1)
        def result=verification.consume(writer,new Consume(UUID.randomUUID(),enrollment,challenge.challengeId(),flow,purpose,1,code))
        assert result.error()==null
        assert result.receiptId()!=null
        result
    }
    UUID completed(){
        def enrollment=enroll()
        assert enrollment.error()==null
        consume(enrollment.enrollmentId(),Purpose.REGISTRATION_EMAIL)
        consume(enrollment.enrollmentId(),Purpose.REGISTRATION_PHONE)
        enrollment.enrollmentId()
    }
    UUID projected(){
        UUID enrollment=completed()
        def claim=projections.claim(writer).orElseThrow()
        assert claim.command().identityId()!=null
        def transport=new HttpVerificationProjection(URI.create("http://127.0.0.1:${userPort}"),PROJECTION_KEY)
        try{
            def response=transport.send(claim)
            assert response.outcome()==P.Outcome.ACK
            assert response.acknowledgement().matches(claim.command())
            assert authority.attach(writer,claim,response)
        }finally{transport.close()}
        enrollment
    }
    UUID eligible(){UUID enrollment=projected();verification.status(writer,enrollment,flow);enrollment}
    Map outcome(boolean confirmation=false){
        try{
            Instant deadline=database.transaction{sql->
                PostgresVerificationProjectionStore.lockControls(sql)
                def validator=new PostgresBankingEligibilityStore(sql)
                Instant bound=confirmation?
                    validator.requireLocalForConfirmation(new L.Binding(ISSUER,flow.subject(),flow.clientId(),'synthetic-provider-session')):
                    validator.requireCurrentLocalRegistration(ISSUER,flow.subject())
                boolean deadlineCurrent=sql.fetchOne('select clock_timestamp() < cast(? as timestamptz)',[java.time.OffsetDateTime.ofInstant(bound,ZoneOffset.UTC)] as Object[]).get(0,Boolean)
                assert deadlineCurrent
                bound
            }
            [deadline:deadline]
        }catch(Verification.Failure failure){[error:failure.error()]}
    }
    void mutate(String sql,Object...args){database.transaction{it.execute(sql,args)}}
    UUID operation(UUID enrollment){database.transaction{it.fetchOne('select projection_operation_id from verification_authorities where enrollment_id=?',enrollment).get(0,UUID)}}
    def 'readiness and unchanged confirmation reject absent enrollment identically'(){
        expect:outcome()==[error:Verification.Error.CONFLICT];outcome(true)==outcome()
    }
    def 'zero or one genuine consumed OTP cannot satisfy readiness #purpose'(){
        given:
        def enrollment=enroll()
        if(purpose!=null)consume(enrollment.enrollmentId(),purpose)
        expect:
        outcome()==[error:Verification.Error.CONFLICT]
        outcome(true)==outcome()
        where:
        purpose << [null,Purpose.REGISTRATION_EMAIL,Purpose.REGISTRATION_PHONE]
    }
    def 'materialized permanent marker and two receipts without User ACK are insufficient'(){
        given:UUID enrollment=completed()
        expect:
        database.transaction{it.fetchOne('select count(*) from managed_registration_principals where enrollment_id=?',enrollment).get(0,Integer)}==1
        outcome()==[error:Verification.Error.CONFLICT]
        outcome(true)==outcome()
    }
    def 'real persisted User ACK PROJECTED without completion grant is insufficient'(){
        given:UUID enrollment=projected()
        expect:
        database.transaction{it.fetchOne('select state from verification_authorities where enrollment_id=?',enrollment).get(0,String)}=='PROJECTED'
        outcome()==[error:Verification.Error.CONFLICT]
        outcome(true)==outcome()
    }
    def 'full real receipt projection and grant predicate returns identical original deadline'(){
        given:UUID enrollment=eligible()
        when:
        def ready=outcome()
        def confirmation=outcome(true)
        int grantCount=database.transaction{it.fetchOne('select count(*) from banking_eligibility_grants g join managed_registration_principals m on m.identity_id=g.identity_id where m.enrollment_id=?',enrollment).get(0,Integer)}
        then:
        ready.deadline instanceof Instant
        confirmation==ready
        grantCount==1
    }
    def 'consumed challenge regression invalidates both entry points'(){
        given:UUID enrollment=eligible()
        mutate("update verification_challenges set state='EXPIRED' where id in (select challenge_id from verification_receipts where enrollment_id=?)",enrollment)
        expect:outcome()==[error:Verification.Error.CONFLICT];outcome(true)==outcome()
    }
    def 'current contact association or epoch change rejects stale completion evidence #mutation'(){
        given:UUID enrollment=eligible()
        if(mutation=='contact')mutate("update verification_enrollments set email='changed@example.test' where id=?",enrollment)
        else mutate('update verification_authorities set verification_epoch=verification_epoch+1 where enrollment_id=?',enrollment)
        expect:outcome()==[error:Verification.Error.CONFLICT];outcome(true)==outcome()
        where:mutation << ['contact','epoch']
    }
    def 'persisted ACK tampering and recovery cannot satisfy readiness #mutation'(){
        given:
        UUID enrollment=eligible()
        UUID op=operation(enrollment)
        switch(mutation){
            case 'revision':mutate('update auth_verification_projection_tombstones set version=version+1 where operation_id=?',op);break
            case 'phone-version':mutate('update auth_verification_projection_tombstones set phone_contact_version=phone_contact_version+1 where operation_id=?',op);break
            case 'epoch':mutate('update auth_verification_projection_tombstones set verification_epoch=verification_epoch+1 where operation_id=?',op);break
            case 'payload':mutate("update auth_verification_projection_tombstones set payload_hash=repeat('f',64) where operation_id=?",op);break
            case 'recovery':mutate('update auth_verification_projection_tombstones set recovery_barrier=true where operation_id=?',op);break
        }
        expect:outcome()==[error:Verification.Error.CONFLICT];outcome(true)==outcome()
        where:mutation << ['revision','phone-version','epoch','payload','recovery']
    }
    def 'expired local enrollment retains unavailable classification for both entry points'(){
        given:UUID enrollment=eligible()
        // Isolated negative fault injection after genuine completion; not a clock/authority seed.
        mutate("update verification_enrollments set expires_at=clock_timestamp()-interval '1 second' where id=?",enrollment)
        expect:outcome()==[error:Verification.Error.UNAVAILABLE];outcome(true)==outcome()
    }
    def 'persisted ACK without its original projection command is insufficient'(){
        given:UUID enrollment=eligible()
        mutate('delete from auth_verification_projection_commands where operation_id=?',operation(enrollment))
        expect:outcome()==[error:Verification.Error.CONFLICT];outcome(true)==outcome()
    }
    def 'new entry point retains caller-owned control-lock requirement'(){
        when:database.transaction{new PostgresBankingEligibilityStore(it).requireCurrentLocalRegistration(ISSUER,flow.subject())}
        then:thrown(P.Failure)
    }
}
