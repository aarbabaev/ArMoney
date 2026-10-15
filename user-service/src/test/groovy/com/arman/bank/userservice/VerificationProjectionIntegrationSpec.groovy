package com.arman.bank.userservice

import com.arman.bank.runtime.Database
import com.arman.bank.userservice.application.RegistrationConflict
import com.arman.bank.userservice.application.VerificationProjectionStore.Rejected
import com.arman.bank.userservice.domain.Profile
import com.arman.bank.userservice.domain.VerificationProjection
import com.arman.bank.userservice.infrastructure.*
import org.testcontainers.containers.PostgreSQLContainer
import spock.lang.Specification

import java.sql.DriverManager
import java.time.OffsetDateTime
import java.util.concurrent.*

/** Disposable PostgreSQL protocol acceptance. A central projection ACK is never an
 * auth receipt, ACTIVE decision, operator attestation or financial success.
 * Production Main wiring and scheduling are outside this fixture.
 */
class VerificationProjectionIntegrationSpec extends Specification {
    static final String WRITER = 'synthetic-auth'
    static final String P = '+971501234567'
    static final String Q = '+971521234567'
    static final String R = '+971541234567'
    PostgreSQLContainer postgres
    Database database
    PostgresVerificationProjectionStore projections
    PostgresProfiles profiles

    def setup() {
        postgres = new PostgreSQLContainer('postgres:17.6-alpine')
        postgres.start()
        database = new Database(postgres.jdbcUrl, postgres.username, postgres.password)
        projections = new PostgresVerificationProjectionStore(database)
        profiles = new PostgresProfiles(database)
    }
    def cleanup() { database?.close(); postgres?.stop() }

    def 'lost response exact replay after newer revision and pool restart returns old ACK without old phone restoration'() {
        given:
        UUID owner = UUID.randomUUID()
        def first = command(owner, 0, P, 1, 10)
        def oldAck = commit(first)
        def second = command(owner, 1, Q, 2, 11)
        commit(second)
        def before = state()
        database.close()
        database = new Database(postgres.jdbcUrl, postgres.username, postgres.password)
        projections = new PostgresVerificationProjectionStore(database)
        when:
        def replay = commit(first)
        then:
        replay == oldAck
        replay == first.ack()
        state() == before
        current(owner).canonical_phone == Q
        count('profiles') == 0
        count('profile_directory') == 0
    }

    def 'concurrent exact replays across store instances commit one ACK claim and tombstone'() {
        given:
        def input = command(UUID.randomUUID(), 0, P, 1, 1)
        def other = new PostgresVerificationProjectionStore(database)
        when:
        def results = parallel((0..<8).collect { index -> { -> commit(input, index % 2 ? other : projections) } })
        then:
        results.every { it == input.ack() }
        count('verification_projection_current') == 1
        count('registration_phone_claims') == 1
        count('verification_projection_operations') == 1
        count('verification_projection_tombstones') == 1
    }

    def 'same writer operation changed payload and competing revision cannot mutate committed state'() {
        given:
        UUID owner = UUID.randomUUID()
        def first = command(owner, 0, P, 1, 5)
        commit(first)
        def before = state()
        expect:
        reject { commit(command(owner, 0, Q, 1, 5, first.operationId())) } == 409
        reject { commit(command(owner, 0, Q, 2, 6)) } == 409
        state() == before
    }

    def 'higher revisions reject old epoch changed same-phone version and stale changed-phone versions atomically'() {
        given:
        UUID owner = UUID.randomUUID()
        commit(command(owner, 0, P, 3, 10))
        def before = state()
        expect:
        [command(owner, 1, Q, 4, 10), command(owner, 1, Q, 4, 9),
         command(owner, 1, P, 4, 11), command(owner, 1, Q, 3, 11),
         command(owner, 1, Q, 2, 11)].every { value -> reject { commit(value) } == 409 }
        state() == before
        when: 'email-only epoch refresh keeps the independent phone version'
        def refreshed = command(owner, 1, P, 3, 11)
        then:
        commit(refreshed) == refreshed.ack()
        current(owner).phone_contact_version == 3
        current(owner).verification_epoch == 11L
    }

    def 'two identities racing one phone cannot both reserve ownership'() {
        given:
        def a = command(UUID.randomUUID(), 0, P, 1, 1)
        def b = command(UUID.randomUUID(), 0, P, 1, 1)
        when:
        def results = parallel([{ -> attempt { commit(a) } }, { -> attempt { commit(b) } }])
        then:
        results.count { it instanceof VerificationProjection.Ack } == 1
        results.count(409) == 1
        count('registration_phone_claims') == 1
        count('verification_projection_current') == 1
        count('verification_projection_operations') == 1
        count('verification_projection_tombstones') == 1
    }

    def 'physical PRIMARY P to Q resets both flags and frees verified P for a new legitimate legacy owner'() {
        given:
        UUID owner = UUID.randomUUID()
        def original = legacyProfile(owner, P, true)
        def placement = directory(owner).subMap(['identity_id','profile_id','shard_id','initialized'])
        commit(command(owner, 0, P, 1, 1))
        expect:
        physical(owner).phone_verified
        directory(owner).phone_verified
        when:
        commit(command(owner, 1, Q, 2, 2))
        then:
        physical(owner).phone_number == Q
        !physical(owner).phone_verified
        directory(owner).phone_number == Q
        !directory(owner).phone_verified
        directory(owner).subMap(['identity_id','profile_id','shard_id','initialized']) == placement
        profiles.find(owner).orElseThrow().id() == original.id()
        when: 'the V3 unique verified phone index must no longer reserve obsolete P'
        UUID successor = UUID.randomUUID()
        legacyProfile(successor, P, true)
        then:
        physical(successor).phone_number == P
        physical(successor).phone_verified
        profiles.resolvePhone(UUID.randomUUID(), P).orElseThrow().identityId() == successor
        profiles.resolvePhone(UUID.randomUUID(), Q).orElseThrow().identityId() == owner
    }

    def 'NULL phone resets false same phone preserves true and uninitialized placement is not initialized or relocated'() {
        given:
        UUID empty = UUID.randomUUID()
        profiles.save(new Profile(UUID.randomUUID(), empty, 'Synthetic Empty'))
        when:
        commit(command(empty, 0, P, 1, 1))
        then:
        physical(empty).phone_number == P
        !physical(empty).phone_verified
        !directory(empty).phone_verified
        when:
        UUID verified = UUID.randomUUID()
        legacyProfile(verified, Q, true)
        commit(command(verified, 0, Q, 1, 1))
        commit(command(verified, 1, Q, 1, 2))
        then:
        physical(verified).phone_verified
        directory(verified).phone_verified
        when:
        UUID pending = UUID.randomUUID()
        UUID profileId = UUID.randomUUID()
        execute("insert into profile_directory(identity_id,profile_id,shard_id) values (?,?,'primary')", pending, profileId)
        commit(command(pending, 0, R, 1, 1))
        then:
        !directory(pending).initialized
        directory(pending).profile_id == profileId
        directory(pending).shard_id == 'primary'
        database.transaction { it.fetchOne('select id from profiles where identity_id=?', pending) } == null
    }

    def 'missing initialized PRIMARY row or conflicting physical phone rolls back all projection state'() {
        given:
        UUID missing = UUID.randomUUID()
        execute("insert into profile_directory(identity_id,profile_id,shard_id,initialized) values (?,?,'primary',true)", missing, UUID.randomUUID())
        def beforeMissing = state()
        expect:
        reject { commit(command(missing, 0, P, 1, 1)) } == 409
        state() == beforeMissing
        when:
        UUID inconsistent = UUID.randomUUID()
        legacyProfile(inconsistent, Q, false)
        execute('update profiles set phone_number=? where identity_id=?', R, inconsistent)
        def beforePhysical = state()
        then:
        reject { commit(command(inconsistent, 0, Q, 1, 1)) } == 409
        state() == beforePhysical
    }

    def 'stale operator phone-only attestation rejects after projected P to Q to P and cannot add audit evidence'() {
        given:
        UUID owner = UUID.randomUUID()
        legacyProfile(owner, P, false)
        commit(command(owner, 0, P, 1, 1))
        commit(command(owner, 1, Q, 2, 2))
        expect:
        reject { commit(command(owner, 2, P, 1, 3)) } == 409
        when: 'even a genuinely new P contact version does not authorize the old operator path'
        commit(command(owner, 2, P, 3, 3))
        def before = state()
        profiles.verifyPendingPhone(owner, P, 'synthetic-operator', 'synthetic-old-evidence')
        then:
        thrown(IllegalStateException)
        state() == before
        count('phone_verification_audit') == 0
        !directory(owner).phone_verified
        !physical(owner).phone_verified
    }

    def 'registerPhone and stale save cannot restore old P after Q and new profile creation consumes current claim'() {
        given:
        UUID owner = UUID.randomUUID()
        def saved = legacyProfile(owner, P, false)
        commit(command(owner, 0, P, 1, 1))
        commit(command(owner, 1, Q, 2, 2))
        when:
        profiles.registerPhone(owner, P)
        then:
        thrown(RegistrationConflict)
        when:
        profiles.registerPhone(owner, Q)
        def stale = profiles.save(new Profile(saved.id(), owner, 'Synthetic Rename', P, true))
        then:
        stale.phoneNumber() == Q
        !stale.phoneVerified()
        physical(owner).phone_number == Q
        when:
        UUID fresh = UUID.randomUUID()
        commit(command(fresh, 0, R, 1, 1))
        def created = profiles.save(new Profile(UUID.randomUUID(), fresh, 'Synthetic New'))
        then:
        created.phoneNumber() == R
        !created.phoneVerified()
        directory(fresh).initialized
    }

    def 'registration competing with first projection and save competing with replacement cannot leave split ownership'() {
        given:
        UUID owner = UUID.randomUUID()
        def input = command(owner, 0, Q, 1, 1)
        when:
        def results = parallel([{ -> attempt { commit(input) } },
            { -> try { profiles.registerPhone(owner, P); 'REGISTERED' } catch (RegistrationConflict ignored) { 'CONFLICT' } }])
        then:
        if (results[0] instanceof VerificationProjection.Ack) {
            assert results[1] == 'CONFLICT'
            assert current(owner).canonical_phone == Q
        } else {
            assert results[0] == 409
            assert results[1] == 'REGISTERED'
            assert current(owner) == null
        }
        when:
        UUID placed = UUID.randomUUID()
        def original = legacyProfile(placed, R, false)
        commit(command(placed, 0, R, 1, 1))
        def replacement = command(placed, 1, Q, 2, 2)
        // A new owner is used if Q was won by the first projection above.
        if (current(owner) != null) replacement = command(placed, 1, P, 2, 2)
        def chosen = replacement
        def updates = parallel([{ -> commit(chosen) }, { -> profiles.save(new Profile(original.id(), placed, 'Synthetic Concurrent', R, true)) }])
        then:
        updates[0] == chosen.ack()
        profiles.find(placed).orElseThrow().phoneNumber() == chosen.canonicalPhone()
        physical(placed).phone_number == chosen.canonicalPhone()
        directory(placed).phone_number == chosen.canonicalPhone()
        !physical(placed).phone_verified
    }

    def 'save blocked in real shard IO rereads newer central projection and leaves shard physical phone untouched'() {
        given:
        def shardContainer = new PostgreSQLContainer('postgres:17.6-alpine')
        shardContainer.start()
        def shard = new Database(shardContainer.jdbcUrl, shardContainer.username, shardContainer.password)
        def pools = new ProfileShards([primary: database, east: shard], [ab: 'east'], 'primary')
        def sharded = new PostgresProfiles(pools)
        UUID owner = UUID.randomUUID()
        sharded.registerPhone(owner, P)
        def saved = sharded.save(new Profile(UUID.randomUUID(), owner, 'Synthetic East'), 'ab@example.test')
        commit(command(owner, 0, P, 1, 1))
        def locked = DriverManager.getConnection(shardContainer.jdbcUrl, shardContainer.username, shardContainer.password)
        locked.autoCommit = false
        def statement = locked.prepareStatement('select id from profiles where identity_id=? for update')
        statement.setObject(1, owner)
        def lockedRows = statement.executeQuery()
        assert lockedRows.next()
        def executor = Executors.newSingleThreadExecutor()
        when:
        def future = executor.submit({ sharded.save(new Profile(saved.id(), owner, 'Synthetic Delayed', P, true), 'ab@example.test') } as Callable)
        awaitBlockedSave(shard)
        commit(command(owner, 1, Q, 2, 2)) // Must finish while physical shard IO remains blocked.
        locked.rollback()
        def response = future.get(15, TimeUnit.SECONDS)
        then:
        response.phoneNumber() == Q
        !response.phoneVerified()
        sharded.find(owner).orElseThrow().phoneNumber() == Q
        shard.transaction { it.fetchOne('select phone_number from profiles where identity_id=?', owner).get('phone_number') } == null
        directory(owner).shard_id == 'east'
        directory(owner).profile_id == saved.id()
        cleanup:
        lockedRows?.close(); statement?.close(); locked?.rollback(); locked?.close()
        executor?.shutdownNow(); executor?.awaitTermination(5, TimeUnit.SECONDS)
        shard?.close(); shardContainer?.stop()
    }

    def 'retained record and permanent tombstone capacities reject new operations but preserve exact ACK'() {
        given:
        def limited = new PostgresVerificationProjectionStore(database, 1, 1)
        def first = command(UUID.randomUUID(), 0, P, 1, 1)
        commit(first, limited)
        def before = state()
        expect:
        commit(first, limited) == first.ack()
        reject { commit(command(UUID.randomUUID(), 0, Q, 1, 1), limited) } == 429
        state() == before
    }

    def 'late database insertion failure rolls back physical central claim current revision and tombstone together'() {
        given:
        UUID owner = UUID.randomUUID()
        legacyProfile(owner, P, true)
        commit(command(owner, 0, P, 1, 1))
        def before = state()
        execute('''create function qa_fail_projection_history() returns trigger language plpgsql as $$
            begin raise exception 'synthetic transaction fault'; end $$''')
        execute('''create trigger qa_fail_projection_history before insert on verification_projection_operations
            for each row execute function qa_fail_projection_history()''')
        expect:
        reject { commit(command(owner, 1, Q, 2, 2)) } == 503
        state() == before
        physical(owner).phone_number == P
        physical(owner).phone_verified
    }

    def 'expired historical ACKs are purged at most one hundred while permanent tombstones reject same and changed bodies'() {
        given:
        // Insert explicitly aged immutable history rather than weakening its UPDATE/DELETE triggers
        // or pretending a seven-day wait happened. Only cleanup/replay behavior is under test here.
        def history = (0..<101).collect { command(UUID.randomUUID(), 0, P, 1, 1) }
        def expired = OffsetDateTime.now(java.time.ZoneOffset.UTC).minusDays(1)
        database.transaction { sql ->
            history.each { input ->
                sql.execute('''insert into verification_projection_tombstones(writer_id,operation_id,payload_hash,expires_at)
                    values (?,?,?,cast(? as timestamptz))''', WRITER, input.operationId(), input.payloadHash(), expired)
                sql.execute('''insert into verification_projection_operations(writer_id,operation_id,identity_id,
                    expected_version,version,canonical_phone,phone_contact_version,verification_epoch,payload_hash,expires_at)
                    values (?,?,?,?,?,?,?,?,?,cast(? as timestamptz))''', WRITER, input.operationId(), input.identityId(),
                    input.expectedVersion(), input.version(), input.canonicalPhone(), input.phoneContactVersion(),
                    input.verificationEpoch(), input.payloadHash(), expired)
            }
        }
        expect:
        reject { commit(history.first()) } == 410
        when:
        int removed = projections.cleanup()
        then:
        removed == 100
        count('verification_projection_operations') == 1
        count('verification_projection_tombstones') == 101
        [history.first(), history.last()].every { input -> reject { commit(input) } == 410 }
        when:
        int remainder = projections.cleanup()
        def old = history.first()
        then:
        remainder == 1
        count('verification_projection_operations') == 0
        count('verification_projection_tombstones') == 101
        reject { commit(command(old.identityId(), 0, Q, 2, 2, old.operationId())) } == 410
        count('registration_phone_claims') == 0
        count('verification_projection_current') == 0
    }

    private VerificationProjection command(UUID owner, int expected, String phone, int contact, long epoch, UUID operation = UUID.randomUUID()) {
        new VerificationProjection(operation, owner, expected, expected + 1, phone, contact, epoch,
            VerificationProjection.hash(operation, owner, expected, expected + 1, phone, contact, epoch))
    }
    private VerificationProjection.Ack commit(VerificationProjection input, PostgresVerificationProjectionStore target = projections) {
        assert target.admit(WRITER)
        target.commit(WRITER, input)
    }
    private Profile legacyProfile(UUID owner, String phone, boolean verified) {
        profiles.registerPhone(owner, phone)
        def result = profiles.save(new Profile(UUID.randomUUID(), owner, 'Synthetic Legacy'))
        if (verified) profiles.verifyPendingPhone(owner, phone, 'synthetic-operator', 'synthetic-legacy-evidence')
        profiles.find(owner).orElseThrow()
    }
    private Map current(UUID owner) { database.transaction { it.fetchOne('select * from verification_projection_current where identity_id=?', owner)?.intoMap() } }
    private Map directory(UUID owner) { database.transaction { it.fetchOne('select * from profile_directory where identity_id=?', owner)?.intoMap() } }
    private Map physical(UUID owner) { database.transaction { it.fetchOne('select * from profiles where identity_id=?', owner)?.intoMap() } }
    private long count(String table) { database.transaction { it.fetchOne('select count(*) as n from ' + table).get('n', Long) } }
    private void execute(String query, Object... values) { database.transaction { it.execute(query, values) } }
    private Map state() {
        // Fixed test identifiers only; admission budgets intentionally excluded because rejected requests consume them.
        database.transaction { sql ->
            [profiles:'identity_id',profile_directory:'identity_id',registration_phone_claims:'identity_id',
             verification_projection_current:'identity_id',verification_projection_operations:'writer_id,operation_id',
             verification_projection_tombstones:'writer_id,operation_id',phone_verification_audit:'id'].collectEntries { table, order ->
                [(table): sql.fetch('select row_to_json(t)::text as snapshot from ' + table + ' t order by ' + order).getValues('snapshot', String)]
            }
        }
    }
    private static Object attempt(Closure action) { try { action.call() } catch (Rejected rejected) { rejected.status() } }
    private static int reject(Closure action) {
        def result = attempt(action)
        assert result instanceof Integer
        result as int
    }
    private static List parallel(List<Closure> actions) {
        def executor = Executors.newFixedThreadPool(actions.size())
        try {
            def gate = new CountDownLatch(1)
            def futures = actions.collect { action -> executor.submit({ gate.await(); action.call() } as Callable) }
            gate.countDown()
            futures.collect { it.get(20, TimeUnit.SECONDS) }
        } finally { executor.shutdownNow(); assert executor.awaitTermination(5, TimeUnit.SECONDS) }
    }
    private static void awaitBlockedSave(Database shard) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (System.nanoTime() < deadline) {
            boolean waiting = shard.transaction { it.fetchOne("select count(*) as n from pg_stat_activity where datname=current_database() and wait_event_type='Lock' and query like '%insert into profiles%'").get('n', Long) > 0 }
            if (waiting) return
            Thread.sleep(20)
        }
        throw new AssertionError('Synthetic save did not reach the expected PostgreSQL row lock')
    }
}
