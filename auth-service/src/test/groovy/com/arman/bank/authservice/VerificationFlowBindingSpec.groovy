package com.arman.bank.authservice

import com.arman.bank.authservice.domain.IdentityVerification
import com.arman.bank.authservice.domain.IdentityVerification.*
import com.arman.bank.authservice.domain.Verification
import spock.lang.Specification

class VerificationFlowBindingSpec extends Specification {
    static final String ISSUER='https://issuer.example/realms/armoney'
    static final Policy POLICY=Policy.defaults([ISSUER] as Set,['mobile','web'] as Set,['provider'] as Set)
    static String token(int value){Base64.urlEncoder.withoutPadding().encodeToString([(byte)value]*32 as byte[])}
    static Flow binding(String client='mobile',String root='root',String tab='tab',String encoded=token(0)){new Flow(ISSUER,'subject',client,root,tab,encoded)}

    def 'retention denial context survives binding HMAC rotation while full tuple differs'(){
        given:
        def old=binding()
        def rotated=binding('mobile','root','tab',token(1))
        expect:
        Arrays.equals(IdentityVerification.contextFingerprint(old),IdentityVerification.contextFingerprint(rotated))
        !Arrays.equals(IdentityVerification.tuple(old),IdentityVerification.tuple(rotated))
        IdentityVerification.contextFingerprint(old).length==32
    }

    def 'new root tab or client is a different denial context'(){
        expect:
        !Arrays.equals(IdentityVerification.contextFingerprint(binding()),IdentityVerification.contextFingerprint(binding(client,root,tab)))
        where:
        client   | root    | tab
        'web'    | 'root'  | 'tab'
        'mobile' | 'root2' | 'tab'
        'mobile' | 'root'  | 'tab2'
    }

    def 'canonical HMAC bounds and invisible provider identifiers fail before use'(){
        when:
        IdentityVerification.validate(binding('mobile','root',tab,encoded),POLICY)
        then:
        def failure=thrown(Verification.Failure)
        failure.error()==Verification.Error.BAD_INPUT
        where:
        tab       | encoded
        'tab'     | 'x'
        'tab'     | token(0)+'='
        'tab'     | token(0).substring(0,42)+'B'
        'tab\n'   | token(0)
        'ta\u00a0b' | token(0)
    }

    def 'length prefixed contexts cannot alias concatenated identifiers'(){
        expect:
        !Arrays.equals(IdentityVerification.contextFingerprint(binding('mobile','ab','c')),IdentityVerification.contextFingerprint(binding('mobile','a','bc')))
    }
}
