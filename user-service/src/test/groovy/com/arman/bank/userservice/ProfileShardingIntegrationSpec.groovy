package com.arman.bank.userservice

import com.arman.bank.runtime.Database
import com.arman.bank.userservice.application.*
import com.arman.bank.userservice.infrastructure.*
import org.testcontainers.containers.PostgreSQLContainer
import org.flywaydb.core.Flyway
import spock.lang.Specification
import java.util.concurrent.*

class ProfileShardingIntegrationSpec extends Specification {
    PostgreSQLContainer primaryContainer
    PostgreSQLContainer shardContainer
    Database primary
    Database shard
    ProfileShards pools
    PostgresProfiles store
    ProfileService service
    def setup() {
        primaryContainer = new PostgreSQLContainer('postgres:17.6-alpine')
        shardContainer = new PostgreSQLContainer('postgres:17.6-alpine')
        primaryContainer.start(); shardContainer.start()
        primary = new Database(primaryContainer.jdbcUrl, primaryContainer.username, primaryContainer.password)
        shard = new Database(shardContainer.jdbcUrl, shardContainer.username, shardContainer.password)
        pools = new ProfileShards([primary: primary, east: shard], [ab: 'east'], 'primary')
        store = new PostgresProfiles(pools)
        service = new ProfileService(store)
    }
    def cleanup() { pools?.close(); primary?.close(); shard?.close(); primaryContainer?.stop(); shardContainer?.stop() }
    def count(Database db, String table) { db.transaction { it.fetchCount(org.jooq.impl.DSL.table(table)) } }

    def 'physical placement preserves owner identity and remains pinned after map and email changes'() {
        given:
        def owner = UUID.randomUUID()
        def other = UUID.randomUUID()
        when:
        def profile = service.save(owner, 'Synthetic East', ' AB@example.test ')
        then:
        profile.identityId() == owner
        profile.id() != owner
        count(primary, 'profiles') == 0
        count(shard, 'profiles') == 1
        !service.find(other).present
        when:
        def reconfigured = new ProfileService(new PostgresProfiles(new ProfileShards([primary: primary, east: shard], [ab: 'primary'], 'primary')))
        def updated = reconfigured.save(owner, 'Changed', 'zz@example.test')
        then:
        updated.id() == profile.id()
        count(primary, 'profiles') == 0
        reconfigured.find(owner).get().displayName() == 'Changed'
        when:
        primary.transaction { it.execute("update profile_directory set shard_id = 'primary' where identity_id = ?", owner) }
        then:
        thrown(org.jooq.exception.DataAccessException)
        when:
        new ProfileService(new PostgresProfiles(primary)).find(owner)
        then:
        thrown(IllegalStateException)
    }

    def 'concurrent initial writes share one durable UUID and a shard outage recovers original placement'() {
        given:
        def owner = UUID.randomUUID()
        def executor = Executors.newFixedThreadPool(6)
        when:
        def results = (1..12).collect { n -> executor.submit({ service.save(owner, 'Name ' + n, 'ab@example.test') } as Callable) }
            .collect { it.get(20, TimeUnit.SECONDS) }
        then:
        results*.id().unique().size() == 1
        count(primary, 'profile_directory') == 1
        count(shard, 'profiles') == 1
        when:
        def pending = UUID.randomUUID()
        shard.close()
        service.save(pending, 'Recover', 'ab@example.test')
        then:
        thrown(org.jooq.exception.DataAccessException)
        !service.find(pending).present
        when:
        def pinned = primary.transaction { it.fetchOne('select profile_id from profile_directory where identity_id = ?', pending).get(0, UUID) }
        shard = new Database(shardContainer.jdbcUrl, shardContainer.username, shardContainer.password)
        def restarted = new ProfileService(new PostgresProfiles(new ProfileShards([primary: primary, east: shard], [ab: 'primary'], 'primary')))
        def recovered = restarted.save(pending, 'Recover', 'zz@example.test')
        then:
        recovered.id() == pinned
        count(primary, 'profiles') == 0
        restarted.find(pending).get().id() == pinned
        cleanup:
        executor?.shutdownNow()
    }

    def 'registered phone uniqueness unverified discovery audit and lookup quota remain global across physical shards'() {
        given:
        def left = UUID.randomUUID()
        def right = UUID.randomUUID()
        service.save(left, 'Primary', 'zz@example.test')
        service.save(right, 'East', 'ab@example.test')
        service.registerPhone(right, '+971501234567')
        expect:
        service.resolvePhone(left, '+971501234567').get().identityId() == right
        !service.find(right).get().phoneVerified()
        count(primary, 'registration_phone_claims') == 1
        count(shard, 'registration_phone_claims') == 0
        count(primary, 'phone_verification_audit') == 0
        count(shard, 'phone_verification_audit') == 0
        when:
        service.registerPhone(left, '+971501234567')
        then:
        thrown(RegistrationConflict)
        service.find(left).get().phoneNumber() == null
        when:
        (1..29).each { service.resolvePhone(left, '+971509999999') }
        service.resolvePhone(left, '+971501234567')
        then:
        thrown(LookupLimitExceeded)
        when:
        service.changePhone(right, '+971501234568')
        then:
        thrown(RegistrationConflict)
        service.changePhone(right, '+971501234567').get().phoneNumber() == '+971501234567'
        service.resolvePhone(right, '+971501234567').get().identityId() == right
        count(primary, 'profiles') == 1
        when:
        store.verifyPendingPhone(right, '+971501234567', 'synthetic-operator', 'case-shards')
        then:
        service.find(right).get().phoneVerified()
        count(primary, 'phone_verification_audit') == 1
        count(shard, 'phone_verification_audit') == 0
    }

    def 'invalid trusted emails cause no directory reservation and legacy clients use default'() {
        when:
        service.save(UUID.randomUUID(), 'Invalid', 'ab@example.test\r\nforged')
        then:
        thrown(IllegalArgumentException)
        count(primary, 'profile_directory') == 0
        when:
        def owner = UUID.randomUUID()
        def saved = service.save(owner, 'Legacy')
        then:
        count(primary, 'profiles') == 1
        service.find(owner).get().id() == saved.id()
    }

    def 'SEC-SHARD-001 and SEC-SHARD-002 preserve auth email grammar and configured missing-email default'() {
        given:
        def configured = new ProfileService(new PostgresProfiles(new ProfileShards([primary: primary, east: shard], [ab: 'primary'], 'east')))
        when:
        def missing = configured.save(UUID.randomUUID(), 'Missing', null)
        def punctuation = configured.save(UUID.randomUUID(), 'Punctuation', '.a@example.123')
        def ordinary = configured.save(UUID.randomUUID(), 'Mapped', ' AB@example.test ')
        then:
        count(primary, 'profiles') == 1
        count(shard, 'profiles') == 2
        configured.find(missing.identityId()).get().id() == missing.id()
        configured.find(punctuation.identityId()).get().id() == punctuation.id()
        configured.find(ordinary.identityId()).get().id() == ordinary.id()
    }

    def 'shard commit before failed primary confirmation recovers original UUID and placement'() {
        given:
        def owner = UUID.randomUUID()
        service.registerPhone(owner, '+971501234567')
        primary.transaction {
            it.execute('''create function synthetic_reject_confirmation() returns trigger language plpgsql as $$
                begin if new.initialized and not old.initialized then raise exception 'Synthetic confirmation outage'; end if; return new; end; $$''')
            it.execute('create trigger synthetic_confirmation_failure before update on profile_directory for each row execute function synthetic_reject_confirmation()')
        }
        when:
        service.save(owner, 'Committed East', 'ab@example.test')
        then:
        thrown(org.jooq.exception.DataAccessException)
        count(shard, 'profiles') == 1
        count(primary, 'profiles') == 0
        !service.find(owner).present
        !service.resolvePhone(UUID.randomUUID(), '+971501234567').present
        when:
        def pinned = primary.transaction { it.fetchOne('select profile_id from profile_directory where identity_id = ?', owner).get(0, UUID) }
        primary.transaction { it.execute('drop trigger synthetic_confirmation_failure on profile_directory'); it.execute('drop function synthetic_reject_confirmation()') }
        def reconfigured = new ProfileService(new PostgresProfiles(new ProfileShards([primary: primary, east: shard], [ab: 'primary'], 'primary')))
        def recovered = reconfigured.save(owner, 'Recovered East', 'zz@example.test')
        then:
        recovered.id() == pinned
        recovered.displayName() == 'Recovered East'
        recovered.phoneNumber() == '+971501234567'
        !recovered.phoneVerified()
        reconfigured.resolvePhone(UUID.randomUUID(), '+971501234567').get().id() == pinned
        count(shard, 'profiles') == 1
        count(primary, 'profiles') == 0
        reconfigured.find(owner).get().id() == pinned
    }

    def 'composite readiness fails when any configured shard closes'() {
        expect:
        pools.ready()
        primary.ready()
        when:
        shard.close()
        then:
        primary.ready()
        !pools.ready()
    }

    def 'central claim mismatch during remote shard read prevents stale recipient disclosure'() {
        given:
        def owner = UUID.randomUUID()
        def requester = UUID.randomUUID()
        service.save(owner, 'East Recipient', 'ab@example.test')
        service.registerPhone(owner, '+971501234570')
        def connection = java.sql.DriverManager.getConnection(shardContainer.jdbcUrl, shardContainer.username, shardContainer.password)
        connection.autoCommit = false
        def statement = connection.createStatement()
        statement.execute('lock table profiles in access exclusive mode')
        def executor = Executors.newSingleThreadExecutor()
        when:
        def result = executor.submit({ service.resolvePhone(requester, '+971501234570') } as Callable)
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        boolean blocked = false
        while (!blocked && System.nanoTime() < deadline) {
            blocked = shard.transaction { it.fetchOne("select count(*) from pg_stat_activity where wait_event_type = 'Lock' and query like '%select * from profiles where identity_id%'").get(0, Integer) > 0 }
            if (!blocked) Thread.sleep(25)
        }
        assert blocked : 'Expected resolver shard read to block after directory snapshot'
        // Isolated operator fixture creates central inconsistency during IO; immutable public APIs cannot do this.
        primary.transaction { it.execute('update profile_directory set phone_number = ?, phone_verified = false where identity_id = ?', '+971501234571', owner) }
        connection.commit()
        then:
        !result.get(10, TimeUnit.SECONDS).present
        !service.find(owner).get().phoneVerified()
        cleanup:
        connection?.rollback(); statement?.close(); connection?.close(); executor?.shutdownNow()
    }

    def 'cross shard concurrent claims choose one owner and retries preserve names and shard UUIDs'() {
        given:
        def left = UUID.randomUUID()
        def right = UUID.randomUUID()
        def original = [service.save(left, 'Primary', 'zz@example.test'), service.save(right, 'East', 'ab@example.test')]
        def executor = Executors.newFixedThreadPool(2)
        def gate = new CountDownLatch(1)
        when:
        def futures = [left, right].collect { owner -> executor.submit({
            gate.await(10, TimeUnit.SECONDS)
            try { service.registerPhone(owner, '+971501234567'); return owner }
            catch (RegistrationConflict expected) { return null }
        } as Callable) }
        gate.countDown()
        def results = futures.collect { it.get(15, TimeUnit.SECONDS) }
        def winner = results.find { it != null }
        then:
        results.count { it != null } == 1
        count(primary, 'registration_phone_claims') == 1
        count(shard, 'registration_phone_claims') == 0
        when:
        service.registerPhone(winner, '+971501234567')
        def resolved = service.resolvePhone(UUID.randomUUID(), '+971501234567').get()
        then:
        resolved.identityId() == winner
        !resolved.phoneVerified()
        original.each { old ->
            assert service.find(old.identityId()).get().id() == old.id()
            assert service.find(old.identityId()).get().displayName() == old.displayName()
        }
        cleanup:
        executor?.shutdownNow()
    }

    def 'claim before failed shard creation survives restart and remains undiscoverable until confirmation'() {
        given:
        def owner = UUID.randomUUID()
        service.registerPhone(owner, '+971501234567')
        shard.close()
        when:
        service.save(owner, 'Recover Registered', 'ab@example.test')
        then:
        thrown(org.jooq.exception.DataAccessException)
        !service.resolvePhone(UUID.randomUUID(), '+971501234567').present
        when:
        def pinned = primary.transaction { it.fetchOne('select profile_id from profile_directory where identity_id = ?', owner).get(0, UUID) }
        service.registerPhone(owner, '+971501234567')
        shard = new Database(shardContainer.jdbcUrl, shardContainer.username, shardContainer.password)
        def restarted = new ProfileService(new PostgresProfiles(new ProfileShards([primary: primary, east: shard], [ab: 'primary'], 'primary')))
        def recovered = restarted.save(owner, 'Recovered Registered', 'zz@example.test')
        then:
        recovered.id() == pinned
        recovered.phoneNumber() == '+971501234567'
        !recovered.phoneVerified()
        restarted.resolvePhone(UUID.randomUUID(), '+971501234567').get().identityId() == owner
        count(primary, 'profiles') == 0
        count(shard, 'profiles') == 1
    }

    def 'registration during shard outage is durable centrally while lookup fails closed and recovers'() {
        given:
        def owner = UUID.randomUUID()
        def saved = service.save(owner, 'East Recipient', 'ab@example.test')
        shard.close()
        when:
        service.registerPhone(owner, '+971501234567')
        service.registerPhone(owner, '+971501234567')
        service.resolvePhone(UUID.randomUUID(), '+971501234567')
        then:
        thrown(org.jooq.exception.DataAccessException)
        count(primary, 'registration_phone_claims') == 1
        when:
        shard = new Database(shardContainer.jdbcUrl, shardContainer.username, shardContainer.password)
        def restarted = new ProfileService(new PostgresProfiles(new ProfileShards([primary: primary, east: shard], [:], 'primary')))
        def resolved = restarted.resolvePhone(UUID.randomUUID(), '+971501234567').get()
        then:
        resolved.id() == saved.id()
        resolved.displayName() == 'East Recipient'
        !resolved.phoneVerified()
        count(primary, 'phone_verification_audit') == 0
    }

    def 'legacy directory phone collision on another shard cannot be claimed'() {
        given:
        def legacy = UUID.randomUUID()
        def newcomer = UUID.randomUUID()
        service.save(legacy, 'Legacy East', 'ab@example.test')
        primary.transaction { it.execute('update profile_directory set phone_number = ? where identity_id = ?', '+971501234567', legacy) }
        when:
        service.registerPhone(newcomer, '+971501234567')
        then:
        thrown(RegistrationConflict)
        count(primary, 'registration_phone_claims') == 0
        !service.resolvePhone(newcomer, '+971501234567').present
        when:
        service.registerPhone(legacy, '+971501234567')
        then:
        service.resolvePhone(newcomer, '+971501234567').get().identityId() == legacy
        service.find(legacy).get().displayName() == 'Legacy East'
        !service.find(legacy).get().phoneVerified()
    }

    def 'registration racing a blocked shard creation binds the reserved directory without holding primary locks across IO'() {
        given:
        def owner = UUID.randomUUID()
        def connection = java.sql.DriverManager.getConnection(shardContainer.jdbcUrl, shardContainer.username, shardContainer.password)
        connection.autoCommit = false
        def statement = connection.createStatement()
        statement.execute('lock table profiles in access exclusive mode')
        def executor = Executors.newFixedThreadPool(2)
        when:
        def saving = executor.submit({ service.save(owner, 'Concurrent East', 'ab@example.test') } as Callable)
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        boolean blocked = false
        while (!blocked && System.nanoTime() < deadline) {
            blocked = shard.transaction { it.fetchOne("select count(*) from pg_stat_activity where wait_event_type = 'Lock' and query like '%insert into profiles%'").get(0, Integer) > 0 }
            if (!blocked) Thread.sleep(25)
        }
        assert blocked : 'Expected creation to block after durable directory reservation'
        executor.submit({ service.registerPhone(owner, '+971501234567'); return true } as Callable).get(10, TimeUnit.SECONDS)
        connection.commit()
        def saved = saving.get(10, TimeUnit.SECONDS)
        then:
        saved.phoneNumber() == '+971501234567'
        !saved.phoneVerified()
        service.resolvePhone(UUID.randomUUID(), '+971501234567').get().id() == saved.id()
        count(primary, 'profiles') == 0
        count(shard, 'profiles') == 1
        cleanup:
        connection?.rollback(); statement?.close(); connection?.close(); executor?.shutdownNow()
    }

    def 'primary outage never permits shard-only registration or recipient lookup'() {
        given:
        def owner = UUID.randomUUID()
        service.registerPhone(owner, '+971501234567')
        service.save(owner, 'East', 'ab@example.test')
        primary.close()
        when:
        service.registerPhone(owner, '+971501234567')
        then:
        thrown(org.jooq.exception.DataAccessException)
        when:
        service.resolvePhone(UUID.randomUUID(), '+971501234567')
        then:
        thrown(org.jooq.exception.DataAccessException)
        count(shard, 'registration_phone_claims') == 0
        count(shard, 'profiles') == 1
    }

    def 'populated V3 upgrade backfills primary placement and phone state without changing UUIDs'() {
        given:
        def legacy = new PostgreSQLContainer('postgres:17.6-alpine')
        legacy.start()
        Flyway.configure().dataSource(legacy.jdbcUrl, legacy.username, legacy.password).locations('classpath:db/migration').target('3').load().migrate()
        def identity = UUID.randomUUID()
        def id = UUID.randomUUID()
        def connection = java.sql.DriverManager.getConnection(legacy.jdbcUrl, legacy.username, legacy.password)
        def statement = connection.prepareStatement('insert into profiles(id,identity_id,display_name,phone_number,phone_verified) values (?,?,?,?,true)')
        statement.setObject(1,id); statement.setObject(2,identity); statement.setString(3,'Legacy'); statement.setString(4,'+15550000009'); statement.executeUpdate()
        statement.close(); connection.close()
        when:
        def upgraded = new Database(legacy.jdbcUrl, legacy.username, legacy.password)
        def upgradedService = new ProfileService(new PostgresProfiles(new ProfileShards([primary: upgraded, east: shard], [ab: 'east'], 'primary')))
        def saved = upgradedService.save(identity, 'Legacy Updated', 'ab@example.test')
        then:
        saved.id() == id
        saved.phoneVerified()
        saved.phoneNumber() == '+15550000009'
        count(upgraded, 'profiles') == 1
        count(shard, 'profiles') == 0
        cleanup:
        upgraded?.close(); legacy?.stop()
    }
}


