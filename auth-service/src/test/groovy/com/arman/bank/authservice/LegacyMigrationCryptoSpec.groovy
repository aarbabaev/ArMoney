package com.arman.bank.authservice.infrastructure

import spock.lang.Specification
import java.time.Instant
import java.security.MessageDigest

class LegacyMigrationCryptoSpec extends Specification {
    static String verifier='a'*43
    static String challenge=Base64.urlEncoder.withoutPadding().encodeToString(MessageDigest.getInstance('SHA-256').digest(verifier.bytes))
    static def context(UUID id=UUID.randomUUID(),String purpose='LEGACY_ACCOUNT'){
        new LegacyMigrationCrypto.Context(id,purpose,'https://issuer.example/realm','legacy-client','https://migration.example/migration/legacy/account-oidc/callback',challenge,Instant.EPOCH,Instant.EPOCH.plusSeconds(120))
    }
    def 'AEAD retains original verifier across restart and key rotation but refuses wrong purpose tag and missing key'(){
        given:def c=context();def old=new LegacyMigrationCrypto('old',[old:([71]*32) as byte[]],([72]*32) as byte[])
        def encrypted=old.encrypt(c,verifier)
        def rotated=new LegacyMigrationCrypto('new',[old:([71]*32) as byte[],'new':([73]*32) as byte[]],([72]*32) as byte[])
        expect:rotated.decrypt(c,encrypted)==verifier;encrypted.nonce().size()==12;encrypted.ciphertext().size()<=256
        when:rotated.decrypt(context(c.attemptId(),'MANAGED_BINDING'),encrypted)
        then:thrown(IllegalStateException)
        when:new LegacyMigrationCrypto('new',['new':([73]*32) as byte[]],([72]*32) as byte[]).decrypt(c,encrypted)
        then:thrown(IllegalStateException)
        when:def bad=encrypted.ciphertext();bad[0]++;rotated.decrypt(c,new LegacyMigrationCrypto.Encrypted(encrypted.keyId(),encrypted.nonce(),bad))
        then:thrown(IllegalStateException)
    }
    def 'wrong challenge or nonPKCE verifier fails and source key arrays are defensively copied'(){
        given:byte[] key=([71]*32) as byte[];def crypto=new LegacyMigrationCrypto('key',[key:key],([72]*32) as byte[]);key[0]++
        def c=context();def encrypted=crypto.encrypt(c,verifier)
        expect:crypto.decrypt(c,encrypted)==verifier
        when:crypto.encrypt(c,'b'*43)
        then:thrown(IllegalStateException)
    }
}
