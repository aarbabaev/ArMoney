package com.arman.bank.walletservice
import com.arman.bank.walletservice.domain.Wallet
import com.arman.bank.walletservice.infrastructure.HttpLedgerAccounts
import com.arman.bank.runtime.InternalHttp
import com.sun.net.httpserver.HttpServer
import spock.lang.Specification

class HttpLedgerAccountsSpec extends Specification {
    static final String KEY = 'integration-service-key-at-least-32-chars'
    HttpServer server
    HttpLedgerAccounts client
    def cleanup() { client?.close(); server?.stop(0) }
    def "malformed untrusted ledger responses fail closed"() {
        given:
        def wallet = new Wallet(UUID.randomUUID(), UUID.randomUUID(), 'AED', 'ACTIVE')
        def data = [id:UUID.randomUUID().toString(), wallet_id:wallet.id().toString(), owner_id:wallet.ownerId().toString(), currency:'AED']
        if (field != null) data[field] = bad
        def body = special ?: InternalHttp.JSON.writeValueAsString(data)
        server = HttpServer.create(new InetSocketAddress('127.0.0.1', 0), 0)
        server.createContext('/v1/ledger/accounts', { ex ->
            ex.responseHeaders.set('Content-Type', 'application/json')
            ex.responseHeaders.set('Location', '/redirect-target')
            byte[] bytes = body.getBytes(java.nio.charset.StandardCharsets.UTF_8)
            ex.sendResponseHeaders(status, bytes.length)
            ex.responseBody.write(bytes)
            ex.close()
        } as com.sun.net.httpserver.HttpHandler)
        server.start()
        client = new HttpLedgerAccounts("http://127.0.0.1:${server.address.port}", KEY)
        when:
        client.provision(wallet)
        then:
        thrown(Exception)
        where:
        field       | bad                    | special        | status
        'id'        | '1-1-1-1-1'            | null           | 200
        'wallet_id' | UUID.randomUUID().toString()   | null           | 200
        'owner_id'  | UUID.randomUUID().toString()   | null           | 200
        'currency'  | 'USD'                  | null           | 200
        'id'        | null                   | null           | 200
        null        | null                   | '{invalid'     | 200
        null        | null                   | 'x' * 4097     | 200
        null        | null                   | null           | 302
    }
    def "ledger destination must be a configured origin"() {
        when:
        new HttpLedgerAccounts(url, KEY)
        then:
        thrown(IllegalArgumentException)
        where:
        url << ['file:///tmp/ledger', 'http://user:pass@ledger', 'http://ledger/path', 'http://ledger?target=other', 'http://ledger#fragment']
    }
    def "balance accepts only matching account and exact nonnegative int64"() {
        given:
        def wallet = new Wallet(UUID.randomUUID(), UUID.randomUUID(), 'AED', 'ACTIVE', 'READY', UUID.randomUUID())
        def data = [id:wallet.ledgerAccountId().toString(), wallet_id:wallet.id().toString(), owner_id:wallet.ownerId().toString(), currency:'AED', balance_minor:123L]
        if (field != null) data[field] = bad
        server = HttpServer.create(new InetSocketAddress('127.0.0.1', 0), 0)
        server.createContext('/v1/ledger/accounts/' + wallet.ledgerAccountId(), { ex ->
            assert ex.requestMethod == 'GET'
            assert ex.requestHeaders.getFirst('X-Identity-Id') == wallet.ownerId().toString()
            assert ex.requestHeaders.getFirst('X-Service-Key') == KEY
            ex.responseHeaders.set('Content-Type', 'application/json')
            byte[] bytes = InternalHttp.JSON.writeValueAsBytes(data)
            ex.sendResponseHeaders(200, bytes.length); ex.responseBody.write(bytes); ex.close()
        } as com.sun.net.httpserver.HttpHandler)
        server.start()
        client = new HttpLedgerAccounts("http://127.0.0.1:${server.address.port}", KEY)
        when:
        def value
        def failed = false
        try { value = client.balance(wallet) } catch (Exception rejectedResponse) { failed = true }
        then:
        failed == rejected
        if (!rejected) assert value == expected
        where:
        field           | bad                                    | rejected | expected
        null            | null                                   | false    | 123L
        'balance_minor' | 0L                                     | false    | 0L
        'balance_minor' | Long.MAX_VALUE                         | false    | Long.MAX_VALUE
        'balance_minor' | -1L                                    | true     | null
        'balance_minor' | 1.5                                    | true     | null
        'balance_minor' | '123'                                  | true     | null
        'balance_minor' | new BigInteger('9223372036854775808')   | true     | null
        'balance_minor' | null                                   | true     | null
        'id'            | UUID.randomUUID().toString()            | true     | null
        'owner_id'      | UUID.randomUUID().toString()            | true     | null
        'wallet_id'     | UUID.randomUUID().toString()            | true     | null
        'currency'      | 'USD'                                  | true     | null
    }
}

