package com.arman.bank.authservice

import com.arman.bank.authservice.application.*
import com.arman.bank.authservice.infrastructure.*
import com.arman.bank.runtime.Database
import org.testcontainers.containers.PostgreSQLContainer
import spock.lang.Specification
import java.time.*

class BankingEligibilityIntegrationSpec extends Specification {
    PostgreSQLContainer postgres
    Database database
    UUID identity = UUID.randomUUID()
    Instant now = Instant.parse('2026-01-01T00:00:00Z')
    String password = 'synthetic sufficiently long password'

    def setup() {
        postgres = new PostgreSQLContainer('postgres:17.6-alpine')
        postgres.start()
        database = new Database(postgres.jdbcUrl, postgres.username, postgres.password)
        new PostgresAuthStore(database).register(new com.arman.bank.authservice.domain.Identity(identity, 'fixture@example.test', '+971501234567'), new Argon2Passwords().hash(password))
    }
    def cleanup() { database?.close(); postgres?.stop() }

    void associate(String state, Closure beforeCommit = {}, boolean pendingOnly = false) {
        database.transaction { sql ->
            PostgresVerificationProjectionStore.lockControls(sql)
            def enrollment = UUID.randomUUID()
            def email = UUID.randomUUID()
            def phone = UUID.randomUUID()
            sql.execute("insert into verification_enrollments(id,issuer,subject,binding_digest,binding_key_id,phone,email,state,created_at,expires_at) values (?, 'https://issuer.example/realm', 'fixture', ?, 'fixture', '+971501234567', 'fixture@example.test', 'PENDING_CONTACTS', cast(? as timestamptz), cast(? as timestamptz))", enrollment, new byte[32], now.atOffset(ZoneOffset.UTC), now.plusSeconds(86400).atOffset(ZoneOffset.UTC))
            sql.execute("insert into verification_candidates(id,enrollment_id,purpose,version,canonical_value,created_at) values (?,?,'REGISTRATION_EMAIL',1,'fixture@example.test',cast(? as timestamptz)), (?,?,'REGISTRATION_PHONE',1,'+971501234567',cast(? as timestamptz))", email,enrollment,now.atOffset(ZoneOffset.UTC),phone,enrollment,now.atOffset(ZoneOffset.UTC))
            sql.execute('insert into verification_authorities(enrollment_id,pending_identity_id,mapped_identity_id,state,email_candidate_id,phone_candidate_id,projection_operation_id,created_at,expires_at) values (?,?,?,?,?,?,?,cast(? as timestamptz),cast(? as timestamptz))', enrollment,identity,pendingOnly ? null : identity,state,email,phone,UUID.randomUUID(),now.atOffset(ZoneOffset.UTC),now.plusSeconds(86400).atOffset(ZoneOffset.UTC))
            beforeCommit.call()
        }
    }
    def 'off preserves populated authority login profile and existing sessions'() {
        given:
        def old = new AuthService(new PostgresAuthStore(database),new Argon2Passwords(),Clock.fixed(now,ZoneOffset.UTC),Stub(RegistrationProfiles)).login('fixture@example.test',password)
        associate(state)
        def profiles = Mock(RegistrationProfiles)
        def service = new AuthService(new PostgresAuthStore(database), new Argon2Passwords(), Clock.fixed(now,ZoneOffset.UTC),profiles)
        when:
        def session = service.login('fixture@example.test',password)
        then:
        service.me('Bearer '+session.accessToken()).id() == identity
        service.me('Bearer '+old.accessToken()).id() == identity
        1 * profiles.provision(identity,'+971501234567')
        where:
        state << ['PENDING_CONTACTS','PROJECTION_PENDING','PROJECTED','SUSPENDED','BLOCKED_MIGRATION']
    }
    def 'on denies new admission for every associated state while preserving stored sessions'() {
        given:
        def old = new AuthService(new PostgresAuthStore(database),new Argon2Passwords(),Clock.fixed(now,ZoneOffset.UTC),Stub(RegistrationProfiles)).login('fixture@example.test',password)
        associate(state)
        def profiles = Mock(RegistrationProfiles)
        def store = new PostgresAuthStore(database,true)
        def service = new AuthService(store,new Argon2Passwords(),Clock.fixed(now,ZoneOffset.UTC),profiles)
        when:
        service.login('fixture@example.test',password)
        then:
        def loginFailure = thrown(AuthFailure)
        loginFailure.kind() == AuthFailure.Kind.UNAUTHORIZED
        0 * profiles._
        when:
        def existingOwner = service.me('Bearer '+old.accessToken()).id()
        then:
        existingOwner == identity
        when:
        store.saveSession('a'*64,identity,now,now.plusSeconds(60))
        then:
        thrown(AuthFailure)
        database.transaction { sql -> sql.fetchOne('select count(*) from sessions').get(0,Integer) } == 1
        where:
        state << ['PENDING_CONTACTS','PROJECTION_PENDING','PROJECTED','SUSPENDED','BLOCKED_MIGRATION']
    }
    def 'on denies SSO and registration retry without profile provisioning'() {
        given:
        database.transaction { sql -> sql.execute("insert into external_identities(issuer,subject,identity_id) values ('https://issuer.example/realm','fixture',?)",identity) }
        associate('PENDING_CONTACTS')
        def profiles = Mock(RegistrationProfiles)
        def service = new AuthService(new PostgresAuthStore(database,true),new Argon2Passwords(),Clock.fixed(now,ZoneOffset.UTC),profiles)
        when:
        service.sso('synthetic', { token -> new SsoTokens.Principal('https://issuer.example/realm','fixture','+971501234567') } as SsoTokens)
        then:
        thrown(AuthFailure)
        0 * profiles._
        when:
        service.register('fixture@example.test',password,'+971501234567')
        then:
        thrown(AuthFailure)
        0 * profiles._
    }
    def 'on preserves unassociated login and verifies unknown user with dummy hash'() {
        given:
        def profiles = Stub(RegistrationProfiles)
        def service = new AuthService(new PostgresAuthStore(database,true),new Argon2Passwords(),Clock.fixed(now,ZoneOffset.UTC),profiles)
        expect:
        service.me('Bearer '+service.login('fixture@example.test',password).accessToken()).id() == identity
        when:
        service.login('unknown@example.test',password)
        then:
        def failure = thrown(AuthFailure)
        failure.kind() == AuthFailure.Kind.UNAUTHORIZED
    }
    def 'session insertion waits for concurrent association and denies after its commit'() {
        given:
        def entered = new java.util.concurrent.CountDownLatch(1)
        def release = new java.util.concurrent.CountDownLatch(1)
        def pool = java.util.concurrent.Executors.newFixedThreadPool(2)
        def association = pool.submit({ associate('PENDING_CONTACTS', { entered.countDown(); assert release.await(10,java.util.concurrent.TimeUnit.SECONDS) }) } as Runnable)
        assert entered.await(10,java.util.concurrent.TimeUnit.SECONDS)
        def save = pool.submit({ new PostgresAuthStore(database,true).saveSession('b'*64,identity,now,now.plusSeconds(60)) } as Runnable)
        when:
        boolean lockWaiter = false
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5)
        while (!lockWaiter && System.nanoTime() < deadline) {
            lockWaiter = database.transaction { sql -> sql.fetchOne("select exists(select 1 from pg_stat_activity where datname=current_database() and pid<>pg_backend_pid() and wait_event_type='Lock' and query like '%verification_control%' and cardinality(pg_blocking_pids(pid))>0) as waiting").get('waiting',Boolean) }
            if (!lockWaiter) Thread.sleep(20)
        }
        then:
        lockWaiter
        !save.isDone()
        when:
        release.countDown()
        association.get(10,java.util.concurrent.TimeUnit.SECONDS)
        save.get(10,java.util.concurrent.TimeUnit.SECONDS)
        then:
        def failure = thrown(java.util.concurrent.ExecutionException)
        failure.cause instanceof AuthFailure
        database.transaction { sql -> sql.fetchOne('select count(*) from sessions').get(0,Integer) } == 0
        cleanup:
        release.countDown()
        pool.shutdownNow()
    }
    def 'unknown user still runs password verification against constructor dummy hash'() {
        given:
        def passwords = Mock(PasswordHasher)
        1 * passwords.hash(_) >> 'synthetic-dummy-hash'
        def store = Mock(AuthStore)
        def profiles = Mock(RegistrationProfiles)
        def service = new AuthService(store,passwords,Clock.fixed(now,ZoneOffset.UTC),profiles)
        when:
        service.login('missing@example.test',password)
        then:
        1 * store.allowAttempt(_,now) >> true
        1 * store.findByEmail('missing@example.test') >> Optional.empty()
        1 * passwords.verify(password,'synthetic-dummy-hash') >> false
        0 * profiles._
        def failure = thrown(AuthFailure)
        failure.kind() == AuthFailure.Kind.UNAUTHORIZED
    }
    def 'pending-only association denies independently of mapped identity column'() {
        given:
        associate('PENDING_CONTACTS', {}, true)
        def store = new PostgresAuthStore(database,true)
        when:
        store.requireBankingAccess(identity)
        then:
        def failure = thrown(AuthFailure)
        failure.kind() == AuthFailure.Kind.UNAUTHORIZED
        when:
        store.saveSession('c'*64,identity,now,now.plusSeconds(60))
        then:
        thrown(AuthFailure)
    }
    def 'schema forbids mapped-only association with a different pending identity'() {
        given:
        associate('PENDING_CONTACTS')
        when:
        database.transaction { sql -> sql.execute('update verification_authorities set pending_identity_id=? where mapped_identity_id=?',UUID.randomUUID(),identity) }
        then:
        thrown(org.jooq.exception.DataAccessException)
    }
    def 'missing control fails closed for access creation and old session validation'() {
        given:
        def old = new AuthService(new PostgresAuthStore(database),new Argon2Passwords(),Clock.fixed(now,ZoneOffset.UTC),Stub(RegistrationProfiles)).login('fixture@example.test',password)
        database.transaction { sql -> sql.execute('delete from verification_control where id=1') }
        def store = new PostgresAuthStore(database,true)
        when:
        store.requireBankingAccess(identity)
        then:
        def accessFailure = thrown(AuthFailure)
        accessFailure.kind() == AuthFailure.Kind.UNAVAILABLE
        when:
        store.saveSession('d'*64,identity,now,now.plusSeconds(60))
        then:
        def saveFailure = thrown(AuthFailure)
        saveFailure.kind() == AuthFailure.Kind.UNAVAILABLE
        when:
        store.findSession(AuthService.digest(old.accessToken()),now)
        then:
        def findFailure = thrown(AuthFailure)
        findFailure.kind() == AuthFailure.Kind.UNAVAILABLE
    }}
