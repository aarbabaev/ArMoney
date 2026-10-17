package com.arman.bank.authservice.infrastructure

import com.arman.bank.authservice.application.*
import com.arman.bank.authservice.domain.IdentityVerification.*
import com.arman.bank.authservice.domain.Verification
import com.arman.bank.authservice.domain.LoginProof as L
import com.arman.bank.authservice.domain.Verification.Purpose
import com.arman.bank.authservice.domain.VerificationProjection as P
import com.arman.bank.authservice.infrastructure.*
import com.arman.bank.runtime.Database
import org.testcontainers.containers.PostgreSQLContainer
import spock.lang.Specification
import java.time.*
import java.util.concurrent.*

/** Real PG and explicit default-off composition; no observer requests/provider activation or positive session proof. */
class CodeConfirmationResourcesIntegrationSpec extends Specification {
    static final String WRITER='sso-provider'
    static final String ISSUER='https://issuer.example/sso/realms/armoney'
    static final String PHONE='+971501234567'
    static final String EMAIL='fixture@example.test'
    boolean grantsEnabled=true
    CodeConfirmationResources resources
    io.javalin.Javalin app
    java.net.http.HttpClient client=java.net.http.HttpClient.newHttpClient()
    Map environment=com.arman.bank.authservice.CodeConfirmationConfigurationSpec.environment()+[
        ARMONEY_SSO_MANAGED_PAIRS:groovy.json.JsonOutput.toJson([[issuer:ISSUER,client_id:'armoney-web']]),
        ARMONEY_SSO_LEGACY_PAIRS:groovy.json.JsonOutput.toJson([[issuer:ISSUER,client_id:'armoney-ios']])]
    CodeConfirmationConfiguration configuration
    PostgreSQLContainer postgres
    Database database
    VerificationCrypto crypto
    PostgresIdentityVerificationStore authority
    PostgresVerificationProjectionStore projections
    Flow flow=new Flow(ISSUER,'new-subject','armoney-web','root','tab',Base64.urlEncoder.withoutPadding().encodeToString(([3]*32) as byte[]))
    def setup(){
        postgres=new PostgreSQLContainer('postgres:17.6-alpine');postgres.start()
        database=new Database(postgres.jdbcUrl,postgres.username,postgres.password)
        crypto=new VerificationCrypto('test',[test:([11]*32) as byte[]],[test:([22]*32) as byte[]])
        wire()
        configuration=new CodeConfirmationConfiguration(environment)
        resources=CodeConfirmationResources.open(database,configuration).orElseThrow()
        assert field(field(resources,'cleanup'),'executor')==null // Construction starts no maintenance thread.
        app=io.javalin.Javalin.create{resources.configure(it)}.start('127.0.0.1',0)
        resources.start() // Caller has completed HTTP startup.
        long limit=System.nanoTime()+TimeUnit.SECONDS.toNanos(5)
        while(!row('sso_code_intent_cleanup').healthy&&System.nanoTime()<limit)Thread.sleep(20)
        assert row('sso_code_intent_cleanup').healthy
    }
    def cleanup(){app?.stop();resources?.close();client?.close();database?.close();postgres?.stop()}
    void wire(){
        def reader=new PostgresIdentityVerificationStore(database,crypto,([41]*32) as byte[],policy(),null)
        projections=new PostgresVerificationProjectionStore(database,P.Policy.defaults([WRITER] as Set),([42]*32) as byte[],
            { sql -> { UUID identity,Instant now -> reader.projectionAuthorityInTransaction(sql,identity,now) } as VerificationProjectionStore.AuthorityPort })
        authority=new PostgresIdentityVerificationStore(database,crypto,([41]*32) as byte[],policy(),projections,WRITER,grantsEnabled)
    }
    static Policy policy(){Policy.defaults([ISSUER] as Set,['armoney-web'] as Set,[WRITER] as Set)}
    def observed(Closure operation,String email=EMAIL){
        def started=authority.admit(WRITER,flow)
        def observation=new Observation(flow,PHONE,email,PHONE,true,true,started.plusSeconds(600),started)
        operation.call(observation,started)
    }
    def enroll(String email=EMAIL){observed({o,t->authority.enroll(WRITER,new Enrollment(UUID.randomUUID(),flow,PHONE,email),o,t)},email)}
    def open(UUID enrollment,Purpose purpose,int version=1,String email=EMAIL){
        def challenge=observed({o,t->authority.issue(WRITER,new Issue(UUID.randomUUID(),enrollment,flow,purpose,version),o,t)},email)
        assert challenge.error()==null
        def delivery=new PostgresVerificationStore(database,crypto)
        def lease=delivery.claimDelivery().orElseThrow()
        def payload=crypto.decrypt(lease.encrypted(),enrollment,lease.challengeId(),purpose,version)
        assert delivery.finishDelivery(lease.deliveryId(),lease.fence(),true)
        new Consume(UUID.randomUUID(),enrollment,challenge.challengeId(),flow,purpose,version,payload.code())
    }
    def consume(Consume command,String email=EMAIL){observed({o,t->authority.consume(WRITER,command,o,t)},email)}
    def prepare(UUID enrollment){observed({o,t->authority.prepare(WRITER,UUID.randomUUID(),enrollment,flow,o,t)})}
    def row(String table){database.transaction{it.fetchOne('select * from '+table)?.intoMap()}}
    long count(String table){database.transaction{it.fetchOne('select count(*) from '+table).get(0,Long)}}
    void execute(String query,Object...args){database.transaction{it.execute(query,args)}}
    static P.Result ack(P.Claim claim){def c=claim.command();new P.Result(P.Outcome.ACK,new P.Ack(c.operationId(),c.identityId(),c.version(),c.phoneContactVersion(),c.verificationEpoch(),c.payloadHash()))}
    def complete(){
        def enrolled=enroll()
        consume(open(enrolled.enrollmentId(),Purpose.REGISTRATION_EMAIL))
        consume(open(enrolled.enrollmentId(),Purpose.REGISTRATION_PHONE))
        enrolled
    }
    def projected(){
        def enrolled=complete()
        def claim=projections.claim(WRITER).orElseThrow()
        assert authority.attach(WRITER,claim,ack(claim))
        enrolled
    }
    def status(UUID enrollment,String email=EMAIL){observed({o,t->authority.status(WRITER,enrollment,flow,o,t)},email)}
    def grants(){database.transaction{it.fetch('select to_jsonb(t)::text as snapshot from banking_eligibility_grants t order by identity_id,verification_epoch').getValues('snapshot',String)}}
    L.Write command(){
        def now=Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS)
        new L.Write(UUID.randomUUID(),UUID.randomUUID().toString().replace('-','')*2,'code-v1',ISSUER,flow.subject(),'armoney-web','user-session',
            flow.authenticationSessionId(),now,now.plusSeconds(120),L.Kind.INTERACTIVE,UUID.randomUUID(),now)
    }
    def eligible(){def enrollment=projected();status(enrollment.enrollmentId());enrollment}
    static Object field(Object owner,String name){def member=owner.getClass().getDeclaredField(name);member.accessible=true;member.get(owner)}
    static Map write(L.Write input){[command_id:input.commandId().toString(),code_digest:input.codeDigest(),code_key_id:input.codeKeyId(),issuer:input.issuer(),subject:input.subject(),client_id:input.clientId(),user_session_id:input.userSessionId(),
        authentication_session_id:input.authenticationSessionId(),issued_at:input.issuedAt().toString(),expires_at:input.expiresAt().toString(),kind:'interactive',event_id:input.eventId().toString(),password_authenticated_at:input.passwordAuthenticatedAt().toString()]}
    static Map key(L.Write input){[issuer:input.issuer(),subject:input.subject(),client_id:input.clientId(),user_session_id:input.userSessionId(),candidates:[[code_key_id:input.codeKeyId(),code_digest:input.codeDigest()]]]}
    static Map confirm(L.Write input){[issuer:input.issuer(),subject:input.subject(),client_id:input.clientId(),user_session_id:input.userSessionId(),code_key_id:input.codeKeyId(),code_digest:input.codeDigest()]}
    def post(String operation,Map body){
        String path='/internal/sso-code-intents'+(operation=='stage'?'':'/'+operation)
        String header=operation=='stage'?'X-ArMoney-Login-Proof-Write-Key':operation=='key'?'X-ArMoney-Code-Intent-Lookup-Key':'X-ArMoney-Login-Proof-Confirm-Key'
        String credential=operation=='stage'?environment.SSO_LOGIN_PROOF_WRITE_KEY:operation=='key'?environment.SSO_CODE_INTENT_LOOKUP_KEY:environment.SSO_LOGIN_PROOF_CONFIRM_KEY
        def request=java.net.http.HttpRequest.newBuilder(URI.create("http://127.0.0.1:${app.port()}${path}")).timeout(Duration.ofSeconds(6)).header('Content-Type','application/json').header(header,credential)
            .POST(java.net.http.HttpRequest.BodyPublishers.ofString(groovy.json.JsonOutput.toJson(body))).build()
        client.send(request,java.net.http.HttpResponse.BodyHandlers.ofString())
    }
    def 'bundle exposes stage and original-key selection using shared components and no extra hourly debit'(){
        given:eligible();def input=command()
        when:def staged=post('stage',write(input));def selected=post('key',key(input))
        then:staged.statusCode()==200;selected.statusCode()==200;selected.body()=='{"code_key_id":"code-v1"}'
        count('sso_code_intents')==1;count('sso_code_intent_commands')==1;count('sso_login_creation_reservations')==1
        count('sso_login_proofs')==0;count('sso_login_events')==0;count('sessions')==0
        field(field(resources,'stageRoutes'),'composedScopes').is(configuration.scopes())
    }
    def 'composed stage rejects configured legacy pair before stage mutation inside one admission scope'(){
        given:eligible();def input=command();def before=count('sso_code_intents')
        when:def denied=post('stage',write(input)+[client_id:'armoney-ios'])
        then:denied.statusCode()==409;count('sso_code_intents')==before;count('sso_login_creation_reservations')==0
        count('sso_code_intent_nonces')==0;count('sso_login_proofs')==0;count('sessions')==0
        count('sso_login_proof_admission')==1
    }
    def 'stage and lookup each retain their own single backend and single committed admission'(){
        given:
        eligible();def input=command()
        execute('create table qa_bundle_backends(phase text,pid integer)')
        execute('''create function qa_bundle_backends() returns trigger language plpgsql as $$begin insert into qa_bundle_backends values(TG_TABLE_NAME,pg_backend_pid());return new;end $$''')
        execute('create trigger qa_bundle_admit after update on sso_login_proof_admission for each row execute function qa_bundle_backends()')
        execute('create trigger qa_bundle_intent after insert on sso_code_intents for each row execute function qa_bundle_backends()')
        when:def response=post('stage',write(input))
        then:response.statusCode()==200
        database.transaction{sql->
            assert sql.fetchOne('select count(distinct pid) from qa_bundle_backends').get(0,Integer)==1
            assert sql.fetchOne('select count(*) from qa_bundle_backends where phase=?','sso_login_proof_admission').get(0,Integer)==1
            true
        }
        when:execute('delete from qa_bundle_backends');def selected=post('key',key(input))
        then:selected.statusCode()==200
        database.transaction{sql->sql.fetchOne('select count(*) from qa_bundle_backends where phase=?','sso_login_proof_admission').get(0,Integer)==1}
    }
    def 'composition shares original stores and material but legacy eligibility supplier remains unavailable'(){
        given:
        eligible();def input=command()
        def intents=field(field(resources,'stageRoutes'),'store')
        def lookupStore=field(field(resources,'lookupRoutes'),'store')
        def core=field(field(field(resources,'confirmationRoutes'),'service'),'store')
        def proofs=field(core,'proofs')
        expect:
        field(core,'intents').is(intents);field(core,'database').is(database);field(lookupStore,'database').is(database)
        field(field(resources,'cleanup'),'store').is(intents)
        field(intents,'keys').is(configuration.stageConfiguration().retentionKeys())
        field(lookupStore,'keys').is(configuration.stageConfiguration().retentionKeys())
        field(intents,'crypto').is(configuration.stageConfiguration().detailCrypto())
        Arrays.equals(field(proofs,'retentionKey') as byte[],configuration.proofRetentionKey())
        when:proofs.write(WRITER,input)
        then:def unavailable=thrown(L.Failure);unavailable.error()==L.Error.UNAVAILABLE
        count('sso_login_proofs')==0;count('sso_login_events')==0;count('sessions')==0
        when:new PostgresAuthStore(database,false).requireBankingAccess(row('identities').id)
        then:thrown(AuthFailure)
    }
    def 'one maintenance owner starts once and idempotent close leaves borrowed database usable'(){
        given:def worker=field(resources,'cleanup');def executor=field(worker,'executor');def observer=field(resources,'observer')
        when:resources.start()
        then:field(worker,'executor').is(executor)
        when:resources.close();resources.close()
        then:field(worker,'executor')==null;executor.awaitTermination(3,TimeUnit.SECONDS);field(observer,'closed')
        database.ready();database.transaction{it.fetchOne('select 1').get(0,Integer)==1}
        when:resources.start()
        then:thrown(IllegalStateException)
    }
    def 'real HTTP bind failure owner cleanup closes observer without closing borrowed database'(){
        given:
        app.stop();resources.close()
        resources=CodeConfirmationResources.open(database,configuration).orElseThrow()
        def observer=field(resources,'observer');def occupied=new ServerSocket(0,0,InetAddress.loopbackAddress)
        when:
        try{app=io.javalin.Javalin.create{resources.configure(it)};app.start('127.0.0.1',occupied.localPort)}finally{resources.close()}
        then:thrown(RuntimeException);field(observer,'closed');field(field(resources,'cleanup'),'executor')==null;database.ready()
        cleanup:occupied?.close()
    }
    def 'closed observer cannot fan out or manufacture proof while borrowed stores retain identity'(){
        given:eligible();def input=command();assert post('stage',write(input)).statusCode()==200;def before=row('verification_authorities');resources.close()
        when:def response=post('confirm',confirm(input))
        then:response.statusCode()==503;row('verification_authorities')==before
        count('sso_code_confirmation_anchors')==1;count('sso_code_confirmation_results')==0;count('sso_login_proofs')==0;count('sessions')==0
        database.ready()
    }
}
