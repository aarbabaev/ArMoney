package com.arman.bank.authservice.infrastructure

import com.arman.bank.authservice.application.MigrationProviderReadinessPort as R
import com.arman.bank.authservice.domain.LoginProof as L
import com.sun.net.httpserver.HttpServer
import groovy.json.*
import spock.lang.Specification
import java.time.*
import java.util.concurrent.*

/** Actual bounded loopback HTTP, synthetic provider facts only. */
class HttpMigrationProviderReadinessSpec extends Specification {
    static final String ISSUER='https://issuer.example/sso/realms/armoney'
    static final String PATH='/sso/realms/armoney/armoney-status/code-session/v2'
    static final String REDIRECT='https://migration.example/migration/managed/callback'
    static final String KEY='synthetic-readiness-private-key-123456'
    HttpServer server
    ExecutorService threads
    HttpMigrationProviderReadiness adapter
    R.Request query=new R.Request(R.Purpose.MIGRATION_MANAGED,REDIRECT,new L.Binding(ISSUER,'synthetic-subject','managed-client','synthetic-session'))
    List requests=new CopyOnWriteArrayList()
    Map response
    CountDownLatch responseEntered
    CountDownLatch responseRelease
    String raw
    int status=200
    String contentType='application/json'
    def setup(){
        def now=Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS)
        response=[protocol_version:2,purpose:'MIGRATION_MANAGED',original_redirect_uri:REDIRECT,provider_email_verified:true,
            facts:[issuer:ISSUER,subject:'synthetic-subject',client_id:'managed-client',user_session_id:'synthetic-session',username:'+971501234567',email:'fixture@example.test',phone_number:'+971501234567',enabled:true,user_session_active:true,client_session_active:true,observed_at:now.toString(),valid_until:now.plusSeconds(5).toString()]]
        server=HttpServer.create(new InetSocketAddress('127.0.0.1',0),0)
        threads=Executors.newFixedThreadPool(2);server.executor=threads
        server.createContext(PATH){exchange->
            try{
                assert exchange.requestMethod=='POST'
                assert exchange.requestHeaders.getFirst('X-ArMoney-Auth-Status-Key')==KEY
                requests.add(new JsonSlurper().parse(exchange.requestBody))
                if(responseEntered!=null){responseEntered.countDown();assert responseRelease.await(3,TimeUnit.SECONDS)}
                byte[] bytes=(raw?:JsonOutput.toJson(response)).getBytes('UTF-8')
                exchange.responseHeaders.set('Content-Type',contentType)
                exchange.sendResponseHeaders(status,bytes.length);exchange.responseBody.write(bytes)
            }finally{exchange.close()}
        }
        server.start()
        def scopes=new CodeIntentPairScopes(JsonOutput.toJson([[issuer:ISSUER,client_id:'managed-client']]),'[]')
        adapter=new HttpMigrationProviderReadiness(URI.create('http://127.0.0.1:'+server.address.port),KEY,scopes,HttpMigrationProviderReadiness.privateClient())
    }
    def cleanup(){adapter?.close();server?.stop(0);threads?.shutdownNow();threads?.awaitTermination(3,TimeUnit.SECONDS)}
    def observe(){adapter.observe(query,Instant.now(),Duration.ofSeconds(4))}
    def 'exact V2 request and unchanged exact12 facts round trip with original purpose and redirect'(){
        when:def actual=observe()
        then:actual.requireReady(query).binding()==query.binding()
        actual.providerEmailVerified()
        requests==[[protocol_version:2,purpose:'MIGRATION_MANAGED',original_redirect_uri:REDIRECT,issuer:ISSUER,subject:'synthetic-subject',client_id:'managed-client',user_session_id:'synthetic-session']]
        !actual.toString().contains('fixture@example.test')
        !query.toString().contains('synthetic-subject')
    }
    def 'literal false is retained as negative provider evidence and cannot become ready'(){
        given:response.provider_email_verified=false
        when:def actual=observe()
        then:!actual.providerEmailVerified()
        when:actual.requireReady(query)
        then:thrown(L.Failure)
        requests.size()==1
    }
    def 'invalid exact envelope cannot be accepted or retried'(){
        given:
        if(fault=='missing')response.remove('provider_email_verified')
        if(fault=='extra')response.extra=true
        if(fault=='null')response.provider_email_verified=null
        if(fault=='text')response.provider_email_verified='true'
        if(fault=='number')response.provider_email_verified=1
        if(fault=='version-type')response.protocol_version=2.0
        if(fault=='version')response.protocol_version=1
        if(fault=='purpose')response.purpose='MIGRATION_ACCOUNT'
        if(fault=='redirect')response.original_redirect_uri='https://foreign.example/callback'
        if(fault=='facts-extra')response.facts.email_verified=true
        if(fault=='facts-missing')response.facts.remove('email')
        if(fault=='binding')response.facts.subject='foreign'
        if(fault=='boolean')response.facts.enabled='true'
        if(fault=='lifetime')response.facts.valid_until=response.facts.observed_at
        if(fault=='canonical-email')response.facts.email='Fixture@example.test'
        if(fault=='duplicate')raw=JsonOutput.toJson(response).replace('"protocol_version":2','"protocol_version":2,"protocol_version":2')
        if(fault=='nested-duplicate')raw=JsonOutput.toJson(response).replace('"enabled":true','"enabled":true,"enabled":true')
        if(fault=='trailing')raw=JsonOutput.toJson(response)+' {}'
        if(fault=='oversized')raw='x'*12289
        when:observe()
        then:thrown(L.Failure)
        requests.size()==1
        where:fault<<['missing','extra','null','text','number','version-type','version','purpose','redirect','facts-extra','facts-missing','binding','boolean','lifetime','canonical-email','duplicate','nested-duplicate','trailing','oversized']
    }
    def 'non200 and redirects never cause another credentialed request'(){
        given:status=code
        when:observe()
        then:thrown(L.Failure)
        requests.size()==1
        where:code<<[302,400,401,409,429,503]
    }
    def 'closing an in-flight owned adapter cancels the existing request without retry'(){
        given:
        responseEntered=new CountDownLatch(1);responseRelease=new CountDownLatch(1)
        def executor=Executors.newSingleThreadExecutor()
        def future=executor.submit({observe()} as Callable)
        assert responseEntered.await(2,TimeUnit.SECONDS)
        when:adapter.close();future.get(2,TimeUnit.SECONDS)
        then:def failure=thrown(ExecutionException)
        failure.cause instanceof L.Failure
        requests.size()==1
        cleanup:responseRelease?.countDown();future?.cancel(true);executor?.shutdownNow();executor?.awaitTermination(3,TimeUnit.SECONDS)
    }
    def 'closed adapter and exhausted original budget issue no request'(){
        when:adapter.observe(query,Instant.now(),Duration.ZERO)
        then:thrown(L.Failure)
        requests.empty
        when:adapter.close();observe()
        then:thrown(L.Failure)
        requests.empty
    }
}
