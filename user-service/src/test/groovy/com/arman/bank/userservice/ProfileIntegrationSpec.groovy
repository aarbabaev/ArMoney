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
    def "phone remains pending until operator verification and change revokes lookup"() {
        given:
        def store = new PostgresProfiles(db)
        req('PUT', '/v1/users/me', alice, '{"display_name":"Alice"}')
        expect:
        json(req('GET', '/v1/users/me')).get('phone_number').isNull()
        req('PUT', '/v1/users/me/phone', bob, '{"phone_number":"+15550000001"}').statusCode() == 404
        req('PUT', '/v1/users/me/phone', alice, '{"phone_number":"5550000001"}').statusCode() == 400
        req('PUT', '/v1/users/me/phone', alice, '{"phone_number":"+15550000001","phone_verified":true}').statusCode() == 400
        req('PUT', '/v1/users/me/phone', alice, '{"phone_number":"+15550000001"}').statusCode() == 200
        req('POST', '/v1/users/resolve-phone', bob, '{"phone_number":"+15550000001"}').statusCode() == 404
        when:
        store.verifyPendingPhone(alice, '+15550000001', 'test-operator', 'case-001')
        def resolved = req('POST', '/v1/users/resolve-phone', bob, '{"phone_number":"+15550000001"}')
        then:
        resolved.statusCode() == 200
        json(resolved).fieldNames().toList().sort() == ['display_name','identity_id','phone_number']
        json(resolved).get('identity_id').asText() == alice.toString()
        json(req('PUT', '/v1/users/me/phone', alice, '{"phone_number":"+15550000001"}')).get('phone_verified').asBoolean()
        json(req('PUT', '/v1/users/me', alice, '{"display_name":"Renamed"}')).get('phone_verified').asBoolean()
        db.transaction { it.fetchCount(org.jooq.impl.DSL.table('phone_verification_audit')) } == 1
        when:
        def updated = req('PUT', '/v1/users/me/phone', alice, '{"phone_number":"+15550000002"}')
        then:
        !json(updated).get('phone_verified').asBoolean()
        req('POST', '/v1/users/resolve-phone', bob, '{"phone_number":"+15550000001"}').statusCode() == 404
        when:
        store.verifyPendingPhone(alice, '+15550000001', 'test-operator', 'stale')
        then:
        thrown(IllegalStateException)
        !store.find(alice).get().phoneVerified()
        db.transaction { it.fetchCount(org.jooq.impl.DSL.table('phone_verification_audit')) } == 1
    }
    def "concurrent verification cannot assign one phone to two identities and losing audit rolls back"() {
        given:
        def store = new PostgresProfiles(db)
        def service = new ProfileService(store)
        [alice,bob].each { service.save(it, 'Synthetic'); service.changePhone(it, '+15550000003') }
        def pool = Executors.newFixedThreadPool(2)
        when:
        def results = [alice,bob].collect { owner -> pool.submit({
            try { store.verifyPendingPhone(owner, '+15550000003', 'test-operator', 'unique-case'); return true }
            catch (org.jooq.exception.DataAccessException expected) { return false }
        } as Callable) }.collect { it.get(10, TimeUnit.SECONDS) }
        then:
        results.count(true) == 1
        db.transaction { it.fetchCount(org.jooq.impl.DSL.table('phone_verification_audit')) } == 1
        [alice,bob].count { store.find(it).get().phoneVerified() } == 1
        when:
        def winner = [alice,bob].find { store.find(it).get().phoneVerified() }
        store.verifyPendingPhone(winner, '+15550000003', 'test-operator', 'duplicate')
        then:
        thrown(IllegalStateException)
        cleanup:
        pool?.shutdownNow()
    }
    def "lookup limiter counts concurrent misses persists across service recreation and expires"() {
        given:
        def pool = Executors.newFixedThreadPool(8)
        when:
        def statuses = (1..40).collect { pool.submit({ req('POST', '/v1/users/resolve-phone', alice, '{"phone_number":"+15559999999"}').statusCode() } as Callable) }
            .collect { it.get(15, TimeUnit.SECONDS) }
        then:
        statuses.count(404) == 30
        statuses.count(429) == 10
        req('POST', '/v1/users/resolve-phone', bob, '{"phone_number":"+15559999999"}').statusCode() == 404
        when:
        new ProfileService(new PostgresProfiles(db)).resolvePhone(alice, '+15559999999')
        then:
        thrown(LookupLimitExceeded)
        when:
        db.transaction { it.execute("update phone_lookup_limits set window_start = current_timestamp - interval '61 seconds' where requester_id = ?", alice) }
        then:
        req('POST', '/v1/users/resolve-phone', alice, '{"phone_number":"+15559999999"}').statusCode() == 404
        cleanup:
        pool?.shutdownNow()
    }
}
