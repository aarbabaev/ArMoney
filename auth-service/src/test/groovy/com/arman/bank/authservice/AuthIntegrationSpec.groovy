package com.arman.bank.authservice

import com.arman.bank.authservice.application.*
import com.arman.bank.authservice.infrastructure.*
import com.arman.bank.runtime.*
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.testcontainers.containers.PostgreSQLContainer
import spock.lang.Specification
import java.net.http.*
import java.time.*
import java.util.concurrent.*

class AuthIntegrationSpec extends Specification {
    static final String KEY = 'integration-test-service-key-32-characters'
    static final String PASSWORD = 'a sufficiently long password'
    PostgreSQLContainer postgres
    Database database
    ServiceRuntime runtime
    AuthService service
    TestClock clock
    HttpClient client

    def setup() {
        postgres = new PostgreSQLContainer('postgres:17.6-alpine')
        postgres.start()
        database = new Database(postgres.jdbcUrl, postgres.username, postgres.password)
        clock = new TestClock(Instant.parse('2026-01-01T00:00:00Z'))
        service = new AuthService(new PostgresAuthStore(database), new Argon2Passwords(), clock)
        def routes = new AuthRoutes(service, KEY)
        runtime = ServiceRuntime.start('auth-service', 0, database, routes::configure)
        client = HttpClient.newHttpClient()
    }

    def cleanup() {
        client?.close()
        runtime?.close()
        database?.close()
        postgres?.stop()
    }

    def request(String method, String path, String body = null, String token = null, String key = KEY) {
        def builder = HttpRequest.newBuilder(URI.create("http://localhost:${runtime.port()}" + path))
            .timeout(Duration.ofSeconds(10))
        if (key != null) builder.header('X-Service-Key', key)
        if (body != null) builder.header('Content-Type', 'application/json')
        if (token != null) builder.header('Authorization', 'Bearer ' + token)
        builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body))
        client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
    }

    def body(String email, String password = PASSWORD) { JsonOutput.toJson([email: email, password: password]) }
    def parsed(response) { new JsonSlurper().parseText(response.body()) }

    def "register login session restart expiry and logout preserve identity and hide secrets"() {
        when:
        def registration = request('POST', '/v1/auth/register', body(' Alice@Example.com '))
        def duplicate = request('POST', '/v1/auth/register', body('alice@example.com', 'different long password'))
        def wrong = request('POST', '/v1/auth/login', body('alice@example.com', 'different long password'))
        def unknown = request('POST', '/v1/auth/login', body('unknown@example.com'))
        def login = request('POST', '/v1/auth/login', body('alice@example.com'))
        def token = parsed(login).access_token as String
        def me = request('GET', '/v1/auth/me', null, token)

        then:
        registration.statusCode() == 202
        duplicate.statusCode() == 202
        duplicate.body() == registration.body()
        wrong.statusCode() == 401
        wrong.body() == unknown.body()
        login.statusCode() == 200
        login.headers().firstValue('Cache-Control').orElse('') == 'no-store'
        token.size() == 43
        me.statusCode() == 200
        parsed(me).email == 'alice@example.com'
        !me.body().contains('password')
        database.transaction { sql -> sql.fetchOne('select password_hash from identities').get(0, String) }.startsWith('$argon2id$')
        database.transaction { sql -> sql.fetchOne('select token_hash from sessions').get(0, String) } == AuthService.digest(token)
        new AuthService(new PostgresAuthStore(database), new Argon2Passwords(), clock).me('Bearer ' + token).email() == 'alice@example.com'
        request('GET', '/v1/auth/me').statusCode() == 401
        request('GET', '/v1/auth/me', null, 'x' * 43).statusCode() == 401

        when:
        def logout = request('POST', '/v1/auth/logout', null, token)

        then:
        logout.statusCode() == 204
        request('GET', '/v1/auth/me', null, token).statusCode() == 401
        request('POST', '/v1/auth/logout', null, token).statusCode() == 204

        when:
        def secondToken = parsed(request('POST', '/v1/auth/login', body('alice@example.com'))).access_token as String
        clock.current = clock.current.plusSeconds(1800)

        then:
        request('GET', '/v1/auth/me', null, secondToken).statusCode() == 401
    }

    def "invalid input and missing service identity fail closed"() {
        expect:
        request('POST', '/v1/auth/register', body('a@example.com'), null, null).statusCode() == 401
        request('POST', '/v1/auth/register', body('a@example.com'), null, 'wrong').statusCode() == 401
        request('POST', '/v1/auth/register', '{').statusCode() == 400
        request('POST', '/v1/auth/register', body('not-email')).statusCode() == 400
        request('POST', '/v1/auth/register', body('a@example.com', 'short')).statusCode() == 400
        request('POST', '/v1/auth/register', body('a@example.com', 'x' * 129)).statusCode() == 400
        request('POST', '/v1/auth/register', body('a@example.com').replace('}', ',"admin":true}')).statusCode() == 400
        request('POST', '/v1/auth/register', body('a@example.com') + '{}').statusCode() == 400
        request('POST', '/v1/auth/register', body('a@example.com', 'x' * 5000)).statusCode() == 413
        database.transaction { sql -> sql.fetchCount(org.jooq.impl.DSL.table('identities')) } == 0
    }

    def "attempt limits are atomic across concurrent requests and expire"() {
        given:
        def store = new PostgresAuthStore(database)
        def executor = Executors.newFixedThreadPool(8)
        def key = AuthService.digest('limited@example.com')

        when:
        def results = (1..20).collect {
            executor.submit({ store.allowAttempt(key, clock.instant()) } as Callable<Boolean>)
        }.collect { it.get(15, TimeUnit.SECONDS) }

        then:
        results.count { it } == 10
        !store.allowAttempt(key, clock.instant())

        when:
        clock.current = clock.current.plusSeconds(900)

        then:
        store.allowAttempt(key, clock.instant())

        cleanup:
        executor?.shutdownNow()
    }

    static class TestClock extends Clock {
        Instant current
        TestClock(Instant current) { this.current = current }
        ZoneId getZone() { ZoneOffset.UTC }
        Clock withZone(ZoneId zone) { this }
        Instant instant() { current }
    }
}
