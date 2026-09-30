package com.arman.bank.userservice
import spock.lang.Specification
import com.tngtech.archunit.core.importer.ClassFileImporter
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes
class ArchitectureSpec extends Specification {
    def "domain and application do not depend on infrastructure"() {
        given:
        def imported = new ClassFileImporter().importPackages('com.arman.bank.userservice')
        expect:
        classes().that().resideInAPackage('..domain..').should().onlyDependOnClassesThat()
            .resideInAnyPackage('java..', 'com.arman.bank.userservice.domain..').check(imported)
        classes().that().resideInAPackage('..application..').should().onlyDependOnClassesThat()
            .resideInAnyPackage('java..','com.arman.bank.userservice.domain..','com.arman.bank.userservice.application..').check(imported)
    }
}
