package com.arman.bank.authservice

import com.arman.bank.authservice.domain.LoginProof
import com.arman.bank.authservice.domain.LoginProof.*
import com.arman.bank.authservice.infrastructure.*
import com.arman.bank.runtime.Database
import org.testcontainers.containers.PostgreSQLContainer
import spock.lang.Specification
import java.time.*
import java.util.concurrent.*

/** Real owning PostgreSQL, synthetic managed classification; no provider or bank authorization. */
class CodeIntentIntegrationSpec extends Specification {
    static final String ISSUER='https://issuer.example/realm', ACTOR='synthetic-provider', CLIENT='armoney-web'
    PostgreSQLContainer postgres
    Database database
    PostgresCodeIntentStore store
    UUID identity=UUID.randomUUID(), enrollment=UUID.randomUUID()
    def policy=Policy.defaults([ISSUER] as Set,[CLIENT] as Set,['code1'] as Set,[ACTOR] as Set)
    def key=Base64.encoder.encodeToString(new byte[32])
    def setup(){
        postgres=new PostgreSQLContainer('postgres:17.6-alpine');postgres.start()
        database=new Database(postgres.jdbcUrl,postgres.username,postgres.password)
        database.transaction{sql->
            sql.execute('insert into identities(id) values (?)',identity)
            sql.execute('insert into external_identities values (?,?,?)',ISSUER,'managed-subject',identity)
            sql.execute('''insert into verification_enrollments(id,issuer,subject,binding_digest,binding_key_id,phone,email,state,created_at,expires_at)
                values (?,?,?,?,'synthetic','+971501234567','synthetic@example.test','PENDING_CONTACTS',clock_timestamp(),clock_timestamp()+interval '1 day')''',enrollment,ISSUER,'managed-subject',new byte[32])
            sql.execute('''insert into verification_authorities(enrollment_id,pending_identity_id,mapped_identity_id,state,created_at,expires_at)
                values (?,?,?,'PROJECTION_PENDING',statement_timestamp(),statement_timestamp()+interval '86400 seconds')''',enrollment,identity,identity)
            sql.execute('insert into managed_registration_principals(identity_id,enrollment_id) values (?,?)',identity,enrollment)
        }
        store=makeStore(['original':key],'original')
        store.cleanup()
    }
    def cleanup(){database?.close();postgres?.stop()}
    PostgresCodeIntentStore makeStore(Map ring,String current){new PostgresCodeIntentStore(database,policy,new CodeIntentRetentionKeys(current,ring),detailCrypto())}
    CodeIntentDetailCrypto detailCrypto(){new CodeIntentDetailCrypto('aead',['aead':Base64.encoder.encodeToString(([7]*32) as byte[])],[] as Set)}
    Write input(UUID command=UUID.randomUUID(),String digest='a'*64){
        def now=Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS)
        new Write(command,digest,'code1',ISSUER,'managed-subject',CLIENT,'synthetic-user-session','synthetic-auth-session',now,now.plusSeconds(120),Kind.INTERACTIVE,UUID.randomUUID(),now)
    }
    Write command(Write w,UUID id){new Write(id,w.codeDigest(),w.codeKeyId(),w.issuer(),w.subject(),w.clientId(),w.userSessionId(),w.authenticationSessionId(),w.issuedAt(),w.expiresAt(),w.kind(),w.eventId(),w.passwordAuthenticatedAt())}
    long count(String table){database.transaction{sql->sql.fetchOne('select count(*) from '+table).get(0,Long)}}
    Map businessSnapshot(){database.transaction{sql->
        ['identities','external_identities','sessions','verification_enrollments','verification_authorities','verification_candidates',
         'verification_challenges','verification_receipts','auth_verification_projection_commands','auth_verification_projection_tombstones',
         'banking_eligibility_grants','managed_registration_principals','registration_completion_intents','sso_login_events','sso_login_proofs','sso_login_proof_commands']
        .collectEntries{table->[(table):sql.fetch('select row_to_json(t)::text as snapshot from '+table+' t order by row_to_json(t)::text').getValues('snapshot',String)]}
    }}
    def 'fresh stage exact lost ACK and alias replay preserve original result without authority side effects'(){
        given: def request=input();def businessBefore=businessSnapshot()
        when:
        def first=store.stage(ACTOR,request)
        def replay=store.stage(ACTOR,request)
        def alias=store.stage(ACTOR,command(request,UUID.randomUUID()))
        then:
        first==replay && first==alias
        first.state()=='STAGED'
        first.expiresAt()==request.expiresAt()
        count('sso_code_intents')==1
        count('sso_login_creation_reservations')==1
        count('sso_code_intent_commands')==2
        count('sso_login_proofs')==0
        count('sso_login_events')==0
        count('sessions')==0
        businessSnapshot()==businessBefore
    }
    def 'concurrent lost ACK retries serialize to one immutable intent and one charge'(){
        given: def request=input();def pool=Executors.newFixedThreadPool(2)
        when:
        def tasks=(1..2).collect{pool.submit({store.stage(ACTOR,request)} as Callable)}
        def results=tasks.collect{it.get(15,TimeUnit.SECONDS)}
        then: results[0]==results[1];count('sso_code_intents')==1;count('sso_login_creation_reservations')==1
        cleanup: pool.shutdownNow()
    }
    def 'changed payload for same command conflicts and rolls back reservation'(){
        given: def request=input();store.stage(ACTOR,request)
        when: store.stage(ACTOR,input(request.commandId(),'b'*64))
        then: def failure=thrown(Failure);failure.error()==LoginProof.Error.CONFLICT
        count('sso_code_intents')==1;count('sso_login_creation_reservations')==1
    }
    def 'four command alias limit does not prevent exact replay'(){
        given:
        def request=input();def first=store.stage(ACTOR,request)
        (1..3).each{store.stage(ACTOR,command(request,UUID.randomUUID()))}
        when: store.stage(ACTOR,command(request,UUID.randomUUID()))
        then: def failure=thrown(Failure);failure.error()==LoginProof.Error.RATE_LIMITED
        when: def replay=store.stage(ACTOR,request)
        then: replay==first;count('sso_code_intent_commands')==4
    }
    def 'rotation retains original key and missing original key fails unavailable without reset'(){
        given:
        def request=input();def first=store.stage(ACTOR,request)
        def next=Base64.encoder.encodeToString((byte[])([2]*32))
        expect: makeStore(['original':key,'next':next],'next').stage(ACTOR,request)==first
        when: makeStore(['next':next],'next').stage(ACTOR,request)
        then: def failure=thrown(Failure);failure.error()==LoginProof.Error.UNAVAILABLE
        count('sso_code_intents')==1;count('sso_code_intent_admission')==1
    }
    def 'rotation migrates every configured actor budget including inactive depleted actors without resetting state'(){
        given:
        def actors=[ACTOR,'inactive-provider','new-provider'] as Set
        def multiPolicy=Policy.defaults([ISSUER] as Set,[CLIENT] as Set,['code1'] as Set,actors)
        def old=new PostgresCodeIntentStore(database,multiPolicy,new CodeIntentRetentionKeys('original',['original':key]),detailCrypto())
        def first=input();old.stage(ACTOR,first)
        old.stage('inactive-provider',input(UUID.randomUUID(),'b'*64))
        def oldKeys=new CodeIntentRetentionKeys('original',['original':key])
        def inactiveHash=oldKeys.hash('original','armoney-intent-actor-v1','inactive-provider')
        database.transaction{sql->sql.execute("update sso_code_intent_admission set credits=0,refilled_at=clock_timestamp()+interval '1 hour' where actor_hash=?",(Object)inactiveHash)}
        def preserved=database.transaction{sql->sql.fetchOne('select credits,refilled_at from sso_code_intent_admission where actor_hash=?',(Object)inactiveHash).intoMap()}
        def next=Base64.encoder.encodeToString((byte[])([2]*32))
        def currentKeys=new CodeIntentRetentionKeys('next',['original':key,'next':next])
        def rotated=new PostgresCodeIntentStore(database,multiPolicy,currentKeys,detailCrypto())
        when: rotated.stage(ACTOR,first)
        then:
        database.transaction{sql->
            assert sql.fetchOne('select count(*) from sso_code_intent_admission where retention_key_id<>?', 'next').get(0,Integer)==0
            assert sql.fetchOne('select credits,refilled_at from sso_code_intent_admission where actor_hash=?',(Object)currentKeys.hash('next','armoney-intent-actor-v1','inactive-provider')).intoMap()==preserved
            assert sql.fetchOne('select count(*) from sso_code_intents where retention_key_id=?','original').get(0,Integer)==2
            true
        }
        when: rotated.stage('inactive-provider',input(UUID.randomUUID(),'c'*64))
        then: def failure=thrown(Failure);failure.error()==LoginProof.Error.RATE_LIMITED
        when:
        database.transaction{sql->
            sql.execute('alter table sso_code_intents disable trigger sso_code_intent_no_change')
            sql.execute('alter table sso_code_intent_details disable trigger sso_code_intent_detail_no_change')
            sql.execute("""update sso_code_intents set issued_at=statement_timestamp()-interval '8 days',
                expires_at=statement_timestamp()-interval '8 days'+interval '120 seconds',stage_cutoff=statement_timestamp()-interval '8 days'+interval '30 seconds',
                retained_until=statement_timestamp()-interval '1 day'+interval '120 seconds'""")
            sql.execute("update sso_code_intent_details set erase_at=clock_timestamp()-interval '1 day'")
            sql.execute('alter table sso_code_intents enable trigger sso_code_intent_no_change')
            sql.execute('alter table sso_code_intent_details enable trigger sso_code_intent_detail_no_change')
        }
        rotated.cleanup()
        def restarted=new PostgresCodeIntentStore(database,multiPolicy,new CodeIntentRetentionKeys('next',['next':next]),detailCrypto())
        def created=restarted.stage('new-provider',input(UUID.randomUUID(),'d'*64))
        then: created.state()=='STAGED';count('sso_code_intent_admission')==3;count('sso_code_intents')==1
    }
    def 'duplicate logical actor rows under different retained keys cannot be merged or reset'(){
        given:
        store.stage(ACTOR,input())
        def next=Base64.encoder.encodeToString((byte[])([2]*32))
        def both=new CodeIntentRetentionKeys('next',['original':key,'next':next])
        database.transaction{sql->sql.execute("insert into sso_code_intent_admission values (?,'next',0,clock_timestamp())",(Object)both.hash('next','armoney-intent-actor-v1',ACTOR))}
        def before=database.transaction{sql->sql.fetch('select * from sso_code_intent_admission order by actor_hash').intoMaps()}
        when: new PostgresCodeIntentStore(database,policy,both,detailCrypto()).stage(ACTOR,input(UUID.randomUUID(),'b'*64))
        then: def failure=thrown(Failure);failure.error()==LoginProof.Error.UNAVAILABLE
        database.transaction{sql->sql.fetch('select * from sso_code_intent_admission order by actor_hash').intoMaps()}==before
    }

    def 'missing or ambiguous configured actor prevents rotation instead of resetting budgets'(){
        given:
        store.stage(ACTOR,input())
        def otherPolicy=Policy.defaults([ISSUER] as Set,[CLIENT] as Set,['code1'] as Set,['different-provider'] as Set)
        def other=new PostgresCodeIntentStore(database,otherPolicy,new CodeIntentRetentionKeys('original',['original':key]),detailCrypto())
        def before=database.transaction{sql->sql.fetch('select * from sso_code_intent_admission').intoMaps()}
        when: other.stage('different-provider',input(UUID.randomUUID(),'b'*64))
        then: def failure=thrown(Failure);failure.error()==LoginProof.Error.UNAVAILABLE
        database.transaction{sql->sql.fetch('select * from sso_code_intent_admission').intoMaps()}==before
    }

    def 'authority suspension or epoch change denies immutable replay #change'(){
        given:
        def request=input();store.stage(ACTOR,request)
        database.transaction{sql->sql.execute(change)}
        when: store.stage(ACTOR,request)
        then: def failure=thrown(Failure);failure.error()==LoginProof.Error.CONFLICT
        count('sso_login_proofs')==0;count('sessions')==0
        where:
        change << ["update verification_authorities set state='SUSPENDED'","update verification_authorities set verification_epoch=verification_epoch+1"]
    }
    def 'changed current contact candidate invalidates the immutable authority stamp'(){
        given:
        def request=input();store.stage(ACTOR,request)
        def candidate=UUID.randomUUID()
        database.transaction{sql->
            sql.execute("insert into verification_candidates values (?,?,'REGISTRATION_EMAIL',1,'changed@example.test',clock_timestamp())",candidate,enrollment)
            sql.execute('update verification_authorities set email_candidate_id=?',candidate)
        }
        when: store.stage(ACTOR,request)
        then: def failure=thrown(Failure);failure.error()==LoginProof.Error.CONFLICT
        count('sso_code_intents')==1;count('sso_login_creation_reservations')==1;count('sso_login_events')==0
    }
    def 'changed exact external association invalidates stage replay without linking contacts'(){
        given:
        def request=input();store.stage(ACTOR,request)
        database.transaction{sql->
            def replacement=UUID.randomUUID()
            sql.execute('insert into identities(id) values (?)',replacement)
            sql.execute('update external_identities set identity_id=? where issuer=? and subject=?',replacement,ISSUER,request.subject())
        }
        when: store.stage(ACTOR,request)
        then: def failure=thrown(Failure);failure.error()==LoginProof.Error.CONFLICT
        count('sso_code_intents')==1;count('sso_login_creation_reservations')==1;count('sessions')==0
    }

    def 'missing detail before original code expiry is unavailable and never recreated'(){
        given:
        def request=input();store.stage(ACTOR,request)
        database.transaction{sql->
            sql.execute('alter table sso_code_intent_details disable trigger sso_code_intent_detail_no_change')
            sql.execute('delete from sso_code_intent_details')
            sql.execute('alter table sso_code_intent_details enable trigger sso_code_intent_detail_no_change')
        }
        when: store.stage(ACTOR,request)
        then: def failure=thrown(Failure);failure.error()==LoginProof.Error.UNAVAILABLE
        count('sso_code_intent_details')==0;count('sso_code_intents')==1
    }
    def 'unhealthy cleanup blocks only new creation while original replay survives'(){
        given:
        def request=input();def first=store.stage(ACTOR,request)
        database.transaction{sql->sql.execute('update sso_code_intent_cleanup set healthy=false')}
        expect: store.stage(ACTOR,request)==first
        when: store.stage(ACTOR,input(UUID.randomUUID(),'b'*64))
        then: def failure=thrown(Failure);failure.error()==LoginProof.Error.UNAVAILABLE
        count('sso_code_intents')==1
    }
    def 'same transaction final authority mutation rolls all staged inserts back'(){
        given:
        database.transaction{sql->
            sql.execute('''create function qa_intent_epoch_change() returns trigger language plpgsql as $$ begin
                update verification_authorities set verification_epoch=verification_epoch+1; return NEW; end $$''')
            sql.execute('create trigger qa_epoch_change after insert on sso_code_intent_commands for each row execute function qa_intent_epoch_change()')
        }
        when: store.stage(ACTOR,input())
        then: def failure=thrown(Failure);failure.error()==LoginProof.Error.CONFLICT
        count('sso_code_intents')==0;count('sso_code_intent_details')==0;count('sso_code_intent_commands')==0;count('sso_login_creation_reservations')==0
        database.transaction{sql->sql.fetchOne('select verification_epoch from verification_authorities').get(0,Long)}==1
    }
    def 'an existing legacy identity without permanent managed classification cannot stage'(){
        given:
        def legacy=UUID.randomUUID();def legacyEnrollment=UUID.randomUUID()
        database.transaction{sql->
            sql.execute('insert into identities(id) values (?)',legacy)
            sql.execute('insert into external_identities values (?,?,?)',ISSUER,'legacy-subject',legacy)
            sql.execute("""insert into verification_enrollments(id,issuer,subject,binding_digest,binding_key_id,phone,email,state,created_at,expires_at)
                values (?,?,?,?,'synthetic','+971521234567','legacy@example.test','PENDING_CONTACTS',clock_timestamp(),clock_timestamp()+interval '1 day')""",legacyEnrollment,ISSUER,'legacy-subject',new byte[32])
            sql.execute("""insert into verification_authorities(enrollment_id,pending_identity_id,mapped_identity_id,state,created_at,expires_at)
                values (?,?,?,'PROJECTION_PENDING',statement_timestamp(),statement_timestamp()+interval '86400 seconds')""",legacyEnrollment,legacy,legacy)
        }
        def w=input()
        def request=new Write(w.commandId(),w.codeDigest(),w.codeKeyId(),w.issuer(),'legacy-subject',w.clientId(),w.userSessionId(),w.authenticationSessionId(),w.issuedAt(),w.expiresAt(),w.kind(),w.eventId(),w.passwordAuthenticatedAt())
        when: store.stage(ACTOR,request)
        then: def failure=thrown(Failure);failure.error()==LoginProof.Error.CONFLICT
        count('sso_code_intents')==0;count('sso_login_proofs')==0;count('sessions')==0
        new PostgresAuthStore(database,false).requireBankingAccess(legacy)==null
    }
    def 'retained tombstones contain keyed hashes and no subject session event or recipient text'(){
        given: def request=input();store.stage(ACTOR,request)
        expect:
        database.transaction{sql->
            def json=sql.fetchOne('select row_to_json(t)::text from sso_code_intents t').get(0,String)
            assert !json.contains(request.subject())
            assert !json.contains(request.userSessionId())
            assert !json.contains(request.authenticationSessionId())
            assert !json.contains(request.eventId().toString())
            assert !json.contains('synthetic@example.test')
            def row=sql.fetchOne('select actor_hash from sso_code_intents')
            assert Arrays.equals(row.get(0,byte[]),new CodeIntentRetentionKeys('original',['original':key]).hash('original','armoney-intent-actor-v1',ACTOR))
            true
        }
    }

    def 'reuse is rejected rather than treated as a staged interactive event'(){
        given:
        def w=input()
        def reuse=new Write(w.commandId(),w.codeDigest(),w.codeKeyId(),w.issuer(),w.subject(),w.clientId(),w.userSessionId(),w.authenticationSessionId(),w.issuedAt(),w.expiresAt(),Kind.REUSE,null,null)
        when: store.stage(ACTOR,reuse)
        then: def failure=thrown(Failure);failure.error()==LoginProof.Error.CONFLICT
        count('sso_code_intents')==0;count('sso_login_events')==0
    }
    def 'staged intent does not allow managed banking with either enforcement flag #enabled'(){
        given: store.stage(ACTOR,input())
        when: new PostgresAuthStore(database,enabled).requireBankingAccess(identity)
        then: thrown(RuntimeException)
        count('sessions')==0;count('sso_login_proofs')==0
        where: enabled << [false,true]
    }
    def 'final expiry during insertion rolls back intent detail aliases and hourly charge'(){
        given:
        def w=input()
        def shortLived=new Write(w.commandId(),w.codeDigest(),w.codeKeyId(),w.issuer(),w.subject(),w.clientId(),w.userSessionId(),w.authenticationSessionId(),w.issuedAt(),w.issuedAt().plusSeconds(2),w.kind(),w.eventId(),w.passwordAuthenticatedAt())
        database.transaction{sql->
            sql.execute('create function qa_intent_delay() returns trigger language plpgsql as $$ begin perform pg_sleep(3); return NEW; end $$')
            sql.execute('create trigger qa_intent_delay after insert on sso_code_intent_commands for each row execute function qa_intent_delay()')
        }
        when: store.stage(ACTOR,shortLived)
        then: def failure=thrown(Failure);failure.error()==LoginProof.Error.EXPIRED
        count('sso_code_intents')==0;count('sso_code_intent_details')==0;count('sso_code_intent_commands')==0;count('sso_login_creation_reservations')==0
    }
    def 'exact stage replay after original fresh cutoff retains its original admission and expiry'(){
        given:
        def w=input();def issued=w.issuedAt().minusSeconds(27)
        def request=new Write(w.commandId(),w.codeDigest(),w.codeKeyId(),w.issuer(),w.subject(),w.clientId(),w.userSessionId(),w.authenticationSessionId(),issued,issued.plusSeconds(120),w.kind(),w.eventId(),issued)
        def first=store.stage(ACTOR,request)
        def original=database.transaction{sql->sql.fetch('select * from sso_code_intents').intoMaps()}
        Thread.sleep(3200)
        assert Instant.now().isAfter(request.issuedAt().plusSeconds(30))
        when: def replay=store.stage(ACTOR,request)
        then: replay==first
        database.transaction{sql->sql.fetch('select * from sso_code_intents').intoMaps()}==original
        count('sso_login_creation_reservations')==1
    }
    def 'control lock waiter rechecks original expiry after actual PostgreSQL lock wait'(){
        given:
        def w=input()
        def request=new Write(w.commandId(),w.codeDigest(),w.codeKeyId(),w.issuer(),w.subject(),w.clientId(),w.userSessionId(),w.authenticationSessionId(),w.issuedAt(),w.issuedAt().plusSeconds(3),w.kind(),w.eventId(),w.passwordAuthenticatedAt())
        def held=new CountDownLatch(1);def release=new CountDownLatch(1)
        def pool=Executors.newFixedThreadPool(2)
        def blocker=pool.submit({database.transaction{sql->sql.fetchOne('select id from verification_control where id=1 for update');held.countDown();assert release.await(15,TimeUnit.SECONDS);true}} as Callable)
        assert held.await(10,TimeUnit.SECONDS)
        def pending=pool.submit({try{store.stage(ACTOR,request)}catch(Failure failure){failure.error()}} as Callable)
        boolean waiting=false
        for(int n=0;n<100&&!waiting;n++){
            waiting=database.transaction{sql->sql.fetchOne("select count(*) from pg_stat_activity where datname=current_database() and wait_event_type='Lock'").get(0,Integer)>0}
            if(!waiting)Thread.sleep(50)
        }
        assert waiting
        Thread.sleep(3100)
        when: release.countDown();blocker.get(10,TimeUnit.SECONDS);def outcome=pending.get(10,TimeUnit.SECONDS)
        then: outcome==LoginProof.Error.EXPIRED
        count('sso_code_intents')==0;count('sso_login_creation_reservations')==0
        cleanup: release.countDown();pool.shutdownNow()
    }

    def 'stage and legacy proof share twenty requests then reject the interleaved twenty first #last'(){
        given:
        def proof=new PostgresLoginProofStore(database,policy,([9]*32) as byte[],({sql->null} as java.util.function.Function))
        database.transaction{sql->
            sql.execute('''create function qa_freeze_refill() returns trigger language plpgsql as $$ begin NEW.refilled_at='2099-01-01T00:00:00Z'; return NEW; end $$''' )
            sql.execute('create trigger qa_freeze_refill before update on sso_login_proof_admission for each row execute function qa_freeze_refill()')
        }
        def request=input()
        (1..20).each{n->if(n%2==1)store.stage(ACTOR,request);else proof.admit(ACTOR)}
        when:
        if(last=='stage')store.stage(ACTOR,request);else proof.admit(ACTOR)
        then:
        def failure=thrown(Failure);failure.error()==LoginProof.Error.RATE_LIMITED
        count('sso_code_intents')==1;count('sso_login_creation_reservations')==1;count('sso_login_proofs')==0
        where: last << ['stage','proof']
    }
    def 'first stage after admission cutoff is rejected even while original code remains valid'(){
        given:
        def w=input();def issued=w.issuedAt().minusSeconds(31)
        def request=new Write(w.commandId(),w.codeDigest(),w.codeKeyId(),w.issuer(),w.subject(),w.clientId(),w.userSessionId(),w.authenticationSessionId(),issued,issued.plusSeconds(120),w.kind(),w.eventId(),issued)
        when: store.stage(ACTOR,request)
        then: def failure=thrown(Failure);failure.error()==LoginProof.Error.EXPIRED
        count('sso_code_intents')==0;count('sso_login_creation_reservations')==0
    }
    def 'cleanup really waits for stage control and fails at its local one second lock timeout'(){
        given:
        def held=new CountDownLatch(1);def release=new CountDownLatch(1)
        def pool=Executors.newFixedThreadPool(2)
        def blocker=pool.submit({database.transaction{sql->sql.fetchOne('select id from verification_control where id=1 for update');held.countDown();assert release.await(10,TimeUnit.SECONDS);true}} as Callable)
        assert held.await(5,TimeUnit.SECONDS)
        def pending=pool.submit({store.cleanup()} as Callable)
        boolean waiting=false
        for(int n=0;n<15&&!waiting;n++){
            waiting=database.transaction{sql->sql.fetchOne("select count(*) from pg_stat_activity where datname=current_database() and wait_event_type='Lock'").get(0,Integer)>0}
            if(!waiting)Thread.sleep(25)
        }
        assert waiting
        when: pending.get(5,TimeUnit.SECONDS)
        then:
        def failure=thrown(ExecutionException)
        def causes=[];for(Throwable cause=failure;cause!=null;cause=cause.cause)causes.add(cause)
        causes.any{it instanceof java.sql.SQLException && it.SQLState=='55P03'}
        cleanup: release.countDown();blocker?.get(5,TimeUnit.SECONDS);pool.shutdownNow()
    }

    def 'hourly shared thousand cap rejects new stage but exact replay is not charged'(){
        given:
        def request=input();def first=store.stage(ACTOR,request)
        database.transaction{sql->sql.execute("insert into sso_login_creation_reservations select gen_random_uuid(),'INTENT',clock_timestamp() from generate_series(1,999)")}
        when: store.stage(ACTOR,input(UUID.randomUUID(),'b'*64))
        then: def failure=thrown(Failure);failure.error()==LoginProof.Error.RATE_LIMITED
        when: def replay=store.stage(ACTOR,request)
        then: replay==first;count('sso_login_creation_reservations')==1000;count('sso_code_intents')==1
    }
    def 'global intent table cap refuses new creation without purging replay protection'(){
        given:
        def request=input();def first=store.stage(ACTOR,request)
        database.transaction{sql->sql.execute("""insert into sso_code_intents
            select gen_random_uuid(),lpad(to_hex(n),64,'0'),'code1','original',decode(repeat('00',32),'hex'),
              decode(repeat('00',32),'hex'),decode(repeat('00',32),'hex'),decode(repeat('00',32),'hex'),
              statement_timestamp(),statement_timestamp()+interval '120 seconds',statement_timestamp(),
              statement_timestamp()+interval '30 seconds',statement_timestamp()+interval '120 seconds 7 days'
            from generate_series(1,99999) n""")}
        when: store.stage(ACTOR,input(UUID.randomUUID(),'b'*64))
        then: def failure=thrown(Failure);failure.error()==LoginProof.Error.RATE_LIMITED
        when: def replay=store.stage(ACTOR,request)
        then: replay==first;count('sso_code_intents')==100000
    }
    def 'stage command capacity cannot be bypassed by a new alias but exact replay remains available'(){
        given:
        def request=input();def first=store.stage(ACTOR,request)
        database.transaction{sql->
            sql.execute("""insert into sso_code_intents
                select gen_random_uuid(),lpad(to_hex(n),64,'0'),'code1','original',decode(repeat('00',32),'hex'),
                  decode(repeat('00',32),'hex'),decode(repeat('00',32),'hex'),decode(repeat('00',32),'hex'),
                  statement_timestamp(),statement_timestamp()+interval '120 seconds',statement_timestamp(),
                  statement_timestamp()+interval '30 seconds',statement_timestamp()+interval '120 seconds 7 days'
                from generate_series(1,25000) n""")
            sql.execute("""insert into sso_code_intent_commands
                select decode(repeat('00',32),'hex'),gen_random_uuid(),i.id,s
                from sso_code_intents i cross join generate_series(1,4) s where i.code_digest<>? limit 99999""",request.codeDigest())
        }
        when: store.stage(ACTOR,command(request,UUID.randomUUID()))
        then: def failure=thrown(Failure);failure.error()==LoginProof.Error.RATE_LIMITED
        when: def replay=store.stage(ACTOR,request)
        then: replay==first;count('sso_code_intent_commands')==100000
    }
    def 'eighty percent capacity is an alert and does not silently reduce the creation cap'(){
        given:
        database.transaction{sql->sql.execute("""insert into sso_code_intents
            select gen_random_uuid(),lpad(to_hex(n),64,'0'),'code1','original',decode(repeat('00',32),'hex'),
              decode(repeat('00',32),'hex'),decode(repeat('00',32),'hex'),decode(repeat('00',32),'hex'),
              statement_timestamp(),statement_timestamp()+interval '120 seconds',statement_timestamp(),
              statement_timestamp()+interval '30 seconds',statement_timestamp()+interval '120 seconds 7 days'
            from generate_series(1,80000) n""")}
        store.cleanup()
        when: def result=store.stage(ACTOR,input())
        then:
        result.state()=='STAGED';count('sso_code_intents')==80001
        database.transaction{sql->def row=sql.fetchOne('select healthy,capacity_alert from sso_code_intent_cleanup');row.get('healthy',Boolean)&&row.get('capacity_alert',Boolean)}
    }
    def 'bounded cleanup progresses across wakes and reports overdue retained tombstones'(){
        given:
        database.transaction{sql->
            sql.execute("""insert into sso_code_intents
                select gen_random_uuid(),lpad(to_hex(n),64,'0'),'code1','original',decode(repeat('00',32),'hex'),
                  decode(repeat('00',32),'hex'),decode(repeat('00',32),'hex'),decode(repeat('00',32),'hex'),
                  statement_timestamp()-interval '8 days',statement_timestamp()-interval '8 days'+interval '120 seconds',
                  statement_timestamp()-interval '8 days',statement_timestamp()-interval '8 days'+interval '30 seconds',
                  statement_timestamp()-interval '1 day'+interval '120 seconds'
                from generate_series(1,301) n""")
            sql.execute("""insert into sso_code_intent_commands select decode(repeat('00',32),'hex'),gen_random_uuid(),i.id,s
                from sso_code_intents i cross join generate_series(1,4) s""")
        }
        when: store.cleanup()
        then:
        count('sso_code_intents')==51;count('sso_code_intent_commands')==204
        !database.transaction{sql->sql.fetchOne('select healthy from sso_code_intent_cleanup').get(0,Boolean)}
        when: store.cleanup()
        then:
        count('sso_code_intents')==0;count('sso_code_intent_commands')==0
        database.transaction{sql->sql.fetchOne('select healthy from sso_code_intent_cleanup').get(0,Boolean)}
    }

    def 'cleanup deletes expired details separately and tombstones only after original seven days'(){
        given:
        def request=input();store.stage(ACTOR,request)
        database.transaction{sql->
            sql.execute('alter table sso_code_intents disable trigger sso_code_intent_no_change')
            sql.execute('alter table sso_code_intent_details disable trigger sso_code_intent_detail_no_change')
            sql.execute("""update sso_code_intents set issued_at=statement_timestamp()-interval '10 minutes',
                expires_at=statement_timestamp()-interval '8 minutes',stage_cutoff=statement_timestamp()-interval '9 minutes 30 seconds',
                retained_until=statement_timestamp()+interval '7 days'-interval '8 minutes'""")
            sql.execute("update sso_code_intent_details set erase_at=clock_timestamp()-interval '3 minutes'")
            sql.execute('alter table sso_code_intents enable trigger sso_code_intent_no_change')
            sql.execute('alter table sso_code_intent_details enable trigger sso_code_intent_detail_no_change')
        }
        when: store.cleanup()
        then: count('sso_code_intent_details')==0;count('sso_code_intents')==1;count('sso_code_intent_commands')==1
        when:
        database.transaction{sql->
            sql.execute('alter table sso_code_intents disable trigger sso_code_intent_no_change')
            sql.execute("""update sso_code_intents set issued_at=statement_timestamp()-interval '8 days',
                expires_at=statement_timestamp()-interval '8 days'+interval '120 seconds',stage_cutoff=statement_timestamp()-interval '8 days'+interval '30 seconds',
                retained_until=statement_timestamp()-interval '1 day'+interval '120 seconds'""")
            sql.execute('alter table sso_code_intents enable trigger sso_code_intent_no_change')
        }
        store.cleanup()
        then: count('sso_code_intents')==0;count('sso_code_intent_commands')==0
    }

    def 'retained records reject real updates and premature deletes #table'(){
        given: store.stage(ACTOR,input())
        when: database.transaction{sql->sql.execute('update '+table+' set '+field+'='+field)}
        then: thrown(RuntimeException)
        when: database.transaction{sql->sql.execute('update '+table+' set '+field+'='+mutation)}
        then: thrown(RuntimeException)
        when: database.transaction{sql->sql.execute('delete from '+table)}
        then: thrown(RuntimeException)
        where:
        table | field | mutation
        'sso_code_intents' | 'code_key_id' | "'changed'"
        'sso_code_intent_details' | 'event_id' | 'gen_random_uuid()'
        'sso_code_intent_commands' | 'command_id' | 'gen_random_uuid()'
    }
}
