package com.arman.bank.ledgerservice

import com.arman.bank.ledgerservice.infrastructure.LocalDemoFunding
import com.arman.bank.ledgerservice.infrastructure.LocalDemoFundingMain
import spock.lang.Specification
import spock.lang.Unroll

class LocalDemoFundingSpec extends Specification {
    @Unroll
    def 'local funding rejects missing or wrong gate #environment'() {
        when:
        LocalDemoFunding.requireLocalOptIn(environment)
        then:
        thrown(IllegalStateException)
        where:
        environment << [[:], [APP_ENV:'local'], [ARMONEY_LOCAL_DEMO_FUNDING:'I_UNDERSTAND_SYNTHETIC_FUNDS'],
            [APP_ENV:'production', ARMONEY_LOCAL_DEMO_FUNDING:'I_UNDERSTAND_SYNTHETIC_FUNDS'],
            [APP_ENV:'local', ARMONEY_LOCAL_DEMO_FUNDING:'true']]
    }

    @Unroll
    def 'CLI rejects invalid amount #amount'() {
        when:
        LocalDemoFundingMain.parse(([UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()]*.toString() + amount) as String[])
        then:
        thrown(IllegalArgumentException)
        where:
        amount << ['0','99999','500001','1.5','9223372036854775808']
    }

    def 'CLI accepts integer fils at both baseline bounds'() {
        expect:
        [100000,500000].every { amount ->
            LocalDemoFundingMain.parse(([UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()]*.toString() + amount.toString()) as String[]).amountFils() == amount
        }
    }
}
