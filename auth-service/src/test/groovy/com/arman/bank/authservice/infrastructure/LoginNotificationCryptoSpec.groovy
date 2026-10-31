package com.arman.bank.authservice.infrastructure
import spock.lang.Specification
import java.time.Instant
class LoginNotificationCryptoSpec extends Specification {
    def keys(){new ManagedBankingKeys('k1',[k1:Base64.encoder.encodeToString(([91]*32) as byte[])])}
    def context(){new LoginNotificationCrypto.Context(UUID.randomUUID(),'https://issuer.example/realm',UUID.randomUUID(),UUID.randomUUID(),'k1',([5]*32) as byte[],Instant.EPOCH)}
    def 'real AEAD authenticates original owner event source and rejects tampering with no plaintext fallback'(){
        given:def crypto=new LoginNotificationCrypto(keys());def c=context()
        def payload=new LoginNotificationCrypto.Payload('subject','owner@example.test','Optional Name')
        def encrypted=crypto.encrypt(c,payload)
        expect:crypto.decrypt(c,encrypted.nonce(),encrypted.ciphertext())==payload
        encrypted.nonce().length==12
        !new String(encrypted.ciphertext(),java.nio.charset.StandardCharsets.ISO_8859_1).contains('owner@example.test')
        when:
        def foreign=new LoginNotificationCrypto.Context(c.id(),c.issuer(),c.event(),UUID.randomUUID(),c.key(),c.stamp(),c.occurred())
        crypto.decrypt(foreign,encrypted.nonce(),encrypted.ciphertext())
        then:thrown(IllegalArgumentException)
        when:encrypted.ciphertext()[0]=(byte)(encrypted.ciphertext()[0]^1);crypto.decrypt(c,encrypted.nonce(),encrypted.ciphertext())
        then:thrown(IllegalArgumentException)
    }
    def 'unknown original key fails closed and derived AES key differs from root material'(){
        given:def k=keys();def c=context();def crypto=new LoginNotificationCrypto(k)
        expect:!Arrays.equals(k.noticeKey('k1'),([91]*32) as byte[])
        when:crypto.encrypt(new LoginNotificationCrypto.Context(c.id(),c.issuer(),c.event(),c.owner(),'missing',c.stamp(),c.occurred()),new LoginNotificationCrypto.Payload('subject','owner@example.test',null))
        then:thrown(IllegalArgumentException)
    }
}
