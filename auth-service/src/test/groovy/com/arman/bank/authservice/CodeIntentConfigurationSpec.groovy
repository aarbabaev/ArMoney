package com.arman.bank.authservice

import com.arman.bank.authservice.infrastructure.CodeIntentConfiguration
import com.arman.bank.authservice.infrastructure.CodeIntentRetentionKeys
import com.arman.bank.authservice.domain.LoginProof
import spock.lang.Specification

class CodeIntentConfigurationSpec extends Specification {
    def 'default off allocates no resources and ignores dormant missing keys'(){
        expect:
        !new CodeIntentConfiguration([:]).enabled
        new CodeIntentConfiguration([:]).open(null).isEmpty()
    }
    def 'flag is strict literal boolean #value'(){
        when: new CodeIntentConfiguration(['AUTH_SSO_CODE_INTENT_STAGE_ENABLED':value])
        then: thrown(IllegalArgumentException)
        where: value << ['TRUE','1','',' false','False',null]
    }
    def 'enabled requires full managed authority configuration'(){
        when: new CodeIntentConfiguration(['AUTH_SSO_CODE_INTENT_STAGE_ENABLED':'true'])
        then: thrown(IllegalArgumentException)
    }
    static String encoded(int value){Base64.encoder.encodeToString(([value]*32) as byte[])}
    static Map environment(){[
        AUTH_VERIFICATION_AUTHORITY_BRIDGE_ENABLED:'true',SSO_VERIFICATION_BRIDGE_KEY:'synthetic-incoming-bridge-credential-unique',
        AUTH_TO_SSO_STATUS_KEY:'synthetic-outgoing-observation-credential',AUTH_VERIFICATION_AUTHORITY_RETENTION_KEY:encoded(11),
        VERIFICATION_OTP_HMAC_KEY:encoded(12),VERIFICATION_DELIVERY_ENCRYPTION_KEY:encoded(13),VERIFICATION_KEY_ID:'fixture-v1',
        SSO_ISSUER:'https://issuer.example/sso/realms/armoney',SSO_VERIFICATION_CLIENT_IDS:'armoney-ios,armoney-web',SSO_ENROLLMENT_STATUS_ORIGIN:'http://sso-service:8080',
        AUTH_REGISTRATION_AUTHORITY_COMPLETION_ENABLED:'true',AUTH_VERIFICATION_PROJECTION_ENABLED:'true',
        AUTH_VERIFICATION_PROJECTION_WRITER_ID:'auth-projection',AUTH_TO_USER_VERIFICATION_PROJECTION_KEY:'synthetic-projection-credential-unique',
        AUTH_VERIFICATION_PROJECTION_RETENTION_KEY:encoded(14),USER_BASE_URL:'http://user-service:8080',AUTH_REGISTRATION_ELIGIBILITY_GRANTS_ENABLED:'true',
        AUTH_SSO_CODE_INTENT_STAGE_ENABLED:'true',SSO_LOGIN_PROOF_RETENTION_HMAC_KEY_ID:'stage-v1',SSO_LOGIN_PROOF_RETENTION_HMAC_KEY:encoded(15),
        SSO_CODE_INTENT_DETAIL_ENCRYPTION_KEY_ID:'aead-v1',SSO_CODE_INTENT_DETAIL_ENCRYPTION_KEY:encoded(16),
        SSO_CODE_INTENT_ISSUERS:'https://issuer.example/sso/realms/armoney',SSO_CODE_INTENT_CLIENTS:'armoney-web',SSO_CODE_INTENT_CODE_KEY_IDS:'code-v1',SSO_CODE_INTENT_ACTORS:'sso-provider']}
    def 'complete explicit configuration can construct dormant factory without starting resources'(){
        expect: new CodeIntentConfiguration(environment()).enabled
    }
    def 'every enabled intent scope or retention setting is required #name'(){
        given: def env=environment();env.remove(name)
        when: new CodeIntentConfiguration(env)
        then: thrown(IllegalArgumentException)
        where: name << ['SSO_LOGIN_PROOF_RETENTION_HMAC_KEY_ID','SSO_LOGIN_PROOF_RETENTION_HMAC_KEY','SSO_CODE_INTENT_ISSUERS','SSO_CODE_INTENT_CLIENTS','SSO_CODE_INTENT_CODE_KEY_IDS','SSO_CODE_INTENT_ACTORS','SSO_CODE_INTENT_DETAIL_ENCRYPTION_KEY_ID','SSO_CODE_INTENT_DETAIL_ENCRYPTION_KEY']
    }
    def 'retention keys cannot equal other cryptographic or transport material #foreign'(){
        given: def env=environment();env[foreign]=env.SSO_LOGIN_PROOF_RETENTION_HMAC_KEY
        when: new CodeIntentConfiguration(env)
        then: thrown(IllegalArgumentException)
        where: foreign << ['SSO_LOGIN_CODE_HMAC_KEY','SSO_LOGIN_PROOF_WRITE_KEY','SSO_LOGIN_PROOF_RESOLVE_KEY','MAILTRAP_SMTP_PASSWORD']
    }
    def 'detail encryption cannot reuse current or retained HMAC material or retire its current key'(){
        when: new CodeIntentConfiguration(environment()+change)
        then: thrown(IllegalArgumentException)
        where:
        change << [[SSO_CODE_INTENT_DETAIL_ENCRYPTION_KEY:encoded(15)],
                   [SSO_CODE_INTENT_DETAIL_ENCRYPTION_PREVIOUS_KEYS:'old:'+encoded(15)],
                   [SSO_LOGIN_PROOF_RETENTION_HMAC_PREVIOUS_KEYS:'old:'+encoded(16)],
                   [SSO_CODE_INTENT_DETAIL_ENCRYPTION_RETIRED_KEY_IDS:'aead-v1']]
    }
    def 'retention ring rejects invalid or more than eight keys'(){
        when: new CodeIntentRetentionKeys('one',ring)
        then: thrown(IllegalArgumentException)
        where:
        ring << [[:],['one':'not-base64'],['one':Base64.encoder.encodeToString(new byte[31])],
                 (1..9).collectEntries{[(it==1?'one':'k'+it):Base64.encoder.encodeToString(new byte[32])]}]
    }
    def 'hashes are domain separated length prefixed and old key lookup never falls back'(){
        given:
        def keys=new CodeIntentRetentionKeys('new',['new':Base64.encoder.encodeToString(new byte[32]),'old':Base64.encoder.encodeToString((byte[])([1]*32))])
        expect:
        !Arrays.equals(keys.hash('old','armoney-intent-payload-v1','a','bc'),keys.hash('old','armoney-intent-payload-v1','ab','c'))
        !Arrays.equals(keys.hash('old','armoney-intent-payload-v1','a'),keys.hash('old','armoney-intent-binding-v1','a'))
        when: keys.hash('missing','armoney-intent-payload-v1','a')
        then:
        def failure=thrown(LoginProof.Failure)
        failure.error()==LoginProof.Error.UNAVAILABLE
    }
}
