package com.arman.bank.appgateway

import spock.lang.Specification

class MigrationBrowserConfigurationSpec extends Specification {
    def 'redirect policy binds mode endpoint callback and exact decoded query fields'() {
        given:
        def config = MigrationBrowserConfiguration.from([GATEWAY_MIGRATION_BROWSER_ENABLED:'true',
            MIGRATION_PUBLIC_ORIGIN:'https://bank.example', MIGRATION_EDGE_TO_GATEWAY_KEY:'e' * 43,
            MIGRATION_GATEWAY_TO_AUTH_KEY:'a' * 43, MIGRATION_LEGACY_AUTHORIZATION_ENDPOINT:'https://legacy.example/authorize',
            MIGRATION_LEGACY_CLIENT_ID:'legacy', MIGRATION_MANAGED_AUTHORIZATION_ENDPOINT:'https://managed.example/authorize',
            MIGRATION_MANAGED_CLIENT_ID:'managed'], URI.create('http://auth:8081'))
        def token = Base64.urlEncoder.withoutPadding().encodeToString(new byte[32])
        def location = 'https://legacy.example/authorize?client_id=legacy&redirect_uri=https%3A%2F%2Fbank.example%2Fmigration%2Flegacy%2Faccount-oidc%2Fcallback&response_type=code&scope=openid&state=' + token + '&nonce=' + token + '&code_challenge=' + token + '&code_challenge_method=S256&prompt=login&max_age=0'

        when:
        config.requireLocation(location, '/migration/legacy/account-oidc/start', 303)
        then:
        noExceptionThrown()

        when:
        config.requireLocation(location + '&%73tate=' + token, '/migration/legacy/account-oidc/start', 303)
        then:
        thrown(IllegalArgumentException)

        when:
        config.requireLocation(location, '/migration/managed/start', 303)
        then:
        thrown(IllegalArgumentException)
    }
    def 'default disabled settings allocate no transport and reject partial configuration'() {
        expect:
        !MigrationBrowserConfiguration.from([:], URI.create('http://auth:8081')).enabled()

        when:
        MigrationBrowserConfiguration.from([MIGRATION_PUBLIC_ORIGIN: 'https://bank.example'], URI.create('http://auth:8081'))
        then:
        thrown(IllegalArgumentException)
    }

    def 'enabled configuration requires distinct purpose credentials and canonical origin'() {
        given:
        def env = [GATEWAY_MIGRATION_BROWSER_ENABLED:'true', MIGRATION_PUBLIC_ORIGIN:'https://bank.example', MIGRATION_LEGACY_AUTHORIZATION_ENDPOINT:'https://legacy.example/authorize', MIGRATION_LEGACY_CLIENT_ID:'legacy', MIGRATION_MANAGED_AUTHORIZATION_ENDPOINT:'https://managed.example/authorize', MIGRATION_MANAGED_CLIENT_ID:'managed',
            MIGRATION_EDGE_TO_GATEWAY_KEY:'e' * 43, MIGRATION_GATEWAY_TO_AUTH_KEY:'a' * 43]
        expect:
        MigrationBrowserConfiguration.from(env, URI.create('http://auth:8081')).enabled()

        when:
        MigrationBrowserConfiguration.from(env + [MIGRATION_GATEWAY_TO_AUTH_KEY: 'e' * 43], URI.create('http://auth:8081'))
        then:
        thrown(IllegalArgumentException)

        when:
        MigrationBrowserConfiguration.from(env + [INTERNAL_AUTH_KEY:'a' * 43], URI.create('http://auth:8081'))
        then:
        thrown(IllegalArgumentException)

        when:
        MigrationBrowserConfiguration.from(env + [MIGRATION_PUBLIC_ORIGIN:'http://bank.example'], URI.create('http://auth:8081'))
        then:
        thrown(IllegalArgumentException)
    }
}
