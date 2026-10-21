package com.arman.bank.authservice.infrastructure

import com.arman.bank.authservice.domain.LegacyMigrationOtp as O
import spock.lang.Specification
import java.time.Instant

class LegacyMigrationOtpCryptoSpec extends Specification {
    def crypto(){new LegacyMigrationOtpCrypto('key',[key:([83]*32) as byte[]],[key:([84]*32) as byte[]])}
    def envelope(O.Purpose purpose=O.Purpose.PHONE){new LegacyMigrationOtpCrypto.Envelope(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),purpose,'a'*64,'b'*64,Instant.EPOCH.plusSeconds(300))}
    def 'random OTP and encrypted payload bind migration challenge purpose contact and original expiry'(){
        given:def c=crypto();def e=envelope();def code=c.code();def encrypted=c.encrypt(e,'+971501234567',code)
        expect:code==~ /[0-9]{6}/;encrypted.nonce().size()==12;encrypted.ciphertext().size()<=1024
        c.decrypt(e,encrypted).code()==code;c.decrypt(e,encrypted).destination()=='+971501234567'
        when:c.decrypt(new LegacyMigrationOtpCrypto.Envelope(e.deliveryId(),e.challengeId(),e.candidateId(),O.Purpose.EMAIL,e.bindingHash(),e.contactDigest(),e.expiresAt()),encrypted)
        then:thrown(O.Failure)
        when:def changed=encrypted.ciphertext();changed[0]++;c.decrypt(e,new LegacyMigrationOtpCrypto.Encrypted(encrypted.keyId(),encrypted.nonce(),changed))
        then:thrown(O.Failure)
    }
    def 'original keys survive rotation but missing key never falls back and generation is initial only'(){
        given:def c=crypto();def e=envelope();def encrypted=c.encrypt(e,'+971501234567','123456')
        def rotated=new LegacyMigrationOtpCrypto('next',[key:([83]*32) as byte[],next:([85]*32) as byte[]],[key:([84]*32) as byte[],next:([86]*32) as byte[]])
        expect:rotated.decrypt(e,encrypted).code()=='123456'
        when:new LegacyMigrationOtpCrypto('next',[next:([85]*32) as byte[]],[next:([86]*32) as byte[]]).decrypt(e,encrypted)
        then:thrown(O.Failure)
        when:c.codeDigest('key',e,2L,'123456')
        then:thrown(O.Failure)
    }
}
