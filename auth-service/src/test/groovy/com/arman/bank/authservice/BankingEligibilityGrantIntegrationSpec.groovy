package com.arman.bank.authservice.infrastructure

import com.arman.bank.authservice.application.*
import com.arman.bank.authservice.domain.IdentityVerification.*
import com.arman.bank.authservice.domain.Verification
import com.arman.bank.authservice.domain.Verification.Purpose
import com.arman.bank.authservice.domain.VerificationProjection as P
import com.arman.bank.authservice.infrastructure.*
import com.arman.bank.runtime.Database
import org.testcontainers.containers.PostgreSQLContainer
import spock.lang.Specification
import java.time.*
import java.util.concurrent.*

/** Synthetic provider observations and disposable PostgreSQL only; no bank grant or remote sends. */
class BankingEligibilityGrantIntegrationSpec extends Specification {
    static final String WRITER='fixture-auth'
    static final String ISSUER='https://issuer.example/realm'
    static final String PHONE='+971501234567'
    static final String EMAIL='fixture@example.test'
    boolean grantsEnabled=true
    com.sun.net.httpserver.HttpServer httpServer
    HttpVerificationObservations httpTransport
    java.util.concurrent.ExecutorService httpExecutor
    boolean providerEnabled=true, providerActive=true
    def httpRequests=new java.util.concurrent.CopyOnWriteArrayList()
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
    }
    def cleanup(){httpTransport?.close();httpServer?.stop(0);httpExecutor?.shutdownNow();database?.close();postgres?.stop()}
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
    def startHttp(){
        httpServer=com.sun.net.httpserver.HttpServer.create(new InetSocketAddress('127.0.0.1',0),0)
        httpExecutor=Executors.newFixedThreadPool(2);httpServer.executor=httpExecutor
        httpServer.createContext('/sso/realms/armoney/armoney-status/enrollment'){exchange->
            try{
                assert exchange.requestHeaders.getFirst('X-ArMoney-Auth-Status-Key')=='synthetic-status-credential-distinct'
                def body=new groovy.json.JsonSlurper().parse(exchange.requestBody)
                assert body.keySet()==['issuer','subject','client_id','provider_root_session_id','provider_tab_id','authentication_session_id'] as Set
                httpRequests.add(body)
                def result=body+[username:PHONE,email:EMAIL,phone_number:PHONE,enabled:providerEnabled,
                    authentication_session_active:providerActive,authentication_session_expires_at:Instant.now().plusSeconds(600).toString()]
                byte[] bytes=groovy.json.JsonOutput.toJson(result).getBytes(java.nio.charset.StandardCharsets.UTF_8)
                exchange.responseHeaders.set('Content-Type','application/json')
                exchange.sendResponseHeaders(200,bytes.length);exchange.responseBody.write(bytes)
            }finally{exchange.close()}
        }
        httpServer.start()
        def client=java.net.http.HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).followRedirects(java.net.http.HttpClient.Redirect.NEVER).build()
        httpTransport=new HttpVerificationObservations(URI.create('http://127.0.0.1:'+httpServer.address.port),'synthetic-status-credential-distinct',policy(),client)
        new IdentityVerificationService(authority,httpTransport,policy())
    }
    def 'zero or one current contact receipt never records a grant'(){
        given:
        def enrolled=enroll()
        if(purpose!=null)consume(open(enrolled.enrollmentId(),purpose))
        when:
        status(enrolled.enrollmentId())
        then:
        count('banking_eligibility_grants')==0
        count('identities')==0
        count('sessions')==0
        count('sso_login_events')==0
        where:
        purpose << [null,Purpose.REGISTRATION_EMAIL,Purpose.REGISTRATION_PHONE]
    }
    def 'default-off records no grant even after exact receipt-backed projection ACK'(){
        given:
        grantsEnabled=false;wire()
        def enrolled=projected()
        when:
        def response=status(enrolled.enrollmentId())
        then:
        response.state()=='PROJECTED'
        count('banking_eligibility_grants')==0
    }
    def 'fresh status records one immutable identity epoch and repeated concurrent status preserves exact expiry and epoch'(){
        given:
        def enrolled=projected()
        def before=row('verification_authorities')
        when:
        def first=status(enrolled.enrollmentId())
        def frozen=grants()
        def pool=Executors.newFixedThreadPool(2)
        def results=(1..2).collect{pool.submit({status(enrolled.enrollmentId())} as Callable)}.collect{it.get(10,TimeUnit.SECONDS)}
        then:
        first.state()=='PROJECTED'
        results.every{it.state()=='PROJECTED'}
        count('banking_eligibility_grants')==1
        grants()==frozen
        row('verification_authorities').verification_epoch==before.verification_epoch
        row('banking_eligibility_grants').projection_operation_id==before.projection_operation_id
        row('banking_eligibility_grants').expires_at<=row('verification_enrollments').expires_at
        count('sso_login_proofs')==0
        count('sessions')==0
        when:
        new PostgresAuthStore(database,flag).saveSession('a'*64,row('identities').id,Instant.now(),Instant.now().plusSeconds(30))
        then:
        thrown(AuthFailure)
        count('sessions')==0
        cleanup:
        pool?.shutdownNow()
        where:
        flag << [false,true]
    }
    def 'each mismatched persisted ACK binding prevents initial grant'(){
        given:
        def enrolled=projected()
        if(field=='identity'){
            UUID other=UUID.randomUUID();execute('insert into identities(id) values (?)',other)
            execute('update auth_verification_projection_commands set identity_id=?',other)
        }else execute(mutation)
        when:
        status(enrolled.enrollmentId())
        then:
        def failure=thrown(Verification.Failure)
        failure.error()==Verification.Error.CONFLICT
        count('banking_eligibility_grants')==0
        where:
        field       | mutation
        'operation' | 'update verification_authorities set projection_operation_id=gen_random_uuid()'
        'identity'  | null
        'revision'  | 'update auth_verification_projection_tombstones set version=version+1'
        'phone'     | 'update auth_verification_projection_tombstones set phone_contact_version=phone_contact_version+1'
        'epoch'     | 'update auth_verification_projection_tombstones set verification_epoch=verification_epoch+1'
        'hash'      | "update auth_verification_projection_tombstones set payload_hash=repeat('f',64)"
    }
    def 'contact or association invalidation commits suspension once even after grant creation flag is disabled'(){
        given:
        def enrolled=projected();status(enrolled.enrollmentId())
        def epoch=row('verification_authorities').verification_epoch
        def frozen=grants()
        grantsEnabled=false;wire()
        if(association){
            UUID other=UUID.randomUUID();execute('insert into identities(id) values (?)',other)
            execute('update external_identities set identity_id=?',other)
        }
        when:
        status(enrolled.enrollmentId(),association?EMAIL:'changed@example.test')
        then:
        def failure=thrown(Verification.Failure)
        failure.error()==Verification.Error.CONFLICT
        row('verification_authorities').state=='SUSPENDED'
        row('verification_authorities').verification_epoch==epoch+1
        grants()==frozen
        when:
        status(enrolled.enrollmentId(),association?EMAIL:'changed@example.test')
        then:
        thrown(Verification.Failure)
        row('verification_authorities').verification_epoch==epoch+1
        grants()==frozen
        where:
        association << [false,true]
    }
    def 'real HTTP status negatives suspend existing evidence but positive-only issue path still rejects before store'(){
        given:
        def enrolled=projected()
        def service=startHttp()
        assert service.status(WRITER,enrolled.enrollmentId(),flow).state()=='PROJECTED'
        def epoch=row('verification_authorities').verification_epoch
        def frozen=grants()
        if(disabled)providerEnabled=false;else providerActive=false
        when:
        service.issue(WRITER,new Issue(UUID.randomUUID(),enrolled.enrollmentId(),flow,Purpose.REGISTRATION_PHONE,1))
        then:
        def positiveFailure=thrown(Verification.Failure)
        positiveFailure.error()==Verification.Error.UNAVAILABLE
        row('verification_authorities').state=='PROJECTED'
        row('verification_authorities').verification_epoch==epoch
        when:
        service.status(WRITER,enrolled.enrollmentId(),flow)
        then:
        def negativeFailure=thrown(Verification.Failure)
        negativeFailure.error()==Verification.Error.CONFLICT
        row('verification_authorities').state=='SUSPENDED'
        row('verification_authorities').verification_epoch==epoch+1
        grants()==frozen
        when:
        service.status(WRITER,enrolled.enrollmentId(),flow)
        then:
        thrown(Verification.Failure)
        row('verification_authorities').verification_epoch==epoch+1
        httpRequests.size()==4
        grants()==frozen
        count('sessions')==0
        count('sso_login_events')==0
        where:
        disabled << [false,true]
    }
    def 'failure inserting evidence rolls back grant and exact status retry records original epoch without projection allocation'(){
        given:
        def enrolled=projected()
        def before=database.transaction{it.fetchOne('select to_jsonb(t)::text from verification_authorities t').get(0,String)}
        execute("create function fixture_fail_grant() returns trigger language plpgsql as 'begin raise exception ''synthetic grant fault''; end;'")
        execute('create trigger fixture_grant_fault before insert on banking_eligibility_grants for each row execute function fixture_fail_grant()')
        when:
        status(enrolled.enrollmentId())
        then:
        def fault=thrown(org.jooq.exception.DataAccessException)
        fault.sqlState()=='P0001'
        count('banking_eligibility_grants')==0
        database.transaction{it.fetchOne('select to_jsonb(t)::text from verification_authorities t').get(0,String)}==before
        when:
        execute('drop trigger fixture_grant_fault on banking_eligibility_grants')
        status(enrolled.enrollmentId())
        then:
        count('banking_eligibility_grants')==1
        database.transaction{it.fetchOne('select to_jsonb(t)::text from verification_authorities t').get(0,String)}==before
        count('registration_completion_intents')==1
        count('auth_verification_projection_commands')==1
    }
    def 'deadline passing during grant insert rolls back rather than returning expired evidence'(){
        given:
        def enrolled=projected()
        if(deadline=='enrollment'){
            execute("update verification_enrollments set created_at=statement_timestamp()-interval '24 hours'+interval '1 second',expires_at=statement_timestamp()+interval '1 second'")
        }else{
            execute("update "+deadline+" set created_at=statement_timestamp()-interval '604800 seconds'+interval '1 second',expires_at=statement_timestamp()+interval '1 second'")
        }
        execute("create function fixture_slow_grant() returns trigger language plpgsql as 'begin perform pg_sleep(1.2); return new; end;'")
        execute('create trigger fixture_grant_slow before insert on banking_eligibility_grants for each row execute function fixture_slow_grant()')
        when:
        status(enrolled.enrollmentId())
        then:
        def failure=thrown(Verification.Failure)
        failure.error()==Verification.Error.UNAVAILABLE
        count('banking_eligibility_grants')==0
        where:
        deadline << ['enrollment','auth_verification_projection_commands','auth_verification_projection_tombstones']
    }
    def 'local record outliving its original flow cannot authorize expired-flow status or create more records'(){
        given:
        def enrolled=projected();status(enrolled.enrollmentId())
        def frozen=grants()
        execute("update verification_flows set expires_at=clock_timestamp()-interval '1 millisecond'")
        when:
        status(enrolled.enrollmentId())
        then:
        def failure=thrown(Verification.Failure)
        failure.error()==Verification.Error.BINDING
        grants()==frozen
        count('sessions')==0
    }
    def 'grant rows reject ordinary no-op update field update and delete'(){
        given:
        def enrolled=projected();status(enrolled.enrollmentId())
        def frozen=grants()
        when:
        execute(mutation)
        then:
        def immutable=thrown(org.jooq.exception.DataAccessException)
        immutable.sqlState()=='23514'
        grants()==frozen
        where:
        mutation << ['delete from banking_eligibility_grants','update banking_eligibility_grants set identity_id=identity_id',
            "update banking_eligibility_grants set expires_at=expires_at-interval '1 second'"]
    }
    def 'observation becoming stale behind an actual PostgreSQL control lock cannot mint evidence'(){
        given:
        def enrolled=projected()
        def started=authority.admit(WRITER,flow)
        def observation=new Observation(flow,PHONE,EMAIL,PHONE,true,true,started.plusSeconds(600),started)
        def blocker=java.sql.DriverManager.getConnection(postgres.jdbcUrl,postgres.username,postgres.password)
        blocker.autoCommit=false
        blocker.createStatement().execute('select id from verification_control where id=1 for update')
        def executor=Executors.newSingleThreadExecutor()
        def attempted=executor.submit({ ->
            try{authority.status(WRITER,enrolled.enrollmentId(),flow,observation,started);return null}
            catch(Verification.Failure failure){return failure.error()}
        } as Callable)
        boolean waiting=false
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(4)
        while(!waiting && System.nanoTime()<until){
            waiting=database.transaction{it.fetchOne("select exists(select 1 from pg_stat_activity where datname=current_database() and pid<>pg_backend_pid() and wait_event_type='Lock' and query like '%verification_control%')").get(0,Boolean)}
            if(!waiting)Thread.sleep(20)
        }
        assert waiting
        Thread.sleep(5100)
        when:
        blocker.rollback()
        def outcome=attempted.get(10,TimeUnit.SECONDS)
        then:
        outcome==Verification.Error.UNAVAILABLE
        count('banking_eligibility_grants')==0
        row('verification_authorities').state=='PROJECTED'
        cleanup:
        blocker?.rollback();blocker?.close();executor?.shutdownNow()
    }
    def 'expired persisted evidence cannot be renewed or rematerialized by another status request'(){
        given:
        def enrolled=projected()
        execute("update auth_verification_projection_commands set created_at=statement_timestamp()-interval '604800 seconds'+interval '2 seconds',expires_at=statement_timestamp()+interval '2 seconds'")
        status(enrolled.enrollmentId())
        def frozen=grants()
        def epoch=row('verification_authorities').verification_epoch
        Thread.sleep(2100)
        when:
        status(enrolled.enrollmentId())
        then:
        def failure=thrown(Verification.Failure)
        failure.error()==Verification.Error.UNAVAILABLE
        grants()==frozen
        row('verification_authorities').verification_epoch==epoch
        count('registration_completion_intents')==1
        count('auth_verification_projection_commands')==1
    }
    def 'flow expiring during grant insert rolls back all evidence'(){
        given:
        def enrolled=projected()
        execute("update verification_flows set expires_at=statement_timestamp()+interval '1 second'")
        execute("create function fixture_slow_flow_grant() returns trigger language plpgsql as 'begin perform pg_sleep(1.2); return new; end;'")
        execute('create trigger fixture_slow_flow before insert on banking_eligibility_grants for each row execute function fixture_slow_flow_grant()')
        when:
        status(enrolled.enrollmentId())
        then:
        def failure=thrown(Verification.Failure)
        failure.error()==Verification.Error.UNAVAILABLE
        count('banking_eligibility_grants')==0
    }
    def 'grant adapter cannot record through a caller transaction lacking original authorized flow capability'(){
        given:
        def enrolled=projected()
        def started=authority.admit(WRITER,flow)
        def observation=new Observation(flow,PHONE,EMAIL,PHONE,true,true,started.plusSeconds(600),started)
        when:
        database.transaction{sql->
            PostgresVerificationProjectionStore.lockControls(sql)
            new PostgresBankingEligibilityStore(sql).record(enrolled.enrollmentId(),flow,observation,started)
        }
        then:
        def failure=thrown(Verification.Failure)
        failure.error()==Verification.Error.UNAVAILABLE
        count('banking_eligibility_grants')==0
    }
}
