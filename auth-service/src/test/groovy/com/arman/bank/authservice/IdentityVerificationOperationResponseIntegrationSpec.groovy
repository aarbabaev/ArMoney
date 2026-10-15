package com.arman.bank.authservice

import com.arman.bank.authservice.domain.IdentityVerification.*
import com.arman.bank.authservice.domain.Verification
import com.arman.bank.authservice.domain.Verification.Purpose
import com.arman.bank.authservice.infrastructure.PostgresIdentityVerificationStore
import com.arman.bank.authservice.infrastructure.PostgresVerificationStore
import com.arman.bank.authservice.infrastructure.VerificationCrypto
import com.arman.bank.runtime.Database
import org.testcontainers.containers.PostgreSQLContainer
import spock.lang.Specification
import spock.lang.Unroll
import java.time.Instant
import java.time.OffsetDateTime

/** Real owning-store operations and persisted outcomes. Provider observations are
 * synthetic inputs only; no provider HTTP adapter or banking activation is claimed.
 */
class IdentityVerificationOperationResponseIntegrationSpec extends Specification {
    static final String ISSUER = 'https://issuer.example/realm'
    static final String WRITER = 'synthetic_provider'
    static final String PHONE = '+971521234567'
    static final String EMAIL = 'third@example.test'
    PostgreSQLContainer postgres
    Database database
    VerificationCrypto crypto
    PostgresIdentityVerificationStore store
    Flow flow

    def setup() {
        postgres = new PostgreSQLContainer('postgres:17.6-alpine')
        postgres.start()
        database = new Database(postgres.jdbcUrl, postgres.username, postgres.password)
        crypto = new VerificationCrypto('test', [test:([11] * 32) as byte[]], [test:([22] * 32) as byte[]])
        flow = new Flow(ISSUER, 'synthetic-subject', 'armoney-web', 'synthetic-root', 'synthetic-tab',
            Base64.urlEncoder.withoutPadding().encodeToString(([3] * 32) as byte[]))
        wire()
    }

    def cleanup() { database?.close(); postgres?.stop() }

    @Unroll
    def '#purpose preserves operation state expiry and requested version independently of enrollment revision'() {
        given: 'three contact replacements diverge revision four from email three and phone two'
        def initial = enroll('+971501234567', 'first@example.test')
        enroll('+971501234567', 'second@example.test')
        enroll('+971501234567', EMAIL)
        def current = enroll(PHONE, EMAIL)
        assert current.enrollmentId() == initial.enrollmentId()
        assert current.version() == 4
        assert current.emailContactVersion() == 3
        assert current.phoneContactVersion() == 2
        def enrollment = row('select created_at, expires_at from verification_enrollments where id=?', current.enrollmentId())
        def enrollmentExpiry = instant(enrollment.expires_at)
        assert enrollmentExpiry.epochSecond - instant(enrollment.created_at).epochSecond == 86400
        def issueCommand = new Issue(UUID.randomUUID(), current.enrollmentId(), flow, purpose, requestedVersion)

        when:
        def issued = issue(issueCommand)
        def challenge = row('select * from verification_challenges where id=?', issued.challengeId())

        then:
        issued.error() == null
        issued.state() == 'PENDING_DELIVERY'
        issued.contactVersion() == requestedVersion
        issued.version() == 4
        issued.emailContactVersion() == 3
        issued.phoneContactVersion() == 2
        issued.expiresAt() == instant(challenge.expires_at)
        issued.expiresAt().epochSecond - instant(challenge.created_at).epochSecond == 300
        issued.expiresAt() != enrollmentExpiry
        issued.resendAt() == instant(challenge.resend_at)
        issued.attemptsRemaining() == 5
        challenge.purpose == purpose.name()
        challenge.contact_version == requestedVersion
        !issued.emailVerified() && !issued.phoneVerified()
        issue(issueCommand) == issued
        scalar('select count(*) as n from verification_challenges') == 1
        scalar('select count(*) as n from verification_delivery_outbox') == 1

        when: 'the actual encrypted outbox is claimed and locally acknowledged, without sending a provider message'
        def delivery = new PostgresVerificationStore(database, crypto)
        def lease = delivery.claimDelivery().orElseThrow()
        assert lease.challengeId() == issued.challengeId()
        def payload = crypto.decrypt(lease.encrypted(), current.enrollmentId(), issued.challengeId(), purpose, requestedVersion)
        assert payload.destination() == (purpose == Purpose.REGISTRATION_EMAIL ? EMAIL : PHONE)
        assert delivery.finishDelivery(lease.deliveryId(), lease.fence(), true)
        def consumeCommand = new Consume(UUID.randomUUID(), current.enrollmentId(), issued.challengeId(), flow,
            purpose, requestedVersion, payload.code())
        def consumed = consume(consumeCommand)
        def receipt = row('select * from verification_receipts where id=?', consumed.receiptId())

        then:
        consumed.error() == null
        consumed.state() == 'CONSUMED'
        consumed.contactVersion() == requestedVersion
        consumed.version() == 5 // A newly committed receipt advances authority revision once.
        consumed.emailContactVersion() == 3
        consumed.phoneContactVersion() == 2
        receipt.challenge_id == issued.challengeId()
        receipt.contact_version == requestedVersion
        receipt.purpose == purpose.name()
        consumed.emailVerified() == (purpose == Purpose.REGISTRATION_EMAIL)
        consumed.phoneVerified() == (purpose == Purpose.REGISTRATION_PHONE)

        when: 'a fresh pool retries the exact operation after an uncertain response'
        database.close()
        database = new Database(postgres.jdbcUrl, postgres.username, postgres.password)
        wire()
        def replay = consume(consumeCommand)
        def currentStatus = status(current.enrollmentId())

        then:
        replay == consumed
        scalar('select count(*) as n from verification_receipts') == 1
        currentStatus.enrollmentId() == current.enrollmentId()
        currentStatus.version() == 5
        currentStatus.contactVersion() == 5
        currentStatus.emailContactVersion() == 3
        currentStatus.phoneContactVersion() == 2
        currentStatus.expiresAt() == enrollmentExpiry
        currentStatus.state() != 'CONSUMED'
        currentStatus.state() != 'PENDING_DELIVERY'
        currentStatus.emailVerified() == consumed.emailVerified()
        currentStatus.phoneVerified() == consumed.phoneVerified()
        currentStatus.challengeId() == null && currentStatus.receiptId() == null
        scalar('select count(*) as n from identities') == 0
        scalar('select count(*) as n from sessions') == 0
        scalar('select count(*) as n from auth_verification_projection_commands') == 0

        where:
        purpose                    | requestedVersion
        Purpose.REGISTRATION_EMAIL | 3
        Purpose.REGISTRATION_PHONE | 2
    }

    private void wire() {
        store = new PostgresIdentityVerificationStore(database, crypto, ([41] * 32) as byte[],
            Policy.defaults([ISSUER] as Set, ['armoney-web'] as Set, [WRITER] as Set), null)
    }
    private Observation observation(Instant anchor, String phone = PHONE, String email = EMAIL) {
        new Observation(flow, phone, email, phone, true, true, anchor.plusSeconds(600), anchor)
    }
    private Verification.Result enroll(String phone, String email) {
        Instant anchor = store.admit(WRITER, flow)
        store.enroll(WRITER, new Enrollment(UUID.randomUUID(), flow, phone, email), observation(anchor, phone, email), anchor)
    }
    private Verification.Result issue(Issue command) {
        Instant anchor = store.admit(WRITER, flow)
        store.issue(WRITER, command, observation(anchor), anchor)
    }
    private Verification.Result consume(Consume command) {
        Instant anchor = store.admit(WRITER, flow)
        store.consume(WRITER, command, observation(anchor), anchor)
    }
    private Verification.Result status(UUID enrollment) {
        Instant anchor = store.admit(WRITER, flow)
        store.status(WRITER, enrollment, flow, observation(anchor), anchor)
    }
    private Map row(String query, Object... arguments) {
        database.transaction { it.fetchOne(query, arguments).intoMap() }
    }
    private long scalar(String query) { database.transaction { it.fetchOne(query).get('n', Long) } }
    private static Instant instant(Object value) { ((OffsetDateTime) value).toInstant() }
}
