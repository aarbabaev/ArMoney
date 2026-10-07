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
    TestProfiles profiles = new TestProfiles()

    def setup() {
        postgres = new PostgreSQLContainer('postgres:17.6-alpine')
        postgres.start()
        database = new Database(postgres.jdbcUrl, postgres.username, postgres.password)
        clock = new TestClock(Instant.parse('2026-01-01T00:00:00Z'))
        service = new AuthService(new PostgresAuthStore(database), new Argon2Passwords(), clock, profiles)
        def routes = new AuthRoutes(service, KEY, { token -> new SsoTokens.Principal('https://issuer.example/realm', token, '+971501234567') } as SsoTokens)
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
    def registrationBody(String email, String password = PASSWORD, String phone = '+971501234567') {
        JsonOutput.toJson([email:email, password:password, phone_number:phone])
    }
    def parsed(response) { new JsonSlurper().parseText(response.body()) }

    def "register login session restart expiry and logout preserve identity and hide secrets"() {
        when:
        def registration = request('POST', '/v1/auth/register', registrationBody(' Alice@Example.com '))
        def duplicate = request('POST', '/v1/auth/register', registrationBody('alice@example.com', 'different long password'))
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
        new AuthService(new PostgresAuthStore(database), new Argon2Passwords(), clock, profiles).me('Bearer ' + token).email() == 'alice@example.com'
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

    def 'private contact lookup requires service key and distinguishes unavailable contact from unknown identity'() {
        given:
        service.register('local@example.com', PASSWORD, '+971521234567')
        def store = new PostgresAuthStore(database)
        def local = store.findByEmail('local@example.com').orElseThrow().identity().id()
        def external = store.externalIdentity('https://issuer.example/realm', 'no-email', '+971501234567').id()
        def path = "/v1/internal/identities/${local}/email"
        when:
        def response = request('GET', path)
        then:
        response.statusCode() == 200
        parsed(response) == [email:'local@example.com', verified:false]
        response.headers().firstValue('Cache-Control').orElse('') == 'no-store'
        request('GET', path, null, null, null).statusCode() == 401
        request('GET', path, null, null, 'wrong').statusCode() == 401
        request('GET', '/v1/internal/identities/invalid/email', null, null, null).statusCode() == 401
        request('GET', '/v1/internal/identities/invalid/email').statusCode() == 400
        request('GET', '/v1/internal/identities/1-1-1-1-1/email').statusCode() == 400
        request('GET', "/v1/internal/identities/${UUID.randomUUID()}/email").statusCode() == 404
        parsed(request('GET', "/v1/internal/identities/${external}/email")) == [email:null, verified:false]
    }

    def 'SSO contact refresh clears stale claims and never links local credentials by email'() {
        given:
        service.register('local@example.com', PASSWORD, '+971521234567')
        def store = new PostgresAuthStore(database)
        def local = store.findByEmail('local@example.com').orElseThrow()
        def original = new SsoTokens.Principal('https://issuer.example/realm', 'email-subject', '+971501234567', 'local@example.com', true)
        def token = service.sso('synthetic-token', { ignored -> original } as SsoTokens).accessToken()
        def id = service.me('Bearer ' + token).id()
        def path = "/v1/internal/identities/${id}/email"
        expect:
        id != local.identity().id()
        parsed(request('GET', path)) == [email:'local@example.com', verified:true]
        service.me('Bearer ' + token).email() == null

        when:
        def changed = new SsoTokens.Principal(original.issuer(), original.subject(), original.phoneNumber(), email, verified)
        def next = service.sso('synthetic-token', { ignored -> changed } as SsoTokens)
        then:
        service.me('Bearer ' + next.accessToken()).id() == id
        parsed(request('GET', path)) == [email:expectedEmail, verified:expectedVerified]
        store.findByEmail('local@example.com').orElseThrow() == local
        service.me('Bearer ' + service.login('local@example.com', PASSWORD).accessToken()).id() == local.identity().id()
        database.transaction { sql -> sql.fetchOne('select password_hash from identities where id = ?', id).get(0, String) } == null
        where:
        email                 | verified | expectedEmail         | expectedVerified
        'changed@example.com' | true     | 'changed@example.com' | true
        'changed@example.com' | false    | 'changed@example.com' | false
        'local@example.com'   | false    | 'local@example.com'   | false
        null                  | false    | null                  | false
        'invalid'             | true     | null                  | false
    }

    def 'phone conflict leaves the prior contact and other identities unchanged'() {
        given:
        def store = new PostgresAuthStore(database)
        def first = new SsoTokens.Principal('https://issuer.example/realm', 'first', '+971501234567', 'first@example.com', true)
        def second = new SsoTokens.Principal(first.issuer(), 'second', '+971521234567', 'second@example.com', true)
        def id = store.externalIdentity(first).id()
        def other = store.externalIdentity(second).id()
        when:
        store.externalIdentity(new SsoTokens.Principal(first.issuer(), first.subject(), second.phoneNumber(), 'changed@example.com', false))
        then:
        def failure = thrown(AuthFailure)
        failure.kind() == AuthFailure.Kind.UNAUTHORIZED
        store.emailContact(id).orElseThrow() == new AuthStore.EmailContact('first@example.com', true)
        store.emailContact(other).orElseThrow() == new AuthStore.EmailContact('second@example.com', true)
    }

    def 'failed contact write rolls back new mapping and identity together'() {
        given:
        def store = new PostgresAuthStore(database)
        database.transaction { sql -> sql.execute("ALTER TABLE identities ADD CONSTRAINT synthetic_contact_failure CHECK (notification_email IS NULL)") }
        when:
        store.externalIdentity(new SsoTokens.Principal('https://issuer.example/realm', 'rollback', '+971501234567', 'rollback@example.com', true))
        then:
        thrown(org.jooq.exception.DataAccessException)
        database.transaction { sql -> sql.fetchOne('select count(*) from identities').get(0, Long) } == 0
        database.transaction { sql -> sql.fetchOne('select count(*) from external_identities').get(0, Long) } == 0
        database.transaction { sql -> sql.fetchOne('select count(*) from sessions').get(0, Long) } == 0
    }

    def "invalid input and missing service identity fail closed"() {
        expect:
        request('POST', '/v1/auth/register', registrationBody('a@example.com'), null, null).statusCode() == 401
        request('POST', '/v1/auth/register', registrationBody('a@example.com'), null, 'wrong').statusCode() == 401
        request('POST', '/v1/auth/register', '{').statusCode() == 400
        request('POST', '/v1/auth/register', registrationBody('not-email')).statusCode() == 400
        request('POST', '/v1/auth/register', registrationBody('a@example.com', 'short')).statusCode() == 400
        request('POST', '/v1/auth/register', registrationBody('a@example.com', 'x' * 129)).statusCode() == 400
        request('POST', '/v1/auth/register', registrationBody('a@example.com').replace('}', ',"admin":true}')).statusCode() == 400
        request('POST', '/v1/auth/register', registrationBody('a@example.com') + '{}').statusCode() == 400
        request('POST', '/v1/auth/register', registrationBody('a@example.com', 'x' * 5000)).statusCode() == 413
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

    def "SSO subjects preserve identity across concurrent exchanges without linking legacy email"() {
        given:
        service.register('same@example.com', PASSWORD, '+971521234567')
        def legacy = service.me('Bearer ' + service.login('same@example.com', PASSWORD).accessToken()).id()
        def executor = Executors.newFixedThreadPool(6)
        def store = new PostgresAuthStore(database)

        when:
        def ids = (1..12).collect {
            executor.submit({ store.externalIdentity('https://issuer.example/realm', 'subject-A', '+971501234567').id() } as Callable<UUID>)
        }.collect { it.get(15, TimeUnit.SECONDS) }
        def response = request('POST', '/v1/auth/sso', JsonOutput.toJson([access_token:'subject-A']))
        def token = parsed(response).access_token as String
        def me = parsed(request('GET', '/v1/auth/me', null, token))

        then:
        ids.toSet().size() == 1
        response.statusCode() == 200
        me.id == ids.first().toString()
        me.email == null
        ids.first() != legacy
        store.externalIdentity('https://issuer.example/realm', 'subject-B', '+971541234567').id() != ids.first()
        store.externalIdentity('https://other.example/realm', 'subject-A', '+971551234567').id() != ids.first()
        new PostgresAuthStore(database).externalIdentity('https://issuer.example/realm', 'subject-A', '+971501234567').id() == ids.first()
        database.transaction { sql -> sql.fetchOne('select count(*) from identities').get(0, Long) } == 4
        request('POST', '/v1/auth/logout', null, token).statusCode() == 204
        request('GET', '/v1/auth/me', null, token).statusCode() == 401

        cleanup:
        executor?.shutdownNow()
    }

    def "SSO input shape and service trust boundary fail closed"() {
        expect:
        request('POST', '/v1/auth/sso', '{"access_token":"token"}', null, null).statusCode() == 401
        request('POST', '/v1/auth/sso', '{"access_token":123}').statusCode() == 400
        request('POST', '/v1/auth/sso', '{"access_token":""}').statusCode() == 400
        request('POST', '/v1/auth/sso', '{"access_token":"token","issuer":"evil"}').statusCode() == 400
        request('POST', '/v1/auth/sso', JsonOutput.toJson([access_token:('x' * 8193)])).statusCode() == 400
        request('POST', '/v1/auth/sso', JsonOutput.toJson([access_token:('x' * 13000)])).statusCode() == 413
    }

    def 'registration requires an exact UAE mobile number and no extra fields'() {
        expect:
        request('POST', '/v1/auth/register', registrationBody('phone@example.com', PASSWORD, phone)).statusCode() == 400
        database.transaction { sql -> sql.fetchCount(org.jooq.impl.DSL.table('identities')) } == 0
        where:
        phone << [null, '', '+971511234567', '+971571234567', '+971591234567', '+97150123456', '+9715012345678',
                  '0501234567', '+12025550123', ' +971501234567', '+971501234567\n', '+97150١٢٣٤٥٦٧']
    }

    def 'missing phone is invalid and duplicate email cannot mutate its claim or password'() {
        expect:
        request('POST', '/v1/auth/register', body('phone@example.com')).statusCode() == 400
        request('POST', '/v1/auth/register', registrationBody('phone@example.com')).statusCode() == 202
        request('POST', '/v1/auth/register', registrationBody('phone@example.com', 'another sufficiently long password', '+971521234567')).statusCode() == 409
        request('POST', '/v1/auth/register', registrationBody('different@example.com')).statusCode() == 409
        request('POST', '/v1/auth/login', body('phone@example.com')).statusCode() == 200
        database.transaction { sql -> sql.fetchOne('select registration_phone from identities').get(0, String) } == '+971501234567'
    }

    def 'concurrent legacy and SSO registration of a phone creates one identity without orphan mappings'() {
        given:
        def store = new PostgresAuthStore(database)
        def executor = Executors.newFixedThreadPool(2)
        def start = new CountDownLatch(1)
        def hash = new Argon2Passwords().hash(PASSWORD)

        when:
        def pending = [executor.submit({
            start.await(10, TimeUnit.SECONDS)
            try { store.register(new com.arman.bank.authservice.domain.Identity(UUID.randomUUID(), 'race@example.com', '+971501234567'), hash).id() }
            catch (AuthFailure failure) { failure.kind() }
        } as Callable<Object>), executor.submit({
            start.await(10, TimeUnit.SECONDS)
            try { store.externalIdentity('https://issuer.example/realm', 'racing-subject', '+971501234567').id() }
            catch (AuthFailure failure) { failure.kind() }
        } as Callable<Object>)]
        start.countDown()
        def results = pending.collect { it.get(15, TimeUnit.SECONDS) }

        then:
        results.count { it instanceof UUID } == 1
        results.count { it == AuthFailure.Kind.CONFLICT } == 1
        database.transaction { sql -> sql.fetchOne('select count(*) from identities').get(0, Long) } == 1
        database.transaction { sql -> sql.fetchOne('select count(*) from external_identities e left join identities i on i.id=e.identity_id where i.id is null').get(0, Long) } == 0

        cleanup:
        executor?.shutdownNow()
    }

    def 'concurrent different legacy emails cannot reserve the same phone'() {
        given:
        def store = new PostgresAuthStore(database)
        def executor = Executors.newFixedThreadPool(4)
        def hash = new Argon2Passwords().hash(PASSWORD)
        when:
        def results = (1..8).collect { index ->
            executor.submit({
                try { store.register(new com.arman.bank.authservice.domain.Identity(UUID.randomUUID(), "race${index}@example.com", '+971501234567'), hash).id() }
                catch (AuthFailure failure) { failure.kind() }
            } as Callable<Object>)
        }.collect { it.get(15, TimeUnit.SECONDS) }
        then:
        results.count { it instanceof UUID } == 1
        results.count { it == AuthFailure.Kind.CONFLICT } == 7
        database.transaction { sql -> sql.fetchOne('select count(*) from identities').get(0, Long) } == 1
        cleanup:
        executor?.shutdownNow()
    }

    def 'concurrent changed phone claims for one SSO subject roll back the losing identity'() {
        given:
        def store = new PostgresAuthStore(database)
        def executor = Executors.newFixedThreadPool(2)
        when:
        def results = ['+971501234567', '+971521234567'].collect { phone ->
            executor.submit({
                try { store.externalIdentity('https://issuer.example/realm', 'one-subject', phone).id() }
                catch (AuthFailure failure) { failure.kind() }
            } as Callable<Object>)
        }.collect { it.get(15, TimeUnit.SECONDS) }
        then:
        results.count { it instanceof UUID } == 1
        results.count { it == AuthFailure.Kind.UNAUTHORIZED } == 1
        database.transaction { sql -> sql.fetchOne('select count(*) from identities').get(0, Long) } == 1
        database.transaction { sql -> sql.fetchOne('select count(*) from external_identities').get(0, Long) } == 1
        cleanup:
        executor?.shutdownNow()
    }

    def 'lost provisioning acknowledgement is retried with durable identity before issuing a session'() {
        given:
        profiles.failAfterCommit = true

        when:
        def registration = request('POST', '/v1/auth/register', registrationBody('retry@example.com'))
        def identity = database.transaction { sql -> sql.fetchOne('select id from identities').get(0, UUID) }
        def failedLogin = request('POST', '/v1/auth/login', body('retry@example.com'))

        then:
        registration.statusCode() == 503
        failedLogin.statusCode() == 503
        profiles.claims == [(identity): '+971501234567']
        database.transaction { sql -> sql.fetchOne('select count(*) from sessions').get(0, Long) } == 0

        when:
        profiles.failAfterCommit = false
        service = new AuthService(new PostgresAuthStore(database), new Argon2Passwords(), clock, profiles)
        def recovered = service.login('retry@example.com', PASSWORD)

        then:
        service.me('Bearer ' + recovered.accessToken()).id() == identity
        profiles.claims.size() == 1
        database.transaction { sql -> sql.fetchOne('select count(*) from identities').get(0, Long) } == 1
    }

    def 'SSO provisioning outage and conflict never issue sessions and retry preserves the mapping'() {
        given:
        profiles.failure = failureKind

        when:
        def denied = request('POST', '/v1/auth/sso', JsonOutput.toJson([access_token:'pending-subject']))
        def identity = database.transaction { sql -> sql.fetchOne('select id from identities').get(0, UUID) }

        then:
        denied.statusCode() == expectedStatus
        database.transaction { sql -> sql.fetchOne('select count(*) from sessions').get(0, Long) } == 0

        when:
        profiles.failure = null
        def success = request('POST', '/v1/auth/sso', JsonOutput.toJson([access_token:'pending-subject']))

        then:
        success.statusCode() == 200
        service.me('Bearer ' + parsed(success).access_token).id() == identity

        where:
        failureKind                 | expectedStatus
        AuthFailure.Kind.UNAVAILABLE | 503
        AuthFailure.Kind.CONFLICT    | 409
    }

    def 'new SSO mappings reject missing or foreign numbers without persisting an identity'() {
        when:
        service.sso('synthetic-token', { ignored -> new SsoTokens.Principal('https://issuer.example/realm', 'new-subject', phone) } as SsoTokens)
        then:
        thrown(AuthFailure)
        database.transaction { sql -> sql.fetchOne('select count(*) from identities').get(0, Long) } == 0
        where:
        phone << [null, '', '+12025550123', '+971571234567']
    }

    def 'existing SSO claimed number cannot be changed or omitted'() {
        given:
        def store = new PostgresAuthStore(database)
        def identity = store.externalIdentity('https://issuer.example/realm', 'immutable-subject', '+971501234567')
        when:
        service.sso('synthetic-token', { ignored -> new SsoTokens.Principal('https://issuer.example/realm', 'immutable-subject', phone) } as SsoTokens)
        then:
        def error = thrown(AuthFailure)
        error.kind() == AuthFailure.Kind.UNAUTHORIZED
        store.externalIdentity('https://issuer.example/realm', 'immutable-subject', '+971501234567').id() == identity.id()
        database.transaction { sql -> sql.fetchOne('select count(*) from sessions').get(0, Long) } == 0
        where:
        phone << [null, '+971521234567', '+12025550123']
    }

    def 'historical phone-less legacy and SSO identities keep their IDs without automatic enrollment'() {
        given:
        def legacy = UUID.randomUUID()
        def external = UUID.randomUUID()
        def hash = new Argon2Passwords().hash(PASSWORD)
        database.transaction { sql ->
            sql.execute('insert into identities(id,email,password_hash) values (?,?,?)', legacy, 'historical@example.com', hash)
            sql.execute('insert into identities(id) values (?)', external)
            sql.execute('insert into external_identities(issuer,subject,identity_id) values (?,?,?)', 'https://issuer.example/realm', 'old-subject', external)
        }
        profiles.failure = AuthFailure.Kind.UNAVAILABLE

        expect:
        service.me('Bearer ' + service.login('historical@example.com', PASSWORD).accessToken()).id() == legacy
        service.me('Bearer ' + service.sso('synthetic-token', { ignored -> new SsoTokens.Principal('https://issuer.example/realm', 'old-subject', null) } as SsoTokens).accessToken()).id() == external
        profiles.claims.isEmpty()
        database.transaction { sql -> sql.fetchOne('select count(*) from identities where registration_phone is null').get(0, Long) } == 2
    }

    static class TestProfiles implements RegistrationProfiles {
        Map<UUID, String> claims = new ConcurrentHashMap<>()
        volatile boolean failAfterCommit
        volatile AuthFailure.Kind failure
        void provision(UUID identity, String phone) {
            if (failure != null) throw new AuthFailure(failure)
            def existing = claims.putIfAbsent(identity, phone)
            if (existing != null && existing != phone) throw new AuthFailure(AuthFailure.Kind.CONFLICT)
            if (failAfterCommit) throw new AuthFailure(AuthFailure.Kind.UNAVAILABLE)
        }
    }

    static class TestClock extends Clock {
        Instant current
        TestClock(Instant current) { this.current = current }
        ZoneId getZone() { ZoneOffset.UTC }
        Clock withZone(ZoneId zone) { this }
        Instant instant() { current }
    }
}
