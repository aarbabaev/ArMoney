package com.arman.bank.userservice

import com.arman.bank.userservice.domain.VerificationProjection
import spock.lang.Specification

class VerificationProjectionSpec extends Specification {
    static final UUID OP = UUID.fromString('00000000-0000-4000-8000-000000000001')
    static final UUID ID = UUID.fromString('00000000-0000-4000-8000-000000000002')
    static VerificationProjection command(int expected, int version, String phone, int contact, long epoch) {
        new VerificationProjection(OP, ID, expected, version, phone, contact, epoch,
            VerificationProjection.hash(OP, ID, expected, version, phone, contact, epoch))
    }
    def 'projection revision is independent of phone contact version and epoch'() {
        given:
        def command = command(7, 8, '+971501234567', 2, 103)
        expect:
        command.ack().version() == 8
        command.ack().phoneContactVersion() == 2
        command.ack().verificationEpoch() == 103
        !command.toString().contains('+971')
        !command.ack().toString().contains(ID.toString())
    }
    def 'revision bounds contact bounds epoch and canonical phone fail closed'() {
        when:
        command(expected, version, phone, contact, epoch)
        then:
        thrown(IllegalArgumentException)
        where:
        expected          | version           | phone            | contact | epoch
        -1                | 0                 | '+971501234567'  | 1       | 1L
        0                 | 2                 | '+971501234567'  | 1       | 1L
        Integer.MAX_VALUE | Integer.MIN_VALUE | '+971501234567'  | 1       | 1L
        0                 | 1                 | '+971501234567'  | 0       | 1L
        0                 | 1                 | '+971501234567'  | 1       | 0L
        0                 | 1                 | '0501234567'     | 1       | 1L
        0                 | 1                 | '+971511234567'  | 1       | 1L
    }
    def 'hash authenticates every command field but is not a credential'() {
        given:
        def original = command(0, 1, '+971501234567', 1, 1)
        when:
        new VerificationProjection(OP, ID, 0, 1, '+971501234568', 1, 1, original.payloadHash())
        then:
        thrown(IllegalArgumentException)
        original.payloadHash() ==~ /[0-9a-f]{64}/
    }
}
