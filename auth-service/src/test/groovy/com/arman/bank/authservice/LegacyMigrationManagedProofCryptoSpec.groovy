package com.arman.bank.authservice.infrastructure

import com.arman.bank.authservice.application.*
import com.arman.bank.authservice.domain.LegacyMigrationOtp
import spock.lang.Specification

class LegacyMigrationManagedProofCryptoSpec extends Specification {
    def 'User release has its exact retained HMAC domain and never accepts an arbitrary purpose'(){
        given:
        byte[] key=([31]*32) as byte[]
        def crypto=new LegacyMigrationManagedProofCrypto('old',[old:key])
        String domain='armoney-legacy-migration-user-release-v1'
        def mac=javax.crypto.Mac.getInstance('HmacSHA256')
        mac.init(new javax.crypto.spec.SecretKeySpec(key,'HmacSHA256'))
        def expected=mac.doFinal(LegacyMigrationOtp.tuple(domain,'one','two'))
        expect:
        crypto.hash('old',domain,'one','two')==expected
        crypto.hash('old',domain,'one','two')!=crypto.hash('old','armoney-legacy-migration-final-completion-v1','one','two')
        crypto.hash('old',domain,'one','two')!=crypto.hash('old',domain,'two','one')
        when:crypto.hash('old','armoney-legacy-migration-user-release-v2','one','two')
        then:thrown(LegacyMigrationOtp.Failure)
        when:crypto.hash('old','arbitrary-purpose','one','two')
        then:thrown(LegacyMigrationOtp.Failure)
    }
    def 'original retained HMAC key exact domain and field order are preserved across rotation'(){
        given:
        def first=new LegacyMigrationManagedProofCrypto('old',[old:([31]*32) as byte[]])
        def rotated=new LegacyMigrationManagedProofCrypto('new',[old:([31]*32) as byte[],new:([32]*32) as byte[]])
        def original=first.hash('old','armoney-legacy-migration-managed-receipt-v1','one','two')
        expect:
        original==rotated.hash('old','armoney-legacy-migration-managed-receipt-v1','one','two')
        original!=rotated.hash('new','armoney-legacy-migration-managed-receipt-v1','one','two')
        original!=rotated.hash('old','armoney-legacy-migration-target-v1','one','two')
        original!=rotated.hash('old','armoney-legacy-migration-managed-receipt-v1','two','one')
        when:new LegacyMigrationManagedProofCrypto('new',[new:([32]*32) as byte[]]).hash('old','armoney-legacy-migration-target-v1','one')
        then:thrown(LegacyMigrationOtp.Failure)
    }
    def 'constructor copies material rejects duplicate keys and never renders material or callbacks'(){
        given:
        byte[] material=([31]*32) as byte[]
        def crypto=new LegacyMigrationManagedProofCrypto('old',[old:material])
        def hash=crypto.hash('old','armoney-legacy-migration-managed-state-v1','synthetic-secret-state')
        Arrays.fill(material,(byte)0)
        expect:
        hash==crypto.hash('old','armoney-legacy-migration-managed-state-v1','synthetic-secret-state')
        !new MigrationManagedProofPort.Callback('synthetic-code-canary','synthetic-state-canary','synthetic-cookie-canary').toString().contains('canary')
        !crypto.toString().contains('31')
        crypto.token()==~'[A-Za-z0-9_-]{43}'
        when:new LegacyMigrationManagedProofCrypto('one',[one:([31]*32) as byte[],two:([31]*32) as byte[]])
        then:thrown(LegacyMigrationOtp.Failure)
    }
    def 'OIDC account command hash never aliases local password mode or another association generation'(){
        given:
        def crypto=new LegacyMigrationCrypto('aead',[aead:([71]*32) as byte[]],([72]*32) as byte[])
        def ownerId=UUID.randomUUID()
        def command=UUID.randomUUID()
        def migration=UUID.randomUUID()
        def local=crypto.command(ownerId,command,migration,1,2,3)
        def oidc=crypto.commandOidc(ownerId,command,migration,1,4,2,3,'https://issuer.example/realm','signed-owner','a'*64)
        expect:
        local!=oidc
        oidc!=crypto.commandOidc(ownerId,command,migration,1,5,2,3,'https://issuer.example/realm','signed-owner','a'*64)
        oidc!=crypto.commandOidc(ownerId,command,migration,1,4,2,3,'https://issuer.example/realm','another-owner','a'*64)
    }
}
