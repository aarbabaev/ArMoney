package com.arman.bank.authservice.infrastructure

import com.arman.bank.authservice.domain.ManagedBankingAccess
import com.arman.bank.authservice.domain.LoginProof
import spock.lang.Specification
import java.time.Instant

class ManagedBankingKeysSpec extends Specification {
    def 'fingerprint binds exact token and tuple with immutable original key'(){
        given:
        def material=Base64.encoder.encodeToString(([91]*32) as byte[])
        def keys=new ManagedBankingKeys('first',[first:material])
        def binding=new LoginProof.Binding('https://issuer.example','subject','armoney-web','sid')
        def event=UUID.randomUUID()
        def first=new ManagedBankingAccess.Token(binding,event,Instant.EPOCH,Instant.EPOCH.plusSeconds(30),'fixture@example.test','+971501234567','first.token',true)
        def second=new ManagedBankingAccess.Token(binding,event,first.issuedAt(),first.expiresAt(),first.email(),first.phone(),'second.token',true)
        when:def hash=keys.fingerprint('first',first);hash[0]++
        then:!Arrays.equals(hash,keys.fingerprint('first',first));!Arrays.equals(keys.fingerprint('first',first),keys.fingerprint('first',second))
        when:keys.fingerprint('retired',first)
        then:thrown(LoginProof.Failure)
    }
    def 'migration vector binds immutable source proof and token times without changing registration domain'(){
        given:
        def keys=new ManagedBankingKeys('first',[first:Base64.encoder.encodeToString(([91]*32) as byte[])])
        def binding=new LoginProof.Binding('https://issuer.example','subject','armoney-web','sid')
        def source=UUID.fromString('00000000-0000-0000-0000-000000000002')
        def event=UUID.fromString('00000000-0000-0000-0000-000000000003')
        def proof=UUID.fromString('00000000-0000-0000-0000-000000000004')
        def token=new ManagedBankingAccess.Token(binding,event,Instant.parse('2026-10-18T00:00:00Z'),Instant.parse('2026-10-18T00:00:30Z'),'fixture@example.test','+971501234567','synthetic.token',true)
        expect:
        Arrays.equals(keys.fingerprint('first',token),keys.fingerprint('first',new ManagedBankingAccess.Token(binding,event,token.issuedAt(),token.expiresAt(),token.email(),token.phone(),token.bearer(),false)))
        Arrays.equals(keys.fingerprint('first',token,source,1L,1L,1L,proof),keys.fingerprint('first',new ManagedBankingAccess.Token(binding,event,token.issuedAt(),token.expiresAt(),token.email(),token.phone(),token.bearer(),false),source,1L,1L,1L,proof))
        HexFormat.of().formatHex(keys.fingerprint('first',token,source,1L,1L,1L,proof))=='edd95566213f30565e15bca0cb7a0791940bd717410c3c1f1d4c1296b5f506a8'
        !Arrays.equals(keys.fingerprint('first',token),keys.fingerprint('first',token,source,1L,1L,1L,proof))
        !Arrays.equals(keys.fingerprint('first',token,source,1L,1L,1L,proof),keys.fingerprint('first',token,source,2L,1L,1L,proof))
        !Arrays.equals(keys.fingerprint('first',token,source,1L,1L,1L,proof),keys.fingerprint('first',token,source,1L,1L,2L,proof))
        !Arrays.equals(keys.fingerprint('first',token,source,1L,1L,1L,proof),keys.fingerprint('first',token,proof,1L,1L,1L,source))
    }
    def 'duplicate key material cannot be called independent'(){
        given:def material=Base64.encoder.encodeToString(([91]*32) as byte[])
        when:new ManagedBankingKeys('first',[first:material,second:material])
        then:thrown(IllegalArgumentException)
    }
}
