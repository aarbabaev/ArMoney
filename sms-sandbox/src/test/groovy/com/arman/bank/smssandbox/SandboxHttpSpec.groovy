package com.arman.bank.smssandbox

import com.fasterxml.jackson.databind.ObjectMapper
import spock.lang.Specification
import spock.lang.TempDir
import java.nio.file.Path
import java.net.http.*

class SandboxHttpSpec extends Specification {
    @TempDir Path directory
    String sender = UUID.randomUUID().toString()
    String reader = UUID.randomUUID().toString()
    Main service
    def json = new ObjectMapper()
    def client = HttpClient.newHttpClient()

    def setup() { service = new Main(directory, sender, reader, 0) }
    def cleanup() { service?.close() }
    def call(String method, String path, String token = null, String body = '', Map headers = [:]) {
        def builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:${service.port()}${path}"))
                .timeout(java.time.Duration.ofSeconds(10))
                .method(method, HttpRequest.BodyPublishers.ofString(body))
        if (token != null) builder.header('Authorization', "Bearer $token")
        headers.each { k,v -> builder.header(k.toString(), v.toString()) }
        client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
    }
    String payload(String text = 'Synthetic code 654321') {
        json.writeValueAsString([destination:'+971501234567', text:text, purpose:'login'])
    }

    def 'sender cannot read or clear and reader cannot send; anonymous receives no message contents'() {
        expect:
        call('GET', '/api/messages').statusCode() == 401
        call('POST', '/api/messages', reader, payload(), ['Idempotency-Key':'a']).statusCode() == 401
        call('POST', '/api/messages', sender, payload(), ['Idempotency-Key':'a']).statusCode() == 201
        call('GET', '/api/messages', sender).statusCode() == 401
        call('DELETE', '/api/messages', sender, '', ['X-Confirm-Clear':'true']).statusCode() == 401
        call('GET', '/api/messages', reader).body().contains('654321')
        !call('GET', '/').body().contains('654321')
        call('GET', '/api/messages', reader).headers().firstValue('Cache-Control').get() == 'no-store'
    }

    def 'HTTP stable IDs and contents survive process lifecycle and clear is explicit'() {
        given:
        def first = call('POST', '/api/messages', sender, payload(), ['Idempotency-Key':'durable'])
        def id = json.readTree(first.body()).get('id').asText()
        service.close()
        service = new Main(directory, sender, reader, 0)
        when:
        def replay = call('POST', '/api/messages', sender, payload(), ['Idempotency-Key':'durable'])
        then:
        replay.statusCode() == 200
        json.readTree(replay.body()).get('id').asText() == id
        !replay.body().contains('654321')
        call('POST', '/api/messages', sender, payload('different'), ['Idempotency-Key':'durable']).statusCode() == 409
        call('DELETE', '/api/messages', reader).statusCode() == 400
        call('GET', '/api/messages', reader).body().contains('654321')
        call('DELETE', '/api/messages', reader, '', ['X-Confirm-Clear':'true']).statusCode() == 204
        call('GET', '/api/messages', reader).body() == '[]'
        call('POST', '/api/messages', sender, payload(), ['Idempotency-Key':'durable']).statusCode() == 410
    }

    def 'malformed inputs fail without poisoning readiness and UI uses inert text rendering'() {
        given:
        String attack = '<img src=x onerror=alert(1)><script>alert(2)</script>'
        expect:
        call('POST', '/api/messages', sender, '{', ['Idempotency-Key':'bad']).statusCode() == 400
        call('POST', '/api/messages', sender, 'null', ['Idempotency-Key':'bad']).statusCode() == 400
        call('POST', '/api/messages', sender, payload('x' * 9000), ['Idempotency-Key':'large']).statusCode() == 413
        call('GET', '/health/ready').statusCode() == 200
        call('POST', '/api/messages', sender, payload(attack), ['Idempotency-Key':'xss']).statusCode() == 201
        json.readTree(call('GET', '/api/messages', reader).body()).get(0).get('text').asText() == attack
        call('GET', '/inbox.js').body().contains('line.textContent = value')
        !call('GET', '/inbox.js').body().contains('innerHTML')
        call('GET', '/').headers().firstValue('Content-Security-Policy').get().contains("script-src 'self'")
    }

    def 'unauthorized attempts consume a fixed bounded rate budget'() {
        when:
        def statuses = (1..121).collect { this.call('GET', '/api/messages').statusCode() }
        then:
        statuses.count { it == 401 } == 120
        statuses.last() == 429
        call('GET', '/health/ready').statusCode() == 200
    }

    def 'missing equal and weak credentials fail startup'() {
        when:
        new Main(directory.resolve('invalid'), a, b, 0)
        then:
        thrown(IllegalArgumentException)
        where:
        a              | b
        null           | null
        'weak'         | 'other'
        'a' * 32       | 'a' * 32
        'a' * 31 + ' ' | 'b' * 32
    }

    def 'isolated surrogate JSON cannot alias a question mark payload or cleared tombstone'() {
        given:
        def headers = ['Idempotency-Key':'unicode-alias']
        def first = call('POST', '/api/messages', sender, payload('?'), headers)
        def malformed = payload('?').replace('?', '\\' + surrogate)
        expect:
        first.statusCode() == 201
        call('POST', '/api/messages', sender, malformed, headers).statusCode() == 400
        json.readTree(call('POST', '/api/messages', sender, payload('?'), headers).body()).get('id') == json.readTree(first.body()).get('id')
        call('DELETE', '/api/messages', reader, '', ['X-Confirm-Clear':'true']).statusCode() == 204
        call('POST', '/api/messages', sender, malformed, headers).statusCode() == 400
        call('POST', '/api/messages', sender, payload('?'), headers).statusCode() == 410
        call('GET', '/api/messages', reader).body() == '[]'
        where:
        surrogate << ['uD800', 'uDC00']
    }

    def 'paired surrogate emoji retains exact retry and clear semantics after restart'() {
        given:
        String emoji = new String(Character.toChars(0x1F600))
        String escaped = payload('?').replace('?', '\\' + 'uD83D' + '\\' + 'uDE00')
        def headers = ['Idempotency-Key':'unicode-valid']
        def first = call('POST', '/api/messages', sender, escaped, headers)
        service.close()
        service = new Main(directory, sender, reader, 0)
        expect:
        first.statusCode() == 201
        json.readTree(call('POST', '/api/messages', sender, payload(emoji), headers).body()).get('id') == json.readTree(first.body()).get('id')
        json.readTree(call('GET', '/api/messages', reader).body()).get(0).get('text').asText() == emoji
        call('POST', '/api/messages', sender, payload('?'), headers).statusCode() == 409
        call('DELETE', '/api/messages', reader, '', ['X-Confirm-Clear':'true']).statusCode() == 204
        call('POST', '/api/messages', sender, escaped, headers).statusCode() == 410
        call('POST', '/api/messages', sender, payload('?'), headers).statusCode() == 409
    }

    def 'request rejects scalar coercion and trailing JSON before reserving the key'() {
        given:
        def headers = ['Idempotency-Key':'strict-json']
        expect:
        call('POST', '/api/messages', sender, body, headers).statusCode() == 400
        call('GET', '/api/messages', reader).body() == '[]'
        call('POST', '/api/messages', sender, payload(), headers).statusCode() == 201
        call('GET', '/health/ready').statusCode() == 200
        where:
        body << [
            '{"destination":"+971501234567","text":123,"purpose":"login"}',
            '{"destination":"+971501234567","text":1.5,"purpose":"login"}',
            '{"destination":"+971501234567","text":true,"purpose":"login"}',
            '{"destination":"+971501234567","text":"test","purpose":123}',
            '{"destination":"+971501234567","text":"test","purpose":"login"}{}',
            '{"destination":"+971501234567","text":"test","purpose":"login"}null'
        ]
    }
}
