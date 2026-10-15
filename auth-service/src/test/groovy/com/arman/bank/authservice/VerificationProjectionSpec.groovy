package com.arman.bank.authservice

import com.arman.bank.authservice.domain.VerificationProjection
import com.arman.bank.authservice.domain.VerificationProjection.*
import spock.lang.Specification

class VerificationProjectionSpec extends Specification {
    static final UUID OPERATION=UUID.fromString('00000000-0000-4000-8000-000000000001')
    static final UUID IDENTITY=UUID.fromString('00000000-0000-4000-8000-000000000002')
    static Command command(int expected=0,int version=1,int phoneVersion=1,long epoch=1L){VerificationProjection.create(OPERATION,IDENTITY,expected,version,'+971501234567',phoneVersion,epoch)}
    def 'canonical command hash binds operation identity every version phone and epoch'(){
        given:
        def first=command()
        expect:
        first.payloadHash()==~ /[0-9a-f]{64}/
        first.payloadHash()==command().payloadHash()
        first.payloadHash()!=command(1,2,1,2L).payloadHash()
        first.payloadHash()!=VerificationProjection.create(UUID.randomUUID(),IDENTITY,0,1,'+971501234567',1,1L).payloadHash()
        first.payloadHash()!=VerificationProjection.create(OPERATION,UUID.randomUUID(),0,1,'+971501234567',1,1L).payloadHash()
        first.payloadHash()!=VerificationProjection.create(OPERATION,IDENTITY,0,1,'+971521234567',1,1L).payloadHash()
        first.payloadHash()!=VerificationProjection.create(OPERATION,IDENTITY,0,1,'+971501234567',2,1L).payloadHash()
    }
    def 'phone version is independent from projection revision and epoch'(){
        given:
        def refresh=command(7,8,3,10L)
        when:
        VerificationProjection.validate(refresh)
        then:
        noExceptionThrown()
        refresh.version()==8 && refresh.phoneContactVersion()==3 && refresh.verificationEpoch()==10
    }
    def 'numeric boundaries reject overflow zero and noncanonical phone'(){
        when:
        VerificationProjection.create(OPERATION,IDENTITY,expected,version,phone,contact,epoch)
        then:
        thrown(Failure)
        where:
        expected          | version           | phone           | contact | epoch
        -1                | 0                 | '+971501234567'  | 1       | 1L
        Integer.MAX_VALUE | Integer.MIN_VALUE | '+971501234567'  | 1       | 1L
        0                 | 2                 | '+971501234567'  | 1       | 1L
        0                 | 1                 | '0501234567'     | 1       | 1L
        0                 | 1                 | '+971501234567'  | 0       | 1L
        0                 | 1                 | '+971501234567'  | 1       | 0L
    }
    def 'all six acknowledgement fields must match immutable command'(){
        given:
        def value=command()
        expect:
        new Ack(OPERATION,IDENTITY,1,1,1L,value.payloadHash()).matches(value)
        !new Ack(UUID.randomUUID(),IDENTITY,1,1,1L,value.payloadHash()).matches(value)
        !new Ack(OPERATION,UUID.randomUUID(),1,1,1L,value.payloadHash()).matches(value)
        !new Ack(OPERATION,IDENTITY,2,1,1L,value.payloadHash()).matches(value)
        !new Ack(OPERATION,IDENTITY,1,2,1L,value.payloadHash()).matches(value)
        !new Ack(OPERATION,IDENTITY,1,1,2L,value.payloadHash()).matches(value)
        !new Ack(OPERATION,IDENTITY,1,1,1L,'a'*64).matches(value)
    }
    def 'payload mutation cannot reuse its original hash or leak through representations'(){
        given:
        def value=command()
        when:
        VerificationProjection.validate(new Command(OPERATION,IDENTITY,0,1,'+971521234567',1,1L,value.payloadHash()))
        then:
        thrown(Failure)
        !value.toString().contains('+971')
        !new Authority(IDENTITY,UUID.randomUUID(),'+971501234567',1,1,1L,UUID.randomUUID(),UUID.randomUUID()).toString().contains('+971')
    }
}
