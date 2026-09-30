package com.arman.bank.authservice

import com.tngtech.archunit.core.importer.ClassFileImporter
import spock.lang.Specification
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes

class AuthArchitectureSpec extends Specification {
    def "domain and application are independent of HTTP SQL and crypto adapters"() {
        given:
        def imported = new ClassFileImporter().importPackages('com.arman.bank.authservice')

        expect:
        classes().that().resideInAPackage('..domain..').should().onlyDependOnClassesThat()
            .resideInAnyPackage('java..', '..domain..').check(imported)
        classes().that().resideInAPackage('..application..').should().onlyDependOnClassesThat()
            .resideInAnyPackage('java..', '..application..', '..domain..').check(imported)
    }
}
