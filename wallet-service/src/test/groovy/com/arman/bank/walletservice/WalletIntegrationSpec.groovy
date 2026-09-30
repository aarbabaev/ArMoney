package com.arman.bank.walletservice
import com.arman.bank.walletservice.application.*
import com.arman.bank.walletservice.infrastructure.*
import com.arman.bank.runtime.*
import org.testcontainers.containers.PostgreSQLContainer
import spock.lang.Specification
import java.net.http.*
import java.time.Duration
import java.util.concurrent.*
class WalletIntegrationSpec extends Specification {
    static final String KEY = 'integration-service-key-at-least-32-chars'
    PostgreSQLContainer postgres
    Database db
    ServiceRuntime runtime
    HttpClient client
    UUID alice = UUID.randomUUID()
    UUID bob = UUID.randomUUID()
    def setup() {
        postgres = new PostgreSQLContainer('postgres:17.6-alpine')
        postgres.start()
        db = new Database(postgres.jdbcUrl, postgres.username, postgres.password)
        def routes = new WalletRoutes(new WalletService(new PostgresWallets(db)), KEY)
        runtime = ServiceRuntime.start('wallet-service', 0, db, routes::configure)
        client = HttpClient.newHttpClient()
    }
    def cleanup() { client?.close(); runtime?.close(); db?.close(); postgres?.stop() }
    def req(String method, String path, UUID owner = alice, String body = null, String key = KEY) {
        def b = HttpRequest.newBuilder(URI.create("http://localhost:${runtime.port()}" + path)).timeout(Duration.ofSeconds(10))
        if (key != null) b.header('X-Service-Key', key)
        if (owner != null) b.header('X-Identity-Id', owner.toString())
        if (body != null) b.header('Content-Type', 'application/json')
        b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body))
        client.send(b.build(), HttpResponse.BodyHandlers.ofString())
    }
    def json(response) { InternalHttp.JSON.readTree(response.body()) }

    def "wallet retries and concurrent creation produce one stable wallet per owner and currency"() {
        given:
        def service = new WalletService(new PostgresWallets(db))
        def executor = Executors.newFixedThreadPool(8)
        when:
        def wallets = (1..16).collect {
            executor.submit({ service.open(alice, 'EUR') } as Callable)
        }.collect { it.get(15, TimeUnit.SECONDS) }
        def response = req('POST', '/v1/wallets', alice, '{"currency":"EUR"}')
        then:
        wallets*.id().unique().size() == 1
        response.statusCode() == 202
        json(response).get('provisioning_status').asText() == 'PENDING'
        json(response).get('ledger_account_id').isNull()
        json(response).get('id').asText() == wallets.first().id().toString()
        json(req('GET', '/v1/wallets')).get('wallets').size() == 1
        json(req('GET', '/v1/wallets', bob)).get('wallets').size() == 0
        req('POST', '/v1/wallets', bob, '{"currency":"EUR"}').statusCode() == 202
        req('POST', '/v1/wallets', alice, '{"currency":"USD"}').statusCode() == 202
        service.list(alice)*.currency() == ['EUR', 'USD']
        db.transaction { it.fetchCount(org.jooq.impl.DSL.table('wallets')) } == 3
        when:
        runtime.close()
        db = new Database(postgres.jdbcUrl, postgres.username, postgres.password)
        then:
        new WalletService(new PostgresWallets(db)).list(alice).size() == 2
        cleanup:
        executor?.shutdownNow()
    }
    def "retries do not reopen a closed wallet"() {
        given:
        def service = new WalletService(new PostgresWallets(db))
        def wallet = service.open(alice, 'EUR')
        db.transaction { it.execute("update wallets set status = 'CLOSED' where id = ?", wallet.id()) }
        expect:
        service.open(alice, 'EUR').status() == 'CLOSED'
        service.open(alice, 'EUR').id() == wallet.id()
        req('POST', '/v1/wallets', alice, '{"currency":"EUR"}').statusCode() == 200
    }
    def "confirmed wallet returns 200 and stable account mapping without exposing a balance"() {
        given:
        def store = new PostgresWallets(db)
        def wallet = new WalletService(store).open(alice, 'EUR')
        def account = UUID.randomUUID()
        store.complete(store.claim().orElseThrow(), account)
        when:
        def response = req('POST', '/v1/wallets', alice, '{"currency":"EUR"}')
        then:
        response.statusCode() == 200
        json(response).get('id').asText() == wallet.id().toString()
        json(response).get('provisioning_status').asText() == 'READY'
        json(response).get('ledger_account_id').asText() == account.toString()
        !json(response).has('balance_minor')
        req('GET', '/health/ready', null, null, null).statusCode() == 200
    }
    def "wallet rejects untrusted owners and invalid currency without writes"() {
        expect:
        req('POST', '/v1/wallets', alice, '{"currency":"EUR"}', null).statusCode() == 401
        req('GET', '/v1/wallets', null).statusCode() == 401
        req('GET', '/v1/wallets', alice, null, 'wrong').statusCode() == 401
        req('POST', '/v1/wallets', alice, body).statusCode() == 400
        db.transaction { it.fetchCount(org.jooq.impl.DSL.table('wallets')) } == 0
        where:
        body << ['{}','{"currency":"eur"}','{"currency":"XYZ"}','{"currency":null}',
            '{"currency":"EUR","owner_id":"forged"}','{"currency":"EUR","currency":"USD"}']
    }
}
