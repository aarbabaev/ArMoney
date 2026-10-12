package com.arman.bank.authservice

import com.arman.bank.authservice.application.AuthFailure
import com.arman.bank.authservice.infrastructure.KeycloakTokens
import com.sun.net.httpserver.HttpServer
import groovy.json.JsonOutput
import spock.lang.Specification
import java.time.*

class KeycloakTokensSpec extends Specification {
    static final String ISSUER = 'https://bank.example/sso/realms/armoney'
    HttpServer server
    KeycloakTokens provider
    Map claims
    int status = 200
    String raw
    int delay

    def setup() {
        claims = [active:true, iss:ISSUER, sub:'stable-subject', phone_number:'+971501234567', aud:['armoney-api'], client_id:'armoney-ios', azp:'armoney-ios', token_type:'Bearer', exp:2000000000]
        server = HttpServer.create(new InetSocketAddress('127.0.0.1', 0), 0)
        server.createContext('/protocol/openid-connect/token/introspect') { exchange ->
            exchange.requestBody.readAllBytes()
            if (delay) Thread.sleep(delay)
            byte[] bytes = (raw ?: JsonOutput.toJson(claims)).getBytes('UTF-8')
            exchange.sendResponseHeaders(status, bytes.length)
            try { exchange.responseBody.write(bytes) } finally { exchange.close() }
        }
        server.start()
        provider = new KeycloakTokens(URI.create("http://127.0.0.1:${server.address.port}/protocol/openid-connect/token/introspect"),
            ISSUER, 'armoney-auth', 'synthetic-test-secret-at-least-32-chars', Clock.fixed(Instant.ofEpochSecond(1900000000), ZoneOffset.UTC))
    }
    def cleanup() { provider?.close(); server?.stop(0) }

    def 'both allowed native clients establish the same issuer and subject with matching or absent azp'() {
        given:
        claims.client_id = nativeClient
        if (authorizedParty) claims.azp = nativeClient
        else claims.remove('azp')
        expect:
        provider.verify('opaque-access-token').issuer() == ISSUER
        provider.verify('opaque-access-token').subject() == 'stable-subject'
        provider.verify('opaque-access-token').phoneNumber() == '+971501234567'
        where:
        nativeClient      | authorizedParty
        'armoney-ios'     | true
        'armoney-ios'     | false
        'armoney-android' | true
        'armoney-android' | false
    }

    def 'client allowlist and authorized party must agree exactly across native clients'() {
        given:
        claims.client_id = nativeClient
        claims.azp = authorizedParty
        when:
        provider.verify('opaque-access-token')
        then:
        def error = thrown(AuthFailure)
        error.kind() == AuthFailure.Kind.UNAUTHORIZED
        where:
        nativeClient        | authorizedParty
        'armoney-ios'       | 'armoney-android'
        'armoney-android'   | 'armoney-ios'
        'armoney-android'   | 'other-client'
        'armoney-android'   | null
        'armoney-android'   | ['armoney-android']
        'armoney-android-x' | 'armoney-android-x'
        'ARMONEY-ANDROID'   | 'ARMONEY-ANDROID'
        'armoney-auth'      | 'armoney-auth'
        null                | 'armoney-android'
        ['armoney-android'] | 'armoney-android'
    }

    def 'rejects unacceptable provider claims'() {
        given:
        claims[field] = value
        when:
        provider.verify('opaque-access-token')
        then:
        def error = thrown(AuthFailure)
        error.kind() == AuthFailure.Kind.UNAUTHORIZED
        where:
        field       | value
        'active'    | false
        'active'    | 'true'
        'iss'       | 'https://other.example/realm'
        'aud'       | ['other-api']
        'client_id' | 'other-client'
        'azp'       | 'other-client'
        'token_type'| 'Refresh'
        'exp'       | 1900000000
        'exp'       | '2000000000'
        'nbf'       | 2000000000
        'sub'       | ''
        'sub'       | null
        'phone_number' | 971501234567
        'phone_number' | ['+971501234567']
    }

    def 'absent phone is passed to the mapping policy for historical identity compatibility'() {
        given:
        claims.remove('phone_number')
        expect:
        provider.verify('opaque-access-token').phoneNumber() == null
    }

    def 'only a valid email and literal boolean verification establish verified contact'() {
        given:
        claims.email = email
        claims.email_verified = verification
        when:
        def principal = provider.verify('opaque-access-token')
        then:
        principal.email() == expectedEmail
        principal.emailVerified() == expectedVerified
        where:
        email                 | verification | expectedEmail         | expectedVerified
        ' Contact@Example.com ' | true       | 'contact@example.com' | true
        'contact@example.com' | false        | 'contact@example.com' | false
        'contact@example.com' | 'true'       | 'contact@example.com' | false
        'contact@example.com' | 1            | 'contact@example.com' | false
        'contact@example.com' | null         | 'contact@example.com' | false
        'invalid'             | true         | null                  | false
        null                  | true         | null                  | false
        ['contact@example.com'] | true       | null                  | false
        ('x' * 255) + '@example.com' | true   | null                  | false
    }

    def 'absent optional email claims do not prevent authentication'() {
        expect:
        provider.verify('opaque-access-token').email() == null
        !provider.verify('opaque-access-token').emailVerified()
    }

    def 'provider failures and unbounded or malformed responses fail unavailable'() {
        given:
        status = responseStatus
        raw = responseBody
        when:
        provider.verify('opaque-access-token')
        then:
        def error = thrown(AuthFailure)
        error.kind() == AuthFailure.Kind.UNAVAILABLE
        where:
        responseStatus | responseBody
        503            | '{}'
        302            | '{}'
        401            | '{}'
        200            | '{'
        200            | '{"active":true,"active":false}'
        200            | ('x' * 32769)
    }

    def 'slow provider has a total deadline'() {
        given:
        delay = 4000
        when:
        provider.verify('opaque-access-token')
        then:
        def error = thrown(AuthFailure)
        error.kind() == AuthFailure.Kind.UNAVAILABLE
    }
}
