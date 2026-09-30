package com.arman.bank.ledgerservice
import spock.lang.Specification
import com.tngtech.archunit.core.importer.ClassFileImporter
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes
class LedgerArchitectureSpec extends Specification {
    def "application depends only on its ports and domain"() {
        expect:
        classes().that().resideInAPackage('..application..').should().onlyDependOnClassesThat()
          .resideInAnyPackage('java..','com.arman.bank.ledgerservice.application..','com.arman.bank.ledgerservice.domain..')
          .check(new ClassFileImporter().importPackages('com.arman.bank.ledgerservice'))
    }
}
