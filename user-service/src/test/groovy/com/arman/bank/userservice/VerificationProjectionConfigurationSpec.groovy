package com.arman.bank.userservice

import com.arman.bank.userservice.infrastructure.VerificationProjectionConfiguration
import spock.lang.Specification

class VerificationProjectionConfigurationSpec extends Specification {
    def 'projection is disabled by default without credentials'() {
        expect:
        !VerificationProjectionConfiguration.from([:]).enabled()
        VerificationProjectionConfiguration.from([:]).writers().isEmpty()
    }
    def 'enabled configuration requires a distinct dedicated credential and fixed writer'() {
        given:
        def key = 'synthetic-projection-credential-000001'
        def env = [USER_VERIFICATION_PROJECTION_ENABLED:'true',
            AUTH_TO_USER_VERIFICATION_PROJECTION_KEY:key, USER_VERIFICATION_PROJECTION_WRITER_ID:'auth']
        expect:
        VerificationProjectionConfiguration.from(env).writers() == [auth:key]
        !VerificationProjectionConfiguration.from(env).toString().contains(key)
        when:
        VerificationProjectionConfiguration.from(env + [INTERNAL_AUTH_KEY:key])
        then:
        thrown(IllegalArgumentException)
    }
    def 'invalid or incomplete enabled configuration is rejected'() {
        when:
        VerificationProjectionConfiguration.from(env)
        then:
        thrown(IllegalArgumentException)
        where:
        env << [[USER_VERIFICATION_PROJECTION_ENABLED:'TRUE'],
            [USER_VERIFICATION_PROJECTION_ENABLED:'true'],
            [USER_VERIFICATION_PROJECTION_ENABLED:'true', AUTH_TO_USER_VERIFICATION_PROJECTION_KEY:'short', USER_VERIFICATION_PROJECTION_WRITER_ID:'auth']]
    }
    def 'writers are bounded and ambiguous credentials are rejected'() {
        when:
        new VerificationProjectionConfiguration(true, [auth:'x' * 32, other:'x' * 32])
        then:
        thrown(IllegalArgumentException)
        when:
        new VerificationProjectionConfiguration(true, (1..17).collectEntries { [("writer${it}".toString()):("key${it}".padRight(32,'x'))] })
        then:
        thrown(IllegalArgumentException)
    }
}
