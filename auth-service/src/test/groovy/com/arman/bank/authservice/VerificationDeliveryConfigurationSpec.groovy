package com.arman.bank.authservice.infrastructure

import spock.lang.Specification

class VerificationDeliveryConfigurationSpec extends Specification {
    static Map environment() {
        [AUTH_VERIFICATION_DELIVERY_ENABLED: 'true', VERIFICATION_SMS_SEND_TOKEN: 'synthetic_sms_sender_token_32_characters',
         MAILTRAP_SMTP_USERNAME: 'synthetic-user', MAILTRAP_SMTP_PASSWORD: 'synthetic-password', MAILTRAP_FROM_EMAIL: 'synthetic@example.test',
         VERIFICATION_KEY_ID: 'test', VERIFICATION_OTP_HMAC_KEY: Base64.encoder.encodeToString(([11] * 32) as byte[]),
         VERIFICATION_DELIVERY_ENCRYPTION_KEY: Base64.encoder.encodeToString(([22] * 32) as byte[])]
    }
    def 'disabled mode requires no secrets and creates no worker or external client'() {
        given:
        def config = new VerificationDeliveryConfiguration([VERIFICATION_SMS_BASE_URL: 'https://untrusted.example', MAILTRAP_SMTP_HOST: 'bad'])
        expect:
        !config.enabled && config.worker(null) == null
        config.email == null && config.smsBase == null && config.smsToken == null
    }
    // Proof adapters are not packaged. This verifies actual delivery isolation and unchanged caller flags.
    def 'delivery SMTP configuration preserves independent flags and does not enable notices'() {
        given:
        def env = environment() + [LOGIN_NOTIFICATIONS_ENABLED: 'false', AUTH_VERIFICATION_BRIDGE_ENABLED: 'false', AUTH_LOGIN_PROOF_BRIDGE_ENABLED: 'false']
        def before = new HashMap(env)
        def config = new VerificationDeliveryConfiguration(env)
        expect:
        config.enabled && config.email.enabled
        config.email.smtpHost == 'sandbox.smtp.mailtrap.io'
        config.smsBase.toString() == 'http://sms-sandbox:8080'
        env.LOGIN_NOTIFICATIONS_ENABLED == 'false'
        !new EmailConfiguration(env).enabled
        !new VerificationConfiguration(env).enabled
        env.AUTH_LOGIN_PROOF_BRIDGE_ENABLED == 'false'
        env == before
        !config.toString().contains(env.VERIFICATION_SMS_SEND_TOKEN)
    }
    def 'enabled mode fails startup without required independently provisioned secrets'() {
        given:
        def env = environment(); env.remove(name)
        when:
        new VerificationDeliveryConfiguration(env)
        then:
        thrown(IllegalArgumentException)
        where:
        name << ['VERIFICATION_SMS_SEND_TOKEN','MAILTRAP_SMTP_USERNAME','MAILTRAP_SMTP_PASSWORD','MAILTRAP_FROM_EMAIL',
                 'VERIFICATION_KEY_ID','VERIFICATION_OTP_HMAC_KEY','VERIFICATION_DELIVERY_ENCRYPTION_KEY']
    }
    def 'production configuration cannot select public loopback credential-bearing or path-changing SMS targets'() {
        when:
        new VerificationDeliveryConfiguration(environment() + [VERIFICATION_SMS_BASE_URL: target])
        then:
        thrown(IllegalArgumentException)
        where:
        target << ['http://127.0.0.1:8080', 'https://public.example', 'http://sms-sandbox:8080/api/messages',
                   'http://user@sms-sandbox:8080', 'http://sms-sandbox:8080?key=x', 'http://sms-sandbox:8080#fragment',
                   'http://sms-sandbox:8081', 'http://sms-sandbox:8080/%2f']
    }
    def 'sandbox-only SMTP host mode and port validation remains mandatory'() {
        when:
        new VerificationDeliveryConfiguration(environment() + [(name): value])
        then:
        thrown(IllegalArgumentException)
        where:
        name                | value
        'MAILTRAP_MODE'      | 'sending'
        'MAILTRAP_SMTP_HOST' | 'smtp.example'
        'MAILTRAP_SMTP_PORT' | '465'
    }
    def 'sender credential cannot reuse sandbox reader or business credential'() {
        when:
        def env = environment(); env[name] = env.VERIFICATION_SMS_SEND_TOKEN
        new VerificationDeliveryConfiguration(env)
        then:
        thrown(IllegalArgumentException)
        where:
        name << ['SMS_SANDBOX_READ_TOKEN', 'INTERNAL_AUTH_KEY', 'AUTH_TO_SSO_STATUS_KEY']
    }
}
