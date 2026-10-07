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
    def req(String method, String path, UUID owner = alice, String body = null, String key = KEY, String email = null) {
        def b = HttpRequest.newBuilder(URI.create("http://localhost:${runtime.port()}" + path)).timeout(Duration.ofSeconds(10))
        if (key != null) b.header('X-Service-Key', key)
        if (owner != null) b.header('X-Identity-Id', owner.toString())
        if (email != null) b.header('X-Identity-Email', email)
        if (body != null) b.header('Content-Type', 'application/json')
        b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body))
        client.send(b.build(), HttpResponse.BodyHandlers.ofString())
    }
    def json(response) { InternalHttp.JSON.readTree(response.body()) }

    def "trusted email validation rejects malformed header before reserving profile"() {
        expect:
        req('PUT', '/v1/users/me', alice, '{"display_name":"Synthetic"}', KEY, 'invalid-email').statusCode() == 400
        db.transaction { it.fetchCount(org.jooq.impl.DSL.table('profile_directory')) } == 0
        req('PUT', '/v1/users/me', alice, '{"display_name":"Synthetic"}', KEY, '.a@example.123').statusCode() == 200
    }

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
    def "registration is durable immutable and discoverable without ownership verification"() {
        expect:
        req('PUT', '/internal/registrations/me', alice, '{"phone_number":"+971501234567"}').statusCode() == 204
        req('PUT', '/internal/registrations/me', alice, '{"phone_number":"+971501234567"}').statusCode() == 204
        req('GET', '/v1/users/me').statusCode() == 404
        req('POST', '/v1/users/resolve-phone', bob, '{"phone_number":"+971501234567"}').statusCode() == 404
        when:
        def saved = req('PUT', '/v1/users/me', alice, '{"display_name":"Alice"}')
        def resolved = req('POST', '/v1/users/resolve-phone', bob, '{"phone_number":"+971501234567"}')
        then:
        saved.statusCode() == 200
        json(saved).get('phone_number').asText() == '+971501234567'
        !json(saved).get('phone_verified').asBoolean()
        resolved.statusCode() == 200
        json(resolved).fieldNames().toList().sort() == ['display_name','identity_id','phone_number']
        json(resolved).get('identity_id').asText() == alice.toString()
        req('PUT', '/v1/users/me/phone', alice, '{"phone_number":"+971501234567"}').statusCode() == 200
        req('PUT', '/v1/users/me/phone', alice, '{"phone_number":"+971501234568"}').statusCode() == 409
        req('PUT', '/internal/registrations/me', alice, '{"phone_number":"+971501234568"}').statusCode() == 409
        req('PUT', '/internal/registrations/me', bob, '{"phone_number":"+971501234567"}').statusCode() == 409
        new PostgresProfiles(db).find(alice).get().phoneNumber() == '+971501234567'
        db.transaction { it.fetchCount(org.jooq.impl.DSL.table('phone_verification_audit')) } == 0
    }
    def "private registration rejects absent credentials malformed phones and spoofed fields"() {
        expect:
        req('PUT', '/internal/registrations/me', alice, '{"phone_number":"+971501234567"}', null).statusCode() == 401
        req('PUT', '/internal/registrations/me', alice, '{"phone_number":"+971501234567"}', 'wrong').statusCode() == 401
        req('PUT', '/internal/registrations/me', null, '{"phone_number":"+971501234567"}').statusCode() == 401
        req('PUT', '/internal/registrations/me', alice, body).statusCode() == 400
        db.transaction { it.fetchCount(org.jooq.impl.DSL.table('registration_phone_claims')) } == 0
        where:
        body << ['{}', '{"phone_number":null}', '{"phone_number":123}', '{"phone_number":"+15550000001"}',
            '{"phone_number":"0501234567"}', '{"phone_number":"+971511234567"}', '{"phone_number":"+97150123456"}',
            '{"phone_number":"+971501234567","identity_id":"forged"}', '{"phone_number":"+971501234567","phone_verified":true}']
    }
    def "legacy profiles cannot claim publicly and binding preserves name and verification"() {
        given:
        def store = new PostgresProfiles(db)
        store.save(new com.arman.bank.userservice.domain.Profile(UUID.randomUUID(), alice, 'Original'))
        db.transaction { it.execute('update profiles set phone_number = ?, phone_verified = true where identity_id = ?', '+971501234567', alice); it.execute('update profile_directory set phone_number = ?, phone_verified = true where identity_id = ?', '+971501234567', alice) }
        expect:
        req('POST', '/v1/users/resolve-phone', bob, '{"phone_number":"+971501234567"}').statusCode() == 404
        req('PUT', '/v1/users/me/phone', alice, '{"phone_number":"+971501234567"}').statusCode() == 409
        req('PUT', '/internal/registrations/me', bob, '{"phone_number":"+971501234567"}').statusCode() == 409
        req('PUT', '/internal/registrations/me', alice, '{"phone_number":"+971501234568"}').statusCode() == 409
        req('PUT', '/internal/registrations/me', alice, '{"phone_number":"+971501234567"}').statusCode() == 204
        store.find(alice).get().displayName() == 'Original'
        store.find(alice).get().phoneVerified()
        req('POST', '/v1/users/resolve-phone', bob, '{"phone_number":"+971501234567"}').statusCode() == 200
        when:
        store.save(new com.arman.bank.userservice.domain.Profile(UUID.randomUUID(), bob, 'Bob'))
        store.registerPhone(bob, '+971501234568')
        then:
        store.find(bob).get().displayName() == 'Bob'
        store.find(bob).get().phoneNumber() == '+971501234568'
        !store.find(bob).get().phoneVerified()
    }
    def "concurrent registrations assign a phone to exactly one identity"() {
        given:
        def pool = Executors.newFixedThreadPool(2)
        def start = new CountDownLatch(1)
        when:
        def futures = [alice,bob].collect { owner -> pool.submit({
            start.await(10, TimeUnit.SECONDS)
            req('PUT', '/internal/registrations/me', owner, '{"phone_number":"+971501234567"}').statusCode()
        } as Callable) }
        start.countDown()
        def statuses = futures.collect { it.get(15, TimeUnit.SECONDS) }
        then:
        statuses.sort() == [204,409]
        db.transaction { it.fetchCount(org.jooq.impl.DSL.table('registration_phone_claims')) } == 1
        db.transaction { it.fetchCount(org.jooq.impl.DSL.table('profiles')) } == 0
        cleanup:
        pool?.shutdownNow()
    }
    def "concurrent same identity retries share one immutable claim"() {
        given:
        def pool = Executors.newFixedThreadPool(2)
        when:
        def statuses = (1..2).collect { pool.submit({
            req('PUT', '/internal/registrations/me', alice, '{"phone_number":"+971501234567"}').statusCode()
        } as Callable) }.collect { it.get(15, TimeUnit.SECONDS) }
        then:
        statuses == [204,204]
        db.transaction { it.fetchCount(org.jooq.impl.DSL.table('registration_phone_claims')) } == 1
        cleanup:
        pool?.shutdownNow()
    }
    def "registration survives connection pool restart and unavailable database fails closed"() {
        given:
        def store = new PostgresProfiles(db)
        store.registerPhone(alice, '+971501234567')
        when:
        db.close()
        db = new Database(postgres.jdbcUrl, postgres.username, postgres.password)
        store = new PostgresProfiles(db)
        store.registerPhone(alice, '+971501234567')
        def profile = new ProfileService(store).save(alice, 'Restarted')
        then:
        profile.phoneNumber() == '+971501234567'
        !profile.phoneVerified()
        when:
        // Runtime retains the now-closed original pool.
        def response = req('PUT', '/internal/registrations/me', alice, '{"phone_number":"+971501234567"}')
        then:
        response.statusCode() == 503
    }
    def "explicit operator evidence stays separate from registration and rejects stale verification"() {
        given:
        def store = new PostgresProfiles(db)
        store.registerPhone(alice, '+971501234567')
        new ProfileService(store).save(alice, 'Alice')
        when:
        store.verifyPendingPhone(alice, '+971501234568', 'test-operator', 'stale-case')
        then:
        thrown(IllegalStateException)
        db.transaction { it.fetchCount(org.jooq.impl.DSL.table('phone_verification_audit')) } == 0
        when:
        store.verifyPendingPhone(alice, '+971501234567', 'test-operator', 'case-001')
        store.registerPhone(alice, '+971501234567')
        def renamed = new ProfileService(store).save(alice, 'Renamed')
        then:
        renamed.phoneVerified()
        renamed.phoneNumber() == '+971501234567'
        db.transaction { it.fetchCount(org.jooq.impl.DSL.table('phone_verification_audit')) } == 1
        when:
        store.verifyPendingPhone(alice, '+971501234567', 'test-operator', 'duplicate')
        then:
        thrown(IllegalStateException)
    }
    def "lookup limiter counts concurrent misses persists across service recreation and expires"() {
        given:
        def pool = Executors.newFixedThreadPool(8)
        when:
        def statuses = (1..40).collect { pool.submit({ req('POST', '/v1/users/resolve-phone', alice, '{"phone_number":"+971509999999"}').statusCode() } as Callable) }
            .collect { it.get(15, TimeUnit.SECONDS) }
        then:
        statuses.count(404) == 30
        statuses.count(429) == 10
        req('POST', '/v1/users/resolve-phone', bob, '{"phone_number":"+971509999999"}').statusCode() == 404
        when:
        new ProfileService(new PostgresProfiles(db)).resolvePhone(alice, '+971509999999')
        then:
        thrown(LookupLimitExceeded)
        when:
        db.transaction { it.execute("update phone_lookup_limits set window_start = current_timestamp - interval '61 seconds' where requester_id = ?", alice) }
        then:
        req('POST', '/v1/users/resolve-phone', alice, '{"phone_number":"+971509999999"}').statusCode() == 404
        cleanup:
        pool?.shutdownNow()
    }
}
