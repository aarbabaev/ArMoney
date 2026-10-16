package com.arman.bank.authservice

import com.arman.bank.authservice.domain.LoginProof
import com.arman.bank.runtime.Database
import org.testcontainers.containers.PostgreSQLContainer
import spock.lang.Specification
import java.net.http.*
import java.nio.file.*
import java.sql.DriverManager
import java.time.Duration
import java.util.concurrent.*

/** Two actual AuthMain JVMs, isolated PG and synthetic directional credentials.
 * No provider success, confirmation, proof issuance or banking authority is simulated. */
class LoginOperationProcessesIntegrationSpec extends Specification {
    static final int NAMESPACE=0x41524D4F
    static final String KEY='synthetic-process-proof-write-credential'
    PostgreSQLContainer postgres
    Database database
    HttpClient client
    List<Process> processes=[]
    List<Path> logs=[]
    def setup(){
        postgres=new PostgreSQLContainer('postgres:17.6-alpine');postgres.start()
        database=new Database(postgres.jdbcUrl,postgres.username,postgres.password)
        client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()
        database.transaction{sql->
            sql.execute('''create function qa_process_freeze() returns trigger language plpgsql as $$ begin NEW.refilled_at='2099-01-01T00:00:00Z'; return NEW; end $$''')
            sql.execute('create trigger qa_process_freeze before insert or update on sso_login_proof_admission for each row execute function qa_process_freeze()')
        }
    }
    def cleanup(){
        processes.each{it.destroy()}
        processes.each{if(!it.waitFor(8,TimeUnit.SECONDS)){it.destroyForcibly();assert it.waitFor(5,TimeUnit.SECONDS)}}
        client?.close();database?.close();postgres?.stop()
    }
    int start(boolean enabled){
        int port
        new ServerSocket(0,0,InetAddress.loopbackAddress).withCloseable{port=it.localPort}
        Path lib=Path.of('build','install','auth-service','lib').toAbsolutePath()
        assert Files.isDirectory(lib):'integrationTest must depend on the actual installDist artifact'
        def executable=Path.of(System.getProperty('java.home'),'bin',System.getProperty('os.name').toLowerCase().contains('win')?'java.exe':'java').toString()
        def builder=new ProcessBuilder(executable,'-Xms32m','-Xmx256m','-cp',lib.toString()+File.separator+'*','com.arman.bank.authservice.Main')
        def env=builder.environment()
        // Prevent inherited operator flags/credentials from affecting isolated process fixtures.
        env.keySet().removeIf{it.startsWith('AUTH_')||it.startsWith('SSO_')||it.startsWith('VERIFICATION_')||it.startsWith('ARMONEY_')||it.startsWith('INTERNAL_')}
        env.putAll([DB_URL:postgres.jdbcUrl,DB_USER:postgres.username,DB_PASSWORD:postgres.password,PORT:port.toString(),
            INTERNAL_AUTH_KEY:'synthetic-process-internal-service-key',USER_BASE_URL:'http://127.0.0.1:1',
            AUTH_LOGIN_PROOF_BRIDGE_ENABLED:enabled.toString()])
        if(enabled)env.putAll([SSO_LOGIN_PROOF_WRITER_ID:'process-fixture',SSO_LOGIN_PROOF_WRITE_KEY:KEY,
            SSO_LOGIN_PROOF_RESOLVE_KEY:'synthetic-process-proof-resolve-credential',
            SSO_LOGIN_PROOF_RETENTION_KEY:Base64.encoder.encodeToString(([37]*32) as byte[]),
            SSO_ISSUER:'https://issuer.example/realm',SSO_LOGIN_PROOF_CLIENT_IDS:'armoney-web',SSO_LOGIN_PROOF_CODE_KEY_IDS:'fixture',
            SSO_INTROSPECTION_URL:'http://127.0.0.1:1/realms/fixture/protocol/openid-connect/token/introspect',SSO_CLIENT_ID:'armoney-web',SSO_CLIENT_SECRET:'synthetic-process-client-secret-32-characters'])
        Path log=Files.createTempFile('armoney-auth-main-fixture-','.log');logs.add(log)
        def process=builder.redirectErrorStream(true).redirectOutput(log.toFile()).start();processes.add(process)
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(30)
        boolean ready=false
        while(!ready&&process.alive&&System.nanoTime()<deadline){
            try{ready=client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:${port}/health/ready")).timeout(Duration.ofSeconds(2)).GET().build(),HttpResponse.BodyHandlers.discarding()).statusCode()==200}
            catch(IOException ignored){}
            if(!ready)Thread.sleep(50)
        }
        assert ready:"Actual AuthMain failed readiness; private synthetic log: ${log}"
        port
    }
    HttpResponse<String> post(int port,String key=KEY,String body='not-json'){
        def builder=HttpRequest.newBuilder(URI.create("http://127.0.0.1:${port}/internal/sso-login-proofs"))
            .timeout(Duration.ofSeconds(10)).header('Content-Type','application/json')
        if(key!=null)builder.header('X-ArMoney-Login-Proof-Write-Key',key)
        client.send(builder.POST(HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString())
    }
    long locks(){database.transaction{it.fetchOne("select count(*) from pg_locks where locktype='advisory' and database=(select oid from pg_database where datname=current_database()) and classid=cast(? as oid) and objsubid=2",NAMESPACE).get(0,Long)}}
    long rows(String table){database.transaction{it.fetchOne('select count(*) from '+table).get(0,Long)}}
    def awaitCondition(Closure condition){long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(6);while(!condition.call()&&System.nanoTime()<end)Thread.sleep(20);assert condition.call()}
    def fixtureSlots(int amount){
        def acquired=[]
        try{
            for(int slot=0;slot<amount;slot++){
                def connection=DriverManager.getConnection(postgres.jdbcUrl,postgres.username,postgres.password)
                // Register before statement creation: every partial acquisition has an owner.
                acquired.add(connection)
                def statement=connection.prepareStatement('select pg_try_advisory_lock(?,?)')
                try{statement.setInt(1,NAMESPACE);statement.setInt(2,slot);def rows=statement.executeQuery();try{assert rows.next();assert rows.getBoolean(1)}finally{rows.close()}}finally{statement.close()}
            }
            return acquired
        }catch(Throwable primary){
            acquired.each{connection->try{connection.close()}catch(Throwable cleanup){primary.addSuppressed(cleanup)}}
            throw primary
        }
    }
    def allReusable(){
        def connections=fixtureSlots(16)
        try{assert locks()==16;true}finally{connections.each{it.close()}}
    }
    Socket socket(int port){
        def socket=new Socket()
        socket.connect(new InetSocketAddress('127.0.0.1',port),2000)
        socket.soTimeout=7000
        socket
    }
    void headers(Socket socket,int port,int length,String key=KEY){
        String request="POST /internal/sso-login-proofs HTTP/1.1\r\nHost: 127.0.0.1:${port}\r\nContent-Type: application/json\r\nContent-Length: ${length}\r\nConnection: keep-alive\r\nX-ArMoney-Login-Proof-Write-Key: ${key}\r\n\r\n"
        socket.outputStream.write(request.getBytes(java.nio.charset.StandardCharsets.US_ASCII));socket.outputStream.flush()
    }
    static String line(InputStream input){
        def bytes=new ByteArrayOutputStream()
        for(int size=0;size<8192;size++){
            int value=input.read();if(value<0)throw new EOFException('synthetic peer closed')
            if(value==10)return bytes.toString(java.nio.charset.StandardCharsets.US_ASCII).replaceAll('\\r$','')
            bytes.write(value)
        }
        throw new IOException('synthetic oversized response line')
    }
    static int response(InputStream input){
        String first=line(input);assert first.startsWith('HTTP/1.1 ')
        int status=Integer.parseInt(first.split(' ')[1])
        def headers=[:];String header
        while(!(header=line(input)).isEmpty()){int colon=header.indexOf(':');assert colon>0;headers[header.substring(0,colon).toLowerCase()]=header.substring(colon+1).trim()}
        if(headers['transfer-encoding']?.equalsIgnoreCase('chunked')){
            while(true){int size=Integer.parseInt(line(input).split(';')[0],16);if(size==0){while(!line(input).isEmpty()){};break};assert input.readNBytes(size).length==size;assert line(input).isEmpty()}
        }else{assert headers.containsKey('content-length');int size=Integer.parseInt(headers['content-length']);assert input.readNBytes(size).length==size}
        status
    }
    def noBusinessWrites(){
        ['sso_login_proofs','sso_login_events','sso_login_proof_commands','sessions','verification_delivery_outbox'].every{rows(it)==0}
    }
    def fiveConnectionsReusable(int port){
        def blocker=DriverManager.getConnection(postgres.jdbcUrl,postgres.username,postgres.password)
        def workers=Executors.newFixedThreadPool(5)
        try{
            blocker.autoCommit=false;blocker.createStatement().execute('select id from verification_control where id=1 for update')
            def requests=(1..5).collect{workers.submit({post(port).statusCode()} as Callable<Integer>)}
            awaitCondition{locks()==5}
            awaitCondition{database.transaction{it.fetchOne("select count(distinct pid) from pg_stat_activity where datname=current_database() and wait_event_type='Lock' and query like '%verification_control%' and pid<>pg_backend_pid()").get(0,Long)}==5}
            blocker.rollback()
            assert requests.collect{it.get(10,TimeUnit.SECONDS)}==[400]*5
            awaitCondition{locks()==0}
            true
        }finally{
            blocker.rollback();blocker.close();workers.shutdownNow();assert workers.awaitTermination(3,TimeUnit.SECONDS)
        }
    }
    def 'absolute inbound body deadline closes authenticated #mode despite traffic and releases database resources'(){
        given:
        int port=start(true)
        def peer=socket(port)
        def executor=Executors.newSingleThreadExecutor()
        def stopped=new java.util.concurrent.atomic.AtomicBoolean(false)
        long started=System.nanoTime()
        headers(peer,port,1000)
        def writer=executor.submit({
            try{
                peer.outputStream.write(123);peer.outputStream.flush()
                if(mode=='drip')while(!stopped.get()){Thread.sleep(200);peer.outputStream.write(32);peer.outputStream.flush()}
            }catch(IOException ignored){}catch(InterruptedException interrupted){Thread.currentThread().interrupt()}
        } as Runnable)
        when:
        def bytes=new ByteArrayOutputStream()
        boolean physicallyClosed=false
        try{
            for(int size=0;size<8192;size++){int value=peer.inputStream.read();if(value<0){physicallyClosed=true;break};bytes.write(value)}
        }catch(SocketTimeoutException timeout){throw timeout}
        catch(SocketException closed){physicallyClosed=true}
        long elapsed=TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started)
        then:
        physicallyClosed
        elapsed>=3000 && elapsed<=6500
        !bytes.toString(java.nio.charset.StandardCharsets.US_ASCII).startsWith('HTTP/1.1 200')
        awaitCondition{locks()==0}
        database.transaction{it.fetchOne('select count(*) from pg_stat_activity where datname=current_database() and application_name like \'PostgreSQL JDBC Driver%\' and state=\'idle in transaction\'').get(0,Long)}==0
        database.transaction{it.fetchOne('select credits from sso_login_proof_admission').get(0,BigDecimal)}==19
        noBusinessWrites();allReusable()
        when:
        stopped.set(true);peer.close();writer.get(3,TimeUnit.SECONDS)
        then:
        post(port).statusCode()==400
        fiveConnectionsReusable(port)
        noBusinessWrites();locks()==0
        cleanup:
        stopped.set(true);peer?.close();executor?.shutdownNow();assert executor.awaitTermination(3,TimeUnit.SECONDS)
        where:mode << ['stall','drip']
    }
    def 'slow unauthenticated body consumes no gate or admission and returns generic credential denial'(){
        given:int port=start(true);def peer=socket(port);long started=System.nanoTime()
        when:
        headers(peer,port,1000,'wrong-synthetic-key')
        peer.outputStream.write(123);peer.outputStream.flush()
        int status=response(new BufferedInputStream(peer.inputStream))
        then:
        status==401
        TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started)<3000
        rows('sso_login_proof_admission')==0;locks()==0;noBusinessWrites()
        cleanup:peer?.close()
    }
    def 'completed near-boundary body cannot leave a watchdog that closes the next keepalive request'(){
        given:int port=start(true);def peer=socket(port);def input=new BufferedInputStream(peer.inputStream)
        when:
        headers(peer,port,2)
        peer.outputStream.write(123);peer.outputStream.flush()
        Thread.sleep(3100)
        peer.outputStream.write(125);peer.outputStream.flush()
        int first=response(input)
        Thread.sleep(1200) // Cross the first request's absolute deadline on this same socket.
        headers(peer,port,2)
        peer.outputStream.write('{}'.getBytes(java.nio.charset.StandardCharsets.US_ASCII));peer.outputStream.flush()
        int second=response(input)
        then:
        first==400;second==400
        database.transaction{it.fetchOne('select credits from sso_login_proof_admission').get(0,BigDecimal)}==18
        noBusinessWrites();locks()==0;allReusable()
        cleanup:peer?.close()
    }
    def 'fixture partial acquisition failure releases every earlier connection and advisory slot'(){
        given:
        def blocker=DriverManager.getConnection(postgres.jdbcUrl,postgres.username,postgres.password)
        def statement=blocker.prepareStatement('select pg_try_advisory_lock(?,?)')
        try{statement.setInt(1,NAMESPACE);statement.setInt(2,1);def result=statement.executeQuery();try{assert result.next();assert result.getBoolean(1)}finally{result.close()}}finally{statement.close()}
        when:fixtureSlots(2)
        then:
        thrown(AssertionError)
        locks()==1
        when:
        blocker.close()
        then:locks()==0;allReusable()
        cleanup:blocker?.close()
    }
    def 'two actual AuthMain processes share all sixteen slots and reject before parse or admission'(){
        given:
        int first=start(true)
        int second=start(true)
        assert post(first,'wrong-synthetic-key').statusCode()==401
        assert post(second,null).statusCode()==401
        assert rows('sso_login_proof_admission')==0
        assert locks()==0
        def held=fixtureSlots(14)
        def blocker=DriverManager.getConnection(postgres.jdbcUrl,postgres.username,postgres.password)
        blocker.autoCommit=false;blocker.createStatement().execute('select id from verification_control where id=1 for update')
        def executor=Executors.newFixedThreadPool(2)
        def pending=[first,second].collect{port->executor.submit({post(port).statusCode()} as Callable<Integer>)}
        awaitCondition{locks()==16}
        awaitCondition{database.transaction{it.fetchOne("select count(*) from pg_stat_activity where datname=current_database() and wait_event_type='Lock' and query like '%verification_control%' and pid<>pg_backend_pid()").get(0,Long)}==2}
        when:
        long started=System.nanoTime()
        def refused=post(second)
        then:
        refused.statusCode()==429
        refused.headers().firstValue('Retry-After').get()=='60'
        TimeUnit.NANOSECONDS.toSeconds(System.nanoTime()-started)<5
        rows('sso_login_proof_admission')==0
        when:
        blocker.rollback()
        def outcomes=pending.collect{it.get(10,TimeUnit.SECONDS)}
        held.each{it.close()};held.clear()
        then:
        outcomes==[400,400]
        awaitCondition{locks()==0}
        database.transaction{it.fetchOne('select credits from sso_login_proof_admission').get(0,BigDecimal)}==18
        allReusable()
        rows('sso_login_proofs')==0;rows('sso_login_events')==0;rows('sessions')==0
        when:
        def saturated=fixtureSlots(16)
        try{assert post(first).statusCode()==429;assert post(second).statusCode()==429}finally{saturated.each{it.close()}}
        then:
        locks()==0;allReusable()
        cleanup:
        blocker?.rollback();blocker?.close();held?.each{it.close()};executor?.shutdownNow()
    }
    def 'actual default-off AuthMain leaves proof transport absent and allocates no admission'(){
        given:int port=start(false)
        expect:
        post(port).statusCode()==404
        rows('sso_login_proof_admission')==0
        locks()==0
    }
}
