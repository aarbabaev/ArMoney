package com.arman.bank.authservice.infrastructure

import com.arman.bank.authservice.application.VerificationService
import com.arman.bank.authservice.domain.Verification.*
import com.arman.bank.runtime.Database
import com.sun.net.httpserver.HttpServer
import groovy.json.JsonOutput
import org.testcontainers.containers.PostgreSQLContainer
import spock.lang.*
import java.time.*
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicReference

/** Actual Flyway/PostgreSQL store and worker; HTTP captures are synthetic SMS only.
 * Despite its leased AcceptanceSpec name, this explicitly selected :test case uses
 * Testcontainers. It never proves carrier delivery, process crash, ACTIVE or projection.
 */
class VerificationDeliveryPostgresAcceptanceSpec extends Specification {
    static final String ISSUER = 'https://issuer.example/realm'
    static final String TOKEN = 'synthetic_sms_sender_token_32_characters'
    @Shared PostgreSQLContainer postgres
    @Shared Database database
    VerificationCrypto crypto
    PostgresVerificationStore store
    VerificationService service
    Binding binding = new Binding(ISSUER, 'synthetic-subject', 'synthetic-server-session')
    List<VerificationDeliveryWorker> workers = []
    List<Map> requests = new CopyOnWriteArrayList<>()
    HttpServer server
    ExecutorService executor
    AtomicReference<Throwable> serverFailure = new AtomicReference<>()
    boolean loseResponse
    Closure afterCapture

    def setupSpec() {
        postgres = new PostgreSQLContainer('postgres:17.6-alpine')
        postgres.start()
        database = new Database(postgres.jdbcUrl, postgres.username, postgres.password)
    }
    def cleanupSpec() { database?.close(); postgres?.stop() }
    def setup() {
        database.transaction { it.execute('truncate verification_authority_commands, verification_authority_admission, verification_flows, verification_flow_barriers, verification_authority_phone_claims, verification_authorities, verification_candidates, auth_verification_projection_commands, verification_receipts, verification_delivery_outbox, verification_challenges, verification_commands, verification_phone_claims, verification_enrollments') }
        crypto = new VerificationCrypto('test', ['test': ([11] * 32) as byte[]], ['test': ([22] * 32) as byte[]])
        store = new PostgresVerificationStore(database, crypto)
        service = new VerificationService(store, [ISSUER] as Set)
        executor = Executors.newFixedThreadPool(2)
        server = HttpServer.create(new InetSocketAddress('127.0.0.1', 0), 0)
        server.executor = executor
        server.createContext('/api/messages') { exchange ->
            try {
                requests.add([key: exchange.requestHeaders.getFirst('Idempotency-Key'),
                    body: new String(exchange.requestBody.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)])
                afterCapture?.call()
                exchange.responseHeaders.set('Content-Type', 'application/json')
                if (loseResponse) {
                    // Capture happened, but a truncated acknowledgement is unusable.
                    exchange.sendResponseHeaders(201, 100)
                    exchange.responseBody.write('{'.getBytes('UTF-8'))
                } else {
                    byte[] ack = JsonOutput.toJson([id: UUID.randomUUID().toString(), createdAt: 1L, replay: false]).getBytes('UTF-8')
                    exchange.sendResponseHeaders(201, ack.length)
                    exchange.responseBody.write(ack)
                }
            } catch (IOException expectedAfterResponseLoss) {
                if (!loseResponse) serverFailure.set(expectedAfterResponseLoss)
            } catch (Throwable failure) { serverFailure.set(failure) }
            finally { exchange.close() }
        }
        server.start()
    }
    def cleanup() {
        workers.each { it.close() }
        server?.stop(0)
        executor?.shutdownNow()
        assert executor == null || executor.awaitTermination(5, TimeUnit.SECONDS)
        assert serverFailure.get() == null
        assert count('select count(*) as n from verification_receipts') == 0
        assert count('select count(*) as n from identities') == 0
        assert count('select count(*) as n from external_identities') == 0
        assert count("select count(*) as n from verification_enrollments where state <> 'PENDING_CONTACTS'") == 0
    }
    Map issue() {
        def enrollment = service.enroll(new EnrollmentCommand(UUID.randomUUID(), binding, '+971501234567', 'synthetic@example.test'))
        def command = new ChallengeCommand(UUID.randomUUID(), enrollment.enrollmentId(), binding, Purpose.REGISTRATION_PHONE, 1)
        [command: command, result: service.issue(command)]
    }
    VerificationDeliveryWorker worker(Clock clock = Clock.systemUTC()) {
        def transport = new VerificationDeliveryTransport({ recipient, subject, text, html ->
            throw new AssertionError('Phone fixture must never use SMTP')
        } as VerificationDeliveryTransport.MailSender, URI.create("http://127.0.0.1:${server.address.port}"), TOKEN, true)
        def result = new VerificationDeliveryWorker(new PostgresVerificationStore(database, crypto), crypto, transport, clock)
        workers.add(result)
        result
    }
    long count(String query, Object... values) { database.transaction { it.fetchOne(query, values).get('n', Long) } }
    void execute(String query, Object... values) { database.transaction { it.execute(query, values) } }
    def row(UUID delivery) { database.transaction { it.fetchOne('select * from verification_delivery_outbox where id = ?', delivery) } }
    String challenge(UUID id) { database.transaction { it.fetchOne('select state from verification_challenges where id = ?', id).get('state', String) } }
    void expireLease(UUID delivery) { execute("update verification_delivery_outbox set lease_until = clock_timestamp() - interval '1 second' where id = ?", delivery) }

    def 'real expired lease reclaim rotates fence and rejects the stale acknowledgement'() {
        given:
        issue()
        def original = store.claimDelivery().orElseThrow()
        def payload = crypto.decrypt(original.encrypted(), original.enrollmentId(), original.challengeId(), original.purpose(), 1)
        when:
        expireLease(original.deliveryId())
        def restarted = new PostgresVerificationStore(database, crypto)
        def fresh = restarted.claimDelivery().orElseThrow()
        then:
        fresh.deliveryId() == original.deliveryId()
        fresh.challengeId() == original.challengeId()
        fresh.fence() != original.fence()
        crypto.decrypt(fresh.encrypted(), fresh.enrollmentId(), fresh.challengeId(), fresh.purpose(), 1) == payload
        !store.finishDelivery(original.deliveryId(), original.fence(), true)
        row(fresh.deliveryId()).get('state') == 'LEASED'
        challenge(fresh.challengeId()) == 'PENDING_DELIVERY'
        restarted.finishDelivery(fresh.deliveryId(), fresh.fence(), true)
        challenge(fresh.challengeId()) == 'OPEN'
        requests.empty
    }

    def 'new worker recovers abandoned expired lease using the same persisted delivery and challenge'() {
        given:
        def issued = issue()
        def abandoned = store.claimDelivery().orElseThrow()
        def ciphertext = row(abandoned.deliveryId()).get('ciphertext', byte[])
        when:
        expireLease(abandoned.deliveryId())
        worker().tick()
        then:
        requests.size() == 1
        requests.first().key == abandoned.deliveryId().toString()
        row(abandoned.deliveryId()).get('state') == 'ACCEPTED'
        row(abandoned.deliveryId()).get('attempts') == 2
        row(abandoned.deliveryId()).get('ciphertext') == null
        ciphertext.length > 16
        challenge(issued.result.challengeId()) == 'OPEN'
        count('select count(*) as n from verification_challenges') == 1
        count('select count(*) as n from verification_delivery_outbox') == 1
        !store.finishDelivery(abandoned.deliveryId(), abandoned.fence(), true)
    }

    def 'lost HTTP acknowledgement retries identical command key and payload after worker reconstruction'() {
        given:
        def issued = issue()
        loseResponse = true
        def first = worker()
        when:
        first.tick()
        then:
        requests.size() == 1
        challenge(issued.result.challengeId()) == 'PENDING_DELIVERY'
        def delivery = UUID.fromString(requests.first().key)
        row(delivery).get('state') == 'RETRY_WAIT'
        row(delivery).get('ciphertext') != null
        when:
        first.close()
        loseResponse = false
        execute('update verification_delivery_outbox set available_at = clock_timestamp() where id = ?', delivery)
        worker().tick()
        then:
        requests.size() == 2
        requests[0] == requests[1]
        row(delivery).get('state') == 'ACCEPTED'
        row(delivery).get('attempts') == 2
        challenge(issued.result.challengeId()) == 'OPEN'
        new VerificationService(new PostgresVerificationStore(database, crypto), [ISSUER] as Set).issue(issued.command) == issued.result
        count('select count(*) as n from verification_challenges') == 1
        count('select count(*) as n from verification_delivery_outbox') == 1
    }

    def 'superseded queued payload is erased and only its replacement can reach transport'() {
        given:
        def first = issue()
        def original = store.claimDelivery().orElseThrow()
        execute("update verification_challenges set created_at = clock_timestamp() - interval '61 seconds' where id = ?", first.result.challengeId())
        def replacement = service.issue(new ChallengeCommand(UUID.randomUUID(), first.command.enrollmentId(), binding, Purpose.REGISTRATION_PHONE, 1))
        when:
        worker().tick()
        then:
        requests.size() == 1
        requests.first().key != original.deliveryId().toString()
        challenge(first.result.challengeId()) == 'SUPERSEDED'
        row(original.deliveryId()).get('state') == 'EXPIRED'
        row(original.deliveryId()).get('ciphertext') == null
        row(original.deliveryId()).get('nonce') == null
        !store.finishDelivery(original.deliveryId(), original.fence(), true)
        challenge(replacement.challengeId()) == 'OPEN'
    }

    def 'supersession during in-flight send refuses stale acknowledgement without reopening old challenge'() {
        given:
        def first = issue()
        afterCapture = {
            execute("update verification_challenges set created_at = clock_timestamp() - interval '61 seconds' where id = ?", first.result.challengeId())
            service.issue(new ChallengeCommand(UUID.randomUUID(), first.command.enrollmentId(), binding, Purpose.REGISTRATION_PHONE, 1))
        }
        when:
        worker().tick()
        then:
        requests.size() == 1 // In-flight messages can arrive; the contract does not promise erasure of sent bytes.
        challenge(first.result.challengeId()) == 'SUPERSEDED'
        row(UUID.fromString(requests.first().key)).get('state') == 'EXPIRED'
        count("select count(*) as n from verification_challenges where state = 'OPEN'") == 0
        count("select count(*) as n from verification_challenges where state = 'PENDING_DELIVERY'") == 1
    }

    def 'database-expired pending code is never sent and its encrypted payload is erased'() {
        given:
        def issued = issue()
        execute("update verification_delivery_outbox set expires_at = clock_timestamp() - interval '1 second'")
        execute("update verification_challenges set expires_at = clock_timestamp() - interval '1 second'")
        when:
        worker().tick()
        then:
        requests.empty
        challenge(issued.result.challengeId()) == 'EXPIRED'
        count("select count(*) as n from verification_delivery_outbox where state = 'EXPIRED' and ciphertext is null and nonce is null") == 1
        store.claimDelivery().empty
    }
}
