package com.arman.bank.paymentservice

import com.arman.bank.runtime.*
import com.arman.bank.paymentservice.application.*
import com.arman.bank.paymentservice.infrastructure.*
import org.testcontainers.containers.PostgreSQLContainer
import spock.lang.*
import java.net.http.*
import java.time.*

/** Synthetic read fixtures establish pagination only, never evidence of ledger posting. */
class PaymentPaginationIntegrationSpec extends Specification {
    @Shared PostgreSQLContainer pg
    @Shared Database db
    @Shared ServiceRuntime runtime
    @Shared HttpClient client
    static final String KEY = 'synthetic-pagination-key-at-least-32-characters'
    UUID alice = UUID.randomUUID()
    UUID bob = UUID.randomUUID()
    UUID stranger = UUID.randomUUID()
    def setupSpec() {
        pg = new PostgreSQLContainer('postgres:17.6-alpine')
        pg.start()
        db = new Database(pg.jdbcUrl, pg.username, pg.password)
        def routes = new PaymentRoutes(new PaymentService(new PostgresPayments(db), Stub(PaymentPeers)), KEY)
        runtime = ServiceRuntime.start('payment-service', 0, db, routes::configure)
        client = HttpClient.newHttpClient()
    }
    def cleanupSpec() { client?.close(); runtime?.close(); pg?.stop() }
    def setup() { db.transaction { it.execute('truncate email_outbox, notifications, payments') } }
    def get(String path, UUID owner = alice, String key = KEY) {
        def request = HttpRequest.newBuilder(URI.create("http://localhost:${runtime.port()}" + path)).timeout(Duration.ofSeconds(10)).GET()
        if (owner != null) request.header('X-Identity-Id', owner.toString())
        if (key != null) request.header('X-Service-Key', key)
        client.send(request.build(), HttpResponse.BodyHandlers.ofString())
    }
    def json(response) { InternalHttp.JSON.readTree(response.body()) }
    def seed(int count, OffsetDateTime time, String status = 'COMPLETED') {
        db.transaction { sql ->
            (1..count).each {
                def id = UUID.randomUUID()
                sql.execute("""
                    insert into payments(id,requester_id,idempotency_key,request_hash,source_wallet_id,destination_wallet_id,
                      currency,amount_minor,status,recipient_id,recipient_phone,debit_account_id,credit_account_id,created_at)
                    values (?,?,?,?,?,?,'AED',125,?,?,'+971501234567',?,?,cast(? as timestamptz))
                    """, id, alice, id.toString(), 'a' * 64, UUID.randomUUID(), UUID.randomUUID(), status,
                    bob, UUID.randomUUID(), UUID.randomUUID(), time)
                if (status == 'COMPLETED') {
                    [alice, bob].each { owner ->
                        sql.execute("""
                            insert into notifications(id,owner_id,payment_id,type,currency,amount_minor,created_at)
                            values (?,?,?,'PAYMENT_RECEIVED','AED',125,cast(? as timestamptz))
                            """, UUID.randomUUID(), owner, id, time)
                    }
                }
            }
        }
    }

    def 'both resources traverse beyond one hundred with timestamp ties and intervening newer inserts'() {
        given:
        def time = OffsetDateTime.parse('2026-10-13T12:34:56.123456Z')
        seed(137, time)
        def expected = db.transaction { sql -> resource == 'payments'
            ? sql.fetch('select id from payments order by created_at desc, id desc').getValues(0, UUID)
            : sql.fetch('select id from notifications where owner_id = ? order by created_at desc, id desc', alice).getValues(0, UUID) }
        when:
        def first = get('/v1/' + resource + '/page?limit=17')
        def page = json(first)
        def ids = page.get(resource).collect { UUID.fromString(it.get('id').asText()) }
        seed(3, time.plusSeconds(1))
        int pages = 1
        while (!page.get('next_cursor').isNull()) {
            assert pages++ < 20
            def response = get('/v1/' + resource + '/page?limit=17&cursor=' + page.get('next_cursor').asText())
            assert response.statusCode() == 200
            page = json(response)
            ids.addAll(page.get(resource).collect { UUID.fromString(it.get('id').asText()) })
        }
        then:
        first.statusCode() == 200
        ids == expected
        ids.toSet().size() == 137
        json(get('/v1/' + resource + '/page')).get(resource).size() == 50
        json(get('/v1/' + resource + '/page?limit=100')).get(resource).size() == 100
        json(get('/v1/' + resource)).fieldNames().toList() == [resource]
        json(get('/v1/' + resource)).get(resource).size() == 100
        json(get('/v1/' + resource + '/page', stranger)).get(resource).size() == 0
        json(get('/v1/' + resource + '/page', stranger)).get('next_cursor').isNull()
        where:
        resource << ['payments', 'notifications']
    }

    def 'page validation authentication and cursor owner and resource isolation'() {
        given:
        seed(3, OffsetDateTime.now(ZoneOffset.UTC).withNano(123456000))
        def cursor = json(get('/v1/payments/page?limit=1')).get('next_cursor').asText()
        expect:
        get('/v1/payments/page', null).statusCode() == 401
        get('/v1/payments/page', alice, null).statusCode() == 401
        ['/v1/payments/page?cursor=' + cursor, '/v1/notifications/page?cursor=' + cursor].every { path ->
            def response = get(path, stranger)
            response.statusCode() == 400 && json(response).get('error').asText() == 'invalid_page_request'
        }
        get('/v1/notifications/page?cursor=' + cursor).statusCode() == 400
        ['limit=', 'limit=0', 'limit=101', 'limit=-1', 'limit=01', 'limit=1.0', 'limit=1&limit=2',
         'cursor=', 'cursor=x', 'cursor=' + 'A' * 513, 'cursor=' + cursor + '&cursor=' + cursor,
         'offset=1', 'limit=1&', 'limit=%31', 'cursor=%41', 'limit=1&unknown=x', 'x=' + 'a' * 1024].every { query ->
            def response = get('/v1/payments/page?' + query)
            response.statusCode() == 400 && json(response).get('error').asText() == 'invalid_page_request'
        }
    }

    def 'incoming pending stays hidden and notification reads do not reorder a page'() {
        given:
        def time = OffsetDateTime.parse('2026-10-13T12:34:56.123456Z')
        seed(3, time)
        seed(2, time.plusSeconds(1), 'PENDING')
        def first = json(get('/v1/notifications/page?limit=1'))
        def id = UUID.fromString(first.get('notifications').get(0).get('id').asText())
        def cursor = first.get('next_cursor').asText()
        def before = get('/v1/notifications/page?cursor=' + cursor).body()
        when:
        new PostgresPayments(db).readNotification(alice, id)
        then:
        get('/v1/notifications/page?cursor=' + cursor).body() == before
        json(get('/v1/payments/page', alice)).get('payments').size() == 5
        json(get('/v1/payments/page', bob)).get('payments').size() == 3
        json(get('/v1/payments/page?limit=100', bob)).get('next_cursor').isNull()
    }
}
