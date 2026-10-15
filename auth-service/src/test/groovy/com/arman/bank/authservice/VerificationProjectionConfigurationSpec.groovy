package com.arman.bank.authservice

import com.arman.bank.authservice.infrastructure.VerificationProjectionConfiguration
import spock.lang.Specification

class VerificationProjectionConfigurationSpec extends Specification {
    static Map environment(){[AUTH_VERIFICATION_PROJECTION_ENABLED:'true',AUTH_VERIFICATION_PROJECTION_WRITER_ID:'auth',
        AUTH_TO_USER_VERIFICATION_PROJECTION_KEY:'synthetic-private-projection-key-32-characters',
        AUTH_VERIFICATION_PROJECTION_RETENTION_KEY:Base64.encoder.encodeToString(([33]*32) as byte[]),USER_BASE_URL:'http://user-service:8080']}
    def 'default disabled configuration creates no worker store or client and requires no secrets'(){
        given:
        def config=new VerificationProjectionConfiguration([USER_BASE_URL:'invalid'])
        expect:
        !config.enabled
        config.worker(null)==null
        config.store(null)==null
    }
    def 'enabled mode requires dedicated credentials and canonical retention key'(){
        given:
        def env=environment()
        env.remove(name)
        when:
        new VerificationProjectionConfiguration(env)
        then:
        thrown(IllegalArgumentException)
        where:
        name << ['AUTH_VERIFICATION_PROJECTION_WRITER_ID','AUTH_TO_USER_VERIFICATION_PROJECTION_KEY','AUTH_VERIFICATION_PROJECTION_RETENTION_KEY','USER_BASE_URL']
    }
    def 'credential material cannot equal business proof status OTP or encryption keys'(){
        given:
        def env=environment()
        env[name]=secret=='transport'?env.AUTH_TO_USER_VERIFICATION_PROJECTION_KEY:env.AUTH_VERIFICATION_PROJECTION_RETENTION_KEY
        when:
        new VerificationProjectionConfiguration(env)
        then:
        thrown(IllegalArgumentException)
        where:
        name                                  | secret
        'INTERNAL_AUTH_KEY'                    | 'transport'
        'AUTH_TO_SSO_STATUS_KEY'                | 'transport'
        'SSO_LOGIN_PROOF_WRITE_KEY'             | 'transport'
        'VERIFICATION_OTP_HMAC_KEY'             | 'retention'
        'VERIFICATION_DELIVERY_ENCRYPTION_KEY'  | 'retention'
        'SSO_LOGIN_PROOF_RETENTION_KEY'         | 'retention'
    }
    def 'strict literal flag fixed origin and secret validation fail startup'(){
        when:
        new VerificationProjectionConfiguration(environment()+change)
        then:
        thrown(IllegalArgumentException)
        where:
        change << [[AUTH_VERIFICATION_PROJECTION_ENABLED:'yes'],[AUTH_TO_USER_VERIFICATION_PROJECTION_KEY:'short'],
            [AUTH_VERIFICATION_PROJECTION_RETENTION_KEY:'not-base64'],[USER_BASE_URL:'http://user@user-service:8080'],
            [USER_BASE_URL:'http://user-service:8080/path'],[USER_BASE_URL:'http://user-service:8080?secret=x'],
            [USER_BASE_URL:'http://user-service:8080#fragment']]
    }
    def 'both code HMAC aliases reject transport or retention reuse in raw or canonical base64 representation'(){
        given:
        def env=environment()
        String raw='synthetic_retention_material_32x'
        assert raw.length()==32
        if(secret=='transport')env.AUTH_TO_USER_VERIFICATION_PROJECTION_KEY=raw
        else env.AUTH_VERIFICATION_PROJECTION_RETENTION_KEY=Base64.encoder.encodeToString(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8))
        env[alias]=encoded?Base64.encoder.encodeToString(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8)):raw
        when:
        new VerificationProjectionConfiguration(env)
        then:
        thrown(IllegalArgumentException)
        where:
        alias                          | secret      | encoded
        'SSO_LOGIN_CODE_HMAC_KEY'       | 'transport' | false
        'SSO_LOGIN_CODE_HMAC_KEY'       | 'transport' | true
        'SSO_LOGIN_CODE_HMAC_KEY'       | 'retention' | false
        'SSO_LOGIN_CODE_HMAC_KEY'       | 'retention' | true
        'ARMONEY_LOGIN_CODE_HMAC_KEY'   | 'transport' | false
        'ARMONEY_LOGIN_CODE_HMAC_KEY'   | 'transport' | true
        'ARMONEY_LOGIN_CODE_HMAC_KEY'   | 'retention' | false
        'ARMONEY_LOGIN_CODE_HMAC_KEY'   | 'retention' | true
    }
    def 'valid independent configuration cannot activate any banking flags'(){
        given:
        def env=environment()
        def config=new VerificationProjectionConfiguration(env)
        expect:
        config.enabled
        !env.containsKey('AUTH_SSO_LOGIN_PROOF_ENABLED')
        !env.containsKey('LOGIN_NOTIFICATIONS_ENABLED')
        !config.toString().contains(env.AUTH_TO_USER_VERIFICATION_PROJECTION_KEY)
    }
}
