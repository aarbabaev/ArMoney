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

/** Staged trusted observations and real disposable PostgreSQL. No actual provider visibility, banking session or remote sends. */
class LoginProofEligibilityIntegrationSpec extends Specification {
    static final String WRITER='fixture-auth'
    static final String ISSUER='https://issuer.example/realm'
    static final String PHONE='+971501234567'
    static final String EMAIL='fixture@example.test'
    boolean grantsEnabled=true
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
    def cleanup(){database?.close();postgres?.stop()}
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
    L.Observation facts(L.Binding binding,Instant anchor){new L.Observation(binding,PHONE,EMAIL,PHONE,true,true,anchor.plusSeconds(600),anchor)}
    LoginProofService service(PostgresLoginProofStore store){new LoginProofService(store,{binding,anchor->facts(binding,anchor)} as LoginProofObservations)}
    def eligible(){def enrollment=projected();status(enrollment.enrollmentId());enrollment}
    def snapshots(){database.transaction{sql->['sso_login_proofs','sso_login_events','sso_login_proof_commands'].collectEntries{table->
        [(table):sql.fetch('select to_jsonb(t)::text as snapshot from '+table+' t order by 1').getValues('snapshot',String)]}}}
    static L.Resolve lookup(L.Write input){new L.Resolve(input.codeDigest(),input.codeKeyId(),input.issuer(),input.subject(),input.clientId(),input.userSessionId())}
    static L.Write change(L.Write input,String field){
        new L.Write(input.commandId(),input.codeDigest(),input.codeKeyId(),input.issuer(),input.subject(),field=='client'?'armoney-ios':input.clientId(),
            field=='session'?'foreign-session':input.userSessionId(),input.authenticationSessionId(),input.issuedAt(),input.expiresAt(),input.kind(),input.eventId(),input.passwordAuthenticatedAt())
    }
    def 'staged interactive CREATE and lost-ACK replay consume actual receipt-backed grant without bank session or ACTIVE'(){
        given:
        eligible();def store=proofs();def usecase=service(store);def input=command()
        when:
        def first=usecase.write(WRITER,input);def frozen=snapshots();def retry=usecase.write(WRITER,input)
        then:
        retry==first
        snapshots()==frozen
        first.eventId()==input.eventId()
        first.expiresAt()==input.expiresAt()
        count('sso_login_proofs')==1
        count('sso_login_events')==1
        row('sso_login_events').verification_epoch==row('banking_eligibility_grants').verification_epoch
        count('sessions')==0
        row('verification_authorities').state=='PROJECTED'
        when:
        new PostgresAuthStore(database,flag).requireBankingAccess(row('identities').id)
        then:
        thrown(AuthFailure)
        where:
        flag << [false,true]
    }
    def 'changed command client or session conflicts without mutating persisted proof or event'(){
        given:
        eligible();def usecase=service(proofs());def input=command();usecase.write(WRITER,input);def frozen=snapshots()
        when:
        usecase.write(WRITER,change(input,field))
        then:
        def failure=thrown(L.Failure)
        failure.error()==L.Error.CONFLICT
        snapshots()==frozen
        where:
        field << ['client','session']
    }
    def 'observation remains outside transactions and cannot change original admission binding or anchor'(){
        given:
        eligible();def store=proofs();def input=command();def admission=store.admitCreate(WRITER,input)
        def valid=facts(input.binding(),admission.startedAt())
        def observed=field=='anchor'?new L.Observation(valid.binding(),PHONE,EMAIL,PHONE,true,true,valid.sessionDeadline(),admission.startedAt().plusNanos(1)):
            new L.Observation(new L.Binding(ISSUER,flow.subject(),'armoney-web','foreign-session'),PHONE,EMAIL,PHONE,true,true,valid.sessionDeadline(),admission.startedAt())
        when:
        store.writeObserved(WRITER,input,admission,observed)
        then:
        def failure=thrown(L.Failure)
        failure.error()==L.Error.UNAVAILABLE
        snapshots().values().every{it.empty}
        where:
        field << ['anchor','binding']
    }
    def 'zero or one current receipt and missing grant cannot be consumed as proof authority'(){
        given:
        if(purpose=='grant')projected()
        else {def e=enroll();if(purpose!=null)consume(open(e.enrollmentId(),purpose))}
        when:
        service(proofs()).write(WRITER,command())
        then:
        def failure=thrown(L.Failure)
        failure.error()==L.Error.CONFLICT
        snapshots().values().every{it.empty}
        where:
        purpose << [null,Purpose.REGISTRATION_EMAIL,Purpose.REGISTRATION_PHONE,'grant']
    }
    def 'mutating current receipt epoch or projection ACK inside proof insert is caught by final reread and rolled back'(){
        given:
        eligible();def usecase=service(proofs());def frozen=snapshots()
        execute("create function fixture_change_evidence() returns trigger language plpgsql as 'begin "+mutation+"; return new; end;'")
        execute('create trigger fixture_change before insert on sso_login_proofs for each row execute function fixture_change_evidence()')
        def epoch=row('verification_authorities').verification_epoch
        when:
        usecase.write(WRITER,command())
        then:
        def failure=thrown(L.Failure)
        failure.error()==L.Error.CONFLICT
        snapshots()==frozen
        row('verification_authorities').verification_epoch==epoch
        count('verification_receipts')==2
        where:
        mutation << ["delete from verification_receipts where purpose=''REGISTRATION_EMAIL''",
            'update verification_authorities set verification_epoch=verification_epoch+1',
            "update auth_verification_projection_tombstones set payload_hash=repeat(''f'',64)"]
    }
    def 'effective authority or provider deadline crossing during insert cannot return a proof'(){
        given:
        if(deadline=='authority'){
            def enrollment=projected()
            execute("update verification_authorities set created_at=statement_timestamp()-interval '24 hours'+interval '1 second',expires_at=statement_timestamp()+interval '1 second'")
            status(enrollment.enrollmentId())
        }else eligible()
        def store=proofs()
        def usecase=new LoginProofService(store,{binding,anchor->new L.Observation(binding,PHONE,EMAIL,PHONE,true,true,anchor.plusSeconds(deadline=='provider'?1:600),anchor)} as LoginProofObservations)
        execute("create function fixture_expire_evidence() returns trigger language plpgsql as 'begin perform pg_sleep(1.2); return new; end;'")
        execute('create trigger fixture_expire before insert on sso_login_proofs for each row execute function fixture_expire_evidence()')
        when:
        usecase.write(WRITER,command())
        then:
        def failure=thrown(L.Failure)
        failure.error()==L.Error.UNAVAILABLE
        snapshots().values().every{it.empty}
        where:
        deadline << ['authority','provider']
    }
    def 'fault after event and proof inserts rolls back all CREATE records and exact retry uses original event and expiry'(){
        given:
        eligible();def usecase=service(proofs());def input=command()
        execute("create function fixture_fail_command() returns trigger language plpgsql as 'begin raise exception ''synthetic command fault''; end;'")
        execute('create trigger fixture_fail before insert on sso_login_proof_commands for each row execute function fixture_fail_command()')
        when:
        usecase.write(WRITER,input)
        then:
        def failure=thrown(org.jooq.exception.DataAccessException)
        failure.sqlState()=='P0001'
        snapshots().values().every{it.empty}
        when:
        execute('drop trigger fixture_fail on sso_login_proof_commands')
        def result=usecase.write(WRITER,input)
        then:
        result.eventId()==input.eventId()
        result.expiresAt()==input.expiresAt()
        count('sso_login_proofs')==1
        count('sso_login_events')==1
    }
    def 'historical resolve remains immutable lookup without provider fanout after eligibility suspension'(){
        given:
        eligible();def store=proofs();def input=command();def result=service(store).write(WRITER,input);def frozen=snapshots()
        execute("update verification_authorities set state='SUSPENDED',verification_epoch=verification_epoch+1")
        when:
        def historical=store.resolve(WRITER,lookup(input))
        then:
        historical==result
        snapshots()==frozen
        when:
        service(store).write(WRITER,input)
        then:
        def failure=thrown(L.Failure)
        failure.error()==L.Error.CONFLICT
        snapshots()==frozen
    }
    def 'actual PostgreSQL control wait makes admitted observation stale and rolls back CREATE'(){
        given:
        eligible();def store=proofs();def input=command();def admission=store.admitCreate(WRITER,input);def observed=facts(input.binding(),admission.startedAt())
        def blocker=java.sql.DriverManager.getConnection(postgres.jdbcUrl,postgres.username,postgres.password)
        blocker.autoCommit=false;blocker.createStatement().execute('select id from verification_control where id=1 for update')
        def executor=Executors.newSingleThreadExecutor()
        def task=executor.submit({->try{store.writeObserved(WRITER,input,admission,observed);null}catch(L.Failure rejected){rejected.error()}} as Callable)
        boolean waiting=false;long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(4)
        while(!waiting&&System.nanoTime()<until){
            waiting=database.transaction{it.fetchOne("select exists(select 1 from pg_stat_activity where datname=current_database() and pid<>pg_backend_pid() and wait_event_type='Lock' and query like '%verification_control%')").get(0,Boolean)}
            if(!waiting)Thread.sleep(20)
        }
        assert waiting
        Thread.sleep(5100)
        when:
        blocker.rollback()
        def outcome=task.get(10,TimeUnit.SECONDS)
        then:
        outcome==L.Error.UNAVAILABLE
        snapshots().values().every{it.empty}
        cleanup:
        blocker?.rollback();blocker?.close();executor?.shutdownNow()
    }
    def 'every projection ACK binding receipt and association mismatch prevents staged CREATE'(){
        given:
        eligible()
        if(mutation in ['identity','association']){
            UUID other=UUID.randomUUID();execute('insert into identities(id) values (?)',other)
            if(mutation=='identity')execute('update auth_verification_projection_commands set identity_id=?',other)
            else execute('update external_identities set identity_id=?',other)
        }else execute(mutation)
        when:
        service(proofs()).write(WRITER,command())
        then:
        def failure=thrown(L.Failure)
        failure.error()==L.Error.CONFLICT
        snapshots().values().every{it.empty}
        where:
        mutation << ['identity','update verification_authorities set projection_operation_id=gen_random_uuid()',
            'update auth_verification_projection_tombstones set version=version+1',
            'update auth_verification_projection_tombstones set phone_contact_version=phone_contact_version+1',
            'update auth_verification_projection_tombstones set verification_epoch=verification_epoch+1',
            "update auth_verification_projection_tombstones set payload_hash=repeat('f',64)",
            "update auth_verification_projection_tombstones set recovery_barrier=true",
            "delete from verification_receipts where purpose='REGISTRATION_PHONE'",
            'association']
    }
    def 'changed provider contacts disabled user and inactive session never mint a proof or choose a recipient'(){
        given:
        eligible();def store=proofs()
        def usecase=new LoginProofService(store,{binding,anchor->new L.Observation(binding,PHONE,field=='email'?'foreign@example.test':EMAIL,PHONE,
            field!='disabled',field!='inactive',anchor.plusSeconds(600),anchor)} as LoginProofObservations)
        when:
        usecase.write(WRITER,command())
        then:
        def failure=thrown(L.Failure)
        failure.error()==L.Error.CONFLICT
        snapshots().values().every{it.empty}
        where:
        field << ['email','disabled','inactive']
    }
    def 'staged interactive capability rejects reuse and foreign store admissions without weakening legacy reuse'(){
        given:
        eligible();def store=proofs();def input=command();def admission=store.admitCreate(WRITER,input)
        when:
        proofs().writeObserved(WRITER,input,admission,facts(input.binding(),admission.startedAt()))
        then:
        def failure=thrown(L.Failure)
        failure.error()==L.Error.UNAVAILABLE
        snapshots().values().every{it.empty}
        when:
        def reuse=new L.Write(UUID.randomUUID(),input.codeDigest(),input.codeKeyId(),input.issuer(),input.subject(),input.clientId(),input.userSessionId(),
            input.authenticationSessionId(),input.issuedAt(),input.expiresAt(),L.Kind.REUSE,null,null)
        service(store).write(WRITER,reuse)
        then:
        def rejected=thrown(L.Failure)
        rejected.error()==L.Error.CONFLICT
        snapshots().values().every{it.empty}
    }
    def 'new-command code replay final reread rejects mutated evidence and preserves the original immutable proof'(){
        given:
        eligible();def usecase=service(proofs());def input=command();usecase.write(WRITER,input);def frozen=snapshots()
        execute("create function fixture_change_replay() returns trigger language plpgsql as 'begin update verification_authorities set verification_epoch=verification_epoch+1; return new; end;'")
        execute('create trigger fixture_change_replay before insert on sso_login_proof_commands for each row execute function fixture_change_replay()')
        def alias=new L.Write(UUID.randomUUID(),input.codeDigest(),input.codeKeyId(),input.issuer(),input.subject(),input.clientId(),input.userSessionId(),
            input.authenticationSessionId(),input.issuedAt(),input.expiresAt(),input.kind(),input.eventId(),input.passwordAuthenticatedAt())
        when:
        usecase.write(WRITER,alias)
        then:
        def failure=thrown(L.Failure)
        failure.error()==L.Error.CONFLICT
        snapshots()==frozen
        row('verification_authorities').verification_epoch==row('banking_eligibility_grants').verification_epoch
    }
}

