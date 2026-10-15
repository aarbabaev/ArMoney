package com.arman.bank.authservice

import com.arman.bank.authservice.application.VerificationService
import com.arman.bank.authservice.application.VerificationStore
import com.arman.bank.authservice.infrastructure.*
import io.javalin.Javalin
import java.net.http.*
import java.time.Duration
import spock.lang.Specification

/** Actual delivery/configuration and loopback verification-route boundaries only.
 * Unpublished proof adapters are deliberately absent. Their original config collision,
 * opt-in and positive parsing tests remain in the original worktree, outside this slice.
 * Here reserved proof credentials are foreign delivery/verification values; proof URLs
 * must remain absent (404), never a synthetic successful implementation.
 */
class VerificationDeliveryCredentialBoundarySpec extends Specification {
    static final String ISSUER = 'https://issuer.example/realm'
    static final List<String> SMS_FOREIGN = ['SMS_SANDBOX_READ_TOKEN', 'SSO_VERIFICATION_BRIDGE_KEY',
        'AUTH_TO_SSO_STATUS_KEY', 'SSO_LOGIN_PROOF_WRITE_KEY', 'SSO_LOGIN_PROOF_RESOLVE_KEY',
        'INTERNAL_AUTH_KEY', 'SSO_CLIENT_SECRET', 'ARMONEY_PROVIDER_BINDING_KEY',
        'VERIFICATION_OTP_HMAC_KEY', 'VERIFICATION_DELIVERY_ENCRYPTION_KEY', 'MAILTRAP_SMTP_PASSWORD']
    static final List<String> RESERVED_FOREIGN = ['SSO_VERIFICATION_BRIDGE_KEY', 'AUTH_TO_SSO_STATUS_KEY',
        'ARMONEY_PROVIDER_BINDING_KEY', 'INTERNAL_AUTH_KEY', 'SSO_CLIENT_SECRET', 'SSO_LOGIN_CODE_HMAC_KEY',
        'VERIFICATION_OTP_HMAC_KEY', 'VERIFICATION_DELIVERY_ENCRYPTION_KEY']
    static Map environment() {
        def env = [AUTH_VERIFICATION_DELIVERY_ENABLED: 'true', AUTH_VERIFICATION_BRIDGE_ENABLED: 'true',
            AUTH_LOGIN_PROOF_BRIDGE_ENABLED: 'false', LOGIN_NOTIFICATIONS_ENABLED: 'false',
            SSO_ISSUER: ISSUER, VERIFICATION_KEY_ID: 'current',
            MAILTRAP_SMTP_USERNAME: 'synthetic-user', MAILTRAP_FROM_EMAIL: 'synthetic@example.test']
        (SMS_FOREIGN + RESERVED_FOREIGN + ['VERIFICATION_SMS_SEND_TOKEN']).unique().eachWithIndex { name, i ->
            env[name] = 'synthetic_independent_credential_' + i.toString().padLeft(3, '0')
        }
        env.VERIFICATION_OTP_HMAC_KEY = Base64.encoder.encodeToString(([11] * 32) as byte[])
        env.VERIFICATION_DELIVERY_ENCRYPTION_KEY = Base64.encoder.encodeToString(([22] * 32) as byte[])
        env
    }

    def 'actual delivery and verification authorities accept distinct credentials without enabling notices'() {
        given:
        def env = environment()
        def before = new HashMap(env)
        expect:
        new VerificationDeliveryConfiguration(env).enabled
        new VerificationConfiguration(env).enabled
        !new EmailConfiguration(env).enabled
        env.LOGIN_NOTIFICATIONS_ENABLED == 'false'
        env.AUTH_LOGIN_PROOF_BRIDGE_ENABLED == 'false'
        env == before
    }

    def 'SMS sender rejects every configured foreign authority even when other feature flags are off'() {
        given:
        def env = environment()
        env.AUTH_VERIFICATION_BRIDGE_ENABLED = 'false'
        env.AUTH_LOGIN_PROOF_BRIDGE_ENABLED = 'false'
        env[name] = env.VERIFICATION_SMS_SEND_TOKEN
        when:
        new VerificationDeliveryConfiguration(env)
        then:
        thrown(IllegalArgumentException)
        where:
        name << SMS_FOREIGN
    }

    def 'verification bridge rejects business service credential reuse'() {
        given:
        def env = environment()
        env.SSO_VERIFICATION_BRIDGE_KEY = env.INTERNAL_AUTH_KEY
        when:
        new VerificationConfiguration(env)
        then:
        thrown(IllegalArgumentException)
    }

    def 'both enabled verification consumers reject shared OTP and encryption material'() {
        given:
        def env = environment()
        env.VERIFICATION_DELIVERY_ENCRYPTION_KEY = env.VERIFICATION_OTP_HMAC_KEY
        when:
        factory.call(env)
        then:
        thrown(IllegalArgumentException)
        where:
        factory << [{ Map candidate -> new VerificationConfiguration(candidate) }, { Map candidate -> new VerificationDeliveryConfiguration(candidate) }]
    }

    def 'delivery alone leaves independent flags disabled and input unchanged'() {
        given:
        def env = environment() + [AUTH_VERIFICATION_BRIDGE_ENABLED: 'false', AUTH_LOGIN_PROOF_BRIDGE_ENABLED: 'false']
        def before = new HashMap(env)
        when:
        def delivery = new VerificationDeliveryConfiguration(env)
        then:
        delivery.enabled
        !new EmailConfiguration(env).enabled
        new VerificationConfiguration(env).routes(null) == null
        env.AUTH_LOGIN_PROOF_BRIDGE_ENABLED == 'false'
        env == before
    }

    def 'disabled construction ignores malformed credentials and creates no verification routes or delivery worker'() {
        given:
        def env = [VERIFICATION_SMS_SEND_TOKEN: '\n', SSO_LOGIN_PROOF_WRITE_KEY: '\n',
            SSO_LOGIN_PROOF_RESOLVE_KEY: '\n', SSO_VERIFICATION_BRIDGE_KEY: '\n', MAILTRAP_SMTP_PASSWORD: '\n']
        def before = new HashMap(env)
        expect:
        new VerificationDeliveryConfiguration(env).worker(null) == null
        new VerificationConfiguration(env).routes(null) == null
        !new EmailConfiguration(env).enabled
        env == before
    }

    def 'packaged independent switches reject ambiguous opt in instead of enabling a feature'() {
        when:
        factory.call([(flag): value])
        then:
        thrown(IllegalArgumentException)
        where:
        [flag, factory, value] << [
            ['AUTH_VERIFICATION_DELIVERY_ENABLED', { Map env -> new VerificationDeliveryConfiguration(env) }],
            ['AUTH_VERIFICATION_BRIDGE_ENABLED', { Map env -> new VerificationConfiguration(env) }],
            ['LOGIN_NOTIFICATIONS_ENABLED', { Map env -> new EmailConfiguration(env) }]
        ].collectMany { pair -> ['TRUE', '1', '', ' true'].collect { value -> [pair[0], pair[1], value] } }
    }

    def 'foreign credentials and bearer presentation cannot authorize any packaged verification bridge operation'() {
        given:
        def env = environment()
        VerificationStore verification = Mock()
        def app = Javalin.create { config ->
            new VerificationRoutes(new VerificationService(verification, Set.of(ISSUER)), env.SSO_VERIFICATION_BRIDGE_KEY).configure(config)
        }.start('127.0.0.1', 0)
        def client = HttpClient.newHttpClient()
        def id = '00000000-0000-0000-0000-000000000001'
        def operations = ['/internal/enrollments', "/internal/enrollments/$id/status",
            "/internal/enrollments/$id/challenges", "/internal/enrollments/$id/challenges/$id/consume"]
        when:
        operations.each { path ->
            def authority = 'SSO_VERIFICATION_BRIDGE_KEY'
            def header = 'X-ArMoney-SSO-Bridge-Key'
            def others = (SMS_FOREIGN + RESERVED_FOREIGN + ['VERIFICATION_SMS_SEND_TOKEN']).unique().findAll { it != authority }
            others.each { foreign ->
                def response = send(client, app.port(), path.toString(), [(header): env[foreign]])
                assert response.statusCode() == 401 : "foreign authority rejected on $path"
                assert response.headers().firstValue('Cache-Control').orElse('') == 'no-store'
                assert !response.body().contains('ACTIVE')
                assert !response.body().contains(env[foreign])
            }
            assert send(client, app.port(), path.toString(), ['Authorization': 'Bearer ' + env[authority]]).statusCode() == 401
            assert send(client, app.port(), path.toString(), ['X-Internal-Key': env[authority]]).statusCode() == 401
            assert send(client, app.port(), path.toString(), [(header): env[authority]]).statusCode() == 400
        }
        then:
        0 * verification._
        cleanup:
        client?.close()
        app?.stop()
    }

    def 'omitted proof endpoints remain absent under every reserved authority and credential presentation'() {
        given:
        def env = environment()
        VerificationStore verification = Mock()
        def app = Javalin.create { config ->
            new AuthRoutes(null, env.INTERNAL_AUTH_KEY).configure(config)
            new VerificationRoutes(new VerificationService(verification, Set.of(ISSUER)), env.SSO_VERIFICATION_BRIDGE_KEY).configure(config)
        }.start('127.0.0.1', 0)
        def client = HttpClient.newHttpClient()
        def operations = [
            ['/internal/sso-login-proofs', 'X-ArMoney-Login-Proof-Write-Key', 'SSO_LOGIN_PROOF_WRITE_KEY'],
            ['/internal/sso-login-proofs/resolve', 'X-ArMoney-Login-Proof-Resolve-Key', 'SSO_LOGIN_PROOF_RESOLVE_KEY']]
        when:
        operations.each { path, header, authority ->
            def credentials = (SMS_FOREIGN + RESERVED_FOREIGN + ['VERIFICATION_SMS_SEND_TOKEN']).unique()
            credentials.each { name ->
                def response = send(client, app.port(), path.toString(), [(header): env[name]])
                assert response.statusCode() == 404
                assert !response.body().contains('ACTIVE')
                assert !response.body().contains(env[name])
            }
            assert send(client, app.port(), path.toString(), ['Authorization': 'Bearer ' + env[authority]]).statusCode() == 404
            assert send(client, app.port(), path.toString(), ['X-Internal-Key': env[authority]]).statusCode() == 404
        }
        then:
        0 * verification._
        cleanup:
        client?.close()
        app?.stop()
    }

    private static HttpResponse<String> send(HttpClient client, int port, String path, Map headers) {
        def request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port$path"))
            .timeout(Duration.ofSeconds(4)).header('Content-Type', 'application/json')
        headers.each { name, value -> request.header(name.toString(), value.toString()) }
        client.send(request.POST(HttpRequest.BodyPublishers.ofString('invalid')).build(), HttpResponse.BodyHandlers.ofString())
    }
}
