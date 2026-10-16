package com.arman.bank.authservice.infrastructure

import com.arman.bank.authservice.domain.IdentityVerification.*
import com.arman.bank.authservice.domain.IdentityVerification.Flow
import com.arman.bank.authservice.domain.Verification
import com.sun.net.httpserver.HttpServer
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import java.net.http.HttpClient
import java.time.*
import java.util.concurrent.*
import spock.lang.Specification

/** Synthetic loopback transport checks only; provider two-node visibility has independent acceptance. */
class VerificationObservationsTransportSpec extends Specification {
    static final String KEY='synthetic-observation-private-credential'
    static final String ISSUER='https://issuer.example/sso/realms/armoney'
    static final Policy POLICY=Policy.defaults([ISSUER] as Set,['armoney-ios'] as Set,['sso-provider'] as Set)
    def flow=new Flow(ISSUER,'subject','armoney-ios','root','tab',Base64.urlEncoder.withoutPadding().encodeToString(new byte[32]))
    def anchor=Instant.parse('2026-10-16T10:00:00Z')
    def requests=new CopyOnWriteArrayList<Map>()
    def executor=Executors.newFixedThreadPool(2)
    def release=new CountDownLatch(1)
    HttpServer server
    HttpVerificationObservations transport
    int status=200
    String contentType='application/json'
    String response
    byte[] rawBytes
    boolean stall
    Map observed(){[issuer:flow.issuer(),subject:flow.subject(),client_id:flow.clientId(),provider_root_session_id:flow.rootSessionId(),provider_tab_id:flow.tabId(),
        authentication_session_id:flow.authenticationSessionId(),username:'+971501234567',email:'fixture@example.test',phone_number:'+971501234567',
        enabled:true,authentication_session_active:true,authentication_session_expires_at:anchor.plusSeconds(60).toString()]}
    def setup(){
        response=JsonOutput.toJson(observed())
        server=HttpServer.create(new InetSocketAddress('127.0.0.1',0),0)
        server.executor=executor
        server.createContext('/') { exchange ->
            try{
                requests.add([path:exchange.requestURI.toString(),method:exchange.requestMethod,key:exchange.requestHeaders.getFirst('X-ArMoney-Auth-Status-Key'),
                    bridge:exchange.requestHeaders.getFirst('X-ArMoney-SSO-Bridge-Key'),body:exchange.requestBody.readAllBytes()])
                exchange.responseHeaders.set('Content-Type',contentType)
                if(status==302)exchange.responseHeaders.set('Location',"http://127.0.0.1:${server.address.port}/redirect")
                byte[] bytes=rawBytes!=null?rawBytes:response.getBytes(java.nio.charset.StandardCharsets.UTF_8)
                exchange.sendResponseHeaders(status,bytes.length)
                if(stall)release.await(6,TimeUnit.SECONDS)
                exchange.responseBody.write(bytes)
            }catch(IOException ignored){}finally{exchange.close()}
        }
        server.start()
        def client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).followRedirects(HttpClient.Redirect.NEVER).build()
        transport=new HttpVerificationObservations(URI.create("http://127.0.0.1:${server.address.port}"),KEY,POLICY,client)
    }
    def cleanup(){release.countDown();transport.close();server.stop(0);executor.shutdownNow();assert executor.awaitTermination(5,TimeUnit.SECONDS)}
    def 'exact twelve-field response preserves DB anchor and request has only six canonical binding names'(){
        when:
        def result=transport.observe(flow,anchor)
        then:
        result.observationStartedAt()==anchor
        result.flow()==flow
        result.sessionExpiresAt()==anchor.plusSeconds(60)
        requests.size()==1
        requests[0].path=='/sso/realms/armoney/armoney-status/enrollment'
        requests[0].method=='POST'
        requests[0].key==KEY
        requests[0].bridge==null
        new JsonSlurper().parse(requests[0].body as byte[]).keySet()==['issuer','subject','client_id','provider_root_session_id','provider_tab_id','authentication_session_id'] as Set
        !transport.toString().contains(KEY)
    }
    def 'non-success and redirects deny without retry or alternate path'(){
        given:
        status=code
        when:
        transport.observe(flow,anchor)
        then:
        def failure=thrown(Verification.Failure)
        failure.error()==Verification.Error.UNAVAILABLE
        requests.size()==1
        where:
        code << [201,302,400,401,404,409,410,429,503]
    }
    def 'wrong tuple contacts flags timestamps and unknown fields cannot become observations'(){
        given:
        def changed=observed()
        changed[field]=value
        response=JsonOutput.toJson(changed)
        when:
        transport.observe(flow,anchor)
        then:
        def failure=thrown(Verification.Failure)
        failure.error()==Verification.Error.UNAVAILABLE
        where:
        field                              | value
        'subject'                          | 'other'
        'provider_root_session_id'         | 'other'
        'provider_tab_id'                  | 'other'
        'client_id'                        | 'other'
        'authentication_session_id'        | 'B'*43
        'username'                         | '+971501234568'
        'email'                            | 'Mixed@Example.test'
        'phone_number'                     | '0501234567'
        'enabled'                          | false
        'enabled'                          | 'true'
        'authentication_session_active'    | false
        'authentication_session_expires_at' | '2026-10-16T10:01:00+00:00'
        'authentication_session_expires_at' | '2026-10-16T10:00:00Z'
        'verification_epoch'               | 1
        'observation_started_at'           | '2026-10-16T10:00:04Z'
    }
    def 'duplicate trailing malformed oversized and wrong content type responses deny'(){
        given:
        response=raw
        contentType=type
        when:
        transport.observe(flow,anchor)
        then:
        thrown(Verification.Failure)
        where:
        raw                              | type
        '{"enabled":true,"enabled":true}' | 'application/json'
        '{} {}'                          | 'application/json'
        '{'                              | 'application/json'
        'x'*12289                        | 'application/json'
        '{}'                             | 'text/plain'
    }
    def 'four-second complete exchange deadline includes stalled response body'(){
        given:
        stall=true
        when:
        long began=System.nanoTime()
        transport.observe(flow,anchor)
        then:
        thrown(Verification.Failure)
        Duration.ofNanos(System.nanoTime()-began).toMillis()<5500
        requests.size()==1
    }
    def 'closed transport denies without network and public constructor rejects loopback HTTP'(){
        when:
        transport.close()
        transport.observe(flow,anchor)
        then:
        thrown(Verification.Failure)
        requests.empty
        when:
        new HttpVerificationObservations(URI.create("http://127.0.0.1:${server.address.port}"),KEY,POLICY)
        then:
        thrown(IllegalArgumentException)
    }
    def 'malformed UTF8 cannot be reinterpreted as another JSON encoding'(){
        given:
        rawBytes=[(byte)0xc3,(byte)0x28] as byte[]
        when:
        transport.observe(flow,anchor)
        then:
        thrown(Verification.Failure)
    }
    def 'interruption cancels observation and preserves the interrupt flag'(){
        given:
        Thread.currentThread().interrupt()
        when:
        transport.observe(flow,anchor)
        then:
        thrown(Verification.Failure)
        Thread.currentThread().isInterrupted()
        cleanup:
        Thread.interrupted()
    }
}
