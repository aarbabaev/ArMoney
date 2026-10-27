package com.arman.bank.userservice

import com.arman.bank.runtime.ServiceRuntime
import com.arman.bank.userservice.application.*
import com.arman.bank.userservice.infrastructure.*
import com.fasterxml.jackson.databind.ObjectMapper
import java.net.http.*
import spock.lang.Specification

/** Synthetic stores establish only the combined HTTP boundary, never PostgreSQL correctness. */
class VerificationProjectionWiringSpec extends Specification {
    static final String BUSINESS = 'b' * 32
    static final String DEDICATED = 'd' * 32
    static final String OWNER = '00000000-0000-4000-8000-000000000001'
    static final String PATH = VerificationProjectionRoutes.PATH
    def profiles = Mock(ProfileStore)
    def projection = Mock(VerificationProjectionStore)
    def runtime
    HttpClient client = HttpClient.newHttpClient()

    def start(boolean enabled) {
        def config = VerificationProjectionConfiguration.from(enabled
            ? [USER_VERIFICATION_PROJECTION_ENABLED:'true', AUTH_TO_USER_VERIFICATION_PROJECTION_KEY:DEDICATED,
               USER_VERIFICATION_PROJECTION_WRITER_ID:'auth', INTERNAL_AUTH_KEY:BUSINESS] : [:])
        def routes = new ProfileRoutes(new ProfileService(profiles), BUSINESS, config, enabled ? projection : null)
        runtime = ServiceRuntime.start('user-wiring-fixture', 0, null, routes::configure)
    }
    def cleanup() { client?.close(); runtime?.close() }

    def send(String path, String method, String body, boolean business, boolean dedicated, boolean owner, boolean chunked = false) {
        def publisher = chunked ? HttpRequest.BodyPublishers.ofInputStream { new ByteArrayInputStream(body.bytes) }
            : HttpRequest.BodyPublishers.ofString(body)
        def request = HttpRequest.newBuilder(URI.create("http://localhost:${runtime.port()}${path}"))
            .timeout(java.time.Duration.ofSeconds(5)).header('Content-Type', 'application/json').method(method, publisher)
        if (business) request.header('X-Service-Key', BUSINESS)
        if (dedicated) request.header('X-ArMoney-Verification-Projection-Key', DEDICATED)
        if (owner) request.header('X-Identity-Id', OWNER)
        client.send(request.build(), HttpResponse.BodyHandlers.ofString())
    }

    def 'enabled wiring accepts only dedicated credential for projection without business owner'() {
        given:
        start(true)
        def command = VerificationProjectionSpec.command(0, 1, '+971501234567', 1, 1)
        def body = new ObjectMapper().writeValueAsString(VerificationProjectionRoutesSpec.body())
        when:
        def accepted = send(PATH, 'POST', body, false, true, false)
        then:
        1 * projection.admit('auth') >> true
        1 * projection.commit('auth', command, { long start -> long elapsed=System.nanoTime()-start; elapsed>=0&&elapsed<4_000_000_000L }) >> command.ack()
        accepted.statusCode() == 200
        when:
        def rejected = send(PATH, 'POST', body, true, false, true)
        then:
        rejected.statusCode() == 401
        0 * projection._
        0 * profiles._
    }

    def 'projection headers do not bypass ordinary routes identity or registration authentication'() {
        given:
        start(true)
        expect:
        send('/v1/users/me', 'GET', '', false, true, true).statusCode() == 401
        send('/v1/users/me', 'GET', '', true, true, false).statusCode() == 401
        send('/internal/registrations/me', 'PUT', '{"phone_number":"+971501234567"}', false, true, true).statusCode() == 401
        send(PATH, 'PUT', '{}', false, true, false).statusCode() == 401
        send(PATH + '/', 'POST', '{}', false, true, false).statusCode() == 401
    }

    def 'body limits include unknown length and existing registration remains available'() {
        given:
        start(true)
        when:
        def registration = send('/internal/registrations/me', 'PUT', '{"phone_number":"+971501234567"}', true, false, true)
        then:
        registration.statusCode() == 204
        1 * profiles.registerPhone(UUID.fromString(OWNER), '+971501234567')
        when:
        def ordinary = send('/v1/users/me', 'PUT', 'x' * 4097, true, true, true, true)
        def oversized = oversizedProjectionSocket()
        then:
        ordinary.statusCode() == 413
        // The absolute guard physically aborts an authenticated oversized body;
        // a response is optional, but peer closure must be observed, never inferred
        // from an arbitrary HttpClient IOException or elapsed time.
        oversized.closed
        oversized.status == null || oversized.status in [400,413]
        oversized.bytes <= 4096
        1 * projection.admit('auth') >> true
        0 * projection.commit(_, _, _)
        0 * profiles._
        when:
        def recovered = send('/internal/registrations/me', 'PUT', '{"phone_number":"+971501234567"}', true, false, true)
        then:
        recovered.statusCode() == 204
        1 * profiles.registerPhone(UUID.fromString(OWNER), '+971501234567')
        0 * projection._
    }

    def 'default off registers no dedicated route and keeps ordinary profile lookup'() {
        given:
        start(false)
        when:
        def absent = send(PATH, 'POST', '{}', true, true, true)
        def profile = send('/v1/users/me', 'GET', '', true, false, true)
        then:
        absent.statusCode() == 404
        profile.statusCode() == 404
        1 * profiles.find(UUID.fromString(OWNER)) >> Optional.empty()
        0 * projection._
    }

    def 'enabled invalid or reused credentials fail before route startup'() {
        when:
        VerificationProjectionConfiguration.from([USER_VERIFICATION_PROJECTION_ENABLED:'true'])
        then:
        thrown(IllegalArgumentException)
        when:
        new ProfileRoutes(new ProfileService(profiles), BUSINESS,
            new VerificationProjectionConfiguration(true, [auth:BUSINESS]), projection)
        then:
        thrown(IllegalArgumentException)
    }

    private Map oversizedProjectionSocket() {
        def socket=new Socket('127.0.0.1',runtime.port())
        socket.soTimeout=6500
        try {
            String header='POST '+PATH+' HTTP/1.1\r\nHost: localhost\r\nContent-Type: application/json\r\nX-ArMoney-Verification-Projection-Key: '+DEDICATED+'\r\nTransfer-Encoding: chunked\r\nConnection: keep-alive\r\n\r\n'
            String chunk=Integer.toHexString(12289)+'\r\n'+('x'*12289)+'\r\n0\r\n\r\n'
            socket.outputStream.write((header+chunk).getBytes('US-ASCII'))
            socket.outputStream.flush()
            def response=new ByteArrayOutputStream()
            boolean closed=false
            try {
                int next
                while((next=socket.inputStream.read())!=-1) {
                    response.write(next)
                    if(response.size()>4096)throw new AssertionError('Oversized rejection response exceeded bound')
                }
                closed=true
            } catch(SocketTimeoutException timeout) {
                throw new AssertionError('Oversized projection peer did not physically close within bound')
            } catch(SocketException reset) {
                // The test has not closed its endpoint; this is a peer-observed reset.
                closed=true
            }
            Integer status=null
            if(response.size()>0) {
                String first=response.toString('US-ASCII').split('\r\n',2)[0]
                if(!(first==~ /HTTP\/1\.1 (400|413)( .*)?/))throw new AssertionError('Unexpected oversized projection response status')
                status=Integer.parseInt(first.split(' ',3)[1])
            }
            [closed:closed,status:status,bytes:response.size()]
        } finally {
            // Runs only AFTER the peer EOF/reset assertion above.
            socket.close()
        }
    }
}
