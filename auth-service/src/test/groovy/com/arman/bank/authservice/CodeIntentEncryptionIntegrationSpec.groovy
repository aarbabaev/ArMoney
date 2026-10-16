package com.arman.bank.authservice

import com.arman.bank.authservice.domain.LoginProof
import com.arman.bank.authservice.infrastructure.*
import com.arman.bank.runtime.Database
import org.testcontainers.containers.PostgreSQLContainer
import spock.lang.Specification
import java.time.*
import java.util.concurrent.*

/** Real PostgreSQL/AEAD. This is opaque detail reconstruction, never authority confirmation. */
class CodeIntentEncryptionIntegrationSpec extends Specification {
    static final String ISSUER='https://issuer.example/realm', ACTOR='provider', CLIENT='armoney-web'
    PostgreSQLContainer postgres
    Database database
    UUID identity=UUID.randomUUID(),enrollment=UUID.randomUUID()
    def policy=LoginProof.Policy.defaults([ISSUER] as Set,[CLIENT] as Set,['code'] as Set,[ACTOR] as Set)
    static String key(int value){Base64.encoder.encodeToString(([value]*32) as byte[])}
    def retention=new CodeIntentRetentionKeys('retention',['retention':key(1)])
    def crypto=new CodeIntentDetailCrypto('aead',['aead':key(2)],[] as Set)
    PostgresCodeIntentStore store
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
        store=new PostgresCodeIntentStore(database,policy,retention,crypto);store.cleanup()
    }
    def cleanup(){database?.close();postgres?.stop()}
    LoginProof.Write input(){
        def now=Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS)
        new LoginProof.Write(UUID.randomUUID(),UUID.randomUUID().toString().replace('-','')*2,'code',ISSUER,'managed-subject',CLIENT,'user-session','opaque-original-auth-session',now,now.plusSeconds(120),LoginProof.Kind.INTERACTIVE,UUID.randomUUID(),now)
    }
    LoginProof.Resolve binding(LoginProof.Write w){new LoginProof.Resolve(w.codeDigest(),w.codeKeyId(),w.issuer(),w.subject(),w.clientId(),w.userSessionId())}
    LoginProof.Write reconstruct(LoginProof.Write request,PostgresCodeIntentStore selected=store){database.transaction{sql->PostgresVerificationProjectionStore.lockControls(sql);selected.reconstructInTransaction(sql,ACTOR,binding(request))}}
    long count(String table){database.transaction{sql->sql.fetchOne('select count(*) from '+table).get(0,Long)}}
    Map detail(){database.transaction{sql->sql.fetchOne('select * from sso_code_intent_details').intoMap().collectEntries{k,v->[(k):(v instanceof byte[] ? HexFormat.of().formatHex((byte[])v) : v)]}}}
    boolean waiting(String event){database.transaction{sql->sql.fetchOne('select count(*) from pg_stat_activity where datname=current_database() and wait_event=?',event).get(0,Integer)>0}}
    void awaitWait(String event){boolean found=false;for(int n=0;n<80&&!found;n++){found=waiting(event);if(!found)Thread.sleep(25)};assert found}
    long encryptionCount(CodeIntentDetailCrypto selected){def method=CodeIntentDetailCrypto.getDeclaredMethod('encryptionCount');method.accessible=true;((Number)method.invoke(selected)).longValue()}
    def 'genuine reconstruction preserves all original thirteen fields and original first alias'(){
        given: def request=input();def first=store.stage(ACTOR,request);def original=detail()
        when:
        def alias=new LoginProof.Write(UUID.randomUUID(),request.codeDigest(),request.codeKeyId(),request.issuer(),request.subject(),request.clientId(),request.userSessionId(),request.authenticationSessionId(),request.issuedAt(),request.expiresAt(),request.kind(),request.eventId(),request.passwordAuthenticatedAt())
        def replay=store.stage(ACTOR,alias)
        def reconstructed=reconstruct(request)
        then:
        reconstructed==request
        Arrays.equals(LoginProof.payloadHash(reconstructed),LoginProof.payloadHash(request))
        first==replay;detail()==original;count('sso_code_intent_nonces')==1
        count('sso_login_proofs')==0;count('sso_login_events')==0;count('sessions')==0
        !new String(HexFormat.of().parseHex(original.ciphertext),java.nio.charset.StandardCharsets.ISO_8859_1).contains(request.authenticationSessionId())
    }
    def 'replay final post AEAD check rejects #fault and rolls new alias back'(){
        given:
        def source=input()
        def request=new LoginProof.Write(source.commandId(),source.codeDigest(),source.codeKeyId(),source.issuer(),source.subject(),source.clientId(),source.userSessionId(),source.authenticationSessionId(),source.issuedAt(),source.issuedAt().plusSeconds(fault=='deadline' ? 5 : 120),source.kind(),source.eventId(),source.passwordAuthenticatedAt())
        store.stage(ACTOR,request)
        String mutation=fault=='deadline' ? 'perform pg_sleep(6);' : 'update verification_authorities set verification_epoch=verification_epoch+1;'
        database.transaction{sql->
            sql.execute('create sequence qa_replay_delay_seen')
            sql.execute("""create function qa_replay_cipher_delay(value bytea) returns bytea language plpgsql as \$\$ begin
                if current_query() like 'select * from sso_code_intent_details where%' then
                    perform nextval('qa_replay_delay_seen');${mutation}
                end if;return value;end \$\$""")
            sql.execute('alter table sso_code_intent_details rename to qa_original_details')
            sql.execute("""create view sso_code_intent_details as select intent_id,event_id,password_authenticated_at,erase_at,
                encryption_key_id,encryption_generation,nonce,qa_replay_cipher_delay(ciphertext) as ciphertext from qa_original_details""")
        }
        def replay=new LoginProof.Write(UUID.randomUUID(),request.codeDigest(),request.codeKeyId(),request.issuer(),request.subject(),request.clientId(),request.userSessionId(),request.authenticationSessionId(),request.issuedAt(),request.expiresAt(),request.kind(),request.eventId(),request.passwordAuthenticatedAt())
        when: store.stage(ACTOR,replay)
        then:
        def failure=thrown(LoginProof.Failure);failure.error()==expected
        database.transaction{sql->sql.fetchOne('select is_called from qa_replay_delay_seen').get(0,Boolean)}
        count('sso_code_intent_commands')==1;count('sso_code_intents')==1;count('sso_code_intent_nonces')==1
        database.transaction{sql->sql.fetchOne('select verification_epoch from verification_authorities').get(0,Long)}==1
        where:
        fault | expected
        'deadline' | LoginProof.Error.EXPIRED
        'authority stamp' | LoginProof.Error.CONFLICT
    }
    def 'three deterministic nonce collisions deny without ciphertext intent or creation charge'(){
        given:
        database.transaction{sql->sql.execute('insert into sso_code_intent_nonces values(?,?)','aead',new byte[12])}
        def draws=new java.util.concurrent.atomic.AtomicInteger()
        def fixed=new java.security.SecureRandom(){@Override void nextBytes(byte[] bytes){draws.incrementAndGet();Arrays.fill(bytes,(byte)0)}}
        def constructor=CodeIntentDetailCrypto.getDeclaredConstructor(String,Map,Set,java.security.SecureRandom)
        constructor.accessible=true
        def deterministic=constructor.newInstance('aead',['aead':key(2)],[] as Set,fixed)
        def selected=new PostgresCodeIntentStore(database,policy,retention,deterministic)
        when: selected.stage(ACTOR,input())
        then:
        def failure=thrown(LoginProof.Failure);failure.error()==LoginProof.Error.UNAVAILABLE
        draws.get()==3;encryptionCount(deterministic)==0;count('sso_code_intent_nonces')==1;count('sso_code_intent_details')==0
        count('sso_code_intents')==0;count('sso_login_creation_reservations')==0
    }
    def 'concurrent first stage selects one ciphertext first alias and hourly charge'(){
        given:
        def request=input()
        def other=new LoginProof.Write(UUID.randomUUID(),request.codeDigest(),request.codeKeyId(),request.issuer(),request.subject(),request.clientId(),request.userSessionId(),request.authenticationSessionId(),request.issuedAt(),request.expiresAt(),request.kind(),request.eventId(),request.passwordAuthenticatedAt())
        def start=new CountDownLatch(1);def pool=Executors.newFixedThreadPool(2)
        def second=new PostgresCodeIntentStore(database,policy,retention,new CodeIntentDetailCrypto('aead',['aead':key(2)],[] as Set))
        def futures=[[store,request],[second,other]].collect{pair->pool.submit({assert start.await(10,TimeUnit.SECONDS);pair[0].stage(ACTOR,pair[1])} as Callable)}
        when: start.countDown();def results=futures.collect{it.get(15,TimeUnit.SECONDS)}
        then:
        results[0]==results[1];count('sso_code_intents')==1;count('sso_code_intent_details')==1
        count('sso_code_intent_commands')==2;count('sso_login_creation_reservations')==1
        def original=database.transaction{sql->sql.fetchOne('select command_id from sso_code_intent_commands where alias_slot=1').get(0,UUID)}
        original in [request.commandId(),other.commandId()]
        reconstruct(request).commandId()==original
        when: def ciphertext=detail();store.stage(ACTOR,request);second.stage(ACTOR,other)
        then: detail()==ciphertext;count('sso_login_creation_reservations')==1
        cleanup: start.countDown();pool.shutdownNow()
    }
    def 'cross row ciphertext or parent AAD swap cannot reconstruct #target'(){
        given:
        def first=input();def second=input()
        def firstResult=store.stage(ACTOR,first);def secondResult=store.stage(ACTOR,second)
        database.transaction{sql->
            if(target=='ciphertext'){
                sql.execute('alter table sso_code_intent_details disable trigger sso_code_intent_detail_no_change')
                sql.execute('update sso_code_intent_details set ciphertext=(select ciphertext from sso_code_intent_details where intent_id=?) where intent_id=?',secondResult.intentId(),firstResult.intentId())
                sql.execute('alter table sso_code_intent_details enable trigger sso_code_intent_detail_no_change')
            }else{
                sql.execute('alter table sso_code_intents disable trigger sso_code_intent_no_change')
                sql.execute('update sso_code_intents set payload_hash=(select payload_hash from sso_code_intents where id=?) where id=?',secondResult.intentId(),firstResult.intentId())
                sql.execute('alter table sso_code_intents enable trigger sso_code_intent_no_change')
            }
        }
        when: reconstruct(first)
        then: def failure=thrown(LoginProof.Failure);failure.error()==LoginProof.Error.UNAVAILABLE
        count('sso_login_proofs')==0;count('sso_login_events')==0;count('sso_code_intent_details')==2
        where: target << ['ciphertext','parent-aad']
    }

    def 'actual committed reservation fault injection #mode burns nonce with no encryption or intent'(){
        given:
        def driver=new NonceCommitFaultDriver(mode)
        java.sql.DriverManager.registerDriver(driver)
        def faultDatabase=new Database(driver.prefix+postgres.jdbcUrl,postgres.username,postgres.password)
        def selectedCrypto=new CodeIntentDetailCrypto('aead',['aead':key(2)],[] as Set)
        def selected=new PostgresCodeIntentStore(faultDatabase,policy,retention,selectedCrypto)
        driver.armed.set(true)
        when: selected.stage(ACTOR,input())
        then:
        def failure=thrown(LoginProof.Failure);failure.error()==LoginProof.Error.UNAVAILABLE
        boolean interrupted=Thread.interrupted()
        interrupted==(mode=='cancel-after-ack')
        driver.fired.get()==1;driver.phase.get()=='NONCE_COMMIT'
        encryptionCount(selectedCrypto)==0
        count('sso_code_intent_nonces')==1;count('sso_code_intent_details')==0
        count('sso_code_intents')==0;count('sso_login_creation_reservations')==0
        cleanup:
        Thread.interrupted() // Preserve assertion above, then clear this synthetic test-only interruption.
        faultDatabase?.close();java.sql.DriverManager.deregisterDriver(driver)
        where: mode << ['lost-ack','cancel-after-ack']
    }

    def 'final intent rollback permanently burns acknowledged nonce reservation'(){
        given:
        database.transaction{sql->
            sql.execute("create function qa_reject_detail() returns trigger language plpgsql as \$\$ begin raise exception 'synthetic fault'; end \$\$")
            sql.execute('create trigger qa_reject_detail before insert on sso_code_intent_details for each row execute function qa_reject_detail()')
        }
        when: store.stage(ACTOR,input())
        then: thrown(RuntimeException)
        count('sso_code_intent_nonces')==1;count('sso_code_intents')==0;count('sso_code_intent_commands')==0;count('sso_login_creation_reservations')==0
    }
    def 'swapping authenticated detail metadata never reconstructs or repairs #change'(){
        given:
        def request=input();store.stage(ACTOR,request)
        database.transaction{sql->
            sql.execute('alter table sso_code_intent_details disable trigger sso_code_intent_detail_no_change')
            sql.execute(change)
            sql.execute('alter table sso_code_intent_details enable trigger sso_code_intent_detail_no_change')
        }
        when: reconstruct(request)
        then: def failure=thrown(LoginProof.Failure);failure.error()==LoginProof.Error.UNAVAILABLE
        count('sso_login_proofs')==0;count('sso_login_events')==0
        where:
        change << ["update sso_code_intent_details set ciphertext=set_byte(ciphertext,0,get_byte(ciphertext,0)#1)",
                   "update sso_code_intent_details set event_id=gen_random_uuid()",
                   "update sso_code_intent_details set password_authenticated_at=password_authenticated_at-interval '1 second'",
                   "update sso_code_intent_details set encryption_generation=encryption_generation+1"]
    }
    def 'nonce registry uniqueness survives independent store instances and restart'(){
        given: def request=input();store.stage(ACTOR,request);def row=detail()
        when: database.transaction{sql->sql.execute("insert into sso_code_intent_nonces values(?,decode(?,'hex'))",row.encryption_key_id,row.nonce)}
        then: thrown(RuntimeException)
        when:
        def restarted=new PostgresCodeIntentStore(database,policy,retention,new CodeIntentDetailCrypto('aead',['aead':key(2)],[] as Set))
        def replay=restarted.stage(ACTOR,request)
        then: replay.state()=='STAGED';count('sso_code_intent_nonces')==1;detail()==row
    }
    def 'nonce per key capacity fails closed without allocating an intent'(){
        given:
        database.transaction{sql->sql.execute("insert into sso_code_intent_nonces select 'aead',decode(lpad(to_hex(n),24,'0'),'hex') from generate_series(1,100000)n")}
        when: store.stage(ACTOR,input())
        then: def failure=thrown(LoginProof.Failure);failure.error()==LoginProof.Error.UNAVAILABLE
        count('sso_code_intent_nonces')==100000;count('sso_code_intents')==0
    }
    def 'rotation preserves original ciphertext and missing original key never reconstructs a substitute'(){
        given:
        def request=input();store.stage(ACTOR,request);def original=detail()
        def next=new CodeIntentDetailCrypto('next',['aead':key(2),'next':key(3)],[] as Set)
        def rotated=new PostgresCodeIntentStore(database,policy,retention,next)
        expect: rotated.stage(ACTOR,request).state()=='STAGED';detail()==original;reconstruct(request,rotated)==request
        when: reconstruct(request,new PostgresCodeIntentStore(database,policy,retention,new CodeIntentDetailCrypto('next',['next':key(3)],[] as Set)))
        then: def failure=thrown(LoginProof.Failure);failure.error()==LoginProof.Error.UNAVAILABLE
        detail()==original;count('sso_code_intent_nonces')==1
    }
    def 'retired key with actual overdue detail retains nonce until purge then forbids key ID or material resurrection'(){
        given:
        def request=input();store.stage(ACTOR,request)
        def retired=new CodeIntentDetailCrypto('next',['aead':key(2),'next':key(3)],['aead'] as Set)
        def rotated=new PostgresCodeIntentStore(database,policy,retention,retired)
        rotated.cleanup()
        expect: count('sso_code_intent_nonces')==1;count('sso_code_intent_details')==1
        when: reconstruct(request,rotated)
        then: def failure=thrown(LoginProof.Failure);failure.error()==LoginProof.Error.UNAVAILABLE
        when:
        database.transaction{sql->
            sql.execute('alter table sso_code_intent_details disable trigger sso_code_intent_detail_no_change')
            sql.execute("update sso_code_intent_details set erase_at=clock_timestamp()-interval '10 minutes'")
            sql.execute('alter table sso_code_intent_details enable trigger sso_code_intent_detail_no_change')
        }
        rotated.cleanup()
        then: count('sso_code_intent_nonces')==0;count('sso_code_intent_details')==0;count('sso_code_intent_encryption_keys')==2
        when:
        database.transaction{sql->PostgresVerificationProjectionStore.lockControls(sql);new PostgresCodeIntentKeyCatalog(new CodeIntentDetailCrypto('relabeled',['relabeled':key(2)],[] as Set)).synchronize(sql,false)}
        then: def reused=thrown(LoginProof.Failure);reused.error()==LoginProof.Error.UNAVAILABLE
        when:
        database.transaction{sql->PostgresVerificationProjectionStore.lockControls(sql);new PostgresCodeIntentKeyCatalog(new CodeIntentDetailCrypto('aead',['aead':key(4)],[] as Set)).synchronize(sql,false)}
        then: def changed=thrown(LoginProof.Failure);changed.error()==LoginProof.Error.UNAVAILABLE
        count('sso_code_intent_encryption_keys')==2;count('sso_code_intent_nonces')==0
    }
    def 'permanent catalog hard cap cannot be cleared to admit a new key'(){
        given:
        database.transaction{sql->sql.execute("""insert into sso_code_intent_encryption_keys
            select 'historical-'||n,decode(md5(n::text)||md5('key-'||n),'hex'),'RETIRED',2 from generate_series(1,1023)n""")}
        def next=new PostgresCodeIntentStore(database,policy,retention,new CodeIntentDetailCrypto('next',['next':key(3)],[] as Set))
        when: next.stage(ACTOR,input())
        then: def failure=thrown(LoginProof.Failure);failure.error()==LoginProof.Error.UNAVAILABLE
        count('sso_code_intent_encryption_keys')==1024;count('sso_code_intent_nonces')==0;count('sso_code_intents')==0
        when: database.transaction{sql->sql.execute('delete from sso_code_intent_encryption_keys')}
        then: thrown(RuntimeException)
    }

    def 'retirement queued behind committed reservation fences the resumed final insertion'(){
        given:
        database.transaction{sql->
            sql.execute('''create function qa_pause_reservation() returns trigger language plpgsql as $$ begin perform pg_sleep(2); return NEW; end $$''')
            sql.execute('create trigger qa_pause_reservation after insert on sso_code_intent_nonces for each row execute function qa_pause_reservation()')
        }
        def pool=Executors.newFixedThreadPool(2)
        def pending=pool.submit({try{store.stage(ACTOR,input())}catch(LoginProof.Failure rejected){rejected.error()}} as Callable)
        awaitWait('PgSleep')
        def rotated=new CodeIntentDetailCrypto('next',['aead':key(2),'next':key(3)],['aead'] as Set)
        def retire=pool.submit({database.transaction{sql->PostgresVerificationProjectionStore.lockControls(sql);new PostgresCodeIntentKeyCatalog(rotated).synchronize(sql,false);true}} as Callable)
        awaitWait('transactionid')
        when: def result=pending.get(10,TimeUnit.SECONDS);retire.get(10,TimeUnit.SECONDS)
        then:
        result==LoginProof.Error.UNAVAILABLE
        count('sso_code_intent_nonces')==1;count('sso_code_intent_details')==0;count('sso_code_intents')==0
        database.transaction{sql->sql.fetchOne('select state from sso_code_intent_encryption_keys where key_id=?','aead').get(0,String)}=='RETIRED'
        cleanup: pool.shutdownNow()
    }
    def 'entire stage retains one backend even with four of five pool connections occupied'(){
        given:
        database.transaction{sql->
            sql.execute('create table qa_phase_backends(phase text,pid integer)')
            sql.execute('''create function qa_backend_trace() returns trigger language plpgsql as $$ begin insert into qa_phase_backends values(TG_TABLE_NAME,pg_backend_pid()); return NEW; end $$''')
            ['sso_login_proof_admission','sso_code_intent_nonces','sso_code_intent_details'].each{table->sql.execute('create trigger qa_backend_trace after insert on '+table+' for each row execute function qa_backend_trace()')}
        }
        def occupied=new CountDownLatch(4);def release=new CountDownLatch(1);def pool=Executors.newFixedThreadPool(5)
        def holders=(1..4).collect{pool.submit({database.withSession{session->occupied.countDown();assert release.await(15,TimeUnit.SECONDS);true}} as Callable)}
        assert occupied.await(10,TimeUnit.SECONDS)
        when: def outcome=pool.submit({store.stage(ACTOR,input())} as Callable).get(10,TimeUnit.SECONDS)
        then: outcome.state()=='STAGED'
        when: release.countDown();holders.each{it.get(5,TimeUnit.SECONDS)}
        then:
        database.transaction{sql->
            assert sql.fetchOne('select count(distinct pid) from qa_phase_backends').get(0,Integer)==1
            assert sql.fetchOne('select count(distinct phase) from qa_phase_backends').get(0,Integer)==3
            true
        }
        cleanup: release.countDown();pool.shutdownNow()
    }
    /** Test-only injection after REAL PostgreSQL COMMIT, never a simulated wire/protocol failure. */
    static class NonceCommitFaultDriver implements java.sql.Driver {
        final String prefix='jdbc:qa-code-intent:'+UUID.randomUUID()+':'
        final String mode
        final java.util.concurrent.atomic.AtomicBoolean armed=new java.util.concurrent.atomic.AtomicBoolean(false)
        final java.util.concurrent.atomic.AtomicInteger fired=new java.util.concurrent.atomic.AtomicInteger()
        final java.util.concurrent.atomic.AtomicReference<String> phase=new java.util.concurrent.atomic.AtomicReference<>()
        NonceCommitFaultDriver(String mode){this.mode=mode}
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
                            def rows=statement.executeQuery('select exists(select 1 from sso_code_intent_nonces) as nonce,exists(select 1 from sso_code_intents) as intent')
                            try{assert rows.next();failCommit=rows.getBoolean('nonce')&&!rows.getBoolean('intent')&&armed.compareAndSet(true,false)}finally{rows.close()}
                        }finally{statement.close()}
                    }
                    Object result=method.invoke(physical,arguments)
                    if(failCommit){
                        phase.set('NONCE_COMMIT');fired.incrementAndGet()
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
        @Override java.util.logging.Logger getParentLogger(){java.util.logging.Logger.getLogger('qa-code-intent-fault')}
    }

}
