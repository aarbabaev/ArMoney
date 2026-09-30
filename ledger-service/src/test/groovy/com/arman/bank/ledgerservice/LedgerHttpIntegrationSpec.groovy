package com.arman.bank.ledgerservice
import com.arman.bank.runtime.*
import com.arman.bank.ledgerservice.application.*
import com.arman.bank.ledgerservice.infrastructure.*
import org.testcontainers.containers.PostgreSQLContainer
import spock.lang.Specification
import java.net.http.*
import java.time.Duration

class LedgerHttpIntegrationSpec extends Specification {
    def "private HTTP requires trusted identity validates integer money and scopes reads"() {
        given:
        def pg = new PostgreSQLContainer('postgres:17.6-alpine')
        pg.start()
        def db = new Database(pg.jdbcUrl, pg.username, pg.password)
        def key = 'ledger-test-service-key-at-least-32-chars'
        def service = new LedgerService(new PostgresLedger(db))
        def routes = new LedgerRoutes(service, key)
        def runtime = ServiceRuntime.start('ledger-service', 0, db, routes::configure)
        def client = HttpClient.newHttpClient()
        def alice = UUID.randomUUID()
        def bob = UUID.randomUUID()
        def call = { method, path, body, owner, credential ->
            def builder = HttpRequest.newBuilder(URI.create("http://localhost:${runtime.port()}" + path)).timeout(Duration.ofSeconds(10))
            if (credential != null) builder.header('X-Service-Key', credential)
            if (owner != null) builder.header('X-Identity-Id', owner.toString())
            if (body != null) builder.header('Content-Type', 'application/json')
            builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(InternalHttp.JSON.writeValueAsString(body)))
            client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
        }
        def body = [wallet_id: UUID.randomUUID().toString(), currency: 'EUR']
        expect:
        call('POST', '/v1/ledger/accounts', body, alice, null).statusCode() == 401
        call('POST', '/v1/ledger/accounts', body, null, key).statusCode() == 401
        when:
        def opened = call('POST', '/v1/ledger/accounts', body, alice, key)
        def id = InternalHttp.JSON.readTree(opened.body()).get('id').asText()
        def recipient = service.open(bob, UUID.randomUUID(), 'EUR')
        def command = [payment_id: UUID.randomUUID().toString(), debit_account_id: id, credit_account_id: recipient.id().toString(),
                       currency:'EUR', amount_minor: 10]
        then:
        opened.statusCode() == 200
        call('POST', '/v1/ledger/accounts', body, alice, key).body() == opened.body()
        call('GET', '/v1/ledger/accounts/' + id, null, bob, key).statusCode() == 404
        call('GET', '/v1/ledger/accounts/' + id, null, alice, key).statusCode() == 200
        call('POST', '/v1/ledger/transfers', command, alice, key).statusCode() == 409
        call('GET', '/v1/ledger/transfers/' + command.payment_id, null, alice, key).statusCode() == 200
        call('GET', '/v1/ledger/transfers/' + command.payment_id, null, bob, key).statusCode() == 404
        call('POST', '/v1/ledger/transfers', command + [amount_minor:11], alice, key).statusCode() == 409
        [0, -1, 1.5, '10', 9223372036854775808G].every {
            call('POST', '/v1/ledger/transfers', command + [amount_minor:it], alice, key).statusCode() == 400
        }
        call('POST', '/v1/ledger/transfers', command + [owner_id:bob.toString()], alice, key).statusCode() == 400
        call('POST', '/v1/ledger/accounts', body + [balance_minor:100], alice, key).statusCode() == 400
        cleanup:
        client?.close(); runtime?.close(); db?.close(); pg?.stop()
    }
}
