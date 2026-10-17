package com.arman.bank.authservice.infrastructure

import com.arman.bank.authservice.domain.CodeIntentKeyLookup as K
import com.arman.bank.authservice.domain.LoginProof
import com.arman.bank.runtime.Database
import org.testcontainers.containers.PostgreSQLContainer
import spock.lang.Specification
import java.time.*

/** Disposable PostgreSQL metadata fixtures only. No provider, eligible authority or HTTP installation. */
class CodeIntentKeyLookupIntegrationSpec extends Specification {
    static final String ISSUER='https://issuer.example/realm'
    static final String ACTOR='fixture-provider'
    PostgreSQLContainer postgres
    Database database
    CodeIntentRetentionKeys keys
    def setup(){
        postgres=new PostgreSQLContainer('postgres:17.6-alpine');postgres.start()
        database=new Database(postgres.jdbcUrl,postgres.username,postgres.password)
        keys=new CodeIntentRetentionKeys('retained',['retained':Base64.encoder.encodeToString(([21]*32) as byte[])])
    }
    def cleanup(){database?.close();postgres?.stop()}
    static LoginProof.Policy policy(){LoginProof.Policy.defaults([ISSUER,'https://second.example/realm'] as Set,['armoney-web','armoney-ios'] as Set,['old','new'] as Set,[ACTOR,'other-provider'] as Set)}
    static CodeIntentPairScopes scopes(){new CodeIntentPairScopes('[{"issuer":"'+ISSUER+'","client_id":"armoney-web"},{"issuer":"https://second.example/realm","client_id":"armoney-ios"}]','[]')}
    def store(CodeIntentRetentionKeys selected=keys){new PostgresCodeIntentKeyLookupStore(database,policy(),scopes(),selected)}
    static K.Input input(List<K.Candidate> candidates=[new K.Candidate('old','a'*64)],String subject='subject',String client='armoney-web'){
        new K.Input(ISSUER,subject,client,'session',candidates)
    }
    def seed(String digest='a'*64,String codeKey='old',long seconds=60,String retentionId='retained',CodeIntentRetentionKeys material=keys){
        def expiry=Instant.now().plusSeconds(seconds);def issued=expiry.minusSeconds(120);def id=UUID.randomUUID()
        database.transaction{sql->sql.execute("""insert into sso_code_intents values(?,?,?,?,?,?,?,?,cast(? as timestamptz),cast(? as timestamptz),
            clock_timestamp(),cast(? as timestamptz),cast(? as timestamptz))""",id,digest,codeKey,retentionId,
            material.hash(retentionId,'armoney-intent-actor-v1',ACTOR),([31]*32) as byte[],
            material.hash(retentionId,'armoney-intent-binding-v1',ISSUER,'subject','armoney-web','session'),([32]*32) as byte[],
            issued.toString(),expiry.toString(),issued.plusSeconds(30).toString(),expiry.plusSeconds(604800).toString())}
        id
    }
    def business(){database.transaction{sql->['sso_code_intents','sso_code_intent_details','sso_code_intent_commands','sso_code_intent_nonces',
        'sso_code_intent_encryption_keys','sso_code_confirmation_anchors','sso_code_confirmation_results','sso_code_confirmation_anchor_completions',
        'sso_login_creation_reservations','sso_login_proofs','sso_login_events','sso_login_proof_commands','identities','sessions',
        'verification_authorities','banking_eligibility_grants','managed_registration_principals'].collectEntries{table->
            [(table):sql.fetch('select to_jsonb(t)::text from '+table+' t order by 1').getValues(0,String)]}}}
    long count(String table){database.transaction{it.fetchOne('select count(*) from '+table).get(0,Long)}}
    def 'retained ORIGINAL key selects only original code ID after current key rotation without business writes'(){
        given:seed();def original=business()
        def rotated=new CodeIntentRetentionKeys('current',['retained':Base64.encoder.encodeToString(([21]*32) as byte[]),'current':Base64.encoder.encodeToString(([22]*32) as byte[])])
        when:def result=store(rotated).lookup(ACTOR,input([new K.Candidate('new','b'*64),new K.Candidate('old','a'*64)]))
        then:result==new K.Result('old');business()==original;count('sso_login_proof_admission')==1
    }
    def 'zero or expired matching intents are generic missing'(){
        given:if(expired)seed('a'*64,'old',-1);def original=business()
        when:store().lookup(ACTOR,input())
        then:def missing=thrown(K.Failure);missing.error()==K.Error.MISSING;missing.message=='code_intent_request_rejected';business()==original
        where:expired << [false,true]
    }
    def 'two distinct unexpired matching intents deny rather than select first candidate'(){
        given:seed();seed('b'*64,'new');def original=business()
        when:store().lookup(ACTOR,input([new K.Candidate('old','a'*64),new K.Candidate('new','b'*64)]))
        then:def ambiguous=thrown(K.Failure);ambiguous.error()==K.Error.UNAVAILABLE;business()==original
    }
    def 'expired first candidate does not hide exactly one live second candidate'(){
        given:seed('a'*64,'old',-1);seed('b'*64,'new');def original=business()
        when:def result=store().lookup(ACTOR,input([new K.Candidate('old','a'*64),new K.Candidate('new','b'*64)]))
        then:result.codeKeyId()=='new';business()==original
    }
    def 'missing original retention key is unavailable'(){
        given:seed();def original=business()
        def missing=new CodeIntentRetentionKeys('current',['current':Base64.encoder.encodeToString(([22]*32) as byte[])])
        when:store(missing).lookup(ACTOR,input())
        then:def unavailable=thrown(K.Failure);unavailable.error()==K.Error.UNAVAILABLE;business()==original
    }
    def 'unreadable original key cannot be skipped when another candidate matches in either order'(){
        given:
        seed()
        def readable=new CodeIntentRetentionKeys('current',['current':Base64.encoder.encodeToString(([22]*32) as byte[])])
        seed('b'*64,'new',60,'current',readable)
        def original=business()
        def candidates=[new K.Candidate('old','a'*64),new K.Candidate('new','b'*64)]
        if(readableFirst)candidates=candidates.reverse()
        when:store(readable).lookup(ACTOR,input(candidates))
        then:def unavailable=thrown(K.Failure);unavailable.error()==K.Error.UNAVAILABLE;business()==original
        count('sso_login_proofs')==0;count('sso_login_creation_reservations')==0;count('sso_code_confirmation_anchors')==0
        where:readableFirst << [false,true]
    }
    def 'original actor binding and candidate ID mismatches never select existing intent'(){
        given:seed();def original=business()
        when:store().lookup(actor,input(candidates,subject,client))
        then:def denied=thrown(K.Failure);denied.error()==K.Error.CONFLICT;business()==original
        where:
        actor | subject | client | candidates
        'other-provider' | 'subject' | 'armoney-web' | [new K.Candidate('old','a'*64)]
        ACTOR | 'foreign' | 'armoney-web' | [new K.Candidate('old','a'*64)]
        ACTOR | 'subject' | 'armoney-ios' | [new K.Candidate('old','a'*64)]
        ACTOR | 'subject' | 'armoney-web' | [new K.Candidate('new','a'*64)]
    }
    def 'duplicate candidate validation is charged once and never reads or changes business records'(){
        given:seed();def original=business()
        when:store().lookup(ACTOR,input([new K.Candidate('old','a'*64),new K.Candidate('old','b'*64)]))
        then:def bad=thrown(K.Failure);bad.error()==K.Error.BAD_INPUT;business()==original
        database.transaction{sql->sql.fetchOne('select credits from sso_login_proof_admission').get(0,BigDecimal)==19}
    }
    def 'original expiry is rechecked after the second indexed read before returning a selected key'(){
        given:
        seed('a'*64,'old',2);seed('b'*64,'new',-1)
        // Isolated test-only view delays the second indexed digest read; no production callback/clock mutation.
        database.transaction{sql->
            sql.execute('alter table sso_code_intents rename to qa_lookup_intents')
            sql.execute('create sequence qa_lookup_delay_hits')
            sql.execute('create sequence qa_lookup_first_live')
            sql.execute('''create function qa_lookup_delay(d text,deadline timestamptz) returns boolean language plpgsql as $$
                begin if d=repeat('a',64) and clock_timestamp()<deadline then perform nextval('qa_lookup_first_live');end if;
                if d=repeat('b',64) then perform nextval('qa_lookup_delay_hits');perform pg_sleep(2.5);end if;return true;end $$''')
            sql.execute('create view sso_code_intents as select * from qa_lookup_intents where qa_lookup_delay(code_digest,expires_at)')
        }
        when:store().lookup(ACTOR,input([new K.Candidate('old','a'*64),new K.Candidate('new','b'*64)]))
        then:def expired=thrown(K.Failure);expired.error()==K.Error.MISSING
        database.transaction{sql->
            assert sql.fetchOne('select is_called from qa_lookup_delay_hits').get(0,Boolean)
            assert sql.fetchOne('select is_called from qa_lookup_first_live').get(0,Boolean)
            assert sql.fetchOne("select expires_at<clock_timestamp() from qa_lookup_intents where code_digest=repeat('a',64)").get(0,Boolean)
            true
        }
        count('sso_login_proofs')==0;count('sso_login_creation_reservations')==0;count('sso_code_confirmation_anchors')==0
    }
    def 'unknown server actor is rejected before any gate or admission'(){
        when:store().lookup('unconfigured',input())
        then:def denied=thrown(K.Failure);denied.error()==K.Error.UNAUTHORIZED;count('sso_login_proof_admission')==0
    }
    def 'explicit request capability is one shot and cannot escape owned backend scope'(){
        given:seed();def selected=store();def escaped=null
        when:def result=selected.inRequest(ACTOR,{request->escaped=request;request.lookup(input())})
        then:result.codeKeyId()=='old'
        when:escaped.lookup(input())
        then:thrown(K.Failure)
        when:selected.inRequest(ACTOR,{request->request.lookup(input());request.lookup(input())})
        then:def reused=thrown(K.Failure);reused.error()==K.Error.UNAVAILABLE
    }
    def 'existing global16 occupancy rejects lookup before shared admission and releases no foreign permit'(){
        given:seed();def held=[]
        try{
            (0..<16).each{slot->
                def connection=java.sql.DriverManager.getConnection(postgres.jdbcUrl,postgres.username,postgres.password)
                held.add(connection)
                def statement=connection.prepareStatement('select pg_try_advisory_lock(?,?)')
                try{statement.setInt(1,0x41524D4F);statement.setInt(2,slot);def rows=statement.executeQuery()
                    try{assert rows.next();assert rows.getBoolean(1)}finally{rows.close()}
                }finally{statement.close()}
            }
        }catch(Throwable failed){held.each{it.close()};throw failed}
        when:store().lookup(ACTOR,input())
        then:def busy=thrown(K.Failure);busy.error()==K.Error.RATE_LIMITED;count('sso_login_proof_admission')==0
        when:held.each{it.close()};held.clear();def result=store().lookup(ACTOR,input())
        then:result.codeKeyId()=='old';count('sso_login_proof_admission')==1
        cleanup:held.each{it.close()}
    }
    def 'shared admission saturation rejects without an hourly debit or business writes'(){
        given:
        seed();def original=business();store().lookup(ACTOR,input())
        database.transaction{sql->sql.execute("update sso_login_proof_admission set credits=0,refilled_at=clock_timestamp()+interval '1 hour'")}
        when:store().lookup(ACTOR,input())
        then:def limited=thrown(K.Failure);limited.error()==K.Error.RATE_LIMITED;business()==original;count('sso_login_creation_reservations')==0
    }
}
