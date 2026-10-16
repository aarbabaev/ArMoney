package com.arman.bank.authservice

import com.arman.bank.authservice.infrastructure.CodeIntentDetailCrypto
import com.arman.bank.authservice.domain.LoginProof
import spock.lang.Specification
import java.time.Instant

class CodeIntentDetailCryptoSpec extends Specification {
    static String key(int value){Base64.encoder.encodeToString(([value]*32) as byte[])}
    def crypto=new CodeIntentDetailCrypto('one',['one':key(1)],[] as Set)
    byte[] nonce=new byte[12]
    byte[] aad=CodeIntentDetailCrypto.aad(UUID.fromString('00000000-0000-0000-0000-000000000001'),'retention',new byte[32],new byte[32],'code',Instant.parse('2026-10-17T00:00:00Z'),Instant.parse('2026-10-17T00:02:00Z'))
    def 'real AES256 GCM round trip uses exact canonical length prefixed AAD and no plaintext encoding'(){
        when: def encrypted=crypto.encrypt('one',nonce,'opaque-authentication-session',aad)
        then:
        crypto.decrypt('one',nonce,encrypted,aad)=='opaque-authentication-session'
        !new String(encrypted,java.nio.charset.StandardCharsets.ISO_8859_1).contains('opaque-authentication-session')
        encrypted.length=='opaque-authentication-session'.bytes.length+16
        !crypto.toString().contains(key(1))
        new DataInputStream(new ByteArrayInputStream(aad)).readInt()=='armoney-intent-auth-session-v1'.bytes.length
    }
    def 'tampering ciphertext tag nonce AAD or selecting wrong material always denies #change'(){
        given:
        def encrypted=crypto.encrypt('one',nonce,'opaque-session',aad)
        byte[] changedNonce=nonce.clone(),changedAad=aad.clone()
        def selected=crypto
        if(change=='ciphertext')encrypted[0]=(byte)(encrypted[0]^1)
        if(change=='tag')encrypted[encrypted.length-1]=(byte)(encrypted[encrypted.length-1]^1)
        if(change=='nonce')changedNonce[0]=1
        if(change=='aad')changedAad[0]=(byte)(changedAad[0]^1)
        if(change=='key')selected=new CodeIntentDetailCrypto('one',['one':key(2)],[] as Set)
        when: selected.decrypt('one',changedNonce,encrypted,changedAad)
        then: def failure=thrown(LoginProof.Failure);failure.error()==LoginProof.Error.UNAVAILABLE
        where: change << ['ciphertext','tag','nonce','aad','key']
    }
    def 'strict pre decrypt bounds reject malformed envelopes #size'(){
        when: crypto.decrypt('one',new byte[nonceSize],size < 0 ? null : new byte[size],aad)
        then: def failure=thrown(LoginProof.Failure);failure.error()==LoginProof.Error.UNAVAILABLE
        where:
        nonceSize | size
        11 | 20
        13 | 20
        12 | -1
        12 | 0
        12 | 15
        12 | 1025
    }
    def 'encryption rejects invalid opaque binding instead of trimming or coercing #value'(){
        when: crypto.encrypt('one',nonce,value,aad)
        then: def failure=thrown(LoginProof.Failure);failure.error()==LoginProof.Error.UNAVAILABLE
        where: value << [null,'',' ',' leading','trailing ','line\nfeed','a'*256,'\uD800','\uDC00']
    }
    def 'independent ring is bounded canonical unique material and current cannot be retired'(){
        when: new CodeIntentDetailCrypto(current,ring,retired)
        then: thrown(IllegalArgumentException)
        where:
        current | ring | retired
        'one' | [:] | ([] as Set)
        'one' | ['one':key(1),'two':key(1)] | ([] as Set)
        'one' | ['one':key(1)] | (['one'] as Set)
        'one' | ['one':key(1).replace('=','')] | ([] as Set)
        'one' | (1..9).collectEntries{[(it==1?'one':'k'+it):key(it)]} | ([] as Set)
    }
}
