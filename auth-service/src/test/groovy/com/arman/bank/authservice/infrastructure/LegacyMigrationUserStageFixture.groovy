package com.arman.bank.authservice.infrastructure

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import groovy.transform.PackageScope
import org.testcontainers.containers.PostgreSQLContainer

import java.net.http.*
import java.nio.file.*
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.Flow
import java.util.concurrent.TimeUnit

/** Actual UserMain and HTTP baseline, without User classes or fabricated ACKs.
 * Only root's disposable DinD runner network, without host ports/binds, may enable
 * this fixture. UserMain's listener is its actual default interface listener;
 * loopback URLs here do not restrict that listener. Caller owns the fresh PG.
 */
final class LegacyMigrationUserStageFixture implements AutoCloseable {
    private static final int BODY_LIMIT = 12288
    private final URI endpoint
    private final String readKey = credential()
    private final String stageKey = credential()
    private final String publicationKey = credential()
    private final String decisionKey = credential()
    private final boolean liveCurrent
    private final String currentWriter = 'fixture-auth-current'
    private final String currentKey = credential()
    private final String mutationWriter = 'fixture-user-mutation'
    private final String mutationKey = credential()
    private final List<HttpLegacyMigrationStage> adapters = []
    private final HttpClient client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(2)).followRedirects(HttpClient.Redirect.NEVER).build()
    private Process child
    private Thread outputDrainer
    private volatile boolean closed

    static LegacyMigrationUserStageFixture launch(Path repositoryRoot,
            PostgreSQLContainer<?> userPg, URI authPrimaryOrigin) {
        launchMode(repositoryRoot, userPg, authPrimaryOrigin, false)
    }

    /** Optional genuine UserMain route composition. This alone supplies neither
     * Auth FINAL nor source classification, publication or migration release.
     */
    static LegacyMigrationUserStageFixture launchWithLiveCurrent(Path repositoryRoot,
            PostgreSQLContainer<?> userPg, URI authPrimaryOrigin) {
        launchMode(repositoryRoot, userPg, authPrimaryOrigin, true)
    }

    private static LegacyMigrationUserStageFixture launchMode(Path repositoryRoot,
            PostgreSQLContainer<?> userPg, URI authPrimaryOrigin, boolean liveCurrent) {
        if (System.getProperty('armoney.test.isolated.user.fixture') != 'true')
            throw new IllegalStateException('Root-gated disposable User fixture isolation required')
        if (userPg == null || !userPg.isRunning())
            throw new IllegalArgumentException('Caller-owned fresh running PostgreSQL required')
        if (authPrimaryOrigin == null || authPrimaryOrigin.scheme != 'http' ||
                authPrimaryOrigin.host != '127.0.0.1' || authPrimaryOrigin.port < 1 ||
                authPrimaryOrigin.userInfo != null || authPrimaryOrigin.query != null ||
                authPrimaryOrigin.fragment != null || !(authPrimaryOrigin.path in ['', '/']))
            throw new IllegalArgumentException('Isolated loopback Auth PRIMARY origin required')
        Path lib = repositoryRoot.toAbsolutePath().normalize()
            .resolve('user-service/build/install/user-service/lib')
        if (!Files.isDirectory(lib))
            throw new IllegalStateException('Actual User installDist required; no fallback server')
        int port
        new ServerSocket(0, 0, InetAddress.getByName('127.0.0.1')).withCloseable { port = it.localPort }
        def fixture = new LegacyMigrationUserStageFixture(URI.create('http://127.0.0.1:' + port), liveCurrent)
        try {
            fixture.spawn(lib, userPg, authPrimaryOrigin)
            fixture.awaitReady()
            return fixture
        } catch (Throwable failure) {
            try { fixture.close() } catch (Throwable cleanup) { failure.addSuppressed(cleanup) }
            throw failure
        }
    }

    private LegacyMigrationUserStageFixture(URI endpoint, boolean liveCurrent) {
        this.endpoint = endpoint
        this.liveCurrent = liveCurrent
    }

    URI origin() { endpoint }

    /** Synthetic fixture credentials only. origin is the fixed User child origin;
     * the Auth decision listener uses decisionKey with its caller-owned origin.
     * A settings value/ACK is not publication or banking authority.
     */
    @PackageScope SyntheticPublicationSettings syntheticPublicationSettings() {
        if (System.getProperty('armoney.test.isolated.user.fixture') != 'true')
            throw new IllegalStateException('Root-gated disposable User fixture isolation required')
        requireOpen()
        new SyntheticPublicationSettings(publicationKey, decisionKey, endpoint)
    }

    @PackageScope
    static record SyntheticPublicationSettings(String publicationKey, String decisionKey, URI origin) {
        @Override String toString() { 'SyntheticPublicationSettings[credentials=redacted]' }
    }

    /** Fixture-owned purpose credentials only. Access never enables or mutates a
     * route, releases a baseline, or substitutes for a genuine Auth read/decision.
     */
    @PackageScope SyntheticCurrentSettings syntheticCurrentSettings() {
        if (System.getProperty('armoney.test.isolated.user.fixture') != 'true')
            throw new IllegalStateException('Root-gated disposable User fixture isolation required')
        requireOpen()
        if (!liveCurrent) throw new IllegalStateException('Live current fixture was not selected')
        new SyntheticCurrentSettings(currentWriter, currentKey, mutationWriter, mutationKey, endpoint)
    }

    @PackageScope
    static record SyntheticCurrentSettings(String currentWriter, String currentKey,
            String mutationWriter, String mutationKey, URI origin) {
        @Override String toString() { 'SyntheticCurrentSettings[credentials=redacted]' }
    }

    private static String credential() {
        byte[] bytes = new byte[32]
        new SecureRandom().nextBytes(bytes)
        Base64.urlEncoder.withoutPadding().encodeToString(bytes)
    }

    private void spawn(Path lib, PostgreSQLContainer<?> pg, URI authOrigin) {
        String executable = System.getProperty('os.name').toLowerCase(Locale.ROOT).contains('win') ? 'java.exe' : 'java'
        String java = Path.of(System.getProperty('java.home'), 'bin', executable).toString()
        def builder = new ProcessBuilder(java, '-Xms32m', '-Xmx256m', '-cp',
            lib.toString() + File.separator + '*', 'com.arman.bank.userservice.Main')
        def env = builder.environment()
        def essential = new LinkedHashMap<String, String>()
        env.each { name, value ->
            if (name.toUpperCase(Locale.ROOT) in ['SYSTEMROOT', 'WINDIR', 'TEMP', 'TMP', 'PATH'])
                essential.put(name, value)
        }
        env.clear()
        env.putAll(essential)
        env.putAll([
            DB_URL: pg.jdbcUrl, DB_USER: pg.username, DB_PASSWORD: pg.password,
            PORT: endpoint.port.toString(), INTERNAL_AUTH_KEY: readKey,
            USER_MIGRATION_BASELINE_ENABLED: 'true', USER_MIGRATION_WRITER_ID: 'migration-qa',
            AUTH_TO_USER_MIGRATION_STAGE_KEY: stageKey,
            AUTH_TO_USER_MIGRATION_PUBLICATION_KEY: publicationKey,
            USER_TO_AUTH_MIGRATION_DECISION_KEY: decisionKey,
            AUTH_MIGRATION_PRIMARY_ORIGIN: authOrigin.toString()
        ])
        // Default launch remains OFF, with no live purpose credentials in child env.
        env.put('USER_LIVE_REVISION_ENABLED', liveCurrent ? 'true' : 'false')
        if (liveCurrent) env.putAll([
            USER_LIVE_CURRENT_WRITER_ID: currentWriter,
            AUTH_TO_USER_MIGRATION_USER_CURRENT_KEY: currentKey,
            USER_LIVE_MUTATION_WRITER_ID: mutationWriter,
            USER_TO_AUTH_MIGRATION_USER_MUTATION_KEY: mutationKey
        ])
        child = builder.redirectErrorStream(true).start()
        // Drain without retaining or exposing potentially sensitive process output.
        // Memory remains bounded regardless of the amount emitted by the child.
        outputDrainer = new Thread({
            try {
                child.inputStream.withCloseable { stream ->
                    byte[] chunk = new byte[4096]
                    while (stream.read(chunk) != -1) { /* discard private child output */ }
                }
            } catch (IOException ignored) { }
        } as Runnable, 'legacy-migration-user-output')
        outputDrainer.daemon = true
        outputDrainer.start()
    }

    private void awaitReady() {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
        while (child.isAlive() && System.nanoTime() < deadline) {
            def pending = null
            try {
                def request = HttpRequest.newBuilder(endpoint.resolve('/health/ready'))
                    .timeout(Duration.ofSeconds(2)).GET().build()
                pending = client.sendAsync(request, HttpResponse.BodyHandlers.discarding())
                if (pending.get(2, TimeUnit.SECONDS).statusCode() == 200 && child.isAlive()) return
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt()
                throw new IllegalStateException('User fixture startup interrupted')
            } catch (Exception ignored) { }
            finally { pending?.cancel(true) }
            Thread.sleep(25)
        }
        throw new IllegalStateException('Actual isolated UserMain failed readiness; child output suppressed')
    }

    /** Creates only an existing-owner legacy profile through authenticated User HTTP.
     * Does not create verification projection, Auth receipt, challenge or accepted OTP.
     */
    Map bootstrap(UUID ownerId, String canonicalPhone) {
        requireOpen()
        if (ownerId == null || ownerId == new UUID(0, 0) || canonicalPhone == null ||
                !(canonicalPhone ==~ /\+9715[024568][0-9]{7}/))
            throw new IllegalArgumentException('Synthetic owner and canonical UAE phone required')
        call('PUT', '/internal/registrations/me', ownerId, [phone_number: canonicalPhone], 204)
        Map saved = call('PUT', '/v1/users/me', ownerId, [display_name: 'Migration fixture'], 200)
        Map actual = call('GET', '/v1/users/me', ownerId, null, 200)
        if (saved != actual || actual.keySet() !=
                (['id', 'identity_id', 'display_name', 'phone_number', 'phone_verified'] as Set) ||
                actual.identity_id != ownerId.toString() || actual.phone_number != canonicalPhone ||
                actual.phone_verified != false || actual.display_name != 'Migration fixture')
            throw new IllegalStateException('Actual User legacy profile baseline mismatch')
        UUID profile
        try { profile = UUID.fromString(actual.id as String) }
        catch (RuntimeException ignored) { throw new IllegalStateException('Actual User profile UUID missing') }
        if (profile == new UUID(0, 0) || profile.toString() != actual.id)
            throw new IllegalStateException('Actual User profile UUID noncanonical')
        Collections.unmodifiableMap(new LinkedHashMap(actual))
    }

    synchronized HttpLegacyMigrationStage stagePort() {
        requireOpen()
        def adapter = new HttpLegacyMigrationStage(endpoint, readKey, stageKey)
        adapters.add(adapter)
        adapter
    }

    private Map call(String method, String path, UUID owner, Map body, int expectedStatus) {
        def request = HttpRequest.newBuilder(endpoint.resolve(path)).timeout(Duration.ofSeconds(3))
            .header('X-Service-Key', readKey).header('X-Identity-Id', owner.toString())
        if (body == null) request.GET()
        else request.header('Content-Type', 'application/json').method(method,
            HttpRequest.BodyPublishers.ofString(JsonOutput.toJson(body)))
        // Bound both body completion time and retained bytes, including stalled bodies.
        def future = client.sendAsync(request.build(), { info -> new BoundedBody() } as HttpResponse.BodyHandler<byte[]>)
        HttpResponse<byte[]> response
        try { response = future.get(3, TimeUnit.SECONDS) }
        catch (InterruptedException interrupted) {
            future.cancel(true)
            Thread.currentThread().interrupt()
            throw new IllegalStateException('User fixture HTTP operation interrupted')
        }
        catch (Exception ignored) {
            future.cancel(true)
            throw new IllegalStateException('User fixture HTTP operation unavailable: ' + method + ' ' + path)
        }
        byte[] bytes = response.body()
        if (response.statusCode() != expectedStatus || bytes.length > BODY_LIMIT)
            throw new IllegalStateException('User fixture HTTP operation rejected: ' + method + ' ' + path + ' status=' + response.statusCode())
        if (expectedStatus == 204) {
            if (bytes.length != 0) throw new IllegalStateException('Unexpected User registration response body')
            return [:]
        }
        Object parsed
        try { parsed = new JsonSlurper().parse(bytes, 'UTF-8') }
        catch (RuntimeException ignored) { throw new IllegalStateException('Malformed User fixture HTTP response') }
        if (!(parsed instanceof Map)) throw new IllegalStateException('Unexpected User fixture HTTP response shape')
        (Map) parsed
    }

    private void requireOpen() {
        if (closed || child == null || !child.isAlive())
            throw new IllegalStateException('Actual User fixture unavailable')
    }

    @Override synchronized void close() {
        if (closed) return
        closed = true
        try {
            adapters.each { it.close() }
        } finally {
            try {
                if (child != null) {
                    child.destroy()
                    if (!child.waitFor(8, TimeUnit.SECONDS)) {
                        child.destroyForcibly()
                        if (!child.waitFor(5, TimeUnit.SECONDS))
                            throw new IllegalStateException('Owned User fixture child did not terminate')
                    }
                }
            } finally {
                client.shutdownNow()
                if (!client.awaitTermination(Duration.ofSeconds(3)))
                    throw new IllegalStateException('Owned User HTTP client did not terminate')
                if (outputDrainer != null) {
                    outputDrainer.join(2000)
                    if (outputDrainer.isAlive()) throw new IllegalStateException('Owned User output drain did not terminate')
                }
            }
        }
    }

    private static final class BoundedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> result = new CompletableFuture<>()
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream()
        private Flow.Subscription subscription

        @Override CompletionStage<byte[]> getBody() { result }
        @Override void onSubscribe(Flow.Subscription next) {
            if (subscription != null) { next.cancel(); return }
            subscription = next
            next.request(1)
        }
        @Override void onNext(List<ByteBuffer> items) {
            try {
                for (ByteBuffer item : items) {
                    if (item.remaining() > BODY_LIMIT - bytes.size()) {
                        subscription.cancel()
                        result.completeExceptionally(new IllegalStateException('User response size exceeded'))
                        return
                    }
                    byte[] part = new byte[item.remaining()]
                    item.get(part)
                    bytes.writeBytes(part)
                }
                subscription.request(1)
            } catch (RuntimeException ignored) {
                subscription.cancel()
                result.completeExceptionally(new IllegalStateException('User response unavailable'))
            }
        }
        @Override void onError(Throwable ignored) {
            result.completeExceptionally(new IllegalStateException('User response unavailable'))
        }
        @Override void onComplete() { result.complete(bytes.toByteArray()) }
    }

    @Override String toString() { 'LegacyMigrationUserStageFixture[credentials=redacted]' }
}
