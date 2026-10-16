package com.arman.bank.authservice.infrastructure

import com.arman.bank.authservice.application.LoginProofStore
import com.arman.bank.authservice.application.LoginProofService
import com.arman.bank.authservice.application.LoginProofObservations
import com.arman.bank.authservice.domain.LoginProof as L
import com.arman.bank.runtime.Database
import org.testcontainers.containers.PostgreSQLContainer
import spock.lang.Specification
import java.sql.DriverManager
import java.time.*
import java.util.concurrent.*

/** Real request capabilities; synthetic observer work never establishes provider authority. */
class LoginRequestScopeIntegrationSpec extends Specification {
    static final String ACTOR='request-fixture', ISSUER='https://issuer.example/realm'
    PostgreSQLContainer postgres
    Database database
    def setup(){
        postgres=new PostgreSQLContainer('postgres:17.6-alpine');postgres.start()
        database=new Database(postgres.jdbcUrl,postgres.username,postgres.password)
        database.transaction{sql->
            sql.execute('create table qa_request_effects(id uuid primary key,pid integer not null)')
            sql.execute('''create function qa_request_freeze() returns trigger language plpgsql as $$ begin NEW.refilled_at='2099-01-01T00:00:00Z'; return NEW; end $$''')
            sql.execute('create trigger qa_request_freeze before insert or update on sso_login_proof_admission for each row execute function qa_request_freeze()')
        }
    }
    def cleanup(){database?.close();postgres?.stop()}
    def scope(){new PostgresLoginRequestScope(database)}
    def policy(){L.Policy.defaults([ISSUER] as Set,['armoney-web'] as Set,['fixture'] as Set,[ACTOR] as Set)}
    def store(){new PostgresLoginProofStore(database,policy(),([7]*32) as byte[],{sql->{binding,now->throw new L.Failure(L.Error.UNAVAILABLE)} as LoginProofStore.EligibilityPort})}
    L.Write command(){
        def now=Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS)
        new L.Write(UUID.randomUUID(),'a'*64,'fixture',ISSUER,'subject','armoney-web','session','authentication',now,now.plusSeconds(120),L.Kind.INTERACTIVE,UUID.randomUUID(),now)
    }
    def credits(){database.transaction{it.fetchOne('select credits from sso_login_proof_admission').get(0,BigDecimal)}}
    long owned(){database.transaction{it.fetchOne("select count(*) from pg_locks where locktype='advisory' and classid=cast(? as oid) and objsubid=2",0x41524D4F).get(0,Long)}}
    def raw(){DriverManager.getConnection(postgres.jdbcUrl,postgres.username,postgres.password)}
    def 'admission remote interval and all business transactions retain the fifth physical connection'(){
        given:
        def executor=Executors.newFixedThreadPool(4);def ready=new CountDownLatch(4);def release=new CountDownLatch(1)
        def holders=(1..4).collect{executor.submit({database.withSession{session->ready.countDown();assert release.await(15,TimeUnit.SECONDS);null}} as Callable)}
        assert ready.await(5,TimeUnit.SECONDS)
        UUID id=UUID.randomUUID();int first=0
        when:
        def result=scope().inRequest(ACTOR){request->
            first=request.transaction{it.fetchOne('select pg_backend_pid()').get(0,Integer)}
            def observer=raw()
            try{
                def statement=observer.prepareStatement("select state,xact_start from pg_stat_activity where pid=?")
                try{statement.setInt(1,first);def rows=statement.executeQuery();try{assert rows.next();assert rows.getString(1)=='idle';assert rows.getObject(2)==null}finally{rows.close()}}finally{statement.close()}
            }finally{observer.close()}
            request.transaction{sql->assert sql.fetchOne('select pg_backend_pid()').get(0,Integer)==first;sql.execute('insert into qa_request_effects values (?,?)',id,first)}
            id
        }
        then:
        result==id
        when:
        release.countDown();holders.each{it.get(5,TimeUnit.SECONDS)}
        then:
        credits()==19
        owned()==0
        database.transaction{it.fetchOne('select pid from qa_request_effects where id=?',id).get(0,Integer)}==first
        cleanup:
        release.countDown();executor.shutdownNow()
    }
    def 'staged service performs one admission across observer and unavailable write and leaks no permit'(){
        given:
        int calls=0
        def usecase=new LoginProofService(store(),{binding,anchor->calls++;new L.Observation(binding,'+971501234567','fixture@example.test','+971501234567',true,true,anchor.plusSeconds(120),anchor)} as LoginProofObservations)
        when:usecase.write(ACTOR,command())
        then:
        def rejected=thrown(L.Failure);rejected.error()==L.Error.CONFLICT
        calls==1;credits()==19;owned()==0
        database.transaction{it.fetchOne('select count(*) from sso_login_proofs').get(0,Long)}==0
    }
    def 'closed wrong-thread and repeated terminal capabilities cannot become another admitted operation'(){
        given:
        def target=store();def escaped
        def executor=Executors.newSingleThreadExecutor()
        when:
        target.inRequest(ACTOR){request->
            escaped=request
            assert executor.submit({try{request.admitCreate(command());null}catch(L.Failure denied){denied.error()}} as Callable).get(3,TimeUnit.SECONDS)==L.Error.UNAVAILABLE
            def admission=request.admitCreate(command())
            assert admission.startedAt()!=null
        }
        escaped.resolve(new L.Resolve('a'*64,'fixture',ISSUER,'subject','armoney-web','session'))
        then:
        def denied=thrown(L.Failure);denied.error()==L.Error.UNAVAILABLE
        credits()==19;owned()==0
        when:
        target.inRequest(ACTOR){request->
            try{request.resolve(new L.Resolve('a'*64,'fixture',ISSUER,'subject','armoney-web','session'))}catch(L.Failure missing){assert missing.error()==L.Error.MISSING}
            request.write(command())
        }
        then:
        def duplicate=thrown(L.Failure);duplicate.error()==L.Error.UNAVAILABLE
        credits()==18;owned()==0
        cleanup:executor.shutdownNow()
    }
    def 'backend loss during remote interval cannot write on a replacement connection'(){
        given:UUID id=UUID.randomUUID()
        when:
        scope().inRequest(ACTOR){request->
            int pid=request.transaction{it.fetchOne('select pg_backend_pid()').get(0,Integer)}
            def observer=raw()
            try{def statement=observer.prepareStatement('select pg_terminate_backend(?)');try{statement.setInt(1,pid);def rows=statement.executeQuery();try{assert rows.next();assert rows.getBoolean(1)}finally{rows.close()}}finally{statement.close()}}finally{observer.close()}
            request.transaction{it.execute('insert into qa_request_effects values (?,?)',id,pid)}
        }
        then:
        thrown(RuntimeException)
        database.transaction{it.fetchOne('select count(*) from qa_request_effects').get(0,Long)}==0
        owned()==0
    }
    def 'same store admission from a completed request cannot authorize a later request'(){
        given:
        def target=store();def input=command()
        def original=target.inRequest(ACTOR){it.admitCreate(input)}
        when:
        target.inRequest(ACTOR){request->
            request.writeObserved(input,original,new L.Observation(input.binding(),'+971501234567','fixture@example.test','+971501234567',true,true,original.startedAt().plusSeconds(120),original.startedAt()))
        }
        then:
        def denied=thrown(L.Failure);denied.error()==L.Error.UNAVAILABLE
        credits()==18;owned()==0
        database.transaction{it.fetchOne('select count(*) from sso_login_proofs').get(0,Long)}==0
    }
    def 'stage capability cannot escape or perform a second operation after a rejected first command'(){
        given:
        def material=Base64.encoder.encodeToString(([8]*32) as byte[])
        def target=new PostgresCodeIntentStore(database,policy(),new CodeIntentRetentionKeys('fixture',['fixture':material]),
            new CodeIntentDetailCrypto('detail',['detail':Base64.encoder.encodeToString(([9]*32) as byte[])],[] as Set))
        def escaped=target.inRequest(ACTOR){it}
        when:escaped.stage(command())
        then:
        def closed=thrown(L.Failure);closed.error()==L.Error.UNAVAILABLE
        credits()==19
        when:
        target.inRequest(ACTOR){request->
            try{request.stage(command())}catch(L.Failure missing){assert missing.error()==L.Error.CONFLICT}
            request.stage(command())
        }
        then:
        def repeated=thrown(L.Failure);repeated.error()==L.Error.UNAVAILABLE
        credits()==18;owned()==0
        database.transaction{it.fetchOne('select count(*) from sso_code_intents').get(0,Long)}==0
        database.transaction{it.fetchOne('select count(*) from sso_code_intent_nonces').get(0,Long)}==0
    }
    def 'interruption after remote work withholds result and releases the original permit'(){
        when:
        scope().inRequest(ACTOR){request->
            request.transaction{it.fetchOne('select pg_backend_pid()').get(0,Integer)}
            Thread.currentThread().interrupt()
            'must-not-return'
        }
        then:
        def denied=thrown(L.Failure);denied.error()==L.Error.UNAVAILABLE
        Thread.interrupted()
        owned()==0
        credits()==19
        cleanup:Thread.interrupted()
    }
    def 'configured real PostgreSQL statement timeout bounds blocked admission and releases permit'(){
        given:
        def administrator=raw()
        administrator.createStatement().execute("alter role ${postgres.username} set statement_timeout='1s'")
        def bounded=new Database(postgres.jdbcUrl,postgres.username,postgres.password)
        def blocker=raw();blocker.autoCommit=false
        blocker.createStatement().execute('select id from verification_control where id=1 for update')
        long started=System.nanoTime()
        when:new PostgresLoginRequestScope(bounded).inRequest(ACTOR){throw new AssertionError('must not reach parsing')}
        then:
        thrown(RuntimeException)
        TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started)>=800
        TimeUnit.NANOSECONDS.toSeconds(System.nanoTime()-started)<5
        when:blocker.rollback()
        then:owned()==0;database.transaction{it.fetchOne('select count(*) from sso_login_proof_admission').get(0,Long)}==0
        cleanup:
        blocker?.rollback();blocker?.close();bounded?.close()
        administrator?.createStatement()?.execute("alter role ${postgres.username} reset statement_timeout");administrator?.close()
    }
}
