package com.arman.bank.authservice

import com.arman.bank.authservice.application.*
import com.arman.bank.authservice.domain.Identity
import com.arman.bank.authservice.domain.ManagedBankingAccess
import spock.lang.Specification
import java.time.*

/** Admission routing evidence only; synthetic authenticated principals are not provider-runtime evidence. */
class ManagedCutoverAdmissionSpec extends Specification {
    static final String ISSUER='https://issuer.example/realm'
    static final String PASSWORD='synthetic long legacy password'
    def store=Mock(AuthStore)
    def passwords=Mock(PasswordHasher)
    def profiles=Mock(RegistrationProfiles)
    def banking=Mock(ManagedBankingStore)
    def observations=Mock(CodeConfirmationObservations)
    def now=Instant.parse('2026-10-18T00:00:00Z')

    AuthService auth(boolean enabled=true) {
        new AuthService(store,passwords,Clock.fixed(now,ZoneOffset.UTC),profiles,
            new ManagedBankingService(enabled,banking,observations,{issuer,client->issuer==ISSUER&&client=='armoney-android'} as ManagedBankingService.PairScope),
            {issuer,client->issuer==ISSUER&&client=='armoney-web'})
    }
    SsoTokens.Principal principal(String client='armoney-web',boolean proof=false) {
        new SsoTokens.Principal(ISSUER,'legacy-subject','+971501234567','legacy@example.test',true, true,
            new SsoTokens.TokenContext(SsoTokens.Metadata.present(client),SsoTokens.Metadata.present('user-session'),
                SsoTokens.Metadata.present(now),SsoTokens.Metadata.present(now.plusSeconds(120)),
                proof?SsoTokens.Metadata.present(new SsoTokens.ProofClaims('interactive',UUID.randomUUID(),false)):SsoTokens.Metadata.absent()))
    }

    def 'cutover denies explicit legacy pair before any business read or write even with proof-shaped claims'() {
        given:def service=auth();def provider=Mock(SsoTokens)
        when:service.sso('synthetic.token',provider)
        then:
        1 * provider.verify('synthetic.token') >> principal('armoney-web',proof)
        def failure=thrown(AuthFailure);failure.kind()==AuthFailure.Kind.UNAUTHORIZED
        0 * store._;0 * profiles._;0 * banking._;0 * observations._
        where:proof << [false,true]
    }

    def 'cutover local admission uniformly denies before credentials rate state hashing or business mutation'() {
        given:def service=auth()
        when:
        if(register)service.register(email,password,phone)
        else service.login(email,password)
        then:
        def failure=thrown(AuthFailure);failure.kind()==AuthFailure.Kind.UNAUTHORIZED
        0 * store._;0 * profiles._;0 * banking._;0 * passwords.verify(_,_);0 * passwords.hash(password)
        where:
        register | email                  | password | phone
        true     | 'new@example.test'     | PASSWORD | '+971501234567'
        true     | 'legacy@example.test'  | PASSWORD | '+971501234567'
        false    | 'legacy@example.test'  | PASSWORD | null
        false    | 'unknown@example.test' | PASSWORD | null
        false    | 'legacy@example.test'  | 'wrong synthetic long password' | null
        true     | null                   | null     | null
        false    | null                   | null     | null
    }
    def 'default off still verifies unknown credentials with dummy hash and returns generic failure'() {
        given:def service=auth(false)
        when:service.login('unknown@example.test',PASSWORD)
        then:
        1 * store.allowAttempt(AuthService.digest('unknown@example.test'),now) >> true
        1 * store.findByEmail('unknown@example.test') >> Optional.empty()
        1 * passwords.verify(PASSWORD,_) >> false
        def failure=thrown(AuthFailure);failure.kind()==AuthFailure.Kind.UNAUTHORIZED
        0 * store._;0 * profiles._;0 * banking._
    }

    def 'default off retains bounded attempt rejection before credential verification'() {
        given:def service=auth(false)
        when:service.login('legacy@example.test',PASSWORD)
        then:
        1 * store.allowAttempt(_,now) >> false
        def failure=thrown(AuthFailure);failure.kind()==AuthFailure.Kind.RATE_LIMITED
        0 * store._;0 * passwords.verify(_,_);0 * profiles._
    }

    def 'default off explicit legacy pair still provisions and stores only session digest'() {
        given:def service=auth(false);def identity=new Identity(UUID.randomUUID(),'legacy@example.test','+971501234567');String savedDigest
        when:def session=service.sso('synthetic.token',{token->principal()} as SsoTokens)
        then:
        1 * store.externalIdentity(_) >> identity
        1 * store.requireBankingAccess(identity.id())
        1 * profiles.provision(identity.id(),identity.registrationPhone())
        1 * store.saveSession(_,identity.id(),now,now.plusSeconds(1800)) >> {hash,id,created,expiry->savedDigest=hash}
        savedDigest==AuthService.digest(session.accessToken())
        !session.boundedLifetime()
        0 * banking._;0 * observations._
    }

    def 'managed pair with missing proof never falls back to legacy mutation'() {
        given:def service=auth()
        when:service.sso('synthetic.token',{token->principal('armoney-android')} as SsoTokens)
        then:thrown(AuthFailure);0 * store._;0 * profiles._;0 * banking._;0 * observations._
    }

    def 'managed pair with full proof delegates to dedicated consumer without generic session writes'() {
        given:def service=auth()
        when:def session=service.sso('synthetic.token',{token->principal('armoney-android',true)} as SsoTokens)
        then:
        // Stub only the consumer result to prove routing, never authority or PostgreSQL correctness.
        1 * banking.inRequest(_) >> new ManagedBankingAccess.Decision(UUID.randomUUID(),now.plusSeconds(90),false)
        session.boundedLifetime();session.accessToken().length()==43;session.expiresAt()==now.plusSeconds(90)
        0 * store._;0 * profiles._
    }

    def 'unknown or absent client metadata never chooses legacy path in either mode'() {
        given:def service=auth(enabled)
        def authenticated=missing?new SsoTokens.Principal(ISSUER,'subject','+971501234567', null, false, false):principal('unknown-client')
        when:service.sso('synthetic.token',{token->authenticated} as SsoTokens)
        then:thrown(AuthFailure);0 * store._;0 * profiles._;0 * banking._
        where:
        enabled | missing
        true    | true
        true    | false
        false   | true
        false   | false
    }
}
