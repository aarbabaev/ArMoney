package com.arman.bank.authservice

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
class RegistrationAuthorityCompletionIntegrationSpec extends Specification {
    static final String WRITER='fixture-auth'
    static final String ISSUER='https://issuer.example/realm'
    static final String PHONE='+971501234567'
    static final String EMAIL='fixture@example.test'
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
        authority=new PostgresIdentityVerificationStore(database,crypto,([41]*32) as byte[],policy(),projections,WRITER)
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
    def 'second current receipt materializes exactly reserved UUID and durable projection but never a bank session'(){
        given:
        def enrolled=enroll()
        def pending=row('verification_authorities').pending_identity_id
        consume(open(enrolled.enrollmentId(),Purpose.REGISTRATION_EMAIL))
        expect:
        count('identities')==0
        count('managed_registration_principals')==0
        when:
        consume(open(enrolled.enrollmentId(),Purpose.REGISTRATION_PHONE))
        then:
        row('identities').id==pending
        row('identities').email==null
        row('identities').password_hash==null
        row('identities').registration_phone==null
        row('external_identities').identity_id==pending
        row('managed_registration_principals').identity_id==pending
        row('verification_authorities').state=='PROJECTION_PENDING'
        count('registration_completion_intents')==1
        count('auth_verification_projection_commands')==1
        count('sessions')==0
        count('sso_login_events')==0
        when:
        def claim=projections.claim(WRITER).orElseThrow()
        def frozen=claim.requestBody()
        assert authority.attach(WRITER,claim,ack(claim))
        then:
        row('verification_authorities').state=='PROJECTED'
        authority.attach(WRITER,claim,ack(claim))
        Arrays.equals(frozen,claim.requestBody())
        when:
        new PostgresAuthStore(database,flag).saveSession('a'*64,pending,Instant.now(),Instant.now().plusSeconds(60))
        then:
        def failure=thrown(AuthFailure)
        failure.kind()==AuthFailure.Kind.UNAUTHORIZED
        count('sessions')==0
        where:
        flag << [false,true]
    }
    def 'different command UUIDs racing prepare reuse business intent epoch operation and exact bytes'(){
        given:
        def enrolled=complete()
        def before=row('verification_authorities')
        byte[] bytes=row('auth_verification_projection_commands').request_body
        def executor=Executors.newFixedThreadPool(2)
        when:
        def futures=(1..2).collect{executor.submit({prepare(enrolled.enrollmentId())} as Callable)}
        def results=futures.collect{it.get(10,TimeUnit.SECONDS)}
        then:
        results.every{it.operationId()==before.projection_operation_id}
        row('verification_authorities').verification_epoch==before.verification_epoch
        row('verification_authorities').projection_revision==before.projection_revision
        Arrays.equals(bytes,row('auth_verification_projection_commands').request_body as byte[])
        count('identities')==1
        count('registration_completion_intents')==1
        cleanup:
        executor.shutdownNow()
    }
    def 'insert failure rolls back second receipt identity mapping marker epoch and intent together'(){
        given:
        def enrolled=enroll()
        consume(open(enrolled.enrollmentId(),Purpose.REGISTRATION_EMAIL))
        def second=open(enrolled.enrollmentId(),Purpose.REGISTRATION_PHONE)
        def prior=row('verification_authorities')
        def challengeBefore=database.transaction{it.fetch("select to_jsonb(t)::text as snapshot from verification_challenges t order by id").getValues('snapshot',String)}
        def receiptsBefore=database.transaction{it.fetch("select to_jsonb(t)::text as snapshot from verification_receipts t order by id").getValues('snapshot',String)}
        execute("create function fixture_fail() returns trigger language plpgsql as 'begin raise exception ''synthetic fault''; end;'")
        execute('create trigger fixture_fault before insert on '+faultTable+' for each row execute function fixture_fail()')
        when:
        consume(second)
        then:
        def injected=thrown(org.jooq.exception.DataAccessException)
        injected.sqlState()=='P0001'
        count('verification_receipts')==1
        count('identities')==0
        count('external_identities')==0
        count('managed_registration_principals')==0
        count('registration_completion_intents')==0
        count('auth_verification_projection_commands')==0
        row('verification_authorities')==prior
        database.transaction{it.fetch("select to_jsonb(t)::text as snapshot from verification_challenges t order by id").getValues('snapshot',String)}==challengeBefore
        database.transaction{it.fetch("select to_jsonb(t)::text as snapshot from verification_receipts t order by id").getValues('snapshot',String)}==receiptsBefore
        count('auth_verification_projection_tombstones')==0
        when:
        execute('drop trigger fixture_fault on '+faultTable)
        def retry=consume(second)
        then:
        retry.error()==null
        count('identities')==1
        count('registration_completion_intents')==1
        where:
        faultTable << ['identities','managed_registration_principals','external_identities','auth_verification_projection_commands','registration_completion_intents']
    }
    def 'contact replacement makes old ACK historical and later completion preserves unchanged phone receipt'(){
        given:
        def enrolled=complete()
        def claim=projections.claim(WRITER).orElseThrow()
        def old=claim.command()
        def phoneReceipt=database.transaction{it.fetchOne("select id from verification_receipts where purpose='REGISTRATION_PHONE'").get('id',UUID)}
        enroll('replacement@example.test')
        when:
        authority.attach(WRITER,claim,ack(claim))
        then:
        row('verification_authorities').state!='PROJECTED'
        row('auth_verification_projection_tombstones').state=='SUPERSEDED'
        when:
        execute("update verification_challenges set created_at=created_at-interval '61 seconds' where purpose='REGISTRATION_EMAIL'")
        consume(open(enrolled.enrollmentId(),Purpose.REGISTRATION_EMAIL,2,'replacement@example.test'),'replacement@example.test')
        then:
        count('identities')==1
        count('registration_completion_intents')==2
        row('verification_authorities').projection_revision==old.version()+1
        database.transaction{it.fetchOne("select id from verification_receipts where purpose='REGISTRATION_PHONE'").get('id',UUID)}==phoneReceipt
        database.transaction{it.fetchOne('select phone_contact_version from auth_verification_projection_commands order by version desc limit 1').get(0,Integer)}==old.phoneContactVersion()
    }
    def 'lost ACK restart reclaims identical operation bytes and attaches only current authority'(){
        given:
        complete()
        def first=projections.claim(WRITER).orElseThrow()
        execute("update auth_verification_projection_commands set lease_until=clock_timestamp()-interval '1 second'")
        database.close();database=new Database(postgres.jdbcUrl,postgres.username,postgres.password);wire()
        when:
        def retry=projections.claim(WRITER).orElseThrow()
        then:
        retry.command()==first.command()
        Arrays.equals(retry.requestBody(),first.requestBody())
        !authority.attach(WRITER,first,ack(first))
        authority.attach(WRITER,retry,ack(retry))
        row('verification_authorities').state=='PROJECTED'
    }
    def 'managed marker and business intent reject delete no-op update and field update and cleanup preserves them'(){
        given:
        complete()
        def before=database.transaction{it.fetchOne('select to_jsonb(t)::text as snapshot from '+table+' t').get('snapshot',String)}
        when:
        execute(mutation+' '+table+suffix)
        then:
        def immutable=thrown(org.jooq.exception.DataAccessException)
        immutable.sqlState()=='23514'
        database.transaction{it.fetchOne('select to_jsonb(t)::text as snapshot from '+table+' t').get('snapshot',String)}==before
        when:
        authority.cleanup();projections.cleanup()
        then:
        count('managed_registration_principals')==1
        count('registration_completion_intents')==1
        where:
        [table,mutation,suffix] << ['managed_registration_principals','registration_completion_intents'].collectMany{tableName->
            [[tableName,'delete from',''],[tableName,'update',' set identity_id=identity_id'],
             [tableName,'update'," set created_at=created_at+interval '1 second'"]]
        }
    }
    def 'permanent managed marker denies old digest session and profile SSO even with flags off and authority removed'(){
        given:
        complete()
        UUID identity=row('identities').id
        def token='synthetic-session'
        execute("insert into sessions(token_hash,identity_id,expires_at) values (?,?,clock_timestamp()+interval '1 hour')",AuthService.digest(token),identity)
        execute('delete from verification_authorities')
        def store=new PostgresAuthStore(database,false)
        when:
        store.findSession(AuthService.digest(token),Instant.now())
        then:
        def sessionFailure=thrown(AuthFailure)
        sessionFailure.kind()==AuthFailure.Kind.UNAUTHORIZED
        when:
        store.externalIdentity(new SsoTokens.Principal(ISSUER,flow.subject(),PHONE,EMAIL,true))
        then:
        thrown(AuthFailure)
        count('managed_registration_principals')==1
    }
    def 'contact conflict never links legacy owner by matching contact or silently verifies identity'(){
        given:
        UUID legacy=UUID.randomUUID()
        execute('insert into identities(id,email,password_hash,registration_phone) values (?,?,?,?)',legacy,EMAIL,'synthetic-password-digest',PHONE)
        when:
        def enrolled=enroll()
        then:
        enrolled.state()=='BLOCKED_MIGRATION'
        row('identities').id==legacy
        count('identities')==1
        count('external_identities')==0
        count('managed_registration_principals')==0
        count('registration_completion_intents')==0
    }
    def 'staged unmapped subject cannot obtain a different legacy UUID through SSO before both receipts'(){
        given:
        def enrolled=enroll()
        if(firstPurpose!=null)consume(open(enrolled.enrollmentId(),firstPurpose))
        def profiles=Mock(RegistrationProfiles)
        def service=new AuthService(new PostgresAuthStore(database,flag),new Argon2Passwords(),Clock.systemUTC(),profiles)
        when:
        service.sso('synthetic-provider-token',{ token -> new SsoTokens.Principal(ISSUER,flow.subject(),PHONE,EMAIL,true) } as SsoTokens)
        then:
        def failure=thrown(AuthFailure)
        failure.kind()==AuthFailure.Kind.UNAUTHORIZED
        0*profiles._
        count('identities')==0
        count('external_identities')==0
        count('sessions')==0
        count('sso_login_events')==0
        where:
        [firstPurpose,flag] << [null,Purpose.REGISTRATION_EMAIL,Purpose.REGISTRATION_PHONE].collectMany{purpose->
            [[purpose,false],[purpose,true]]
        }
    }
    def 'reserved staged contacts reject a different SSO subject and new local credentials in every mode'(){
        given:
        def enrolled=enroll()
        if(firstPurpose!=null)consume(open(enrolled.enrollmentId(),firstPurpose))
        def profiles=Mock(RegistrationProfiles)
        def service=new AuthService(new PostgresAuthStore(database,flag),new Argon2Passwords(),Clock.systemUTC(),profiles)
        when:
        service.sso('synthetic-provider-token',{ token -> new SsoTokens.Principal(ISSUER,'different-subject',PHONE,EMAIL,true) } as SsoTokens)
        then:
        def ssoFailure=thrown(AuthFailure)
        ssoFailure.kind()==AuthFailure.Kind.CONFLICT
        0*profiles._
        when:
        service.register(email, 'synthetic sufficiently long password', phone)
        then:
        def registrationFailure=thrown(AuthFailure)
        registrationFailure.kind()==AuthFailure.Kind.CONFLICT
        0*profiles._
        count('identities')==0
        count('external_identities')==0
        count('sessions')==0
        count('sso_login_events')==0
        where:
        [firstPurpose,flag,email,phone] << [null,Purpose.REGISTRATION_EMAIL,Purpose.REGISTRATION_PHONE].collectMany{purpose->
            [false,true].collectMany{enabled->
                [[purpose,enabled,EMAIL,'+971521234567'],[purpose,enabled,'other@example.test',PHONE]]
            }
        }
    }
    def 'existing exact local owner retry remains compatible even when another staged authority uses its email'(){
        given:
        UUID legacy=UUID.randomUUID()
        def originalPhone='+971521234567'
        execute('insert into identities(id,email,password_hash,registration_phone) values (?,?,?,?)',legacy,EMAIL,'synthetic-original-hash',originalPhone)
        // This is blocked staging evidence, never permission to preempt the existing identity.
        enroll()
        when:
        def retried=new PostgresAuthStore(database,false).register(new com.arman.bank.authservice.domain.Identity(UUID.randomUUID(),EMAIL,originalPhone),'synthetic-replacement-hash')
        then:
        retried.id()==legacy
        row('identities').password_hash=='synthetic-original-hash'
        count('identities')==1
        count('managed_registration_principals')==0
    }
    def 'SSO waiting on uncommitted staging observes committed authority and cannot allocate an alternate UUID'(){
        given:
        def staged=new CountDownLatch(1)
        def release=new CountDownLatch(1)
        def pool=Executors.newFixedThreadPool(2)
        def staging=pool.submit({database.transaction{sql->
            PostgresVerificationProjectionStore.lockControls(sql)
            UUID enrollment=UUID.randomUUID()
            sql.execute("insert into verification_enrollments(id,issuer,subject,binding_digest,binding_key_id,phone,email,state,created_at,expires_at) values (?,?,?,?,?,?,?,'PENDING_CONTACTS',statement_timestamp(),statement_timestamp()+interval '24 hours')",enrollment,ISSUER,flow.subject(),new byte[32],'test',PHONE,EMAIL)
            sql.execute("insert into verification_authorities(enrollment_id,pending_identity_id,state,created_at,expires_at) values (?,?,'PENDING_CONTACTS',statement_timestamp(),statement_timestamp()+interval '24 hours')",enrollment,UUID.randomUUID())
            staged.countDown()
            assert release.await(10,TimeUnit.SECONDS)
            null
        }} as Callable)
        assert staged.await(10,TimeUnit.SECONDS)
        def profiles=Mock(RegistrationProfiles)
        def service=new AuthService(new PostgresAuthStore(database,false),new Argon2Passwords(),Clock.systemUTC(),profiles)
        def sso=pool.submit({service.sso('synthetic-provider-token',{token->new SsoTokens.Principal(ISSUER,flow.subject(),PHONE,EMAIL,true)} as SsoTokens)} as Callable)
        when:
        boolean waited=false
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5)
        while(!waited&&System.nanoTime()<deadline){
            waited=database.transaction{sql->sql.fetchOne("select exists(select 1 from pg_stat_activity where datname=current_database() and pid<>pg_backend_pid() and wait_event_type='Lock' and query like '%verification_control%' and cardinality(pg_blocking_pids(pid))>0)").get(0,Boolean)}
            if(!waited)Thread.sleep(20)
        }
        then:
        waited
        !sso.done
        when:
        release.countDown()
        staging.get(10,TimeUnit.SECONDS)
        sso.get(10,TimeUnit.SECONDS)
        then:
        def failure=thrown(ExecutionException)
        failure.cause instanceof AuthFailure
        failure.cause.kind()==AuthFailure.Kind.UNAUTHORIZED
        0*profiles._
        count('identities')==0
        count('external_identities')==0
        count('sessions')==0
        count('sso_login_events')==0
        cleanup:
        release.countDown();pool.shutdownNow()
    }
    def 'SSO committed before staging remains an unclassified legacy owner with off-mode compatibility'(){
        given:
        def profiles=Stub(RegistrationProfiles)
        def service=new AuthService(new PostgresAuthStore(database,false),new Argon2Passwords(),Clock.systemUTC(),profiles)
        def session=service.sso('synthetic-provider-token',{token->new SsoTokens.Principal(ISSUER,flow.subject(),PHONE,EMAIL,true)} as SsoTokens)
        UUID existing=service.me('Bearer '+session.accessToken()).id()
        when:
        enroll()
        then:
        service.me('Bearer '+session.accessToken()).id()==existing
        row('identities').id==existing
        count('identities')==1
        count('managed_registration_principals')==0
    }
}
