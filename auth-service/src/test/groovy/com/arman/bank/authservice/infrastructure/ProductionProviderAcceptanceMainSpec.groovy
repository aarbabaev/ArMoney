package com.arman.bank.authservice.infrastructure

import com.arman.bank.authservice.CodeConfirmationConfigurationSpec
import groovy.json.JsonOutput
import spock.lang.Specification

/** Pure startup checks only: never constructs Database, HTTP clients, listeners or workers. */
class ProductionProviderAcceptanceMainSpec extends Specification {
    static final List<String> OWN=['AUTH_PRODUCTION_ACCEPTANCE_KEY','AUTH_PRODUCTION_ACCEPTANCE_ADMIN_SECRET']
    static final List<String> CSV=['SSO_LOGIN_PROOF_RETENTION_HMAC_PREVIOUS_KEYS','SSO_CODE_INTENT_DETAIL_ENCRYPTION_PREVIOUS_KEYS','AUTH_MANAGED_BANKING_PREVIOUS_KEYS']
    static final List<String> RINGS=['AUTH_MIGRATION_CUSTODY_ENCRYPTION_KEYS','AUTH_MIGRATION_DIGEST_KEYS','AUTH_MIGRATION_PROOF_KEYS',
        'AUTH_MIGRATION_OTP_HMAC_KEYS','AUTH_MIGRATION_OTP_ENCRYPTION_KEYS','ARMONEY_CODE_INTENT_FIXTURE_LOCAL_MATERIAL','ARMONEY_CODE_INTENT_CODE_HMAC_KEYS']
    static final String RAW='?'*32
    static Map inventory(){[AUTH_PRODUCTION_ACCEPTANCE_KEY:'synthetic-distinct-controller-material',AUTH_PRODUCTION_ACCEPTANCE_ADMIN_SECRET:'synthetic-distinct-admin-material']}
    static List<String> encodings(String raw=RAW){
        byte[] bytes=raw.getBytes('UTF-8')
        [Base64.encoder.encodeToString(bytes),Base64.encoder.withoutPadding().encodeToString(bytes),
            Base64.urlEncoder.encodeToString(bytes),Base64.urlEncoder.withoutPadding().encodeToString(bytes)]
    }
    static String container(String name,String value){CSV.contains(name)?'prior:'+value:JsonOutput.toJson([prior:value])}
    static Map startup(){CodeConfirmationConfigurationSpec.environment()+inventory()+[
        CI:'true',PRODUCTION_PROVIDER_ACCEPTANCE_ISOLATED:'true',PRODUCTION_PROVIDER_ACCEPTANCE_FRESH_FIXTURE:'true',PRODUCTION_PROVIDER_ACCEPTANCE_EXCLUSIVE_POLICY_WRITER:'true',
        AUTH_VERIFICATION_DELIVERY_ENABLED:'false',DB_URL:'jdbc:postgresql://auth-db:5432/bank',DB_USER:'bank',VERIFICATION_SMS_BASE_URL:'http://sms-sandbox:8080']}

    def 'independent configured scalar and known ring inventory is accepted without mutation'(){
        given:
        def env=inventory()+[INTERNAL_AUTH_KEY:'another-independent-synthetic-service-key',MAILTRAP_SMTP_USERNAME:'another-independent-synthetic-smtp-user',
            VERIFICATION_OTP_HMAC_KEY:Base64.encoder.encodeToString(([21]*32) as byte[]),SSO_CODE_INTENT_CODE_KEY_IDS:'code-v1,code-v2']
        (CSV+RINGS).eachWithIndex{name,index->env[name]=container(name,Base64.encoder.encodeToString(([(index+60)]*32) as byte[]))}
        def before=new LinkedHashMap(env)
        when:ProductionProviderAcceptanceMain.validateCredentials(env)
        then:noExceptionThrown();env==before
    }
    def 'every known JSON and CSV ring exposes std URL padded and unpadded aliases for both fixture roles'(){
        given:def env=inventory();env[own]=RAW;env[ring]=container(ring,encoded)
        when:ProductionProviderAcceptanceMain.validateCredentials(env)
        then:
        def rejected=thrown(ProductionProviderAcceptanceController.Rejected)
        rejected.status==503;rejected.message=='production_acceptance_rejected';rejected.cause==null
        where:
        [own,ring,encoded] << [OWN,CSV+RINGS,encodings()].combinations()
    }
    def 'decoded alias comparison works from encoded fixture credentials to raw scalar roles'(){
        given:def env=inventory();env[own]=encoded;env[other]=RAW
        when:ProductionProviderAcceptanceMain.validateCredentials(env)
        then:thrown(ProductionProviderAcceptanceController.Rejected)
        where:[own,other,encoded] << [OWN,['INTERNAL_AUTH_KEY','AUTH_TO_SSO_STATUS_KEY','MAILTRAP_SMTP_USERNAME','SMS_SANDBOX_READ_TOKEN'],encodings()].combinations()
    }
    def 'fixture credentials cannot alias one another across encodings'(){
        given:def env=inventory();env.AUTH_PRODUCTION_ACCEPTANCE_KEY=first;env.AUTH_PRODUCTION_ACCEPTANCE_ADMIN_SECRET=second
        when:ProductionProviderAcceptanceMain.validateCredentials(env)
        then:thrown(ProductionProviderAcceptanceController.Rejected)
        where:[first,second] << [[RAW]+encodings(),[RAW]+encodings()].combinations()
    }
    def 'strict known containers reject duplicate malformed nested nonbinary and oversized input'(){
        given:def env=inventory();env[name]=value
        when:ProductionProviderAcceptanceMain.validateCredentials(env)
        then:
        def rejected=thrown(ProductionProviderAcceptanceController.Rejected)
        rejected.message=='production_acceptance_rejected';rejected.cause==null
        where:
        name | value
        'AUTH_MIGRATION_OTP_HMAC_KEYS' | '{"a":"x","a":"y"}'
        'AUTH_MIGRATION_PROOF_KEYS' | '{"a":"x"} {}'
        'AUTH_MIGRATION_PROOF_KEYS' | '{"a":{"key":"x"}}'
        'AUTH_MIGRATION_PROOF_KEYS' | '{"a":42}'
        'AUTH_MIGRATION_PROOF_KEYS' | '{}'
        'AUTH_MIGRATION_PROOF_KEYS' | '[]'
        'AUTH_MIGRATION_PROOF_KEYS' | JsonOutput.toJson([a:Base64.encoder.encodeToString(([1]*31) as byte[])])
        'AUTH_MIGRATION_PROOF_KEYS' | ('x'*16385)
        'SSO_LOGIN_PROOF_RETENTION_HMAC_PREVIOUS_KEYS' | 'a:x,a:y'
        'SSO_LOGIN_PROOF_RETENTION_HMAC_PREVIOUS_KEYS' | 'a:x,'
        'AUTH_MANAGED_BANKING_PREVIOUS_KEYS' | 'a=x'
        'SSO_CODE_INTENT_DETAIL_ENCRYPTION_PREVIOUS_KEYS' | 'a:x:b'
    }
    def 'unknown nonempty credential containers reject instead of hiding material'(){
        given:def env=inventory();env[name]=value
        when:ProductionProviderAcceptanceMain.validateCredentials(env)
        then:thrown(ProductionProviderAcceptanceController.Rejected)
        where:
        name | value
        'FUTURE_KEYS' | '{"v1":"opaque"}'
        'FUTURE_KEY_RING' | 'v1:opaque'
        'FUTURE_SECRETS' | 'opaque'
        'FUTURE_TOKENS' | '["opaque"]'
        'FUTURE_PASSWORDS' | 'opaque'
        'FUTURE_KEY' | '{"v1":"opaque"}'
        'FUTURE_TOKEN' | 'v1:opaque'
        'FUTURE_SECRET' | 'one,two'
        'FUTURE_MATERIAL' | '{"v1":"opaque"}'
        'FUTURE_RING' | 'opaque'
        'FUTURE_CREDENTIALS' | 'opaque'
        'FUTURE_SECRET_FILE' | '/private/credential'
    }
    def 'actual startup validates credentials and sanitizes rejection before resource allocation'(){
        given:def env=startup();env[own]=RAW;env.AUTH_MIGRATION_OTP_HMAC_KEYS=JsonOutput.toJson([v1:encodings().get(2)])
        when:ProductionProviderAcceptanceMain.validate(env)
        then:
        def rejected=thrown(ProductionProviderAcceptanceController.Rejected)
        rejected.status==503;rejected.message=='production_acceptance_rejected';rejected.cause==null
        where:own << OWN
    }
    def 'actual valid startup configuration remains pure and accepted'(){
        given:def env=startup();def before=new LinkedHashMap(env)
        when:ProductionProviderAcceptanceMain.validate(env)
        then:noExceptionThrown();env==before
    }
}
