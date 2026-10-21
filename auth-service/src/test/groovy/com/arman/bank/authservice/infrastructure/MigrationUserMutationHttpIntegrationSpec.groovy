package com.arman.bank.authservice.infrastructure

import com.arman.bank.runtime.Database
import com.arman.bank.authservice.domain.VerificationProjection
import io.javalin.Javalin
import org.testcontainers.containers.PostgreSQLContainer
import spock.lang.Specification
import spock.util.concurrent.PollingConditions
import java.net.http.*
import java.time.Duration
import java.util.concurrent.*

/** Actual private HTTP/gate/PG framing evidence only; no positive classification or provider authority. */
class MigrationUserMutationHttpIntegrationSpec extends Specification {
    PostgreSQLContainer pg
    Database database
    Javalin app
    MigrationUserRevisionConfiguration configuration
    def setup(){
        pg=new PostgreSQLContainer('postgres:17.6-alpine');pg.start();database=new Database(pg.jdbcUrl,pg.username,pg.password)
        configuration=MigrationUserRevisionConfiguration.from(MigrationUserRevisionConfigurationSpec.environment())
        def projection=new PostgresVerificationProjectionStore(database,VerificationProjection.Policy.defaults(['projection'] as Set),([7]*32) as byte[],{sql->null})
        def store=new PostgresMigrationUserMutationStore(database,projection,'projection',new LegacyMigrationManagedProofCrypto('one',[one:([9]*32) as byte[]]))
        def routes=new MigrationUserMutationRoutes(configuration,store)
        app=Javalin.create{config->routes.configure(config)}.start('127.0.0.1',0)
    }
    def cleanup(){app?.stop();database?.close();pg?.stop()}
    private long count(String table){database.transaction{sql->
        switch(table){
            case 'admission':return sql.fetchOne('select count(*) from sso_login_proof_admission').get(0,Long)
            case 'receipt':return sql.fetchOne('select count(*) from auth_user_mutation_receipts').get(0,Long)
            case 'authority':return sql.fetchOne('select count(*) from migration_banking_authorities').get(0,Long)
            case 'locks':return sql.fetchOne("select count(*) from pg_locks where locktype='advisory' and classid=? and objsubid=2",0x41524D4F).get(0,Long)
            default:throw new IllegalArgumentException('fixture query rejected')
        }
    }}
    def 'bad or duplicated purpose credentials reject before any body parsing shared admission or gate'(){
        given:
        def client=HttpClient.newHttpClient()
        def builder=HttpRequest.newBuilder(URI.create('http://127.0.0.1:'+app.port()+MigrationUserMutationRoutes.PATH)).timeout(Duration.ofSeconds(5)).header('Content-Type','application/json')
        headers.each{builder.header(MigrationUserMutationRoutes.HEADER,it)}
        when:
        def response=client.send(builder.POST(HttpRequest.BodyPublishers.ofString('not-json')).build(),HttpResponse.BodyHandlers.ofString())
        then:
        response.statusCode()==401
        count('admission')==0L
        count('locks')==0L
        count('receipt')==0L
        cleanup:
        client?.close()
        where:
        headers << [[],['wrong'],['m'*32,'m'*32]]
    }
    def 'authenticated malformed body charges exactly one shared admission without business writes'(){
        given:
        def client=HttpClient.newHttpClient()
        when:
        def response=client.send(HttpRequest.newBuilder(URI.create('http://127.0.0.1:'+app.port()+MigrationUserMutationRoutes.PATH)).timeout(Duration.ofSeconds(5)).header('Content-Type','application/json').header(MigrationUserMutationRoutes.HEADER,'m'*32).POST(HttpRequest.BodyPublishers.ofString('{')).build(),HttpResponse.BodyHandlers.ofString())
        then:
        response.statusCode()==400
        count('admission')==1L
        database.transaction{sql->sql.fetchOne('select credits from sso_login_proof_admission').get(0,BigDecimal)}==19G
        count('locks')==0L
        count('receipt')==0L
        count('authority')==0L
        cleanup:
        client?.close()
    }
    def 'authenticated stalled or drip body physically closes at absolute deadline and releases owned resources #drip'(){
        given:
        def socket=new Socket('127.0.0.1',app.port());socket.soTimeout=7000
        def output=socket.outputStream
        String headers='POST '+MigrationUserMutationRoutes.PATH+' HTTP/1.1\r\nHost: localhost\r\nContent-Type: application/json\r\n'+MigrationUserMutationRoutes.HEADER+': '+('m'*32)+'\r\nContent-Length: 1000\r\n\r\n'
        output.write(headers.bytes);output.flush()
        def executor=Executors.newSingleThreadExecutor()
        def future=executor.submit({if(drip)for(int i=0;i<30;i++){try{Thread.sleep(200);output.write(32);output.flush()}catch(IOException writeStopped){break}}} as Runnable)
        long start=System.nanoTime()
        boolean closed=false
        when:
        try{while(socket.inputStream.read()!=-1){};closed=true}catch(SocketException transportClosed){closed=true}
        long elapsed=TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start)
        new PollingConditions(timeout:5,delay:0.05).eventually{assert count('locks')==0L}
        then:
        closed
        elapsed>=3000L
        elapsed<=6500L
        count('locks')==0L
        count('receipt')==0L
        count('authority')==0L
        database.transaction{sql->sql.fetchOne('select 1').get(0,Integer)}==1
        cleanup:
        socket?.close();future?.cancel(true);executor?.shutdownNow()
        assert executor==null||executor.awaitTermination(3,TimeUnit.SECONDS)
        where:
        drip << [false,true]
    }
}
