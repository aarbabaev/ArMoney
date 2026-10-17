package com.arman.bank.authservice

import com.arman.bank.runtime.Database
import org.testcontainers.containers.PostgreSQLContainer
import spock.lang.Specification
import java.net.http.*
import java.nio.file.*
import java.time.Duration
import java.util.concurrent.*

/** Real child JVM running TEST-ONLY explicit C2 adapters on loopback. Not AuthMain or provider activation.
 * Run only in the coordinator's isolated runtime; no persistent database/stack fixtures. */
class CodeIntentConfirmationProcessesIntegrationSpec extends Specification {
    PostgreSQLContainer postgres
    Database database
    Process child
    HttpClient client=HttpClient.newHttpClient()
    def setup(){postgres=new PostgreSQLContainer('postgres:17.6-alpine');postgres.start();database=new Database(postgres.jdbcUrl,postgres.username,postgres.password)}
    def cleanup(){
        if(child!=null){child.destroy();if(!child.waitFor(8,TimeUnit.SECONDS)){child.destroyForcibly();assert child.waitFor(5,TimeUnit.SECONDS)}}
        client?.close();database?.close();postgres?.stop()
    }
    int start(boolean enabled=true){
        int port
        new ServerSocket(0,0,InetAddress.loopbackAddress).withCloseable{port=it.localPort}
        def java=Path.of(System.getProperty('java.home'),'bin',System.getProperty('os.name').toLowerCase().contains('win')?'java.exe':'java').toString()
        def classes=Path.of('build','classes','java','test').toAbsolutePath();def lib=Path.of('build','install','auth-service','lib').toAbsolutePath()
        assert Files.isDirectory(classes);assert Files.isDirectory(lib)
        def builder=new ProcessBuilder(java,'-Xms32m','-Xmx256m','-cp',classes.toString()+File.pathSeparator+lib.toString()+File.separator+'*','com.arman.bank.authservice.CodeIntentConfirmationHttpFixture')
        def env=builder.environment();env.keySet().removeIf{it.startsWith('AUTH_')||it.startsWith('SSO_')||it.startsWith('ARMONEY_')||it.startsWith('INTERNAL_')||it.startsWith('FIXTURE_')}
        env.putAll([FIXTURE_C2_ENABLED:enabled.toString(),DB_URL:postgres.jdbcUrl,DB_USER:postgres.username,DB_PASSWORD:postgres.password,PORT:port.toString()])
        def log=Files.createTempFile('armoney-c2-http-test-','.log');child=builder.redirectErrorStream(true).redirectOutput(log.toFile()).start()
        boolean ready=false;long limit=System.nanoTime()+TimeUnit.SECONDS.toNanos(30)
        while(!ready&&child.alive&&System.nanoTime()<limit){
            try{ready=client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:${port}/fixture-ready")).timeout(Duration.ofSeconds(2)).GET().build(),HttpResponse.BodyHandlers.discarding()).statusCode()==200}catch(IOException ignored){}
            if(!ready)Thread.sleep(50)
        }
        assert ready:"Test-only C2 process unavailable; synthetic private log ${log}"
        port
    }
    static String key(String operation){operation=='key'?CodeIntentConfirmationHttpFixture.LOOKUP_KEY:CodeIntentConfirmationHttpFixture.CONFIRM_KEY}
    static String header(String operation){operation=='key'?'X-ArMoney-Code-Intent-Lookup-Key':'X-ArMoney-Login-Proof-Confirm-Key'}
    static void send(Socket peer,int port,String operation,int length,String credential,String partial=''){
        String wire="POST /internal/sso-code-intents/${operation} HTTP/1.1\r\nHost: 127.0.0.1:${port}\r\nContent-Type: application/json\r\nContent-Length: ${length}\r\n${header(operation)}: ${credential}\r\nConnection: keep-alive\r\n\r\n${partial}"
        peer.outputStream.write(wire.getBytes(java.nio.charset.StandardCharsets.US_ASCII));peer.outputStream.flush()
    }
    long count(String table){database.transaction{it.fetchOne('select count(*) from '+table).get(0,Long)}}
    long locks(){database.transaction{it.fetchOne("select count(*) from pg_locks where locktype='advisory' and classid=cast(? as oid) and objsubid=2",0x41524D4F).get(0,Long)}}
    boolean released(){long limit=System.nanoTime()+TimeUnit.SECONDS.toNanos(6);while(locks()!=0&&System.nanoTime()<limit)Thread.sleep(20);assert locks()==0
        assert database.transaction{it.fetchOne("select count(*) from pg_stat_activity where datname=current_database() and state='idle in transaction'").get(0,Integer)==0};true}
    boolean noWrites(){['sso_code_confirmation_anchors','sso_code_confirmation_results','sso_login_events','sso_login_proofs','sessions','sso_login_creation_reservations'].every{count(it)==0}}
    static int response(Socket peer){
        def bytes=new ByteArrayOutputStream();int previous=0;int value
        while(bytes.size()<4096&&(value=peer.inputStream.read())!=-1){bytes.write(value);previous=(previous<<8)|value
            if(bytes.size()>=4&&previous==0x0d0a0d0a)break}
        String headers=bytes.toString(java.nio.charset.StandardCharsets.US_ASCII)
        assert headers.contains('\r\n\r\n')
        int size=(headers.readLines().find{it.toLowerCase().startsWith('content-length:')}?.split(':',2)?.last()?.strip()?:'0') as int
        assert peer.inputStream.readNBytes(size).length==size
        Integer.parseInt(headers.split(' ',3)[1])
    }
    def 'absolute body deadline closes #mode #operation and releases backend and all permits without writes'(){
        given:int port=start();def peer=new Socket('127.0.0.1',port);peer.soTimeout=7000
        def executor=Executors.newSingleThreadExecutor();Future dripping=null;long started=System.nanoTime()
        when:
        send(peer,port,operation,1000,key(operation),'{')
        if(mode=='drip')dripping=executor.submit({try{for(int i=0;i<30;i++){Thread.sleep(200);peer.outputStream.write(32);peer.outputStream.flush()}}catch(IOException closed){};true} as Callable)
        boolean closed=false
        try{closed=peer.inputStream.read()==-1}catch(SocketTimeoutException timeout){throw timeout}catch(SocketException physicalClose){closed=true}
        long elapsed=TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started)
        then:closed;elapsed>=3000&&elapsed<=6500;released();noWrites();count('sso_login_proof_admission')==1
        cleanup:peer?.close();dripping?.cancel(true);executor.shutdownNow()
        where:[operation,mode] << [['key','confirm'],['stall','drip']].combinations()
    }
    def 'wrong credential with stalled body does not acquire permit or spend admission'(){
        given:int port=start();def peer=new Socket('127.0.0.1',port);peer.soTimeout=2500
        when:send(peer,port,operation,1000,'wrong','{');int status=response(peer)
        then:status==401;locks()==0;count('sso_login_proof_admission')==0;noWrites()
        cleanup:peer?.close()
        where:operation << ['key','confirm']
    }
    def 'completed near-boundary body cancels timer before keepalive connection is reused'(){
        given:int port=start();def peer=new Socket('127.0.0.1',port);peer.soTimeout=7000
        when:
        send(peer,port,operation,2,key(operation),'{');Thread.sleep(3000);peer.outputStream.write(125);peer.outputStream.flush()
        int first=response(peer);Thread.sleep(1500);send(peer,port,operation,2,key(operation),'{}');int next=response(peer)
        then:first==400;next==400;released();noWrites()
        cleanup:peer?.close()
        where:operation << ['key','confirm']
    }
    def 'unselected test fixture does not register C2 handlers or admit'(){
        given:int port=start(false)
        when:def result=client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:${port}/internal/sso-code-intents/${operation}")).timeout(Duration.ofSeconds(5)).header(header(operation),key(operation)).header('Content-Type','application/json').POST(HttpRequest.BodyPublishers.ofString('{}')).build(),HttpResponse.BodyHandlers.ofString())
        then:result.statusCode()==404;count('sso_login_proof_admission')==0;locks()==0
        where:operation << ['key','confirm']
    }
}
