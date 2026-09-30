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
        def wallet = new Wallet(UUID.randomUUID(), UUID.randomUUID(), 'EUR', 'ACTIVE')
        def data = [id:UUID.randomUUID().toString(), wallet_id:wallet.id().toString(), owner_id:wallet.ownerId().toString(), currency:'EUR']
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
}

