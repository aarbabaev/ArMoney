package com.arman.bank.authservice.infrastructure

import spock.lang.Specification
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import io.javalin.http.Context
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import com.arman.bank.authservice.application.LegacyMigrationBrowserService
import com.arman.bank.authservice.application.MigrationManagedProofPort

/** Pure boundary tests only: no creator, HTTP, provider, database or proof success. */
class LegacyMigrationBrowserSecuritySpec extends Specification {
    static final String TOKEN = 'A' * 43
    static final String COOKIE = 'B' * 42 + 'A'
    static final String INGRESS_KEY = 'g' * 64
    static final String ORIGIN = 'https://auth.example.test'
    static final Instant NOW = Instant.parse('2026-10-18T10:00:00Z')
    static final UUID COMMAND = UUID.fromString('11111111-1111-4111-8111-111111111111')
    static final String ENDPOINT = 'https://sso.example.test/sso/realms/armoney/protocol/openid-connect/auth'
    static LegacyMigrationBrowserConfiguration configured(String origin = ORIGIN) {
        def base = URI.create(origin)
        new LegacyMigrationBrowserConfiguration(true, base,
            base.resolve(LegacyMigrationBrowserConfiguration.LEGACY_CALLBACK), base.resolve(LegacyMigrationBrowserConfiguration.MANAGED_CALLBACK),
            URI.create(ENDPOINT), 'legacy-migration', URI.create(ENDPOINT), 'managed-migration')
    }
    static Map authorization() {
        [client_id:'managed-migration', redirect_uri:ORIGIN + LegacyMigrationBrowserConfiguration.MANAGED_CALLBACK,
         response_type:'code', scope:'openid', prompt:'login', max_age:'0', state:TOKEN, nonce:TOKEN, code_challenge:TOKEN, code_challenge_method:'S256']
    }
    static URI location(Map fields = authorization(), String endpoint = ENDPOINT) {
        URI.create(endpoint + '?' + fields.collect { key,value ->
            URLEncoder.encode(key.toString(), StandardCharsets.UTF_8) + '=' + URLEncoder.encode(value.toString(), StandardCharsets.UTF_8)
        }.join('&'))
    }
    def request(String path, String method = 'GET', Map supplied = [:], String query = null) {
        def headers = ['Host':['auth-service:8080'], 'X-ArMoney-Migration-Auth-Key':[INGRESS_KEY],
                       'X-ArMoney-Migration-Origin':[ORIGIN]] + supplied
        def seen = [headers:[:], cookies:[], status:null, body:null]
        def servlet = Stub(HttpServletRequest) {
            isSecure() >> false
            getScheme() >> 'http'
            getServerName() >> 'auth-service'
            getServerPort() >> 8080
            getMethod() >> method
            getRequestURI() >> path
            getQueryString() >> query
            getHeaderNames() >> { Collections.enumeration(headers.keySet()) }
            getHeaders(_ as String) >> { String name -> Collections.enumeration(headers[name] ?: []) }
        }
        def response = Stub(HttpServletResponse) {
            addHeader('Set-Cookie',_ as String) >> { String name,String value -> seen.cookies.add(value) }
        }
        def context
        context = Stub(Context) {
            req() >> servlet
            res() >> response
            header(_ as String,_ as String) >> { String name,String value -> seen.headers[name] = value; context }
            status(_ as int) >> { int value -> seen.status = value; context }
            contentType(_ as String) >> { String value -> seen.type = value; context }
            result(_ as String) >> { String value -> seen.body = value; context }
        }
        [context:context, seen:seen]
    }
    static LegacyMigrationBrowserIngressConfiguration ingress() {
        LegacyMigrationBrowserIngressConfiguration.from([AUTH_MIGRATION_BROWSER_ENABLED:'true',
            MIGRATION_PUBLIC_ORIGIN:ORIGIN,MIGRATION_GATEWAY_TO_AUTH_KEY:INGRESS_KEY])
    }
    def routes(LegacyMigrationBrowserService service, String body = '') {
        new LegacyMigrationBrowserRoutes(configured(), ingress(), service, Clock.fixed(NOW,ZoneOffset.UTC),
            { Context ignored -> body.getBytes(StandardCharsets.US_ASCII) } as LegacyMigrationBrowserRoutes.BodyReader)
    }
    def 'default disabled settings confer no request authority'() {
        given: def config = LegacyMigrationBrowserConfiguration.disabled()
        when: config.requireRequest('https','auth.example.test',443,'GET','/migration/legacy',null)
        then: thrown(IllegalArgumentException)
        !config.enabled()
    }
    def 'constructor rejects noncanonical or nonHTTPS origins: #origin'() {
        when: configured(origin)
        then: thrown(IllegalArgumentException)
        where:
        origin << ['http://auth.example.test','https://auth.example.test/','https://auth.example.test/base',
                   'https://user@auth.example.test','https://auth.example.test?x=1','https://auth.example.test#part',
                   'https://AUTH.example.test','https://auth.example.test:0443','https://auth..example.test']
    }
    def 'callbacks must match exact fixed namespace and purposes cannot use same pair'() {
        when:
        new LegacyMigrationBrowserConfiguration(true, URI.create(ORIGIN),
            URI.create(ORIGIN + '/wrong'), URI.create(ORIGIN + LegacyMigrationBrowserConfiguration.MANAGED_CALLBACK),
            URI.create(ENDPOINT), 'legacy', URI.create(ENDPOINT), 'managed')
        then: thrown(IllegalArgumentException)
        when:
        new LegacyMigrationBrowserConfiguration(true, URI.create(ORIGIN),
            URI.create(ORIGIN + LegacyMigrationBrowserConfiguration.LEGACY_CALLBACK), URI.create(ORIGIN + LegacyMigrationBrowserConfiguration.MANAGED_CALLBACK),
            URI.create(ENDPOINT), 'same', URI.create(ENDPOINT), 'same')
        then: thrown(IllegalArgumentException)
    }
    def 'request guards reject wrong connector origin port method path or POST Origin'() {
        when: configured().requireRequest(scheme,host,port,method,path,origin)
        then: thrown(IllegalArgumentException)
        where:
        scheme | host                | port | method | path                        | origin
        'http' | 'auth.example.test' | 443  | 'POST' | '/migration/managed/start'   | ORIGIN
        'https'| 'evil.example.test' | 443  | 'POST' | '/migration/managed/start'   | ORIGIN
        'https'| 'auth.example.test' | 8443 | 'POST' | '/migration/managed/start'   | ORIGIN
        'https'| 'auth.example.test' | 443  | 'PUT'  | '/migration/managed/start'   | ORIGIN
        'https'| 'auth.example.test' | 443  | 'POST' | '/migration/managed/start'   | null
        'https'| 'auth.example.test' | 443  | 'POST' | '/migration/managed/start'   | 'https://evil.example.test'
        'https'| 'auth.example.test' | 443  | 'GET'  | '/migration/managed/start'   | null
        'https'| 'auth.example.test' | 443  | 'POST' | '/migration/managed/callback'| ORIGIN
        'https'| 'auth.example.test' | 443  | 'GET'  | '/migration/managed/callback'| 'https://evil.example.test'
    }
    def 'configured request and producer redirect shapes pass syntax only not account proof'() {
        when:
        configured().requireRequest('https','auth.example.test',443,'POST','/migration/managed/start',ORIGIN)
        configured().requireRequest('https','auth.example.test',443,'GET','/migration/managed/callback',null)
        configured().requireAuthorization(location(),true)
        then: noExceptionThrown()
    }
    def 'authorization redirect denies wrong fixed endpoints or reserved OIDC values'() {
        when: configured().requireAuthorization(location(authorization() + override, endpoint),true)
        then: thrown(IllegalArgumentException)
        where:
        override                                  | endpoint
        [:]                                       | 'https://evil.example.test/auth'
        [client_id:'legacy-migration']             | ENDPOINT
        [redirect_uri:'https://evil.example.test'] | ENDPOINT
        [response_type:'token']                   | ENDPOINT
        [code_challenge_method:'plain']           | ENDPOINT
        [scope:'openid profile']                  | ENDPOINT
        [prompt:'none']                           | ENDPOINT
        [max_age:'1']                             | ENDPOINT
        [max_age:'0.0']                           | ENDPOINT
        [max_age:'+0']                            | ENDPOINT
        [nonce:'bad']                             | ENDPOINT
        [extra:'secret']                          | ENDPOINT
    }
    def 'authorization query requires exactly one max_age zero for both configured purposes'() {
        when:
        configured().requireAuthorization(location(),true)
        configured().requireAuthorization(location(authorization() + [client_id:'legacy-migration',
            redirect_uri:ORIGIN + LegacyMigrationBrowserConfiguration.LEGACY_CALLBACK]),false)
        then: noExceptionThrown()
        when:
        def fields = authorization(); fields.remove('max_age')
        configured().requireAuthorization(location(fields),true)
        then: thrown(IllegalArgumentException)
        when:
        configured().requireAuthorization(URI.create(location().toString() + '&max_age=0'),true)
        then: thrown(IllegalArgumentException)
    }
    def 'form parser rejects duplicate encoded keys extras malformed encoding controls and oversize'() {
        when: LegacyMigrationBrowserRoutes.form(body.getBytes(StandardCharsets.US_ASCII), ['csrf','login','password'] as Set)
        then: thrown(IllegalArgumentException)
        where:
        body << ['csrf=x&login=y&password=z&%63srf=q', 'csrf=x&login=y&password=z&identity_id=secret',
                 'csrf=x&login=y', 'csrf=x&login=y&password=%GG', 'csrf=x&login=y&password=%C0%AF',
                 'csrf=x&login=y&password=%0D%0A', 'csrf=x&login=y&password=', 'x' * 8193]
    }
    def 'callback parser denies duplicates errors unknown fields missing bindings or malformed state'() {
        when: LegacyMigrationBrowserRoutes.callback(query)
        then: thrown(IllegalArgumentException)
        where:
        query << ['state=' + TOKEN + '&code=x&state=' + TOKEN, 'state=' + TOKEN + '&error=access_denied',
                  'state=' + TOKEN + '&code=x&attempt_id=secret', 'code=x', 'state=bad&code=x',
                  'state=' + TOKEN + '&code=', 'state=' + TOKEN + '&code=%0A',
                  'state=' + TOKEN + '&code=x&%63ode=y']
    }
    def 'callback accepts optional Keycloak metadata without forwarding it as proof'() {
        when:
        def parsed = LegacyMigrationBrowserRoutes.callback('state=' + TOKEN + '&code=original' + extra, URI.create(ENDPOINT))
        then:
        parsed.state() == TOKEN
        parsed.code() == 'original'
        !parsed.toString().contains('original')
        where:
        extra << ['', '&session_state=opaque-session', '&iss=https%3A%2F%2Fsso.example.test%2Fsso%2Frealms%2Farmoney',
            '&session_state=opaque-session&iss=https%3A%2F%2Fsso.example.test%2Fsso%2Frealms%2Farmoney']
    }
    def 'callback metadata rejects ambiguity foreign issuer malformed values and fragments'() {
        when:
        LegacyMigrationBrowserRoutes.callback('state=' + TOKEN + '&code=original' + extra, URI.create(ENDPOINT))
        then: thrown(IllegalArgumentException)
        where:
        extra << ['&iss=https://evil.example.test/sso/realms/armoney', '&iss=', '&session_state=',
            '&session_state=x&%73ession_state=y', '&iss=x&%69ss=x', '&session_state=%C0%AF',
            '&session_state=%0A', '&session_state=%GG', '&session_state=' + ('x' * 513),
            '&session_state=x+y', '&error=access_denied', '#fragment', '&unknown=x',
            '&iss=https://sso.example.test/sso/realms/armoney/', '&session_state=x&session_state=y']
    }
    def 'callback issuer cannot select another trusted purpose issuer'() {
        when:
        LegacyMigrationBrowserRoutes.callback('state=' + TOKEN + '&code=original&iss=https://sso.example.test/sso/realms/armoney',
            URI.create('https://other.example.test/sso/realms/armoney/protocol/openid-connect/auth'))
        then: thrown(IllegalArgumentException)
    }
    def 'both callbacks reject malformed metadata before producer and preserve cookies'() {
        given:
        def service = Mock(LegacyMigrationBrowserService)
        def exchange = request(path, 'GET', ['Cookie':[LegacyMigrationBrowserRoutes.ATTEMPT_COOKIE + '=' + TOKEN + '; ' +
            LegacyMigrationBrowserRoutes.ACCOUNT_COOKIE + '=' + COOKIE]], 'state=' + TOKEN + '&code=original&' + metadata)
        when: routes(service).handle(exchange.context)
        then:
        0 * service._
        exchange.seen.status == 400
        exchange.seen.cookies.empty
        !exchange.seen.body.contains('original')
        where:
        [path, metadata] << [
            [LegacyMigrationBrowserConfiguration.LEGACY_CALLBACK, LegacyMigrationBrowserConfiguration.MANAGED_CALLBACK],
            ['iss=https://evil.example.test', 'session_state=x&%73ession_state=y', 'session_state=%ED%A0%80']
        ].combinations()
    }
    def 'both callbacks accept provider metadata only against their server-selected endpoint'() {
        given:
        def service = Mock(LegacyMigrationBrowserService)
        def exchange = request(path, 'GET', ['Cookie':[LegacyMigrationBrowserRoutes.ATTEMPT_COOKIE + '=' + TOKEN + '; ' +
            LegacyMigrationBrowserRoutes.ACCOUNT_COOKIE + '=' + COOKIE]], 'state=' + TOKEN +
            '&code=original&session_state=opaque&iss=https%3A%2F%2Fsso.example.test%2Fsso%2Frealms%2Farmoney')
        when: routes(service).handle(exchange.context)
        then:
        (legacy ? 1 : 0) * service.accountOidcCallback({ it.providerCode() == 'original' && it.returnedState() == TOKEN && it.initiatingBrowserCookie() == TOKEN }) >> {
            throw new LegacyMigrationBrowserService.Failure(LegacyMigrationBrowserService.Kind.UNAUTHORIZED,false)
        }
        (legacy ? 0 : 1) * service.managedVerification({ it.providerCode() == 'original' && it.returnedState() == TOKEN && it.initiatingBrowserCookie() == TOKEN }, COOKIE) >> {
            throw new LegacyMigrationBrowserService.Failure(LegacyMigrationBrowserService.Kind.UNAUTHORIZED,false)
        }
        0 * service._
        exchange.seen.status == 401
        exchange.seen.cookies.empty
        where:
        path | legacy
        LegacyMigrationBrowserConfiguration.LEGACY_CALLBACK | true
        LegacyMigrationBrowserConfiguration.MANAGED_CALLBACK | false
    }
    def 'duplicate or absent cookies and noncanonical attempt values deny before any authority call'() {
        when: LegacyMigrationBrowserRoutes.cookie(headers, LegacyMigrationBrowserRoutes.ATTEMPT_COOKIE)
        then: thrown(IllegalArgumentException)
        where:
        headers << [[], [LegacyMigrationBrowserRoutes.ATTEMPT_COOKIE + '=' + TOKEN] * 2,
                    [LegacyMigrationBrowserRoutes.ATTEMPT_COOKIE + '=' + TOKEN + '; ' + LegacyMigrationBrowserRoutes.ATTEMPT_COOKIE + '=' + TOKEN],
                    ['other=' + TOKEN], [LegacyMigrationBrowserRoutes.ATTEMPT_COOKIE + '=bad'],
                    [LegacyMigrationBrowserRoutes.ATTEMPT_COOKIE + '=' + TOKEN + '; other=x; other=y']]
    }
    def 'attempt cookie creation and clearing retain exact host-only flags and original remaining bound'() {
        expect:
        LegacyMigrationBrowserRoutes.attemptCookie(TOKEN,120) ==
            '__Secure-ArMoneyMigrationAttempt=' + TOKEN + '; Path=/migration/; Secure; HttpOnly; SameSite=Lax; Max-Age=120'
        LegacyMigrationBrowserRoutes.clearAttemptCookie() ==
            '__Secure-ArMoneyMigrationAttempt=; Path=/migration/; Secure; HttpOnly; SameSite=Lax; Max-Age=0'
        !LegacyMigrationBrowserRoutes.attemptCookie(TOKEN,1).contains('Domain=')
        when: LegacyMigrationBrowserRoutes.attemptCookie(TOKEN,121)
        then: thrown(IllegalArgumentException)
    }
    def 'exact three cookie roles preserve flags parse separately and reject any alternate namespace'() {
        given:
        def roles = [LegacyMigrationBrowserRoutes.ATTEMPT_COOKIE, LegacyMigrationBrowserRoutes.FORM_COOKIE,
                     LegacyMigrationBrowserRoutes.ACCOUNT_COOKIE]
        def header = roles.collect { it + '=' + TOKEN }.join('; ')
        expect:
        roles.every { LegacyMigrationBrowserRoutes.cookie([header],it).value() == TOKEN }
        roles.every { LegacyMigrationBrowserRoutes.cookieHeader(it,TOKEN,60) ==
            it + '=' + TOKEN + '; Path=/migration/; Secure; HttpOnly; SameSite=Lax; Max-Age=60' }
        roles.every { LegacyMigrationBrowserRoutes.clearCookie(it) ==
            it + '=; Path=/migration/; Secure; HttpOnly; SameSite=Lax; Max-Age=0' }
        when: LegacyMigrationBrowserRoutes.cookie([header], '__Host-armoney-migration-form')
        then: thrown(IllegalArgumentException)
        when: LegacyMigrationBrowserRoutes.cookieHeader('__Secure-Other',TOKEN,60)
        then: thrown(IllegalArgumentException)
        when: LegacyMigrationBrowserRoutes.clearCookie('__Secure-Other')
        then: thrown(IllegalArgumentException)
    }
    def 'disabled configuration registers nothing and enabled constructor rejects absent genuine facade'() {
        when:
        new LegacyMigrationBrowserRoutes(LegacyMigrationBrowserConfiguration.disabled(),LegacyMigrationBrowserIngressConfiguration.disabled(),null).configure(null)
        then: noExceptionThrown()
        when: new LegacyMigrationBrowserRoutes(configured(),ingress(),null)
        then: thrown(NullPointerException)
    }
    def 'route configuration refuses disabled or foreign-origin ingress without TLS fallback'() {
        when:
        new LegacyMigrationBrowserRoutes(configured(),LegacyMigrationBrowserIngressConfiguration.disabled(),Mock(LegacyMigrationBrowserService))
        then: thrown(IllegalArgumentException)
        when:
        def other = LegacyMigrationBrowserIngressConfiguration.from([AUTH_MIGRATION_BROWSER_ENABLED:'true',
            MIGRATION_PUBLIC_ORIGIN:'https://other.example.test',MIGRATION_GATEWAY_TO_AUTH_KEY:INGRESS_KEY])
        new LegacyMigrationBrowserRoutes(configured(),other,Mock(LegacyMigrationBrowserService))
        then: thrown(IllegalArgumentException)
    }
    def 'private HTTP without purpose key or with stripped client assertions denies before body cookie or facade'() {
        given:
        def service = Mock(LegacyMigrationBrowserService)
        def exchange = request('/migration/managed/callback','GET',headers,
            'state=' + TOKEN + '&code=SECRET-CODE')
        when: routes(service).handle(exchange.context)
        then:
        0 * service._
        exchange.seen.status == 400
        exchange.seen.cookies.empty
        !exchange.seen.body.contains('SECRET')
        where:
        headers << [['X-ArMoney-Migration-Auth-Key':[]],
                    ['X-ArMoney-Migration-Auth-Key':[INGRESS_KEY,INGRESS_KEY]],
                    ['X-ArMoney-Migration-Auth-Key':['wrong']],
                    ['X-ArMoney-Migration-Origin':['https://evil.example.test']],
                    ['X-ArMoney-Migration-Origin':[ORIGIN,ORIGIN]],
                    ['X-ArMoney-Migration-Edge-Key':[INGRESS_KEY]],
                    ['Forwarded':['proto=https;host=auth.example.test']],
                    ['X-Forwarded-Proto':['https']],['X-Identity-Id':['forged']],['X-Service-Key':['forged']]]
    }
    def 'form renders only server-issued CSRF command and HttpOnly form binder without exposing cookie'() {
        given:
        def service = Mock(LegacyMigrationBrowserService)
        def exchange = request('/migration/legacy')
        when: routes(service).handle(exchange.context)
        then:
        1 * service.form(null) >> new LegacyMigrationBrowserService.Form(COOKIE,TOKEN,COMMAND,NOW.plusSeconds(100))
        0 * service._
        exchange.seen.status == 200
        exchange.seen.cookies == [LegacyMigrationBrowserRoutes.cookieHeader(LegacyMigrationBrowserRoutes.FORM_COOKIE,COOKIE,100)]
        exchange.seen.body.contains('name="csrf"') && exchange.seen.body.contains(COMMAND.toString())
        exchange.seen.body.contains('Existing email') && !exchange.seen.body.contains('Phone')
        !exchange.seen.body.contains(LegacyMigrationBrowserRoutes.FORM_COOKIE) && !exchange.seen.body.contains(COOKIE)
        exchange.seen.headers['Referrer-Policy'] == 'no-referrer'
    }
    def 'wrong POST origin duplicate cookies or unknown form fields deny before facade'() {
        given:
        def service = Mock(LegacyMigrationBrowserService)
        def headers = ['Origin':[origin], 'Content-Type':['application/x-www-form-urlencoded'],
                       'Cookie':[cookie]]
        def exchange = request('/migration/managed/start','POST',headers)
        when: routes(service,body).handle(exchange.context)
        then:
        0 * service._
        exchange.seen.status == 400
        exchange.seen.cookies.empty
        !exchange.seen.body.contains('SECRET')
        where:
        origin                      | cookie                                                                                     | body
        'https://evil.example.test'  | LegacyMigrationBrowserRoutes.ACCOUNT_COOKIE + '=' + TOKEN                                   | 'csrf=' + TOKEN + '&command_id=' + COMMAND
        ORIGIN                      | LegacyMigrationBrowserRoutes.ACCOUNT_COOKIE + '=' + TOKEN + '; ' + LegacyMigrationBrowserRoutes.ACCOUNT_COOKIE + '=' + TOKEN | 'csrf=' + TOKEN + '&command_id=' + COMMAND
        ORIGIN                      | LegacyMigrationBrowserRoutes.ACCOUNT_COOKIE + '=' + TOKEN                                   | 'csrf=' + TOKEN + '&command_id=' + COMMAND + '&identity_id=SECRET'
    }
    def 'candidate account renders only candidate form with original deadline and producer command'() {
        given:
        def service = Mock(LegacyMigrationBrowserService)
        def exchange = request('/migration/legacy/password','POST',['Origin':[ORIGIN],
            'Content-Type':['application/x-www-form-urlencoded'],'Cookie':[LegacyMigrationBrowserRoutes.FORM_COOKIE + '=' + TOKEN]])
        when:
        routes(service,'csrf=' + TOKEN + '&command_id=' + COMMAND + '&legacy_login=old%40example.test&password=SECRET').handle(exchange.context)
        then:
        1 * service.password(TOKEN,TOKEN,'old@example.test','SECRET',COMMAND) >> new LegacyMigrationBrowserService.Account(COOKIE,TOKEN,COMMAND,NOW.plusSeconds(70),true)
        0 * service._
        exchange.seen.status == 200
        exchange.seen.body.contains('action="/migration/legacy/email"')
        !exchange.seen.body.contains('action="/migration/managed/start"')
        !exchange.seen.body.contains('old@example.test') && !exchange.seen.body.contains('SECRET') && !exchange.seen.body.contains(COOKIE)
        exchange.seen.cookies.contains(LegacyMigrationBrowserRoutes.cookieHeader(LegacyMigrationBrowserRoutes.ACCOUNT_COOKIE,COOKIE,70))
    }
    def 'email candidate calls genuine facade once and renders only newly issued managed form'() {
        given:
        def service = Mock(LegacyMigrationBrowserService)
        def exchange = request('/migration/legacy/email','POST',['Origin':[ORIGIN],
            'Content-Type':['application/x-www-form-urlencoded'],'Cookie':[LegacyMigrationBrowserRoutes.ACCOUNT_COOKIE + '=' + TOKEN]])
        when:
        routes(service,'csrf=' + TOKEN + '&command_id=' + COMMAND + '&email=candidate%40example.test').handle(exchange.context)
        then:
        1 * service.emailCandidate(TOKEN,TOKEN,COMMAND,'candidate@example.test') >> new LegacyMigrationBrowserService.Account(COOKIE,COOKIE,COMMAND,NOW.plusSeconds(60),false)
        0 * service._
        exchange.seen.status == 200
        exchange.seen.body.contains('action="/migration/managed/start"')
        !exchange.seen.body.contains('action="/migration/legacy/email"') && !exchange.seen.body.contains('candidate@example.test')
        exchange.seen.cookies.contains(LegacyMigrationBrowserRoutes.cookieHeader(LegacyMigrationBrowserRoutes.ACCOUNT_COOKIE,COOKIE,60))
        !exchange.seen.headers.containsKey('Location')
    }
    def 'invalid candidate fields origin or account binder never call facade or reflect candidate'() {
        given:
        def service = Mock(LegacyMigrationBrowserService)
        def exchange = request('/migration/legacy/email','POST',['Origin':[origin],
            'Content-Type':['application/x-www-form-urlencoded'],'Cookie':[cookie]])
        when: routes(service,body).handle(exchange.context)
        then:
        0 * service._
        exchange.seen.status == 400
        exchange.seen.cookies.empty
        !exchange.seen.body.contains('SECRET') && !exchange.seen.body.contains('example.test')
        where:
        origin | cookie | body
        ORIGIN | LegacyMigrationBrowserRoutes.ACCOUNT_COOKIE + '=' + TOKEN | 'csrf=' + TOKEN + '&command_id=' + COMMAND + '&email=SECRET%40example.test'
        ORIGIN | LegacyMigrationBrowserRoutes.ACCOUNT_COOKIE + '=' + TOKEN | 'csrf=' + TOKEN + '&command_id=' + COMMAND + '&email=%20secret%40example.test'
        ORIGIN | LegacyMigrationBrowserRoutes.ACCOUNT_COOKIE + '=' + TOKEN | 'csrf=' + TOKEN + '&command_id=' + COMMAND + '&email=SECRET'
        ORIGIN | LegacyMigrationBrowserRoutes.ACCOUNT_COOKIE + '=' + TOKEN | 'csrf=' + TOKEN + '&command_id=' + COMMAND + '&email=' + ('a' * 250) + '%40example.test'
        ORIGIN | LegacyMigrationBrowserRoutes.ACCOUNT_COOKIE + '=' + TOKEN | 'csrf=' + TOKEN + '&command_id=' + COMMAND + '&email=secret%40example.test&email=secret%40example.test'
        ORIGIN | LegacyMigrationBrowserRoutes.ACCOUNT_COOKIE + '=' + TOKEN | 'csrf=' + TOKEN + '&command_id=' + COMMAND + '&email=secret%40example.test&owner_id=SECRET'
        ORIGIN | LegacyMigrationBrowserRoutes.FORM_COOKIE + '=' + TOKEN | 'csrf=' + TOKEN + '&command_id=' + COMMAND + '&email=secret%40example.test'
        ORIGIN | LegacyMigrationBrowserRoutes.ACCOUNT_COOKIE + '=' + TOKEN + '; ' + LegacyMigrationBrowserRoutes.ACCOUNT_COOKIE + '=' + TOKEN | 'csrf=' + TOKEN + '&command_id=' + COMMAND + '&email=secret%40example.test'
        'https://evil.example.test' | LegacyMigrationBrowserRoutes.ACCOUNT_COOKIE + '=' + TOKEN | 'csrf=' + TOKEN + '&command_id=' + COMMAND + '&email=secret%40example.test'
        ORIGIN | LegacyMigrationBrowserRoutes.ACCOUNT_COOKIE + '=' + TOKEN | 'csrf=SECRET&command_id=' + COMMAND + '&email=secret%40example.test'
    }
    def 'candidate replay denial preserves original cookies and never renews expiry'() {
        given:
        def service = Mock(LegacyMigrationBrowserService)
        def exchange = request('/migration/legacy/email','POST',['Origin':[ORIGIN],
            'Content-Type':['application/x-www-form-urlencoded'],'Cookie':[LegacyMigrationBrowserRoutes.ACCOUNT_COOKIE + '=' + TOKEN]])
        when: routes(service,'csrf=' + TOKEN + '&command_id=' + COMMAND + '&email=secret%40example.test').handle(exchange.context)
        then:
        1 * service.emailCandidate(TOKEN,TOKEN,COMMAND,'secret@example.test') >> { throw new LegacyMigrationBrowserService.Failure(LegacyMigrationBrowserService.Kind.CONFLICT,false) }
        0 * service._
        exchange.seen.status == 409
        exchange.seen.cookies.empty
        !exchange.seen.body.contains('secret@example.test')
    }
    def 'first managed start uses original account binder and issues independent attempt cookie with fixed303'() {
        given:
        def service = Mock(LegacyMigrationBrowserService)
        def exchange = request('/migration/managed/start','POST', ['Origin':[ORIGIN],
            'Content-Type':['application/x-www-form-urlencoded'],'Cookie':[LegacyMigrationBrowserRoutes.ACCOUNT_COOKIE + '=' + TOKEN]])
        when: routes(service,'csrf=' + TOKEN + '&command_id=' + COMMAND).handle(exchange.context)
        then:
        1 * service.managedStart(TOKEN,TOKEN,COMMAND,null) >> new LegacyMigrationBrowserService.Redirect(location(),COOKIE,NOW.plusSeconds(90))
        0 * service._
        exchange.seen.status == 303
        exchange.seen.headers['Location'] == location().toASCIIString()
        exchange.seen.cookies == [LegacyMigrationBrowserRoutes.attemptCookie(COOKIE,90)]
        exchange.seen.body == ''
    }
    def 'active original attempt is passed to producer and denial never renews or clears cookie'() {
        given:
        def service = Mock(LegacyMigrationBrowserService)
        def exchange = request('/migration/legacy','GET',['Cookie':[LegacyMigrationBrowserRoutes.ATTEMPT_COOKIE + '=' + TOKEN]])
        when: routes(service).handle(exchange.context)
        then:
        1 * service.form(TOKEN) >> { throw new LegacyMigrationBrowserService.Failure(LegacyMigrationBrowserService.Kind.CONFLICT,false) }
        0 * service._
        exchange.seen.status == 409
        exchange.seen.cookies.empty
        !exchange.seen.headers.containsKey('Location')
    }
    def 'GET callback foreign or duplicate Origin denies before facade despite valid original cookie and state'() {
        given:
        def service = Mock(LegacyMigrationBrowserService)
        def exchange = request('/migration/managed/callback','GET',['Origin':origins,
            'Cookie':[LegacyMigrationBrowserRoutes.ATTEMPT_COOKIE + '=' + TOKEN]],
            'state=' + TOKEN + '&code=SECRET-CODE')
        when: routes(service).handle(exchange.context)
        then:
        0 * service._
        exchange.seen.status == 400
        exchange.seen.cookies.empty
        !exchange.seen.body.contains('SECRET')
        where:
        origins << [['https://evil.example.test'],[ORIGIN,ORIGIN]]
    }
    def 'duplicate callback query denies before producer and never clears an original cookie'() {
        given:
        def service = Mock(LegacyMigrationBrowserService)
        def exchange = request('/migration/managed/callback','GET',['Cookie':[LegacyMigrationBrowserRoutes.ATTEMPT_COOKIE + '=' + TOKEN]],
            'state=' + TOKEN + '&code=SECRET-CODE&state=' + TOKEN)
        when: routes(service).handle(exchange.context)
        then:
        0 * service._
        exchange.seen.status == 400
        exchange.seen.cookies.empty
        !exchange.seen.body.contains('SECRET')
    }
    def 'wrong callback binder state or replay is producer-denied without credential echo or implicitclear'() {
        given:
        def service = Mock(LegacyMigrationBrowserService)
        def exchange = request('/migration/managed/callback','GET',['Cookie':[LegacyMigrationBrowserRoutes.ATTEMPT_COOKIE + '=' + TOKEN + '; ' + LegacyMigrationBrowserRoutes.ACCOUNT_COOKIE + '=' + COOKIE]],
            'state=' + TOKEN + '&code=SECRET-CODE')
        when: routes(service).handle(exchange.context)
        then:
        1 * service.managedVerification({ MigrationManagedProofPort.Callback value ->
            value.providerCode() == 'SECRET-CODE' && value.returnedState() == TOKEN && value.initiatingBrowserCookie() == TOKEN
        },COOKIE) >> { throw new LegacyMigrationBrowserService.Failure(LegacyMigrationBrowserService.Kind.UNAUTHORIZED,false) }
        0 * service._
        exchange.seen.status == 401
        exchange.seen.cookies.empty
        !exchange.seen.body.contains('SECRET') && !exchange.seen.body.contains(TOKEN)
    }
    def 'uncertain managed continuation failure preserves original restricted cookies'() {
        given:
        def service = Mock(LegacyMigrationBrowserService)
        def exchange = request('/migration/managed/callback','GET',['Cookie':[LegacyMigrationBrowserRoutes.ATTEMPT_COOKIE + '=' + TOKEN + '; ' + LegacyMigrationBrowserRoutes.ACCOUNT_COOKIE + '=' + COOKIE]],
            'state=' + TOKEN + '&code=SECRET-CODE')
        when: routes(service).handle(exchange.context)
        then:
        1 * service.managedVerification(_,COOKIE) >> { throw new LegacyMigrationBrowserService.Failure(LegacyMigrationBrowserService.Kind.UNAVAILABLE,true) }
        0 * service._
        exchange.seen.status == 503
        exchange.seen.cookies.empty
    }
    def 'producer-supplied off-origin redirect is rejected without Location or attemptcookie'() {
        given:
        def service = Mock(LegacyMigrationBrowserService)
        def exchange = request('/migration/managed/start','POST',['Origin':[ORIGIN],
            'Content-Type':['application/x-www-form-urlencoded'],'Cookie':[LegacyMigrationBrowserRoutes.ACCOUNT_COOKIE + '=' + TOKEN]])
        when: routes(service,'csrf=' + TOKEN + '&command_id=' + COMMAND).handle(exchange.context)
        then:
        1 * service.managedStart(TOKEN,TOKEN,COMMAND,null) >> new LegacyMigrationBrowserService.Redirect(URI.create('https://evil.example.test/?code=SECRET'),TOKEN,NOW.plusSeconds(90))
        0 * service._
        exchange.seen.status == 400
        !exchange.seen.headers.containsKey('Location')
        exchange.seen.cookies.empty
        !exchange.seen.body.contains('SECRET')
    }
    private static LegacyMigrationBrowserService.ActionForm action(LegacyMigrationBrowserService.Action purpose) {
        new LegacyMigrationBrowserService.ActionForm(purpose,TOKEN,COMMAND,null,5)
    }
    private static LegacyMigrationBrowserService.ActionBundle bundle(List forms) {
        new LegacyMigrationBrowserService.ActionBundle(COMMAND,forms,NOW.plusSeconds(60))
    }
    def 'all continuation POST endpoints use account CSRF command and the exact genuine facade method'() {
        given:
        def service = Mock(LegacyMigrationBrowserService)
        def exchange = request(path,'POST',['Origin':[ORIGIN],'Content-Type':['application/x-www-form-urlencoded'],
            'Cookie':[LegacyMigrationBrowserRoutes.ACCOUNT_COOKIE + '=' + TOKEN]])
        when: routes(service,'csrf=' + TOKEN + '&command_id=' + COMMAND).handle(exchange.context)
        then:
        1 * service."$target"(TOKEN,TOKEN,COMMAND) >> new LegacyMigrationBrowserService.ReadOnlyStatus(LegacyMigrationBrowserService.Status.PENDING_RESULT,NOW.plusSeconds(60))
        0 * service._
        exchange.seen.status == 503
        exchange.seen.cookies.empty
        where:
        path | target
        '/migration/legacy/verify/stage' | 'stage'
        '/migration/legacy/otp/issue' | 'issue'
        '/migration/legacy/verify/decision' | 'authorizePublication'
        '/migration/legacy/verify/publication' | 'publish'
        '/migration/legacy/verify/final-command' | 'prepareFinal'
        '/migration/legacy/verify/final' | 'finish'
    }
    def 'consume uses exact six ASCII digits without code reflection and renders ordered consume resend bundle'() {
        given:
        def service = Mock(LegacyMigrationBrowserService)
        def exchange = request('/migration/legacy/otp/consume','POST',['Origin':[ORIGIN],
            'Content-Type':['application/x-www-form-urlencoded'],'Cookie':[LegacyMigrationBrowserRoutes.ACCOUNT_COOKIE + '=' + TOKEN]])
        def resend = new LegacyMigrationBrowserService.ActionForm(LegacyMigrationBrowserService.Action.EMAIL_ISSUE,COOKIE,
            UUID.fromString('22222222-2222-4222-8222-222222222222'),NOW.plusSeconds(30),5)
        when: routes(service,'csrf=' + TOKEN + '&command_id=' + COMMAND + '&code=123456').handle(exchange.context)
        then:
        1 * service.consume(TOKEN,TOKEN,COMMAND,'123456') >> bundle([action(LegacyMigrationBrowserService.Action.EMAIL_CONSUME),resend])
        0 * service._
        exchange.seen.status == 200
        exchange.seen.body.indexOf('action="/migration/legacy/otp/consume"') < exchange.seen.body.indexOf('action="/migration/legacy/otp/issue"')
        exchange.seen.body.count('<form ') == 2
        exchange.seen.body.contains(' disabled')
        !exchange.seen.body.contains('123456') && !exchange.seen.body.contains(LegacyMigrationBrowserRoutes.ACCOUNT_COOKIE)
        exchange.seen.cookies.empty
    }
    def 'continuation rejects malformed code extra purpose missing account or query before facade'() {
        given:
        def service = Mock(LegacyMigrationBrowserService)
        def exchange = request('/migration/legacy/otp/consume','POST',['Origin':[ORIGIN],
            'Content-Type':['application/x-www-form-urlencoded'],'Cookie':[cookie]],query)
        when: routes(service,'csrf=' + TOKEN + '&command_id=' + COMMAND + suffix).handle(exchange.context)
        then:
        0 * service._
        exchange.seen.status == 400
        exchange.seen.cookies.empty
        !exchange.seen.body.contains('SECRET')
        where:
        suffix | cookie | query
        '&code=12345' | LegacyMigrationBrowserRoutes.ACCOUNT_COOKIE + '=' + TOKEN | null
        '&code=1234567' | LegacyMigrationBrowserRoutes.ACCOUNT_COOKIE + '=' + TOKEN | null
        '&code=12a456' | LegacyMigrationBrowserRoutes.ACCOUNT_COOKIE + '=' + TOKEN | null
        '&code=%EF%BC%91%EF%BC%92%EF%BC%93%EF%BC%94%EF%BC%95%EF%BC%96' | LegacyMigrationBrowserRoutes.ACCOUNT_COOKIE + '=' + TOKEN | null
        '&code=123456&purpose=SECRET' | LegacyMigrationBrowserRoutes.ACCOUNT_COOKIE + '=' + TOKEN | null
        '&code=123456&code=123456' | LegacyMigrationBrowserRoutes.ACCOUNT_COOKIE + '=' + TOKEN | null
        '&code=123456' | LegacyMigrationBrowserRoutes.FORM_COOKIE + '=' + TOKEN | null
        '&code=123456' | LegacyMigrationBrowserRoutes.ACCOUNT_COOKIE + '=' + TOKEN | 'secret=SECRET'
    }
    def 'managed callback requires account cookie before any callback claim'() {
        given:
        def service = Mock(LegacyMigrationBrowserService)
        def exchange = request('/migration/managed/callback','GET',['Cookie':[LegacyMigrationBrowserRoutes.ATTEMPT_COOKIE + '=' + TOKEN]],'state=' + TOKEN + '&code=SECRET')
        when: routes(service).handle(exchange.context)
        then:
        0 * service._
        exchange.seen.status == 400
        exchange.seen.cookies.empty
    }
    def 'managed verification receives both original binders and renders only initial stage form'() {
        given:
        def service = Mock(LegacyMigrationBrowserService)
        def exchange = request('/migration/managed/callback','GET',['Cookie':[LegacyMigrationBrowserRoutes.ATTEMPT_COOKIE + '=' + TOKEN + '; ' + LegacyMigrationBrowserRoutes.ACCOUNT_COOKIE + '=' + COOKIE]],'state=' + TOKEN + '&code=SECRET')
        when: routes(service).handle(exchange.context)
        then:
        1 * service.managedVerification({ MigrationManagedProofPort.Callback value -> value.initiatingBrowserCookie() == TOKEN && value.returnedState() == TOKEN && value.providerCode() == 'SECRET' },COOKIE) >> bundle([action(LegacyMigrationBrowserService.Action.STAGE)])
        0 * service._
        exchange.seen.status == 200
        exchange.seen.body.contains('action="/migration/legacy/verify/stage"')
        !exchange.seen.body.contains('SECRET') && !exchange.seen.body.contains(COOKIE)
        exchange.seen.cookies.empty
    }
    def 'status GET allocates no command and clears restricted cookies only for known completed'() {
        given:
        def service = Mock(LegacyMigrationBrowserService)
        def exchange = request('/migration/legacy/verify/status','GET',['Cookie':[LegacyMigrationBrowserRoutes.ACCOUNT_COOKIE + '=' + TOKEN]])
        when: routes(service).handle(exchange.context)
        then:
        1 * service.status(TOKEN) >> new LegacyMigrationBrowserService.ReadOnlyStatus(state,NOW.minusSeconds(10))
        0 * service._
        exchange.seen.status == status
        exchange.seen.cookies.size() == clearCount
        !exchange.seen.body.contains(TOKEN)
        where:
        state | status | clearCount
        LegacyMigrationBrowserService.Status.PENDING_RESULT | 503 | 0
        LegacyMigrationBrowserService.Status.RECOVERY_REQUIRED | 200 | 0
        LegacyMigrationBrowserService.Status.COMPLETED | 200 | 3
    }
    def 'status query and mutation GET never reach facade'() {
        given:
        def service = Mock(LegacyMigrationBrowserService)
        def exchange = request(path,'GET',['Cookie':[LegacyMigrationBrowserRoutes.ACCOUNT_COOKIE + '=' + TOKEN]],query)
        when: routes(service).handle(exchange.context)
        then:
        0 * service._
        exchange.seen.status == 400
        exchange.seen.cookies.empty
        where:
        path | query
        '/migration/legacy/verify/status' | 'command_id=SECRET'
        '/migration/legacy/otp/issue' | null
        '/migration/legacy/otp/consume' | null
    }
    def 'unavailable continuation never clears original cookies even if producer requests clear'() {
        given:
        def service = Mock(LegacyMigrationBrowserService)
        def exchange = request('/migration/legacy/verify/final','POST',['Origin':[ORIGIN],
            'Content-Type':['application/x-www-form-urlencoded'],'Cookie':[LegacyMigrationBrowserRoutes.ACCOUNT_COOKIE + '=' + TOKEN]])
        when: routes(service,'csrf=' + TOKEN + '&command_id=' + COMMAND).handle(exchange.context)
        then:
        1 * service.finish(TOKEN,TOKEN,COMMAND) >> { throw new LegacyMigrationBrowserService.Failure(LegacyMigrationBrowserService.Kind.UNAVAILABLE,true) }
        0 * service._
        exchange.seen.status == 503
        exchange.seen.cookies.empty
    }
    def 'invalid producer bundle cannot render cross-channel forms or expired authority'() {
        given:
        def service = Mock(LegacyMigrationBrowserService)
        def exchange = request('/migration/legacy/verify/status','GET',['Cookie':[LegacyMigrationBrowserRoutes.ACCOUNT_COOKIE + '=' + TOKEN]])
        def second = new LegacyMigrationBrowserService.ActionForm(secondAction,COOKIE,
            UUID.fromString('22222222-2222-4222-8222-222222222222'),null,5)
        when: routes(service).handle(exchange.context)
        then:
        1 * service.status(TOKEN) >> new LegacyMigrationBrowserService.ActionBundle(COMMAND,[action(firstAction),second],expiry)
        0 * service._
        exchange.seen.status == 400
        !exchange.seen.body.contains('<form ')
        exchange.seen.cookies.empty
        where:
        firstAction | secondAction | expiry
        LegacyMigrationBrowserService.Action.EMAIL_CONSUME | LegacyMigrationBrowserService.Action.PHONE_ISSUE | NOW.plusSeconds(60)
        LegacyMigrationBrowserService.Action.EMAIL_ISSUE | LegacyMigrationBrowserService.Action.EMAIL_CONSUME | NOW.plusSeconds(60)
        LegacyMigrationBrowserService.Action.EMAIL_CONSUME | LegacyMigrationBrowserService.Action.EMAIL_ISSUE | NOW.minusSeconds(1)
    }
    def 'parsed sensitive values redact diagnostic strings and headers prevent referrer or caching'() {
        given:
        def callback = LegacyMigrationBrowserRoutes.callback('state=' + TOKEN + '&code=SECRET-CODE')
        def cookie = LegacyMigrationBrowserRoutes.cookie([LegacyMigrationBrowserRoutes.ATTEMPT_COOKIE + '=' + TOKEN], LegacyMigrationBrowserRoutes.ATTEMPT_COOKIE)
        def form = LegacyMigrationBrowserRoutes.form('csrf=x&login=y&password=SECRET-PASSWORD'.getBytes(StandardCharsets.US_ASCII), ['csrf','login','password'] as Set)
        expect:
        !callback.toString().contains('SECRET') && !callback.toString().contains(TOKEN)
        !cookie.toString().contains(TOKEN)
        !form.toString().contains('SECRET')
        LegacyMigrationBrowserRoutes.responseHeaders()['Cache-Control'] == 'no-store'
        LegacyMigrationBrowserRoutes.responseHeaders()['Referrer-Policy'] == 'no-referrer'
        LegacyMigrationBrowserRoutes.responseHeaders()['Content-Security-Policy'].contains("default-src 'none'")
    }
}
