package com.arman.bank.authservice

import com.arman.bank.authservice.application.AuthFailure
import com.arman.bank.authservice.infrastructure.HttpRegistrationProfiles
import com.sun.net.httpserver.HttpServer
import groovy.json.JsonSlurper
import spock.lang.Specification
import java.util.concurrent.CopyOnWriteArrayList

class HttpRegistrationProfilesSpec extends Specification {
    static final String KEY = 'synthetic-service-key-at-least-32-characters'
    HttpServer server
    HttpRegistrationProfiles profiles
    int status = 204
    int delay
    List requests = new CopyOnWriteArrayList()

    def setup() {
        server = HttpServer.create(new InetSocketAddress('127.0.0.1', 0), 0)
        server.createContext('/internal/registrations/me') { exchange ->
            requests.add([method:exchange.requestMethod, key:exchange.requestHeaders.getFirst('X-Service-Key'),
                identity:exchange.requestHeaders.getFirst('X-Identity-Id'),
                body:new JsonSlurper().parseText(new String(exchange.requestBody.readAllBytes(), 'UTF-8'))])
            if (delay) Thread.sleep(delay)
            exchange.sendResponseHeaders(status, -1)
            exchange.close()
        }
        server.start()
        profiles = new HttpRegistrationProfiles(URI.create("http://127.0.0.1:${server.address.port}"), KEY)
    }

    def cleanup() { profiles?.close(); server?.stop(0) }

    def 'provisioning sends only the durable identity and immutable number and accepts exact acknowledgement'() {
        given:
        def id = UUID.randomUUID()
        when:
        profiles.provision(id, '+971501234567')
        profiles.provision(id, '+971501234567')
        then:
        requests == (1..2).collect { [method:'PUT', key:KEY, identity:id.toString(), body:[phone_number:'+971501234567']] }
    }

    def 'conflict rejection unavailable and malformed success statuses fail closed'() {
        given:
        status = upstream
        when:
        profiles.provision(UUID.randomUUID(), '+971501234567')
        then:
        def error = thrown(AuthFailure)
        error.kind() == failure
        where:
        upstream | failure
        409      | AuthFailure.Kind.CONFLICT
        401      | AuthFailure.Kind.UNAVAILABLE
        500      | AuthFailure.Kind.UNAVAILABLE
        503      | AuthFailure.Kind.UNAVAILABLE
        200      | AuthFailure.Kind.UNAVAILABLE
        201      | AuthFailure.Kind.UNAVAILABLE
        302      | AuthFailure.Kind.UNAVAILABLE
    }

    def 'lost acknowledgement has a bounded deadline'() {
        given:
        delay = 4000
        when:
        profiles.provision(UUID.randomUUID(), '+971501234567')
        then:
        def error = thrown(AuthFailure)
        error.kind() == AuthFailure.Kind.UNAVAILABLE
        requests.size() == 1
    }

    def 'configuration rejects client-controllable path query or embedded credentials'() {
        when:
        new HttpRegistrationProfiles(URI.create(destination), KEY)
        then:
        thrown(IllegalArgumentException)
        where:
        destination << ['http://user:pass@localhost', 'http://localhost/path', 'http://localhost?target=evil', 'http://localhost#fragment', 'file:///tmp/users']
    }
}
