package com.arman.bank.authservice

import com.arman.bank.authservice.domain.LoginProof
import com.arman.bank.authservice.infrastructure.PostgresLoginOperationGate
import com.arman.bank.runtime.Database
import org.testcontainers.containers.PostgreSQLContainer
import spock.lang.Specification
import java.util.concurrent.*
import java.util.concurrent.atomic.*

/** Gate primitives on real PostgreSQL. Independent sessions/pools are NOT actual Auth Main processes.
 * Multi-JVM route/provider integration remains separate required acceptance. */
class LoginOperationGateIntegrationSpec extends Specification {
    static final int NAMESPACE=0x41524D4F
    PostgreSQLContainer postgres
    Database database
    def gate=new PostgresLoginOperationGate()
    def setup(){
        postgres=new PostgreSQLContainer('postgres:17.6-alpine');postgres.start()
        database=new Database(postgres.jdbcUrl,postgres.username,postgres.password)
        database.transaction{it.execute('create table qa_gate_effects(id uuid primary key,result text not null)')}
    }
    def cleanup(){database?.close();postgres?.stop()}
    int backend(Database.OwnedSession session){session.autocommit{it.fetchOne('select pg_backend_pid()').get(0,Integer)}}
    long owned(){database.transaction{it.fetchOne("select count(*) from pg_locks where locktype='advisory' and classid=cast(? as oid) and objsubid=2",NAMESPACE).get(0,Long)}}
    boolean allAvailable(){database.withSession{session->session.autocommit{sql->
        for(int slot=0;slot<16;slot++){
            assert sql.fetchOne('select pg_try_advisory_lock(?,?)',NAMESPACE,slot).get(0,Boolean)
            assert sql.fetchOne('select pg_advisory_unlock(?,?)',NAMESPACE,slot).get(0,Boolean)
        }
        true
    }}}
    def 'sixteen independent sessions across four pool five instances admit globally and seventeenth rejects bounded'(){
        given:
        def instances=(1..4).collect{new Database(postgres.jdbcUrl,postgres.username,postgres.password)}
        def pool=Executors.newFixedThreadPool(16);def ready=new CountDownLatch(16);def release=new CountDownLatch(1)
        def pids=Collections.synchronizedSet(new HashSet<Integer>())
        def futures=(0..<16).collect{int index->pool.submit({
            instances[index.intdiv(4)].withSession{session->gate.withPermit(session){permit->
                int pid=permit.autocommit{it.fetchOne('select pg_backend_pid()').get(0,Integer)}
                pids.add(pid)
                ready.countDown();assert release.await(20,TimeUnit.SECONDS)
                permit.transaction{sql->assert sql.fetchOne('select pg_backend_pid()').get(0,Integer)==pid;sql.execute('insert into qa_gate_effects values (?,?)',UUID.randomUUID(),'technical-fixture')}
            }}
        } as Callable)}
        assert ready.await(15,TimeUnit.SECONDS)
        assert pids.size()==16
        assert owned()==16
        assert database.transaction{sql->sql.connectionResult{connection->
            Integer[] values=pids.toArray(new Integer[0])
            def array=connection.createArrayOf('integer',values)
            try{
                def statement=connection.prepareStatement("select count(*) from pg_stat_activity where pid=any(cast(? as integer[])) and state='idle' and xact_start is null")
                try{
                    statement.setArray(1,array)
                    def rows=statement.executeQuery()
                    try{assert rows.next();return rows.getInt(1)}finally{rows.close()}
                }finally{statement.close()}
            }finally{array.free()}
        }}==16
        def called=new AtomicBoolean(false)
        long start=System.nanoTime()
        when: database.withSession{session->gate.withPermit(session){called.set(true);'must-not-return'}}
        then:
        def failure=thrown(LoginProof.Failure);failure.error()==LoginProof.Error.RATE_LIMITED
        !called.get();TimeUnit.NANOSECONDS.toSeconds(System.nanoTime()-start)<5
        when: release.countDown();futures.each{it.get(15,TimeUnit.SECONDS)}
        then:
        owned()==0;allAvailable()
        database.transaction{it.fetchOne('select count(*) from qa_gate_effects').get(0,Integer)}==16
        cleanup:
        release.countDown();pool.shutdownNow();instances.each{it.close()}
    }
    def 'five occupied pool connections complete transactions on their original sessions without second acquisition'(){
        given:
        def pool=Executors.newFixedThreadPool(5);def ready=new CountDownLatch(5);def proceed=new CountDownLatch(1)
        def futures=(1..5).collect{pool.submit({database.withSession{session->gate.withPermit(session){permit->
            int original=permit.autocommit{it.fetchOne('select pg_backend_pid()').get(0,Integer)}
            ready.countDown();assert proceed.await(10,TimeUnit.SECONDS)
            permit.transaction{sql->assert sql.fetchOne('select pg_backend_pid()').get(0,Integer)==original;sql.execute('insert into qa_gate_effects values (?,?)',UUID.randomUUID(),'technical-fixture')}
        }}} as Callable)}
        assert ready.await(5,TimeUnit.SECONDS)
        when: proceed.countDown();futures.each{it.get(10,TimeUnit.SECONDS)}
        then: allAvailable();database.transaction{it.fetchOne('select count(*) from qa_gate_effects').get(0,Integer)}==5
        cleanup: proceed.countDown();pool.shutdownNow()
    }

    def 'same owned backend releases on success rollback and cancellation before pooled reuse #outcome'(){
        given: def called=new AtomicBoolean(false)
        when:
        database.withSession{session->
            int original=backend(session)
            try{
                gate.withPermit(session){permit->
                    assert permit.autocommit{it.fetchOne('select pg_backend_pid()').get(0,Integer)}==original
                    permit.transaction{sql->
                        assert sql.fetchOne('select pg_backend_pid()').get(0,Integer)==original
                        if(outcome=='rollback')throw new IllegalArgumentException('synthetic rollback')
                        sql.execute('insert into qa_gate_effects values (?,?)',UUID.randomUUID(),'technical-fixture')
                    }
                    if(outcome=='cancel')throw new CancellationException('synthetic cancellation')
                    called.set(true)
                }
            }catch(RuntimeException rejected){
                if(outcome=='rollback')assert rejected instanceof IllegalArgumentException && rejected.message=='synthetic rollback'
                else if(outcome=='cancel')assert rejected instanceof CancellationException
                else throw rejected
            }
            assert backend(session)==original
            assert session.autocommit{it.fetchOne("select count(*) from pg_locks where pid=pg_backend_pid() and classid=cast(? as oid) and objsubid=2",NAMESPACE).get(0,Integer)}==0
            gate.withPermit(session){permit->permit.verify();assert backend(session)==original;true}
        }
        then:
        called.get()==(outcome=='success');allAvailable()
        database.transaction{it.fetchOne('select count(*) from qa_gate_effects').get(0,Integer)}==(outcome=='rollback' ? 0 : 1)
        where: outcome << ['success','rollback','cancel']
    }
    def 'escaped permit and wrong thread cannot act after operation scope'(){
        given: def saved=new AtomicReference<PostgresLoginOperationGate.Permit>();def pool=Executors.newSingleThreadExecutor()
        when:
        database.withSession{session->gate.withPermit(session){permit->
            saved.set(permit)
            try{pool.submit({permit.verify()} as Callable).get(5,TimeUnit.SECONDS);assert false}catch(ExecutionException denied){assert denied.cause instanceof LoginProof.Failure}
            true
        }}
        saved.get().verify()
        then: def failure=thrown(LoginProof.Failure);failure.error()==LoginProof.Error.UNAVAILABLE
        allAvailable()
        cleanup: pool.shutdownNow()
    }
    def 'preexisting same namespace or reentrant admission invalidates without acquiring again #reentrant'(){
        given: def original=new AtomicInteger()
        when:
        database.withSession{session->
            original.set(backend(session))
            if(reentrant)gate.withPermit(session){gate.withPermit(session){'must-not-return'}}
            else{
                session.autocommit{it.fetchOne('select pg_advisory_lock(?,?)',NAMESPACE,99)}
                gate.withPermit(session){'must-not-return'}
            }
        }
        then: thrown(RuntimeException)
        database.withSession{session->backend(session)}!=original.get()
        allAvailable()
        where: reentrant << [false,true]
    }
    def 'cleanup rejects hidden reentrant count or lost exact slot and physically evicts #fault'(){
        given: def original=new AtomicInteger();def mutated=new AtomicBoolean(false)
        when:
        database.withSession{session->
            original.set(backend(session))
            gate.withPermit(session){permit->permit.autocommit{sql->
                int slot=sql.fetchOne("select objid::integer from pg_locks where pid=pg_backend_pid() and classid=cast(? as oid) and objsubid=2",NAMESPACE).get(0,Integer)
                if(fault=='extra-reference')assert sql.fetchOne('select pg_try_advisory_lock(?,?)',NAMESPACE,slot).get(0,Boolean)
                else assert sql.fetchOne('select pg_advisory_unlock(?,?)',NAMESPACE,slot).get(0,Boolean)
                mutated.set(true)
                'must-not-return'
            }}
        }
        then: thrown(RuntimeException)
        database.withSession{session->backend(session)}!=original.get()
        allAvailable()
        mutated.get()
        where: fault << ['extra-reference','lost-slot']
    }
    def 'release leaves unrelated advisory namespace and bigint namespace untouched'(){
        when:
        database.withSession{session->
            session.autocommit{sql->sql.fetchOne('select pg_advisory_lock(?,?)',NAMESPACE+1,0);sql.fetchOne('select pg_advisory_lock(cast(? as bigint))',((long)NAMESPACE)<<32)}
            gate.withPermit(session){it.verify();true}
            session.autocommit{sql->
                assert sql.fetchOne('select pg_advisory_unlock(?,?)',NAMESPACE+1,0).get(0,Boolean)
                assert sql.fetchOne('select pg_advisory_unlock(cast(? as bigint))',((long)NAMESPACE)<<32).get(0,Boolean)
                true
            }
        }
        then: allAvailable()
    }
    def 'read only primary session is unavailable and evicted instead of authorizing writes'(){
        given: def original=new AtomicInteger();def called=new AtomicBoolean(false)
        when:
        database.withSession{session->
            original.set(backend(session));session.autocommit{it.execute('set default_transaction_read_only=on')}
            gate.withPermit(session){called.set(true)}
        }
        then: def failure=thrown(LoginProof.Failure);failure.error()==LoginProof.Error.UNAVAILABLE
        !called.get();database.withSession{session->backend(session)}!=original.get();allAvailable()
    }
    def 'disconnect during remote pause denies late response before any business write'(){
        given:
        def ready=new CountDownLatch(1);def late=new CountDownLatch(1);def original=new AtomicInteger();def pool=Executors.newSingleThreadExecutor()
        def pending=pool.submit({database.withSession{session->gate.withPermit(session){permit->
            original.set(permit.autocommit{it.fetchOne('select pg_backend_pid()').get(0,Integer)})
            ready.countDown();assert late.await(10,TimeUnit.SECONDS)
            permit.transaction{sql->sql.execute('insert into qa_gate_effects values (?,?)',UUID.randomUUID(),'must-not-commit')}
        }}} as Callable)
        assert ready.await(5,TimeUnit.SECONDS)
        assert database.transaction{it.fetchOne('select pg_terminate_backend(?)',original.get()).get(0,Boolean)}
        when: late.countDown();pending.get(10,TimeUnit.SECONDS)
        then: thrown(ExecutionException)
        database.transaction{it.fetchOne('select count(*) from qa_gate_effects').get(0,Integer)}==0
        allAvailable()
        cleanup: late.countDown();pool.shutdownNow()
    }
    def 'real commit followed by injected acknowledgement loss returns uncertainty and preserves original durable identity'(){
        given:
        def driver=new GateFaultDriver('commit');java.sql.DriverManager.registerDriver(driver)
        def faultDatabase=new Database(driver.prefix+postgres.jdbcUrl,postgres.username,postgres.password)
        UUID original=UUID.randomUUID();driver.armed.set(true)
        when:
        faultDatabase.withSession{session->gate.withPermit(session){permit->permit.transaction{sql->sql.execute('insert into qa_gate_effects values (?,?)',original,'original-result');'must-not-return'}}}
        then: thrown(PostgresLoginOperationGate.UncertainCommit)
        driver.fired.get()==1
        database.transaction{it.fetchOne('select result from qa_gate_effects where id=?',original).get(0,String)}=='original-result'
        allAvailable()
        when:
        def reconciled=database.withSession{session->gate.withPermit(session){permit->permit.transaction{sql->sql.fetchOne('select result from qa_gate_effects where id=?',original).get(0,String)}}}
        then: reconciled=='original-result'
        database.transaction{it.fetchOne('select count(*) from qa_gate_effects').get(0,Integer)}==1
        cleanup: faultDatabase?.close();java.sql.DriverManager.deregisterDriver(driver)
    }
    def 'verification failure after acknowledged commit withholds result without claiming rollback or uncertain commit'(){
        given:
        def driver=new GateFaultDriver('postcommit-verification');java.sql.DriverManager.registerDriver(driver)
        def faultDatabase=new Database(driver.prefix+postgres.jdbcUrl,postgres.username,postgres.password)
        UUID original=UUID.randomUUID();driver.armed.set(true)
        when:
        faultDatabase.withSession{session->gate.withPermit(session){permit->permit.transaction{sql->sql.execute('insert into qa_gate_effects values (?,?)',original,'acknowledged-result');'must-not-return'}}}
        then:
        def failure=thrown(RuntimeException)
        !(failure instanceof PostgresLoginOperationGate.UncertainCommit)
        driver.committed.get();driver.fired.get()==1
        database.transaction{it.fetchOne('select result from qa_gate_effects where id=?',original).get(0,String)}=='acknowledged-result'
        allAvailable()
        when:
        def reconciled=database.withSession{session->gate.withPermit(session){permit->permit.transaction{sql->sql.fetchOne('select result from qa_gate_effects where id=?',original).get(0,String)}}}
        then: reconciled=='acknowledged-result'
        database.transaction{it.fetchOne('select count(*) from qa_gate_effects').get(0,Integer)}==1
        cleanup: faultDatabase?.close();java.sql.DriverManager.deregisterDriver(driver)
    }
    def 'injected unlock acknowledgement or zero verification failure denies return and evicts #mode'(){
        given:
        def driver=new GateFaultDriver(mode);java.sql.DriverManager.registerDriver(driver)
        def faultDatabase=new Database(driver.prefix+postgres.jdbcUrl,postgres.username,postgres.password)
        def original=new AtomicInteger();driver.armed.set(true)
        when: faultDatabase.withSession{session->original.set(backend(session));gate.withPermit(session){'must-not-return'}}
        then: thrown(RuntimeException)
        driver.fired.get()==1
        faultDatabase.withSession{session->backend(session)}!=original.get()
        allAvailable()
        cleanup: faultDatabase?.close();java.sql.DriverManager.deregisterDriver(driver)
        where: mode << ['unlock','zero-verification']
    }
    /** Test-only failure after real COMMIT/unlock or before a required verification query. */
    static class GateFaultDriver implements java.sql.Driver {
        final String prefix='jdbc:qa-login-gate:'+UUID.randomUUID()+':'
        final String mode
        final AtomicBoolean armed=new AtomicBoolean(false),unlocked=new AtomicBoolean(false),committed=new AtomicBoolean(false)
        final AtomicInteger fired=new AtomicInteger()
        GateFaultDriver(String mode){this.mode=mode}
        @Override boolean acceptsURL(String url){url!=null&&url.startsWith(prefix)}
        @Override java.sql.Connection connect(String url,Properties properties){
            if(!acceptsURL(url))return null
            def physical=java.sql.DriverManager.getConnection(url.substring(prefix.length()),properties)
            def handler={Object proxy,java.lang.reflect.Method method,Object[] args->
                try{
                    Object result=method.invoke(physical,args)
                    if(method.name=='commit'&&mode=='postcommit-verification'&&armed.get())committed.set(true)
                    if(method.name=='commit'&&mode=='commit'&&armed.compareAndSet(true,false)){
                        fired.incrementAndGet();throw new java.sql.SQLException('synthetic postcommit acknowledgement loss','08007')
                    }
                    if(method.name=='prepareStatement'&&args[0] instanceof String){
                        String query=args[0]
                        if(mode=='postcommit-verification'&&committed.get()&&query.contains('select d.oid::bigint as database_id')&&armed.compareAndSet(true,false)){
                            ((java.sql.PreparedStatement)result).close();fired.incrementAndGet();throw new java.sql.SQLException('synthetic postcommit verification failure','08006')
                        }
                        if(mode=='zero-verification'&&unlocked.get()&&query.contains('select count(*) from pg_locks')&&armed.compareAndSet(true,false)){
                            ((java.sql.PreparedStatement)result).close();fired.incrementAndGet();throw new java.sql.SQLException('synthetic verification failure','08006')
                        }
                        if(query.contains('pg_advisory_unlock')){
                            def statement=result
                            def statementHandler={Object statementProxy,java.lang.reflect.Method action,Object[] values->
                                try{
                                    Object rows=action.invoke(statement,values)
                                    if(action.name in ['executeQuery','execute']){
                                        def resultRows=rows instanceof java.sql.ResultSet ? rows : ((java.sql.PreparedStatement)statement).getResultSet()
                                        unlocked.set(true)
                                        if(mode=='unlock'&&armed.compareAndSet(true,false)){
                                            assert ((java.sql.ResultSet)resultRows).next();assert ((java.sql.ResultSet)resultRows).getBoolean(1)
                                            ((java.sql.ResultSet)resultRows).close();fired.incrementAndGet();throw new java.sql.SQLException('synthetic unlock acknowledgement loss','08006')
                                        }
                                    }
                                    rows
                                }catch(java.lang.reflect.InvocationTargetException wrapped){throw wrapped.cause}
                            } as java.lang.reflect.InvocationHandler
                            return java.lang.reflect.Proxy.newProxyInstance(getClass().classLoader,[java.sql.PreparedStatement] as Class[],statementHandler)
                        }
                    }
                    result
                }catch(java.lang.reflect.InvocationTargetException wrapped){throw wrapped.cause}
            } as java.lang.reflect.InvocationHandler
            (java.sql.Connection)java.lang.reflect.Proxy.newProxyInstance(getClass().classLoader,[java.sql.Connection] as Class[],handler)
        }
        @Override java.sql.DriverPropertyInfo[] getPropertyInfo(String url,Properties properties){new java.sql.DriverPropertyInfo[0]}
        @Override int getMajorVersion(){1}
        @Override int getMinorVersion(){0}
        @Override boolean jdbcCompliant(){false}
        @Override java.util.logging.Logger getParentLogger(){java.util.logging.Logger.getLogger('qa-login-gate-fault')}
    }
}
