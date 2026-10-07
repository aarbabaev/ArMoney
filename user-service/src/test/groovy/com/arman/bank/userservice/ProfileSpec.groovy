package com.arman.bank.userservice

import com.arman.bank.userservice.domain.Profile
import spock.lang.Specification

class ProfileSpec extends Specification {
    def "UAE mobile inputs accept exactly the assigned mobile prefixes"() {
        expect:
        Profile.validatePhone('+9715' + prefix + '1234567') == '+9715' + prefix + '1234567'
        where:
        prefix << ['0','2','4','5','6','8']
    }
    def "invalid new inputs are rejected"() {
        when:
        Profile.validatePhone(phone)
        then:
        thrown(IllegalArgumentException)
        where:
        phone << [null, '', '+15550000001', '0501234567', '+971511234567', '+971531234567', '+971571234567', '+971591234567', '+97150123456', '+9715012345678', ' +971501234567']
    }
    def "historical international profile data remains readable"() {
        expect:
        new Profile(UUID.randomUUID(), UUID.randomUUID(), 'Historical', '+15550000001', true).phoneVerified()
    }
}
