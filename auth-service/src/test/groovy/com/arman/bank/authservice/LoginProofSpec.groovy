package com.arman.bank.authservice

import com.arman.bank.authservice.domain.LoginProof
import com.arman.bank.authservice.domain.LoginProof.*
import com.arman.bank.authservice.domain.LoginProof.Error
import com.arman.bank.authservice.infrastructure.PostgresLoginProofStore
import java.time.Instant
import spock.lang.Specification

class LoginProofSpec extends Specification {
    static final String ISSUER = 'https://issuer.example/realm'
    static final Instant NOW = Instant.parse('2026-10-15T12:00:00Z')
    static final Policy POLICY = Policy.defaults(Set.of(ISSUER), Set.of('armoney-web'), Set.of('current', 'previous'), Set.of('provider'))
    static Binding binding(String subject = 'subject') { new Binding(ISSUER, subject, 'armoney-web', 'session') }
    static Write command(Map changes = [:]) {
        def values = [command: UUID.randomUUID(), digest: 'a' * 64, key: 'current', issuer: ISSUER, subject: 'subject', client: 'armoney-web',
            session: 'session', authentication: 'authentication', issued: NOW, expiry: NOW.plusSeconds(120), kind: Kind.INTERACTIVE,
            event: UUID.fromString('11111111-1111-4111-8111-111111111111'), password: NOW.minusSeconds(1)] + changes
        new Write(values.command, values.digest, values.key, values.issuer, values.subject, values.client, values.session, values.authentication,
            values.issued, values.expiry, values.kind, values.event, values.password)
    }
    static Activation activation(Map changes = [:]) {
        def values = [binding: binding(), identity: UUID.randomUUID(), state: 'ACTIVE', emailReceipt: true, phoneReceipt: true,
            emailVersion: 2, phoneVersion: 3, epoch: 7L, operation: UUID.randomUUID(), projection: 4, ackPhoneVersion: 3, ackEpoch: 7L,
            hash: 'b' * 64, ack: true, email: 'synthetic@example.test', active: true, match: true, observed: NOW] + changes
        new Activation(values.binding, values.identity, values.state, values.emailReceipt, values.phoneReceipt, values.emailVersion,
            values.phoneVersion, values.epoch, values.operation, values.projection, values.ackPhoneVersion, values.ackEpoch, values.hash,
            values.ack, values.email, values.active, values.match, values.observed)
    }
    def 'interactive and reuse inputs obey explicit null and time rules'() {
        when:
        LoginProof.validate(command(), 'provider', POLICY)
        LoginProof.validate(command(kind: Kind.REUSE, event: null, password: null), 'provider', POLICY)
        then:
        noExceptionThrown()
    }
    def 'invalid input cannot become proof'() {
        when:
        LoginProof.validate(command(change), 'provider', POLICY)
        then:
        def failure = thrown(Failure)
        failure.error() == expected
        where:
        change                                       | expected
        [digest: 'A' * 64]                            | Error.BAD_INPUT
        [digest: 'a' * 63]                            | Error.BAD_INPUT
        [key: 'unknown']                             | Error.CONFLICT
        [issuer: 'https://untrusted.example']         | Error.CONFLICT
        [client: 'armoney-migration']                 | Error.CONFLICT
        [event: null]                                | Error.BAD_INPUT
        [password: NOW.plusSeconds(1)]                | Error.BAD_INPUT
        [password: NOW.minusSeconds(1801)]            | Error.BAD_INPUT
        [expiry: NOW]                                | Error.BAD_INPUT
        [expiry: NOW.plusSeconds(121)]                | Error.BAD_INPUT
        [issued: NOW.plusNanos(1)]                    | Error.BAD_INPUT
        [kind: Kind.REUSE]                           | Error.BAD_INPUT
        [authentication: ' ']                        | Error.BAD_INPUT
        [subject: 'subject\u2003other']               | Error.BAD_INPUT
        [session: 'session\u0001']                   | Error.BAD_INPUT
        [subject: '\uD800']                          | Error.BAD_INPUT
    }
    def 'unknown writer fails generically and cannot select requester scope'() {
        when:
        LoginProof.validate(command(), 'browser-selected-writer', POLICY)
        then:
        def failure = thrown(Failure)
        failure.error() == Error.UNAUTHORIZED
        failure.message == 'login_proof_request_rejected'
    }
    def 'expiry is exclusive and original admission window is bounded'() {
        when:
        LoginProof.unexpired(NOW, NOW)
        then:
        thrown(Failure)
        when:
        LoginProof.fresh(command(issued: NOW.minusSeconds(31)), NOW)
        then:
        thrown(Failure)
        when:
        LoginProof.fresh(command(issued: NOW.plusSeconds(31)), NOW)
        then:
        thrown(Failure)
    }
    def 'projection revision is independent from phone version and observation has a strict five-second bound'() {
        expect:
        activation().completeFor(binding(), NOW)
        activation(epoch: 8L, ackEpoch: 8L, projection: 5).completeFor(binding(), NOW)
        activation(observed: NOW.minusSeconds(5)).completeFor(binding(), NOW)
        !activation(observed: NOW.minusSeconds(5).minusNanos(1)).completeFor(binding(), NOW)
        !activation(observed: NOW.plusNanos(1)).completeFor(binding(), NOW)
    }
    def 'missing or inconsistent authority cannot become ACTIVE evidence'() {
        expect:
        !activation(change).completeFor(binding(), NOW)
        where:
        change << [[state: 'VERIFIED'], [emailReceipt: false], [phoneReceipt: false], [ack: false], [ackPhoneVersion: 2],
                   [ackEpoch: 6L], [projection: 0], [operation: null], [hash: 'not-a-hash'], [identity: null],
                   [active: false], [match: false], [binding: binding('different')], [email: 'UPPER@example.test']]
    }
    def 'canonical hashes exclude only command ID and never conflate field boundaries'() {
        given:
        def first = command()
        def second = command(command: UUID.randomUUID())
        expect:
        Arrays.equals(LoginProof.payloadHash(first), LoginProof.payloadHash(second))
        !Arrays.equals(LoginProof.payloadHash(first), LoginProof.payloadHash(command(session: 'other')))
        !Arrays.equals(LoginProof.requesterHash('ab', 'c', 'd'), LoginProof.requesterHash('a', 'bc', 'd'))
        !Arrays.equals(LoginProof.bindingHash(binding()), LoginProof.bindingHash(binding('other')))
    }
    def 'recipient equality uses a separate secret and representations redact private fields'() {
        given:
        byte[] key = ([11] * 32) as byte[]
        byte[] other = ([22] * 32) as byte[]
        expect:
        !Arrays.equals(PostgresLoginProofStore.recipientDigest(key, 'synthetic@example.test'), PostgresLoginProofStore.recipientDigest(other, 'synthetic@example.test'))
        !command().toString().contains('authentication')
        !binding().toString().contains('subject')
        !activation().toString().contains('synthetic@example.test')
    }
}
