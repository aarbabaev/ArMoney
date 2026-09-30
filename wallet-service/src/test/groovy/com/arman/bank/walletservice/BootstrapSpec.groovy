package com.arman.bank.walletservice

import com.arman.bank.runtime.ServiceRuntime
import com.tngtech.archunit.core.importer.ClassFileImporter
import spock.lang.Specification
import java.net.http.*
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses

class BootstrapSpec extends Specification {
    def "operational API and contract are served; business routes are absent"() {
        given:
        def runtime = ServiceRuntime.start('wallet-service', 0, null)
        def client = HttpClient.newHttpClient()

        expect:
        ['/health/live', '/health/ready', '/openapi.yaml'].every {
            client.send(HttpRequest.newBuilder(URI.create("http://localhost:${runtime.port()}" + it)).build(),
                HttpResponse.BodyHandlers.ofString()).statusCode() == 200
        }
        client.send(HttpRequest.newBuilder(URI.create("http://localhost:${runtime.port()}/v1/transfers")).build(),
            HttpResponse.BodyHandlers.ofString()).statusCode() == 404

        cleanup:
        runtime?.close()
    }

    def "service has no dependencies on another service or Spring"() {
        given:
        def classes = new ClassFileImporter().importPackages('com.arman.bank.walletservice')

        expect:
        noClasses().should().dependOnClassesThat().resideInAnyPackage(
            'org.springframework..', 'com.arman.bank.appgateway..', 'com.arman.bank.authservice..', 'com.arman.bank.userservice..', 'com.arman.bank.paymentservice..', 'com.arman.bank.ledgerservice..'
        ).check(classes)
    }
}
