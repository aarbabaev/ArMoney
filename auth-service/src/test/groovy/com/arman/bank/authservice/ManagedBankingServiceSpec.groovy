package com.arman.bank.authservice

import com.arman.bank.authservice.application.*
import spock.lang.Specification

class ManagedBankingServiceSpec extends Specification {
    def 'default off consumer touches no store or observer'(){
        given:
        def store=Mock(ManagedBankingStore)
        def observations=Mock(CodeConfirmationObservations)
        def service=new ManagedBankingService(false,store,observations,{issuer,client->true} as ManagedBankingService.PairScope)
        when:service.exchange('synthetic.token',new SsoTokens.Principal('https://issuer.example','subject','+971501234567', null, false, false))
        then:thrown(AuthFailure);0 * store._;0 * observations._
    }
    def 'legacy absent metadata cannot become managed authority'(){
        given:
        def store=Mock(ManagedBankingStore)
        def observations=Mock(CodeConfirmationObservations)
        def service=new ManagedBankingService(true,store,observations,{issuer,client->true} as ManagedBankingService.PairScope)
        when:service.exchange('synthetic.token',new SsoTokens.Principal('https://issuer.example','subject','+971501234567', null, false, false))
        then:thrown(AuthFailure);0 * store._;0 * observations._
    }

    def 'unknown email verification format denies before store even with complete metadata'(){
        given:
        def store=Mock(ManagedBankingStore);def observations=Mock(CodeConfirmationObservations)
        def service=new ManagedBankingService(true,store,observations,{issuer,client->true} as ManagedBankingService.PairScope)
        def now=java.time.Instant.now()
        def context=new SsoTokens.TokenContext(SsoTokens.Metadata.present('armoney-web'),SsoTokens.Metadata.present('session'),
            SsoTokens.Metadata.present(now),SsoTokens.Metadata.present(now.plusSeconds(120)),
            SsoTokens.Metadata.present(new SsoTokens.ProofClaims('interactive',UUID.randomUUID(),true)))
        when:service.exchange('synthetic.token',new SsoTokens.Principal('https://issuer.example','subject','+971501234567','fixture@example.test',false,false,context))
        then:thrown(AuthFailure);0 * store._;0 * observations._
    }
    def 'known literal false is passed unchanged to the owning source admission'(){
        given:
        def store=Mock(ManagedBankingStore);def request=Mock(ManagedBankingStore.Request);def observations=Mock(CodeConfirmationObservations)
        def service=new ManagedBankingService(true,store,observations,{issuer,client->true} as ManagedBankingService.PairScope)
        def now=java.time.Instant.now()
        def context=new SsoTokens.TokenContext(SsoTokens.Metadata.present('armoney-web'),SsoTokens.Metadata.present('session'),
            SsoTokens.Metadata.present(now),SsoTokens.Metadata.present(now.plusSeconds(120)),
            SsoTokens.Metadata.present(new SsoTokens.ProofClaims('interactive',UUID.randomUUID(),true)))
        when:service.exchange('synthetic.token',new SsoTokens.Principal('https://issuer.example','subject','+971501234567','fixture@example.test',false,true,context))
        then:
        1 * store.inRequest(_) >> {java.util.function.Function work->work.apply(request)}
        1 * request.admitExchange({!it.providerEmailVerified()}) >> {throw new AuthFailure(AuthFailure.Kind.UNAUTHORIZED)}
        thrown(AuthFailure);0 * observations._;0 * request.exchange(_,_,_)
    }
}
