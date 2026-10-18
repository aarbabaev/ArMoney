package com.arman.bank.authservice.infrastructure

import com.nimbusds.jose.*
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator
import com.nimbusds.jwt.*
import groovy.json.JsonOutput
import spock.lang.Specification
import java.net.http.*
import java.time.*
import java.util.concurrent.*

/** Synthetic signatures and controlled transport only; no provider/browser/receipt authority claim. */
class LegacyMigrationOidcClientSpec extends Specification {
    static final Instant NOW=Instant.parse('2026-10-18T10:00:00Z')
    static final String ISSUER='https://issuer.example/sso/realms/armoney'
    static final URI JWKS=URI.create('https://issuer.example/sso/realms/armoney/protocol/openid-connect/certs')
    static final String CLIENT='legacy-client'
    static final UUID OWNER=UUID.fromString('19041929-b84e-4e14-9e7e-eb91b80ca103')
    def rsa=new RSAKeyGenerator(2048).keyID('key-one').generate()
    def requests=new CopyOnWriteArrayList<HttpRequest>()
    byte[] jwks
    int status=200
    String contentType='application/json'
    CompletableFuture pending
    HttpClient http=new FixtureHttpClient()
    LegacyMigrationOidcClient client
    def setup(){
        jwks=JsonOutput.toJson([keys:[rsa.toPublicJWK().toJSONObject()]]).bytes
        ((FixtureHttpClient)http).responder = { HttpRequest request, handler ->
            requests.add(request)
            if(pending!=null)return pending
            def response=Stub(HttpResponse){
                statusCode() >> status
                body() >> jwks
                headers() >> HttpHeaders.of(['Content-Type':[contentType]],{a,b->true})
            }
            CompletableFuture.completedFuture(response)
        }
        client=new LegacyMigrationOidcClient(new LegacyMigrationOidcClient.Settings(URI.create(ISSUER),JWKS,CLIENT),Clock.fixed(NOW,ZoneOffset.UTC),http)
    }
    def cleanup(){client?.close()}
    def attempt(){new LegacyMigrationOidcClient.Attempt(OWNER,ISSUER,'existing-subject','session','original-nonce',NOW.minusSeconds(10),NOW,NOW.plusSeconds(60))}
    Map claims(){[iss:ISSUER,sub:'existing-subject',sid:'session',nonce:'original-nonce',aud:CLIENT,azp:CLIENT,
                  auth_time:NOW.minusSeconds(5).epochSecond,iat:NOW.epochSecond,exp:NOW.plusSeconds(60).epochSecond]}
    String token(Map values=claims(),String kid='key-one',String header=null){
        def h=header ?: JsonOutput.toJson([alg:'RS256',kid:kid,typ:'JWT'])
        // Sign original JSON payload, preserving numeric/type test inputs.
        def object=new JWSObject(JWSHeader.parse(h),new Payload(JsonOutput.toJson(values)))
        object.sign(new RSASSASigner(rsa));object.serialize()
    }
    def verify(String value){client.verify(value,attempt(),{a->true} as LegacyMigrationOidcClient.AttemptGate)}
    def 'genuine signature returns bound data only and fixed endpoint cache is reused'(){
        when:
        def proof=verify(token())
        verify(token())
        then:
        proof.existingOwner()==OWNER
        proof.subject()=='existing-subject'
        proof.authenticatedAt()==NOW.minusSeconds(5)
        proof.originalDeadline()==attempt().originalDeadline()
        !proof.toString().contains('existing-subject')
        requests.size()==1
        requests[0].uri()==JWKS
        requests[0].method()=='GET'
        requests[0].headers().firstValue('Authorization').isEmpty()
        requests[0].timeout().get()<=Duration.ofSeconds(4)
    }
    def 'missing or refused admission context cannot fetch or return proof'(){
        when: client.verify(token(),attempt(),null)
        then: thrown(LegacyMigrationOidcClient.Unavailable)
        requests.empty
        when: client.verify(token(),attempt(),{a->false} as LegacyMigrationOidcClient.AttemptGate)
        then: thrown(LegacyMigrationOidcClient.Unavailable)
        requests.empty
    }
    def 'claim binding freshness and strict types reject signed invalid evidence'(){
        given:
        def alterations=[[iss:'https://foreign.example'],[sub:'other'],[sid:'other'],[nonce:'other'],[aud:['legacy-client','other']],
                         [azp:'other'],[auth_time:null],[auth_time:true],[auth_time:1.2],
                         [auth_time:NOW.minusSeconds(121).epochSecond],[auth_time:NOW.plusSeconds(6).epochSecond],
                         [iat:NOW.plusSeconds(6).epochSecond],[exp:NOW.epochSecond],[nbf:NOW.plusSeconds(6).epochSecond]]
        expect:
        alterations.every { change ->
            try{verify(token(claims()+change));false}catch(LegacyMigrationOidcClient.Unavailable ignored){true}
        }
    }
    def 'signature mismatch unknown kid and token key urls never choose a different key source'(){
        given: def valid=token();verify(valid)
        when: verify(valid.substring(0,valid.lastIndexOf('.')+1)+Base64.urlEncoder.withoutPadding().encodeToString(new byte[256]))
        then: thrown(LegacyMigrationOidcClient.Unavailable)
        when: verify(token(claims(),'unknown'))
        then: thrown(LegacyMigrationOidcClient.Unavailable)
        requests.size()==1 // Unknown-kid fetch storm cannot bypass the 30s issuer admission.
        when: verify(token(claims(),'key-one',JsonOutput.toJson([alg:'RS256',kid:'key-one',jku:'https://attacker.invalid/jwks'])))
        then: thrown(LegacyMigrationOidcClient.Unavailable)
        requests.size()==1
    }
    def 'duplicate header or claims rejected before network even when compact framing is valid'(){
        given:
        def encode={String s->Base64.urlEncoder.withoutPadding().encodeToString(s.bytes)}
        def duplicateHeader=encode('{"alg":"RS256","alg":"RS256","kid":"key-one"}')+'.'+encode(JsonOutput.toJson(claims()))+'.AA'
        def duplicateClaims=encode('{"alg":"RS256","kid":"key-one"}')+'.'+encode('{"sub":"one","sub":"two"}')+'.AA'
        expect:
        [duplicateHeader,duplicateClaims].every { raw ->
            try{verify(raw);false}catch(LegacyMigrationOidcClient.Unavailable ignored){true}
        }
        requests.empty
    }
    def 'unsupported algorithms key headers and malformed framing reject before retrieval'(){
        given:
        def encode={String s->Base64.urlEncoder.withoutPadding().encodeToString(s.bytes)}
        def payload=encode(JsonOutput.toJson(claims()))
        def headers=[[alg:'none',kid:'key-one'],[alg:'HS256',kid:'key-one'],[alg:'RS256',kid:'key-one',jwk:[kty:'RSA']],
                     [alg:'RS256',kid:'key-one',x5u:'https://attacker.invalid/cert'],[alg:'RS256',kid:'key-one',x5c:[]],
                     [alg:'RS256',kid:'key-one',crit:['b64'],b64:false]]
        expect:
        (headers.collect{encode(JsonOutput.toJson(it))+'.'+payload+'.AA'}+['a.b','a.b.c.d','a=.b.c','x'*16385]).every { raw ->
            try{verify(raw);false}catch(LegacyMigrationOidcClient.Unavailable ignored){true}
        }
        requests.empty
    }
    def 'stale admitted anchor and expired immutable attempt cannot return proof or refresh proof time'(){
        given:
        def stale=new LegacyMigrationOidcClient.Attempt(OWNER,ISSUER,'existing-subject','session','original-nonce',NOW.minusSeconds(10),NOW.minusSeconds(5),NOW.plusSeconds(60))
        when: client.verify(token(),stale,{a->true} as LegacyMigrationOidcClient.AttemptGate)
        then: thrown(LegacyMigrationOidcClient.Unavailable)
        requests.empty
        when: client.verify(token(),null,{a->true} as LegacyMigrationOidcClient.AttemptGate)
        then: thrown(LegacyMigrationOidcClient.Unavailable)
        requests.empty
    }
    def 'redirect wrong content type duplicate jwks keys and oversized body fail closed'(){
        when: status=302;verify(token())
        then: thrown(LegacyMigrationOidcClient.Unavailable)
        when: client.close();client=new LegacyMigrationOidcClient(new LegacyMigrationOidcClient.Settings(URI.create(ISSUER),JWKS,CLIENT),Clock.fixed(NOW,ZoneOffset.UTC),http)
        status=200;contentType='text/html';verify(token())
        then: thrown(LegacyMigrationOidcClient.Unavailable)
        when: client.close();client=new LegacyMigrationOidcClient(new LegacyMigrationOidcClient.Settings(URI.create(ISSUER),JWKS,CLIENT),Clock.fixed(NOW,ZoneOffset.UTC),http)
        contentType='application/json';jwks=JsonOutput.toJson([keys:[rsa.toPublicJWK().toJSONObject(),rsa.toPublicJWK().toJSONObject()]]).bytes;verify(token())
        then: thrown(LegacyMigrationOidcClient.Unavailable)
        when: client.close();client=new LegacyMigrationOidcClient(new LegacyMigrationOidcClient.Settings(URI.create(ISSUER),JWKS,CLIENT),Clock.fixed(NOW,ZoneOffset.UTC),http)
        jwks=new byte[65537];verify(token())
        then: thrown(LegacyMigrationOidcClient.Unavailable)
    }
    def 'close cancels pending fetch and permanently fences proof creation'(){
        given:
        pending=new CompletableFuture()
        def worker=Executors.newSingleThreadExecutor()
        def submitted=worker.submit({->try{verify(token());false}catch(LegacyMigrationOidcClient.Unavailable ignored){true}} as Callable<Boolean>)
        try{
            long limit=System.nanoTime()+TimeUnit.SECONDS.toNanos(2)
            while(requests.empty&&System.nanoTime()<limit)Thread.onSpinWait()
            client.close()
            assert submitted.get(2,TimeUnit.SECONDS)
            assert pending.cancelled
        }finally{worker.shutdownNow();worker.awaitTermination(2,TimeUnit.SECONDS)}
        when: verify(token())
        then: thrown(LegacyMigrationOidcClient.Unavailable)
    }
    def 'expired cache is not extended on failed refresh and later bounded rotation works'(){
        given:
        client.close()
        def nanos=new java.util.concurrent.atomic.AtomicLong(0)
        client=new LegacyMigrationOidcClient(new LegacyMigrationOidcClient.Settings(URI.create(ISSUER),JWKS,CLIENT),Clock.fixed(NOW,ZoneOffset.UTC),http,{->nanos.get()} as java.util.function.LongSupplier)
        verify(token())
        when:
        nanos.addAndGet(TimeUnit.MINUTES.toNanos(5)+1)
        status=503
        verify(token())
        then: thrown(LegacyMigrationOidcClient.Unavailable)
        requests.size()==2
        when:
        status=200
        verify(token())
        then: thrown(LegacyMigrationOidcClient.Unavailable)
        requests.size()==2
        when:
        nanos.addAndGet(TimeUnit.SECONDS.toNanos(30))
        verify(token())
        then: requests.size()==3
    }
    def 'final gate cannot return proof after consuming clock budget deadline close or cancellation'(){
        expect:
        ['db-budget','deadline','monotonic-budget','close','interrupt'].every { mode ->
            client.close()
            def now=new java.util.concurrent.atomic.AtomicReference<Instant>(NOW)
            def nanos=new java.util.concurrent.atomic.AtomicLong(0)
            Clock moving=new Clock(){
                ZoneId getZone(){ZoneOffset.UTC}
                Clock withZone(ZoneId zone){this}
                Instant instant(){now.get()}
            }
            client=new LegacyMigrationOidcClient(new LegacyMigrationOidcClient.Settings(URI.create(ISSUER),JWKS,CLIENT),moving,http,{->nanos.get()} as java.util.function.LongSupplier)
            int calls=0
            def gate={ a ->
                calls++
                if(calls==2){
                    if(mode=='db-budget')now.set(NOW.plusSeconds(5))
                    if(mode=='deadline')now.set(a.originalDeadline())
                    if(mode=='monotonic-budget')nanos.set(TimeUnit.SECONDS.toNanos(5))
                    if(mode=='close')client.close()
                    if(mode=='interrupt')Thread.currentThread().interrupt()
                }
                true
            } as LegacyMigrationOidcClient.AttemptGate
            try{
                client.verify(token(),attempt(),gate)
                false
            }catch(LegacyMigrationOidcClient.Unavailable ignored){
                assert calls==2 // Final expiry/cancellation check must not call a third gate.
                true
            }finally{Thread.interrupted()}
        }
    }
    def 'settings require explicit trusted https and body subscriber cancels on capacity'(){
        given:
        def body=new LegacyMigrationOidcExchange.Body()
        def subscription=Mock(Flow.Subscription)
        body.onSubscribe(subscription)
        when: new LegacyMigrationOidcClient.Settings(URI.create(ISSUER),URI.create('http://attacker.invalid/jwks'),CLIENT)
        then: thrown(IllegalArgumentException)
        when: body.onNext([java.nio.ByteBuffer.wrap(new byte[65537])])
        then:
        1 * subscription.cancel()
        body.body.toCompletableFuture().completedExceptionally
    }
    /** Concrete synthetic wire fixture: no class-mocking agent or external HTTP service. */
    static final class FixtureHttpClient extends HttpClient {
        Closure responder
        Optional<java.net.CookieHandler> cookieHandler(){Optional.empty()}
        Optional<Duration> connectTimeout(){Optional.of(Duration.ofSeconds(2))}
        HttpClient.Redirect followRedirects(){HttpClient.Redirect.NEVER}
        Optional<java.net.ProxySelector> proxy(){Optional.empty()}
        javax.net.ssl.SSLContext sslContext(){javax.net.ssl.SSLContext.getDefault()}
        javax.net.ssl.SSLParameters sslParameters(){new javax.net.ssl.SSLParameters()}
        Optional<java.net.Authenticator> authenticator(){Optional.empty()}
        HttpClient.Version version(){HttpClient.Version.HTTP_1_1}
        Optional<Executor> executor(){Optional.empty()}
        public <T> HttpResponse<T> send(HttpRequest request,HttpResponse.BodyHandler<T> handler){
            throw new UnsupportedOperationException('Synchronous transport is outside fixture contract')
        }
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request,HttpResponse.BodyHandler<T> handler){
            responder.call(request,handler) as CompletableFuture<HttpResponse<T>>
        }
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request,HttpResponse.BodyHandler<T> handler,HttpResponse.PushPromiseHandler<T> push){
            throw new UnsupportedOperationException('Push transport is outside fixture contract')
        }
        void shutdownNow(){} // Owns no sockets/executor; adapter cancellation is asserted separately.
    }
}
