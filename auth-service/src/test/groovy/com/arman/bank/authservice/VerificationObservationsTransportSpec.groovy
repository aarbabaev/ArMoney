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
    def diagnostics=new CopyOnWriteArrayList<HttpVerificationObservations.Diagnostic>()
    def traces=new CopyOnWriteArrayList<HttpVerificationObservations.Trace>()
    def executor=Executors.newFixedThreadPool(2)
    def release=new CountDownLatch(1)
    HttpServer server
    HttpVerificationObservations transport
    int status=200
    String contentType='application/json'
    String response
    byte[] rawBytes
    boolean stall
    boolean chunked
    boolean drip
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
                exchange.responseHeaders.set('X-Fixture-Node','sso-a')
                exchange.sendResponseHeaders(status,chunked?0:bytes.length)
                if(drip){exchange.responseBody.write(bytes,0,1);exchange.responseBody.flush();release.await(6,TimeUnit.SECONDS)}
                if(stall)release.await(6,TimeUnit.SECONDS)
                if(drip)exchange.responseBody.write(bytes,1,bytes.length-1)
                else exchange.responseBody.write(bytes)
            }catch(IOException ignored){}finally{exchange.close()}
        }
        server.start()
        def client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).followRedirects(HttpClient.Redirect.NEVER).build()
        transport=new HttpVerificationObservations(URI.create("http://127.0.0.1:${server.address.port}"),KEY,POLICY,client,
            { HttpVerificationObservations.Diagnostic value -> diagnostics.add(value) } as java.util.function.Consumer,
            { HttpVerificationObservations.Trace value -> traces.add(value) } as java.util.function.Consumer)
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
        diagnostics*.name()==['HTTP_200','ACCEPTED']
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
        diagnostics*.name()==[code in [400,401,403,404,503] ? 'HTTP_'+code : 'HTTP_OTHER']
        where:
        code << [201,302,400,401,403,404,409,410,429,500,503]
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
        diagnostics*.name().last()=='TIMEOUT'
    }
    def 'closed transport denies without network and public constructor rejects loopback HTTP'(){
        when:
        transport.close()
        transport.observe(flow,anchor)
        then:
        thrown(Verification.Failure)
        requests.empty
        diagnostics*.name()==['CLOSED']
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
        diagnostics*.name()==['HTTP_200','UTF8']
    }
    def 'interruption cancels observation and preserves the interrupt flag'(){
        given:
        Thread.currentThread().interrupt()
        when:
        transport.observe(flow,anchor)
        then:
        thrown(Verification.Failure)
        Thread.currentThread().isInterrupted()
        diagnostics*.name().last()=='INTERRUPTED'
        cleanup:
        Thread.interrupted()
    }
    def 'status-only trusted negatives preserve validated facts while positive observations still deny'(){
        given:
        def changed=observed();changed[field]=false;response=JsonOutput.toJson(changed)
        when:
        def result=transport.observeStatus(flow,anchor)
        then:
        result.observationStartedAt()==anchor
        result.flow()==flow
        result.enabled()==(field!='enabled')
        result.sessionActive()==(field!='authentication_session_active')
        when:
        transport.observe(flow,anchor)
        then:
        thrown(Verification.Failure)
        requests.size()==2
        where:
        field << ['enabled','authentication_session_active']
    }
    def 'negative status cannot bypass binding canonicalization types or expiry'(){
        given:
        def changed=observed()+[enabled:false];changed[field]=value;response=JsonOutput.toJson(changed)
        when:
        transport.observeStatus(flow,anchor)
        then:
        thrown(Verification.Failure)
        requests.size()==1
        where:
        field | value
        'subject' | 'other'
        'email' | 'Mixed@Example.test'
        'enabled' | 'false'
        'authentication_session_active' | null
        'authentication_session_expires_at' | '2026-10-16T10:00:00Z'
    }
    def 'finite decoder diagnostics preserve generic unavailable for each actual failure phase'(){
        given:
        def changed=observed();changed[field]=value;response=JsonOutput.toJson(changed)
        when: transport.observe(flow,anchor)
        then:
        def failure=thrown(Verification.Failure)
        failure.error()==Verification.Error.UNAVAILABLE
        diagnostics*.name()==['HTTP_200',label]
        requests.size()==1
        where:
        field | value | label
        'subject' | 'different-private-subject' | 'ECHO'
        'provider_tab_id' | 'different-private-tab' | 'ECHO'
        'enabled' | false | 'FLAGS'
        'authentication_session_active' | 'true' | 'FLAGS'
        'authentication_session_expires_at' | 'not-a-private-timestamp' | 'LIFETIME'
        'authentication_session_expires_at' | '2026-10-16T10:00:00Z' | 'LIFETIME'
        'username' | null | 'CONTACT_FIELDS'
        'email' | 'Mixed@Example.test' | 'CONTACTS'
        'phone_number' | '0501234567' | 'CONTACTS'
        'unexpected_private_field' | 'private-value' | 'JSON_SCHEMA'
    }
    def 'content schema and body bound diagnostics never render payload or credentials'(){
        given:
        response=raw;contentType=type
        when: transport.observe(flow,anchor)
        then:
        def failure=thrown(Verification.Failure)
        failure.error()==Verification.Error.UNAVAILABLE
        diagnostics*.name()==['HTTP_200',label]
        def lines=diagnostics.collect { HttpVerificationObservations.diagnosticLine(it) }
        lines.every { it ==~ /AUTH_ENROLLMENT_OBSERVER [A-Z0-9_]+/ }
        !lines.join().contains(KEY) && !lines.join().contains(ISSUER)
        !lines.join().contains(flow.subject()) && !lines.join().contains(flow.authenticationSessionId())
        !lines.join().contains('private-payload') && !lines.join().contains('fixture@example.test')
        requests.size()==1
        where:
        raw | type | label
        '{"private-payload":true}' | 'text/plain' | 'CONTENT_TYPE'
        '{"enabled":true,"enabled":true}' | 'application/json' | 'JSON_SCHEMA'
        '{} {}' | 'application/json' | 'JSON_SCHEMA'
        '{private-payload' | 'application/json' | 'JSON_SCHEMA'
        'private-payload' * 1024 | 'application/json' | 'BODY_LIMIT'
    }
    def 'initial domain errors retain exact original kind and do no network work'(){
        given:
        def invalid=new Flow(ISSUER,'private-subject','unknown-client','private-root','private-tab',flow.authenticationSessionId())
        when: transport.observe(invalid,anchor)
        then:
        def failure=thrown(Verification.Failure)
        failure.error()==Verification.Error.BINDING
        diagnostics*.name()==['INITIAL_VALIDATION']
        requests.empty
        when: transport.observe(flow,null)
        then:
        def missing=thrown(Verification.Failure)
        missing.error()==Verification.Error.UNAVAILABLE
        diagnostics*.name()==['INITIAL_VALIDATION','START_INVALID']
        requests.empty
    }
    def 'refused connection is transport only and never retried'(){
        given: server.stop(0)
        when: transport.observe(flow,anchor)
        then:
        def failure=thrown(Verification.Failure)
        failure.error()==Verification.Error.UNAVAILABLE
        diagnostics*.name()==['TRANSPORT']
        requests.empty
    }
    def 'closing an in-flight body cancels promptly with fixed category and no retry'(){
        given:
        stall=true
        def observer=Executors.newSingleThreadExecutor()
        def work=observer.submit({ ->
            try{transport.observe(flow,anchor);return null}
            catch(Verification.Failure failure){return failure.error()}
        } as Callable)
        long ready=System.nanoTime()+TimeUnit.SECONDS.toNanos(2)
        while(!diagnostics*.name().contains('HTTP_200') && System.nanoTime()<ready)Thread.sleep(10)
        assert diagnostics*.name().contains('HTTP_200')
        when:
        long began=System.nanoTime()
        transport.close()
        def error=work.get(2,TimeUnit.SECONDS)
        then:
        error==Verification.Error.UNAVAILABLE
        Duration.ofNanos(System.nanoTime()-began).toMillis()<2500
        diagnostics*.name()==['HTTP_200','CANCELLED']
        requests.size()==1
        cleanup:
        release.countDown();observer.shutdownNow();assert observer.awaitTermination(5,TimeUnit.SECONDS)
    }
    def 'diagnostic sink failure cannot change accepted or rejected public behavior'(){
        given:
        transport.close()
        def client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).followRedirects(HttpClient.Redirect.NEVER).build()
        transport=new HttpVerificationObservations(URI.create("http://127.0.0.1:${server.address.port}"),KEY,POLICY,client,
            { HttpVerificationObservations.Diagnostic ignored -> throw new IllegalStateException('private-diagnostic-sink') } as java.util.function.Consumer)
        when: def result=transport.observe(flow,anchor)
        then: result.flow()==flow
        when:
        status=401
        transport.observe(flow,anchor)
        then:
        def failure=thrown(Verification.Failure)
        failure.error()==Verification.Error.UNAVAILABLE
        requests.size()==2
    }

    def 'real nonempty error bodies expose framing and peer completion without exposing payload'(){
        given:
        status=400;response='private-payload-with-contact@example.test';chunked=streamed
        when: transport.observe(flow,anchor)
        then:
        thrown(Verification.Failure)
        diagnostics*.name()==['HTTP_400']
        traces*.name().containsAll(['VERSION_HTTP_1_1','TYPE_JSON','ROUTER_NODE_A','SUBSCRIBED','NEXT','COMPLETE','NO_ERROR'])
        traces*.name().contains(streamed?'LENGTH_ABSENT':'LENGTH_POSITIVE')
        traces*.name().contains(streamed?'TRANSFER_CHUNKED':'TRANSFER_ABSENT')
        traces.count { it.name()=='NEXT' }==1
        def lines=traces.collect { HttpVerificationObservations.traceLine(it) }
        lines.every { it ==~ /AUTH_ENROLLMENT_TRANSPORT [A-Z0-9_]+/ }
        !lines.join().contains(response) && !lines.join().contains(KEY) && !lines.join().contains(ISSUER)
        requests.size()==1
        where: streamed << [false,true]
    }
    def 'stalled nonempty 400 records unfinished body before forced cleanup'(){
        given: status=400;stall=!dripping;drip=dripping
        when:
        long began=System.nanoTime()
        transport.observe(flow,anchor)
        then:
        thrown(Verification.Failure)
        Duration.ofNanos(System.nanoTime()-began).toMillis()<5500
        diagnostics*.name()==['HTTP_400','TIMEOUT']
        traces*.name().containsAll(['LENGTH_POSITIVE','SUBSCRIBED',dripping?'NEXT':'NO_NEXT','NOT_COMPLETE'])
        !traces*.name().contains('COMPLETE')
        requests.size()==1
        where: dripping << [false,true]
    }
    def 'header categories never include arbitrary header data and do not parse unbounded lengths as numbers'(){
        given:
        def info=Stub(java.net.http.HttpResponse.ResponseInfo){
            version() >> HttpClient.Version.HTTP_1_1
            headers() >> java.net.http.HttpHeaders.of([
                'Content-Type':['private-header'], 'Content-Length':[length],
                'Transfer-Encoding':['private-transfer'], 'X-Fixture-Node':['private-router']
            ], { a,b -> true })
        }
        when: def labels=HttpVerificationObservations.headerTrace(info)*.name()
        then:
        labels==['VERSION_HTTP_1_1','TYPE_OTHER',expected,'TRANSFER_OTHER','ROUTER_OTHER']
        !labels.join().contains('private')
        where:
        length | expected
        'not-a-number' | 'LENGTH_INVALID'
        '9'*100 | 'LENGTH_POSITIVE'
        '000' | 'LENGTH_ZERO'
    }
}
