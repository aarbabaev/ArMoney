package com.arman.bank.authservice.infrastructure

import spock.lang.Specification

class LegacyMigrationConfigurationSpec extends Specification {
    def 'default off accepts historical configuration without migration resources'() {
        expect:
        !LegacyMigrationConfiguration.from([SSO_ISSUER:'https://legacy.invalid/realm'],[:]).enabled
    }
    def 'strict off rejects partial migration settings including empty values'() {
        when:
        LegacyMigrationConfiguration.from([(name):''],[:])
        then:
        thrown(IllegalArgumentException)
        where:
        name << ['AUTH_MIGRATION_ACTOR_ID','AUTH_MIGRATION_PROOF_KEYS','MIGRATION_PUBLIC_ORIGIN','MIGRATION_GATEWAY_TO_AUTH_KEY']
    }
    def 'selection flag accepts only strict literals'() {
        when:
        LegacyMigrationConfiguration.from([AUTH_MIGRATION_BROWSER_ENABLED:value],[:])
        then:
        thrown(IllegalArgumentException)
        where:
        value << ['TRUE','1','',' false','true ']
    }
    def 'complete selected configuration is pure and preserves caller values'() {
        given:
        def env=environment()
        def original=new HashMap(env)
        when:
        def c=LegacyMigrationConfiguration.from(env,[:])
        def bytes=c.encryption()['k1']
        bytes[0]=(byte)(bytes[0]^1)
        then:
        c.enabled
        env==original
        c.encryption()['k1'][0]!=bytes[0]
        c.browser.legacyCallback().path=='/migration/legacy/account-oidc/callback'
        c.observation.scopes().managed().size()==1
        c.observation.scopes().legacy().size()==1
        c.observation.scopes().classify(c.account.issuer().toString(),'legacy')==CodeIntentPairScopes.Classification.LEGACY
        c.observation.allowedPairs().size()==2
        !env.containsKey('AUTH_SSO_CODE_INTENT_CONFIRMATION_ENABLED')
        !env.containsKey('LOGIN_NOTIFICATIONS_ENABLED')
    }
    def 'missing selected field fails generically without attaching secret-bearing cause'() {
        given:
        def env=environment();env.remove(name)
        when:
        LegacyMigrationConfiguration.from(env,[:])
        then:
        def failure=thrown(IllegalArgumentException)
        failure.message=='Invalid legacy migration configuration'
        failure.cause==null
        where:
        name << ['AUTH_MIGRATION_ACTOR_ID','AUTH_MIGRATION_DIGEST_KEYS','AUTH_MIGRATION_OTP_ENCRYPTION_KEYS','AUTH_TO_USER_MIGRATION_STAGE_KEY','AUTH_TO_USER_MIGRATION_PUBLICATION_KEY','USER_TO_AUTH_MIGRATION_DECISION_KEY','AUTH_TO_SSO_STATUS_KEY','MIGRATION_PUBLIC_ORIGIN']
    }
    def 'shared issuer token endpoint is routing metadata and remains strictly validated'() {
        given:
        def env=environment()
        expect:
        env.AUTH_MIGRATION_LEGACY_TOKEN_ENDPOINT==env.AUTH_MIGRATION_MANAGED_TOKEN_ENDPOINT
        LegacyMigrationConfiguration.from(env,[:]).enabled
        when:
        env[setting]='https://provider.invalid/sso/realms/armoney/protocol/openid-connect/other'
        LegacyMigrationConfiguration.from(env,[:])
        then:
        thrown(IllegalArgumentException)
        where:
        setting << ['AUTH_MIGRATION_LEGACY_TOKEN_ENDPOINT','AUTH_MIGRATION_MANAGED_TOKEN_ENDPOINT']
    }
    def 'actual token credential roles still cannot alias even under endpoint-like names'() {
        given:
        def env=environment()
        env[setting]=env.VERIFICATION_SMS_SEND_TOKEN
        when:
        LegacyMigrationConfiguration.from(env,[:])
        then:
        thrown(IllegalArgumentException)
        where:
        setting << ['EXTRA_TOKEN','EXTRA_TOKEN_ENDPOINT','AUTH_MIGRATION_EXTRA_TOKEN_ENDPOINT','INTERNAL_AUTH_KEY']
    }
    def 'injected raw and canonical encoded aliases cannot reuse ring material'() {
        given:
        def env=environment()
        byte[] material=new byte[32];Arrays.fill(material,(byte)1)
        def supplied=encoded ? Base64.encoder.encode(material) : material
        when:
        LegacyMigrationConfiguration.from(env,[extra:supplied])
        then:
        thrown(IllegalArgumentException)
        where:
        encoded << [false,true]
    }
    def 'constructor intersection and fixed endpoints reject incompatible selected settings'() {
        given:
        def env=environment();env[name]=value
        when:
        LegacyMigrationConfiguration.from(env,[:])
        then:
        thrown(IllegalArgumentException)
        where:
        name | value
        'AUTH_MIGRATION_ACTOR_ID' | ('a'*65)
        'AUTH_MIGRATION_LEGACY_CLIENT_ID' | 'legacy.dot'
        'AUTH_MIGRATION_MANAGED_TOKEN_ENDPOINT' | 'https://provider.invalid/other/token'
        'USER_BASE_URL' | 'https://external.invalid'
        'AUTH_MIGRATION_PROOF_KEYS' | '{"k1":"invalid"}'
        'AUTH_MIGRATION_PROOF_CURRENT_KEY_ID' | 'missing'
    }
    def 'existing retained ring entry cannot alias migration material'() {
        given:
        def env=environment()
        byte[] material=new byte[32];Arrays.fill(material,(byte)1)
        env[name]='old:'+Base64.encoder.encodeToString(material)
        when:
        LegacyMigrationConfiguration.from(env,[:])
        then:
        thrown(IllegalArgumentException)
        where:
        name << ['SSO_LOGIN_PROOF_RETENTION_HMAC_PREVIOUS_KEYS','SSO_CODE_INTENT_DETAIL_ENCRYPTION_PREVIOUS_KEYS','AUTH_MANAGED_BANKING_PREVIOUS_KEYS']
    }
    def 'independent retained material remains valid and malformed ring cannot hide material'() {
        given:
        def env=environment()
        byte[] material=new byte[32];Arrays.fill(material,(byte)99)
        env.AUTH_MANAGED_BANKING_PREVIOUS_KEYS='old:'+Base64.encoder.encodeToString(material)
        expect:
        LegacyMigrationConfiguration.from(env,[:]).enabled
        when:
        env.AUTH_MANAGED_BANKING_PREVIOUS_KEYS='old:invalid'
        LegacyMigrationConfiguration.from(env,[:])
        then:
        thrown(IllegalArgumentException)
    }
    def 'selected constructor diagnostic exposes only safe failure frames'() {
        given:
        def constructor=LegacyMigrationConfiguration.declaredConstructors.find { it.parameterCount==2 }
        constructor.accessible=true
        when:
        try {
            constructor.newInstance(environment(),new LinkedHashMap<String,byte[]>())
        } catch(java.lang.reflect.InvocationTargetException wrapped) {
            def original=wrapped.targetException
            def frames=original.stackTrace.findAll { it.className.startsWith('com.arman.bank.authservice.') }.take(6).collect { it.className+'.'+it.methodName+':'+it.lineNumber }.join(';')
            throw new AssertionError('Selected configuration failure class='+original.class.name+' frames='+frames)
        }
        then:
        noExceptionThrown()
    }
    def 'publication and PRIMARY decision credentials are independent strict directional roles'(){
        given:def env=environment();env[role]=value
        when:LegacyMigrationConfiguration.from(env,[:])
        then:
        def rejected=thrown(IllegalArgumentException)
        rejected.message=='Invalid legacy migration configuration'
        rejected.cause==null
        !rejected.toString().contains(value)
        where:
        role << ['AUTH_TO_USER_MIGRATION_PUBLICATION_KEY','USER_TO_AUTH_MIGRATION_DECISION_KEY','AUTH_TO_USER_MIGRATION_PUBLICATION_KEY','USER_TO_AUTH_MIGRATION_DECISION_KEY']
        value << [('G'*43),('G'*43),(' '*32),('x'*257)]
    }
    def 'directional publication settings cannot silently populate the disabled graph'(){
        when:LegacyMigrationConfiguration.from([(role):value],[:])
        then:thrown(IllegalArgumentException)
        where:
        role << ['AUTH_TO_USER_MIGRATION_PUBLICATION_KEY','USER_TO_AUTH_MIGRATION_DECISION_KEY']
        value << ['',('x'*32)]
    }
    def 'known live selection false coexists with default browser OFF without ignoring arbitrary names'(){
        expect:!LegacyMigrationConfiguration.from([AUTH_MIGRATION_USER_REVISION_ENABLED:'false'],[:]).enabled
        when:LegacyMigrationConfiguration.from([AUTH_MIGRATION_USER_REVISION_ENABLED:'false',AUTH_MIGRATION_UNKNOWN:'unused'],[:])
        then:thrown(IllegalArgumentException)
        when:LegacyMigrationConfiguration.from([AUTH_MIGRATION_USER_REVISION_ENABLED:'false',AUTH_MIGRATION_USER_REVISION_WRITER:'writer'],[:])
        then:thrown(IllegalArgumentException)
    }
    def 'live User selection requires genuine selected migration and preserves independent roles'(){
        given:
        def env=environment()+[AUTH_MIGRATION_USER_REVISION_ENABLED:'true',AUTH_MIGRATION_USER_REVISION_WRITER:'auth-current',AUTH_TO_USER_MIGRATION_USER_CURRENT_KEY:('w'*43),AUTH_MIGRATION_USER_MUTATION_WRITER_ID:'user-mutation',USER_TO_AUTH_MIGRATION_USER_MUTATION_KEY:('z'*43)]
        expect:LegacyMigrationConfiguration.from(env,[:]).enabled
        when:env.AUTH_TO_USER_MIGRATION_USER_CURRENT_KEY=env.AUTH_TO_USER_MIGRATION_PUBLICATION_KEY;LegacyMigrationConfiguration.from(env,[:])
        then:thrown(IllegalArgumentException)
        when:env.AUTH_TO_USER_MIGRATION_USER_CURRENT_KEY='w'*43;env.AUTH_MIGRATION_BROWSER_ENABLED='false';LegacyMigrationConfiguration.from(env,[:])
        then:thrown(IllegalArgumentException)
    }
    static Map<String,String> environment() {
        def e=[AUTH_MIGRATION_BROWSER_ENABLED:'true',AUTH_MIGRATION_ACTOR_ID:'migration',SSO_ISSUER:'https://provider.invalid/sso/realms/armoney',AUTH_MIGRATION_LEGACY_CLIENT_ID:'legacy',AUTH_MIGRATION_MANAGED_CLIENT_ID:'managed',MIGRATION_PUBLIC_ORIGIN:'https://app.invalid',USER_BASE_URL:'http://user-service:8080',SSO_ENROLLMENT_STATUS_ORIGIN:'http://sso-service:8080',VERIFICATION_SMS_BASE_URL:'http://sms-sandbox:8080',MAILTRAP_SMTP_USERNAME:'sandbox-user',MAILTRAP_FROM_EMAIL:'sender@example.test']
        ['CUSTODY','DIGEST','PROOF','OTP'].each { role -> e['AUTH_MIGRATION_'+role+'_CURRENT_KEY_ID']='k1' }
        ['CUSTODY_ENCRYPTION','DIGEST','PROOF','OTP_HMAC','OTP_ENCRYPTION'].eachWithIndex { role,index -> byte[] key=new byte[32];Arrays.fill(key,(byte)(index+1));e['AUTH_MIGRATION_'+role+'_KEYS']='{"k1":"'+Base64.encoder.encodeToString(key)+'"}' }
        ['MIGRATION_GATEWAY_TO_AUTH_KEY','AUTH_TO_SSO_STATUS_KEY','INTERNAL_AUTH_KEY','AUTH_TO_USER_MIGRATION_STAGE_KEY','AUTH_TO_USER_MIGRATION_PUBLICATION_KEY','USER_TO_AUTH_MIGRATION_DECISION_KEY','VERIFICATION_SMS_SEND_TOKEN','MAILTRAP_SMTP_PASSWORD'].eachWithIndex { name,index -> e[name]=((char)(71+index)).toString()*43 }
        ['LEGACY','MANAGED'].each { mode -> ['AUTHORIZATION_ENDPOINT':'auth','TOKEN_ENDPOINT':'token','JWKS_URI':'certs'].each { suffix,path -> e['AUTH_MIGRATION_'+mode+'_'+suffix]=e.SSO_ISSUER+'/protocol/openid-connect/'+path } }
        e.ARMONEY_SSO_MANAGED_PAIRS='[{"issuer":"'+e.SSO_ISSUER+'","client_id":"managed"}]'
        e.ARMONEY_SSO_LEGACY_PAIRS='[{"issuer":"'+e.SSO_ISSUER+'","client_id":"legacy"}]'
        e
    }
}
