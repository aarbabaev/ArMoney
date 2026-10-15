package com.arman.bank.authservice

import com.arman.bank.authservice.application.IdentityVerificationService
import com.arman.bank.authservice.application.IdentityVerificationStore
import com.arman.bank.authservice.application.VerificationProjectionStore.AuthorityPort
import com.arman.bank.authservice.domain.IdentityVerification.*
import com.arman.bank.authservice.domain.IdentityVerification
import com.arman.bank.authservice.domain.Verification
import com.arman.bank.authservice.domain.Verification.Purpose
import com.arman.bank.authservice.domain.Verification.Failure
import com.arman.bank.authservice.domain.VerificationProjection
import com.arman.bank.authservice.infrastructure.*
import com.arman.bank.runtime.Database
import org.jooq.DSLContext
import org.jooq.exception.DataAccessException
import org.testcontainers.containers.PostgreSQLContainer
import spock.lang.Specification
import java.time.*
import java.sql.DriverManager
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger
import java.util.function.Function

/** Local staging acceptance only. Observations are explicitly synthetic invocation
 * inputs, not proof of a real provider lookup. The fixture projection port reads the
 * actual owning identity/enrollment/current receipts; it does not create ACTIVE,
 * a bank session, a notice, a user-service commit or any production positive adapter.
 */
class IdentityVerificationIntegrationSpec extends Specification {
    static final String ISSUER = 'https://issuer.example/realm'
    static final String WRITER = 'synthetic_provider'
    static final String PHONE = '+971501234567'
    static final String EMAIL = 'synthetic@example.test'
    PostgreSQLContainer postgres
    Database database
    VerificationCrypto crypto
    PostgresIdentityVerificationStore store
    PostgresVerificationProjectionStore projections
    byte[] retention = ([41] * 32) as byte[]
    Flow flow = flow('subject', 'tab-one')

    def setup() {
        postgres = new PostgreSQLContainer('postgres:17.6-alpine')
        postgres.start()
        database = new Database(postgres.jdbcUrl, postgres.username, postgres.password)
        crypto = new VerificationCrypto('test', [test:([11] * 32) as byte[]], [test:([22] * 32) as byte[]])
        wire()
    }
    def cleanup() { database?.close(); postgres?.stop() }

    def 'unknown subject gets staging UUID only and even both receipts cannot materialize a bank identity or projection'() {
        given:
        def enrolled = enroll(flow)
        verified(enrolled.enrollmentId(), flow, Purpose.REGISTRATION_EMAIL)
        verified(enrolled.enrollmentId(), flow, Purpose.REGISTRATION_PHONE)
        def stagedAuthority = authority(enrolled.enrollmentId())
        expect:
        stagedAuthority.pending_identity_id instanceof UUID
        stagedAuthority.mapped_identity_id == null
        count('identities') == 0
        count('external_identities') == 0
        status(enrolled.enrollmentId(), flow).emailVerified()
        status(enrolled.enrollmentId(), flow).phoneVerified()
        when:
        prepare(enrolled.enrollmentId(), flow)
        then:
        def unavailable = thrown(Failure)
        unavailable.error() == Verification.Error.UNAVAILABLE
        count('identities') == 0
        count('auth_verification_projection_commands') == 0
        count('sessions') == 0
        count('sso_login_events') == 0
        authority(enrolled.enrollmentId()).state != 'ACTIVE'
    }

    def 'same enrollment keeps independent email and phone candidate versions and unchanged receipt on replacement'() {
        given:
        def enrolled = enroll(flow)
        def email = verified(enrolled.enrollmentId(), flow, Purpose.REGISTRATION_EMAIL)
        def beforeCandidate = candidates(enrolled.enrollmentId(), Purpose.REGISTRATION_EMAIL)
        when:
        def changed = enroll(flow, '+971521234567', EMAIL)
        then:
        changed.enrollmentId() == enrolled.enrollmentId()
        changed.emailContactVersion() == 1
        changed.phoneContactVersion() == 2
        changed.version() > enrolled.version()
        changed.emailVerified()
        !changed.phoneVerified()
        candidates(enrolled.enrollmentId(), Purpose.REGISTRATION_EMAIL) == beforeCandidate
        count('verification_receipts') == 1
        count('verification_authority_phone_claims') == 2
        database.transaction { it.fetchOne('select id from verification_receipts').get('id', UUID) } == email.receiptId()
        when: 'candidate history is immutable rather than rewritten in place'
        execute("update verification_candidates set canonical_value='changed@example.test' where purpose='REGISTRATION_EMAIL'")
        then:
        def immutable = thrown(DataAccessException)
        immutable.sqlState() == '23514'
        candidates(enrolled.enrollmentId(), Purpose.REGISTRATION_EMAIL) == beforeCandidate
    }

    def 'old challenge cannot create a current receipt after candidate replacement and new flow cannot reset purpose cooldown'() {
        given:
        def enrolled = enroll(flow)
        def opened = opened(enrolled.enrollmentId(), flow, Purpose.REGISTRATION_PHONE)
        def changed = enroll(flow, '+971521234567', EMAIL)
        when:
        def result = consume(new Consume(UUID.randomUUID(), enrolled.enrollmentId(), opened.challenge.challengeId(), flow,
            Purpose.REGISTRATION_PHONE, 1, opened.code), '+971521234567')
        then:
        result.error() == Verification.Error.CONFLICT
        count('verification_receipts') == 0
        !changed.phoneVerified()
        when:
        def other = flow('subject', 'tab-two')
        enroll(other, '+971521234567', EMAIL)
        def retry = issue(new Issue(UUID.randomUUID(), enrolled.enrollmentId(), other, Purpose.REGISTRATION_PHONE, 2), '+971521234567')
        then:
        retry.error() == Verification.Error.RATE_LIMITED
        count('verification_challenges') == 1
    }

    def 'exact lost enrollment response and consume retries are immutable while same UUID on another flow conflicts'() {
        given:
        UUID command = UUID.randomUUID()
        def first = enroll(flow, PHONE, EMAIL, command)
        def snapshot = durableSnapshot()
        when:
        def replay = enroll(flow, PHONE, EMAIL, command)
        then:
        replay == first
        durableSnapshot() == snapshot
        when:
        def open = opened(first.enrollmentId(), flow, Purpose.REGISTRATION_EMAIL)
        def consumeCommand = new Consume(UUID.randomUUID(),first.enrollmentId(),open.challenge.challengeId(),flow,Purpose.REGISTRATION_EMAIL,1,open.code)
        def consumed = consume(consumeCommand)
        def receiptSnapshot = durableSnapshot()
        then:
        consumed.error() == null
        consume(consumeCommand) == consumed
        durableSnapshot() == receiptSnapshot
        count('verification_receipts') == 1
        when:
        def other = flow('subject', 'tab-two')
        enroll(other)
        enroll(other, PHONE, EMAIL, command)
        then:
        def conflict = thrown(Failure)
        conflict.error() == Verification.Error.CONFLICT
    }

    def 'expired exact tuple and rotated HMAC cannot renew a permanent denial barrier or replay enrollment success'() {
        given:
        UUID command = UUID.randomUUID()
        Instant providerExpiry = now().plusSeconds(2)
        Instant started = admit(flow)
        def first = store.enroll(WRITER, new Enrollment(command, flow, PHONE, EMAIL), observation(flow, PHONE, EMAIL, providerExpiry, started), started)
        Instant originalExpiry = database.transaction { it.fetchOne('select expires_at from verification_flows').get('expires_at', OffsetDateTime).toInstant() }
        awaitDatabaseTime(originalExpiry)
        store.cleanup()
        expect:
        count('verification_flows') == 0
        count('verification_flow_barriers') == 1
        rejected { enroll(flow, PHONE, EMAIL, command) } == Verification.Error.UNAVAILABLE
        when:
        def rotated = new Flow(flow.issuer(), flow.subject(), flow.clientId(), flow.rootSessionId(), flow.tabId(), binding(9))
        then:
        rejected { enroll(rotated) } == Verification.Error.UNAVAILABLE
        count('verification_enrollments') == 1
        authority(first.enrollmentId()).pending_identity_id != null
        when:
        def changedKey = new PostgresIdentityVerificationStore(database, crypto, ([42] * 32) as byte[], policy(), projections)
        changedKey.admit(WRITER, flow)
        then:
        def unavailable = thrown(Failure)
        unavailable.error() == Verification.Error.UNAVAILABLE
    }

    def 'four live flows share unchanged receipts but fifth flow is denied before observation fanout'() {
        given:
        def enrolled = enroll(flow)
        verified(enrolled.enrollmentId(), flow, Purpose.REGISTRATION_EMAIL)
        (2..4).each { tab ->
            def additional = flow('subject', 'tab-' + tab)
            assert enroll(additional).enrollmentId() == enrolled.enrollmentId()
        }
        def fanout = new AtomicInteger()
        def service = new IdentityVerificationService(store, ({ Flow requested, Instant startedAt ->
            fanout.incrementAndGet(); observation(requested, PHONE, EMAIL, null, startedAt)
        } as IdentityVerificationStore.Observations), policy())
        when:
        service.enroll(WRITER, new Enrollment(UUID.randomUUID(), flow('subject','fifth'), PHONE, EMAIL))
        then:
        def denied = thrown(Failure)
        denied.error() == Verification.Error.RATE_LIMITED
        fanout.get() == 0
        count('verification_flows') == 4
        count('verification_receipts') == 1
    }

    def 'flow and barrier capacity are checked before observation and retained barriers are not evicted'() {
        given:
        enroll(flow)
        def limited = new PostgresIdentityVerificationStore(database, crypto, retention,
            new Policy([ISSUER] as Set, ['armoney-web','armoney-ios'] as Set, [WRITER] as Set, maxFlows, maxBarriers, 100), projections)
        def fanout = new AtomicInteger()
        def service = new IdentityVerificationService(limited, ({ Flow requested, Instant startedAt ->
            fanout.incrementAndGet(); observation(requested, PHONE, EMAIL, null, startedAt)
        } as IdentityVerificationStore.Observations), policy())
        when:
        service.enroll(WRITER, new Enrollment(UUID.randomUUID(), flow('another','fresh'), '+971521234567', 'other@example.test'))
        then:
        def denied = thrown(Failure)
        denied.error() == Verification.Error.RATE_LIMITED
        fanout.get() == 0
        count('verification_flow_barriers') == 1
        count('verification_enrollments') == 1
        where:
        maxFlows | maxBarriers
        1        | 40000
        40000    | 1
    }

    def 'hard existing enrollment expiry cannot be extended by old or new provider flow and does not release phone'() {
        given:
        def enrolled = enroll(flow)
        execute("update verification_authorities set created_at=statement_timestamp()-interval '25 hours',expires_at=statement_timestamp()-interval '1 hour'")
        execute("update verification_enrollments set created_at=statement_timestamp()-interval '25 hours',expires_at=statement_timestamp()-interval '1 hour'")
        def before = durableSnapshot()
        expect:
        rejected { status(enrolled.enrollmentId(), flow) } == Verification.Error.UNAVAILABLE
        rejected { enroll(flow('subject','new-root-tab')) } == Verification.Error.UNAVAILABLE
        durableSnapshot() == before
        count('verification_authority_phone_claims') == 1
    }

    def 'durable exhausted admission denies exact replay before external observation'() {
        given:
        UUID command = UUID.randomUUID()
        enroll(flow, PHONE, EMAIL, command)
        execute("update verification_authority_admission set credits=0,refilled_at=statement_timestamp()+interval '1 hour'")
        def fanout = new AtomicInteger()
        def service = new IdentityVerificationService(store, ({ Flow requested, Instant startedAt ->
            fanout.incrementAndGet(); observation(requested, PHONE, EMAIL, null, startedAt)
        } as IdentityVerificationStore.Observations), policy())
        when:
        service.enroll(WRITER,new Enrollment(command,flow,PHONE,EMAIL))
        then:
        def denied = thrown(Failure)
        denied.error() == Verification.Error.RATE_LIMITED
        fanout.get() == 0
        count('verification_authority_commands') == 1
    }

    def 'service forwards its admitted database anchor unchanged to observation and repository'() {
        given:
        List<Instant> anchors = []
        def service = new IdentityVerificationService(store, ({ Flow requested, Instant startedAt ->
            anchors.add(startedAt)
            observation(requested,PHONE,EMAIL,null,startedAt)
        } as IdentityVerificationStore.Observations), policy())
        when:
        def result = service.enroll(WRITER,new Enrollment(UUID.randomUUID(),flow,PHONE,EMAIL))
        then:
        anchors.size() == 1
        anchors.first() != null
        result.state() == 'PENDING_CONTACTS'
        result.error() == null
        count('identities') == 0
    }

    def 'observer cannot replace the independent admitted anchor with another otherwise fresh timestamp'() {
        given:
        def service = new IdentityVerificationService(store, ({ Flow requested, Instant startedAt ->
            // Still recent and non-future: rejection must bind the separate trusted anchor,
            // not merely validate freshness of the observation's own timestamp.
            observation(requested,PHONE,EMAIL,null,startedAt.minusNanos(1))
        } as IdentityVerificationStore.Observations), policy())
        when:
        service.enroll(WRITER,new Enrollment(UUID.randomUUID(),flow,PHONE,EMAIL))
        then:
        def denied = thrown(Failure)
        denied.error() == Verification.Error.UNAVAILABLE
        count('verification_authorities') == 0
        count('verification_authority_commands') == 0
        count('identities') == 0
    }

    def 'command capacity preserves exact replay and expired response cleanup never reuses its permanent command UUID'() {
        given:
        store = new PostgresIdentityVerificationStore(database,crypto,retention,
            new Policy([ISSUER] as Set,['armoney-web','armoney-ios'] as Set,[WRITER] as Set,40000,40000,1),projections)
        UUID command = UUID.randomUUID()
        def first = enroll(flow,PHONE,EMAIL,command)
        expect:
        enroll(flow,PHONE,EMAIL,command) == first
        rejected { issue(new Issue(UUID.randomUUID(),first.enrollmentId(),flow,Purpose.REGISTRATION_EMAIL,1)) } == Verification.Error.RATE_LIMITED
        count('verification_challenges') == 0
        when:
        execute("update verification_authority_commands set created_at=statement_timestamp()-interval '8 days',expires_at=statement_timestamp()-interval '1 day'")
        store.cleanup()
        then:
        count('verification_authority_commands') == 1
        database.transaction { it.fetchOne('select response from verification_authority_commands').get('response') } == null
        rejected { enroll(flow,PHONE,EMAIL,command) } == Verification.Error.UNAVAILABLE
        count('verification_enrollments') == 1
    }

    def 'mapped identity projection reserves epoch and V8 command atomically then survives response loss and restart'() {
        given:
        UUID mapped = mapSubject(flow)
        def enrolled = enroll(flow)
        verified(enrolled.enrollmentId(), flow, Purpose.REGISTRATION_EMAIL)
        verified(enrolled.enrollmentId(), flow, Purpose.REGISTRATION_PHONE)
        UUID request = UUID.randomUUID()
        def identityBefore = bankSnapshot()
        when:
        def pending = prepare(enrolled.enrollmentId(), flow, request)
        def captured = durableSnapshot()
        reopen()
        def replay = prepare(enrolled.enrollmentId(), flow, request)
        then:
        replay.operationId() == pending.operationId()
        replay.state() == VerificationProjection.State.PENDING
        durableSnapshot() == captured
        count('auth_verification_projection_commands') == 1
        authority(enrolled.enrollmentId()).mapped_identity_id == mapped
        authority(enrolled.enrollmentId()).projection_revision == 1
        bankSnapshot() == identityBefore
    }

    def 'fault between epoch reservation and V8 insertion rolls back the entire local prepare'() {
        given:
        mapSubject(flow)
        def enrolled = enroll(flow)
        verified(enrolled.enrollmentId(), flow, Purpose.REGISTRATION_EMAIL)
        verified(enrolled.enrollmentId(), flow, Purpose.REGISTRATION_PHONE)
        def before = durableSnapshot()
        execute('''create function qa_fail_authority_projection() returns trigger language plpgsql as $$
            begin raise exception 'synthetic precommit fault'; end $$''')
        execute('''create trigger qa_fail_authority_projection before insert on auth_verification_projection_commands
            for each row execute function qa_fail_authority_projection()''')
        when:
        prepare(enrolled.enrollmentId(), flow)
        then:
        thrown(DataAccessException)
        durableSnapshot() == before
        count('auth_verification_projection_commands') == 0
        count('auth_verification_projection_tombstones') == 0
    }

    def 'matching synthetic ACK only marks PROJECTED and exact attachment never creates ACTIVE sessions or notices'() {
        given:
        mapSubject(flow)
        def enrolled = enroll(flow)
        verified(enrolled.enrollmentId(), flow, Purpose.REGISTRATION_EMAIL)
        verified(enrolled.enrollmentId(), flow, Purpose.REGISTRATION_PHONE)
        prepare(enrolled.enrollmentId(), flow)
        def claim = projections.claim(WRITER).orElseThrow()
        def result = new VerificationProjection.Result(VerificationProjection.Outcome.ACK, ack(claim.command()))
        def before = bankSnapshot()
        when:
        boolean attached = store.attach(WRITER, claim, result)
        then:
        attached
        authority(enrolled.enrollmentId()).state == 'PROJECTED'
        status(enrolled.enrollmentId(), flow).state() == 'PROJECTED'
        store.attach(WRITER, claim, result)
        bankSnapshot() == before
        count('sessions') == 0
        count('sso_login_events') == 0
    }

    def 'contact replacement invalidates an old ACK and cannot project a different current candidate'() {
        given:
        mapSubject(flow)
        def enrolled = enroll(flow)
        verified(enrolled.enrollmentId(), flow, Purpose.REGISTRATION_EMAIL)
        verified(enrolled.enrollmentId(), flow, Purpose.REGISTRATION_PHONE)
        prepare(enrolled.enrollmentId(), flow)
        def claim = projections.claim(WRITER).orElseThrow()
        enroll(flow, PHONE, 'replacement@example.test')
        def before = snapshot([verification_authorities:'enrollment_id',verification_enrollments:'id',
            verification_candidates:'id',verification_receipts:'id',verification_authority_commands:'requester_digest,command_id',
            verification_authority_phone_claims:'phone',verification_flows:'context_fingerprint',verification_flow_barriers:'context_fingerprint'])
        def bankBefore = bankSnapshot()
        def commandBefore = database.transaction { it.fetchOne("select (to_jsonb(t)-'fence'-'lease_until')::text as frozen from auth_verification_projection_commands t").get('frozen',String) }
        def tombBefore = database.transaction { it.fetchOne("select (to_jsonb(t)-'state'-'recovery_barrier')::text as frozen from auth_verification_projection_tombstones t").get('frozen',String) }
        when:
        boolean processed = store.attach(WRITER, claim, new VerificationProjection.Result(VerificationProjection.Outcome.ACK, ack(claim.command())))
        then:
        // true denotes processing the old historical outcome, never current eligibility.
        processed
        authority(enrolled.enrollmentId()).state != 'PROJECTED'
        snapshot([verification_authorities:'enrollment_id',verification_enrollments:'id',
            verification_candidates:'id',verification_receipts:'id',verification_authority_commands:'requester_digest,command_id',
            verification_authority_phone_claims:'phone',verification_flows:'context_fingerprint',verification_flow_barriers:'context_fingerprint']) == before
        database.transaction { sql ->
            def tomb = sql.fetchOne('select state,recovery_barrier from auth_verification_projection_tombstones')
            assert tomb.get('state') == 'SUPERSEDED'
            assert !tomb.get('recovery_barrier',Boolean)
            assert sql.fetchOne("select (to_jsonb(t)-'state'-'recovery_barrier')::text as frozen from auth_verification_projection_tombstones t").get('frozen',String) == tombBefore
            assert sql.fetchOne("select (to_jsonb(t)-'fence'-'lease_until')::text as frozen from auth_verification_projection_commands t").get('frozen',String) == commandBefore
            def full = sql.fetchOne('select fence,lease_until from auth_verification_projection_commands')
            assert full.get('fence') == null
            assert full.get('lease_until') == null
            true
        }
        count('auth_verification_projection_commands') == 1
        count('auth_verification_projection_tombstones') == 1
        bankSnapshot() == bankBefore
        count('sessions') == 0
        count('sso_login_events') == 0
    }

    def 'current mapping reassignment cannot reuse the initially mapped identity for status or projection'() {
        given:
        mapSubject(flow)
        def enrolled = enroll(flow)
        UUID replacement = UUID.randomUUID()
        execute('insert into identities(id,email,password_hash) values (?,?,?)', replacement, 'other@example.test', 'synthetic-other-digest')
        execute('update external_identities set identity_id=? where issuer=? and subject=?', replacement, ISSUER, flow.subject())
        def before = bankSnapshot()
        expect:
        rejectedOrFalse { status(enrolled.enrollmentId(), flow) }
        count('auth_verification_projection_commands') == 0
        bankSnapshot() == before
    }

    def 'all six server tuple fields remain bound to invocation local evidence and cannot borrow another flow observation'() {
        given:
        def enrolled = enroll(flow)
        def before = durableSnapshot()
        Instant started = admit(flow)
        def evidence = observation(flow, PHONE, EMAIL, null, started)
        def variations = [
            new Flow(flow.issuer(), 'other-subject', flow.clientId(), flow.rootSessionId(), flow.tabId(), flow.authenticationSessionId()),
            new Flow(flow.issuer(), flow.subject(), 'armoney-ios', flow.rootSessionId(), flow.tabId(), flow.authenticationSessionId()),
            new Flow(flow.issuer(), flow.subject(), flow.clientId(), 'another-root', flow.tabId(), flow.authenticationSessionId()),
            new Flow(flow.issuer(), flow.subject(), flow.clientId(), flow.rootSessionId(), 'another-tab', flow.authenticationSessionId()),
            new Flow(flow.issuer(), flow.subject(), flow.clientId(), flow.rootSessionId(), flow.tabId(), binding(7)),
            new Flow('https://other.example/realm', flow.subject(), flow.clientId(), flow.rootSessionId(), flow.tabId(), flow.authenticationSessionId())]
        expect:
        variations.every { requested -> rejectedOrFalse { store.status(WRITER,enrolled.enrollmentId(),requested,evidence,started) } }
        durableSnapshot() == before
    }

    def 'concurrent exact mapped prepare reserves one epoch operation and V8 command'() {
        given:
        mapSubject(flow)
        def enrolled = enroll(flow)
        verified(enrolled.enrollmentId(), flow, Purpose.REGISTRATION_EMAIL)
        verified(enrolled.enrollmentId(), flow, Purpose.REGISTRATION_PHONE)
        UUID request = UUID.randomUUID()
        long priorEpoch = authority(enrolled.enrollmentId()).verification_epoch as long
        def executor = Executors.newFixedThreadPool(4)
        def gate = new CountDownLatch(1)
        when:
        def futures = (0..<4).collect { executor.submit({ gate.await(); prepare(enrolled.enrollmentId(), flow, request) } as Callable) }
        gate.countDown()
        def results = futures.collect { it.get(15,TimeUnit.SECONDS) }
        then:
        results*.operationId().toSet().size() == 1
        authority(enrolled.enrollmentId()).verification_epoch == priorEpoch + 1
        count('auth_verification_projection_commands') == 1
        count('auth_verification_projection_tombstones') == 1
        cleanup:
        executor?.shutdownNow(); executor?.awaitTermination(5,TimeUnit.SECONDS)
    }

    def 'row lock delay past five second observation bound denies before returning current status'() {
        given:
        def enrolled = enroll(flow)
        def connection = DriverManager.getConnection(postgres.jdbcUrl,postgres.username,postgres.password)
        connection.autoCommit = false
        def statement = connection.prepareStatement('select id from verification_enrollments where id=? for update')
        statement.setObject(1,enrolled.enrollmentId())
        def held = statement.executeQuery()
        assert held.next()
        Instant started = admit(flow)
        def evidence = observation(flow, PHONE, EMAIL, null, started)
        def executor = Executors.newSingleThreadExecutor()
        when:
        def future = executor.submit({
            try { store.status(WRITER,enrolled.enrollmentId(),flow,evidence,started) }
            catch(Failure failure) { failure.error() }
        } as Callable)
        long limit = System.nanoTime()+TimeUnit.SECONDS.toNanos(10)
        boolean waiting = false
        while(!waiting) {
            assert System.nanoTime()<limit
            assert !future.done
            waiting = database.transaction { it.fetchOne("select count(*) as n from pg_stat_activity where wait_event_type='Lock' and query like '%verification_enrollments%'").get('n',Long)>0 }
            if(!waiting) Thread.sleep(20)
        }
        while(now().isBefore(evidence.observationStartedAt().plusSeconds(6))) {
            assert System.nanoTime()<limit
            assert !future.done
            Thread.sleep(20)
        }
        connection.rollback()
        def result = future.get(5,TimeUnit.SECONDS)
        then:
        result == Verification.Error.UNAVAILABLE
        count('sessions') == 0
        cleanup:
        held?.close(); statement?.close(); connection?.rollback(); connection?.close()
        executor?.shutdownNow(); executor?.awaitTermination(5,TimeUnit.SECONDS)
    }

    def 'flow expiry during challenge row lock denies consume even while independent observation remains fresh'() {
        given:
        Instant initialAnchor = admit(flow)
        def enrollment = store.enroll(WRITER,new Enrollment(UUID.randomUUID(),flow,PHONE,EMAIL),
            observation(flow,PHONE,EMAIL,initialAnchor.plusSeconds(6),initialAnchor),initialAnchor)
        def open = opened(enrollment.enrollmentId(),flow,Purpose.REGISTRATION_EMAIL)
        Instant flowExpiry = database.transaction { it.fetchOne('select expires_at from verification_flows').get('expires_at',OffsetDateTime).toInstant() }
        def connection = DriverManager.getConnection(postgres.jdbcUrl,postgres.username,postgres.password)
        connection.autoCommit = false
        def statement = connection.prepareStatement('select id from verification_challenges where id=? for update')
        statement.setObject(1,open.challenge.challengeId())
        def held = statement.executeQuery()
        assert held.next()
        // A fresh provider observation may report a later deadline, but it cannot
        // extend the existing flow's immutable local TTL.
        awaitDatabaseTime(flowExpiry.minusSeconds(1))
        assert now().isBefore(flowExpiry)
        Instant started = admit(flow)
        def evidence = observation(flow,PHONE,EMAIL,null,started)
        def input = new Consume(UUID.randomUUID(),enrollment.enrollmentId(),open.challenge.challengeId(),flow,
            Purpose.REGISTRATION_EMAIL,1,open.code)
        def before = snapshot([verification_challenges:'id',verification_receipts:'id',
            verification_commands:'requester_digest,command_id',verification_authority_commands:'requester_digest,command_id',
            verification_authorities:'enrollment_id',verification_enrollments:'id',verification_candidates:'id',
            verification_delivery_outbox:'id'])
        def executor = Executors.newSingleThreadExecutor()
        when:
        def future = executor.submit({
            try { store.consume(WRITER,input,evidence,started) }
            catch(Failure failure) { failure.error() }
        } as Callable)
        long waitLimit = System.nanoTime()+TimeUnit.SECONDS.toNanos(3)
        boolean blocked = false
        while(!blocked) {
            assert System.nanoTime()<waitLimit
            assert !future.done
            blocked = database.transaction { it.fetchOne("select count(*) as n from pg_stat_activity where wait_event_type='Lock' and query like '%verification_challenges%'").get('n',Long)>0 }
            if(!blocked) Thread.sleep(20)
        }
        awaitDatabaseTime(started.plusSeconds(2))
        assert !now().isBefore(flowExpiry)
        assert now().isBefore(started.plusSeconds(5))
        assert evidence.sessionExpiresAt().isAfter(now())
        connection.rollback()
        def result = future.get(2,TimeUnit.SECONDS)
        then:
        result == Verification.Error.UNAVAILABLE
        now().isBefore(started.plusSeconds(5))
        snapshot([verification_challenges:'id',verification_receipts:'id',
            verification_commands:'requester_digest,command_id',verification_authority_commands:'requester_digest,command_id',
            verification_authorities:'enrollment_id',verification_enrollments:'id',verification_candidates:'id',
            verification_delivery_outbox:'id']) == before
        count('verification_receipts') == 0
        count('sessions') == 0
        cleanup:
        held?.close(); statement?.close(); connection?.rollback(); connection?.close()
        executor?.shutdownNow(); executor?.awaitTermination(5,TimeUnit.SECONDS)
    }

    private Policy policy() { Policy.defaults([ISSUER] as Set, ['armoney-web','armoney-ios'] as Set, [WRITER] as Set) }
    private void wire() {
        PostgresIdentityVerificationStore[] holder = new PostgresIdentityVerificationStore[1]
        projections = new PostgresVerificationProjectionStore(database,
            VerificationProjection.Policy.defaults([WRITER] as Set), retention, ({ DSLContext sql ->
                ({ UUID identity, Instant time -> holder[0].projectionAuthorityInTransaction(sql, identity, time) } as AuthorityPort)
            } as Function))
        store = new PostgresIdentityVerificationStore(database, crypto, retention, policy(), projections)
        holder[0] = store
    }
    private static String binding(int fill) { Base64.urlEncoder.withoutPadding().encodeToString(([fill] * 32) as byte[]) }
    private static Flow flow(String subject, String tab) { new Flow(ISSUER, subject, 'armoney-web', 'synthetic-root', tab, binding(3)) }
    private Instant now() { database.transaction { it.fetchOne('select clock_timestamp() as now').get('now', OffsetDateTime).toInstant() } }
    private Observation observation(Flow request, String phone = PHONE, String email = EMAIL, Instant expires = null, Instant startedAt = null) {
        def started = startedAt ?: now()
        new Observation(request, phone, email, phone, true, true, expires ?: started.plusSeconds(600), started)
    }
    private Instant admit(Flow request) { store.admit(WRITER, request) }
    private Verification.Result enroll(Flow request, String phone = PHONE, String email = EMAIL, UUID command = UUID.randomUUID()) {
        Instant started = admit(request)
        store.enroll(WRITER, new Enrollment(command, request, phone, email), observation(request, phone, email, null, started), started)
    }
    private Verification.Result status(UUID enrollment, Flow request, String phone = PHONE, String email = EMAIL) {
        Instant started = admit(request)
        store.status(WRITER, enrollment, request, observation(request, phone, email, null, started), started)
    }
    private Verification.Result issue(Issue input, String phone = PHONE, String email = EMAIL) {
        Instant started = admit(input.flow())
        store.issue(WRITER, input, observation(input.flow(), phone, email, null, started), started)
    }
    private Verification.Result consume(Consume input, String phone = PHONE, String email = EMAIL) {
        Instant started = admit(input.flow())
        store.consume(WRITER, input, observation(input.flow(), phone, email, null, started), started)
    }
    private Map opened(UUID enrollment, Flow request, Purpose purpose, int version = 1) {
        def result = issue(new Issue(UUID.randomUUID(), enrollment, request, purpose, version))
        assert result.error() == null
        def delivery = new PostgresVerificationStore(database, crypto)
        def lease = delivery.claimDelivery().orElseThrow()
        assert lease.challengeId() == result.challengeId()
        def payload = crypto.decrypt(lease.encrypted(), enrollment, lease.challengeId(), purpose, version)
        assert delivery.finishDelivery(lease.deliveryId(), lease.fence(), true)
        [challenge:result, code:payload.code()]
    }
    private Verification.Result verified(UUID enrollment, Flow request, Purpose purpose) {
        def open = opened(enrollment, request, purpose)
        def result = consume(new Consume(UUID.randomUUID(), enrollment, open.challenge.challengeId(), request, purpose, 1, open.code))
        assert result.error() == null
        result
    }
    private VerificationProjection.Status prepare(UUID enrollment, Flow request, UUID command = UUID.randomUUID()) {
        Instant started = admit(request)
        store.prepare(WRITER, command, enrollment, request, observation(request, PHONE, EMAIL, null, started), started)
    }
    private UUID mapSubject(Flow request) {
        UUID identity = UUID.randomUUID()
        execute('insert into identities(id,email,password_hash,registration_phone) values (?,?,?,?)', identity, EMAIL, 'synthetic-password-digest', PHONE)
        execute('insert into external_identities(issuer,subject,identity_id) values (?,?,?)', request.issuer(), request.subject(), identity)
        identity
    }
    private Map authority(UUID enrollment) { database.transaction { it.fetchOne('select * from verification_authorities where enrollment_id=?', enrollment).intoMap() } }
    private List candidates(UUID enrollment, Purpose purpose) { database.transaction { it.fetch('select * from verification_candidates where enrollment_id=? and purpose=? order by version', enrollment, purpose.name()).intoMaps() } }
    private long count(String table) { database.transaction { it.fetchOne('select count(*) as n from ' + table).get('n', Long) } }
    private void execute(String query, Object... values) { database.transaction { it.execute(query, values) } }
    private Map bankSnapshot() { snapshot([identities:'id',external_identities:'issuer,subject',sessions:'token_hash',sso_login_events:'issuer,event_id',sso_login_proofs:'id']) }
    private Map durableSnapshot() { snapshot([verification_enrollments:'id',verification_authorities:'enrollment_id',verification_candidates:'id',
        verification_authority_phone_claims:'phone',verification_flows:'context_fingerprint',verification_flow_barriers:'context_fingerprint',
        verification_authority_commands:'requester_digest,command_id',auth_verification_projection_commands:'writer_hash,operation_id',
        auth_verification_projection_tombstones:'writer_hash,operation_id']) }
    private Map snapshot(Map tables) { database.transaction { sql -> tables.collectEntries { table, order ->
        [(table):sql.fetch('select to_jsonb(t)::text as snapshot from ' + table + ' t order by ' + order).getValues('snapshot', String)] } } }
    private void reopen() { database.close(); database = new Database(postgres.jdbcUrl, postgres.username, postgres.password); wire() }
    private void awaitDatabaseTime(Instant deadline) {
        long limit = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (now().isBefore(deadline)) { assert System.nanoTime() < limit; Thread.sleep(20) }
    }
    private static Verification.Error rejected(Closure action) {
        try { action.call(); throw new AssertionError('Expected staging operation to be denied') }
        catch (Failure failure) { failure.error() }
    }
    private static boolean rejectedOrFalse(Closure action) {
        try { def result = action.call(); result == false || (result instanceof Verification.Result && result.error() != null) }
        catch (Failure ignored) { true }
        catch (VerificationProjection.Failure ignored) { true }
    }
    private static VerificationProjection.Ack ack(VerificationProjection.Command input) {
        new VerificationProjection.Ack(input.operationId(), input.identityId(), input.version(), input.phoneContactVersion(), input.verificationEpoch(), input.payloadHash())
    }
}
