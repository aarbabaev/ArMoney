package com.arman.bank.ledgerservice

import com.arman.bank.ledgerservice.domain.Transfer
import com.tngtech.archunit.core.importer.ClassFileImporter
import spock.lang.Specification
import spock.lang.Unroll
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes

class TransferSpec extends Specification {
    @Unroll
    def "rejects unsupported currency #currency"() {
        when:
        new Transfer(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), Currency.getInstance(currency), 100L)
        then:
        thrown(IllegalArgumentException)
        where:
        currency << ['USD', 'EUR', 'GBP']
    }

    @Unroll
    def "rejects non-positive amount #amount"() {
        when:
        new Transfer(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), Currency.getInstance('AED'), amount)
        then:
        thrown(IllegalArgumentException)
        where:
        amount << [0L, -1L, Long.MIN_VALUE]
    }

    def "rejects self-transfer"() {
        given:
        def id = UUID.randomUUID()
        when:
        new Transfer(UUID.randomUUID(), id, id, Currency.getInstance('AED'), 100L)
        then:
        thrown(IllegalArgumentException)
    }

    def "preserves exact minor units"() {
        expect:
        new Transfer(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), Currency.getInstance('AED'), Long.MAX_VALUE).amountMinor() == Long.MAX_VALUE
    }

    def "domain depends only on the JDK and itself"() {
        expect:
        classes().that().resideInAPackage('..domain..').should().onlyDependOnClassesThat()
            .resideInAnyPackage('java..', '..domain..')
            .check(new ClassFileImporter().importPackages('com.arman.bank.ledgerservice.domain'))
    }
}
