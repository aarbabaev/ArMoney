package com.arman.bank.userservice

import spock.lang.Specification

class MainOwnedResourcesSpec extends Specification {
    def 'startup and shutdown share exactly one reverse cleanup'() {
        given:
        def events = []
        def resources = new Main.OwnedResources()
        resources.own({ events << 'database' } as AutoCloseable)
        resources.own({ events << 'client' } as AutoCloseable)
        resources.own({ events << 'route' } as AutoCloseable)

        when:
        resources.close()
        resources.close()

        then:
        events == ['route', 'client', 'database']
    }

    def 'cleanup continues after a selected component fails and retains both failures'() {
        given:
        def events = []
        def resources = new Main.OwnedResources()
        resources.own({ events << 'database'; throw new IllegalStateException('second') } as AutoCloseable)
        resources.own({ events << 'client'; throw new IllegalStateException('first') } as AutoCloseable)

        when:
        resources.close()

        then:
        def failure = thrown(IllegalStateException)
        failure.message == 'first'
        failure.suppressed*.message == ['second']
        events == ['client', 'database']

        when:
        resources.close()

        then:
        noExceptionThrown()
        events == ['client', 'database']
    }
}
