package com.arman.bank.runtime

import io.javalin.Javalin
import io.javalin.http.UnauthorizedResponse
import spock.lang.Specification
import spock.lang.Unroll
import java.net.http.*

class InternalHttpDedicatedRouteSpec extends Specification {
    static final String KEY = 'b' * 32
    static final String DEDICATED = 'd' * 32
    static final String PATH = '/v1/internal/fixture'
    static final String OWNER = '00000000-0000-4000-8000-000000000001'

    def app
    HttpClient client

    def setup() {
        client = HttpClient.newHttpClient()
        app = Javalin.create { config ->
            InternalHttp.configure(config, KEY, [new InternalHttp.DedicatedRoute('POST', PATH)])
            config.routes.post(PATH) { ctx ->
                if (ctx.header('X-Fixture-Key') != DEDICATED) throw new UnauthorizedResponse()
                def bytes = ctx.req().inputStream.readNBytes(12289)
                if (bytes.length > 12288) { ctx.status(413); return }
                ctx.result(bytes.length.toString())
            }
            config.routes.post('/v1/ordinary') { ctx -> ctx.result(ctx.bodyAsBytes().length.toString()) }
        }.start(0)
    }

    def cleanup() { client?.close(); app?.stop() }

    def send(String path, String method, int size, boolean business, boolean dedicated, boolean owner, boolean chunked = false) {
        byte[] bytes = new byte[size]
        def publisher = chunked ? HttpRequest.BodyPublishers.ofInputStream { new ByteArrayInputStream(bytes) } : HttpRequest.BodyPublishers.ofByteArray(bytes)
        def request = HttpRequest.newBuilder(URI.create("http://localhost:${app.port()}${path}"))
            .method(method, publisher)
        if (business) request.header('X-Service-Key', KEY)
        if (dedicated) request.header('X-Fixture-Key', DEDICATED)
        if (owner) request.header('X-Identity-Id', OWNER)
        client.send(request.build(), HttpResponse.BodyHandlers.ofString())
    }

    def 'dedicated authentication remains in handler and ordinary identity is mandatory'() {
        expect:
        send(PATH, 'POST', 0, false, true, false).statusCode() == 200
        send(PATH, 'POST', 0, true, false, true).statusCode() == 401
        send('/v1/ordinary', 'POST', 0, false, true, true).statusCode() == 401
        send('/v1/ordinary', 'POST', 0, true, true, false).statusCode() == 401
        send('/v1/ordinary', 'POST', 0, true, false, true).statusCode() == 200
    }

    @Unroll
    def 'dedicated headers do not exempt variant #method #path'(String method, String path) {
        expect:
        send(path, method, 0, false, true, false).statusCode() != 200
        send(path, method, 0, false, true, false).statusCode() in [400, 401, 404, 405]
        where:
        method | path
        'GET' | PATH
        'PUT' | PATH
        'POST' | PATH + '/'
        'POST' | PATH + '/extra'
        'POST' | '/v1/internal/%66ixture'
        'POST' | '/v1/internal/fixture%2f'
    }

    @Unroll
    def 'body boundaries remain separate including chunked=#chunked'(boolean chunked) {
        expect:
        send('/v1/ordinary', 'POST', 4096, true, false, true, chunked).statusCode() == 200
        send('/v1/ordinary', 'POST', 4097, true, false, true, chunked).statusCode() == 413
        send(PATH, 'POST', 12288, false, true, false, chunked).statusCode() == 200
        send(PATH, 'POST', 12289, false, true, false, chunked).statusCode() == 413
        where:
        chunked << [false, true]
    }

    @Unroll
    def 'invalid server configuration fails closed #method #path'(String method, String path) {
        when:
        new InternalHttp.DedicatedRoute(method, path)
        then:
        thrown(IllegalArgumentException)
        where:
        method | path
        null | PATH
        'post' | PATH
        'POST' | null
        'POST' | '/v1/*'
        'POST' | PATH + '/'
        'POST' | '/v1/internal/%66ixture'
    }

    def 'legacy configure retains service key owner and four KiB limit'() {
        given:
        def legacy = Javalin.create { config ->
            InternalHttp.configure(config, KEY)
            config.routes.post(PATH) { ctx -> ctx.result(ctx.body()) }
        }.start(0)
        def uri = URI.create("http://localhost:${legacy.port()}${PATH}")
        expect:
        client.send(HttpRequest.newBuilder(uri).header('X-Fixture-Key', DEDICATED).POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString()).statusCode() == 401
        client.send(HttpRequest.newBuilder(uri).header('X-Service-Key', KEY).header('X-Identity-Id', OWNER).POST(HttpRequest.BodyPublishers.ofString('x' * 4097)).build(), HttpResponse.BodyHandlers.ofString()).statusCode() == 413
        cleanup:
        legacy?.stop()
    }
}
