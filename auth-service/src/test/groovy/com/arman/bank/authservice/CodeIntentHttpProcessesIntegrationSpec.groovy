package com.arman.bank.authservice

import com.arman.bank.runtime.Database
import groovy.json.JsonOutput
import org.testcontainers.containers.PostgreSQLContainer
import spock.lang.Specification
import java.net.http.*
import java.nio.file.*
import java.time.Duration
import java.util.concurrent.TimeUnit

/** Real child JVM serving the production stage adapter via an explicit test-only entry point.
 * Not AuthMain, provider activation, confirmation or positive authority acceptance. */
class CodeIntentHttpProcessesIntegrationSpec extends Specification {
    PostgreSQLContainer postgres
    Database database
    HttpClient client=HttpClient.newHttpClient()
    Process child
    Map environment=CodeIntentConfigurationSpec.environment()
    def setup(){
        postgres=new PostgreSQLContainer('postgres:17.6-alpine');postgres.start()
        database=new Database(postgres.jdbcUrl,postgres.username,postgres.password)
        CodeIntentHttpIntegrationSpec.seed(database,environment.SSO_ISSUER,UUID.randomUUID(),UUID.randomUUID())
    }
    def cleanup(){
        if(child!=null){child.destroy();if(!child.waitFor(8,TimeUnit.SECONDS)){child.destroyForcibly();assert child.waitFor(5,TimeUnit.SECONDS)}}
        client?.close();database?.close();postgres?.stop()
    }
    int start(boolean enabled){
        int port
        new ServerSocket(0,0,InetAddress.loopbackAddress).withCloseable{port=it.localPort}
        def java=Path.of(System.getProperty('java.home'),'bin',System.getProperty('os.name').toLowerCase().contains('win')?'java.exe':'java').toString()
        def classes=Path.of('build','classes','java','test').toAbsolutePath()
        def lib=Path.of('build','install','auth-service','lib').toAbsolutePath()
        assert Files.isDirectory(classes);assert Files.isDirectory(lib)
        def builder=new ProcessBuilder(java,'-Xms32m','-Xmx256m','-cp',classes.toString()+File.pathSeparator+lib.toString()+File.separator+'*','com.arman.bank.authservice.CodeIntentHttpProcessFixture')
        def env=builder.environment()
        env.keySet().removeIf{it.startsWith('AUTH_')||it.startsWith('SSO_')||it.startsWith('VERIFICATION_')||it.startsWith('ARMONEY_')||it.startsWith('INTERNAL_')}
        env.putAll(environment)
        env.putAll([AUTH_SSO_CODE_INTENT_STAGE_ENABLED:enabled.toString(),DB_URL:postgres.jdbcUrl,DB_USER:postgres.username,DB_PASSWORD:postgres.password,PORT:port.toString()])
        Path log=Files.createTempFile('armoney-stage-fixture-','.log')
        child=builder.redirectErrorStream(true).redirectOutput(log.toFile()).start()
        boolean ready=false
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(30)
        while(!ready&&child.alive&&System.nanoTime()<end){
            try{ready=client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:${port}/fixture-ready")).timeout(Duration.ofSeconds(2)).GET().build(),HttpResponse.BodyHandlers.discarding()).statusCode()==200}
            catch(IOException ignored){}
            if(!ready)Thread.sleep(50)
        }
        assert ready:"Test-only stage child unavailable; private synthetic log ${log}"
        if(enabled){
            long cleanupEnd=System.nanoTime()+TimeUnit.SECONDS.toNanos(5)
            while(!(database.transaction{it.fetchOne('select healthy from sso_code_intent_cleanup where id=1').get(0,Boolean)})&&System.nanoTime()<cleanupEnd)Thread.sleep(20)
            assert database.transaction{it.fetchOne('select healthy from sso_code_intent_cleanup where id=1').get(0,Boolean)}
        }
        port
    }
    def post(int port,String body,String key=environment.SSO_LOGIN_PROOF_WRITE_KEY){
        def request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:${port}/internal/sso-code-intents")).timeout(Duration.ofSeconds(6)).header('Content-Type','application/json').header('X-ArMoney-Login-Proof-Write-Key',key)
        client.send(request.POST(HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString())
    }
    long count(String table){database.transaction{it.fetchOne('select count(*) from '+table).get(0,Long)}}
    long locks(){database.transaction{it.fetchOne("select count(*) from pg_locks where locktype='advisory' and classid=cast(? as oid) and objsubid=2",0x41524D4F).get(0,Long)}}
    boolean released(){long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(6);while(locks()!=0&&System.nanoTime()<end)Thread.sleep(20);assert locks()==0;true}
    def 'child process serves actual stage factory with noncredential replay and no authority writes'(){
        given:int port=start(true);String body=JsonOutput.toJson(CodeIntentHttpIntegrationSpec.command(environment.SSO_ISSUER))
        when:def first=post(port,body);def retry=post(port,body)
        then:first.statusCode()==200;retry.statusCode()==200;first.body()==retry.body();count('sso_code_intents')==1
        ['sso_login_proofs','sso_login_events','sessions','banking_eligibility_grants'].every{count(it)==0}
        released()
    }
    def 'child process absolute body deadline physically closes authenticated stalled stage input'(){
        given:int port=start(true);def peer=new Socket('127.0.0.1',port);peer.soTimeout=7000
        long started=System.nanoTime()
        String headers="POST /internal/sso-code-intents HTTP/1.1\r\nHost: 127.0.0.1:${port}\r\nContent-Type: application/json\r\nContent-Length: 1000\r\nX-ArMoney-Login-Proof-Write-Key: ${environment.SSO_LOGIN_PROOF_WRITE_KEY}\r\n\r\n{"
        when:
        peer.outputStream.write(headers.getBytes(java.nio.charset.StandardCharsets.US_ASCII));peer.outputStream.flush()
        boolean closed=false
        try{closed=peer.inputStream.read()==-1}catch(SocketTimeoutException timeout){throw timeout}catch(SocketException physicalClose){closed=true}
        long elapsed=TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started)
        then:closed;elapsed>=3000&&elapsed<=6500;released();count('sso_code_intents')==0;count('sso_login_proofs')==0;count('sessions')==0
        cleanup:peer?.close()
    }
    def 'disabled child factory registers no stage handler or admission'(){
        given:int port=start(false)
        expect:post(port,'{}').statusCode()==404;count('sso_login_proof_admission')==0;locks()==0
    }
}
