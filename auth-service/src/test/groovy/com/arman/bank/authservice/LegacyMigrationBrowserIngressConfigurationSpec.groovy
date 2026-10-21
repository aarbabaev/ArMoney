package com.arman.bank.authservice.infrastructure

import jakarta.servlet.http.HttpServletRequest
import spock.lang.Specification

/** Private-hop boundary only; synthetic keys do not prove user/provider authority or edge TLS. */
class LegacyMigrationBrowserIngressConfigurationSpec extends Specification {
    static final String KEY = 'g' * 64
    static final String ORIGIN = 'https://auth.example.test'
    static Map settings() { [AUTH_MIGRATION_BROWSER_ENABLED:'true',MIGRATION_PUBLIC_ORIGIN:ORIGIN,MIGRATION_GATEWAY_TO_AUTH_KEY:KEY] }
    def request(Map additions = [:]) {
        def headers = ['Host':['auth-service:8080'],'X-ArMoney-Migration-Auth-Key':[KEY],
                       'X-ArMoney-Migration-Origin':[ORIGIN]] + additions
        Stub(HttpServletRequest) {
            getHeaderNames() >> { Collections.enumeration(headers.keySet()) }
            getHeaders(_ as String) >> { String name -> Collections.enumeration(headers[name] ?: []) }
            isSecure() >> false
            getScheme() >> 'http'
            getServerName() >> 'auth-service'
            getServerPort() >> 8080
        }
    }
    def 'disabled defaults retain no credential or origin'() {
        given: def config = LegacyMigrationBrowserIngressConfiguration.from([:])
        expect: !config.enabled() && config.publicOrigin() == null
        when: config.requireGateway(request())
        then: thrown(IllegalArgumentException)
    }
    def 'disabled partial settings malformed flags missing keys or noncanonical origins deny startup'() {
        when: LegacyMigrationBrowserIngressConfiguration.from(values)
        then: thrown(IllegalArgumentException)
        where:
        values << [[MIGRATION_PUBLIC_ORIGIN:ORIGIN], [MIGRATION_GATEWAY_TO_AUTH_KEY:KEY],
                   settings() + [AUTH_MIGRATION_BROWSER_ENABLED:'false'],
                   settings() + [AUTH_MIGRATION_BROWSER_ENABLED:'TRUE'],
                   settings() + [AUTH_MIGRATION_BROWSER_ENABLED:null],
                   settings() + [MIGRATION_GATEWAY_TO_AUTH_KEY:'short'],
                   settings() + [MIGRATION_PUBLIC_ORIGIN:'http://auth.example.test'],
                   settings() + [MIGRATION_PUBLIC_ORIGIN:ORIGIN + '/'],
                   settings() + [MIGRATION_PUBLIC_ORIGIN:ORIGIN + '?x=1'],
                   settings() + [MIGRATION_PUBLIC_ORIGIN:'https://user@auth.example.test']]
    }
    def 'raw standardBase64 and URLBase64 aliases across every other purpose deny startup'() {
        when: LegacyMigrationBrowserIngressConfiguration.from(settings() + [(name):value])
        then: thrown(IllegalArgumentException)
        where:
        name                              | value
        'MIGRATION_EDGE_TO_GATEWAY_KEY'    | KEY
        'INTERNAL_AUTH_KEY'                | KEY
        'AUTH_TO_USER_PROJECTION_KEY'      | Base64.encoder.encodeToString(KEY.bytes)
        'PROVIDER_SECRET'                 | Base64.urlEncoder.withoutPadding().encodeToString(KEY.bytes)
        'SMS_SANDBOX_WRITE_TOKEN'          | KEY
        'MAILTRAP_SMTP_PASSWORD'           | KEY
    }
    def 'startup alias comparison includes decoded dedicated key form and toString never reveals credentials'() {
        given:
        String raw = 'existing_other_purpose_secret_32_chars'
        String encoded = Base64.urlEncoder.withoutPadding().encodeToString(raw.bytes)
        when: LegacyMigrationBrowserIngressConfiguration.from(settings() + [MIGRATION_GATEWAY_TO_AUTH_KEY:encoded,OTHER_SECRET:raw])
        then: thrown(IllegalArgumentException)
        when: def config = LegacyMigrationBrowserIngressConfiguration.from(settings())
        then:
        config.enabled()
        !config.toString().contains(KEY)
        !config.toString().contains(ORIGIN)
    }
    def 'valid dedicated credential and fixed assertion admit private HTTP as channel only'() {
        when: LegacyMigrationBrowserIngressConfiguration.from(settings()).requireGateway(request())
        then: noExceptionThrown()
    }
    def 'missing wrong duplicate edge-only or wrong-origin headers deny private requests'() {
        when: LegacyMigrationBrowserIngressConfiguration.from(settings()).requireGateway(request(headers))
        then: thrown(IllegalArgumentException)
        where:
        headers << [['X-ArMoney-Migration-Auth-Key':[]],['X-ArMoney-Migration-Auth-Key':['w' * 64]],
                    ['X-ArMoney-Migration-Auth-Key':[KEY,KEY]],['X-ArMoney-Migration-Origin':[]],
                    ['X-ArMoney-Migration-Origin':[ORIGIN,ORIGIN]],['X-ArMoney-Migration-Origin':['https://evil.example.test']],
                    ['X-ArMoney-Migration-Auth-Key':[],'X-ArMoney-Migration-Edge-Key':[KEY]]]
    }
    def 'gateway-stripped forwarding identity and service assertions deny even alongside valid hop key'() {
        when: LegacyMigrationBrowserIngressConfiguration.from(settings()).requireGateway(request([(name):['forged']]))
        then: thrown(IllegalArgumentException)
        where:
        name << ['Forwarded','X-Forwarded-Proto','x-FoRwArDeD-Host','X-Identity-Id','X-Service-Key',
                 'X-ArMoney-Migration-Edge-Key','Authorization','X-User-Id','X-Authenticated-User',
                 'X-Identity-Email','x-IdEnTiTy-UnknownSuffix']
    }
    def 'valid-hop identity prefix denies actual handler before facade: #name'() {
        given:
        def service = Mock(com.arman.bank.authservice.application.LegacyMigrationBrowserService)
        def servlet = request([(name):['forged']])
        def seen = [status:null,body:null]
        def context
        context = Stub(io.javalin.http.Context) {
            req() >> servlet
            header(_ as String,_ as String) >> { context }
            status(_ as int) >> { int value -> seen.status = value; context }
            contentType(_ as String) >> { context }
            result(_ as String) >> { String value -> seen.body = value; context }
        }
        def config = LegacyMigrationBrowserSecuritySpec.configured()
        def routes = new LegacyMigrationBrowserRoutes(config,LegacyMigrationBrowserIngressConfiguration.from(settings()),service)
        when: routes.handle(context)
        then:
        0 * service._
        seen.status == 400
        !seen.body.contains('forged')
        where:
        name << ['X-Identity-Email','x-IdEnTiTy-UnknownSuffix']
    }
    def 'bad gateway credentials stop before reading any cookie or header inventory'() {
        given:
        def request = Mock(HttpServletRequest)
        when: LegacyMigrationBrowserIngressConfiguration.from(settings()).requireGateway(request)
        then:
        1 * request.getHeaders('X-ArMoney-Migration-Auth-Key') >> Collections.enumeration(['wrong'])
        1 * request.getHeaders('X-ArMoney-Migration-Origin') >> Collections.enumeration([ORIGIN])
        0 * request.getHeaderNames()
        0 * request.getHeaders('Cookie')
        thrown(IllegalArgumentException)
    }
}
