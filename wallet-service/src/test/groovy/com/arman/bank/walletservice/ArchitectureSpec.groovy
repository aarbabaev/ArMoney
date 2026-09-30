package com.arman.bank.walletservice
import spock.lang.Specification
import com.tngtech.archunit.core.importer.ClassFileImporter
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes
class ArchitectureSpec extends Specification {
    def "domain and application do not depend on infrastructure"() {
        given:
        def imported = new ClassFileImporter().importPackages('com.arman.bank.walletservice')
        expect:
        classes().that().resideInAPackage('..domain..').should().onlyDependOnClassesThat()
            .resideInAnyPackage('java..', 'com.arman.bank.walletservice.domain..').check(imported)
        classes().that().resideInAPackage('..application..').should().onlyDependOnClassesThat()
            .resideInAnyPackage('java..','com.arman.bank.walletservice.domain..','com.arman.bank.walletservice.application..').check(imported)
    }
}
