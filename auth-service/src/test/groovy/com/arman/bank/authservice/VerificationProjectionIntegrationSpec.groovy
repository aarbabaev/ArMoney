package com.arman.bank.authservice

import com.arman.bank.authservice.application.VerificationProjectionStore.AuthorityPort
import com.arman.bank.authservice.domain.VerificationProjection
import com.arman.bank.authservice.domain.VerificationProjection.*
import com.arman.bank.authservice.infrastructure.*
import com.arman.bank.runtime.Database
import org.jooq.DSLContext
import org.testcontainers.containers.PostgreSQLContainer
import spock.lang.Specification

import java.time.Instant
import java.time.OffsetDateTime
import java.util.concurrent.*
import java.util.function.Function

/** Real owning-auth PostgreSQL acceptance. qa_projection_reserved_authority is a
 * synthetic reserved-epoch supplier, never a production authority or ACTIVE backfill.
 * It must read/lock actual identity, VERIFIED enrollment and both current-purpose
 * receipts in the same supplied transaction. No user/financial success is mocked.
 */
class VerificationProjectionIntegrationSpec extends Specification {
    static final String WRITER = 'synthetic_auth'
    static final String PHONE = '+971501234567'
    PostgreSQLContainer postgres
    Database database
    PostgresVerificationProjectionStore store
    UUID fixtureIdentity = UUID.randomUUID()
    UUID enrollment = UUID.randomUUID()
    byte[] retention = ([31] * 32) as byte[]
    Map authorityBefore

    def setup() {
        postgres = new PostgreSQLContainer('postgres:17.6-alpine')
        postgres.start()
        database = new Database(postgres.jdbcUrl, postgres.username, postgres.password)
        database.transaction { sql ->
            sql.execute('insert into identities(id,email,password_hash) values (?,?,?)', fixtureIdentity, 'synthetic@example.test', 'synthetic-password-digest')
            sql.execute('''insert into verification_enrollments(id,issuer,subject,binding_digest,binding_key_id,phone,email,state,created_at,expires_at)
                values (?,?,?,?,?,?,?,'VERIFIED',statement_timestamp(),statement_timestamp()+interval '1 hour')''',
                enrollment, 'https://issuer.example/realm', 'synthetic-subject', new byte[32], 'test', PHONE, 'synthetic@example.test')
            ['REGISTRATION_EMAIL','REGISTRATION_PHONE'].each { purpose ->
                UUID challenge = UUID.randomUUID()
                sql.execute('''insert into verification_challenges(id,enrollment_id,purpose,contact_version,state,digest_key_id,created_at,expires_at,resend_at)
                    values (?,?,?,1,'CONSUMED','test',statement_timestamp(),statement_timestamp()+interval '5 minutes',statement_timestamp())''', challenge, enrollment, purpose)
                sql.execute('''insert into verification_receipts(id,enrollment_id,challenge_id,purpose,contact_version,created_at)
                    values (?,?,?, ?,1,statement_timestamp())''', UUID.randomUUID(), enrollment, challenge, purpose)
            }
            sql.execute('''create table qa_projection_reserved_authority(identity_id uuid primary key,
                enrollment_id uuid not null, epoch bigint not null, email_version integer not null, phone_version integer not null)''')
            sql.execute('insert into qa_projection_reserved_authority values (?,?,1,1,1)', fixtureIdentity, enrollment)
        }
        store = syntheticStore()
        authorityBefore = authoritySnapshot()
    }
    def cleanup() { database?.close(); postgres?.stop() }

    def 'actual production factory remains unavailable and cannot allocate or send despite valid database receipts'() {
        given:
        def config = new VerificationProjectionConfiguration([
            AUTH_VERIFICATION_PROJECTION_ENABLED:'true', AUTH_VERIFICATION_PROJECTION_WRITER_ID:WRITER,
            AUTH_TO_USER_VERIFICATION_PROJECTION_KEY:'synthetic-projection-transport-key-32-characters',
            AUTH_VERIFICATION_PROJECTION_RETENTION_KEY:Base64.encoder.encodeToString(retention),
            USER_BASE_URL:'http://127.0.0.1:1'])
        def production = config.store(database)
        when:
        production.allocate(WRITER, command())
        then:
        def failure = thrown(Failure)
        failure.error() == VerificationProjection.Error.UNAVAILABLE
        count('auth_verification_projection_commands') == 0
        count('auth_verification_projection_tombstones') == 0
        !production.claim(WRITER).present
        authoritySnapshot() == authorityBefore
    }

    def 'allocation durably captures reserved epoch receipt versions and canonical bytes before any network work'() {
        given:
        def input = command()
        when:
        def allocated = store.allocate(WRITER, input)
        then:
        allocated.state() == State.PENDING
        allocated.acknowledgement() == null
        database.transaction { sql ->
            def row = sql.fetchOne('select * from auth_verification_projection_commands')
            assert row.get('identity_id', UUID) == fixtureIdentity
            assert row.get('enrollment_id', UUID) == enrollment
            assert row.get('verification_epoch', Long) == 1L
            assert row.get('phone_contact_version', Integer) == 1
            assert row.get('email_contact_version', Integer) == 1
            assert row.get('email_receipt_id') != null
            assert row.get('phone_receipt_id') != null
            assert Arrays.equals(row.get('request_body', byte[]), HttpVerificationProjection.body(input))
            true
        }
        authoritySnapshot() == authorityBefore
        when:
        def lease = store.claim(WRITER).orElseThrow()
        byte[] mutableCopy = lease.requestBody()
        mutableCopy[0] = (byte) 0
        then:
        lease.command() == input
        Arrays.equals(lease.requestBody(), HttpVerificationProjection.body(input))
        !Arrays.equals(lease.requestBody(), mutableCopy)
    }

    def 'simultaneous exact allocation across two stores returns one durable pending operation'() {
        given:
        def input = command()
        def second = syntheticStore()
        when:
        def results = parallel((0..<8).collect { index -> { -> (index % 2 ? second : store).allocate(WRITER, input) } })
        then:
        results*.operationId().toSet() == ([input.operationId()] as Set)
        results.every { it.state() == State.PENDING }
        count('auth_verification_projection_commands') == 1
        count('auth_verification_projection_tombstones') == 1
        authoritySnapshot() == authorityBefore
    }

    def 'lost ACK requires identical retry after restart before successor and stale completion cannot win new fence'() {
        given:
        def input = command()
        store.allocate(WRITER, input)
        def first = store.claim(WRITER).orElseThrow()
        when: 'the remote outcome is unknown; no ACK is invented'
        store.finish(first, Result.retry())
        then:
        error { store.allocate(WRITER, command(1, 1)) } == VerificationProjection.Error.CONFLICT
        when:
        execute("update auth_verification_projection_commands set available_at=statement_timestamp()-interval '1 second'")
        reopen()
        def retry = store.claim(WRITER).orElseThrow()
        then:
        retry.command() == first.command()
        Arrays.equals(retry.requestBody(), first.requestBody())
        retry.fence() != first.fence()
        retry.attempts() == 2
        !store.finish(first, new Result(Outcome.ACK, ack(input)))
        store.finish(retry, new Result(Outcome.ACK, ack(input)))
        store.status(WRITER, input.operationId()).state() == State.ACKNOWLEDGED
        when:
        advanceEpoch(2)
        def successor = command(1, 2)
        then:
        store.allocate(WRITER, successor).state() == State.PENDING
        authoritySnapshot() == authorityBefore
    }

    def 'expired lease can be reclaimed across instances and only the current claim may finish'() {
        given:
        def input = command()
        store.allocate(WRITER, input)
        def old = store.claim(WRITER).orElseThrow()
        execute("update auth_verification_projection_commands set lease_until=statement_timestamp()-interval '1 second'")
        when:
        def current = syntheticStore().claim(WRITER).orElseThrow()
        then:
        current.fence() != old.fence()
        Arrays.equals(current.requestBody(), old.requestBody())
        !store.finish(old, new Result(Outcome.ACK, ack(input)))
        store.finish(current, new Result(Outcome.ACK, ack(input)))
        !store.finish(current, new Result(Outcome.ACK, ack(input)))
        authoritySnapshot() == authorityBefore
    }

    def 'old ACK after reserved epoch changes is historical superseded evidence and cannot activate changed authority'() {
        given:
        def input = command()
        store.allocate(WRITER, input)
        def lease = store.claim(WRITER).orElseThrow()
        advanceEpoch(2)
        when:
        boolean finished = store.finish(lease, new Result(Outcome.ACK, ack(input)))
        then:
        finished
        store.status(WRITER, input.operationId()).state() == State.SUPERSEDED
        store.status(WRITER, input.operationId()).acknowledgement() == ack(input)
        !store.claim(WRITER).present
        authoritySnapshot() == authorityBefore
        when:
        def successor = command(1, 2)
        then:
        store.allocate(WRITER, successor).state() == State.PENDING
        count('sessions') == 0
        count('sso_login_events') == 0
    }

    def 'missing actual phone receipt rejects synthetic authority before allocation'() {
        given:
        execute("delete from verification_receipts where purpose='REGISTRATION_PHONE'")
        def before = authoritySnapshot()
        expect:
        error { store.allocate(WRITER, command()) } == VerificationProjection.Error.CONFLICT
        count('auth_verification_projection_commands') == 0
        count('auth_verification_projection_tombstones') == 0
        authoritySnapshot() == before
    }

    def 'nonretry response persists terminal state across restart and preserves unresolved recovery barrier'() {
        given:
        def input = command()
        store.allocate(WRITER, input)
        def lease = store.claim(WRITER).orElseThrow()
        when:
        store.finish(lease, new Result(outcome, null))
        reopen()
        then:
        store.status(WRITER, input.operationId()).state() == expected
        !store.claim(WRITER).present
        database.transaction { it.fetchOne('select fence,lease_until from auth_verification_projection_commands').intoMap() } == [fence:null, lease_until:null]
        when:
        advanceEpoch(2)
        then:
        error { store.allocate(WRITER, command(1, 2)) } == VerificationProjection.Error.CONFLICT
        authoritySnapshot() == authorityBefore
        where:
        outcome              | expected
        Outcome.BAD_REQUEST  | State.DISABLED_BAD_REQUEST
        Outcome.UNAUTHORIZED | State.DISABLED_UNAUTHORIZED
        Outcome.CONFLICT     | State.CONFLICT
        Outcome.EXPIRED      | State.EXPIRED_REPLAY
    }

    def 'mismatched six-field ACK stays pending and rate limit delays identical retry at least sixty seconds'() {
        given:
        def input = command()
        store.allocate(WRITER, input)
        def lease = store.claim(WRITER).orElseThrow()
        def mismatched = new Ack(input.operationId(), input.identityId(), input.version(), input.phoneContactVersion(), 99, input.payloadHash())
        when:
        store.finish(lease, new Result(Outcome.ACK, mismatched))
        then:
        store.status(WRITER, input.operationId()).state() == State.PENDING
        !store.claim(WRITER).present
        when:
        execute("update auth_verification_projection_commands set available_at=statement_timestamp()-interval '1 second'")
        def retry = store.claim(WRITER).orElseThrow()
        store.finish(retry, new Result(Outcome.RATE_LIMITED, null))
        then:
        database.transaction { it.fetchOne("select available_at > clock_timestamp()+interval '59 seconds' as delayed from auth_verification_projection_commands").get('delayed', Boolean) }
        !store.claim(WRITER).present
        Arrays.equals(retry.requestBody(), lease.requestBody())
        authoritySnapshot() == authorityBefore
    }

    def 'full command capacity includes unresolved and terminal copies while exact pending replay stays available'() {
        given:
        def limited = syntheticStore(new Policy([WRITER] as Set, 1, 1))
        def input = command()
        limited.allocate(WRITER, input)
        expect:
        limited.allocate(WRITER, input).operationId() == input.operationId()
        when:
        advanceEpoch(2)
        then:
        error { limited.allocate(WRITER, command(1, 2)) } == VerificationProjection.Error.RATE_LIMITED
        when:
        limited.finish(limited.claim(WRITER).orElseThrow(), new Result(Outcome.UNAUTHORIZED, null))
        then:
        error { limited.allocate(WRITER, command(1, 2)) } == VerificationProjection.Error.RATE_LIMITED
        count('auth_verification_projection_commands') == 1
        count('auth_verification_projection_tombstones') == 1
    }

    def 'seven day unresolved expiry denies claim and erases full PII without removing durable identity recovery barrier'() {
        given:
        def input = command()
        store.allocate(WRITER, input)
        expireFullHistory()
        expect:
        error { store.status(WRITER, input.operationId()) } == VerificationProjection.Error.EXPIRED
        error { store.allocate(WRITER, input) } == VerificationProjection.Error.EXPIRED
        !store.claim(WRITER).present
        when:
        store.cleanup()
        reopen()
        then:
        count('auth_verification_projection_commands') == 0
        count('auth_verification_projection_tombstones') == 1
        database.transaction { sql ->
            def row = sql.fetchOne('select * from auth_verification_projection_tombstones')
            assert row.get('state') == 'EXPIRED_UNRESOLVED'
            assert row.get('recovery_barrier', Boolean)
            assert row.get('identity_digest', byte[]).length == 32
            assert !row.intoMap().keySet().any { it in ['identity_id','canonical_phone','request_body','acknowledgement'] }
            true
        }
        when:
        advanceEpoch(2)
        then:
        error { store.allocate(WRITER, command(1, 2)) } == VerificationProjection.Error.CONFLICT
        error { store.allocate(WRITER, input) } == VerificationProjection.Error.EXPIRED
        authoritySnapshot() == authorityBefore
    }

    def 'retention key change fails closed across new instances rather than losing identity equality barriers'() {
        given:
        def input = command()
        store.allocate(WRITER, input)
        def changed = new PostgresVerificationProjectionStore(database, policy(), ([41] * 32) as byte[], factory())
        expect:
        error { changed.status(WRITER, input.operationId()) } == VerificationProjection.Error.UNAVAILABLE
        error { changed.allocate(WRITER, input) } == VerificationProjection.Error.UNAVAILABLE
        count('auth_verification_projection_commands') == 1
        count('auth_verification_projection_tombstones') == 1
        authoritySnapshot() == authorityBefore
    }

    def 'simultaneous claimers across instances share one lease rather than duplicate remote work'() {
        given:
        store.allocate(WRITER, command())
        def second = syntheticStore()
        when:
        def claims = parallel((0..<8).collect { index -> { -> (index % 2 ? second : store).claim(WRITER) } })
        then:
        claims.count { it.present } == 1
        database.transaction { it.fetchOne('select attempts from auth_verification_projection_commands').get('attempts', Integer) } == 1
    }

    def 'replaced current receipt invalidates old ACK even when reserved epoch and phone are unchanged'() {
        given:
        def input = command()
        store.allocate(WRITER, input)
        def lease = store.claim(WRITER).orElseThrow()
        execute("delete from verification_receipts where purpose='REGISTRATION_EMAIL'")
        UUID challenge = UUID.randomUUID()
        execute('''insert into verification_challenges(id,enrollment_id,purpose,contact_version,state,digest_key_id,created_at,expires_at,resend_at)
            values (?,?,'REGISTRATION_EMAIL',1,'CONSUMED','test',statement_timestamp(),statement_timestamp()+interval '5 minutes',statement_timestamp())''', challenge, enrollment)
        execute('''insert into verification_receipts(id,enrollment_id,challenge_id,purpose,contact_version,created_at)
            values (?,?,?,'REGISTRATION_EMAIL',1,statement_timestamp())''', UUID.randomUUID(), enrollment, challenge)
        def changedAuthority = authoritySnapshot()
        when:
        store.finish(lease, new Result(Outcome.ACK, ack(input)))
        then:
        store.status(WRITER, input.operationId()).state() == State.SUPERSEDED
        authoritySnapshot() == changedAuthority
        count('sessions') == 0
        count('sso_login_events') == 0
    }

    def 'writer admission charges exact replay durably across instances and restart'() {
        given:
        def input = command()
        store.allocate(WRITER, input)
        execute("update auth_verification_projection_admission set credits=19,refilled_at=statement_timestamp()+interval '1 hour'")
        def second = syntheticStore()
        when:
        def results = parallel((0..<24).collect { index -> { ->
            try { (index % 2 ? second : store).allocate(WRITER, input) }
            catch (Failure rejected) { rejected.error() }
        } })
        then:
        results.count { it instanceof Status } == 19
        results.count(VerificationProjection.Error.RATE_LIMITED) == 5
        count('auth_verification_projection_commands') == 1
        when:
        reopen()
        then:
        error { store.status(WRITER, input.operationId()) } == VerificationProjection.Error.RATE_LIMITED
    }

    def 'cleanup purges at most one hundred aged full command copies and never evicts tombstone recovery metadata'() {
        given:
        def first = command()
        store.allocate(WRITER, first)
        // Explicit historical SUPERSEDED fixture records exercise bounded physical purge only.
        // They do not establish 100 successful provider operations or positive eligibility.
        database.transaction { sql ->
            (1..100).each { index ->
                def historical = command(index, index + 1L)
                sql.execute('''insert into auth_verification_projection_tombstones(writer_hash,operation_id,payload_hash,
                    identity_digest,version,phone_contact_version,verification_epoch,state,recovery_barrier,created_at,expires_at)
                    select writer_hash,?,?,identity_digest,?,phone_contact_version,?,'SUPERSEDED',false,created_at,expires_at
                    from auth_verification_projection_tombstones where operation_id=?''', historical.operationId(),
                    historical.payloadHash(), historical.version(), historical.verificationEpoch(), first.operationId())
                sql.execute('''insert into auth_verification_projection_commands(writer_hash,operation_id,identity_id,enrollment_id,
                    expected_version,version,canonical_phone,phone_contact_version,email_contact_version,verification_epoch,
                    email_receipt_id,phone_receipt_id,payload_hash,request_body,available_at,created_at,expires_at)
                    select writer_hash,?,identity_id,enrollment_id,?,?,canonical_phone,phone_contact_version,email_contact_version,?,
                    email_receipt_id,phone_receipt_id,?,?,available_at,created_at,expires_at
                    from auth_verification_projection_commands where operation_id=?''', historical.operationId(),
                    historical.expectedVersion(), historical.version(), historical.verificationEpoch(), historical.payloadHash(),
                    HttpVerificationProjection.body(historical), first.operationId())
            }
        }
        expireFullHistory()
        expect:
        !store.claim(WRITER).present
        when:
        store.cleanup()
        then:
        count('auth_verification_projection_commands') == 1
        count('auth_verification_projection_tombstones') == 101
        when:
        store.cleanup()
        then:
        count('auth_verification_projection_commands') == 0
        count('auth_verification_projection_tombstones') == 101
        database.transaction { it.fetchOne('select state,recovery_barrier from auth_verification_projection_tombstones where operation_id=?', first.operationId()).intoMap() } == [state:'EXPIRED_UNRESOLVED', recovery_barrier:true]
        authoritySnapshot() == authorityBefore
    }

    private Policy policy() { Policy.defaults([WRITER] as Set) }
    private PostgresVerificationProjectionStore syntheticStore(Policy selected = policy()) {
        new PostgresVerificationProjectionStore(database, selected, retention, factory())
    }
    private Function<DSLContext,AuthorityPort> factory() {
        ({ DSLContext sql -> ({ UUID identity, Instant now ->
            def row = sql.fetchOne('''select q.*,e.phone from qa_projection_reserved_authority q
                join identities i on i.id=q.identity_id join verification_enrollments e on e.id=q.enrollment_id
                where q.identity_id=? and e.state='VERIFIED' for update of q,i,e''', identity)
            if (row == null) throw new Failure(VerificationProjection.Error.CONFLICT)
            def receipts = sql.fetch('''select * from verification_receipts where enrollment_id=?
                and ((purpose='REGISTRATION_EMAIL' and contact_version=?) or
                     (purpose='REGISTRATION_PHONE' and contact_version=?)) for update''',
                row.get('enrollment_id', UUID), row.get('email_version', Integer), row.get('phone_version', Integer))
            def email = receipts.find { it.get('purpose') == 'REGISTRATION_EMAIL' }
            def phone = receipts.find { it.get('purpose') == 'REGISTRATION_PHONE' }
            if (receipts.size() != 2 || email == null || phone == null) throw new Failure(VerificationProjection.Error.CONFLICT)
            new Authority(identity, row.get('enrollment_id', UUID), row.get('phone', String),
                row.get('phone_version', Integer), row.get('email_version', Integer), row.get('epoch', Long),
                email.get('id', UUID), phone.get('id', UUID))
        } as AuthorityPort) } as Function)
    }
    private Command command(int expected = 0, long epoch = 1, UUID operation = UUID.randomUUID()) {
        VerificationProjection.create(operation, fixtureIdentity, expected, expected + 1, PHONE, 1, epoch)
    }
    private static Ack ack(Command input) { new Ack(input.operationId(), input.identityId(), input.version(), input.phoneContactVersion(), input.verificationEpoch(), input.payloadHash()) }
    private void advanceEpoch(long epoch) { execute('update qa_projection_reserved_authority set epoch=?', epoch) }
    private void expireFullHistory() {
        ['auth_verification_projection_commands','auth_verification_projection_tombstones'].each { table ->
            execute('update ' + table + " set created_at=statement_timestamp()-interval '8 days',expires_at=statement_timestamp()-interval '1 day'")
        }
    }
    private void reopen() {
        database.close()
        database = new Database(postgres.jdbcUrl, postgres.username, postgres.password)
        store = syntheticStore()
    }
    private void execute(String query, Object... values) { database.transaction { it.execute(query, values) } }
    private long count(String table) { database.transaction { it.fetchOne('select count(*) as n from ' + table).get('n', Long) } }
    private Map authoritySnapshot() {
        database.transaction { sql ->
            [identities:'id',verification_enrollments:'id',verification_receipts:'id',sessions:'token_hash',
             sso_login_events:'issuer,event_id',sso_login_proofs:'id'].collectEntries { table, ordering ->
                [(table):sql.fetch('select row_to_json(t)::text as snapshot from ' + table + ' t order by ' + ordering).getValues('snapshot', String)]
            }
        }
    }
    private static Object error(Closure action) {
        try { action.call(); throw new AssertionError('Expected a rejected synthetic projection operation') }
        catch (Failure failure) { failure.error() }
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
}
