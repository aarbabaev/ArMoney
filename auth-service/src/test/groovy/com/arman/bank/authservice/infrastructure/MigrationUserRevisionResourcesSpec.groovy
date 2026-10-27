package com.arman.bank.authservice.infrastructure

import spock.lang.Specification
import java.time.Duration

/** Technical construction/lifecycle only; no positive observation, mutation receipt or REG authority mock. */
class MigrationUserRevisionResourcesSpec extends Specification {
    def 'live resources default OFF before touching migration database or registration dependencies'(){
        expect:MigrationUserRevisionResources.open(null,null,MigrationUserRevisionConfiguration.from([:]),null).empty
    }
    def 'genuine REG resource accessors reject noncompletion rather than returning default authority'(){
        given:def constructor=VerificationAuthorityConfiguration.Resources.declaredConstructors.find{it.parameterCount==5};constructor.accessible=true
        def registration=constructor.newInstance(null,null,null,null,null)
        when:registration.registrationProjections()
        then:thrown(IllegalStateException)
        when:registration.registrationProjectionWriter()
        then:thrown(IllegalStateException)
    }
    def 'selected helper requires borrowed database before allocating live observation client'(){
        given:def revision=selected()
        when:MigrationUserRevisionResources.open(null,null,revision,null)
        then:thrown(NullPointerException)
    }
    def 'helper closes owned real client once and refuses further dependency access'(){
        given:
        def current=HttpMigrationUserCurrent.open(selected()).orElseThrow()
        def crypto=new LegacyMigrationManagedProofCrypto('proof',[proof:([62]*32) as byte[]])
        def constructor=MigrationUserRevisionResources.declaredConstructors.find{it.parameterCount==3};constructor.accessible=true
        def resources=constructor.newInstance(crypto,current,null)
        expect:resources.crypto().is(crypto);resources.userCurrent().is(current)
        when:resources.close();resources.close()
        then:noExceptionThrown()
        when:resources.crypto()
        then:thrown(IllegalStateException)
        when:resources.userCurrent()
        then:thrown(IllegalStateException)
        when:resources.mutationRoutes()
        then:thrown(IllegalStateException)
        when:current.observe(null,Duration.ofSeconds(1))
        then:thrown(IllegalStateException)
        cleanup:current?.close()
    }
    private static MigrationUserRevisionConfiguration selected(){new MigrationUserRevisionConfiguration(true,URI.create('http://user-service:8080'),'auth-current','w'*43,null,'user-mutation','z'*43)}
}
