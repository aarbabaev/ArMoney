package com.arman.bank.authservice.infrastructure

import com.arman.bank.authservice.CodeConfirmationConfigurationSpec
import com.arman.bank.authservice.domain.IdentityVerification.*
import com.arman.bank.runtime.Database
import com.sun.net.httpserver.HttpServer
import groovy.json.*
import org.testcontainers.containers.PostgreSQLContainer
import spock.lang.*
import java.net.http.*
import java.nio.file.*
import java.time.*
import java.util.concurrent.*
import java.util.concurrent.atomic.*

/** Real AuthMain/UserMain/SMS child JVMs, PG and LOCAL SMTP TEST worker path.
 * Pretoken HTTP peer is explicitly SYNTHETIC: no genuine Keycloak/password/visibility,
 * external Mailtrap, token redemption, banking/session grant or joint activation evidence.
 * Actual Main children bind all interfaces: coordinator-isolated runtime ONLY. */
class CodeIntentAuthorityFixtureIntegrationSpec extends Specification {
    static final String ISSUER='https://issuer.example/sso/realms/armoney'
    static final String FIXTURE_KEY='synthetic-joint-authority-provider-fixture-key'
    static final String SMS_SEND='synthetic_joint_sms_sender_token_material'
    static final String SMS_READ='synthetic_joint_sms_reader_token_material'
    @Shared @TempDir Path smsDirectory
    @Shared PostgreSQLContainer authPg
    @Shared PostgreSQLContainer userPg
    @Shared Database database
    @Shared HttpClient client
    @Shared CodeIntentAuthorityFixture.LocalSmtp smtp
    @Shared Map environment
    @Shared List<Process> children=[]
    @Shared List<Path> privateLogs=[]
    @Shared int userPort
    @Shared int authPort
    @Shared int smsPort
    @Shared AtomicInteger sequence=new AtomicInteger()
    CodeIntentAuthorityFixture fixture
    HttpServer provider
    ExecutorService providerExecutor
    AtomicInteger providerCalls=new AtomicInteger()
    AtomicBoolean enabled=new AtomicBoolean(true)
    String phone
    String email
    Flow flow
    String handle
    UUID command
    def setupSpec(){
        authPg=new PostgreSQLContainer('postgres:17.6-alpine')
        userPg=new PostgreSQLContainer('postgres:17.6-alpine')
        authPg.start();userPg.start()
        database=new Database(authPg.jdbcUrl,authPg.username,authPg.password)
        client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()
        userPort=freePort();authPort=freePort();smsPort=freePort()
        environment=CodeConfirmationConfigurationSpec.environment()+[
            CI:'true',CODE_SESSION_ACCEPTANCE_ISOLATED:'true',AUTH_VERIFICATION_DELIVERY_ENABLED:'false',
            AUTH_CODE_INTENT_FIXTURE_PROVIDER_KEY:FIXTURE_KEY,
            INTERNAL_AUTH_KEY:'synthetic-joint-auth-user-internal-service-key',
            SSO_INTROSPECTION_URL:'http://127.0.0.1:1/unused',SSO_CLIENT_ID:'armoney-web',SSO_CLIENT_SECRET:'synthetic-joint-introspection-client-secret',
            USER_BASE_URL:'http://127.0.0.1:'+userPort,
            VERIFICATION_SMS_SEND_TOKEN:SMS_SEND,SMS_SANDBOX_READ_TOKEN:SMS_READ,
            MAILTRAP_SMTP_USERNAME:'synthetic-joint-smtp-user',MAILTRAP_SMTP_PASSWORD:'synthetic-joint-smtp-password',MAILTRAP_FROM_EMAIL:'synthetic@example.test']
        spawn('user-service','com.arman.bank.userservice.Main',[
            DB_URL:userPg.jdbcUrl,DB_USER:userPg.username,DB_PASSWORD:userPg.password,PORT:userPort.toString(),
            INTERNAL_AUTH_KEY:environment.INTERNAL_AUTH_KEY,USER_VERIFICATION_PROJECTION_ENABLED:'true',
            USER_VERIFICATION_PROJECTION_WRITER_ID:environment.AUTH_VERIFICATION_PROJECTION_WRITER_ID,
            AUTH_TO_USER_VERIFICATION_PROJECTION_KEY:environment.AUTH_TO_USER_VERIFICATION_PROJECTION_KEY],userPort)
        spawn('sms-sandbox','com.arman.bank.smssandbox.Main',[
            PORT:smsPort.toString(),SMS_SANDBOX_DATA_DIR:smsDirectory.toString(),SMS_SANDBOX_SEND_TOKEN:SMS_SEND,SMS_SANDBOX_READ_TOKEN:SMS_READ],smsPort)
        spawn('auth-service','com.arman.bank.authservice.Main',environment+[
            DB_URL:authPg.jdbcUrl,DB_USER:authPg.username,DB_PASSWORD:authPg.password,PORT:authPort.toString()],authPort)
        smtp=new CodeIntentAuthorityFixture.LocalSmtp(environment.MAILTRAP_SMTP_USERNAME,environment.MAILTRAP_SMTP_PASSWORD)
    }
    def cleanupSpec(){
        try{smtp?.close()}finally{
            children.each{it.destroy()}
            children.each{if(!it.waitFor(8,TimeUnit.SECONDS)){it.destroyForcibly();assert it.waitFor(5,TimeUnit.SECONDS)}}
            client?.close();database?.close();authPg?.stop();userPg?.stop()
        }
    }
    static int freePort(){int port;new ServerSocket(0,0,InetAddress.loopbackAddress).withCloseable{port=it.localPort};port}
    void spawn(String module,String main,Map supplied,int port){
        Path lib=(module=='auth-service'?Path.of('build','install',module,'lib'):Path.of('..',module,'build','install',module,'lib')).toAbsolutePath().normalize()
        assert Files.isDirectory(lib):'Actual distribution required; no substitute or fabricated ACK fallback'
        String java=Path.of(System.getProperty('java.home'),'bin',System.getProperty('os.name').toLowerCase().contains('win')?'java.exe':'java').toString()
        def builder=new ProcessBuilder(java,'-Xms32m','-Xmx256m','-cp',lib.toString()+File.separator+'*',main)
        def env=builder.environment()
        env.keySet().removeIf{it.startsWith('AUTH_')||it.startsWith('SSO_')||it.startsWith('USER_')||it.startsWith('SMS_')||it.startsWith('ARMONEY_')||it.startsWith('INTERNAL_')||it.startsWith('MAILTRAP_')||it.startsWith('LOGIN_')||it.startsWith('VERIFICATION_')}
        env.putAll(supplied)
        Path log=Files.createTempFile('armoney-joint-auth-synthetic-','.log');privateLogs.add(log)
        Process process=builder.redirectErrorStream(true).redirectOutput(log.toFile()).start();children.add(process)
        long limit=System.nanoTime()+TimeUnit.SECONDS.toNanos(35)
        boolean ready=false
        while(!ready&&process.alive&&System.nanoTime()<limit){
            try{ready=client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:${port}/health/ready")).timeout(Duration.ofSeconds(2)).GET().build(),HttpResponse.BodyHandlers.discarding()).statusCode()==200}
            catch(IOException ignored){}
            if(!ready)Thread.sleep(25)
        }
        assert ready:"Isolated child unavailable; private synthetic log: ${log}"
    }
    def setup(){
        int n=sequence.incrementAndGet()
        phone='+97152'+String.format('%07d',n)
        email='joint-'+UUID.randomUUID()+'@example.test'
        flow=new Flow(ISSUER,'joint-'+UUID.randomUUID(),'armoney-web','root-'+n,'tab-'+n,Base64.urlEncoder.withoutPadding().encodeToString(([3]*32) as byte[]))
        handle=Base64.urlEncoder.withoutPadding().encodeToString(UUID.randomUUID().toString().replace('-','').getBytes('US-ASCII'))
        command=UUID.randomUUID()
        providerExecutor=Executors.newSingleThreadExecutor()
        provider=HttpServer.create(new InetSocketAddress('127.0.0.1',0),0);provider.executor=providerExecutor
        provider.createContext('/sso/realms/armoney/armoney-status/enrollment',{exchange->
            try{
                providerCalls.incrementAndGet()
                if(exchange.requestHeaders.getFirst('X-ArMoney-Auth-Status-Key')!=environment.AUTH_TO_SSO_STATUS_KEY){exchange.sendResponseHeaders(401,-1);return}
                def body=new JsonSlurper().parse(exchange.requestBody)
                def expected=[issuer:flow.issuer(),subject:flow.subject(),client_id:flow.clientId(),provider_root_session_id:flow.rootSessionId(),provider_tab_id:flow.tabId(),authentication_session_id:flow.authenticationSessionId()]
                if(body!=expected){exchange.sendResponseHeaders(400,-1);return}
                def response=expected+[username:phone,email:email,phone_number:phone,enabled:enabled.get(),authentication_session_active:true,
                    authentication_session_expires_at:Instant.now().plusSeconds(600).truncatedTo(java.time.temporal.ChronoUnit.SECONDS).toString()]
                byte[] bytes=JsonOutput.toJson(response).getBytes('UTF-8')
                exchange.responseHeaders.set('Content-Type','application/json');exchange.sendResponseHeaders(200,bytes.length);exchange.responseBody.write(bytes)
            }finally{exchange.close()}
        } as com.sun.net.httpserver.HttpHandler)
        provider.start()
        def policy=Policy.defaults([ISSUER] as Set,['armoney-web','armoney-ios'] as Set,['sso-provider'] as Set)
        fixture=new CodeIntentAuthorityFixture(FIXTURE_KEY,policy,{
            new CodeIntentAuthorityFixture.RealWork(database,environment,policy,URI.create('http://127.0.0.1:'+provider.address.port),URI.create('http://127.0.0.1:'+smsPort),smtp)
        } as CodeIntentAuthorityFixture.Factory)
        fixture.start('127.0.0.1',0)
    }
    def cleanup(){
        fixture?.close();provider?.stop(0);providerExecutor?.shutdownNow()
        assert providerExecutor==null||providerExecutor.awaitTermination(5,TimeUnit.SECONDS)
        noProofOrSession()
    }
    Map prepared(){[experiment_handle:handle,command_id:command.toString(),issuer:flow.issuer(),subject:flow.subject(),client_id:flow.clientId(),
        provider_root_session_id:flow.rootSessionId(),provider_tab_id:flow.tabId(),authentication_session_id:flow.authenticationSessionId()]}
    Map control(UUID id=command){[experiment_handle:handle,command_id:id.toString()]}
    def post(String operation,Map body){
        def request=HttpRequest.newBuilder(URI.create('http://127.0.0.1:'+fixture.port()+CodeIntentAuthorityFixture.PREFIX+operation))
            .timeout(Duration.ofSeconds(10)).header('Content-Type','application/json').header(CodeIntentAuthorityFixture.HEADER,FIXTURE_KEY)
            .POST(HttpRequest.BodyPublishers.ofString(JsonOutput.toJson(body))).build()
        client.send(request,HttpResponse.BodyHandlers.ofString())
    }
    boolean awaitState(String expected){
        long limit=System.nanoTime()+TimeUnit.SECONDS.toNanos(60)
        while(System.nanoTime()<limit){
            def response=post('state',control())
            if(response.statusCode()!=200)throw new AssertionError('Pure fixture state unavailable')
            String state=new JsonSlurper().parseText(response.body()).state
            if(state==expected)return true
            if(state in ['FAILED','CANCELLED','EXPIRED'])throw new AssertionError('Fixture preparation ended before expected state')
            Thread.sleep(500)
        }
        throw new AssertionError('Bounded fixture preparation deadline reached')
    }
    long count(String table){database.transaction{it.fetchOne('select count(*) from '+table).get(0,Long)}}
    boolean noProofOrSession(){
        ['sso_code_intents','sso_login_proofs','sso_login_events','sessions'].each{if(count(it)!=0)throw new AssertionError('Fixture must not stage or grant proof/session')}
        true
    }
    static boolean requireSafe(boolean condition){if(!condition)throw new AssertionError('Private fixture invariant failed');true}
    def 'real OTP delivery consumption materialization User ACK and grant precede opaque readiness'(){
        when:def accepted=post('prepare',prepared())
        then:accepted.statusCode()==202;awaitState('READY')
        when:
        int calls=providerCalls.get()
        def state=post('state',control())
        then:state.body()=='{"state":"READY"}';providerCalls.get()==calls
        when:
        UUID readiness=UUID.randomUUID()
        def first=post('readiness',control(readiness))
        def replay=post('readiness',control(readiness))
        then:first.statusCode()==200;replay.statusCode()==200;requireSafe(first.body()==replay.body());providerCalls.get()>=calls+2
        database.transaction{it.fetchOne("select count(*) from verification_receipts r join verification_enrollments e on e.id=r.enrollment_id where e.subject=?",flow.subject()).get(0,Integer)}==2
        database.transaction{it.fetchOne("select count(*) from banking_eligibility_grants g join managed_registration_principals m on m.identity_id=g.identity_id join verification_enrollments e on e.id=m.enrollment_id where e.subject=?",flow.subject()).get(0,Integer)}==1
        database.transaction{it.fetchOne("select count(*) from auth_verification_projection_tombstones t join verification_authorities a on a.projection_operation_id=t.operation_id join verification_enrollments e on e.id=a.enrollment_id where e.subject=? and t.state='ACKNOWLEDGED' and not t.recovery_barrier",flow.subject()).get(0,Integer)}==1
        noProofOrSession()
    }
    def 'fresh real HTTP negative after readiness cannot replay cached handle or fabricate ACTIVE'(){
        given:requireSafe(post('prepare',prepared()).statusCode()==202);awaitState('READY');UUID readiness=UUID.randomUUID();requireSafe(post('readiness',control(readiness)).statusCode()==200)
        when:enabled.set(false);def denied=post('readiness',control(readiness));def repeat=post('readiness',control(readiness))
        then:denied.statusCode()==503;repeat.statusCode()==503;post('state',control()).body()=='{"state":"FAILED"}'
        database.transaction{it.fetchOne('select a.state from verification_authorities a join verification_enrollments e on e.id=a.enrollment_id where e.subject=?',flow.subject()).get(0,String)}=='SUSPENDED'
        noProofOrSession()
    }
    def 'existing ambiguous SMS inbox fails before either receipt without decrypt shortcut'(){
        given:
        2.times{
            def request=HttpRequest.newBuilder(URI.create('http://127.0.0.1:'+smsPort+'/api/messages')).timeout(Duration.ofSeconds(3))
                .header('Content-Type','application/json').header('Authorization','Bearer '+SMS_SEND).header('Idempotency-Key',UUID.randomUUID().toString())
                .POST(HttpRequest.BodyPublishers.ofString(JsonOutput.toJson([destination:phone,purpose:'REGISTRATION_PHONE',text:'synthetic inbox ambiguity marker']))).build()
            requireSafe(client.send(request,HttpResponse.BodyHandlers.discarding()).statusCode()==201)
        }
        when:def accepted=post('prepare',prepared())
        then:accepted.statusCode()==202;awaitState('FAILED');post('readiness',control()).statusCode()==503
        database.transaction{it.fetchOne('select count(*) from verification_receipts r join verification_enrollments e on e.id=r.enrollment_id where e.subject=?',flow.subject()).get(0,Integer)}==0
        noProofOrSession()
    }
}
