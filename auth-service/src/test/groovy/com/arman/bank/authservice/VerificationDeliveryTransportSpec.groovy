package com.arman.bank.authservice.infrastructure

import com.arman.bank.authservice.application.NotificationDelivery.Result
import com.arman.bank.authservice.domain.Verification.Purpose
import com.arman.bank.authservice.infrastructure.PostgresVerificationStore.DeliveryLease
import com.sun.net.httpserver.HttpServer
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import java.time.Instant
import java.util.concurrent.*
import spock.lang.Specification

/** Loopback synthetic HTTP only. MailSender seam proves routing, not SMTP STARTTLS. */
class VerificationDeliveryTransportSpec extends Specification {
    static final String TOKEN = 'synthetic_sms_sender_token_32_characters'
    def mail = Mock(VerificationDeliveryTransport.MailSender)
    HttpServer server
    def executor = Executors.newFixedThreadPool(2)
    def requests = new CopyOnWriteArrayList<Map>()
    def transport
    int status = 201
    String response = ack(false)
    String type = 'application/json'
    boolean stall
    def release = new CountDownLatch(1)
    static String ack(boolean replay) { JsonOutput.toJson([id: '11111111-1111-4111-8111-111111111111', createdAt: 1792065600000L, replay: replay]) }
    def setup() {
        server = HttpServer.create(new InetSocketAddress('127.0.0.1', 0), 0)
        server.executor = executor
        server.createContext('/') { exchange ->
            try {
                requests.add([path: exchange.requestURI.toString(), method: exchange.requestMethod,
                    key: exchange.requestHeaders.getFirst('Idempotency-Key'), authorization: exchange.requestHeaders.getFirst('Authorization'),
                    body: new String(exchange.requestBody.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)])
                exchange.responseHeaders.set('Content-Type', type)
                if (status == 302) exchange.responseHeaders.set('Location', "http://127.0.0.1:${server.address.port}/redirect-target")
                byte[] body = response.getBytes(java.nio.charset.StandardCharsets.UTF_8)
                exchange.sendResponseHeaders(status, body.length)
                if (stall) release.await(6, TimeUnit.SECONDS)
                exchange.responseBody.write(body)
            } catch (IOException ignored) { /* timeout closes the synthetic socket */ }
            finally { exchange.close() }
        }
        server.start()
        transport = new VerificationDeliveryTransport(mail, URI.create("http://127.0.0.1:${server.address.port}"), TOKEN, true)
    }
    def cleanup() {
        release.countDown(); transport.close(); server.stop(0); executor.shutdownNow()
        assert executor.awaitTermination(5, TimeUnit.SECONDS)
    }
    static DeliveryLease lease(Purpose purpose = Purpose.REGISTRATION_PHONE) {
        new DeliveryLease(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), purpose, 1,
            Instant.now().plusSeconds(60), new VerificationCrypto.Encrypted('test', new byte[12], new byte[20]))
    }
    def 'SMS initial and replay acknowledgements preserve stable delivery key and identical content'() {
        given:
        def delivery = lease()
        def payload = new VerificationCrypto.DeliveryPayload('+971501234567', '123456')
        expect:
        transport.send(delivery, payload)
        when:
        status = 200; response = ack(true)
        then:
        transport.send(delivery, payload)
        requests.size() == 2
        requests*.key == [delivery.deliveryId().toString()] * 2
        requests*.path == ['/api/messages'] * 2
        requests*.method == ['POST'] * 2
        requests*.authorization == ['Bearer ' + TOKEN] * 2
        requests[0].body == requests[1].body
        def body = new JsonSlurper().parseText(requests[0].body)
        body.destination == '+971501234567' && body.purpose == 'REGISTRATION_PHONE' && body.text.contains('123456')
        0 * mail._
    }
    def 'HTTP rejection redirects and invalid or oversized acknowledgements never count as delivery'() {
        given:
        status = code; response = body; type = contentType
        expect:
        !transport.send(lease(), new VerificationCrypto.DeliveryPayload('+971501234567', '123456'))
        requests.size() == 1
        where:
        code | body                                                              | contentType
        409  | '{}'                                                              | 'application/json'
        410  | '{}'                                                              | 'application/json'
        429  | '{}'                                                              | 'application/json'
        507  | '{}'                                                              | 'application/json'
        503  | '{}'                                                              | 'application/json'
        302  | '{}'                                                              | 'application/json'
        204  | ''                                                                | 'application/json'
        201  | '{}'                                                              | 'application/json'
        201  | ack(true)                                                         | 'application/json'
        200  | ack(false)                                                        | 'application/json'
        201  | ack(false) + ' {}'                                                 | 'application/json'
        201  | ack(false).replace('11111111-1111-4111-8111-111111111111', '1-1-1-1-1') | 'application/json'
        201  | ack(false).replace('false', '"false"')                            | 'application/json'
        201  | ack(false).replace('1792065600000', '-1')                          | 'application/json'
        201  | ack(false).replace('"replay":false', '"replay":false,"replay":false') | 'application/json'
        201  | 'x' * 4097                                                        | 'application/json'
        201  | ack(false)                                                        | 'text/plain'
    }
    def 'total deadline includes stalled response body'() {
        given:
        stall = true
        when:
        long started = System.nanoTime()
        def accepted = transport.send(lease(), new VerificationCrypto.DeliveryPayload('+971501234567', '123456'))
        then:
        !accepted
        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 5500
    }
    def 'email uses existing SMTP sender and only accepted response acknowledges intent'() {
        given:
        def delivery = lease(Purpose.REGISTRATION_EMAIL)
        when:
        boolean accepted = transport.send(delivery, new VerificationCrypto.DeliveryPayload('synthetic@example.test', '123456'))
        then:
        1 * mail.send('synthetic@example.test', 'ArMoney registration verification', { it.contains('123456') }) >> outcome
        accepted == expected
        requests.empty
        where:
        outcome                  | expected
        Result.ACCEPTED          | true
        Result.TRANSIENT_FAILURE | false
        Result.PERMANENT_FAILURE | false
    }
    def 'invalid destinations or closed transport cannot send'() {
        expect:
        !transport.send(lease(), new VerificationCrypto.DeliveryPayload('https://public.example', '123456'))
        !transport.send(lease(Purpose.REGISTRATION_EMAIL), new VerificationCrypto.DeliveryPayload('bad\r\nrecipient', '123456'))
        when:
        transport.close()
        then:
        !transport.send(lease(), new VerificationCrypto.DeliveryPayload('+971501234567', '123456'))
        requests.empty
        0 * mail._
    }
}
