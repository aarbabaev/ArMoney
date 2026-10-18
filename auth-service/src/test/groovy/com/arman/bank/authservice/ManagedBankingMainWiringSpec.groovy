package com.arman.bank.authservice

import com.arman.bank.authservice.application.*
import com.arman.bank.authservice.infrastructure.*
import spock.lang.Specification
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Main ownership/configuration only. No database, actual observer, token or provider acceptance evidence. */
class ManagedBankingMainWiringSpec extends Specification {
    def 'off Main owner needs no database observer secret or scheduler and stays deny-only'(){
        given:
        def config=new ManagedBankingConfiguration([:])
        def confirmation=new CodeConfirmationConfiguration([:])
        def resources=Main.openManaged(null,config,confirmation,[:])
        expect:!resources.service().enabled()
        when:resources.start();resources.close();resources.close()
        then:noExceptionThrown()
        when:resources.service().exchange('synthetic.token',new SsoTokens.Principal('https://issuer.example','subject','+971501234567'))
        then:thrown(AuthFailure)
        when:resources.start()
        then:thrown(IllegalStateException)
    }
    def 'owned cleanup starts only explicitly and close cancels worker before observer exactly once'(){
        given:
        def store=Mock(ManagedBankingStore)
        def observation=Mock(CodeConfirmationObservations)
        def service=new ManagedBankingService(false,store,observation,{issuer,client->false} as ManagedBankingService.PairScope)
        def ran=new CountDownLatch(1)
        def calls=new AtomicInteger()
        def closed=new AtomicInteger()
        def resources=new Main.ManagedResources(service,{calls.incrementAndGet();ran.countDown()} as Runnable,{closed.incrementAndGet()} as Runnable)
        expect:calls.get()==0;closed.get()==0
        when:resources.start();resources.start()
        then:ran.await(3,TimeUnit.SECONDS);calls.get()==1
        when:resources.close();resources.close()
        then:closed.get()==1;0 * store._;0 * observation._
        cleanup:resources.close()
    }
    def 'startup failure cleanup closes not-yet-started observer once and preserves cleanup failure identity'(){
        given:
        def service=new ManagedBankingService(false,Mock(ManagedBankingStore),Mock(CodeConfirmationObservations),{issuer,client->false} as ManagedBankingService.PairScope)
        def expected=new IllegalStateException('synthetic-close-failure')
        def calls=new AtomicInteger()
        def resources=new Main.ManagedResources(service,{} as Runnable,{calls.incrementAndGet();throw expected} as Runnable)
        when:resources.close()
        then:def actual=thrown(IllegalStateException);actual.is(expected);calls.get()==1
        when:resources.close()
        then:noExceptionThrown();calls.get()==1
    }
    def 'enabled incomplete configuration fails before any Main database factory could be invoked'(){
        when:new ManagedBankingConfiguration([AUTH_MANAGED_BANKING_ENABLED:'true'])
        then:thrown(IllegalArgumentException)
    }
    def 'unconfigured strict off retains historical legacy SSO without optional client metadata'(){
        given:
        def environment=[:]
        def configuration=new ManagedBankingConfiguration(environment)
        def resources=Main.openManaged(null,configuration,new CodeConfirmationConfiguration([:]),environment)
        def store=Mock(AuthStore)
        def passwords=Stub(PasswordHasher){hash(_) >> 'synthetic-hash'}
        def profiles=Mock(RegistrationProfiles)
        def auth=Main.authService(store,passwords,java.time.Clock.systemUTC(),profiles,resources,configuration,Main.historicalAuthMode(configuration,environment))
        def identity=new com.arman.bank.authservice.domain.Identity(UUID.randomUUID(),null,'+971501234567')
        when:def session=auth.sso('synthetic.token',{token->new SsoTokens.Principal('https://issuer.example/realm','legacy-subject','+971501234567')} as SsoTokens)
        then:session.accessToken().size()==43;auth.expiresIn(session)==1800
        1 * store.externalIdentity(_) >> identity
        1 * store.requireBankingAccess(identity.id())
        1 * profiles.provision(identity.id(),identity.registrationPhone())
        1 * store.saveSession(_,identity.id(),_,_)
        cleanup:resources.close()
    }
    def 'configured classification never downgrades unknown or managed pairs while off'(){
        given:
        def issuer='https://issuer.example/realm'
        def environment=[ARMONEY_SSO_MANAGED_PAIRS:groovy.json.JsonOutput.toJson([[issuer:issuer,client_id:'armoney-web']])]
        def configuration=new ManagedBankingConfiguration(environment)
        def resources=Main.openManaged(null,configuration,new CodeConfirmationConfiguration([:]),environment)
        def store=Mock(AuthStore)
        def profiles=Mock(RegistrationProfiles)
        def passwords=Stub(PasswordHasher){hash(_) >> 'synthetic-hash'}
        def auth=Main.authService(store,passwords,java.time.Clock.systemUTC(),profiles,resources,configuration,Main.historicalAuthMode(configuration,environment))
        def context=new SsoTokens.TokenContext(SsoTokens.Metadata.present(client),SsoTokens.Metadata.absent(),SsoTokens.Metadata.absent(),SsoTokens.Metadata.absent(),SsoTokens.Metadata.absent())
        when:auth.sso('synthetic.token',{token->new SsoTokens.Principal(issuer,'subject','+971501234567',null,false,context)} as SsoTokens)
        then:thrown(AuthFailure);0 * store._;0 * profiles._
        cleanup:resources.close()
        where:client << ['armoney-web','unknown-client']
    }
    def 'supplied dormant managed material also prevents historical fallback'(){
        given:def environment=[AUTH_MANAGED_BANKING_KEY_ID:'configured-dormant']
        def configuration=new ManagedBankingConfiguration(environment)
        expect:!Main.historicalAuthMode(configuration,environment)
    }

}
