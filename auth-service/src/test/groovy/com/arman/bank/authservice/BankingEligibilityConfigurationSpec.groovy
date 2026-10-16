package com.arman.bank.authservice

import com.arman.bank.authservice.infrastructure.BankingEligibilityConfiguration
import spock.lang.Specification

class BankingEligibilityConfigurationSpec extends Specification {
    def 'independent flag defaults off regardless of other bridge flags'() {
        expect:
        !new BankingEligibilityConfiguration([:]).enabled
        !new BankingEligibilityConfiguration([AUTH_VERIFICATION_AUTHORITY_BRIDGE_ENABLED:'true', AUTH_VERIFICATION_PROJECTION_ENABLED:'true', AUTH_LOGIN_PROOF_BRIDGE_ENABLED:'true']).enabled
        new BankingEligibilityConfiguration([AUTH_BANKING_ELIGIBILITY_ENFORCED:'true']).enabled
        !new BankingEligibilityConfiguration([AUTH_BANKING_ELIGIBILITY_ENFORCED:'false']).enabled
    }
    def 'nonliteral enforcement flag fails startup'() {
        when:
        new BankingEligibilityConfiguration([AUTH_BANKING_ELIGIBILITY_ENFORCED:value])
        then:
        thrown(IllegalArgumentException)
        where:
        value << ['', 'TRUE', 'False', ' true', '1', null]
    }
}