package com.arman.bank.userservice

import com.arman.bank.userservice.infrastructure.VerificationProjectionRoutes
import com.arman.bank.userservice.infrastructure.VerificationProjectionConfiguration
import com.arman.bank.userservice.application.VerificationProjectionStore
import com.arman.bank.runtime.ServiceRuntime
import com.fasterxml.jackson.databind.ObjectMapper
import java.net.http.*
import spock.lang.Specification

class VerificationProjectionRoutesSpec extends Specification {
    static final String KEY = 'synthetic-projection-credential-000001'
    static HttpResponse<String> request(int port, String key, String body) {
        def builder=HttpRequest.newBuilder(URI.create("http://localhost:${port}"+VerificationProjectionRoutes.PATH))
            .timeout(java.time.Duration.ofSeconds(5)).header('Content-Type','application/json')
        if (key != null) builder.header('X-ArMoney-Verification-Projection-Key',key)
        HttpClient.newHttpClient().send(builder.POST(HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString())
    }
    static Map body() {
        def c = VerificationProjectionSpec.command(0,1,'+971501234567',1,1)
        [operation_id:c.operationId().toString(), identity_id:c.identityId().toString(),
            expected_version:0, version:1, canonical_phone:c.canonicalPhone(),
            phone_contact_version:1, verification_epoch:1, payload_hash:c.payloadHash()]
    }
    def 'strict parser accepts exact canonical DTO'() {
        expect:
        VerificationProjectionRoutes.parse(new ObjectMapper().writeValueAsBytes(body())) == VerificationProjectionSpec.command(0,1,'+971501234567',1,1)
    }
    def 'strict parser rejects wrong types unknown fields nulls and noncanonical UUIDs'() {
        given:
        def value=body()+patch
        when:
        VerificationProjectionRoutes.parse(new ObjectMapper().writeValueAsBytes(value))
        then:
        thrown(IllegalArgumentException)
        where:
        patch << [[expected_version:'0'],[version:1.0d],[verification_epoch:'1'],[phone_contact_version:null],
            [owner:'spoof'],[identity_id:'00000000-0000-4000-8000-00000000000A'],[operation_id:'0-0-4000-8000-1']]
    }
    def 'strict parser rejects duplicate fields trailing tokens and oversized bodies'() {
        given:
        def json=new ObjectMapper().writeValueAsString(body())
        when:
        VerificationProjectionRoutes.parse((json[0..-2]+',"version":1}').bytes)
        then:
        thrown(com.fasterxml.jackson.core.JsonProcessingException)
        when:
        VerificationProjectionRoutes.parse((json+' {}').bytes)
        then:
        thrown(com.fasterxml.jackson.core.JsonProcessingException)
        when:
        VerificationProjectionRoutes.parse(new byte[12289])
        then:
        thrown(IllegalArgumentException)
    }
    def 'credential rejection never admits or commits and malformed authenticated bodies consume admission'() {
        given:
        def store=Mock(VerificationProjectionStore)
        def routes=new VerificationProjectionRoutes(store,new VerificationProjectionConfiguration(true,[auth:KEY]))
        def runtime=ServiceRuntime.start('user-service',0,null,routes::configure)
        when:
        def unauthenticated=request(runtime.port(),null,'{')
        then:
        unauthenticated.statusCode()==401
        unauthenticated.headers().firstValue('Cache-Control').orElse('')=='no-store'
        0 * store._
        when:
        def malformed=request(runtime.port(),KEY,'{')
        then:
        1 * store.admit('auth') >> true
        0 * store.commit(_, _)
        malformed.statusCode()==400
        !malformed.body().contains(KEY)
        cleanup:
        runtime?.close()
    }
    def 'ACK has exactly six fields and capacity applies before parsing'() {
        given:
        def store=Mock(VerificationProjectionStore)
        def routes=new VerificationProjectionRoutes(store,new VerificationProjectionConfiguration(true,[auth:KEY]))
        def runtime=ServiceRuntime.start('user-service',0,null,routes::configure)
        def command=VerificationProjectionSpec.command(0,1,'+971501234567',1,1)
        when:
        def response=request(runtime.port(),KEY,new ObjectMapper().writeValueAsString(body()))
        then:
        1 * store.admit('auth') >> true
        1 * store.commit('auth',command) >> command.ack()
        response.statusCode()==200
        new ObjectMapper().readTree(response.body()).fieldNames().toList().sort()==
            ['identity_id','operation_id','payload_hash','phone_contact_version','verification_epoch','version']
        !response.body().contains('+971')
        when:
        def denied=request(runtime.port(),KEY,'{')
        then:
        1 * store.admit('auth') >> false
        0 * store.commit(_, _)
        denied.statusCode()==429
        denied.headers().firstValue('Retry-After').orElse('')=='60'
        cleanup:
        runtime?.close()
    }
    def 'disabled configuration registers no projection route'() {
        given:
        def store=Mock(VerificationProjectionStore)
        def routes=new VerificationProjectionRoutes(store,new VerificationProjectionConfiguration(false,[:]))
        def runtime=ServiceRuntime.start('user-service',0,null,routes::configure)
        expect:
        request(runtime.port(),KEY,'{}').statusCode()==404
        cleanup:
        runtime?.close()
    }
    def 'sixteen in flight requests bound admission and a released slot recovers'() {
        given:
        def entered=new java.util.concurrent.CountDownLatch(16)
        def release=new java.util.concurrent.CountDownLatch(1)
        def admits=new java.util.concurrent.atomic.AtomicInteger()
        def commits=new java.util.concurrent.atomic.AtomicInteger()
        def store=new VerificationProjectionStore() {
            boolean admit(String writer) {
                admits.incrementAndGet()
                entered.countDown()
                if (!release.await(40,java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException('Synthetic barrier timed out')
                return true
            }
            com.arman.bank.userservice.domain.VerificationProjection.Ack commit(String writer,
                    com.arman.bank.userservice.domain.VerificationProjection value) {
                commits.incrementAndGet()
                return value.ack()
            }
            int cleanup() { 0 }
        }
        def routes=new VerificationProjectionRoutes(store,new VerificationProjectionConfiguration(true,[auth:KEY]))
        def runtime=ServiceRuntime.start('user-service',0,null,routes::configure)
        def executor=java.util.concurrent.Executors.newFixedThreadPool(16)
        def json=new ObjectMapper().writeValueAsString(body())
        def pending=(1..16).collect {
            executor.submit({
                def builder=HttpRequest.newBuilder(URI.create("http://localhost:${runtime.port()}"+VerificationProjectionRoutes.PATH))
                    .timeout(java.time.Duration.ofSeconds(45)).header('Content-Type','application/json')
                    .header('X-ArMoney-Verification-Projection-Key',KEY)
                HttpClient.newHttpClient().send(builder.POST(HttpRequest.BodyPublishers.ofString(json)).build(),HttpResponse.BodyHandlers.ofString())
            } as java.util.concurrent.Callable<HttpResponse<String>>)
        }
        when:
        assert entered.await(15,java.util.concurrent.TimeUnit.SECONDS) : 'Expected sixteen admitted requests held by the synthetic barrier'
        def denied=request(runtime.port(),KEY,json)
        then:
        denied.statusCode()==429
        denied.headers().firstValue('Retry-After').orElse('')=='60'
        admits.get()==16
        commits.get()==0
        when:
        release.countDown()
        def responses=pending.collect { it.get(15,java.util.concurrent.TimeUnit.SECONDS) }
        def recovered=request(runtime.port(),KEY,json)
        then:
        responses.every { it.statusCode()==200 }
        recovered.statusCode()==200
        admits.get()==17
        commits.get()==17
        cleanup:
        release?.countDown()
        executor?.shutdownNow()
        runtime?.close()
    }
}
