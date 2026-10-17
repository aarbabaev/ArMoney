package com.arman.bank.authservice

import com.arman.bank.runtime.Database
import org.testcontainers.containers.PostgreSQLContainer
import spock.lang.Specification
import java.net.http.*
import java.nio.file.*
import java.time.Duration
import java.util.concurrent.TimeUnit

/** Actual installed AuthMain JVMs and isolated PG only. Run in the coordinator's isolated
 * runtime: Main binds all interfaces. No production provider success or session is simulated. */
class CodeConfirmationMainIntegrationSpec extends Specification {
    static final Map ROUTES=[
        '/internal/sso-code-intents':'X-ArMoney-Login-Proof-Write-Key',
        '/internal/sso-code-intents/key':'X-ArMoney-Code-Intent-Lookup-Key',
        '/internal/sso-code-intents/confirm':'X-ArMoney-Login-Proof-Confirm-Key']
    PostgreSQLContainer postgres
    Database database
    HttpClient client
    List<Process> children=[]
    List<Path> logs=[]
    String application='qa-c2-main-'+UUID.randomUUID()

    def setup(){
        postgres=new PostgreSQLContainer('postgres:17.6-alpine')
        postgres.start()
        database=new Database(postgres.jdbcUrl,postgres.username,postgres.password)
        client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()
        database.transaction{sql->
            sql.execute('create table qa_main_cleanup_sweeps(id bigserial primary key)')
            sql.execute('''create function qa_main_cleanup_sweep() returns trigger language plpgsql as $$
                begin insert into qa_main_cleanup_sweeps default values; return NEW; end $$''')
            sql.execute('create trigger qa_main_cleanup_sweep after update on sso_code_intent_cleanup for each row execute function qa_main_cleanup_sweep()')
        }
    }
    def cleanup(){
        children.each{it.destroy()}
        children.each{if(!it.waitFor(8,TimeUnit.SECONDS)){it.destroyForcibly();assert it.waitFor(5,TimeUnit.SECONDS)}}
        client?.close()
        database?.close()
        postgres?.stop()
    }
    Map selected(){
        CodeConfirmationConfigurationSpec.environment()+[
            INTERNAL_AUTH_KEY:'synthetic-main-internal-service-credential',
            SSO_INTROSPECTION_URL:'http://127.0.0.1:1/realms/fixture/protocol/openid-connect/token/introspect',
            SSO_CLIENT_ID:'armoney-web',SSO_CLIENT_SECRET:'synthetic-main-introspection-client-secret',
            // No provider operation is invoked: these route tests stop at credential/JSON validation.
            AUTH_VERIFICATION_DELIVERY_ENABLED:'false']
    }
    static int freePort(){
        int port
        new ServerSocket(0,0,InetAddress.loopbackAddress).withCloseable{port=it.localPort}
        port
    }
    Process spawn(int port,Map configuration){
        Path lib=Path.of('build','install','auth-service','lib').toAbsolutePath()
        assert Files.isDirectory(lib):'Actual-Main integration requires installDist'
        String java=Path.of(System.getProperty('java.home'),'bin',System.getProperty('os.name').toLowerCase().contains('win')?'java.exe':'java').toString()
        def builder=new ProcessBuilder(java,'-Xms32m','-Xmx256m','-cp',lib.toString()+File.separator+'*','com.arman.bank.authservice.Main')
        def environment=builder.environment()
        environment.keySet().removeIf{it.startsWith('AUTH_')||it.startsWith('SSO_')||it.startsWith('VERIFICATION_')||it.startsWith('ARMONEY_')||it.startsWith('INTERNAL_')||it.startsWith('LOGIN_')||it.startsWith('MAILTRAP_')||it.startsWith('SMS_')}
        environment.putAll([DB_URL:postgres.jdbcUrl+(postgres.jdbcUrl.contains('?')?'&':'?')+'ApplicationName='+application,
            DB_USER:postgres.username,DB_PASSWORD:postgres.password,PORT:port.toString(),
            INTERNAL_AUTH_KEY:'synthetic-main-internal-service-credential',USER_BASE_URL:'http://127.0.0.1:1'])
        environment.putAll(configuration)
        Path log=Files.createTempFile('armoney-c2-main-synthetic-','.log')
        logs.add(log)
        Process process=builder.redirectErrorStream(true).redirectOutput(log.toFile()).start()
        children.add(process)
        process
    }
    void ready(Process process,int port){
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(30)
        boolean available=false
        while(!available&&process.alive&&System.nanoTime()<deadline){
            try{available=client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:${port}/health/ready")).timeout(Duration.ofSeconds(2)).GET().build(),HttpResponse.BodyHandlers.discarding()).statusCode()==200}
            catch(IOException ignored){}
            if(!available)Thread.sleep(50)
        }
        assert available:"Actual Main not ready; private synthetic logs: ${logs}"
    }
    def post(int port,String path,String header=null,String key=null){
        def request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:${port}${path}")).timeout(Duration.ofSeconds(6)).header('Content-Type','application/json')
        if(header!=null)request.header(header,key)
        client.send(request.POST(HttpRequest.BodyPublishers.ofString('not-json')).build(),HttpResponse.BodyHandlers.ofString())
    }
    long count(String table){database.transaction{it.fetchOne('select count(*) from '+table).get(0,Long)}}
    long connections(){database.transaction{it.fetchOne('select count(*) from pg_stat_activity where application_name=?',application).get(0,Long)}}
    boolean awaitCondition(Closure condition){
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(6)
        while(!condition.call()&&System.nanoTime()<deadline)Thread.sleep(20)
        assert condition.call()
        true
    }
    boolean noBusinessWrites(){
        ['sso_code_intents','sso_code_intent_commands','sso_code_intent_nonces','sso_login_proofs','sso_login_events',
         'sso_login_creation_reservations','sessions'].each{assert count(it)==0}
        true
    }
    def 'default off leaves all three routes absent and standalone C1 remains unregistered'(){
        given:
        int port=freePort()
        def environment=selected()
        if(flag==null)environment.remove('AUTH_SSO_CODE_INTENT_CONFIRMATION_ENABLED')
        else environment.AUTH_SSO_CODE_INTENT_CONFIRMATION_ENABLED=flag
        def process=spawn(port,environment)
        ready(process,port)
        expect:
        ROUTES.each{path,header->assert post(port,path,header,environment.SSO_LOGIN_PROOF_WRITE_KEY).statusCode()==404}
        count('qa_main_cleanup_sweeps')==0
        count('sso_code_intent_encryption_keys')==0
        count('sso_login_proof_admission')==0
        noBusinessWrites()
        where:
        flag << [null,'false']
    }
    def 'incomplete selected configuration fails before any database connection'(){
        given:
        def probe=new ServerSocket(0,0,InetAddress.loopbackAddress)
        probe.soTimeout=250
        def environment=selected()+[SSO_LOGIN_PROOF_CONFIRM_KEY:'',DB_URL:"jdbc:postgresql://127.0.0.1:${probe.localPort}/never_connect".toString()]
        when:
        def process=spawn(freePort(),environment)
        then:
        process.waitFor(15,TimeUnit.SECONDS)
        process.exitValue()!=0
        Files.readString(logs.last()).contains('Invalid code confirmation configuration')
        when:
        probe.accept().withCloseable{throw new AssertionError('Invalid configuration attempted database allocation')}
        then:
        thrown(SocketTimeoutException)
        cleanup:
        probe?.close()
    }
    def 'selected bundle registers all directional guards and starts cleanup only after HTTP'(){
        given:
        int port=freePort()
        def environment=selected()
        def process=spawn(port,environment)
        ready(process,port)
        expect:
        ROUTES.each{path,header->
            assert post(port,path).statusCode()==401
            assert post(port,path,header,environment.INTERNAL_AUTH_KEY).statusCode()==401
        }
        count('sso_login_proof_admission')==0
        awaitCondition{count('qa_main_cleanup_sweeps')>=1}
        when:
        def credentials=['/internal/sso-code-intents':environment.SSO_LOGIN_PROOF_WRITE_KEY,
            '/internal/sso-code-intents/key':environment.SSO_CODE_INTENT_LOOKUP_KEY,
            '/internal/sso-code-intents/confirm':environment.SSO_LOGIN_PROOF_CONFIRM_KEY]
        then:
        ROUTES.each{path,header->assert post(port,path,header,credentials[path]).statusCode()==400}
        count('sso_login_proof_admission')==1
        noBusinessWrites()
        when:
        process.destroy()
        then:
        process.waitFor(15,TimeUnit.SECONDS)
        awaitCondition{connections()==0}
        noBusinessWrites()
    }
    def 'occupied HTTP port closes selected startup resources without starting cleanup'(){
        given:
        def occupied=new ServerSocket(0,0,InetAddress.loopbackAddress)
        when:
        def process=spawn(occupied.localPort,selected())
        then:
        process.waitFor(30,TimeUnit.SECONDS)
        process.exitValue()!=0
        awaitCondition{connections()==0}
        count('qa_main_cleanup_sweeps')==0
        noBusinessWrites()
        cleanup:
        occupied?.close()
    }
}
