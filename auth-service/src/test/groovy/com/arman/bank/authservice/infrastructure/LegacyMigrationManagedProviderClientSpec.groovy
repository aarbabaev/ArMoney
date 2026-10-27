package com.arman.bank.authservice.infrastructure

import com.arman.bank.authservice.application.MigrationManagedProofPort as P
import com.arman.bank.authservice.application.MigrationProviderReadinessPort as R
import com.arman.bank.authservice.domain.LegacyMigration
import com.arman.bank.authservice.domain.CodeConfirmation
import com.arman.bank.authservice.domain.LoginProof
import com.nimbusds.jose.*
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator
import groovy.json.JsonOutput
import spock.lang.Specification
import java.net.http.*
import java.time.*
import java.util.concurrent.*
import java.security.MessageDigest

/** Synthetic creator attempt, real RSA signature, controlled wire/observer; NOT a durable migration proof. */
class LegacyMigrationManagedProviderClientSpec extends Specification {
    static final Instant NOW=Instant.parse('2026-10-18T10:00:00Z')
    static final String ISSUER='https://issuer.example/sso/realms/armoney'
    static final String CLIENT='managed-client'
    static final URI TOKEN=URI.create(ISSUER+'/protocol/openid-connect/token')
    static final URI JWKS=URI.create(ISSUER+'/protocol/openid-connect/certs')
    static final URI REDIRECT=URI.create('https://migration.example/migration/managed/callback')
    static final String VERIFIER='server-original-pkce-verifier-abcdefghijklmnopqrstuvwxyz1234567890'
    static final P.Callback CALLBACK=new P.Callback('actual-synthetic-code','original-state','original-browser-cookie')
    def key=new RSAKeyGenerator(2048).keyID('signing-key').generate()
    Map tokenClaims
    List requests=new CopyOnWriteArrayList()
    List forms=[]
    List observations=[]
    List outputs=[]
    int tokenStatus=200
    String tokenType='application/json'
    String responseOverride
    boolean providerEmailVerified=true
    boolean negative
    boolean wrongObservation
    boolean closeDuringObservation
    RuntimeException observerFailure
    List<java.util.logging.LogRecord> failureRecords=[]
    java.util.logging.Logger diagnosticLogger
    java.util.logging.Handler diagnosticHandler
    boolean originalParentHandlers
    long tokenDelayMillis
    FixtureAttempt attempt=new FixtureAttempt()
    SyntheticHttp http=new SyntheticHttp()
    LegacyMigrationManagedProviderClient adapter
    def setup(){
        diagnosticLogger=java.util.logging.Logger.getLogger(LegacyMigrationManagedProviderClient.name)
        originalParentHandlers=diagnosticLogger.useParentHandlers
        diagnosticHandler=new java.util.logging.Handler(){
            void publish(java.util.logging.LogRecord record){failureRecords.add(record)}
            void flush(){}
            void close(){}
        }
        diagnosticLogger.useParentHandlers=false
        diagnosticLogger.addHandler(diagnosticHandler)
        tokenClaims=[iss:ISSUER,sub:'signed-target',sid:'signed-session',aud:CLIENT,azp:CLIENT,nonce:'original-nonce',
                     auth_time:NOW.minusSeconds(5).epochSecond,iat:NOW.epochSecond,exp:NOW.plusSeconds(60).epochSecond]
        http.responder={ HttpRequest request,handler ->
            requests.add(request)
            def isToken=request.uri()==TOKEN
            assert isToken||request.uri()==JWKS
            if(isToken)forms.add(readBody(request))
            if(isToken&&tokenDelayMillis>0)Thread.sleep(tokenDelayMillis)
            byte[] body=isToken?(responseOverride?:JsonOutput.toJson([token_type:'Bearer',access_token:'discard-only',id_token:signed(),refresh_token:'never-used'])).bytes:
                JsonOutput.toJson([keys:[key.toPublicJWK().toJSONObject()]]).bytes
            int status=isToken?tokenStatus:200
            String type=isToken?tokenType:'application/json'
            def response=new SyntheticResponse(request,status,type,body)
            CompletableFuture.completedFuture(response)
        }
        def verifier=new LegacyMigrationOidcClient(new LegacyMigrationOidcClient.Settings(URI.create(ISSUER),JWKS,CLIENT),Clock.fixed(NOW,ZoneOffset.UTC),http)
        R observer={ query,start,budget ->
            def binding=query.binding()
            assert query.purpose()==R.Purpose.MIGRATION_MANAGED
            assert query.originalRedirectUri()==REDIRECT.toString()
            observations.add([binding,start,budget])
            if(observerFailure!=null)throw observerFailure
            if(closeDuringObservation)adapter.close()
            def facts=new CodeConfirmation.Facts(wrongObservation?new LoginProof.Binding(ISSUER,'foreign',CLIENT,'signed-session'):binding,
                '+971501234567','fixture@example.test','+971501234567',!negative,true,true,NOW,NOW.plusSeconds(5))
            new R.Observation(query,facts,providerEmailVerified)
        } as R
        adapter=new LegacyMigrationManagedProviderClient(new LegacyMigrationManagedProviderClient.Settings(URI.create(ISSUER),TOKEN,JWKS,CLIENT,REDIRECT),
            Clock.fixed(NOW,ZoneOffset.UTC),observer,http,verifier)
    }
    def cleanup(){adapter?.close();diagnosticLogger?.removeHandler(diagnosticHandler);if(diagnosticLogger!=null)diagnosticLogger.useParentHandlers=originalParentHandlers}
    def 'failure markers contain only the fixed phase and never sensitive rejection content'(){
        given:
        def sentinel='SENSITIVE-synthetic-code-token-contact'
        if(stage=='VALIDATE_ATTEMPT')attempt.verifier='invalid'
        if(stage=='TOKEN_REQUEST'){tokenStatus=503;responseOverride=sentinel}
        if(stage=='JWT_VERIFY')tokenClaims.nonce=sentinel
        if(stage=='OBSERVATION')observerFailure=new IllegalStateException(sentinel)
        if(stage=='FACTS_VALIDATE')negative=true
        when:
        adapter.exchange(attempt,CALLBACK,{claims,facts->
            if(stage=='SINK_VALIDATE')throw new IllegalStateException(sentinel)
            if(stage=='POST_SINK')adapter.close()
        } as P.VerifiedSink)
        then:
        thrown(LegacyMigrationOidcClient.Unavailable)
        failureRecords*.message==['ARMONEY_MANAGED_PROVIDER_FAILURE_'+stage]
        failureRecords.every{it.thrown==null&&it.parameters==null&&!it.message.contains(sentinel)}
        where:
        stage<<['VALIDATE_ATTEMPT','TOKEN_REQUEST','JWT_VERIFY','OBSERVATION','FACTS_VALIDATE','SINK_VALIDATE','POST_SINK']
    }
    def 'legacy V1 constructor cannot infer verified readiness or invoke receipt sink'(){
        given:
        adapter.close()
        def verifier=new LegacyMigrationOidcClient(new LegacyMigrationOidcClient.Settings(URI.create(ISSUER),JWKS,CLIENT),Clock.fixed(NOW,ZoneOffset.UTC),http)
        P.Observations v1={binding,start,budget->throw new AssertionError('V1 must not substitute for readiness')} as P.Observations
        adapter=new LegacyMigrationManagedProviderClient(new LegacyMigrationManagedProviderClient.Settings(URI.create(ISSUER),TOKEN,JWKS,CLIENT,REDIRECT),Clock.fixed(NOW,ZoneOffset.UTC),v1,http,verifier)
        when:adapter.exchange(attempt,CALLBACK,{claims,facts->outputs.add(facts)} as P.VerifiedSink)
        then:thrown(LegacyMigrationOidcClient.Unavailable)
        outputs.empty
        observations.empty
    }
    def 'false provider verification never reaches the private managed receipt sink'(){
        given:providerEmailVerified=false
        when:adapter.exchange(attempt,CALLBACK,{claims,facts->outputs.add(facts)} as P.VerifiedSink)
        then:thrown(LegacyMigrationOidcClient.Unavailable)
        outputs.empty
        observations.size()==1
        failureRecords*.message==['ARMONEY_MANAGED_PROVIDER_FAILURE_FACTS_VALIDATE']
    }
    def 'successful provider exchange emits no failure marker'(){
        when:positiveExchange()
        then:failureRecords.empty
    }
    String signed(){
        def jws=new JWSObject(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID('signing-key').type(JOSEObjectType.JWT).build(),new Payload(JsonOutput.toJson(tokenClaims)))
        jws.sign(new RSASSASigner(key));jws.serialize()
    }
    void exchange(P.Callback callback=CALLBACK){adapter.exchange(attempt,callback,{claims,facts->outputs.add([claims,facts])} as P.VerifiedSink)}
    private enum FixturePhase { BEFORE_TOKEN, TOKEN_RESPONSE, JWKS, OBSERVATION, SINK }
    private void positiveExchange(){
        try{exchange()}
        catch(LegacyMigrationOidcClient.Unavailable ignored){
            FixturePhase phase=!outputs.empty?FixturePhase.SINK:!observations.empty?FixturePhase.OBSERVATION:
                requests.any{it.uri()==JWKS}?FixturePhase.JWKS:!requests.empty?FixturePhase.TOKEN_RESPONSE:FixturePhase.BEFORE_TOKEN
            // Fixed local enum only: never include the exception, payload, claims, identity or URL.
            throw new AssertionError('FIXTURE_PHASE_'+phase.name())
        }
    }
    def 'local unmapped target uses dedicated managed gate and original server verifier only'(){
        when:positiveExchange()
        then:
        outputs.size()==1
        outputs[0][0].subject()=='signed-target'
        outputs[0][0].clientId()==CLIENT
        outputs[0][1].binding().subject()=='signed-target'
        attempt.verifierReads==1
        requests*.uri()==[TOKEN,JWKS]
        observations.size()==1
        observations[0][1]==NOW
        observations[0][2]>Duration.ZERO&&observations[0][2]<=Duration.ofSeconds(4)
        !outputs[0][0].toString().contains('signed-target')
        when:
        def fields=forms[0].split('&').collectEntries{String field->def pair=field.split('=',2);[(pair[0]):java.net.URLDecoder.decode(pair[1],'UTF-8')]}
        then:
        fields==[grant_type:'authorization_code',client_id:CLIENT,redirect_uri:REDIRECT.toString(),code:CALLBACK.providerCode(),code_verifier:VERIFIER]
        requests[0].headers().firstValue('Authorization').empty
        !fields.containsKey('refresh_token')
    }
    def 'callback mismatch rejects before verifier custody network or observations'(){
        expect:
        [new P.Callback('substituted','original-state','original-browser-cookie'),
         new P.Callback('actual-synthetic-code','substituted','original-browser-cookie'),
         new P.Callback('actual-synthetic-code','original-state','substituted')].every { callback ->
            try{exchange(callback);false}catch(LegacyMigrationOidcClient.Unavailable ignored){
                assert requests.empty&&outputs.empty&&observations.empty;assert attempt.verifierReads==0;true
            }
        }
    }
    def 'corrupt original verifier cannot match original S256 and cannot send'(){
        given:attempt.verifier='another-original-value-abcdefghijklmnopqrstuvwxyz123456789012345'
        when:exchange()
        then:thrown(LegacyMigrationOidcClient.Unavailable)
        requests.empty&&outputs.empty
    }
    def 'existing SSO mode requires exact original issuer and subject while local mode cannot assert a subject'(){
        given:
        attempt.mode=LegacyMigration.ProofMode.LEGACY_OIDC_REAUTH
        attempt.legacyIss=ISSUER;attempt.legacySub='signed-target';attempt.expected='signed-target'
        when:positiveExchange()
        then:outputs.size()==1
        when:
        outputs.clear();attempt=new FixtureAttempt(mode:LegacyMigration.ProofMode.LEGACY_OIDC_REAUTH,legacyIss:ISSUER,legacySub:'original-owner',expected:'original-owner')
        exchange()
        then:thrown(LegacyMigrationOidcClient.Unavailable)
        outputs.empty
    }
    def 'nonce stale interactive time and wrong audience do not emit observations or output'(){
        expect:
        [[nonce:'substitute'],[auth_time:NOW.minusSeconds(121).epochSecond],[aud:'foreign']].every{change ->
            attempt=new FixtureAttempt();def original=tokenClaims;tokenClaims=original+change
            try{exchange();false}catch(LegacyMigrationOidcClient.Unavailable ignored){assert outputs.empty;assert observations.empty;true}
            finally{tokenClaims=original}
        }
    }
    def 'token failures never retry or use refresh or access token as proof'(){
        given:tokenStatus=302
        when:exchange()
        then:thrown(LegacyMigrationOidcClient.Unavailable)
        requests.size()==1&&outputs.empty&&observations.empty
        when:
        attempt=new FixtureAttempt();tokenStatus=200;responseOverride=JsonOutput.toJson([token_type:'Bearer',access_token:'not-an-id-token',refresh_token:'not-proof'])
        exchange()
        then:thrown(LegacyMigrationOidcClient.Unavailable)
        requests.size()==2&&outputs.empty&&observations.empty
    }
    def 'negative mismatched or closed observation cannot reach private sink'(){
        expect:
        ['negative','wrong','close'].every{mode ->
            attempt=new FixtureAttempt();negative=mode=='negative';wrongObservation=mode=='wrong';closeDuringObservation=mode=='close'
            try{exchange();false}catch(LegacyMigrationOidcClient.Unavailable ignored){assert outputs.empty;true}
        }
    }
    def 'creator one shot prevents callback exchange replay and settings mismatch rejects before network'(){
        when:positiveExchange()
        then:outputs.size()==1
        when:exchange()
        then:thrown(LegacyMigrationOidcClient.Unavailable)
        requests.size()==2 // Original token + JWKS only.
        when:
        attempt=new FixtureAttempt(targetClient:'foreign');outputs.clear();exchange()
        then:thrown(LegacyMigrationOidcClient.Unavailable)
        outputs.empty&&requests.size()==2
    }
    def 'token work consumes the observation budget instead of starting another four seconds'(){
        given:tokenDelayMillis=150
        when:positiveExchange()
        then:
        outputs.size()==1
        observations[0][2]<Duration.ofMillis(3900)
        observations[0][1]==attempt.originalDatabaseStart()
    }
    private static String readBody(HttpRequest request){
        def bytes=new java.io.ByteArrayOutputStream()
        request.bodyPublisher().get().subscribe(new java.util.concurrent.Flow.Subscriber<java.nio.ByteBuffer>(){
            void onSubscribe(java.util.concurrent.Flow.Subscription s){s.request(Long.MAX_VALUE)}
            void onNext(java.nio.ByteBuffer b){byte[] v=new byte[b.remaining()];b.get(v);bytes.write(v)}
            void onError(Throwable ignored){throw new AssertionError('Unexpected fixture publisher failure')}
            void onComplete(){}
        })
        bytes.toString('US-ASCII')
    }
    static final class FixtureAttempt implements P.Attempt {
        int verifierReads
        String verifier=VERIFIER
        LegacyMigration.ProofMode mode=LegacyMigration.ProofMode.LOCAL_PASSWORD
        String expected,legacyIss,legacySub
        String targetClient=CLIENT
        UUID attemptId(){UUID.fromString('7e394f1d-cc2b-490a-a6d2-c61e152d538b')}
        UUID receiptId(){UUID.fromString('cf940d44-a496-46bb-bcbe-341ad2f14b9a')}
        UUID migrationId(){UUID.fromString('6337e232-c485-4e82-a5ef-e6ae6d86a4d3')}
        UUID ownerId(){UUID.fromString('19041929-b84e-4e14-9e7e-eb91b80ca103')}
        String issuer(){ISSUER};String clientId(){targetClient};String redirectUri(){REDIRECT.toString()};String expectedSubject(){expected}
        LegacyMigration.ProofMode accountProofMode(){mode};String legacyIssuer(){legacyIss};String legacySubject(){legacySub}
        String pkceChallenge(){Base64.urlEncoder.withoutPadding().encodeToString(MessageDigest.getInstance('SHA-256').digest(VERIFIER.getBytes('US-ASCII')))}
        Instant challengeStartedAt(){NOW.minusSeconds(10)};Instant originalDatabaseStart(){NOW}
        Instant originalAccountDeadline(){NOW.plusSeconds(60)};Instant originalAttemptDeadline(){NOW.plusSeconds(60)}
        void requireCallback(P.Callback c){if(c!=CALLBACK)throw new IllegalStateException('Synthetic callback gate refused')}
        String originalVerifier(){if(++verifierReads!=1)throw new IllegalStateException('Synthetic custody consumed');verifier}
        boolean nonceMatches(String signed){MessageDigest.isEqual('original-nonce'.bytes,signed.bytes)}
        void requireLive(){if(Thread.currentThread().isInterrupted())throw new IllegalStateException('Synthetic attempt cancelled')}
    }
    static final class SyntheticHttp extends HttpClient {
        Closure responder
        Optional<java.net.CookieHandler> cookieHandler(){Optional.empty()};Optional<Duration> connectTimeout(){Optional.of(Duration.ofSeconds(2))}
        HttpClient.Redirect followRedirects(){HttpClient.Redirect.NEVER};Optional<java.net.ProxySelector> proxy(){Optional.empty()}
        javax.net.ssl.SSLContext sslContext(){javax.net.ssl.SSLContext.getDefault()};javax.net.ssl.SSLParameters sslParameters(){new javax.net.ssl.SSLParameters()}
        Optional<java.net.Authenticator> authenticator(){Optional.empty()};HttpClient.Version version(){HttpClient.Version.HTTP_1_1};Optional<Executor> executor(){Optional.empty()}
        public <T> HttpResponse<T> send(HttpRequest r,HttpResponse.BodyHandler<T> h){throw new UnsupportedOperationException()}
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest r,HttpResponse.BodyHandler<T> h){responder.call(r,h) as CompletableFuture<HttpResponse<T>>}
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest r,HttpResponse.BodyHandler<T> h,HttpResponse.PushPromiseHandler<T> p){throw new UnsupportedOperationException()}
        void shutdownNow(){}
    }
    /** Explicit immutable wire data avoids response-method/local-variable mock resolution. */
    static final class SyntheticResponse implements HttpResponse<byte[]> {
        private final HttpRequest originalRequest
        private final int replyStatus
        private final HttpHeaders replyHeaders
        private final byte[] replyBytes
        SyntheticResponse(HttpRequest request,int status,String type,byte[] bytes){
            originalRequest=request;replyStatus=status;replyHeaders=HttpHeaders.of(['Content-Type':[type]],{a,b->true});replyBytes=Arrays.copyOf(bytes,bytes.length)
        }
        int statusCode(){replyStatus};HttpRequest request(){originalRequest}
        Optional<HttpResponse<byte[]>> previousResponse(){Optional.empty()}
        HttpHeaders headers(){replyHeaders};byte[] body(){Arrays.copyOf(replyBytes,replyBytes.length)}
        Optional<javax.net.ssl.SSLSession> sslSession(){Optional.empty()}
        URI uri(){originalRequest.uri()};HttpClient.Version version(){HttpClient.Version.HTTP_1_1}
    }
}
