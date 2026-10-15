package com.arman.bank.authservice

import com.arman.bank.authservice.application.VerificationService
import com.arman.bank.authservice.domain.Verification.*
import com.arman.bank.authservice.infrastructure.*
import com.arman.bank.runtime.Database
import org.testcontainers.containers.PostgreSQLContainer
import spock.lang.Specification
import java.util.concurrent.*

/** Disposable databases only. Source supplied now; requires the orchestrator build lease to execute. */
class VerificationIntegrationSpec extends Specification {
    static final String ISSUER = 'https://issuer.example/realm'
    static final String PHONE = '+971501234567'
    PostgreSQLContainer postgres
    Database database
    VerificationCrypto crypto
    PostgresVerificationStore store
    VerificationService service
    Binding binding = new Binding(ISSUER, 'synthetic-subject', 'synthetic-server-session')

    def setup() {
        postgres = new PostgreSQLContainer('postgres:17.6-alpine')
        postgres.start()
        database = new Database(postgres.jdbcUrl, postgres.username, postgres.password)
        byte[] hmac = new byte[32], encryption = new byte[32]
        Arrays.fill(hmac, (byte) 11); Arrays.fill(encryption, (byte) 22)
        crypto = new VerificationCrypto('test', ['test': hmac], ['test': encryption])
        store = new PostgresVerificationStore(database, crypto)
        service = new VerificationService(store, [ISSUER] as Set)
    }
    def cleanup() {
        database?.close()
        postgres?.stop()
    }
    Result enroll(Binding requester = binding, String phone = PHONE, UUID command = UUID.randomUUID()) {
        service.enroll(new EnrollmentCommand(command, requester, phone, 'synthetic@example.test'))
    }
    ChallengeCommand issueCommand(UUID enrollment, Purpose purpose = Purpose.REGISTRATION_EMAIL) {
        new ChallengeCommand(UUID.randomUUID(), enrollment, binding, purpose, 1)
    }
    ConsumeCommand consumeCommand(UUID enrollment, UUID challenge, String code,
            Purpose purpose = Purpose.REGISTRATION_EMAIL, UUID command = UUID.randomUUID()) {
        new ConsumeCommand(command, enrollment, challenge, binding, purpose, 1, code)
    }
    Map opened(UUID enrollment, Purpose purpose = Purpose.REGISTRATION_EMAIL) {
        def challenge = service.issue(issueCommand(enrollment, purpose))
        def lease = store.claimDelivery().orElseThrow()
        assert lease.challengeId() == challenge.challengeId()
        def payload = crypto.decrypt(lease.encrypted(), enrollment, lease.challengeId(), purpose, 1)
        assert store.finishDelivery(lease.deliveryId(), lease.fence(), true)
        [challenge: challenge, lease: lease, payload: payload]
    }
    long count(String query, Object... values) {
        database.transaction { sql -> sql.fetchOne(query, values).get('n', Long) }
    }
    void execute(String query, Object... values) { database.transaction { sql -> sql.execute(query, values) } }
    static Object attempt(Closure operation) {
        try { operation.call() } catch (Failure failure) { failure.error().name() }
    }
    static List parallel(List<Closure> operations) {
        def executor = Executors.newFixedThreadPool(operations.size())
        try {
            def gate = new CountDownLatch(1)
            def futures = operations.collect { action -> executor.submit({ gate.await(); action.call() } as Callable) }
            gate.countDown()
            futures.collect { it.get(20, TimeUnit.SECONDS) }
        } finally {
            executor.shutdownNow()
            assert executor.awaitTermination(5, TimeUnit.SECONDS)
        }
    }

    def "enrollment replay survives new adapter and conflicting payload never allocates a banking identity"() {
        given:
        def command = UUID.randomUUID()
        def enrollment = enroll(binding, PHONE, command)
        def restarted = new VerificationService(new PostgresVerificationStore(database, crypto), [ISSUER] as Set)
        expect:
        restarted.enroll(new EnrollmentCommand(command, binding, PHONE, 'synthetic@example.test')) == enrollment
        attempt { restarted.enroll(new EnrollmentCommand(command, binding, '+971521234567', 'synthetic@example.test')) } == 'CONFLICT'
        count('select count(*) as n from identities') == 0
        count('select count(*) as n from external_identities') == 0
        count('select count(*) as n from verification_phone_claims') == 1
        enrollment.state() == 'PENDING_CONTACTS'
    }

    def "concurrent pending phone reservations produce one owner without email linking"() {
        when:
        def results = parallel([
            { attempt { enroll(binding) } },
            { attempt { enroll(new Binding(ISSUER, 'other-subject', 'other-session')) } }
        ])
        then:
        results.count { it instanceof Result } == 1
        results.count { it == 'CONFLICT' } == 1
        count('select count(*) as n from verification_phone_claims') == 1
        count('select count(*) as n from verification_enrollments') == 1
        count('select count(*) as n from identities') == 0
    }

    def "legacy phone and mapped identity are preserved and never inferred verified"() {
        given:
        def legacy = UUID.randomUUID()
        execute('insert into identities(id, registration_phone) values (?, ?)', legacy, PHONE)
        execute('insert into external_identities(issuer, subject, identity_id) values (?, ?, ?)', ISSUER, binding.subject(), legacy)
        expect:
        attempt { enroll() } == 'CONFLICT'
        count('select count(*) as n from identities where id = ? and registration_phone = ?', legacy, PHONE) == 1
        count('select count(*) as n from external_identities where identity_id = ?', legacy) == 1
        count('select count(*) as n from verification_receipts') == 0
        count('select count(*) as n from verification_enrollments') == 0
    }

    def "concurrent correct codes consume once and exact successful command replays its receipt"() {
        given:
        def enrollment = enroll().enrollmentId()
        def open = opened(enrollment)
        def first = consumeCommand(enrollment, open.challenge.challengeId(), open.payload.code())
        def second = consumeCommand(enrollment, open.challenge.challengeId(), open.payload.code())
        when:
        def results = parallel([{ attempt { service.consume(first) } }, { attempt { service.consume(second) } }])
        def success = results.find { it instanceof Result }
        def winner = success == results[0] ? first : second
        then:
        results.count { it instanceof Result } == 1
        results.count { it == 'CONFLICT' } == 1
        service.consume(winner) == success
        count('select count(*) as n from verification_receipts') == 1
        count('select count(*) as n from verification_challenges where state = ?', 'CONSUMED') == 1
    }

    def "wrong attempts commit under races and an exact failed command replay does not debit again"() {
        given:
        def enrollment = enroll().enrollmentId()
        def open = opened(enrollment)
        def wrong = open.payload.code() == '000000' ? '999999' : '000000'
        def replay = consumeCommand(enrollment, open.challenge.challengeId(), wrong)
        expect:
        attempt { service.consume(replay) } == 'CONFLICT'
        attempt { service.consume(replay) } == 'CONFLICT'
        count('select count(*) as n from verification_challenges where attempts = 1') == 1
        when:
        def results = parallel((1..4).collect { { attempt { service.consume(consumeCommand(enrollment, open.challenge.challengeId(), wrong)) } } })
        then:
        results.every { it == 'CONFLICT' }
        count('select count(*) as n from verification_challenges where attempts = 5 and state = ?', 'LOCKED') == 1
        attempt { service.consume(consumeCommand(enrollment, open.challenge.challengeId(), open.payload.code())) } == 'CONFLICT'
        count('select count(*) as n from verification_receipts') == 0
    }

    def "resend supersedes before delivery and stale delivery fence cannot reopen an old code"() {
        given:
        def enrollment = enroll().enrollmentId()
        def first = service.issue(issueCommand(enrollment))
        def lease = store.claimDelivery().orElseThrow()
        def payload = crypto.decrypt(lease.encrypted(), enrollment, first.challengeId(), Purpose.REGISTRATION_EMAIL, 1)
        expect:
        attempt { service.issue(issueCommand(enrollment)) } == 'RATE_LIMITED'
        attempt { service.consume(consumeCommand(enrollment, first.challengeId(), payload.code())) } == 'CONFLICT'
        count('select count(*) as n from verification_challenges where attempts = 0') == 1
        when:
        execute("update verification_challenges set created_at = clock_timestamp() - interval '61 seconds' where id = ?", first.challengeId())
        def replacement = service.issue(issueCommand(enrollment))
        then:
        replacement.challengeId() != first.challengeId()
        !store.finishDelivery(lease.deliveryId(), lease.fence(), true)
        attempt { service.consume(consumeCommand(enrollment, first.challengeId(), payload.code())) } == 'CONFLICT'
        count('select count(*) as n from verification_challenges where id = ? and state = ?', first.challengeId(), 'SUPERSEDED') == 1
        count('select count(*) as n from verification_delivery_outbox where challenge_id = ? and ciphertext is null and nonce is null', first.challengeId()) == 1
    }

    def "purpose version binding and payload-hash mismatch reject without consuming a valid challenge"() {
        given:
        def enrollment = enroll().enrollmentId()
        def open = opened(enrollment)
        def command = UUID.randomUUID()
        def wrong = open.payload.code() == '000000' ? '999999' : '000000'
        expect:
        attempt { service.status(enrollment, new Binding(ISSUER, binding.subject(), 'stolen-binding')) } == 'BINDING'
        attempt { service.consume(new ConsumeCommand(UUID.randomUUID(), enrollment, open.challenge.challengeId(), binding, Purpose.REGISTRATION_PHONE, 1, open.payload.code())) } == 'CONFLICT'
        attempt { service.consume(new ConsumeCommand(UUID.randomUUID(), enrollment, open.challenge.challengeId(), binding, Purpose.REGISTRATION_EMAIL, 2, open.payload.code())) } == 'CONFLICT'
        attempt { service.consume(consumeCommand(enrollment, open.challenge.challengeId(), wrong, Purpose.REGISTRATION_EMAIL, command)) } == 'CONFLICT'
        attempt { service.consume(consumeCommand(enrollment, open.challenge.challengeId(), open.payload.code(), Purpose.REGISTRATION_EMAIL, command)) } == 'CONFLICT'
        count('select count(*) as n from verification_challenges where attempts = 1 and state = ?', 'OPEN') == 1
        service.consume(consumeCommand(enrollment, open.challenge.challengeId(), open.payload.code())).receiptId() != null
    }

    def "database expiry fails closed and expired encrypted payload is erased"() {
        given:
        def enrollment = enroll().enrollmentId()
        def pending = service.issue(issueCommand(enrollment))
        def lease = store.claimDelivery().orElseThrow()
        def payload = crypto.decrypt(lease.encrypted(), enrollment, pending.challengeId(), Purpose.REGISTRATION_EMAIL, 1)
        execute('update verification_challenges set expires_at = clock_timestamp() where id = ?', pending.challengeId())
        execute('update verification_delivery_outbox set expires_at = clock_timestamp() where challenge_id = ?', pending.challengeId())
        expect:
        attempt { service.consume(consumeCommand(enrollment, pending.challengeId(), payload.code())) } == 'CONFLICT'
        !store.finishDelivery(lease.deliveryId(), lease.fence(), true)
        count('select count(*) as n from verification_challenges where state = ? and digest is null', 'EXPIRED') == 1
        count('select count(*) as n from verification_delivery_outbox where state = ? and ciphertext is null and nonce is null', 'EXPIRED') == 1
        count('select count(*) as n from verification_receipts') == 0
    }

    def "per-purpose hourly and daily budgets cannot be bypassed by resends or concurrent workers"() {
        given:
        def enrollment = enroll().enrollmentId()
        2.times {
            service.issue(issueCommand(enrollment))
            execute("update verification_challenges set created_at = clock_timestamp() - interval '61 seconds' where enrollment_id = ?", enrollment)
        }
        when:
        def results = parallel((1..3).collect { { attempt { service.issue(issueCommand(enrollment)) } } })
        then:
        results.count { it instanceof Result } == 1
        results.count { it == 'RATE_LIMITED' } == 2
        when:
        7.times {
            execute("update verification_challenges set created_at = clock_timestamp() - interval '2 hours' where enrollment_id = ?", enrollment)
            service.issue(issueCommand(enrollment))
        }
        execute("update verification_challenges set created_at = clock_timestamp() - interval '2 hours' where enrollment_id = ?", enrollment)
        then:
        attempt { service.issue(issueCommand(enrollment)) } == 'RATE_LIMITED'
        count('select count(*) as n from verification_challenges') == 10
        service.issue(issueCommand(enrollment, Purpose.REGISTRATION_PHONE)).challengeId() != null
    }

    def "delivery retry preserves code and ID fences stale worker and erases exhausted payload"() {
        given:
        def enrollment = enroll().enrollmentId()
        service.issue(issueCommand(enrollment))
        def first = store.claimDelivery().orElseThrow()
        def original = crypto.decrypt(first.encrypted(), enrollment, first.challengeId(), first.purpose(), 1)
        expect:
        store.finishDelivery(first.deliveryId(), first.fence(), false)
        store.claimDelivery().isEmpty()
        when:
        execute('update verification_delivery_outbox set available_at = clock_timestamp() where id = ?', first.deliveryId())
        def second = store.claimDelivery().orElseThrow()
        then:
        first.deliveryId() == second.deliveryId()
        first.fence() != second.fence()
        crypto.decrypt(second.encrypted(), enrollment, second.challengeId(), second.purpose(), 1) == original
        !store.finishDelivery(first.deliveryId(), first.fence(), true)
        store.finishDelivery(second.deliveryId(), second.fence(), false)
        when:
        execute('update verification_delivery_outbox set available_at = clock_timestamp() where id = ?', first.deliveryId())
        def third = store.claimDelivery().orElseThrow()
        execute('update verification_delivery_outbox set lease_until = clock_timestamp() where id = ?', third.deliveryId())
        store.expireDeliveryPayloads()
        then:
        store.claimDelivery().isEmpty()
        count('select count(*) as n from verification_delivery_outbox where state = ? and attempts = 3 and ciphertext is null', 'FAILED') == 1
        count('select count(*) as n from verification_challenges where state = ? and digest is null', 'DELIVERY_FAILED') == 1
    }

    def "durable delivery contains authenticated ciphertext rather than plaintext destination or code columns"() {
        given:
        def enrollment = enroll().enrollmentId()
        def issued = service.issue(issueCommand(enrollment))
        def lease = store.claimDelivery().orElseThrow()
        def payload = crypto.decrypt(lease.encrypted(), enrollment, lease.challengeId(), lease.purpose(), 1)
        expect:
        payload.destination() == 'synthetic@example.test'
        payload.code() ==~ /[0-9]{6}/
        count('select count(*) as n from verification_delivery_outbox where octet_length(nonce) = 12 and octet_length(ciphertext) > 16') == 1
        count('select count(*) as n from verification_challenges where octet_length(digest) = 32 and state = ?', 'PENDING_DELIVERY') == 1
        count("select count(*) as n from information_schema.columns where table_name in ('verification_delivery_outbox','verification_challenges','verification_commands') and column_name in ('code','destination','phone','email','payload','session')") == 0
        !lease.toString().contains(payload.code())
        !lease.toString().contains(payload.destination())
        when:
        // Command TTL expiry must reject, never silently issue a replacement irreversible operation.
        def command = issueCommand(enrollment, Purpose.REGISTRATION_PHONE)
        def once = service.issue(command)
        execute('update verification_commands set expires_at = clock_timestamp() where command_id = ?', command.commandId())
        then:
        attempt { service.issue(command) } == 'CONFLICT'
        count('select count(*) as n from verification_challenges where id = ?', once.challengeId()) == 1
    }

    def "both receipts produce verified pending eligibility without identities or projection acknowledgement"() {
        given:
        def enrollment = enroll().enrollmentId()
        def email = opened(enrollment)
        service.consume(consumeCommand(enrollment, email.challenge.challengeId(), email.payload.code()))
        def partial = service.status(enrollment, binding)
        expect:
        partial.emailVerified()
        !partial.phoneVerified()
        partial.state() == 'PENDING_CONTACTS'
        when:
        def phone = opened(enrollment, Purpose.REGISTRATION_PHONE)
        service.consume(consumeCommand(enrollment, phone.challenge.challengeId(), phone.payload.code(), Purpose.REGISTRATION_PHONE))
        def status = service.status(enrollment, binding)
        then:
        status.state() == 'VERIFIED'
        status.emailVerified() && status.phoneVerified()
        status.emailContactVersion() == 1 && status.phoneContactVersion() == 1
        count('select count(*) as n from identities') == 0
        count('select count(*) as n from external_identities') == 0
        count('select count(*) as n from verification_delivery_outbox where state = ? and ciphertext is null and nonce is null', 'ACCEPTED') == 2
        count('select count(*) as n from verification_phone_claims where state = ?', 'RESERVED') == 1
    }
}
