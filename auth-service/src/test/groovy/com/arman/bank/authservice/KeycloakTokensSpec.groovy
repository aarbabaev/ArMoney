package com.arman.bank.authservice

import com.arman.bank.authservice.application.SsoTokens
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

    def 'allowed public clients establish stable identity while native absent azp remains compatible'() {
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
        'armoney-web'     | true
    }

    def 'client allowlist and authorized party must agree exactly across public clients'() {
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
        'armoney-web'       | 'armoney-ios'
        'armoney-web'       | 'armoney-android'
        'armoney-web'       | null
        'armoney-web'       | ['armoney-web']
        'armoney-web-x'     | 'armoney-web-x'
        'ARMONEY-WEB'       | 'ARMONEY-WEB'
        'armoney-ios'       | 'armoney-web'
        'armoney-android'   | 'armoney-web'
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

    def 'web tokens require authorized party and API access audience'() {
        given:
        claims.client_id = 'armoney-web'
        claims.azp = 'armoney-web'
        claims.aud = audience
        if (missingAzp) claims.remove('azp')
        when:
        provider.verify('synthetic-web-token')
        then:
        def error = thrown(AuthFailure)
        error.kind() == AuthFailure.Kind.UNAUTHORIZED
        where:
        audience                       | missingAzp
        ['armoney-api', 'armoney-auth'] | true
        ['armoney-web']                 | false
        ['armoney-auth']                | false
        ['armoney-api-other']           | false
        null                           | false
        ['armoney-web', 'armoney-auth'] | false
    }

    def 'web access token preserves stable subject and trusted phone'() {
        given:
        claims.client_id = 'armoney-web'
        claims.azp = 'armoney-web'
        claims.aud = ['armoney-api', 'armoney-auth']
        expect:
        provider.verify('synthetic-web-access-token').phoneNumber() == '+971501234567'
        provider.verify('synthetic-web-access-token').subject() == 'stable-subject'
    }

    def 'browser client does not relax issuer expiry token type or active checks'() {
        given:
        claims.client_id = 'armoney-web'
        claims.azp = 'armoney-web'
        claims[field] = value
        when:
        provider.verify('synthetic-web-token')
        then:
        def error = thrown(AuthFailure)
        error.kind() == AuthFailure.Kind.UNAUTHORIZED
        where:
        field        | value
        'iss'        | 'https://other.example/realm'
        'active'     | false
        'active'     | 'true'
        'token_type' | 'ID'
        'token_type' | 'Refresh'
        'exp'        | 1900000000
        'nbf'        | 2000000000
        'sub'        | ''
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

    def 'absent email verification remains unknown without changing legacy principal normalization'(){
        given:claims.remove('email_verified')
        when:def principal=provider.verify('opaque-access-token')
        then:!principal.emailVerificationClaimKnown();!principal.emailVerified()
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
        principal.emailVerificationClaimKnown() == (verification instanceof Boolean)
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
    def 'authenticated context preserves exact client session timestamps without certifying eligibility'(){
        given:
        claims.sid=session
        claims.iat=1900000000L
        when:
        def context=provider.verify('synthetic-access-token').tokenContext()
        then:
        context.clientId().value()=='armoney-ios'
        context.sessionId().status()==SsoTokens.MetadataStatus.PRESENT_VALID_FORMAT
        context.sessionId().value()==session
        context.issuedAt().value()==Instant.ofEpochSecond(1900000000L)
        context.expiresAt().value()==Instant.ofEpochSecond(2000000000L)
        context.proof().status()==SsoTokens.MetadataStatus.ABSENT
        !context.toString().contains(session)
        !context.sessionId().toString().contains(session)
        where:
        session << ['exact-session','01234567-89ab-cdef-0123-456789abcdef','s'*255,'session-'+new String(Character.toChars(0x1f600))]
    }
    def 'session metadata preserves absence null type and exact invalid-value distinctions without denying legacy token'(){
        given:
        if(present)claims.sid=value
        if(value instanceof String && value.length()==1 && Character.isSurrogate(value.charAt(0))){
            // Preserve the malformed JSON Unicode escape; UTF-8 encoding a lone Java surrogate would replace it.
            def base=JsonOutput.toJson(claims.findAll{key,ignored->key!='sid'})
            def escape=String.format('%c'+'u%04x',92,(int)value.charAt(0))
            raw=base.substring(0,base.length()-1)+',"sid":"'+escape+'"}'
        }
        when:
        def principal=provider.verify('synthetic-access-token')
        then:
        principal.subject()=='stable-subject'
        principal.tokenContext().sessionId().status()==expected
        principal.tokenContext().sessionId().malformation()==reason
        principal.tokenContext().sessionId().value()==null
        where:
        present | value | expected | reason
        false | null | SsoTokens.MetadataStatus.ABSENT | SsoTokens.Malformation.NONE
        true | null | SsoTokens.MetadataStatus.MALFORMED | SsoTokens.Malformation.NULL_VALUE
        true | 17 | SsoTokens.MetadataStatus.MALFORMED | SsoTokens.Malformation.WRONG_TYPE
        true | true | SsoTokens.MetadataStatus.MALFORMED | SsoTokens.Malformation.WRONG_TYPE
        true | ['session'] | SsoTokens.MetadataStatus.MALFORMED | SsoTokens.Malformation.WRONG_TYPE
        true | '' | SsoTokens.MetadataStatus.MALFORMED | SsoTokens.Malformation.INVALID_VALUE
        true | ' session' | SsoTokens.MetadataStatus.MALFORMED | SsoTokens.Malformation.INVALID_VALUE
        true | 'session ' | SsoTokens.MetadataStatus.MALFORMED | SsoTokens.Malformation.INVALID_VALUE
        true | 'a b' | SsoTokens.MetadataStatus.MALFORMED | SsoTokens.Malformation.INVALID_VALUE
        true | 'a\tb' | SsoTokens.MetadataStatus.MALFORMED | SsoTokens.Malformation.INVALID_VALUE
        true | 'a\u0000b' | SsoTokens.MetadataStatus.MALFORMED | SsoTokens.Malformation.INVALID_VALUE
        true | 'a\u00a0b' | SsoTokens.MetadataStatus.MALFORMED | SsoTokens.Malformation.INVALID_VALUE
        true | 'a\u200bb' | SsoTokens.MetadataStatus.MALFORMED | SsoTokens.Malformation.INVALID_VALUE
        true | new String([(char)0xD800] as char[]) | SsoTokens.MetadataStatus.MALFORMED | SsoTokens.Malformation.INVALID_VALUE
        true | new String([(char)0xDC00] as char[]) | SsoTokens.MetadataStatus.MALFORMED | SsoTokens.Malformation.INVALID_VALUE
        true | 's'*256 | SsoTokens.MetadataStatus.MALFORMED | SsoTokens.Malformation.INVALID_VALUE
    }
    def 'iat optional format has no coercion skew or invented fallback'(){
        given:
        if(present)claims.iat=value
        when:
        def context=provider.verify('synthetic-access-token').tokenContext()
        then:
        context.issuedAt().status()==expected
        context.issuedAt().malformation()==reason
        context.issuedAt().value()==instant
        where:
        present | value | expected | reason | instant
        false | null | SsoTokens.MetadataStatus.ABSENT | SsoTokens.Malformation.NONE | null
        true | null | SsoTokens.MetadataStatus.MALFORMED | SsoTokens.Malformation.NULL_VALUE | null
        true | true | SsoTokens.MetadataStatus.MALFORMED | SsoTokens.Malformation.WRONG_TYPE | null
        true | '1900000000' | SsoTokens.MetadataStatus.MALFORMED | SsoTokens.Malformation.WRONG_TYPE | null
        true | 1900000000.0G | SsoTokens.MetadataStatus.MALFORMED | SsoTokens.Malformation.WRONG_TYPE | null
        true | [] | SsoTokens.MetadataStatus.MALFORMED | SsoTokens.Malformation.WRONG_TYPE | null
        true | -1 | SsoTokens.MetadataStatus.MALFORMED | SsoTokens.Malformation.OUT_OF_RANGE | null
        true | 1900000001L | SsoTokens.MetadataStatus.MALFORMED | SsoTokens.Malformation.OUT_OF_RANGE | null
        true | Long.MAX_VALUE | SsoTokens.MetadataStatus.MALFORMED | SsoTokens.Malformation.OUT_OF_RANGE | null
        true | new BigInteger('9223372036854775808') | SsoTokens.MetadataStatus.MALFORMED | SsoTokens.Malformation.OUT_OF_RANGE | null
        true | 0 | SsoTokens.MetadataStatus.PRESENT_VALID_FORMAT | SsoTokens.Malformation.NONE | Instant.EPOCH
        true | 1899999999L | SsoTokens.MetadataStatus.PRESENT_VALID_FORMAT | SsoTokens.Malformation.NONE | Instant.ofEpochSecond(1899999999L)
        true | 1900000000L | SsoTokens.MetadataStatus.PRESENT_VALID_FORMAT | SsoTokens.Malformation.NONE | Instant.ofEpochSecond(1900000000L)
    }
    def 'unrepresentable optional expiry view cannot newly reject previously accepted legacy expiry'(){
        given:
        claims.exp=Long.MAX_VALUE
        when:
        def principal=provider.verify('synthetic-access-token')
        then:
        principal.subject()=='stable-subject'
        principal.tokenContext().expiresAt().status()==SsoTokens.MetadataStatus.MALFORMED
        principal.tokenContext().expiresAt().malformation()==SsoTokens.Malformation.OUT_OF_RANGE
        principal.tokenContext().expiresAt().value()==null
    }
    def 'legacy principal constructors explicitly supply absent context instead of fabricated timestamps or client'(){
        when:
        def principal=withEmail?new SsoTokens.Principal(ISSUER,'legacy','+971501234567','legacy@example.test',true, true):new SsoTokens.Principal(ISSUER,'legacy','+971501234567', null, false, false)
        then:
        [principal.tokenContext().clientId(),principal.tokenContext().sessionId(),principal.tokenContext().issuedAt(),principal.tokenContext().expiresAt(),principal.tokenContext().proof()].every{
            it.status()==SsoTokens.MetadataStatus.ABSENT&&it.value()==null
        }
        where:
        withEmail << [false,true]
    }
    def 'producer proof metadata accepts exact interactive booleans and reuse only with omitted event'(){
        given:
        UUID event=UUID.fromString('01234567-89ab-cdef-0123-456789abcdef')
        claims.armoney_login_kind=kind
        claims.armoney_login_notice_eligible=eligible
        if(kind=='interactive')claims.armoney_login_event_id=event.toString()
        when:
        def metadata=provider.verify('synthetic-access-token').tokenContext().proof()
        then:
        metadata.status()==SsoTokens.MetadataStatus.PRESENT_VALID_FORMAT
        metadata.value().kind()==kind
        metadata.value().eventId()==(kind=='interactive'?event:null)
        metadata.value().loginNoticeEligible()==eligible
        !metadata.toString().contains(event.toString())
        where:
        kind | eligible
        'interactive' | true
        'interactive' | false
        'reuse' | false
    }
    def 'partial null wrongly typed noncanonical and inconsistent proof metadata stays malformed without denying legacy token'(){
        given:
        claims.putAll(proof)
        when:
        def principal=provider.verify('synthetic-access-token')
        then:
        principal.subject()=='stable-subject'
        principal.tokenContext().proof().status()==SsoTokens.MetadataStatus.MALFORMED
        principal.tokenContext().proof().value()==null
        where:
        proof << [
            [armoney_login_kind:'interactive'],
            [armoney_login_event_id:'01234567-89ab-cdef-0123-456789abcdef'],
            [armoney_login_notice_eligible:false],
            [armoney_login_kind:'interactive',armoney_login_event_id:'01234567-89ab-cdef-0123-456789abcdef'],
            [armoney_login_kind:'interactive',armoney_login_notice_eligible:true],
            [armoney_login_kind:null,armoney_login_notice_eligible:false],
            [armoney_login_kind:'reuse',armoney_login_notice_eligible:null],
            [armoney_login_kind:'reuse',armoney_login_notice_eligible:false,armoney_login_event_id:null],
            [armoney_login_kind:'reuse',armoney_login_notice_eligible:false,armoney_login_event_id:'01234567-89ab-cdef-0123-456789abcdef'],
            [armoney_login_kind:'reuse',armoney_login_notice_eligible:true],
            [armoney_login_kind:'interactive',armoney_login_notice_eligible:true,armoney_login_event_id:null],
            [armoney_login_kind:'INTERACTIVE',armoney_login_notice_eligible:true,armoney_login_event_id:'01234567-89ab-cdef-0123-456789abcdef'],
            [armoney_login_kind:'interactive',armoney_login_notice_eligible:'true',armoney_login_event_id:'01234567-89ab-cdef-0123-456789abcdef'],
            [armoney_login_kind:'interactive',armoney_login_notice_eligible:1,armoney_login_event_id:'01234567-89ab-cdef-0123-456789abcdef'],
            [armoney_login_kind:1,armoney_login_notice_eligible:false],
            [armoney_login_kind:['reuse'],armoney_login_notice_eligible:false],
            [armoney_login_kind:'interactive',armoney_login_notice_eligible:true,armoney_login_event_id:[]],
            [armoney_login_kind:'interactive',armoney_login_notice_eligible:true,armoney_login_event_id:123],
            [armoney_login_kind:'interactive',armoney_login_notice_eligible:true,armoney_login_event_id:'01234567-89AB-CDEF-0123-456789ABCDEF'],
            [armoney_login_kind:'interactive',armoney_login_notice_eligible:true,armoney_login_event_id:'1-1-1-1-1'],
            [armoney_login_kind:'interactive',armoney_login_notice_eligible:true,armoney_login_event_id:' 01234567-89ab-cdef-0123-456789abcdef'],
            [armoney_login_kind:'unknown',armoney_login_notice_eligible:false]
        ]
    }
    def 'mandatory expiry still rejects absent null boolean fraction array and beyond-long values'(){
        given:
        if(present)claims.exp=value
        else claims.remove('exp')
        when:
        provider.verify('synthetic-access-token')
        then:
        def failure=thrown(AuthFailure)
        failure.kind()==AuthFailure.Kind.UNAUTHORIZED
        where:
        present | value
        false | null
        true | null
        true | true
        true | false
        true | 2000000000.5G
        true | [2000000000L]
        true | new BigInteger('9223372036854775808')
    }
}
