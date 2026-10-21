package com.arman.bank.authservice.infrastructure

import spock.lang.Specification

class MigrationUserMutationRoutesSpec extends Specification {
    def 'OFF route accepts absent store without allocating or registering dependencies'(){
        when:
        def routes=new MigrationUserMutationRoutes(MigrationUserRevisionConfiguration.from([:]),null)
        then:
        routes!=null
    }
    def 'selected route requires concrete store rather than positive fallback'(){
        when:
        new MigrationUserMutationRoutes(MigrationUserRevisionConfiguration.from(MigrationUserRevisionConfigurationSpec.environment()),null)
        then:
        thrown(IllegalArgumentException)
    }
}
