package com.arman.bank.authservice.infrastructure

import spock.lang.Specification

class LegacyMigrationResourcesSpec extends Specification {
    def 'release owner is idle until explicit start and resources close it before borrowed dependencies'(){
        given:
        def calls=[]
        def release=new MigrationUserReleaseWorker({calls << 'recover'} as Runnable)
        AutoCloseable dependency={calls << 'dependency'}
        def constructor=LegacyMigrationResources.declaredConstructors.find{it.parameterCount==6};constructor.accessible=true
        def resources=constructor.newInstance([dependency,release],null,null,null,null,release)
        expect:calls.empty
        when:resources.close();release.tick();resources.close()
        then:noExceptionThrown();calls==['dependency']
    }
    def 'off returns before examining database service or observer factory'() {
        expect:
        LegacyMigrationResources.open(null,null,LegacyMigrationConfiguration.from([:],[:]),null).empty
    }
    def 'enabled graph requires real borrowed database before any observer allocation'() {
        given:
        def c=LegacyMigrationConfiguration.from(LegacyMigrationConfigurationSpec.environment(),[:])
        int allocations=0
        LegacyMigrationResources.ObserverFactory factory={ settings -> allocations++;throw new AssertionError('must not allocate') }
        when:
        LegacyMigrationResources.open(null,null,c,factory)
        then:
        thrown(NullPointerException)
        allocations==0
    }
    def 'configuration does not implement or enable missing final cutover'() {
        given:
        def e=LegacyMigrationConfigurationSpec.environment()
        when:
        def c=LegacyMigrationConfiguration.from(e,[:])
        then:
        c.enabled
        !e.containsKey('AUTH_MANAGED_BANKING_ENABLED')
        !e.containsKey('AUTH_SSO_CODE_INTENT_CONFIRMATION_ENABLED')
        c.toString()=='LegacyMigrationConfiguration[redacted]'
    }
    def 'resource owner closes reverse order once and preserves all cleanup failures'() {
        given:
        def calls=[]
        AutoCloseable first={ calls << 'first';throw new IllegalStateException('first synthetic') }
        AutoCloseable second={ calls << 'second';throw new IllegalStateException('second synthetic') }
        def constructor=LegacyMigrationResources.declaredConstructors.find { it.parameterCount==5 }
        constructor.accessible=true
        def resources=constructor.newInstance([first,second],null,null,null,null)
        when:
        resources.close()
        then:
        def failure=thrown(IllegalStateException)
        calls==['second','first']
        failure.suppressed.length==2
        when:
        resources.close()
        then:
        noExceptionThrown()
        calls==['second','first']
    }
    def 'partial construction preserves primary failure and closes only acquired owners in reverse'() {
        given:
        def calls=[]
        AutoCloseable observer={ calls << 'observer' }
        AutoCloseable provider={ calls << 'provider';throw new IllegalStateException('synthetic cleanup') }
        def primary=new IllegalArgumentException('synthetic construction')
        when:
        LegacyMigrationResources.constructionFailed([observer,provider],primary)
        then:
        primary.message=='synthetic construction'
        primary.suppressed.length==1
        calls==['provider','observer']
    }
    def 'worker owner is stopped before transport and observer on shutdown'() {
        given:
        def calls=[]
        AutoCloseable observer={ calls << 'observer' }
        AutoCloseable transport={ calls << 'transport' }
        AutoCloseable worker={ calls << 'worker-stop' }
        def constructor=LegacyMigrationResources.declaredConstructors.find { it.parameterCount==5 }
        constructor.accessible=true
        def resources=constructor.newInstance([observer,transport,worker],null,null,null,null)
        when:
        resources.close()
        then:
        calls==['worker-stop','transport','observer']
    }
    def 'publication HTTP owner closes after worker before delivery and all accessors reject closed graph'(){
        given:def calls=[]
        AutoCloseable observer={calls << 'observer'}
        AutoCloseable stage={calls << 'stage'}
        AutoCloseable delivery={calls << 'delivery'}
        AutoCloseable publication={calls << 'publication'}
        AutoCloseable worker={calls << 'worker'}
        def constructor=LegacyMigrationResources.declaredConstructors.find{it.parameterCount==5};constructor.accessible=true
        def resources=constructor.newInstance([observer,stage,delivery,publication,worker],null,null,null,null)
        when:resources.close()
        then:calls==['worker','publication','delivery','stage','observer']
        when:resources.decisionRoutes()
        then:thrown(IllegalStateException)
        when:resources.routes()
        then:thrown(IllegalStateException)
        when:resources.otp()
        then:thrown(IllegalStateException)
        when:resources.startAfterHttpStartup()
        then:thrown(IllegalStateException)
    }
}
