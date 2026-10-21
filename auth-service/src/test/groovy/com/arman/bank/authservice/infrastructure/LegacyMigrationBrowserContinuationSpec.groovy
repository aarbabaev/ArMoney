package com.arman.bank.authservice.infrastructure

import com.arman.bank.authservice.domain.LegacyMigrationBrowserContinuation as C
import com.arman.bank.authservice.application.LegacyMigrationBrowserService as B
import spock.lang.Specification
import java.time.Instant

class LegacyMigrationBrowserContinuationSpec extends Specification {
    def 'only same channel consume then resend pair is a valid two form bundle'(){
        when:C.bundle([new C.Form(UUID.randomUUID(),first,UUID.randomUUID(),null,5,null),new C.Form(UUID.randomUUID(),second,null,Instant.now(),0,null)])
        then:thrown(IllegalArgumentException)
        where:
        first << [C.Purpose.EMAIL_CONSUME,C.Purpose.PHONE_CONSUME]
        second << [C.Purpose.PHONE_ISSUE,C.Purpose.EMAIL_ISSUE]
    }
    def 'valid siblings are immutable purpose specific descriptions and final needs server command'(){
        given:def consume=new C.Form(UUID.randomUUID(),C.Purpose.EMAIL_CONSUME,UUID.randomUUID(),null,4,null)
        def resend=new C.Form(UUID.randomUUID(),C.Purpose.EMAIL_ISSUE,null,Instant.now(),0,null)
        when:C.bundle([consume,resend])
        then:noExceptionThrown()
        when:new C.Form(UUID.randomUUID(),C.Purpose.FINAL,null,null,0,null)
        then:thrown(IllegalArgumentException)
    }
    def 'page copies forms and never renders csrf in diagnostics'(){
        given:def csrf='a'*43;def values=[new B.ActionForm(B.Action.EMAIL_ISSUE,csrf,UUID.randomUUID(),null,0)]
        def page=new B.ActionBundle(UUID.randomUUID(),values,Instant.now().plusSeconds(30))
        when:values.clear()
        then:page.forms().size()==1
        !page.toString().contains(csrf)
        !page.forms().first().toString().contains(csrf)
        when:page.forms().clear()
        then:thrown(UnsupportedOperationException)
    }
    def 'new hash domains are closed and retained original key is mandatory'(){
        given:def crypto=new LegacyMigrationManagedProofCrypto('new',[old:([31]*32) as byte[],'new':([32]*32) as byte[]])
        def old=crypto.hash('old','armoney-legacy-migration-browser-continuation-token-v1','1','original')
        expect:
        old.length==32
        !Arrays.equals(old,crypto.hash('new','armoney-legacy-migration-browser-continuation-token-v1','1','original'))
        when:crypto.hash('missing','armoney-legacy-migration-browser-continuation-token-v1','1','original')
        then:thrown(com.arman.bank.authservice.domain.LegacyMigrationOtp.Failure)
        when:crypto.hash('old','armoney-legacy-migration-browser-continuation-command-v1','1','original')
        then:thrown(com.arman.bank.authservice.domain.LegacyMigrationOtp.Failure)
    }
}
