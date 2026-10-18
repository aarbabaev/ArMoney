package com.arman.bank.appgateway

import com.arman.bank.runtime.ServiceRuntime
import com.sun.net.httpserver.HttpServer
import java.net.http.*
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.*
import spock.lang.Specification

/** Synthetic HTTP transport evidence only; does not establish browser proof or TLS ingress. */
class MigrationBrowserProxySpec extends Specification {
    private static MigrationBrowserProxy isolatedProxy() {
        new MigrationBrowserProxy(MigrationBrowserConfiguration.from([
            GATEWAY_MIGRATION_BROWSER_ENABLED:'true', MIGRATION_PUBLIC_ORIGIN:'https://bank.example',
            MIGRATION_EDGE_TO_GATEWAY_KEY:'e' * 43, MIGRATION_GATEWAY_TO_AUTH_KEY:'a' * 43,
            MIGRATION_LEGACY_AUTHORIZATION_ENDPOINT:'https://legacy.example/authorize', MIGRATION_LEGACY_CLIENT_ID:'legacy',
            MIGRATION_MANAGED_AUTHORIZATION_ENDPOINT:'https://managed.example/authorize', MIGRATION_MANAGED_CLIENT_ID:'managed'],
            URI.create('http://localhost:1')))
    }

    /** Emits one chunk, then ignores interruption until its owned stream is closed. */
    private static class SlowChunkedInput extends InputStream {
        final CountDownLatch blocked = new CountDownLatch(1)
        final CountDownLatch released = new CountDownLatch(1)
        final CountDownLatch stopped = new CountDownLatch(1)
        final java.util.concurrent.atomic.AtomicBoolean first = new java.util.concurrent.atomic.AtomicBoolean(true)
        int read(byte[] bytes, int offset, int length) {
            if (first.compareAndSet(true, false)) { bytes[offset] = (byte) 120; return 1 }
            blocked.countDown()
            while (released.count > 0) {
                try { released.await() } catch (InterruptedException ignored) { }
            }
            stopped.countDown()
            throw new IOException('synthetic input closed')
        }
        int read() { byte[] one = new byte[1]; read(one,0,1); return one[0] }
        void close() { released.countDown() }
    }

    def 'slow chunked input ignoring interrupts is closed and joined when the original budget expires'() {
        given:
        def proxy = isolatedProxy()
        def input = new SlowChunkedInput()
        long started = System.nanoTime()

        when:
        proxy.readBody(input,8192,started + TimeUnit.SECONDS.toNanos(1))
        then:
        thrown(TimeoutException)
        input.blocked.count == 0
        input.released.count == 0
        input.stopped.count == 0
        System.nanoTime() - started < TimeUnit.SECONDS.toNanos(2)

        cleanup:
        input.close()
        proxy.close()
    }

    def 'shutdown closes and joins an admitted uninterruptible reader within its termination bound'() {
        given:
        def proxy = isolatedProxy()
        def input = new SlowChunkedInput()
        def executor = Executors.newSingleThreadExecutor()
        def read = executor.submit({ -> proxy.readBody(input,8192,System.nanoTime() + TimeUnit.SECONDS.toNanos(5)) } as Callable)
        assert input.blocked.await(1,TimeUnit.SECONDS)

        when:
        long started = System.nanoTime()
        proxy.close()
        then:
        input.stopped.count == 0
        input.released.count == 0
        System.nanoTime() - started < TimeUnit.SECONDS.toNanos(3)

        when:
        read.get(1,TimeUnit.SECONDS)
        then:
        thrown(ExecutionException)

        cleanup:
        input.close()
        executor.shutdownNow()
        assert executor.awaitTermination(1,TimeUnit.SECONDS)
        proxy.close()
    }

    def 'disconnected and oversized synthetic chunked inputs close before admission returns'() {
        given:
        def proxy = isolatedProxy()
        def closed = new java.util.concurrent.atomic.AtomicBoolean()
        def input = new InputStream() {
            int read() { throw new IOException('synthetic disconnect') }
            void close() { closed.set(true) }
        }

        when:
        proxy.readBody(input,8192,System.nanoTime() + TimeUnit.SECONDS.toNanos(1))
        then:
        thrown(ExecutionException)
        closed.get()

        when:
        proxy.readBody(new ByteArrayInputStream(new byte[8193]),8192,System.nanoTime() + TimeUnit.SECONDS.toNanos(1))
        then:
        thrown(ExecutionException)

        cleanup:
        proxy.close()
    }
    def 'dedicated ingress preserves raw query and cookies while discarding caller assertions'() {
        given:
        def captured = new AtomicReference()
        def backend = HttpServer.create(new InetSocketAddress('127.0.0.1', 0), 0)
        backend.createContext('/migration/') { ex ->
            captured.set([query:ex.requestURI.rawQuery, cookies:ex.requestHeaders.get('Cookie'),
                edge:ex.requestHeaders.getFirst('X-ArMoney-Migration-Edge-Key'),
                key:ex.requestHeaders.getFirst('X-ArMoney-Migration-Auth-Key'),
                identity:ex.requestHeaders.getFirst('X-Identity-Id'), forwarded:ex.requestHeaders.getFirst('Forwarded'),
                service:ex.requestHeaders.getFirst('X-Service-Key')])
            ['Cache-Control':'no-store', 'Referrer-Policy':'no-referrer', 'X-Content-Type-Options':'nosniff',
             'Content-Security-Policy':"default-src 'none'; form-action 'self'; base-uri 'none'; frame-ancestors 'none'",
             'Content-Type':'text/html; charset=UTF-8'].each { name, value -> ex.responseHeaders.add(name,value) }
            ex.responseHeaders.add('Set-Cookie','__Secure-ArMoneyMigrationAttempt=; Path=/migration/; Secure; HttpOnly; SameSite=Lax; Max-Age=0')
            byte[] data = '<html>synthetic</html>'.bytes
            ex.sendResponseHeaders(200,data.length)
            ex.responseBody.withCloseable { it.write(data) }
        }
        backend.start()
        def settings = MigrationBrowserConfiguration.from([GATEWAY_MIGRATION_BROWSER_ENABLED:'true',
            MIGRATION_PUBLIC_ORIGIN:'https://bank.example', MIGRATION_LEGACY_AUTHORIZATION_ENDPOINT:'https://legacy.example/authorize', MIGRATION_LEGACY_CLIENT_ID:'legacy', MIGRATION_MANAGED_AUTHORIZATION_ENDPOINT:'https://managed.example/authorize', MIGRATION_MANAGED_CLIENT_ID:'managed', MIGRATION_EDGE_TO_GATEWAY_KEY:'e' * 43,
            MIGRATION_GATEWAY_TO_AUTH_KEY:'a' * 43], URI.create("http://localhost:${backend.address.port}"))
        def proxy = new MigrationBrowserProxy(settings)
        def runtime = ServiceRuntime.start('app-gateway',0,null,proxy::configure)
        def client = HttpClient.newHttpClient()
        def uri = URI.create("http://localhost:${runtime.port()}/migration/legacy/account-oidc/callback?state=a%2Bb&state=duplicate&code=x")
        def request = { -> HttpRequest.newBuilder(uri).header('X-ArMoney-Migration-Edge-Key','e' * 43)
            .header('X-ArMoney-Migration-Origin','https://bank.example') }

        when:
        def result = client.send(request().header('Cookie','attempt=one; attempt=two')
            .header('X-ArMoney-Migration-Auth-Key','forged').header('X-Identity-Id','victim')
            .header('Forwarded','proto=https').header('X-Service-Key','forged').build(), HttpResponse.BodyHandlers.ofString())
        then:
        result.statusCode() == 200
        captured.get().query == 'state=a%2Bb&state=duplicate&code=x'
        captured.get().cookies.join('; ') == 'attempt=one; attempt=two'
        captured.get().key == 'a' * 43
        captured.get().edge == null
        captured.get().identity == null
        captured.get().forwarded == null
        captured.get().service == null
        result.headers().allValues('Set-Cookie').size() == 1

        when:
        captured.set(null)
        def denied = client.send(request().header('X-ArMoney-Migration-Edge-Key','e' * 43).build(), HttpResponse.BodyHandlers.ofString())
        then:
        denied.statusCode() == 403
        captured.get() == null

        when:
        def direct = client.send(HttpRequest.newBuilder(uri).header('Forwarded','proto=https').build(), HttpResponse.BodyHandlers.ofString())
        then:
        direct.statusCode() == 403

        when:
        def continuationPaths = ['/migration/legacy/verify/stage','/migration/legacy/otp/issue','/migration/legacy/otp/consume',
            '/migration/legacy/verify/decision','/migration/legacy/verify/publication','/migration/legacy/verify/final-command','/migration/legacy/verify/final']
        def forwarded = continuationPaths.collect { path ->
            captured.set(null)
            def continuation = HttpRequest.newBuilder(URI.create("http://localhost:${runtime.port()}" + path))
                .header('X-ArMoney-Migration-Edge-Key','e' * 43).header('X-ArMoney-Migration-Origin','https://bank.example')
                .header('Origin','https://bank.example').header('Content-Type','application/x-www-form-urlencoded')
                .header('Cookie','__Secure-ArMoneyMigrationAccount=' + ('b' * 43))
                .POST(HttpRequest.BodyPublishers.ofString('csrf=synthetic&command_id=synthetic')).build()
            [status:client.send(continuation,HttpResponse.BodyHandlers.ofString()).statusCode(),headers:captured.get()]
        }
        then:
        forwarded.size() == 7
        forwarded.every { it.status == 200 && it.headers.key == 'a' * 43 && it.headers.edge == null && it.headers.identity == null }

        when:
        def statusUri = URI.create("http://localhost:${runtime.port()}/migration/legacy/verify/status")
        def statusRequest = HttpRequest.newBuilder(statusUri).header('X-ArMoney-Migration-Edge-Key','e' * 43)
            .header('X-ArMoney-Migration-Origin','https://bank.example').GET().build()
        def readback = client.send(statusRequest,HttpResponse.BodyHandlers.ofString())
        def wrongMethods = continuationPaths.collect { path ->
            client.send(HttpRequest.newBuilder(URI.create("http://localhost:${runtime.port()}" + path)).GET().build(),HttpResponse.BodyHandlers.ofString()).statusCode()
        }
        def unknown = client.send(HttpRequest.newBuilder(URI.create("http://localhost:${runtime.port()}/migration/legacy/verify/unknown")).GET().build(),HttpResponse.BodyHandlers.ofString())
        then:
        readback.statusCode() == 200
        wrongMethods.every { it == 404 }
        unknown.statusCode() == 404

        cleanup:
        client?.close()
        runtime?.close()
        proxy?.close()
        backend?.stop(0)
    }
}
