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

/** Staged trusted observations and real disposable PostgreSQL. Synthetic provider facts only; actual managed sessions in isolated PostgreSQL, no actual provider visibility or remote sends. */
class ManagedBankingIntegrationSpec extends Specification {
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

    ManagedBankingKeys bankingKeys(){new ManagedBankingKeys('bank-v1',['bank-v1':Base64.encoder.encodeToString(([81]*32) as byte[])])}
    PostgresManagedBankingStore bankingStore(){new PostgresManagedBankingStore(database,bankingKeys(),{issuer,client->issuer==ISSUER&&client=='armoney-web'} as ManagedBankingService.PairScope,WRITER)}
    ManagedBankingService consumer(Closure facts={binding,start->committedFacts(binding)}){
        new ManagedBankingService(true,bankingStore(),facts as CodeConfirmationObservations,{issuer,client->issuer==ISSUER&&client=='armoney-web'} as ManagedBankingService.PairScope)
    }
    def confirmed(){
        eligible();def input=command();confirmer(confirmations(staged(input))).confirm(WRITER,lookup(input));bankingStore().cleanup();input
    }
    SsoTokens.Principal principal(L.Write input,String sid=input.userSessionId()){
        new SsoTokens.Principal(ISSUER,input.subject(),PHONE,EMAIL,true,new SsoTokens.TokenContext(
            SsoTokens.Metadata.present(input.clientId()),SsoTokens.Metadata.present(sid),SsoTokens.Metadata.present(input.issuedAt()),
            SsoTokens.Metadata.present(input.expiresAt()),SsoTokens.Metadata.present(new SsoTokens.ProofClaims('interactive',input.eventId(),false))))
    }
    def 'confirmed interactive proof mints only dedicated digest and validates with fresh synthetic facts'(){
        given:def input=confirmed();def service=consumer()
        when:def session=service.exchange('authenticated.fixture.token',principal(input))
        then:session.accessToken().size()==43;count('managed_banking_sessions')==1;count('managed_banking_event_consumptions')==1;count('sessions')==0
        row('managed_banking_sessions').token_hash==AuthService.digest(session.accessToken())
        when:def identity=service.validate('Bearer '+session.accessToken())
        then:identity==row('managed_registration_principals').identity_id
        when:new PostgresAuthStore(database).requireBankingAccess(identity)
        then:thrown(AuthFailure)
    }
    def 'different authenticated tokens for one event cannot mint a second session even after logout'(){
        given:def input=confirmed();def service=consumer();def session=service.exchange('first.token',principal(input))
        service.logout('Bearer '+session.accessToken())
        when:service.exchange('second.token',principal(input))
        then:thrown(L.Failure);count('managed_banking_sessions')==0;count('managed_banking_event_consumptions')==1
    }
    def 'fresh negative synthetic provider commits suspension then denies without a session'(){
        given:def input=confirmed();long epoch=row('verification_authorities').verification_epoch
        when:consumer({binding,start->committedFacts(binding,false)}).exchange('negative.token',principal(input))
        then:thrown(AuthFailure);row('verification_authorities').state=='SUSPENDED';row('verification_authorities').verification_epoch==epoch+1
        count('managed_banking_sessions')==0;count('managed_banking_event_consumptions')==0
        row('managed_banking_completions').verdict=='DENIED'
    }
    def 'observer outage cannot invalidate or provision and leaves only original bounded admission'(){
        given:def input=confirmed();def before=row('verification_authorities')
        when:consumer({binding,start->throw new L.Failure(L.Error.UNAVAILABLE)}).exchange('outage.token',principal(input))
        then:thrown(L.Failure);row('verification_authorities')==before;count('managed_banking_operations')==1
        count('managed_banking_sessions')==0;count('managed_banking_completions')==0
    }
    def 'wrong session binding denies before observer and leaves no admission'(){
        given:def input=confirmed();def calls=new java.util.concurrent.atomic.AtomicInteger()
        when:consumer({binding,start->calls.incrementAndGet();committedFacts(binding)}).exchange('wrong.sid.token',principal(input,'wrong-session'))
        then:thrown(L.Failure);calls.get()==0;count('managed_banking_operations')==0;count('managed_banking_sessions')==0
    }
    def 'receipt change during provider observation rolls back exchange and all consumption'(){
        given:def input=confirmed()
        when:consumer({binding,start->execute("update verification_challenges set state='EXPIRED' where purpose='REGISTRATION_EMAIL'");committedFacts(binding)}).exchange('changed.receipt.token',principal(input))
        then:thrown(L.Failure);count('managed_banking_sessions')==0;count('managed_banking_event_consumptions')==0
    }
    def 'concurrent one event exchange has exactly one winner'(){
        given:def input=confirmed();def executor=Executors.newFixedThreadPool(2);def start=new CountDownLatch(1)
        when:
        def jobs=(1..2).collect{n->executor.submit({start.await(3,TimeUnit.SECONDS);try{consumer().exchange('concurrent.'+n,principal(input));true}catch(L.Failure denied){false}} as Callable<Boolean>)}
        start.countDown()
        def outcomes=jobs.collect{it.get(12,TimeUnit.SECONDS)}
        then:outcomes.count(true)==1;count('managed_banking_sessions')==1;count('managed_banking_event_consumptions')==1
        cleanup:executor.shutdownNow();executor.awaitTermination(3,TimeUnit.SECONDS)
    }
    def 'generic marker gates remain denied with either compatibility flag'(){
        given:def input=confirmed();UUID id=row('managed_registration_principals').identity_id
        when:new PostgresAuthStore(database,enforced).saveSession('a'*64,id,Instant.now(),Instant.now().plusSeconds(30))
        then:thrown(AuthFailure);count('sessions')==0;count('managed_banking_sessions')==0
        where:enforced << [false,true]
    }
    def 'real admission COMMIT lost acknowledgement or cancellation yields zero observer calls and no takeover'(){
        given:
        def input=confirmed();database.close()
        def driver=new BankingCommitFaultDriver(mode);java.sql.DriverManager.registerDriver(driver)
        database=new Database(driver.prefix+postgres.jdbcUrl,postgres.username,postgres.password)
        def calls=new java.util.concurrent.atomic.AtomicInteger();driver.armed.set(true)
        when:consumer({binding,start->calls.incrementAndGet();committedFacts(binding)}).exchange('uncertain.admission',principal(input))
        then:thrown(RuntimeException)
        when:Thread.interrupted()
        then:driver.fired.get()==1;calls.get()==0;count('managed_banking_operations')==1;count('managed_banking_sessions')==0
        when:consumer().exchange('fresh.token.same.event',principal(input))
        then:thrown(L.Failure);count('managed_banking_operations')==1
        cleanup:Thread.interrupted();java.sql.DriverManager.deregisterDriver(driver)
        where:mode << ['lost-ack','cancel-after-ack']
    }
    def 'real session COMMIT lost acknowledgement withholds token and event cannot remint'(){
        given:
        def input=confirmed();database.close()
        def driver=new BankingCommitFaultDriver('lost-ack',true);java.sql.DriverManager.registerDriver(driver)
        database=new Database(driver.prefix+postgres.jdbcUrl,postgres.username,postgres.password);driver.armed.set(true)
        when:consumer().exchange('uncertain.result',principal(input))
        then:thrown(RuntimeException);driver.fired.get()==1;count('managed_banking_sessions')==1;count('managed_banking_event_consumptions')==1
        when:consumer().exchange('other.token.same.event',principal(input))
        then:thrown(L.Failure);count('managed_banking_sessions')==1
        cleanup:java.sql.DriverManager.deregisterDriver(driver)
    }
    def 'final local expiry during consumption insert rolls back session and event'(){
        given:
        def input=confirmed()
        // Fixture-only minimum-deadline shortening; restore immutable trigger before exercising the consumer.
        database.transaction{sql->
            sql.execute('alter table banking_eligibility_grants disable trigger banking_eligibility_grant_immutable')
            sql.execute("update banking_eligibility_grants set expires_at=clock_timestamp()+interval '2 seconds'")
            sql.execute('alter table banking_eligibility_grants enable trigger banking_eligibility_grant_immutable')
        }
        execute('''create function qa_managed_delay() returns trigger language plpgsql as $$ begin perform pg_sleep(2.5);return NEW;end $$''')
        execute('create trigger qa_managed_delay before insert on managed_banking_event_consumptions for each row execute function qa_managed_delay()')
        when:consumer().exchange('delayed.consume',principal(input))
        then:thrown(L.Failure);count('managed_banking_sessions')==0;count('managed_banking_event_consumptions')==0;count('managed_banking_completions')==0
    }
    def 'negative is not blocked by positive cleanup health'(){
        given:def input=confirmed();execute('delete from managed_banking_cleanup');long epoch=row('verification_authorities').verification_epoch
        when:consumer({binding,start->committedFacts(binding,false)}).exchange('negative.unhealthy',principal(input))
        then:thrown(AuthFailure);row('verification_authorities').verification_epoch==epoch+1;count('managed_banking_sessions')==0
    }
    def 'own negative update crossing minimum local expiry rolls back suspension'(){
        given:
        def input=confirmed();long epoch=row('verification_authorities').verification_epoch
        // Fixture-only minimum-deadline shortening; restore immutable trigger before exercising the consumer.
        database.transaction{sql->
            sql.execute('alter table banking_eligibility_grants disable trigger banking_eligibility_grant_immutable')
            sql.execute("update banking_eligibility_grants set expires_at=clock_timestamp()+interval '2 seconds'")
            sql.execute('alter table banking_eligibility_grants enable trigger banking_eligibility_grant_immutable')
        }
        execute('''create function qa_suspend_delay() returns trigger language plpgsql as $$ begin if NEW.state='SUSPENDED' then perform pg_sleep(2.5);end if;return NEW;end $$''')
        execute('create trigger qa_suspend_delay before update on verification_authorities for each row execute function qa_suspend_delay()')
        when:consumer({binding,start->committedFacts(binding,false)}).exchange('negative.delayed',principal(input))
        then:thrown(L.Failure);row('verification_authorities').state=='PROJECTED';row('verification_authorities').verification_epoch==epoch
        count('managed_banking_completions')==0
    }

    def 'stale provider lease cannot insert session or change authority'(){
        given:def input=confirmed();def before=row('verification_authorities')
        when:consumer({binding,start->
            def observed=Instant.parse('2030-01-01T00:00:00Z')
            new com.arman.bank.authservice.domain.CodeConfirmation.Facts(binding,PHONE,EMAIL,PHONE,true,true,true,observed,observed.plusSeconds(1))
        }).exchange('empty.provider.lease',principal(input))
        then:thrown(L.Failure);row('verification_authorities')==before;count('managed_banking_sessions')==0;count('managed_banking_completions')==0
    }
    def 'local invalidation after session creation blocks validation before provider and cannot revive'(){
        given:def input=confirmed();def service=consumer();def session=service.exchange('session.current',principal(input))
        execute("update verification_authorities set state='SUSPENDED',verification_epoch=verification_epoch+1,revision=revision+1")
        def calls=new java.util.concurrent.atomic.AtomicInteger()
        when:consumer({binding,start->calls.incrementAndGet();committedFacts(binding)}).validate('Bearer '+session.accessToken())
        then:thrown(L.Failure);calls.get()==0;count('managed_banking_event_consumptions')==1
    }
    def 'same epoch without exact User ACK does not call provider or create managed session'(){
        given:def input=confirmed();execute("update auth_verification_projection_tombstones set recovery_barrier=true")
        def calls=new java.util.concurrent.atomic.AtomicInteger()
        when:consumer({binding,start->calls.incrementAndGet();committedFacts(binding)}).exchange('recovery.barrier',principal(input))
        then:thrown(L.Failure);calls.get()==0;count('managed_banking_operations')==0;count('managed_banking_sessions')==0
    }
    /** Test-only post-real-COMMIT fault; not actual wire failure evidence. */
    static class BankingCommitFaultDriver implements java.sql.Driver {
        final String prefix='jdbc:qa-managed-banking:'+UUID.randomUUID()+':'
        final String mode
        final boolean resultCommit
        final java.util.concurrent.atomic.AtomicBoolean armed=new java.util.concurrent.atomic.AtomicBoolean(false)
        final java.util.concurrent.atomic.AtomicInteger fired=new java.util.concurrent.atomic.AtomicInteger()
        final java.util.concurrent.atomic.AtomicReference<String> phase=new java.util.concurrent.atomic.AtomicReference<>()
        BankingCommitFaultDriver(String mode,boolean resultCommit=false){this.mode=mode;this.resultCommit=resultCommit}
        @Override boolean acceptsURL(String url){url!=null&&url.startsWith(prefix)}
        @Override java.sql.Connection connect(String url,Properties properties){
            if(!acceptsURL(url))return null
            def physical=java.sql.DriverManager.getConnection(url.substring(prefix.length()),properties)
            def handler={Object proxy,java.lang.reflect.Method method,Object[] arguments->
                try{
                    boolean failCommit=false
                    if(method.name=='commit'&&armed.get()){
                        def statement=physical.createStatement()
                        try{
                            def rows=statement.executeQuery('select exists(select 1 from managed_banking_operations) as anchor,exists(select 1 from managed_banking_sessions) as result')
                            try{assert rows.next();failCommit=(resultCommit?rows.getBoolean('result'):(rows.getBoolean('anchor')&&!rows.getBoolean('result')))&&armed.compareAndSet(true,false)}finally{rows.close()}
                        }finally{statement.close()}
                    }
                    Object result=method.invoke(physical,arguments)
                    if(failCommit){
                        phase.set(resultCommit?'RESULT_COMMIT':'ANCHOR_COMMIT');fired.incrementAndGet()
                        if(mode=='lost-ack')throw new java.sql.SQLException('synthetic acknowledgement loss after real commit','08007')
                        if(mode=='cancel-after-ack')Thread.currentThread().interrupt()
                    }
                    return result
                }catch(java.lang.reflect.InvocationTargetException wrapped){throw wrapped.cause}
            } as java.lang.reflect.InvocationHandler
            (java.sql.Connection)java.lang.reflect.Proxy.newProxyInstance(getClass().classLoader,[java.sql.Connection] as Class[],handler)
        }
        @Override java.sql.DriverPropertyInfo[] getPropertyInfo(String url,Properties properties){new java.sql.DriverPropertyInfo[0]}
        @Override int getMajorVersion(){1}
        @Override int getMinorVersion(){0}
        @Override boolean jdbcCompliant(){false}
        @Override java.util.logging.Logger getParentLogger(){java.util.logging.Logger.getLogger('qa-code-confirmation-fault')}
    }

}
