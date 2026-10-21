package com.arman.bank.authservice

import com.arman.bank.authservice.infrastructure.CodeConfirmationConfiguration
import com.arman.bank.authservice.infrastructure.CodeIntentConfiguration
import groovy.json.JsonOutput
import spock.lang.Specification

/** Pure configuration only. No Database/HTTP/scheduler fixture or remote-health assertion. */
class CodeConfirmationConfigurationSpec extends Specification {
    static Map environment(){
        def env=CodeIntentConfigurationSpec.environment()+[
            AUTH_SSO_CODE_INTENT_CONFIRMATION_ENABLED:'true',
            SSO_LOGIN_PROOF_RETENTION_KEY:CodeIntentConfigurationSpec.encoded(17),
            SSO_LOGIN_PROOF_CONFIRM_KEY:'synthetic-distinct-confirmation-key-material',
            SSO_CODE_INTENT_LOOKUP_KEY:'synthetic-distinct-lookup-key-material',
            SSO_VERIFICATION_CLIENT_IDS:'armoney-web,armoney-ios',
            ARMONEY_SSO_MANAGED_PAIRS:JsonOutput.toJson([[issuer:CodeIntentConfigurationSpec.environment().SSO_ISSUER,client_id:'armoney-web'],
                [issuer:CodeIntentConfigurationSpec.environment().SSO_ISSUER,client_id:'armoney-ios']]),ARMONEY_SSO_LEGACY_PAIRS:'[]']
        env.remove('SSO_CODE_INTENT_ISSUERS');env.remove('SSO_CODE_INTENT_CLIENTS');env
    }
    static Map coenabledEnvironment(){
        def env=com.arman.bank.authservice.infrastructure.LegacyMigrationConfigurationSpec.environment()+environment()
        env.AUTH_MANAGED_BANKING_ENABLED='true';env.AUTH_MANAGED_BANKING_KEY_ID='bank-v1'
        env.AUTH_MANAGED_BANKING_KEY=CodeIntentConfigurationSpec.encoded(18)
        env.AUTH_MIGRATION_LEGACY_CLIENT_ID='armoney-migration-account';env.AUTH_MIGRATION_MANAGED_CLIENT_ID='armoney-android'
        env.SSO_VERIFICATION_CLIENT_IDS='armoney-web,armoney-ios,armoney-android'
        env.ARMONEY_SSO_MANAGED_PAIRS=JsonOutput.toJson(['armoney-web','armoney-ios','armoney-android'].collect{[issuer:env.SSO_ISSUER,client_id:it]})
        env.ARMONEY_SSO_LEGACY_PAIRS=JsonOutput.toJson([[issuer:env.SSO_ISSUER,client_id:'armoney-migration-account']])
        ['LEGACY','MANAGED'].each{mode->['AUTHORIZATION_ENDPOINT':'auth','TOKEN_ENDPOINT':'token','JWKS_URI':'certs'].each{suffix,path->
            env['AUTH_MIGRATION_'+mode+'_'+suffix]=env.SSO_ISSUER+'/protocol/openid-connect/'+path
        }}
        env
    }
    def 'coenabled Main configuration graph keeps migration account global LEGACY and excludes it from C2 banking'(){
        given:def env=coenabledEnvironment();def original=new HashMap(env)
        when:
        def c2=new CodeConfirmationConfiguration(env)
        def banking=new com.arman.bank.authservice.infrastructure.ManagedBankingConfiguration(env)
        def migration=com.arman.bank.authservice.infrastructure.LegacyMigrationConfiguration.from(env,[:])
        then:
        c2.enabled && banking.enabled && migration.enabled
        env==original
        c2.scopes().managedClients()==['armoney-web','armoney-ios','armoney-android'] as Set
        c2.scopes().classify(env.SSO_ISSUER,'armoney-migration-account')==com.arman.bank.authservice.infrastructure.CodeIntentPairScopes.Classification.UNKNOWN
        banking.scopes().classify(env.SSO_ISSUER,'armoney-migration-account')==com.arman.bank.authservice.infrastructure.CodeIntentPairScopes.Classification.LEGACY
        migration.observation.allowedPairs().size()==2
    }
    def 'dedicated account exception requires exact migration settings and never adds verification or C2 scope #change'(){
        given:def env=coenabledEnvironment();env.putAll(change)
        when:new CodeConfirmationConfiguration(env)
        then:thrown(IllegalArgumentException)
        where:change << [
            [AUTH_MIGRATION_BROWSER_ENABLED:'false'],[AUTH_MIGRATION_BROWSER_ENABLED:'TRUE'],
            [AUTH_MIGRATION_LEGACY_CLIENT_ID:'other-account'],[AUTH_MIGRATION_MANAGED_CLIENT_ID:'armoney-migration-account'],
            [AUTH_MIGRATION_MANAGED_CLIENT_ID:null],[AUTH_MIGRATION_MANAGED_CLIENT_ID:'unknown'],
            [SSO_VERIFICATION_CLIENT_IDS:'armoney-web,armoney-ios,armoney-android,armoney-migration-account'],
            [SSO_CODE_INTENT_CLIENTS:'armoney-web,armoney-ios,armoney-android,armoney-migration-account'],
            [ARMONEY_SSO_LEGACY_PAIRS:JsonOutput.toJson([[issuer:environment().SSO_ISSUER,client_id:'arbitrary-fourth']])],
            [ARMONEY_SSO_LEGACY_PAIRS:JsonOutput.toJson([[issuer:'https://wrong.example/sso/realms/armoney',client_id:'armoney-migration-account']])],
            [ARMONEY_SSO_MANAGED_PAIRS:JsonOutput.toJson([[issuer:environment().SSO_ISSUER,client_id:'armoney-migration-account']])]
        ]
    }
    def 'dormant C2 preserves complete validated global classification without selecting account banking'(){
        given:def env=coenabledEnvironment();env.AUTH_SSO_CODE_INTENT_CONFIRMATION_ENABLED='false'
        expect:
        !new CodeConfirmationConfiguration(env).enabled
        new CodeConfirmationConfiguration(env).scopes().classify(env.SSO_ISSUER,'armoney-migration-account')==com.arman.bank.authservice.infrastructure.CodeIntentPairScopes.Classification.LEGACY
    }

    def 'default false allocates no resources and needs no dormant secrets or dependency configuration'(){
        given:def config=new CodeConfirmationConfiguration([:])
        expect:!config.enabled;config.scopes().managed().isEmpty();config.toString()=='CodeConfirmationConfiguration[redacted]'
        when:config.observerEndpoint()
        then:thrown(IllegalArgumentException)
    }
    def 'flag literal is strict #value'(){
        when:new CodeConfirmationConfiguration([AUTH_SSO_CODE_INTENT_CONFIRMATION_ENABLED:value])
        then:thrown(IllegalArgumentException)
        where:value << ['TRUE','1',' false','',null,'False']
    }
    def 'supplied dormant arrays validate syntax bounds duplicates overlap but not enabled dependencies'(){
        expect:!new CodeConfirmationConfiguration([ARMONEY_SSO_MANAGED_PAIRS:JsonOutput.toJson([[issuer:'https://other.example/realm',client_id:'future-client']])]).enabled
        when:new CodeConfirmationConfiguration([ARMONEY_SSO_MANAGED_PAIRS:'[null]'])
        then:thrown(IllegalArgumentException)
        when:new CodeConfirmationConfiguration([ARMONEY_SSO_MANAGED_PAIRS:' '*16385+'[]'])
        then:thrown(IllegalArgumentException)
        when:new CodeConfirmationConfiguration([ARMONEY_SSO_MANAGED_PAIRS:JsonOutput.toJson([CodeIntentPairScopesSpec.pair()]),ARMONEY_SSO_LEGACY_PAIRS:JsonOutput.toJson([CodeIntentPairScopesSpec.pair()])])
        then:thrown(IllegalArgumentException)
    }
    def 'enabled supports one issuer and multiple existing clients deriving only private C1 projections'(){
        given:def env=environment();def before=new LinkedHashMap(env)
        when:def config=new CodeConfirmationConfiguration(env)
        then:
        config.enabled;env==before
        config.scopes().managedClients()==['armoney-web','armoney-ios'] as Set
        config.observerEndpoint().toString()=='http://sso-service:8080/sso/realms/armoney/armoney-status/code-session'
        !env.containsKey('SSO_CODE_INTENT_CLIENTS');!env.containsKey('SSO_CODE_INTENT_ISSUERS')
        !config.toString().contains(env.SSO_LOGIN_PROOF_CONFIRM_KEY)
    }
    def 'every enabled dependency must be explicitly true #name'(){
        given:def env=environment();env[name]='false'
        when:new CodeConfirmationConfiguration(env)
        then:thrown(IllegalArgumentException)
        where:name << ['AUTH_SSO_CODE_INTENT_STAGE_ENABLED','AUTH_VERIFICATION_AUTHORITY_BRIDGE_ENABLED','AUTH_REGISTRATION_AUTHORITY_COMPLETION_ENABLED',
            'AUTH_REGISTRATION_ELIGIBILITY_GRANTS_ENABLED','AUTH_VERIFICATION_PROJECTION_ENABLED']
    }
    def 'missing enabled configuration never allocates partial protocol #name'(){
        given:def env=environment();env.remove(name)
        when:new CodeConfirmationConfiguration(env)
        then:thrown(IllegalArgumentException)
        where:name << ['SSO_LOGIN_PROOF_WRITER_ID','SSO_LOGIN_PROOF_WRITE_KEY','SSO_LOGIN_PROOF_CONFIRM_KEY','SSO_CODE_INTENT_LOOKUP_KEY',
            'AUTH_TO_SSO_STATUS_KEY','SSO_ENROLLMENT_STATUS_ORIGIN','SSO_CODE_INTENT_CODE_KEY_IDS','SSO_CODE_INTENT_ACTORS',
            'SSO_LOGIN_PROOF_RETENTION_HMAC_KEY','SSO_CODE_INTENT_DETAIL_ENCRYPTION_KEY']
    }
    def 'old C1 projection disagreement or positive enforcement is rejected'(){
        when:new CodeConfirmationConfiguration(environment()+change)
        then:thrown(IllegalArgumentException)
        where:change << [[SSO_CODE_INTENT_CLIENTS:'armoney-web'],[SSO_CODE_INTENT_ISSUERS:'https://foreign.example/realm'],
            [AUTH_SSO_LOGIN_PROOF_ENABLED:'true'],[SSO_CODE_INTENT_CODE_KEY_IDS:'one,two,three'],[SSO_LOGIN_PROOF_WRITER_ID:'foreign']]
    }
    def 'matching old projections remain valid and independently selected incomplete bridge rejects'(){
        given:def env=environment()+[SSO_CODE_INTENT_ISSUERS:environment().SSO_ISSUER,SSO_CODE_INTENT_CLIENTS:'armoney-ios,armoney-web']
        expect:new CodeConfirmationConfiguration(env).enabled
        when:new CodeConfirmationConfiguration(env+[AUTH_LOGIN_PROOF_BRIDGE_ENABLED:'true'])
        then:thrown(IllegalArgumentException)
    }
    def 'malformed enabled dependency literals never coerce truthy values'(){
        when:new CodeConfirmationConfiguration(environment()+[AUTH_SSO_CODE_INTENT_STAGE_ENABLED:value])
        then:thrown(IllegalArgumentException)
        where:value << ['TRUE','1',' true','',null]
    }
    def 'new directional credentials enforce exact printable ASCII bounds #name #size'(){
        when:new CodeConfirmationConfiguration(environment()+[(name):value])
        then:thrown(IllegalArgumentException)
        where:
        name | value | size
        'SSO_LOGIN_PROOF_CONFIRM_KEY' | 'x'*31 | 31
        'SSO_LOGIN_PROOF_CONFIRM_KEY' | 'x'*257 | 257
        'SSO_LOGIN_PROOF_CONFIRM_KEY' | 'x'*31+' ' | 32
        'SSO_CODE_INTENT_LOOKUP_KEY' | 'x'*31+'\n' | 32
        'SSO_CODE_INTENT_LOOKUP_KEY' | 'x'*31+'\u00e9' | 32
        'SSO_CODE_INTENT_LOOKUP_KEY' | null | 0
    }
    def 'standalone C1 requirements remain unchanged when C2 is off'(){
        given:def env=CodeIntentConfigurationSpec.environment();env.remove('SSO_CODE_INTENT_CLIENTS')
        expect:!new CodeConfirmationConfiguration(env).enabled
        when:new CodeIntentConfiguration(env)
        then:thrown(IllegalArgumentException)
    }
    def 'unknown unsupported foreign or empty enabled managed scope fails locally'(){
        when:new CodeConfirmationConfiguration(environment()+[ARMONEY_SSO_MANAGED_PAIRS:pairs])
        then:thrown(IllegalArgumentException)
        where:pairs << ['[]',JsonOutput.toJson([[issuer:'https://foreign.example/sso/realms/armoney',client_id:'armoney-web']]),
            JsonOutput.toJson([[issuer:CodeIntentConfigurationSpec.environment().SSO_ISSUER,client_id:'not-a-public-client']])]
    }
    def 'only locally detectable path or origin disagreement rejects without remote probes'(){
        when:new CodeConfirmationConfiguration(environment()+change)
        then:thrown(IllegalArgumentException)
        where:change << [[SSO_ENROLLMENT_STATUS_ORIGIN:'http://127.0.0.1:1'],[SSO_ENROLLMENT_STATUS_ORIGIN:'https://provider.example/not-origin'],
            [SSO_ENROLLMENT_STATUS_ORIGIN:'https://user@provider.example'],[SSO_ENROLLMENT_STATUS_ORIGIN:'https://provider.example/?q=1']]
    }
    def 'syntactically valid unreachable fixed private origin needs no startup HTTP or remote endpoint proof'(){
        given:def config=new CodeConfirmationConfiguration(environment()+[SSO_ENROLLMENT_STATUS_ORIGIN:'https://unavailable.invalid'])
        expect:config.enabled;config.observerEndpoint().path=='/sso/realms/armoney/armoney-status/code-session'
    }
    def 'same locally configured issuer and pair still reject incompatible fixed observer realm path'(){
        given:
        String issuer='https://issuer.example/sso/realms/other'
        def env=environment()+[SSO_ISSUER:issuer,ARMONEY_SSO_MANAGED_PAIRS:JsonOutput.toJson([[issuer:issuer,client_id:'armoney-web']])]
        when:new CodeConfirmationConfiguration(env)
        then:thrown(IllegalArgumentException)
    }
    def 'retained ring members and constructor supplied duplicate bytes cannot escape local separation'(){
        given:def env=environment()+[SSO_LOGIN_PROOF_RETENTION_HMAC_PREVIOUS_KEYS:'old:'+environment().SSO_CODE_INTENT_DETAIL_ENCRYPTION_KEY]
        when:new CodeConfirmationConfiguration(env)
        then:thrown(IllegalArgumentException)
        when:new CodeConfirmationConfiguration(environment(),['one':([78]*32) as byte[],'two':([78]*32) as byte[]])
        then:thrown(IllegalArgumentException)
    }
    def 'locally available material equality is rejected across roles and retained rings'(){
        given:def env=environment();env[name]=env.SSO_LOGIN_PROOF_CONFIRM_KEY
        when:new CodeConfirmationConfiguration(env)
        then:thrown(IllegalArgumentException)
        where:name << ['SSO_CODE_INTENT_LOOKUP_KEY','SSO_LOGIN_PROOF_WRITE_KEY','SSO_LOGIN_PROOF_RESOLVE_KEY','AUTH_TO_SSO_STATUS_KEY',
            'SSO_CLIENT_SECRET','INTERNAL_AUTH_KEY','INTERNAL_METRICS_KEY','SSO_MIGRATION_PROOF_WRITE_KEY','MAILTRAP_SMTP_PASSWORD']
    }
    def 'raw versus decoded material equality and explicit constructor supplied keys cannot bypass separation'(){
        given:def env=environment();def raw='q'*32;env.SSO_LOGIN_PROOF_CONFIRM_KEY=raw;env.SSO_CODE_INTENT_LOOKUP_KEY=Base64.encoder.encodeToString(raw.bytes)
        when:new CodeConfirmationConfiguration(env)
        then:thrown(IllegalArgumentException)
        when:new CodeConfirmationConfiguration(environment(),['local-component-key':environment().SSO_LOGIN_PROOF_CONFIRM_KEY.bytes])
        then:thrown(IllegalArgumentException)
        when:new CodeConfirmationConfiguration(environment(),['local-original-key':Base64.decoder.decode(environment().SSO_LOGIN_PROOF_RETENTION_HMAC_KEY)])
        then:thrown(IllegalArgumentException)
    }
    def 'independent injected local material is copied without changing caller arrays and grants no remote completeness claim'(){
        given:byte[] material=([77]*32) as byte[];byte[] original=material.clone()
        when:def config=new CodeConfirmationConfiguration(environment(),['local-component-key':material])
        then:config.enabled;Arrays.equals(material,original)
    }
    def 'injected raw and encoded bytes compare crosswise on BOTH sides without modifying callers'(){
        given:
        byte[] raw=('q'*32).bytes
        byte[] encoded=Base64.encoder.encode(raw)
        byte[] frozenRaw=raw.clone();byte[] frozenEncoded=encoded.clone()
        when:new CodeConfirmationConfiguration(environment(),['raw':raw,'encoded':encoded])
        then:thrown(IllegalArgumentException)
        Arrays.equals(raw,frozenRaw);Arrays.equals(encoded,frozenEncoded)
        when:new CodeConfirmationConfiguration(environment()+[SSO_LOGIN_PROOF_CONFIRM_KEY:new String(raw,java.nio.charset.StandardCharsets.US_ASCII)],['encoded':encoded])
        then:thrown(IllegalArgumentException)
        Arrays.equals(raw,frozenRaw);Arrays.equals(encoded,frozenEncoded)
        when:new CodeConfirmationConfiguration(environment(),['encoded':encoded,'raw':raw])
        then:thrown(IllegalArgumentException)
    }
    def 'independent opaque nontext bytes and independent encoded material remain accepted defensively'(){
        given:
        byte[] opaque=([-1,-2,0,1]*8) as byte[]
        byte[] independent=Base64.encoder.encode(([99]*32) as byte[])
        byte[] first=opaque.clone();byte[] second=independent.clone()
        when:def config=new CodeConfirmationConfiguration(environment(),['opaque':opaque,'independent':independent])
        then:config.enabled;Arrays.equals(opaque,first);Arrays.equals(independent,second)
    }
    def 'malformed observer credential URI is redacted without a retained cause or input'(){
        given:String secret='synthetic-observer-private-password'
        when:new CodeConfirmationConfiguration(environment()+[SSO_ENROLLMENT_STATUS_ORIGIN:'https://user:'+secret+'@[broken'])
        then:
        def rejected=thrown(IllegalArgumentException)
        rejected.message=='Invalid code confirmation configuration';rejected.cause==null
        def trace=new StringWriter();rejected.printStackTrace(new PrintWriter(trace))
        !trace.toString().contains(secret);!trace.toString().contains('https://user:')
    }
    static byte[] proofRetention(CodeConfirmationConfiguration configuration){
        def getter=CodeConfirmationConfiguration.getDeclaredMethod('proofRetentionKey');getter.accessible=true
        (byte[])getter.invoke(configuration)
    }
    def 'enabled C2 requires independent original V7 recipient key even when historical bridge is off'(){
        given:def env=environment();env.remove('SSO_LOGIN_PROOF_RETENTION_KEY')
        when:new CodeConfirmationConfiguration(env)
        then:def rejected=thrown(IllegalArgumentException);rejected.message=='Invalid code confirmation configuration';rejected.cause==null
        when:def selected=new CodeConfirmationConfiguration(environment())
        then:selected.enabled;Arrays.equals(proofRetention(selected),([17]*32) as byte[])
    }
    def 'proof recipient key rejects malformed noncanonical and wrong decoded lengths without leaking input'(){
        when:new CodeConfirmationConfiguration(environment()+[SSO_LOGIN_PROOF_RETENTION_KEY:value])
        then:def rejected=thrown(IllegalArgumentException);rejected.message=='Invalid code confirmation configuration';rejected.cause==null
        where:value << [null,'','secret-marker-not-base64',Base64.encoder.encodeToString(new byte[31]),Base64.encoder.encodeToString(new byte[33]),
            Base64.encoder.withoutPadding().encodeToString(new byte[32]),('A'*42)+'B=',CodeIntentConfigurationSpec.encoded(17)+'\n']
    }
    def 'proof recipient key must differ from every local secret role #name including retained aliases'(){
        given:
        def env=environment();String key=env.SSO_LOGIN_PROOF_RETENTION_KEY
        env[name]=name.endsWith('_PREVIOUS_KEYS')?'retained:'+key:key
        when:new CodeConfirmationConfiguration(env)
        then:def rejected=thrown(IllegalArgumentException);rejected.message=='Invalid code confirmation configuration';rejected.cause==null
        where:name << ['SSO_LOGIN_PROOF_CONFIRM_KEY','SSO_CODE_INTENT_LOOKUP_KEY','SSO_LOGIN_PROOF_WRITE_KEY','SSO_LOGIN_PROOF_RESOLVE_KEY',
            'AUTH_TO_SSO_STATUS_KEY','SSO_VERIFICATION_BRIDGE_KEY','SSO_CLIENT_SECRET','INTERNAL_AUTH_KEY','INTERNAL_METRICS_KEY',
            'ARMONEY_PROVIDER_BINDING_KEY','SSO_LOGIN_CODE_HMAC_KEY','SSO_MIGRATION_PROOF_WRITE_KEY','MAILTRAP_SMTP_PASSWORD',
            'AUTH_VERIFICATION_AUTHORITY_RETENTION_KEY','VERIFICATION_OTP_HMAC_KEY','VERIFICATION_DELIVERY_ENCRYPTION_KEY',
            'AUTH_TO_USER_VERIFICATION_PROJECTION_KEY','AUTH_VERIFICATION_PROJECTION_RETENTION_KEY',
            'SSO_LOGIN_PROOF_RETENTION_HMAC_KEY','SSO_CODE_INTENT_DETAIL_ENCRYPTION_KEY',
            'SSO_LOGIN_PROOF_RETENTION_HMAC_PREVIOUS_KEYS','SSO_CODE_INTENT_DETAIL_ENCRYPTION_PREVIOUS_KEYS']
    }
    def 'proof recipient key compares raw and encoded injected aliases without modifying caller material'(){
        given:
        byte[] material=encoded?Base64.encoder.encode(('q'*32).bytes):('q'*32).bytes
        byte[] frozen=material.clone()
        def env=environment()+[SSO_LOGIN_PROOF_RETENTION_KEY:Base64.encoder.encodeToString(('q'*32).bytes)]
        when:new CodeConfirmationConfiguration(env,['local-owner-material':material])
        then:def rejected=thrown(IllegalArgumentException);rejected.message=='Invalid code confirmation configuration';rejected.cause==null
        Arrays.equals(material,frozen)
        where:encoded << [false,true]
    }
    def 'proof recipient key also rejects raw alias in an independent configured secret role'(){
        given:def env=environment()+[SSO_LOGIN_PROOF_RETENTION_KEY:Base64.encoder.encodeToString(('q'*32).bytes),SSO_CLIENT_SECRET:'q'*32]
        when:new CodeConfirmationConfiguration(env)
        then:def rejected=thrown(IllegalArgumentException);rejected.message=='Invalid code confirmation configuration';rejected.cause==null
    }
    def 'proof recipient getter returns original parsed bytes through fresh independent clones'(){
        given:def config=new CodeConfirmationConfiguration(environment());byte[] first=proofRetention(config);byte[] second=proofRetention(config)
        expect:!first.is(second);Arrays.equals(first,second)
        when:first[0]=0;second[1]=0
        then:Arrays.equals(proofRetention(config),([17]*32) as byte[]);config.toString()=='CodeConfirmationConfiguration[redacted]'
    }
    def 'unselected C2 with legacy off needs no recipient key and allocates no observer'(){
        given:def config=new CodeConfirmationConfiguration([AUTH_SSO_CODE_INTENT_CONFIRMATION_ENABLED:'false',SSO_LOGIN_PROOF_RETENTION_KEY:'dormant-unselected-value'])
        expect:!config.enabled;com.arman.bank.authservice.infrastructure.HttpCodeSessionObservations.open(config).isEmpty()
    }

}
