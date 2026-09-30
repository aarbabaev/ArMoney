package com.arman.bank.walletservice
import com.arman.bank.runtime.*
import com.arman.bank.walletservice.application.*
import com.arman.bank.walletservice.infrastructure.*
import com.sun.net.httpserver.HttpServer
import org.testcontainers.containers.PostgreSQLContainer
import org.flywaydb.core.Flyway
import spock.lang.*
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger

class ProvisioningIntegrationSpec extends Specification {
    static final String KEY = 'integration-service-key-at-least-32-chars'
    @Shared PostgreSQLContainer postgres
    Database db
    PostgresWallets store
    WalletService service
    HttpServer server
    HttpLedgerAccounts client
    WalletProvisioner worker
    UUID identity = UUID.randomUUID()
    def setupSpec() { postgres = new PostgreSQLContainer('postgres:17.6-alpine'); postgres.start() }
    def cleanupSpec() { postgres?.stop() }
    def setup() {
        db = new Database(postgres.jdbcUrl, postgres.username, postgres.password)
        db.transaction { it.execute('truncate wallets') }
        store = new PostgresWallets(db)
        service = new WalletService(store)
    }
    def cleanup() { worker?.close(); client?.close(); server?.stop(0); db?.close() }
    def due() { db.transaction { it.execute("update wallets set next_provisioning_at = current_timestamp - interval '1 second' where provisioning_status = 'PENDING'") } }
    def startLedger(Closure handler) {
        server = HttpServer.create(new InetSocketAddress('127.0.0.1', 0), 0)
        server.createContext('/v1/ledger/accounts', { exchange ->
            try { handler(exchange) } finally { exchange.close() }
        } as com.sun.net.httpserver.HttpHandler)
        server.start()
        client = new HttpLedgerAccounts("http://127.0.0.1:${server.address.port}", KEY)
        worker = new WalletProvisioner(store, client)
    }
    static void reply(exchange, int status, Map body) {
        byte[] bytes = InternalHttp.JSON.writeValueAsBytes(body)
        exchange.responseHeaders.set('Content-Type', 'application/json')
        exchange.sendResponseHeaders(status, bytes.length)
        exchange.responseBody.write(bytes)
    }
    def "outage and lost response recover the committed ledger account using the original wallet identity"() {
        given:
        def wallet = service.open(identity, 'EUR')
        def accountId = UUID.randomUUID()
        def calls = new AtomicInteger()
        def seen = new CopyOnWriteArrayList()
        def committed = new ConcurrentHashMap()
        startLedger { ex ->
            def body = InternalHttp.JSON.readTree(ex.requestBody)
            seen.add([body.get('wallet_id').asText(), body.get('currency').asText(), ex.requestHeaders.getFirst('X-Identity-Id'), ex.requestHeaders.getFirst('X-Service-Key')])
            int call = calls.incrementAndGet()
            if (call == 1) { reply(ex, 503, [error:'unavailable']); return }
            committed.putIfAbsent(body.get('wallet_id').asText(), accountId)
            if (call == 2) { ex.close(); return } // Commit succeeded, response was lost.
            reply(ex, 200, [id:committed.get(body.get('wallet_id').asText()).toString(), wallet_id:wallet.id().toString(), owner_id:identity.toString(), currency:'EUR', balance_minor:123])
        }
        when:
        worker.runOnce()
        then:
        service.list(identity).first().provisioningStatus() == 'PENDING'
        when:
        due()
        worker.runOnce()
        then:
        service.list(identity).first().provisioningStatus() == 'PENDING'
        committed.size() == 1
        when:
        worker.close()
        db.close()
        db = new Database(postgres.jdbcUrl, postgres.username, postgres.password)
        store = new PostgresWallets(db)
        service = new WalletService(store)
        worker = new WalletProvisioner(store, client)
        due()
        worker.runOnce()
        then:
        service.open(identity, 'EUR').ledgerAccountId() == accountId
        service.open(identity, 'EUR').provisioningStatus() == 'READY'
        committed.size() == 1
        seen.every { it == [wallet.id().toString(), 'EUR', identity.toString(), KEY] }
    }
    def "claims are exclusive and expired claims recover while stale completion is fenced"() {
        given:
        def wallet = service.open(identity, 'EUR')
        def pool = Executors.newFixedThreadPool(8)
        when:
        def results = (1..16).collect { pool.submit({ store.claim() } as Callable) }.collect { it.get(10, TimeUnit.SECONDS) }
        then:
        results.count { it.present } == 1
        when:
        def first = results.find { it.present }.get()
        due()
        def recovered = new PostgresWallets(db).claim().orElseThrow()
        then:
        recovered.wallet().id() == wallet.id()
        recovered.token() != first.token()
        !store.complete(first, UUID.randomUUID())
        when:
        store.retry(first, 1)
        def account = UUID.randomUUID()
        then:
        store.complete(recovered, account)
        service.list(identity).first().ledgerAccountId() == account
        store.claim().empty
        cleanup:
        pool?.shutdownNow()
    }
    def "perpetually failing wallet does not starve later due wallets and closed wallets are skipped"() {
        given:
        def failed = service.open(identity, 'EUR')
        def closed = service.open(identity, 'USD')
        db.transaction { it.execute("update wallets set status = 'CLOSED' where id = ?", closed.id()) }
        def others = (1..12).collect { service.open(UUID.randomUUID(), 'EUR') }
        def visited = new ArrayList<UUID>()
        LedgerAccounts ledger = { wallet ->
            visited.add(wallet.id())
            if (wallet.id() == failed.id()) throw new IOException('outage')
            UUID.randomUUID()
        } as LedgerAccounts
        worker = new WalletProvisioner(store, ledger)
        when:
        def first = worker.runOnce()
        def second = worker.runOnce()
        then:
        first == 10
        second == 3
        visited.count { it == failed.id() } == 1
        !visited.contains(closed.id())
        others.every { w -> store.list(w.ownerId()).first().provisioningStatus() == 'READY' }
        service.open(identity, 'USD').status() == 'CLOSED'
        service.open(identity, 'USD').provisioningStatus() == 'PENDING'
    }
    def "mapping mismatch never marks the wallet ready and same wallet can recover"() {
        given:
        def wallet = service.open(identity, 'EUR')
        def wrong = new AtomicInteger(1)
        def account = UUID.randomUUID()
        startLedger { ex ->
            reply(ex, 200, [id:account.toString(), wallet_id:wallet.id().toString(), owner_id:wrong.get() == 1 ? UUID.randomUUID().toString() : identity.toString(), currency:'EUR'])
        }
        when:
        worker.runOnce()
        then:
        service.list(identity).first().ledgerAccountId() == null
        when:
        wrong.set(0)
        due()
        worker.runOnce()
        then:
        service.list(identity).first().ledgerAccountId() == account
    }
    def "database enforces immutable mapping identity and unique account mapping"() {
        given:
        def a = service.open(identity, 'EUR')
        def b = service.open(identity, 'USD')
        def account = UUID.randomUUID()
        def first = store.claim().orElseThrow()
        store.complete(first, account)
        def second = store.claim().orElseThrow()
        when:
        store.complete(second, account)
        then:
        thrown(Exception)
        when:
        db.transaction { it.execute("update wallets set ledger_account_id = ? where id = ?", UUID.randomUUID(), first.wallet().id()) }
        then:
        thrown(Exception)
        when:
        db.transaction { it.execute("update wallets set owner_id = ? where id = ?", UUID.randomUUID(), a.id()) }
        then:
        thrown(Exception)
        when:
        db.transaction { it.execute("update wallets set status = 'CLOSED' where id = ?", b.id()) }
        db.transaction { it.execute("update wallets set status = 'ACTIVE' where id = ?", b.id()) }
        then:
        thrown(Exception)
    }
    def "V3 upgrades populated V2 active and closed wallets without losing identity"() {
        given:
        def legacy = new PostgreSQLContainer('postgres:17.6-alpine')
        legacy.start()
        Flyway.configure().dataSource(legacy.jdbcUrl, legacy.username, legacy.password).locations('classpath:db/migration').target('2').load().migrate()
        def active = UUID.randomUUID()
        def closed = UUID.randomUUID()
        def conn = java.sql.DriverManager.getConnection(legacy.jdbcUrl, legacy.username, legacy.password)
        def insert = conn.prepareStatement('insert into wallets(id, owner_id, currency, status) values (?, ?, ?, ?)')
        [[active,'EUR','ACTIVE'],[closed,'USD','CLOSED']].each { values ->
            insert.setObject(1, values[0]); insert.setObject(2, identity); insert.setString(3, values[1]); insert.setString(4, values[2]); insert.executeUpdate()
        }
        insert.close(); conn.close()
        when:
        def upgraded = new Database(legacy.jdbcUrl, legacy.username, legacy.password)
        def upgradedStore = new PostgresWallets(upgraded)
        then:
        upgradedStore.list(identity)*.id() == [active, closed]
        upgradedStore.list(identity)*.provisioningStatus() == ['PENDING','PENDING']
        upgradedStore.list(identity)*.ledgerAccountId() == [null,null]
        upgradedStore.claim().orElseThrow().wallet().id() == active
        upgradedStore.claim().empty
        cleanup:
        upgraded?.close()
        legacy?.stop()
    }
}
