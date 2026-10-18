package com.arman.bank.authservice

import com.arman.bank.authservice.application.*
import spock.lang.Specification

class ManagedBankingServiceSpec extends Specification {
    def 'default off consumer touches no store or observer'(){
        given:
        def store=Mock(ManagedBankingStore)
        def observations=Mock(CodeConfirmationObservations)
        def service=new ManagedBankingService(false,store,observations,{issuer,client->true} as ManagedBankingService.PairScope)
        when:service.exchange('synthetic.token',new SsoTokens.Principal('https://issuer.example','subject','+971501234567'))
        then:thrown(AuthFailure);0 * store._;0 * observations._
    }
    def 'legacy absent metadata cannot become managed authority'(){
        given:
        def store=Mock(ManagedBankingStore)
        def observations=Mock(CodeConfirmationObservations)
        def service=new ManagedBankingService(true,store,observations,{issuer,client->true} as ManagedBankingService.PairScope)
        when:service.exchange('synthetic.token',new SsoTokens.Principal('https://issuer.example','subject','+971501234567'))
        then:thrown(AuthFailure);0 * store._;0 * observations._
    }
}
