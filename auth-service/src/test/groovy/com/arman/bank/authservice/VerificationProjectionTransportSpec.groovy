package com.arman.bank.authservice.infrastructure

import com.arman.bank.authservice.domain.VerificationProjection
import com.arman.bank.authservice.domain.VerificationProjection.*
import com.sun.net.httpserver.HttpServer
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import java.time.Instant
import java.util.concurrent.*
import spock.lang.Specification

/** Synthetic loopback HTTP evidence only; does not prove user CAS or auth persistence. */
class VerificationProjectionTransportSpec extends Specification {
    static final String KEY='synthetic-private-projection-key-32-characters'
    def command=VerificationProjection.create(UUID.randomUUID(),UUID.randomUUID(),0,1,'+971501234567',1,1L)
    def claim=new Claim('auth',command,HttpVerificationProjection.body(command),UUID.randomUUID(),Instant.now().plusSeconds(30),Instant.now().plusSeconds(60),1)
    def executor=Executors.newFixedThreadPool(2)
    def requests=new CopyOnWriteArrayList<Map>()
    def release=new CountDownLatch(1)
    HttpServer server
    HttpVerificationProjection transport
    int status=200
    String contentType='application/json'
    String response
    boolean stall
    Map ack(){[operation_id:command.operationId().toString(),identity_id:command.identityId().toString(),version:command.version(),
        phone_contact_version:command.phoneContactVersion(),verification_epoch:command.verificationEpoch(),payload_hash:command.payloadHash()]}
    def setup(){
        response=JsonOutput.toJson(ack())
        server=HttpServer.create(new InetSocketAddress('127.0.0.1',0),0)
        server.executor=executor
        server.createContext('/') { exchange ->
            try {
                requests.add([path:exchange.requestURI.toString(),method:exchange.requestMethod,
                    key:exchange.requestHeaders.getFirst('X-ArMoney-Verification-Projection-Key'),identity:exchange.requestHeaders.getFirst('X-Identity-Id'),
                    body:exchange.requestBody.readAllBytes()])
                exchange.responseHeaders.set('Content-Type',contentType)
                exchange.responseHeaders.set('Cache-Control','no-store')
                if(status==302)exchange.responseHeaders.set('Location',"http://127.0.0.1:${server.address.port}/other")
                byte[] bytes=response.getBytes(java.nio.charset.StandardCharsets.UTF_8)
                exchange.sendResponseHeaders(status,bytes.length)
                if(stall)release.await(6,TimeUnit.SECONDS)
                exchange.responseBody.write(bytes)
            } catch(IOException ignored){/* Synthetic timeout closes socket. */} finally {exchange.close()}
        }
        server.start()
        transport=new HttpVerificationProjection(URI.create("http://127.0.0.1:${server.address.port}"),KEY)
    }
    def cleanup(){release.countDown();transport.close();server.stop(0);executor.shutdownNow();assert executor.awaitTermination(5,TimeUnit.SECONDS)}
    def 'only exact six-field ACK succeeds and retry sends persisted identical bytes with dedicated credential'(){
        when:
        def first=transport.send(claim)
        def retry=transport.send(claim)
        then:
        first.outcome()==Outcome.ACK && first.acknowledgement().matches(command)
        retry.outcome()==Outcome.ACK
        requests.size()==2
        requests*.path==['/v1/internal/verification-projections']*2
        requests*.method==['POST']*2
        requests*.key==[KEY]*2
        requests*.identity==[null]*2
        Arrays.equals(requests[0].body,requests[1].body)
        Arrays.equals(requests[0].body,claim.requestBody())
        def json=new JsonSlurper().parse(requests[0].body as byte[])
        json.keySet()==['operation_id','identity_id','expected_version','version','canonical_phone','phone_contact_version','verification_epoch','payload_hash'] as Set
    }
    def 'terminal HTTP statuses retain distinct nonretryable outcomes'(){
        given:
        status=code
        response='{"error":"verification_projection_rejected"}'
        expect:
        transport.send(claim).outcome()==expected
        requests.size()==1
        where:
        code | expected
        400  | Outcome.BAD_REQUEST
        401  | Outcome.UNAUTHORIZED
        409  | Outcome.CONFLICT
        410  | Outcome.EXPIRED
        429  | Outcome.RATE_LIMITED
        503  | Outcome.RETRY
        302  | Outcome.RETRY
    }
    def 'malformed duplicate oversized wrongly typed and mismatched ACKs never become success'(){
        given:
        def changed=ack()
        if(field!=null)changed[field]=value
        response=raw!=null?raw:JsonOutput.toJson(changed)
        expect:
        transport.send(claim).outcome()==Outcome.RETRY
        where:
        field                   | value                  | raw
        'operation_id'          | '1-1-1-1-1'            | null
        'identity_id'           | UUID.randomUUID().toString() | null
        'version'               | 2                      | null
        'phone_contact_version' | '1'                    | null
        'verification_epoch'    | 1.0                    | null
        'payload_hash'          | 'a'*64                 | null
        'unexpected'            | true                   | null
        null                    | null                   | '{}'
        null                    | null                   | '{"version":1,"version":1}'
        null                    | null                   | 'x'*12289
        null                    | null                   | '{} {}'
    }
    def 'four-second total deadline includes response body consumption'(){
        given:
        stall=true
        when:
        long started=System.nanoTime()
        def result=transport.send(claim)
        then:
        result.outcome()==Outcome.RETRY
        TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started)<5500
    }
    def 'tampered persisted bytes or closed transport cannot send'(){
        given:
        def tampered=new Claim('auth',command,'{}'.bytes,UUID.randomUUID(),claim.leaseUntil(),claim.expiresAt(),1)
        expect:
        transport.send(tampered).outcome()==Outcome.BAD_REQUEST
        when:
        transport.close()
        then:
        transport.send(claim).outcome()==Outcome.RETRY
        requests.empty
    }
}
