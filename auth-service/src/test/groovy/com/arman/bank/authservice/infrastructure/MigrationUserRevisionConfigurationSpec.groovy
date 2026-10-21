package com.arman.bank.authservice.infrastructure

import spock.lang.Specification

class MigrationUserRevisionConfigurationSpec extends Specification {
    static Map environment(){[
        AUTH_MIGRATION_USER_REVISION_ENABLED:'true',USER_BASE_URL:'http://user-service:8080',
        AUTH_MIGRATION_USER_REVISION_WRITER:'auth-current',AUTH_MIGRATION_USER_MUTATION_WRITER_ID:'user-mutation',
        AUTH_TO_USER_MIGRATION_USER_CURRENT_KEY:'c'*32,USER_TO_AUTH_MIGRATION_USER_MUTATION_KEY:'m'*32]}
    def 'default OFF returns without consuming incomplete or malformed dependent settings'(){
        expect:
        !MigrationUserRevisionConfiguration.from([USER_BASE_URL:'not a URI']).enabled()
    }
    def 'selected configuration preserves canonical directional names and fixed User origin'(){
        when:
        def c=MigrationUserRevisionConfiguration.from(environment())
        then:
        c.enabled()
        c.currentWriter()=='auth-current'
        c.mutationWriter()=='user-mutation'
        c.userOrigin().toString()=='http://user-service:8080'
        !c.toString().contains('c'*32)
    }
    def 'selected malformed flags origins writers and credentials fail generically'(){
        given:
        def env=environment();env[field]=value
        when:
        MigrationUserRevisionConfiguration.from(env)
        then:
        def failure=thrown(IllegalArgumentException)
        failure.cause==null
        failure.message=='Migration User revision configuration rejected'
        where:
        field | value
        'AUTH_MIGRATION_USER_REVISION_ENABLED' | 'TRUE'
        'USER_BASE_URL' | 'http://other:8080'
        'USER_BASE_URL' | 'http://user:secret@user-service:8080'
        'AUTH_MIGRATION_USER_REVISION_WRITER' | 'a'*65
        'AUTH_MIGRATION_USER_MUTATION_WRITER_ID' | 'bad.writer'
        'AUTH_TO_USER_MIGRATION_USER_CURRENT_KEY' | 'short'
    }
    def 'current previous and mutation materials must be independent including retained JSON entries'(){
        given:
        def env=environment();env[field]=value
        when:
        MigrationUserRevisionConfiguration.from(env)
        then:
        thrown(IllegalArgumentException)
        where:
        field | value
        'AUTH_TO_USER_MIGRATION_USER_CURRENT_KEY_PREVIOUS' | 'c'*32
        'USER_TO_AUTH_MIGRATION_USER_MUTATION_KEY' | 'c'*32
        'OTHER_KEY' | ('c'*32).bytes.encodeBase64().toString()
        'OTHER_KEYS' | '{"old":"'+('c'*32).bytes.encodeBase64().toString()+'"}'
        'AUTH_MANAGED_BANKING_PREVIOUS_KEYS' | 'old:'+('c'*32).bytes.encodeBase64().toString()
        'SSO_LOGIN_PROOF_RETENTION_HMAC_PREVIOUS_KEYS' | 'old:'+('m'*32).bytes.encodeBase64().toString()
        'SSO_CODE_INTENT_DETAIL_ENCRYPTION_PREVIOUS_KEYS' | 'old:'+('c'*32).bytes.encodeBase64().toString()
    }
    def 'canonical URL unpadded directional material cannot alias standard retained or peer material'(){
        given:
        byte[] material=([-1]*32) as byte[]
        String url=Base64.urlEncoder.withoutPadding().encodeToString(material)
        String standard=Base64.encoder.encodeToString(material)
        def env=environment();env.AUTH_TO_USER_MIGRATION_USER_CURRENT_KEY=url
        env[field]=format=='json'?'{"old":"'+standard+'"}':format=='csv'?'old:'+standard:standard
        when:
        MigrationUserRevisionConfiguration.from(env)
        then:
        thrown(IllegalArgumentException)
        where:
        field | format
        'OTHER_KEYS' | 'json'
        'AUTH_MANAGED_BANKING_PREVIOUS_KEYS' | 'csv'
        'AUTH_TO_USER_MIGRATION_USER_CURRENT_KEY_PREVIOUS' | 'direct'
        'USER_TO_AUTH_MIGRATION_USER_MUTATION_KEY' | 'direct'
    }

}
