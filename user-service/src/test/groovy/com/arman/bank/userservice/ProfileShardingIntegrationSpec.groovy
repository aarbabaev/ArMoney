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

    def 'verified phone uniqueness audit and lookup quota remain global across physical shards'() {
        given:
        def left = UUID.randomUUID()
        def right = UUID.randomUUID()
        service.save(left, 'Primary', 'zz@example.test')
        service.save(right, 'East', 'ab@example.test')
        [left, right].each { service.changePhone(it, '+15550000007') }
        store.verifyPendingPhone(right, '+15550000007', 'synthetic-operator', 'case-shards')
        expect:
        service.resolvePhone(left, '+15550000007').get().identityId() == right
        count(primary, 'phone_verification_audit') == 1
        count(shard, 'phone_verification_audit') == 0
        when:
        store.verifyPendingPhone(left, '+15550000007', 'synthetic-operator', 'case-conflict')
        then:
        thrown(org.jooq.exception.DataAccessException)
        !service.find(left).get().phoneVerified()
        count(primary, 'phone_verification_audit') == 1
        when:
        (1..29).each { service.resolvePhone(left, '+15559999999') }
        service.resolvePhone(left, '+15550000007')
        then:
        thrown(LookupLimitExceeded)
        when:
        service.changePhone(right, '+15550000008')
        then:
        !service.resolvePhone(right, '+15550000007').present
        service.find(right).get().phoneNumber() == '+15550000008'
        count(primary, 'profiles') == 1
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
        when:
        def pinned = primary.transaction { it.fetchOne('select profile_id from profile_directory where identity_id = ?', owner).get(0, UUID) }
        primary.transaction { it.execute('drop trigger synthetic_confirmation_failure on profile_directory'); it.execute('drop function synthetic_reject_confirmation()') }
        def reconfigured = new ProfileService(new PostgresProfiles(new ProfileShards([primary: primary, east: shard], [ab: 'primary'], 'primary')))
        def recovered = reconfigured.save(owner, 'Recovered East', 'zz@example.test')
        then:
        recovered.id() == pinned
        recovered.displayName() == 'Recovered East'
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

    def 'phone revocation during remote shard read prevents stale recipient disclosure'() {
        given:
        def owner = UUID.randomUUID()
        def requester = UUID.randomUUID()
        service.save(owner, 'East Recipient', 'ab@example.test')
        service.changePhone(owner, '+15550000010')
        store.verifyPendingPhone(owner, '+15550000010', 'synthetic-operator', 'race-case')
        def connection = java.sql.DriverManager.getConnection(shardContainer.jdbcUrl, shardContainer.username, shardContainer.password)
        connection.autoCommit = false
        def statement = connection.createStatement()
        statement.execute('lock table profiles in access exclusive mode')
        def executor = Executors.newSingleThreadExecutor()
        when:
        def result = executor.submit({ service.resolvePhone(requester, '+15550000010') } as Callable)
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        boolean blocked = false
        while (!blocked && System.nanoTime() < deadline) {
            blocked = shard.transaction { it.fetchOne("select count(*) from pg_stat_activity where wait_event_type = 'Lock' and query like '%select * from profiles where identity_id%'").get(0, Integer) > 0 }
            if (!blocked) Thread.sleep(25)
        }
        assert blocked : 'Expected resolver shard read to block after directory snapshot'
        // Isolated fixture commits the same authoritative revocation as changePhone without waiting on the deliberately locked shard.
        primary.transaction { it.execute('update profile_directory set phone_number = ?, phone_verified = false where identity_id = ?', '+15550000011', owner) }
        connection.commit()
        then:
        !result.get(10, TimeUnit.SECONDS).present
        !service.find(owner).get().phoneVerified()
        cleanup:
        connection?.rollback(); statement?.close(); connection?.close(); executor?.shutdownNow()
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

