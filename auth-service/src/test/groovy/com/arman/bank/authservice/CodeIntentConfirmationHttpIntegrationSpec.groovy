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

/** Actual C2 HTTP/PG with explicitly synthetic trusted observations. No actual provider visibility, banking session or remote sends. */
class CodeIntentConfirmationHttpIntegrationSpec extends Specification {
    static final String WRITER='fixture-auth'
    static final String ISSUER='https://issuer.example/realm'
    static final String PHONE='+971501234567'
    static final String EMAIL='fixture@example.test'
    boolean grantsEnabled=true
    io.javalin.Javalin app
    java.net.http.HttpClient client=java.net.http.HttpClient.newHttpClient()
    boolean providerEnabled=true
    boolean observerUnavailable=false
    def observerCalls=new java.util.concurrent.atomic.AtomicInteger()
    static final String LOOKUP_KEY='synthetic-c2-lookup-directional-credential'
    static final String CONFIRM_KEY='synthetic-c2-confirm-directional-credential'
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
        def scopes=new CodeIntentPairScopes('[{"issuer":"'+ISSUER+'","client_id":"armoney-web"}]','[]')
        def lookup=new PostgresCodeIntentKeyLookupStore(database,cPolicy(),scopes,new CodeIntentRetentionKeys('retention-v1',['retention-v1':Base64.encoder.encodeToString(([61]*32) as byte[])]))
        def service=new CodeConfirmationService(confirmations(stageStore()),{binding,start->
            observerCalls.incrementAndGet()
            if(observerUnavailable)throw new L.Failure(L.Error.UNAVAILABLE)
            committedFacts(binding,providerEnabled)
        } as CodeConfirmationObservations)
        app=io.javalin.Javalin.create{config->
            new CodeIntentKeyLookupRoutes(lookup,cPolicy(),scopes,WRITER,LOOKUP_KEY).configure(config)
            new CodeConfirmationRoutes(service,cPolicy(),scopes,WRITER,CONFIRM_KEY).configure(config)
        }.start('127.0.0.1',0)
    }
    def cleanup(){app?.stop();client?.close();database?.close();postgres?.stop()}
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
    PostgresLoginProofStore proofs(){new PostgresLoginProofStore(database,
        L.Policy.defaults([ISSUER] as Set,['armoney-web','armoney-ios'] as Set,['fixture-v1'] as Set,[WRITER] as Set),([51]*32) as byte[],
        {sql -> {binding,now -> throw new L.Failure(L.Error.UNAVAILABLE)} as LoginProofStore.EligibilityPort })}
    L.Write command(){
        def now=Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS)
        new L.Write(UUID.randomUUID(),UUID.randomUUID().toString().replace('-','')*2,'fixture-v1',ISSUER,flow.subject(),'armoney-web','user-session',
            flow.authenticationSessionId(),now,now.plusSeconds(120),L.Kind.INTERACTIVE,UUID.randomUUID(),now)
    }
    def eligible(){def enrollment=projected();status(enrollment.enrollmentId());enrollment}
    def snapshots(){database.transaction{sql->['sso_login_proofs','sso_login_events','sso_login_proof_commands'].collectEntries{table->
        [(table):sql.fetch('select to_jsonb(t)::text as snapshot from '+table+' t order by 1').getValues('snapshot',String)]}}}
    static L.Resolve lookup(L.Write input){new L.Resolve(input.codeDigest(),input.codeKeyId(),input.issuer(),input.subject(),input.clientId(),input.userSessionId())}
    PostgresCodeIntentStore stageStore(){new PostgresCodeIntentStore(database,cPolicy(),new CodeIntentRetentionKeys('retention-v1',['retention-v1':Base64.encoder.encodeToString(([61]*32) as byte[])]),
        new CodeIntentDetailCrypto('aead-v1',['aead-v1':Base64.encoder.encodeToString(([62]*32) as byte[])],[] as Set))}
    L.Policy cPolicy(){L.Policy.defaults([ISSUER] as Set,['armoney-web','armoney-ios'] as Set,['fixture-v1'] as Set,[WRITER,'other-actor'] as Set)}
    PostgresCodeConfirmationStore confirmations(PostgresCodeIntentStore staging=stageStore()){new PostgresCodeConfirmationStore(database,staging,proofs(),cPolicy())}
    com.arman.bank.authservice.domain.CodeConfirmation.Facts committedFacts(L.Binding binding,boolean active=true){
        def observed=Instant.parse('2030-01-01T00:00:00Z') // Relative duration, never fabricated provider runtime evidence.
        new com.arman.bank.authservice.domain.CodeConfirmation.Facts(binding,PHONE,EMAIL,PHONE,active,true,true,observed,observed.plusSeconds(5))
    }
    CodeConfirmationService confirmer(PostgresCodeConfirmationStore store,Closure facts={binding,start->committedFacts(binding)}){
        new CodeConfirmationService(store,facts as CodeConfirmationObservations)
    }
    def staged(L.Write input){def staging=stageStore();staging.cleanup();staging.stage(WRITER,input);staging}
    void awaitReplayWindow(){
        long limit=System.nanoTime()+TimeUnit.SECONDS.toNanos(6)
        while(!database.transaction{sql->sql.fetchOne("select clock_timestamp()>=max(started_at)+interval '4 seconds' from sso_code_confirmation_anchors").get(0,Boolean)}){
            assert System.nanoTime()<limit:'original anchor window did not elapse within fixture bound'
            Thread.sleep(25)
        }
    }
    def confirmationRows(){database.transaction{sql->['sso_code_confirmation_anchors','sso_code_confirmation_results','sso_code_confirmation_anchor_completions'].collectEntries{table->
        [(table):sql.fetch('select to_jsonb(t)::text from '+table+' t order by 1').getValues(0,String)]}}}
    Map binding(L.Write input){[code_digest:input.codeDigest(),code_key_id:input.codeKeyId(),issuer:input.issuer(),subject:input.subject(),client_id:input.clientId(),user_session_id:input.userSessionId()]}
    Map selection(L.Write input){[issuer:input.issuer(),subject:input.subject(),client_id:input.clientId(),user_session_id:input.userSessionId(),candidates:[[code_digest:input.codeDigest(),code_key_id:input.codeKeyId()]]]}
    def post(String operation,Object body,String credential=null){
        String key=credential?: (operation=='key'?LOOKUP_KEY:CONFIRM_KEY)
        def request=java.net.http.HttpRequest.newBuilder(URI.create("http://127.0.0.1:${app.port()}/internal/sso-code-intents/${operation}"))
            .timeout(Duration.ofSeconds(8)).header('Content-Type','application/json').header(operation=='key'?'X-ArMoney-Code-Intent-Lookup-Key':'X-ArMoney-Login-Proof-Confirm-Key',key)
            .POST(java.net.http.HttpRequest.BodyPublishers.ofString(body instanceof String?body:groovy.json.JsonOutput.toJson(body))).build()
        client.send(request,java.net.http.HttpResponse.BodyHandlers.ofString())
    }
    def 'actual lookup then direct confirmation and fresh replay preserve original snapshot without a banking session'(){
        given:eligible();def input=command();staged(input)
        when:def selected=post('key',selection(input));def first=post('confirm',binding(input));def frozen=snapshots()
        then:selected.statusCode()==200;selected.body()==groovy.json.JsonOutput.toJson([code_key_id:input.codeKeyId()])
        first.statusCode()==200;new groovy.json.JsonSlurper().parseText(first.body()).keySet()==['proof_id','event_id','kind','login_notice_eligible','issued_at','expires_at'] as Set
        count('sso_login_creation_reservations')==1;count('sso_login_proofs')==1;count('sso_login_events')==1;count('sessions')==0
        when:awaitReplayWindow();def replay=post('confirm',binding(input))
        then:replay.statusCode()==200;replay.body()==first.body();snapshots()==frozen;observerCalls.get()==2
        when:new PostgresAuthStore(database,flag).requireBankingAccess(row('identities').id)
        then:thrown(AuthFailure)
        where:flag << [false,true]
    }
    def 'HTTP current negative commits suspension before generic409 and replay cannot advance epoch again'(){
        given:eligible();def input=command();staged(input);providerEnabled=false;long epoch=row('verification_authorities').verification_epoch
        when:def denied=post('confirm',binding(input));def repeated=post('confirm',binding(input))
        then:denied.statusCode()==409;repeated.statusCode()==409;denied.body()=='{"error":"code_intent_request_rejected"}'
        row('verification_authorities').state=='SUSPENDED';row('verification_authorities').verification_epoch==epoch+1
        observerCalls.get()==1;count('sso_login_proofs')==0;count('sso_login_events')==0;count('sessions')==0
        when:def key=post('key',selection(input))
        then:key.statusCode()==200 // Selection is metadata only and cannot substitute for confirmation.
    }
    def 'unavailable observation remains503 with durable FIRST but no authority mutation or historical fallback'(){
        given:eligible();def input=command();staged(input);observerUnavailable=true;def authorityBefore=row('verification_authorities')
        when:def response=post('confirm',binding(input))
        then:response.statusCode()==503;row('verification_authorities')==authorityBefore;observerCalls.get()==1
        count('sso_code_confirmation_anchors')==1;count('sso_code_confirmation_results')==0;count('sso_login_proofs')==0
    }
    def 'credential failures never admit while authenticated malformed body spends exactly one admission'(){
        expect:post(operation,'not-json','wrong').statusCode()==401;count('sso_login_proof_admission')==0
        when:def response=post(operation,'not-json')
        then:response.statusCode()==400;count('sso_login_proof_admission')==1;observerCalls.get()==0;count('sso_code_confirmation_anchors')==0
        database.transaction{sql->sql.fetchOne('select credits from sso_login_proof_admission').get(0,BigDecimal)==19}
        where:operation << ['key','confirm']
    }
    def 'all16 occupied global slots reject C2 before malformed body parsing without another admission'(){
        given:def held=[]
        try{
            (0..<16).each{slot->
                def connection=java.sql.DriverManager.getConnection(postgres.jdbcUrl,postgres.username,postgres.password);held.add(connection)
                def statement=connection.prepareStatement('select pg_try_advisory_lock(?,?)')
                try{statement.setInt(1,0x41524D4F);statement.setInt(2,slot);def rows=statement.executeQuery()
                    try{assert rows.next();assert rows.getBoolean(1)}finally{rows.close()}
                }finally{statement.close()}
            }
        }catch(Throwable failed){held.each{it.close()};throw failed}
        when:def response=post(operation,'not-json')
        then:response.statusCode()==429;count('sso_login_proof_admission')==0;observerCalls.get()==0
        count('sso_code_confirmation_anchors')==0;count('sso_login_proofs')==0;count('sessions')==0
        cleanup:held.each{it.close()}
        where:operation << ['key','confirm']
    }
    def 'confirmation request admission anchor proof and result use one physical backend and one charge'(){
        given:
        eligible();def input=command();staged(input)
        execute('create table qa_c2_backend(phase text,pid integer)')
        execute('''create function qa_c2_backend() returns trigger language plpgsql as $$begin insert into qa_c2_backend values(TG_TABLE_NAME,pg_backend_pid());return new;end $$''')
        execute('create trigger qa_c2_admission after update on sso_login_proof_admission for each row execute function qa_c2_backend()')
        ['sso_code_confirmation_anchors','sso_login_proofs','sso_code_confirmation_results'].each{table->execute('create trigger qa_c2_backend after insert on '+table+' for each row execute function qa_c2_backend()')}
        when:def response=post('confirm',binding(input))
        then:response.statusCode()==200;observerCalls.get()==1
        database.transaction{sql->
            assert sql.fetchOne('select count(distinct pid) from qa_c2_backend').get(0,Integer)==1
            assert sql.fetchOne('select count(*) from qa_c2_backend where phase=?','sso_login_proof_admission').get(0,Integer)==1
            assert sql.fetchOne('select count(distinct phase) from qa_c2_backend').get(0,Integer)==4
            true
        }
    }
}
