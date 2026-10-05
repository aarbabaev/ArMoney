package com.arman.bank.paymentservice

import com.arman.bank.runtime.*
import com.arman.bank.paymentservice.application.*
import com.arman.bank.paymentservice.infrastructure.*
import org.testcontainers.containers.PostgreSQLContainer
import spock.lang.Specification
import java.net.http.*
import java.time.Duration

class PaymentHttpIntegrationSpec extends Specification {
    def 'HTTP validates input and trusted headers isolates history and reads and preserves replay status'() {
        given:
        def pg = new PostgreSQLContainer('postgres:17.6-alpine')
        pg.start()
        def db = new Database(pg.jdbcUrl, pg.username, pg.password)
        def store = new PostgresPayments(db)
        def peers = Stub(PaymentPeers) { resolve(_, _) >> new PaymentPeers.Mapping(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()) }
        def key = 'test-payment-key-at-least-32-characters'
        def routes = new PaymentRoutes(new PaymentService(store, peers), key)
        def runtime = ServiceRuntime.start('payment-service', 0, db, routes::configure)
        def client = HttpClient.newHttpClient()
        def alice = UUID.randomUUID()
        def bob = UUID.randomUUID()
        def third = UUID.randomUUID()
        def call = { String method, String path, Object body, UUID owner = alice, String internal = key, String idempotency = 'test-key' ->
            def builder = HttpRequest.newBuilder(URI.create("http://localhost:${runtime.port()}" + path)).timeout(Duration.ofSeconds(10))
            if (owner != null) builder.header('X-Identity-Id', owner.toString())
            if (internal != null) builder.header('X-Service-Key', internal)
            if (idempotency != null) builder.header('Idempotency-Key', idempotency)
            if (body != null) builder.header('Content-Type', 'application/json')
            builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(InternalHttp.JSON.writeValueAsString(body)))
            client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
        }
        def payload = [source_wallet_id:UUID.randomUUID().toString(),recipient_id:bob.toString(),recipient_phone:'+15551234567',currency:'AED',amount_minor:125]
        expect:
        call('POST','/v1/payments',payload,alice,null).statusCode() == 401
        call('POST','/v1/payments',payload,null,key).statusCode() == 401
        call('POST','/v1/payments',payload,alice,key,null).statusCode() == 400
        call('POST','/v1/payments',payload,alice,key,'bad key').statusCode() == 400
        [0,-1,1.5,'125',9223372036854775808G].every { call('POST','/v1/payments',payload + [amount_minor:it]).statusCode() == 400 }
        ['USD','EUR','GBP','aed',null].every { call('POST','/v1/payments',payload + [currency:it]).statusCode() == 400 }
        call('POST','/v1/payments',payload + [requester_id:alice.toString()]).statusCode() == 400
        call('POST','/v1/payments',payload + [recipient_id:alice.toString()]).statusCode() == 400
        call('POST','/v1/payments',payload + [recipient_phone:'5551234567']).statusCode() == 400
        call('POST','/v1/payments',payload + [source_wallet_id:'1-1-1-1-1']).statusCode() == 400
        when:
        def accepted = call('POST','/v1/payments',payload)
        def id = InternalHttp.JSON.readTree(accepted.body()).get('id').asText()
        then:
        accepted.statusCode() == 202
        InternalHttp.JSON.readTree(accepted.body()).get('currency').asText() == 'AED'
        InternalHttp.JSON.readTree(accepted.body()).get('amount_minor').longValue() == 125L
        call('POST','/v1/payments',payload).statusCode() == 202
        call('POST','/v1/payments',payload + [amount_minor:126]).statusCode() == 409
        call('GET','/v1/payments/' + id,null,bob).statusCode() == 404
        InternalHttp.JSON.readTree(call('GET','/v1/payments',null,bob).body()).get('payments').size() == 0
        when:
        assert store.finish(store.claim().get(), PaymentPeers.Outcome.POSTED)
        def terminal = call('POST','/v1/payments',payload)
        def notifications = InternalHttp.JSON.readTree(call('GET','/v1/notifications',null,bob).body()).get('notifications')
        def notificationId = notifications.get(0).get('id').asText()
        def firstRead = call('POST','/v1/notifications/' + notificationId + '/read',null,bob)
        then:
        terminal.statusCode() == 200
        InternalHttp.JSON.readTree(terminal.body()).get('status').asText() == 'COMPLETED'
        call('GET','/v1/payments/' + id,null,bob).statusCode() == 200
        call('GET','/v1/payments/' + id,null,third).statusCode() == 404
        notifications.size() == 1
        notifications.get(0).get('currency').asText() == 'AED'
        notifications.get(0).get('amount_minor').longValue() == 125L
        call('POST','/v1/notifications/' + notificationId + '/read',null,alice).statusCode() == 404
        call('POST','/v1/notifications/' + notificationId + '/read',[:],bob).statusCode() == 400
        firstRead.statusCode() == 200
        call('POST','/v1/notifications/' + notificationId + '/read',null,bob).body() == firstRead.body()
        cleanup:
        client?.close()
        runtime?.close()
        pg?.stop()
    }
}
