package com.arman.bank.userservice
import com.arman.bank.userservice.application.*
import com.arman.bank.userservice.infrastructure.*
import com.arman.bank.runtime.*
import org.testcontainers.containers.PostgreSQLContainer
import spock.lang.Specification
import java.net.http.*
import java.time.Duration
import java.util.concurrent.*
class ProfileIntegrationSpec extends Specification {
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
        def routes = new ProfileRoutes(new ProfileService(new PostgresProfiles(db)), KEY)
        runtime = ServiceRuntime.start('user-service', 0, db, routes::configure)
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

    def "profile upsert preserves ID, persists after restart and isolates users"() {
        expect:
        req('GET', '/v1/users/me').statusCode() == 404
        when:
        def first = req('PUT', '/v1/users/me', alice, '{"display_name":" Alice "}')
        def second = req('PUT', '/v1/users/me', alice, '{"display_name":"Alice Updated"}')
        then:
        first.statusCode() == 200
        json(first).get('display_name').asText() == 'Alice'
        json(first).get('id') == json(second).get('id')
        json(req('GET', '/v1/users/me')).get('display_name').asText() == 'Alice Updated'
        req('GET', '/v1/users/me', bob).statusCode() == 404
        db.transaction { it.fetchCount(org.jooq.impl.DSL.table('profiles')) } == 1
        when:
        runtime.close()
        db = new Database(postgres.jdbcUrl, postgres.username, postgres.password)
        then:
        new ProfileService(new PostgresProfiles(db)).find(alice).get().displayName() == 'Alice Updated'
    }
    def "profile rejects missing service identity and invalid payload without writes"() {
        expect:
        req('PUT', '/v1/users/me', alice, '{"display_name":"Alice"}', null).statusCode() == 401
        req('GET', '/v1/users/me', null).statusCode() == 401
        req('GET', '/v1/users/me', alice, null, 'wrong').statusCode() == 401
        req('PUT', '/v1/users/me', alice, body).statusCode() == 400
        db.transaction { it.fetchCount(org.jooq.impl.DSL.table('profiles')) } == 0
        where:
        body << ['{}','{"display_name":"   "}','{"display_name":123}',
            '{"display_name":"Alice","identity_id":"forged"}',
            '{"display_name":"Alice","display_name":"Bob"}',
            '{"display_name":"Alice"}{}', '{"display_name":"' + ('x' * 101) + '"}']
    }
}
