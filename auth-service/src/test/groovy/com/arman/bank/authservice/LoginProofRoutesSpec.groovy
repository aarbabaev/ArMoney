package com.arman.bank.authservice

import com.arman.bank.authservice.application.LoginProofStore
import com.arman.bank.authservice.domain.LoginProof.*
import com.arman.bank.authservice.domain.LoginProof.Error
import com.arman.bank.authservice.infrastructure.*
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import io.javalin.Javalin
import java.net.http.*
import java.time.*
import java.util.concurrent.*
import spock.lang.Specification

/** Synthetic loopback ports only; real database invariants are independently tested in IntegrationSpec. */
class LoginProofRoutesSpec extends Specification {
    static final class UnreadBody extends InputStream {
        int reads
        @Override int read(){reads++;throw new AssertionError('body must not be read')}
    }
    static final String WRITE = 'synthetic-proof-write-key-32-characters'
    static final String RESOLVE = 'synthetic-proof-resolve-key-32-characters'
    static final String ISSUER = 'https://issuer.example/realm'
    LoginProofStore store = Mock()
    LoginProofStore.Request scoped = Mock()
    def policy = Policy.defaults(Set.of(ISSUER), Set.of('armoney-web'), Set.of('current'), Set.of('provider'))
    Javalin app
    def client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()
    def setup() { app = Javalin.create { new LoginProofRoutes(store, policy, 'provider', WRITE, RESOLVE).configure(it) }.start('127.0.0.1', 0) }
    def cleanup() { app.stop(); client.close() }
    def post(String body, String key = WRITE, boolean resolve = false, Map headers = [:], String query = '') {
        def request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:${app.port()}/internal/sso-login-proofs" + (resolve ? '/resolve' : '') + query))
            .timeout(Duration.ofSeconds(8)).header('Content-Type', 'application/json')
        if (key != null) request.header(resolve ? 'X-ArMoney-Login-Proof-Resolve-Key' : 'X-ArMoney-Login-Proof-Write-Key', key)
        headers.each { name, value -> request.header(name, value) }
        client.send(request.POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString())
    }
    static Map lookup() { [code_digest: 'a' * 64, code_key_id: 'current', issuer: ISSUER, subject: 'subject', client_id: 'armoney-web', user_session_id: 'session'] }
    static Map command() { lookup() + [command_id: UUID.randomUUID().toString(), authentication_session_id: 'authentication', issued_at: '2026-10-15T12:00:00Z', expires_at: '2026-10-15T12:01:00Z', kind: 'reuse', event_id: null, password_authenticated_at: null] }
    static Snapshot snapshot() { new Snapshot(UUID.randomUUID(), 'current', null, Kind.REUSE, false, Instant.parse('2026-10-15T12:00:00Z'), Instant.parse('2026-10-15T12:01:00Z')) }
    static Map environment() {
        [AUTH_LOGIN_PROOF_BRIDGE_ENABLED: 'true', SSO_LOGIN_PROOF_WRITER_ID: 'provider', SSO_LOGIN_PROOF_WRITE_KEY: WRITE,
         SSO_LOGIN_PROOF_RESOLVE_KEY: RESOLVE, SSO_LOGIN_PROOF_RETENTION_KEY: Base64.encoder.encodeToString(([33] * 32) as byte[]),
         SSO_ISSUER: ISSUER, SSO_LOGIN_PROOF_CLIENT_IDS: 'armoney-web', SSO_LOGIN_PROOF_CODE_KEY_IDS: 'current']
    }
    static LoginProofBodyDeadline deadline(org.eclipse.jetty.util.thread.Scheduler scheduler,org.eclipse.jetty.io.EndPoint endpoint,long started=System.nanoTime()){
        def constructor=LoginProofBodyDeadline.getDeclaredConstructor(org.eclipse.jetty.util.thread.Scheduler,org.eclipse.jetty.io.EndPoint,Long.TYPE)
        constructor.accessible=true
        constructor.newInstance(scheduler,endpoint,started)
    }
    def 'body adapter rejects non HTTP1 and unknown servlet adapters without reading'(){
        given:
        def request=Mock(jakarta.servlet.http.HttpServletRequest)
        request.getProtocol() >> protocol
        request.getAttribute(_) >> null
        when:LoginProofBodyDeadline.begin(request)
        then:
        def denied=thrown(Failure);denied.error()==expected
        0 * request.getInputStream()
        where:
        protocol | expected
        'HTTP/2.0' | Error.BAD_INPUT
        'HTTP/1.1' | Error.UNAVAILABLE
    }
    def 'stopped or rejecting managed scheduler never begins inbound reading'(){
        given:
        def scheduler=Mock(org.eclipse.jetty.util.thread.Scheduler)
        def endpoint=Mock(org.eclipse.jetty.io.EndPoint)
        def input=new UnreadBody()
        endpoint.isOpen() >> true
        scheduler.isRunning() >>> [true,mode!='stopped']
        scheduler.schedule(_,_,_) >> {throw new RejectedExecutionException('synthetic stopped scheduler')}
        def guard=deadline(scheduler,endpoint)
        when:guard.read(input,12289)
        then:
        def denied=thrown(Failure);denied.error()==Error.UNAVAILABLE
        input.reads==0
        0 * endpoint.close(_)
        cleanup:guard?.close()
        where:mode << ['stopped','rejecting']
    }
    def 'successful body completion cancels managed task and tardy timer cannot close keepalive'(){
        given:
        def scheduler=Mock(org.eclipse.jetty.util.thread.Scheduler)
        def endpoint=Mock(org.eclipse.jetty.io.EndPoint)
        def task=Mock(org.eclipse.jetty.util.thread.Scheduler.Task)
        Runnable timer
        scheduler.isRunning() >> true;endpoint.isOpen() >> true
        scheduler.schedule(_,_,_) >> {Runnable action,long delay,TimeUnit unit->assert delay>0;timer=action;task}
        def guard=deadline(scheduler,endpoint)
        when:
        def result=guard.read(new ByteArrayInputStream('{}'.bytes),12289)
        timer.run();guard.close();timer.run()
        then:
        new String(result)=='{}'
        1 * task.cancel() >> true
        0 * endpoint.close(_)
    }
    def 'deadline reached before body reading physically aborts without scheduling or consuming input'(){
        given:
        def scheduler=Mock(org.eclipse.jetty.util.thread.Scheduler)
        def endpoint=Mock(org.eclipse.jetty.io.EndPoint)
        def input=new UnreadBody()
        scheduler.isRunning() >> true;endpoint.isOpen() >> true
        def guard=deadline(scheduler,endpoint,System.nanoTime()-TimeUnit.SECONDS.toNanos(4))
        when:guard.read(input,12289)
        then:
        def denied=thrown(Failure);denied.error()==Error.BAD_INPUT
        1 * endpoint.close({it instanceof TimeoutException && it.message=='login_proof_body_deadline'})
        0 * scheduler.schedule(_,_,_)
        input.reads==0
        cleanup:guard?.close()
    }
    def 'body completing after elapsed deadline is rejected even if scheduler callback was delayed'(){
        given:
        def scheduler=Mock(org.eclipse.jetty.util.thread.Scheduler)
        def endpoint=Mock(org.eclipse.jetty.io.EndPoint)
        def task=Mock(org.eclipse.jetty.util.thread.Scheduler.Task)
        scheduler.isRunning() >> true;endpoint.isOpen() >> true
        scheduler.schedule(_,_,_) >> task
        def guard=deadline(scheduler,endpoint,System.nanoTime()-TimeUnit.MILLISECONDS.toNanos(3900))
        def input=new InputStream(){int read(){Thread.sleep(150);-1}}
        when:guard.read(input,12289)
        then:
        def denied=thrown(Failure);denied.error()==Error.BAD_INPUT
        1 * endpoint.close(_)
        1 * task.cancel() >> true
        cleanup:guard?.close()
    }
    def 'directional credentials authenticate before parsing or consuming admission'() {
        given:
        0 * store._
        expect:
        post('invalid', RESOLVE).statusCode() == 401
        post('invalid', WRITE, true).statusCode() == 401
        post('invalid', null, false, ['Authorization': 'Bearer synthetic-token', 'X-Service-Key': WRITE, 'X-Identity-Id': UUID.randomUUID().toString()]).statusCode() == 401
        post('invalid', WRITE, false, ['X-ArMoney-Login-Proof-Write-Key': WRITE]).statusCode() == 401
    }
    def 'strict malformed bodies are admitted once but never reach persistence commands'() {
        when:
        def response = post(body)
        then:
        1 * store.inRequest('provider', _) >> { String actor, java.util.function.Function operation -> operation.apply(scoped) }
        0 * scoped.write(_)
        0 * scoped.resolve(_)
        response.statusCode() == 400
        response.headers().firstValue('Cache-Control').get() == 'no-store'
        response.body() == '{"error":"login_proof_request_rejected"}'
        where:
        body << ['{}', '{"issuer":"one","issuer":"two"}', JsonOutput.toJson(command()) + ' {}', 'x' * 12289,
            JsonOutput.toJson(command() + [identity_id: UUID.randomUUID().toString()]),
            JsonOutput.toJson(command() + [event_id: 1]), JsonOutput.toJson(command() + [password_authenticated_at: false]),
            JsonOutput.toJson(command() + [issued_at: '2026-10-15T12:00:00.000Z']),
            JsonOutput.toJson(command() + [issued_at: '2026-10-15T12:00:00+00:00']),
            JsonOutput.toJson(command() + [issued_at: '2026-10-15T12:00:60Z']),
            JsonOutput.toJson(command() + [command_id: '1-1-1-1-1']), JsonOutput.toJson(command() + [subject: 'bad\u2003binding'])]
    }
    def 'configured writer identity ignores caller headers and acknowledgement exposes no binding or digest'() {
        given:
        def result = snapshot()
        when:
        def response = post(JsonOutput.toJson(command()), WRITE, false, ['X-Identity-Id': UUID.randomUUID().toString(), 'X-Service-Key': 'untrusted'])
        then:
        1 * store.inRequest('provider', _) >> { String actor, java.util.function.Function operation -> operation.apply(scoped) }
        then:
        1 * scoped.write({ it.kind() == Kind.REUSE && it.eventId() == null }) >> result
        response.statusCode() == 200
        def body = new JsonSlurper().parseText(response.body())
        body.keySet() == ['proof_id','event_id','kind','login_notice_eligible','issued_at','expires_at','code_key_id','state'] as Set
        body.event_id == null && body.login_notice_eligible == false && body.state == 'RECORDED'
        !response.body().contains('subject') && !response.body().contains('a' * 64)
    }
    def 'resolve is admitted through the same writer and never calls write'() {
        when:
        def response = post(JsonOutput.toJson(lookup()), RESOLVE, true)
        then:
        1 * store.inRequest('provider', _) >> { String actor, java.util.function.Function operation -> operation.apply(scoped) }
        1 * scoped.resolve(_) >> snapshot()
        0 * scoped.write(_)
        response.statusCode() == 200
        new JsonSlurper().parseText(response.body()).keySet() == ['proof_id','event_id','kind','login_notice_eligible','issued_at','expires_at'] as Set
    }
    def 'budget failure denies before parsing and never becomes proof success'() {
        when:
        def response = post('not-json')
        then:
        1 * store.inRequest('provider', _) >> { throw new Failure(Error.RATE_LIMITED) }
        0 * scoped.write(_)
        response.statusCode() == 429
        response.headers().firstValue('Retry-After').get() == '60'
    }
    def 'scope cleanup failure after completed callback withholds proof acknowledgement'() {
        when:
        def response=post(JsonOutput.toJson(command()))
        then:
        1 * store.inRequest('provider', _) >> { String actor,java.util.function.Function operation ->
            assert operation.apply(scoped)!=null
            throw new IllegalStateException('synthetic exact-unlock acknowledgement failure')
        }
        1 * scoped.write(_) >> snapshot()
        response.statusCode()==503
        response.body()=='{"error":"login_proof_request_rejected"}'
    }
    def 'current-eligibility and uncertain write failures do not issue acknowledgement'() {
        when:
        def response = post(JsonOutput.toJson(command()))
        then:
        1 * store.inRequest('provider', _) >> { String actor, java.util.function.Function operation -> operation.apply(scoped) }
        1 * scoped.write(_) >> { throw failure }
        response.statusCode() == expected
        response.body() == '{"error":"login_proof_request_rejected"}'
        where:
        failure                               | expected
        new Failure(Error.CONFLICT)           | 409
        new Failure(Error.EXPIRED)            | 410
        new Failure(Error.UNAVAILABLE)        | 503
        new IllegalStateException('synthetic') | 503
    }
    def 'missing resolve alone permits bounded previous-key lookup'() {
        when:
        def response = post(JsonOutput.toJson(lookup()), RESOLVE, true)
        then:
        1 * store.inRequest('provider', _) >> { String actor, java.util.function.Function operation -> operation.apply(scoped) }
        1 * scoped.resolve(_) >> { throw new Failure(Error.MISSING) }
        response.statusCode() == 404
        response.headers().firstValue('Cache-Control').get() == 'no-store'
    }
    def 'repeated HTTP write retains original command and acknowledgement but consumes admission each time'() {
        given:
        def input = command()
        def result = snapshot()
        when:
        def first = post(JsonOutput.toJson(input))
        def retry = post(JsonOutput.toJson(input))
        then:
        2 * store.inRequest('provider', _) >> { String actor, java.util.function.Function operation -> operation.apply(scoped) }
        2 * scoped.write({ it.commandId().toString() == input.command_id }) >> result
        first.statusCode() == 200 && retry.statusCode() == 200 && first.body() == retry.body()
    }
    def 'route delegates complete concurrent callbacks to the supplied request scope'() {
        given:
        def entered = new CountDownLatch(16)
        def release = new CountDownLatch(1)
        def executor = Executors.newFixedThreadPool(16)
        def futures = []
        // Transport-only fixture gate; real cross-process PostgreSQL admission is tested separately.
        def fixturePermits=new Semaphore(16)
        store.inRequest('provider', _) >> { String actor,java.util.function.Function operation ->
            if(!fixturePermits.tryAcquire())throw new Failure(Error.RATE_LIMITED)
            try { entered.countDown(); if(!release.await(6,TimeUnit.SECONDS))throw new IllegalStateException('synthetic timeout'); operation.apply(scoped) }
            finally { fixturePermits.release() }
        }
        scoped.write(_) >> snapshot()
        when:
        try {
            16.times { futures << executor.submit({ post(JsonOutput.toJson(command())).statusCode() } as Callable<Integer>) }
            assert entered.await(5, TimeUnit.SECONDS)
            assert post(JsonOutput.toJson(command())).statusCode() == 429
        } finally {
            release.countDown()
            executor.shutdown()
        }
        def statuses = futures.collect { it.get(8, TimeUnit.SECONDS) }
        then:
        statuses == [200] * 16
        executor.awaitTermination(5, TimeUnit.SECONDS)
    }
    def 'configuration defaults disabled and cannot enable positive enforcement'() {
        expect:
        !new LoginProofConfiguration([:]).enabled
        new LoginProofConfiguration([:]).routes(null) == null
        when:
        new LoginProofConfiguration([AUTH_SSO_LOGIN_PROOF_ENABLED: 'true'])
        then:
        thrown(IllegalArgumentException)
    }
    def 'production default leaves routes absent without any explicit registration'() {
        given:
        def disabled = Javalin.create().start('127.0.0.1', 0)
        when:
        def response = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:${disabled.port()}/internal/sso-login-proofs"))
            .POST(HttpRequest.BodyPublishers.ofString('{}')).build(), HttpResponse.BodyHandlers.ofString())
        then:
        response.statusCode() == 404
        cleanup:
        disabled.stop()
    }
    def 'actual status binding OTP and migration credentials cannot be reused by proof transport or retention'() {
        when:
        new LoginProofConfiguration(environment() + [(name): reused])
        then:
        thrown(IllegalArgumentException)
        where:
        name                              | reused
        'AUTH_TO_SSO_STATUS_KEY'           | WRITE
        'AUTH_TO_SSO_STATUS_KEY'           | RESOLVE
        'ARMONEY_PROVIDER_BINDING_KEY'     | WRITE
        'ARMONEY_PROVIDER_BINDING_KEY'     | RESOLVE
        'SSO_MIGRATION_PROOF_WRITE_KEY'    | WRITE
        'SSO_MIGRATION_PROOF_RESOLVE_KEY'  | RESOLVE
        'VERIFICATION_OTP_HMAC_KEY'        | environment().SSO_LOGIN_PROOF_RETENTION_KEY
        'VERIFICATION_DELIVERY_ENCRYPTION_KEY' | environment().SSO_LOGIN_PROOF_RETENTION_KEY
    }
    def 'enabled configuration rejects missing keys malformed flags migration client and duplicate allowlists'() {
        when:
        new LoginProofConfiguration(environment() + change)
        then:
        thrown(IllegalArgumentException)
        where:
        change << [[AUTH_LOGIN_PROOF_BRIDGE_ENABLED: 'yes'], [SSO_LOGIN_PROOF_WRITE_KEY: 'short'],
            [SSO_LOGIN_PROOF_RESOLVE_KEY: WRITE], [SSO_LOGIN_PROOF_RETENTION_KEY: 'not-base64'],
            [SSO_LOGIN_PROOF_CLIENT_IDS: 'armoney-migration'], [SSO_LOGIN_PROOF_CODE_KEY_IDS: 'one,two,three'],
            [SSO_LOGIN_PROOF_CLIENT_IDS: 'armoney-web,armoney-web']]
    }
    def 'distinct valid transport configuration remains independent from delivery and banking activation'() {
        given:
        def env = environment() + [AUTH_TO_SSO_STATUS_KEY: 'synthetic-distinct-status-key-32-characters',
            ARMONEY_PROVIDER_BINDING_KEY: 'synthetic-distinct-binding-key-32-characters']
        expect:
        new LoginProofConfiguration(env).enabled
        !new VerificationDeliveryConfiguration(env).enabled
        !new LoginProofConfiguration(env).toString().contains(WRITE)
        !env.containsKey('AUTH_SSO_LOGIN_PROOF_ENABLED')
    }
}
