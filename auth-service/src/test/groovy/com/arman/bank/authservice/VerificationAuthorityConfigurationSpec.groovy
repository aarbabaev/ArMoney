package com.arman.bank.authservice

import com.arman.bank.authservice.infrastructure.VerificationAuthorityConfiguration
import spock.lang.Specification

class VerificationAuthorityConfigurationSpec extends Specification {
    static Map environment(){[AUTH_VERIFICATION_AUTHORITY_BRIDGE_ENABLED:'true',SSO_VERIFICATION_BRIDGE_KEY:'synthetic-incoming-bridge-credential-unique',
        AUTH_TO_SSO_STATUS_KEY:'synthetic-outgoing-observation-credential',AUTH_VERIFICATION_AUTHORITY_RETENTION_KEY:encoded(11),
        VERIFICATION_OTP_HMAC_KEY:encoded(12),VERIFICATION_DELIVERY_ENCRYPTION_KEY:encoded(13),VERIFICATION_KEY_ID:'fixture-v1',
        SSO_ISSUER:'https://issuer.example/sso/realms/armoney',SSO_VERIFICATION_CLIENT_IDS:'armoney-ios,armoney-web',SSO_ENROLLMENT_STATUS_ORIGIN:'http://sso-service:8080']}
    static String encoded(int value){Base64.encoder.encodeToString(([value]*32) as byte[])}
    def 'default off allocates no store routes or client and ignores unused settings'(){
        given:
        def config=new VerificationAuthorityConfiguration([SSO_ENROLLMENT_STATUS_ORIGIN:'invalid',AUTH_TO_SSO_STATUS_KEY:'invalid'])
        expect:
        !config.enabled
        config.open(null)==null
        !config.toString().contains('invalid')
    }
    def 'independent valid configuration enables only the staging resource factory'(){
        given:
        def env=environment()
        when:
        def config=new VerificationAuthorityConfiguration(env)
        then:
        config.enabled
        !env.containsKey('AUTH_VERIFICATION_PROJECTION_ENABLED')
        !env.containsKey('AUTH_LOGIN_PROOF_BRIDGE_ENABLED')
        !env.containsKey('AUTH_VERIFICATION_DELIVERY_ENABLED')
        !config.toString().contains(env.AUTH_TO_SSO_STATUS_KEY)
    }
    def 'every required authority setting must be explicit'(){
        given:
        def env=environment()
        env.remove(name)
        when:
        new VerificationAuthorityConfiguration(env)
        then:
        thrown(IllegalArgumentException)
        where:
        name << ['SSO_VERIFICATION_BRIDGE_KEY','AUTH_TO_SSO_STATUS_KEY','AUTH_VERIFICATION_AUTHORITY_RETENTION_KEY','VERIFICATION_OTP_HMAC_KEY',
            'VERIFICATION_DELIVERY_ENCRYPTION_KEY','VERIFICATION_KEY_ID','SSO_ISSUER','SSO_VERIFICATION_CLIENT_IDS','SSO_ENROLLMENT_STATUS_ORIGIN']
    }
    def 'legacy overlap nonliteral flags and unsafe origins reject startup'(){
        when:
        new VerificationAuthorityConfiguration(environment()+change)
        then:
        thrown(IllegalArgumentException)
        where:
        change << [[AUTH_VERIFICATION_BRIDGE_ENABLED:'true'],[AUTH_VERIFICATION_AUTHORITY_BRIDGE_ENABLED:'TRUE'],
            [SSO_VERIFICATION_CLIENT_IDS:'armoney-ios,armoney-ios'],[SSO_VERIFICATION_CLIENT_IDS:'armoney-ios,'],
            [AUTH_VERIFICATION_AUTHORITY_RETENTION_KEY:encoded(11).replace('=','')],[SSO_ENROLLMENT_STATUS_ORIGIN:'http://127.0.0.1:8080'],
            [SSO_ENROLLMENT_STATUS_ORIGIN:'http://sso-service:9090'],[SSO_ENROLLMENT_STATUS_ORIGIN:'https://user@provider.example'],
            [SSO_ENROLLMENT_STATUS_ORIGIN:'https://provider.example/sso'],[SSO_ENROLLMENT_STATUS_ORIGIN:'https://provider.example?x=1'],
            [SSO_ENROLLMENT_STATUS_ORIGIN:'https://provider.example#x']]
    }
    def 'directional credentials and all owned cryptographic keys must differ'(){
        given:
        def env=environment()
        env[target]=env[source]
        when:
        new VerificationAuthorityConfiguration(env)
        then:
        thrown(IllegalArgumentException)
        where:
        target                                       | source
        'AUTH_TO_SSO_STATUS_KEY'                      | 'SSO_VERIFICATION_BRIDGE_KEY'
        'AUTH_VERIFICATION_AUTHORITY_RETENTION_KEY'   | 'VERIFICATION_OTP_HMAC_KEY'
        'VERIFICATION_DELIVERY_ENCRYPTION_KEY'        | 'VERIFICATION_OTP_HMAC_KEY'
    }
    def 'business proof binding and both code aliases reject raw and encoded key reuse'(){
        given:
        def env=environment()
        String raw='a'*32
        if(secret=='retention')env.AUTH_VERIFICATION_AUTHORITY_RETENTION_KEY=Base64.encoder.encodeToString(raw.bytes)
        else env.AUTH_TO_SSO_STATUS_KEY=raw
        env[foreign]=encoded?Base64.encoder.encodeToString(raw.bytes):raw
        when:
        new VerificationAuthorityConfiguration(env)
        then:
        thrown(IllegalArgumentException)
        where:
        foreign                     | secret      | encoded
        'INTERNAL_AUTH_KEY'          | 'transport' | false
        'SSO_LOGIN_PROOF_WRITE_KEY'   | 'transport' | true
        'SSO_LOGIN_PROOF_RESOLVE_KEY' | 'retention' | false
        'ARMONEY_PROVIDER_BINDING_KEY'| 'retention' | true
        'VERIFICATION_SMS_SEND_TOKEN' | 'transport' | false
        'SSO_LOGIN_CODE_HMAC_KEY'     | 'transport' | false
        'SSO_LOGIN_CODE_HMAC_KEY'     | 'retention' | true
        'ARMONEY_LOGIN_CODE_HMAC_KEY' | 'transport' | true
        'ARMONEY_LOGIN_CODE_HMAC_KEY' | 'retention' | false
    }
    def 'completion flag is independent strict default off and requires bridge and projection'(){
        expect:
        !new VerificationAuthorityConfiguration(environment()).completionEnabled
        when:
        new VerificationAuthorityConfiguration([AUTH_REGISTRATION_AUTHORITY_COMPLETION_ENABLED:value])
        then:
        thrown(IllegalArgumentException)
        where:
        value << ['true','TRUE','1',' true','']
    }
    def 'completion mode validates complete projection settings without activating a resource'(){
        given:
        def env=environment()+[AUTH_REGISTRATION_AUTHORITY_COMPLETION_ENABLED:'true',AUTH_VERIFICATION_PROJECTION_ENABLED:'true',
            AUTH_VERIFICATION_PROJECTION_WRITER_ID:'auth-projection',AUTH_TO_USER_VERIFICATION_PROJECTION_KEY:'synthetic-projection-credential-unique',
            AUTH_VERIFICATION_PROJECTION_RETENTION_KEY:encoded(14),USER_BASE_URL:'http://user-service:8080']
        expect:
        new VerificationAuthorityConfiguration(env).completionEnabled
        when:
        env.remove('AUTH_VERIFICATION_PROJECTION_RETENTION_KEY')
        new VerificationAuthorityConfiguration(env)
        then:
        thrown(IllegalArgumentException)
    }
    def 'eligibility grants are independently strict default off and cannot bypass completion prerequisites'(){
        expect:
        !new VerificationAuthorityConfiguration(environment()).eligibilityGrantsEnabled
        when:
        new VerificationAuthorityConfiguration(environment()+[AUTH_REGISTRATION_ELIGIBILITY_GRANTS_ENABLED:value])
        then:
        thrown(IllegalArgumentException)
        where:
        value << ['true','TRUE','1',' true','',null]
    }
    def 'eligibility grants accept only fully configured completion mode'(){
        given:
        def env=environment()+[AUTH_REGISTRATION_AUTHORITY_COMPLETION_ENABLED:'true',AUTH_VERIFICATION_PROJECTION_ENABLED:'true',
            AUTH_VERIFICATION_PROJECTION_WRITER_ID:'auth-projection',AUTH_TO_USER_VERIFICATION_PROJECTION_KEY:'synthetic-projection-credential-unique',
            AUTH_VERIFICATION_PROJECTION_RETENTION_KEY:encoded(14),USER_BASE_URL:'http://user-service:8080',AUTH_REGISTRATION_ELIGIBILITY_GRANTS_ENABLED:'true']
        expect:
        new VerificationAuthorityConfiguration(env).eligibilityGrantsEnabled
    }
}
