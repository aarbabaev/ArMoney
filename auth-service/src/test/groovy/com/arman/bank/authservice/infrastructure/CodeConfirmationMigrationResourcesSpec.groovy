package com.arman.bank.authservice.infrastructure

import com.arman.bank.authservice.CodeConfirmationConfigurationSpec
import com.arman.bank.authservice.application.MigrationUserCurrentPort
import spock.lang.Specification

class CodeConfirmationMigrationResourcesSpec extends Specification {
    def 'old and injection overloads remain allocation free when C2 is OFF'(){
        given:def off=new CodeConfirmationConfiguration([:])
        expect:
        CodeConfirmationResources.open(null,off).empty
        CodeConfirmationResources.open(null,off,null,null).empty
        CodeConfirmationResources.open(null,off,new LegacyMigrationManagedProofCrypto('proof',[proof:([61]*32) as byte[]]),null).empty
    }
    def 'selected migration injection rejects partial dependencies before database or HTTP allocation'(){
        given:def selected=new CodeConfirmationConfiguration(CodeConfirmationConfigurationSpec.environment())
        def crypto=new LegacyMigrationManagedProofCrypto('proof',[proof:([61]*32) as byte[]])
        MigrationUserCurrentPort never={query,remaining->throw new AssertionError('observation during composition')} as MigrationUserCurrentPort
        when:CodeConfirmationResources.open(null,selected,crypto,null)
        then:thrown(IllegalArgumentException)
        when:CodeConfirmationResources.open(null,selected,null,never)
        then:thrown(IllegalArgumentException)
        when:CodeConfirmationResources.open(null,selected,crypto,never)
        then:thrown(NullPointerException)
    }
}
