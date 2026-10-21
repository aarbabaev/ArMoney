package com.arman.bank.authservice

import com.arman.bank.authservice.application.*
import com.arman.bank.authservice.infrastructure.*
import groovy.json.JsonOutput
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import spock.lang.Specification

/** Main's default-OFF protected behavior and borrowed-port ownership only.
 * No database/provider/session issuance or enabled migration propagation pass.
 * The concrete enabled store needs genuine persisted MIG evidence to reach its
 * current-User dependency; this spec deliberately uses no reflected field checks.
 */
class MainMigrationCompositionSpec extends Specification {
    static final String ISSUER='https://issuer.example/realm'

    static final class CurrentProbe implements MigrationUserCurrentPort, AutoCloseable {
        final AtomicInteger observations=new AtomicInteger()
        final AtomicInteger closes=new AtomicInteger()
        @Override MigrationUserCurrentPort.Observation observe(MigrationUserCurrentPort.Query query,Duration budget){
            observations.incrementAndGet()
            throw new AssertionError('Default-OFF composition invoked a borrowed User-current port')
        }
        @Override void close(){closes.incrementAndGet()}
        @Override String toString(){'CurrentProbe[synthetic]'}
    }

    def 'six argument OFF factory does not require database confirmation or invoke supplied migration dependency'(){
        given:
        def environment=[ARMONEY_SSO_MANAGED_PAIRS:JsonOutput.toJson([[issuer:ISSUER,client_id:'managed-client']])]
        def configuration=new ManagedBankingConfiguration(environment)
        def migrationCrypto=new LegacyMigrationManagedProofCrypto('synthetic',[synthetic:([91]*32) as byte[]])
        def current=new CurrentProbe()
        // null DB and confirmation would fail immediately if the selected branch
        // allocated its concrete managed adapter or opened its managed observer.
        def resources=Main.openManaged(null,configuration,null,environment,migrationCrypto,current)
        expect:
        !resources.service().enabled()
        resources.service().manages(ISSUER,'managed-client')
        !resources.service().manages(ISSUER,'unknown-client')
        when:resources.service().exchange('synthetic.token',null)
        then:thrown(AuthFailure)
        current.observations.get()==0
        when:resources.service().validate('Bearer '+('x'*43))
        then:thrown(AuthFailure)
        current.observations.get()==0
        when:resources.service().logout('Bearer '+('x'*43))
        then:thrown(AuthFailure)
        current.observations.get()==0
        when:resources.start();resources.start();resources.close();resources.close()
        then:noExceptionThrown()
        current.observations.get()==0 && current.closes.get()==0
        when:resources.start()
        then:thrown(IllegalStateException)
        cleanup:resources?.close();current?.close()
    }

    def 'old forwarding overload and explicit null migration arguments retain the same deny only contract'(){
        given:
        def configuration=new ManagedBankingConfiguration([:])
        def resources=explicitNull
            ? Main.openManaged(null,configuration,null,[:],null,null)
            : Main.openManaged(null,configuration,null,[:])
        expect:!resources.service().enabled()
        when:resources.service().exchange('synthetic.token',null)
        then:thrown(AuthFailure)
        when:resources.start();resources.close();resources.close()
        then:noExceptionThrown()
        cleanup:resources?.close()
        where:explicitNull << [false,true]
    }
}
