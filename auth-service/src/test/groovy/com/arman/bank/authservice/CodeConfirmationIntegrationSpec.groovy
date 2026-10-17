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
class CodeConfirmationIntegrationSpec extends Specification {
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
    def 'cleanup evaluates empty and populated V13 capacity tables without deleting fresh confirmation records'(){
        given:def staging=stageStore()
        when:staging.cleanup()
        then:row('sso_code_intent_cleanup').healthy;!row('sso_code_intent_cleanup').capacity_alert
        count('sso_code_confirmation_anchors')==0;count('sso_code_confirmation_results')==0;count('sso_code_confirmation_anchor_completions')==0
        when:
        eligible();def input=command();staging.stage(WRITER,input);def core=confirmations(staging)
        confirmer(core).confirm(WRITER,lookup(input));awaitReplayWindow();confirmer(core).confirm(WRITER,lookup(input))
        def frozen=confirmationRows();staging.cleanup()
        then:confirmationRows()==frozen;row('sso_code_intent_cleanup').healthy;!row('sso_code_intent_cleanup').capacity_alert
        count('sso_code_confirmation_anchors')==2;count('sso_code_confirmation_results')==1;count('sso_code_confirmation_anchor_completions')==1
    }
    def 'actual current receipts grant and projection confirm one original proof then fresh replay without new hourly debit'(){
        given:
        eligible();def input=command();def staging=staged(input);def core=confirmations(staging)
        def origin=row('sso_login_creation_reservations')
        when:
        def first=confirmer(core).confirm(WRITER,lookup(input));def frozen=snapshots()
        awaitReplayWindow();def replay=confirmer(core).confirm(WRITER,lookup(input))
        then:
        !first.deniedCommitted();replay.snapshot()==first.snapshot();snapshots()==frozen
        count('sso_code_confirmation_results')==1;count('sso_code_confirmation_anchors')==2;count('sso_code_confirmation_anchor_completions')==1
        count('sso_login_creation_reservations')==1;row('sso_login_creation_reservations')==origin
        count('sso_login_events')==1;count('sso_login_proofs')==1;count('sessions')==0
        row('verification_authorities').state=='PROJECTED'
        when:new PostgresAuthStore(database,flag).requireBankingAccess(row('identities').id)
        then:thrown(AuthFailure)
        where:flag << [false,true]
    }
    static String replaceReceiptChallenge(){
        // Replacement preserves all candidate/enrollment/purpose/version composite FKs.
        // The original receipt now references an EXPIRED challenge, so consumed evidence is genuinely absent.
        '''with replacement as (
            insert into verification_challenges(id,enrollment_id,purpose,contact_version,state,digest_key_id,created_at,expires_at,resend_at,candidate_id)
            select gen_random_uuid(),c.enrollment_id,c.purpose,c.contact_version,'EXPIRED',c.digest_key_id,c.created_at,c.expires_at,c.resend_at,c.candidate_id
            from verification_challenges c where c.id in(select challenge_id from verification_receipts)
            returning id,candidate_id)
        update verification_receipts r set challenge_id=replacement.id from replacement where r.candidate_id=replacement.candidate_id'''
    }
    void breakLocal(String kind){
        database.transaction{sql->
            switch(kind){
                case 'receipt':assert sql.execute(replaceReceiptChallenge())==2;break
                case 'challenge':sql.execute("update verification_challenges set state='EXPIRED' where state='CONSUMED'");break
                case 'recovery':sql.execute('update auth_verification_projection_tombstones set recovery_barrier=true');break
                case 'ack':sql.execute("update auth_verification_projection_tombstones set payload_hash=repeat('f',64)");break
                case 'grant':
                    sql.execute('alter table banking_eligibility_grants disable trigger banking_eligibility_grant_immutable')
                    sql.execute('update banking_eligibility_grants set email_contact_version=email_contact_version+1')
                    sql.execute('alter table banking_eligibility_grants enable trigger banking_eligibility_grant_immutable');break
                case 'expired-grant':
                    sql.execute('alter table banking_eligibility_grants disable trigger banking_eligibility_grant_immutable')
                    sql.execute("update banking_eligibility_grants set created_at=clock_timestamp()-interval '2 seconds',expires_at=clock_timestamp()-interval '1 second'")
                    sql.execute('alter table banking_eligibility_grants enable trigger banking_eligibility_grant_immutable');break
                default:throw new AssertionError('unknown fixture mutation')
            }
        }
    }
    def 'stale local #kind evidence at #phase cannot call provider or commit even a fresh disabled denial'(){
        given:
        eligible();def input=command();def core=confirmations(staged(input));def original=row('verification_authorities')
        def calls=new java.util.concurrent.atomic.AtomicInteger()
        if(phase=='preflight')breakLocal(kind)
        when:confirmer(core,{binding,start->calls.incrementAndGet();breakLocal(kind);committedFacts(binding,false)}).confirm(WRITER,lookup(input))
        then:thrown(L.Failure);row('verification_authorities')==original
        calls.get()==(phase=='preflight'?0:1);count('sso_code_confirmation_anchors')==(phase=='preflight'?0:1)
        count('sso_code_confirmation_results')==0;count('sso_login_proofs')==0;count('sso_login_events')==0;count('sessions')==0
        where:[kind,phase] << [['receipt','challenge','recovery','ack','grant','expired-grant'],['preflight','completion']].combinations()
    }
    def 'receipt mutation committed while completion waits on its row lock cannot commit stale negative'(){
        given:
        eligible();def input=command();def core=confirmations(staged(input));def original=row('verification_authorities')
        def blocker=java.sql.DriverManager.getConnection(postgres.jdbcUrl,postgres.username,postgres.password)
        blocker.autoCommit=false
        def executor=Executors.newSingleThreadExecutor();def waited=new java.util.concurrent.atomic.AtomicBoolean()
        Future released=null
        when:
        confirmer(core,{binding,start->
            def statement=blocker.createStatement()
            try{assert statement.executeUpdate(replaceReceiptChallenge())==2}finally{statement.close()}
            released=executor.submit({
                long limit=System.nanoTime()+TimeUnit.SECONDS.toNanos(3)
                try{
                    while(!database.transaction{sql->sql.fetchOne("select exists(select 1 from pg_stat_activity where datname=current_database() and wait_event='transactionid' and query like '%verification_receipts%')").get(0,Boolean)}){
                        assert System.nanoTime()<limit:'completion never waited for actual receipt transaction lock'
                        Thread.sleep(10)
                    }
                    waited.set(true);blocker.commit()
                }finally{blocker.rollback()}
            } as Callable)
            committedFacts(binding,false)
        }).confirm(WRITER,lookup(input))
        then:def invalid=thrown(L.Failure);invalid.error()==L.Error.CONFLICT
        when:released.get(5,TimeUnit.SECONDS)
        then:waited.get();row('verification_authorities')==original;count('sso_code_confirmation_anchors')==1
        count('sso_login_proofs')==0;count('sso_code_confirmation_results')==0;count('sso_login_events')==0
        cleanup:executor.shutdownNow();blocker?.close()
    }
    def 'FIRST positive completion cannot allocate a replay or call provider until its original window is spent'(){
        given:eligible();def input=command();def core=confirmations(staged(input))
        def first=confirmer(core).confirm(WRITER,lookup(input));def frozen=confirmationRows();def calls=new java.util.concurrent.atomic.AtomicInteger()
        when:confirmer(core,{binding,start->calls.incrementAndGet();committedFacts(binding)}).confirm(WRITER,lookup(input))
        then:def busy=thrown(L.Failure);busy.error()==L.Error.UNAVAILABLE;calls.get()==0;confirmationRows()==frozen
        when:awaitReplayWindow();def replay=confirmer(core,{binding,start->calls.incrementAndGet();committedFacts(binding)}).confirm(WRITER,lookup(input))
        then:replay.snapshot()==first.snapshot();calls.get()==1;count('sso_code_confirmation_anchors')==2;count('sso_login_proofs')==1
    }
    def 'creator only durable FIRST rejects configured actor B unresolved retry and concurrent duplicate external call'(){
        given:
        eligible();def input=command();def core=confirmations(staged(input))
        def entered=new CountDownLatch(1);def release=new CountDownLatch(1);def calls=new java.util.concurrent.atomic.AtomicInteger()
        def usecase=confirmer(core,{binding,start->calls.incrementAndGet();entered.countDown();assert release.await(10,TimeUnit.SECONDS);committedFacts(binding)})
        def pool=Executors.newSingleThreadExecutor();def first=pool.submit({usecase.confirm(WRITER,lookup(input))} as Callable)
        assert entered.await(5,TimeUnit.SECONDS)
        def frozen=confirmationRows()
        when:confirmer(core).confirm('other-actor',lookup(input))
        then:def foreign=thrown(L.Failure);foreign.error()==L.Error.CONFLICT;confirmationRows()==frozen
        when:usecase.confirm(WRITER,lookup(input))
        then:def busy=thrown(L.Failure);busy.error()==L.Error.UNAVAILABLE;calls.get()==1;confirmationRows()==frozen
        when:release.countDown();def result=first.get(10,TimeUnit.SECONDS)
        then:!result.deniedCommitted();count('sso_login_proofs')==1;count('sso_code_confirmation_anchors')==1
        cleanup:release.countDown();pool.shutdownNow()
    }
    def 'unresolved anchor never renews after observer failure or elapsed budget'(){
        given:eligible();def input=command();def core=confirmations(staged(input))
        when:confirmer(core,{binding,start->throw new L.Failure(L.Error.UNAVAILABLE)}).confirm(WRITER,lookup(input))
        then:thrown(L.Failure)
        when:
        def frozen=confirmationRows();Thread.sleep(4200)
        confirmer(core).confirm(WRITER,lookup(input))
        then:def failure=thrown(L.Failure);failure.error()==L.Error.UNAVAILABLE;confirmationRows()==frozen
        count('sso_login_proofs')==0;count('sso_login_events')==0;count('sessions')==0
        when:confirmer(core).confirm('other-actor',lookup(input))
        then:def actor=thrown(L.Failure);actor.error()==L.Error.CONFLICT;confirmationRows()==frozen
    }
    def 'confirmation consumes timely ORIGINAL stage after fresh CREATE cutoff without reopening it'(){
        given:
        eligible();def original=command();def issued=Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS).minusSeconds(20)
        def input=new L.Write(original.commandId(),original.codeDigest(),original.codeKeyId(),original.issuer(),original.subject(),original.clientId(),original.userSessionId(),original.authenticationSessionId(),issued,issued.plusSeconds(120),original.kind(),original.eventId(),issued)
        def core=confirmations(staged(input));Thread.sleep(11000)
        when:def result=confirmer(core).confirm(WRITER,lookup(input))
        then:!result.deniedCommitted();result.snapshot().issuedAt()==issued;result.snapshot().expiresAt()==issued.plusSeconds(120)
        count('sso_login_creation_reservations')==1
    }
    def 'same stamp fresh negative commits separately with no proof result audit or repeated epoch advance'(){
        given:eligible();def input=command();def core=confirmations(staged(input));long epoch=row('verification_authorities').verification_epoch
        when:def denied=confirmer(core,{binding,start->committedFacts(binding,false)}).confirm(WRITER,lookup(input))
        then:denied.deniedCommitted();denied.snapshot()==null;row('verification_authorities').state=='SUSPENDED'
        row('verification_authorities').verification_epoch==epoch+1
        count('sso_login_proofs')==0;count('sso_login_events')==0;count('sso_code_confirmation_results')==0;count('sso_code_confirmation_anchor_completions')==0
        when:confirmer(core,{binding,start->committedFacts(binding,false)}).confirm(WRITER,lookup(input))
        then:thrown(L.Failure);row('verification_authorities').verification_epoch==epoch+1
    }
    def 'late positive or negative observer facts roll back final work without renewing durable FIRST'(){
        given:eligible();def input=command();def core=confirmations(staged(input));long epoch=row('verification_authorities').verification_epoch
        when:confirmer(core,{binding,start->Thread.sleep(4200);committedFacts(binding,positive)}).confirm(WRITER,lookup(input))
        then:thrown(L.Failure);count('sso_code_confirmation_anchors')==1;count('sso_login_proofs')==0;count('sso_code_confirmation_results')==0
        row('verification_authorities').verification_epoch==epoch
        where:positive << [false,true]
    }
    def 'final proof or result insertion evidence mutation rolls back ALL positive effects leaving original anchor'(){
        given:
        eligible();def input=command();def core=confirmations(staged(input));long epoch=row('verification_authorities').verification_epoch
        execute("create function qa_confirmation_mutate() returns trigger language plpgsql as 'begin update verification_authorities set verification_epoch=verification_epoch+1; return new; end;'")
        execute('create trigger qa_confirmation_mutate before insert on '+table+' for each row execute function qa_confirmation_mutate()')
        when:confirmer(core).confirm(WRITER,lookup(input))
        then:thrown(RuntimeException);count('sso_code_confirmation_anchors')==1;count('sso_login_proofs')==0;count('sso_login_events')==0;count('sso_code_confirmation_results')==0
        row('verification_authorities').verification_epoch==epoch
        where:table << ['sso_login_proofs','sso_code_confirmation_results']
    }
    def 'negative observation stales AFTER suspension update so attempted invalidation rolls back'(){
        given:
        eligible();def input=command();def core=confirmations(staged(input));long epoch=row('verification_authorities').verification_epoch
        execute("create function qa_confirmation_stale() returns trigger language plpgsql as 'begin perform pg_sleep(4.2); return new; end;'")
        execute('create trigger qa_confirmation_stale after update on verification_authorities for each row execute function qa_confirmation_stale()')
        when:confirmer(core,{binding,start->committedFacts(binding,false)}).confirm(WRITER,lookup(input))
        then:thrown(L.Failure);row('verification_authorities').verification_epoch==epoch;row('verification_authorities').state=='PROJECTED';count('sso_login_proofs')==0
    }
    def 'negative mutation rolls back when #bound local deadline expires during update while provider budget remains live'(){
        given:
        eligible();def input=command();def core=confirmations(staged(input));def original=row('verification_authorities')
        execute('create sequence qa_suspend_hits')
        execute('''create function qa_local_deadline_delay() returns trigger language plpgsql as $$
            begin perform nextval('qa_suspend_hits');perform pg_sleep(1.5);return new;end $$''')
        execute('create trigger qa_local_deadline_delay after update on verification_authorities for each row execute function qa_local_deadline_delay()')
        when:
        confirmer(core,{binding,start->
            database.transaction{sql->
                def cutoff=sql.fetchOne("select clock_timestamp()+interval '1 second'").get(0,java.time.OffsetDateTime)
                sql.execute('alter table banking_eligibility_grants disable trigger banking_eligibility_grant_immutable')
                sql.execute("update banking_eligibility_grants set created_at=cast(? as timestamptz)-interval '2 seconds',expires_at=cast(? as timestamptz)",cutoff.toString(),cutoff.toString())
                sql.execute('alter table banking_eligibility_grants enable trigger banking_eligibility_grant_immutable')
                // Valid grant expiry must not exceed ACK bounds; share the exact boundary when testing either ACK component.
                if(bound!='grant')sql.execute("update "+(bound=='command'?'auth_verification_projection_commands':'auth_verification_projection_tombstones')+
                    " set created_at=cast(? as timestamptz)-interval '604800 seconds',expires_at=cast(? as timestamptz)",cutoff.toString(),cutoff.toString())
            }
            committedFacts(binding,false)
        }).confirm(WRITER,lookup(input))
        then:def expired=thrown(L.Failure);expired.error()==L.Error.UNAVAILABLE
        row('verification_authorities')==original;count('sso_login_proofs')==0;count('sso_code_confirmation_results')==0;count('sessions')==0
        database.transaction{sql->
            assert sql.fetchOne('select is_called from qa_suspend_hits').get(0,Boolean)
            assert sql.fetchOne("select clock_timestamp()<started_at+interval '4 seconds' from sso_code_confirmation_anchors").get(0,Boolean)
            true
        }
        where:bound << ['grant','command','tomb']
    }
    def '999 prior legacy creations plus stage remains 1000 after confirmation and replay'(){
        given:
        eligible()
        execute("""insert into sso_login_proofs
            select gen_random_uuid(),'https://legacy.example/realm',lpad(to_hex(n),64,'0'),'legacy-code',
              decode(repeat('00',32),'hex'),decode(repeat('00',32),'hex'),'reuse',null,false,
              statement_timestamp(),statement_timestamp()+interval '120 seconds',statement_timestamp(),statement_timestamp()+interval '7 days'
            from generate_series(1,999) n""")
        def input=command();def core=confirmations(staged(input))
        assert count('sso_login_creation_reservations')==1
        when:def first=confirmer(core).confirm(WRITER,lookup(input));awaitReplayWindow();def replay=confirmer(core).confirm(WRITER,lookup(input))
        then:first.snapshot()==replay.snapshot();count('sso_login_creation_reservations')==1;count('sso_login_proofs')==1000
        database.transaction{sql->PostgresVerificationProjectionStore.lockControls(sql);PostgresLoginCreationBudget.requireAvailable(sql,Instant.now(),1001);true}
        when:
        database.transaction{sql->PostgresVerificationProjectionStore.lockControls(sql);PostgresLoginCreationBudget.requireAvailable(sql,Instant.now(),1000)}
        then:def full=thrown(L.Failure);full.error()==L.Error.RATE_LIMITED
    }
    def 'pruned original hourly reservation excludes mapped recent proof but still counts unrelated unreserved legacy proof'(){
        given:
        eligible();def input=command();def staging=staged(input);def core=confirmations(staging)
        confirmer(core).confirm(WRITER,lookup(input))
        // Isolated abuse-budget fixture ages only the original debit past its legitimate 65-minute pruning horizon.
        // No authority/provider/code clocks or proof creation times are changed.
        execute("update sso_login_creation_reservations set created_at=clock_timestamp()-interval '66 minutes'")
        staging.cleanup()
        assert count('sso_login_creation_reservations')==0
        execute("""insert into sso_login_proofs values(gen_random_uuid(),'https://legacy.example/realm',repeat('e',64),'legacy-code',
            decode(repeat('00',32),'hex'),decode(repeat('00',32),'hex'),'reuse',null,false,
            statement_timestamp(),statement_timestamp()+interval '120 seconds',statement_timestamp(),statement_timestamp()+interval '7 days')""")
        when:
        def frozen=snapshots();awaitReplayWindow();def replay=confirmer(core).confirm(WRITER,lookup(input))
        then:!replay.deniedCommitted();snapshots()==frozen;count('sso_login_creation_reservations')==0;count('sso_login_proofs')==2
        database.transaction{sql->PostgresVerificationProjectionStore.lockControls(sql);PostgresLoginCreationBudget.requireAvailable(sql,Instant.now(),2);true}
        when:database.transaction{sql->PostgresVerificationProjectionStore.lockControls(sql);PostgresLoginCreationBudget.requireAvailable(sql,Instant.now(),1)}
        then:def onlyLegacy=thrown(L.Failure);onlyLegacy.error()==L.Error.RATE_LIMITED
    }
    def 'fresh negative on committed replay suspends authority while original proof result remains immutable'(){
        given:
        eligible();def input=command();def core=confirmations(staged(input))
        def first=confirmer(core).confirm(WRITER,lookup(input));def frozen=snapshots()
        def result=row('sso_code_confirmation_results');long epoch=row('verification_authorities').verification_epoch
        when:awaitReplayWindow();def denied=confirmer(core,{binding,start->committedFacts(binding,false)}).confirm(WRITER,lookup(input))
        then:denied.deniedCommitted();row('verification_authorities').verification_epoch==epoch+1
        snapshots()==frozen;row('sso_code_confirmation_results')==result;count('sso_code_confirmation_anchor_completions')==0
        count('sessions')==0
    }
    def 'immutable FIRST and result reject no op update field update and premature deletion'(){
        given:eligible();def input=command();def core=confirmations(staged(input));confirmer(core).confirm(WRITER,lookup(input))
        def frozen=confirmationRows()
        when:execute(statement)
        then:thrown(RuntimeException);confirmationRows()==frozen
        where:statement << [
            'update sso_code_confirmation_anchors set started_at=started_at',
            "update sso_code_confirmation_anchors set started_at=started_at+interval '1 second'",
            'delete from sso_code_confirmation_anchors',
            'update sso_code_confirmation_results set proof_id=proof_id',
            'update sso_code_confirmation_results set proof_id=gen_random_uuid()',
            'delete from sso_code_confirmation_results']
    }
    def 'missing original retention key never falls back to current key or renews FIRST'(){
        given:eligible();def input=command();staged(input)
        def missing=new PostgresCodeIntentStore(database,cPolicy(),new CodeIntentRetentionKeys('retention-v2',['retention-v2':Base64.encoder.encodeToString(([63]*32) as byte[])]),
            new CodeIntentDetailCrypto('aead-v1',['aead-v1':Base64.encoder.encodeToString(([62]*32) as byte[])],[] as Set))
        def calls=new java.util.concurrent.atomic.AtomicInteger()
        when:confirmer(confirmations(missing),{binding,start->calls.incrementAndGet();committedFacts(binding)}).confirm(WRITER,lookup(input))
        then:def unavailable=thrown(L.Failure);unavailable.error()==L.Error.UNAVAILABLE;calls.get()==0
        count('sso_code_confirmation_anchors')==0;count('sso_login_proofs')==0;count('sso_login_creation_reservations')==1
    }

    def 'real durable FIRST commit with synthetic acknowledgement loss or cancellation yields no external capability'(){
        given:
        eligible();def input=command();staged(input);database.close()
        def driver=new AnchorCommitFaultDriver(mode);java.sql.DriverManager.registerDriver(driver)
        database=new Database(driver.prefix+postgres.jdbcUrl,postgres.username,postgres.password)
        def core=confirmations(stageStore());def calls=new java.util.concurrent.atomic.AtomicInteger();driver.armed.set(true)
        when:confirmer(core,{binding,start->calls.incrementAndGet();committedFacts(binding)}).confirm(WRITER,lookup(input))
        then:thrown(RuntimeException)
        when:Thread.interrupted()
        then:driver.fired.get()==1;driver.phase.get()=='ANCHOR_COMMIT';calls.get()==0
        count('sso_code_confirmation_anchors')==1;count('sso_code_confirmation_results')==0;count('sso_login_proofs')==0
        when:confirmer(core).confirm(WRITER,lookup(input))
        then:def unresolved=thrown(L.Failure);unresolved.error()==L.Error.UNAVAILABLE;count('sso_code_confirmation_anchors')==1
        cleanup:Thread.interrupted();java.sql.DriverManager.deregisterDriver(driver)
        where:mode << ['lost-ack','cancel-after-ack']
    }

    def 'bounded 32 anchors cannot be renewed even when every old replay completed'(){
        given:
        eligible();def input=command();def core=confirmations(staged(input));def result=confirmer(core).confirm(WRITER,lookup(input))
        execute("""insert into sso_code_confirmation_anchors
            select gen_random_uuid(),intent_id,retention_key_id,actor_hash,binding_hash,confirmation_hash,'REPLAY',n,
              started_at-interval '5 seconds',authority_hash,verification_epoch,authority_revision,expires_at,erase_at
            from sso_code_confirmation_anchors cross join generate_series(1,31)n where purpose='FIRST'""")
        execute("""insert into sso_code_confirmation_anchor_completions
            select id,?,clock_timestamp() from sso_code_confirmation_anchors where purpose='REPLAY'""",result.snapshot().proofId())
        awaitReplayWindow();def frozen=confirmationRows();def calls=new java.util.concurrent.atomic.AtomicInteger()
        when:confirmer(core,{binding,start->calls.incrementAndGet();committedFacts(binding)}).confirm(WRITER,lookup(input))
        then:def saturated=thrown(L.Failure);saturated.error()==L.Error.UNAVAILABLE;calls.get()==0;confirmationRows()==frozen
        count('sso_login_proofs')==1;count('sso_login_creation_reservations')==1
    }
    def 'positive cleanup health denial never suppresses a current trusted negative'(){
        given:eligible();def input=command();def core=confirmations(staged(input));long epoch=row('verification_authorities').verification_epoch
        execute('update sso_code_intent_cleanup set healthy=false')
        when:def result=confirmer(core,{binding,start->committedFacts(binding,false)}).confirm(WRITER,lookup(input))
        then:result.deniedCommitted();row('verification_authorities').verification_epoch==epoch+1;count('sso_login_proofs')==0
    }
    def 'retention cleanup purges expired bounded anchors without erasing retained immutable result or managed classification'(){
        given:
        eligible();def input=command();def staging=staged(input);def core=confirmations(staging)
        def result=confirmer(core).confirm(WRITER,lookup(input));awaitReplayWindow();confirmer(core).confirm(WRITER,lookup(input))
        def marker=row('managed_registration_principals');def mapping=row('sso_code_confirmation_results')
        // Fixture-only clock advance: all related immutable times retain their schema relationships.
        database.transaction{sql->
            sql.execute('alter table sso_code_confirmation_anchors disable trigger sso_code_confirmation_anchor_no_change')
            sql.execute("update sso_code_confirmation_anchors set started_at=started_at-interval '1 day',expires_at=expires_at-interval '1 day',erase_at=erase_at-interval '1 day'")
            sql.execute('alter table sso_code_confirmation_anchors enable trigger sso_code_confirmation_anchor_no_change')
        }
        when:staging.cleanup()
        then:count('sso_code_confirmation_anchors')==0;count('sso_code_confirmation_anchor_completions')==0
        row('sso_code_confirmation_results')==mapping;row('managed_registration_principals')==marker
        count('sso_code_intents')==1;count('sso_login_proofs')==1;count('sessions')==0
    }
    def 'unknown positive result COMMIT withholds response then fresh replay returns exact original proof after spent window'(){
        given:
        eligible();def input=command();staged(input);database.close()
        def driver=new AnchorCommitFaultDriver('lost-ack',true);java.sql.DriverManager.registerDriver(driver)
        database=new Database(driver.prefix+postgres.jdbcUrl,postgres.username,postgres.password)
        def core=confirmations(stageStore());driver.armed.set(true)
        when:confirmer(core).confirm(WRITER,lookup(input))
        then:thrown(RuntimeException);driver.fired.get()==1;driver.phase.get()=='RESULT_COMMIT'
        count('sso_code_confirmation_results')==1;count('sso_login_proofs')==1;count('sessions')==0
        when:def frozen=snapshots();awaitReplayWindow();def replay=confirmer(core).confirm(WRITER,lookup(input))
        then:replay.snapshot().proofId()==row('sso_code_confirmation_results').proof_id;snapshots()==frozen
        count('sso_login_creation_reservations')==1;count('sso_code_confirmation_anchor_completions')==1
        cleanup:java.sql.DriverManager.deregisterDriver(driver)
    }
    /** Test-only injection after REAL PostgreSQL COMMIT, never a simulated wire/protocol failure. */
    static class AnchorCommitFaultDriver implements java.sql.Driver {
        final String prefix='jdbc:qa-code-confirmation:'+UUID.randomUUID()+':'
        final String mode
        final boolean resultCommit
        final java.util.concurrent.atomic.AtomicBoolean armed=new java.util.concurrent.atomic.AtomicBoolean(false)
        final java.util.concurrent.atomic.AtomicInteger fired=new java.util.concurrent.atomic.AtomicInteger()
        final java.util.concurrent.atomic.AtomicReference<String> phase=new java.util.concurrent.atomic.AtomicReference<>()
        AnchorCommitFaultDriver(String mode,boolean resultCommit=false){this.mode=mode;this.resultCommit=resultCommit}
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
                            def rows=statement.executeQuery('select exists(select 1 from sso_code_confirmation_anchors) as anchor,exists(select 1 from sso_code_confirmation_results) as result')
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
