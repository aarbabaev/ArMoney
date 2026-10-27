package com.arman.bank.authservice.infrastructure

import com.arman.bank.authservice.application.MigrationLegacyAccountProofPort as A
import com.arman.bank.authservice.application.MigrationManagedProofPort as P
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

/** Synthetic unknown-owner attempt + genuine RSA signature; never a database mapping/receipt claim. */
class LegacyMigrationLegacyAccountProviderClientSpec extends Specification {
    static final Instant NOW=Instant.parse('2026-10-18T10:00:00Z')
    static final String ISSUER='https://issuer.example/sso/realms/armoney',CLIENT='legacy-client'
    static final URI TOKEN=URI.create(ISSUER+'/protocol/openid-connect/token'),JWKS=URI.create(ISSUER+'/protocol/openid-connect/certs')
    static final URI REDIRECT=URI.create('https://migration.example/migration/legacy/account-oidc/callback')
    static final String VERIFIER='server-original-account-verifier-abcdefghijklmnopqrstuvwxyz1234567890'
    static final P.Callback CALLBACK=new P.Callback('account-code','account-state','account-browser-cookie')
    def key=new RSAKeyGenerator(2048).keyID('legacy-signing-key').generate()
    def time=new java.util.concurrent.atomic.AtomicReference<Instant>(NOW)
    Clock clock=new Clock(){ZoneId getZone(){ZoneOffset.UTC};Clock withZone(ZoneId z){this};Instant instant(){time.get()}}
    Map values
    List requests=new CopyOnWriteArrayList(),outputs=[],observed=[]
    FixtureAttempt attempt=new FixtureAttempt()
    int status=200
    String responseOverride
    boolean negative,wrongBinding,lateObservation,closeObservation
    def http=new LegacyMigrationManagedProviderClientSpec.SyntheticHttp()
    LegacyMigrationLegacyAccountProviderClient adapter
    def setup(){
        values=[iss:ISSUER,sub:'cryptographically-known-only-after-verification',sid:'legacy-session',aud:CLIENT,azp:CLIENT,nonce:'account-nonce',
                auth_time:NOW.minusSeconds(5).epochSecond,iat:NOW.epochSecond,exp:NOW.plusSeconds(60).epochSecond]
        http.responder={HttpRequest request,handler ->
            requests.add(request)
            boolean token=request.uri()==TOKEN
            assert token||request.uri()==JWKS
            byte[] body=token?(responseOverride?:JsonOutput.toJson([token_type:'Bearer',access_token:'unused',id_token:signed(),refresh_token:'unused-refresh'])).bytes:
                JsonOutput.toJson([keys:[key.toPublicJWK().toJSONObject()]]).bytes
            int reply=token?status:200
            def response=new LegacyMigrationManagedProviderClientSpec.SyntheticResponse(request,reply,'application/json',body)
            CompletableFuture.completedFuture(response)
        }
        def jwt=new LegacyMigrationOidcClient(new LegacyMigrationOidcClient.Settings(URI.create(ISSUER),JWKS,CLIENT),clock,http)
        P.Observations observer={binding,start,budget ->
            observed.add([binding,start,budget])
            if(lateObservation)time.set(NOW.plusSeconds(5))
            if(closeObservation)adapter.close()
            new CodeConfirmation.Facts(wrongBinding?new LoginProof.Binding(ISSUER,'wrong-subject',CLIENT,'legacy-session'):binding,
                '+971501234567','fixture@example.test','+971501234567',!negative,true,true,NOW,NOW.plusSeconds(5))
        } as P.Observations
        adapter=new LegacyMigrationLegacyAccountProviderClient(new LegacyMigrationLegacyAccountProviderClient.Settings(URI.create(ISSUER),TOKEN,JWKS,CLIENT,REDIRECT),clock,observer,http,jwt)
    }
    def cleanup(){adapter?.close()}
    String signed(){
        def jwt=new JWSObject(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID('legacy-signing-key').type(JOSEObjectType.JWT).build(),new Payload(JsonOutput.toJson(values)))
        jwt.sign(new RSASSASigner(key));jwt.serialize()
    }
    void exchange(P.Callback callback=CALLBACK){adapter.exchange(attempt,callback,{claims,facts->outputs.add([claims,facts])} as A.VerifiedSink)}
    private enum FixturePhase { BEFORE_TOKEN, TOKEN_RESPONSE, JWKS, OBSERVATION, SINK }
    private void positiveExchange(){
        try{exchange()}
        catch(LegacyMigrationOidcClient.Unavailable ignored){
            FixturePhase phase=!outputs.empty?FixturePhase.SINK:!observed.empty?FixturePhase.OBSERVATION:
                requests.any{it.uri()==JWKS}?FixturePhase.JWKS:!requests.empty?FixturePhase.TOKEN_RESPONSE:FixturePhase.BEFORE_TOKEN
            // Fixed local enum only: never include the exception, payload, claims, identity or URL.
            throw new AssertionError('FIXTURE_PHASE_'+phase.name())
        }
    }
    def 'unknown subject is selected solely from signed claims and exact active session before creator mapping lookup'(){
        when:positiveExchange()
        then:
        outputs.size()==1
        outputs[0][0].subject()==values.sub
        outputs[0][0].issuer()==ISSUER
        outputs[0][0].userSessionId()=='legacy-session'
        outputs[0][1].binding().subject()==values.sub
        outputs[0][0].authenticatedAt()==NOW.minusSeconds(5)
        requests*.uri()==[TOKEN,JWKS]
        requests[0].method()=='POST'
        requests[0].headers().firstValue('Authorization').empty
        observed[0][1]==NOW
        observed[0][2]>Duration.ZERO&&observed[0][2]<Duration.ofSeconds(4)
        attempt.reads==1
        !outputs[0][0].toString().contains(values.sub)
    }
    def 'foreign browser state or code cannot consume original custody or contact provider'(){
        expect:
        [new P.Callback('foreign','account-state','account-browser-cookie'),new P.Callback('account-code','foreign','account-browser-cookie'),
         new P.Callback('account-code','account-state','foreign')].every{callback ->
            try{exchange(callback);false}catch(LegacyMigrationOidcClient.Unavailable ignored){assert requests.empty&&outputs.empty&&attempt.reads==0;true}
        }
    }
    def 'missing original custody or S256 mismatch cannot exchange code'(){
        expect:
        [null,'changed-original-server-custody-abcdefghijklmnopqrstuvwxyz1234567890'].every{original ->
            attempt=new FixtureAttempt(verifier:original)
            try{exchange();false}catch(LegacyMigrationOidcClient.Unavailable ignored){assert requests.empty&&outputs.empty;true}
        }
    }
    def 'signature claim nonce audience issuer interactive time and sid failures cannot emit account data'(){
        expect:
        [[iss:'https://foreign.example'],[aud:'managed-client'],[azp:'managed-client'],[nonce:'wrong'],[sid:null],
         [auth_time:null],[auth_time:NOW.minusSeconds(121).epochSecond],[auth_time:NOW.plusSeconds(6).epochSecond]].every{change ->
            attempt=new FixtureAttempt();def previous=values;values=previous+change
            try{exchange();false}catch(LegacyMigrationOidcClient.Unavailable ignored){assert outputs.empty&&observed.empty;true}
            finally{values=previous}
        }
    }
    def 'token uncertainty or refresh only response never retries or fabricates a proof'(){
        given:status=503
        when:exchange()
        then:thrown(LegacyMigrationOidcClient.Unavailable)
        requests.size()==1&&outputs.empty&&observed.empty
        when:
        attempt=new FixtureAttempt();status=200;responseOverride=JsonOutput.toJson([token_type:'Bearer',access_token:'only-access',refresh_token:'not-fresh-authentication'])
        exchange()
        then:thrown(LegacyMigrationOidcClient.Unavailable)
        requests.size()==2&&outputs.empty&&observed.empty
    }
    def 'negative foreign late or closed session observation cannot reach private creator'(){
        expect:
        ['negative','foreign','late','close'].every{mode ->
            attempt=new FixtureAttempt();time.set(NOW);negative=mode=='negative';wrongBinding=mode=='foreign';lateObservation=mode=='late';closeObservation=mode=='close'
            try{exchange();false}catch(LegacyMigrationOidcClient.Unavailable ignored){assert outputs.empty;true}
        }
    }
    def 'one shot original account callback cannot be reexchanged'(){
        when:positiveExchange()
        then:outputs.size()==1
        when:exchange()
        then:thrown(LegacyMigrationOidcClient.Unavailable)
        requests.size()==2
    }
    def 'configured legacy callback and client cannot be chosen by attempt input'(){
        given:attempt.targetClient='managed-client'
        when:exchange()
        then:thrown(LegacyMigrationOidcClient.Unavailable)
        requests.empty&&outputs.empty&&attempt.reads==0
    }
    static final class FixtureAttempt implements A.Attempt {
        int reads
        String verifier=VERIFIER,targetClient=CLIENT
        UUID attemptId(){UUID.fromString('a130eaac-4561-4b1e-b664-638f24f3f01a')}
        String issuer(){ISSUER};String clientId(){targetClient};String redirectUri(){REDIRECT.toString()}
        String pkceChallenge(){Base64.urlEncoder.withoutPadding().encodeToString(MessageDigest.getInstance('SHA-256').digest(VERIFIER.getBytes('US-ASCII')))}
        Instant challengeStartedAt(){NOW.minusSeconds(10)};Instant originalDatabaseStart(){NOW};Instant originalAttemptDeadline(){NOW.plusSeconds(60)}
        void requireCallback(P.Callback callback){if(callback!=CALLBACK)throw new IllegalStateException('Synthetic account callback refused')}
        String originalVerifier(){if(++reads!=1)throw new IllegalStateException('Synthetic original custody consumed');verifier}
        boolean nonceMatches(String nonce){MessageDigest.isEqual('account-nonce'.bytes,nonce.bytes)}
        void requireLive(){if(Thread.currentThread().isInterrupted())throw new IllegalStateException('Synthetic cancelled account attempt')}
    }
}
