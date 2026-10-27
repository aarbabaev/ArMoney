package com.arman.bank.authservice

import com.arman.bank.authservice.infrastructure.*
import spock.lang.Specification

class ManagedBankingConfigurationSpec extends Specification {
    static Map environment(){CodeConfirmationConfigurationSpec.environment()+[
        AUTH_MANAGED_BANKING_ENABLED:'true',AUTH_MANAGED_BANKING_KEY_ID:'bank-v1',
        AUTH_MANAGED_BANKING_KEY:Base64.encoder.encodeToString(([101]*32) as byte[]) ]}
    def 'off parses no key material or dependencies and allocates no resources'(){
        expect:!new ManagedBankingConfiguration([:]).enabled
        when:new ManagedBankingConfiguration([:]).keys()
        then:thrown(IllegalArgumentException)
    }
    def 'strict flag rejects every nonliteral form including explicit null'(){
        when:new ManagedBankingConfiguration([AUTH_MANAGED_BANKING_ENABLED:value])
        then:thrown(IllegalArgumentException)
        where:value << ['TRUE','1',' false','',null]
    }
    def 'enabled validates complete C2 and independent original key material'(){
        expect:new ManagedBankingConfiguration(environment()).enabled
        when:new ManagedBankingConfiguration(environment()+[AUTH_MANAGED_BANKING_KEY:environment().SSO_LOGIN_PROOF_RETENTION_KEY])
        then:thrown(IllegalArgumentException)
        when:new ManagedBankingConfiguration(environment()+[AUTH_SSO_CODE_INTENT_CONFIRMATION_ENABLED:'false'])
        then:thrown(IllegalArgumentException)
    }
    def 'raw and canonical encoded injected aliases are not independent and caller arrays remain unchanged'(){
        given:def env=environment();byte[] bytes=Base64.decoder.decode(env.AUTH_MANAGED_BANKING_KEY);byte[] before=bytes.clone()
        when:new ManagedBankingConfiguration(env,[other:bytes])
        then:thrown(IllegalArgumentException);Arrays.equals(bytes,before)
        when:new ManagedBankingConfiguration(env,[other:env.AUTH_MANAGED_BANKING_KEY.getBytes('UTF-8')])
        then:thrown(IllegalArgumentException)
    }
}
