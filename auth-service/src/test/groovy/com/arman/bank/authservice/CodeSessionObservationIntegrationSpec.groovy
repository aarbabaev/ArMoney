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

/** Synthetic exact HTTP observer to real confirmation service/PG. Not actual Keycloak propagation or activation evidence. */
class CodeSessionObservationIntegrationSpec extends Specification {
    static final String WRITER='fixture-auth'
    static final String ISSUER='https://issuer.example/realm'
    static final String PHONE='+971501234567'
    static final String EMAIL='fixture@example.test'
    boolean grantsEnabled=true
    com.sun.net.httpserver.HttpServer peer
    ExecutorService peerExecutor=Executors.newCachedThreadPool()
    HttpCodeSessionObservations observer
    def peerStatus=new java.util.concurrent.atomic.AtomicInteger(200)
    def peerCalls=new java.util.concurrent.atomic.AtomicInteger()
    def changes=new java.util.concurrent.atomic.AtomicReference<Map>([:])
    def entered=new CountDownLatch(1)
    def releaseResponse=new CountDownLatch(1)
    volatile boolean holdResponse=false
    static final String STATUS_KEY='synthetic-code-session-observer-integration-key'
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
        peer=com.sun.net.httpserver.HttpServer.create(new InetSocketAddress('127.0.0.1',0),0);peer.executor=peerExecutor
        peer.createContext('/',{exchange->
            peerCalls.incrementAndGet()
            def binding=new groovy.json.JsonSlurper().parse(exchange.requestBody)
            entered.countDown()
            try{
                if(holdResponse)assert releaseResponse.await(5,TimeUnit.SECONDS)
                def fields=HttpCodeSessionObservationsSpec.facts()+binding+changes.get()
                byte[] bytes=groovy.json.JsonOutput.toJson(fields).getBytes(java.nio.charset.StandardCharsets.UTF_8)
                int code=exchange.requestURI.path==HttpCodeSessionObservationsSpec.PATH&&exchange.requestHeaders.getFirst('X-ArMoney-Auth-Status-Key')==STATUS_KEY?peerStatus.get():403
                exchange.responseHeaders.set('Content-Type','application/json');exchange.sendResponseHeaders(code,bytes.length);exchange.responseBody.write(bytes)
            }catch(IOException cancelled){}catch(InterruptedException interrupted){Thread.currentThread().interrupt()}
            finally{exchange.close()}
        } as com.sun.net.httpserver.HttpHandler);peer.start()
        def scopes=new CodeIntentPairScopes('[{"issuer":"'+ISSUER+'","client_id":"armoney-web"}]','[]')
        observer=new HttpCodeSessionObservations(URI.create('http://127.0.0.1:'+peer.address.port),STATUS_KEY,scopes,
            HttpCodeSessionObservations.privateClient())
    }
    def cleanup(){releaseResponse.countDown();observer?.close();peer?.stop(0);peerExecutor.shutdownNow();database?.close();postgres?.stop()}
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
    CodeConfirmationService realObserverService(){new CodeConfirmationService(confirmations(stageStore()),observer)}
    boolean noPositive(){['sso_login_proofs','sso_login_events','sso_code_confirmation_results','sso_code_confirmation_anchor_completions','sessions'].every{count(it)==0}}
    def 'real HTTP exact current negative #flag commits suspension once through the actual owning transaction'(){
        given:eligible();def input=command();staged(input);changes.set([(flag):false]);long epoch=row('verification_authorities').verification_epoch
        when:def result=realObserverService().confirm(WRITER,lookup(input))
        then:result.deniedCommitted();row('verification_authorities').state=='SUSPENDED';row('verification_authorities').verification_epoch==epoch+1
        peerCalls.get()==1;noPositive();count('sso_code_confirmation_anchors')==1
        when:realObserverService().confirm(WRITER,lookup(input))
        then:thrown(L.Failure);row('verification_authorities').verification_epoch==epoch+1;peerCalls.get()==1
        where:flag << ['enabled','user_session_active','client_session_active']
    }
    def 'observer HTTP #status with negative-looking body cannot invalidate authority or create proof'(){
        given:eligible();def input=command();staged(input);peerStatus.set(status);changes.set([enabled:false]);def before=row('verification_authorities')
        when:realObserverService().confirm(WRITER,lookup(input))
        then:def unavailable=thrown(L.Failure);unavailable.error()==L.Error.UNAVAILABLE;row('verification_authorities')==before;peerCalls.get()==1;noPositive()
        count('sso_code_confirmation_anchors')==1
        where:status << [401,403,404,429,500,503]
    }
    def 'malformed old or foreign negative200 cannot enter owning invalidation'(){
        given:eligible();def input=command();staged(input);changes.set(mutation);def before=row('verification_authorities')
        when:realObserverService().confirm(WRITER,lookup(input))
        then:def unavailable=thrown(L.Failure);unavailable.error()==L.Error.UNAVAILABLE;row('verification_authorities')==before;noPositive()
        where:mutation << [[enabled:'false'],[enabled:false,user_session_id:'foreign'],[enabled:false,observed_at:null],
            [enabled:false,valid_until:'2030-01-01T00:00:01Z'],[enabled:false,email:'UPPER@example.test']]
    }
    def 'exact positive HTTP facts still require current owned receipts grant and matching projection'(){
        given:eligible();def input=command();staged(input)
        when:def result=realObserverService().confirm(WRITER,lookup(input))
        then:!result.deniedCommitted();result.snapshot()!=null;peerCalls.get()==1
        count('sso_login_proofs')==1;count('sso_login_events')==1;count('sessions')==0;row('verification_authorities').state=='PROJECTED'
        when:new PostgresAuthStore(database,false).requireBankingAccess(row('identities').id)
        then:thrown(AuthFailure)
    }
    def 'negative200 stale behind real control lock wait cannot invalidate at DB receive or final work'(){
        given:
        eligible();def input=command();staged(input);holdResponse=true;changes.set([enabled:false,valid_until:'2030-01-01T00:00:02Z'])
        def before=row('verification_authorities');def executor=Executors.newSingleThreadExecutor()
        def pending=executor.submit({try{realObserverService().confirm(WRITER,lookup(input));null}catch(L.Failure failure){failure.error()}} as Callable)
        assert entered.await(3,TimeUnit.SECONDS)
        def blocker=java.sql.DriverManager.getConnection(postgres.jdbcUrl,postgres.username,postgres.password);blocker.autoCommit=false
        def statement=blocker.createStatement()
        try{def locked=statement.executeQuery('select id from verification_control where id=1 for update');try{assert locked.next()}finally{locked.close()}}finally{statement.close()}
        when:
        releaseResponse.countDown()
        long limit=System.nanoTime()+TimeUnit.SECONDS.toNanos(2)
        while(!database.transaction{sql->sql.fetchOne("select exists(select 1 from pg_stat_activity where datname=current_database() and wait_event='transactionid' and query like '%verification_control%')").get(0,Boolean)}){
            assert System.nanoTime()<limit:'actual control lock waiter was not observed';Thread.sleep(10)
        }
        Thread.sleep(1200);blocker.commit();def result=pending.get(5,TimeUnit.SECONDS)
        then:result==L.Error.UNAVAILABLE;row('verification_authorities')==before;peerCalls.get()==1;noPositive()
        cleanup:releaseResponse.countDown();blocker?.close();executor.shutdownNow()
    }
}
