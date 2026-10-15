package com.arman.bank.paymentservice
import com.arman.bank.paymentservice.application.*
import com.arman.bank.paymentservice.infrastructure.*
import com.arman.bank.runtime.InternalHttp
import spock.lang.Specification
import java.net.http.*
import java.time.Duration
import java.util.concurrent.*
import java.nio.ByteBuffer
import com.sun.net.httpserver.HttpServer

class MailtrapDeliverySpec extends Specification {
    def env(String mode='sandbox') {
        [EMAIL_DELIVERY_ENABLED:'true',MAILTRAP_MODE:mode,MAILTRAP_API_TOKEN:'synthetic-token',
         MAILTRAP_INBOX_ID:'123',MAILTRAP_FROM_EMAIL:'bank@example.test',AUTH_BASE_URL:'http://auth-service:8080',USER_BASE_URL:'http://user-service:8080',
         INTERNAL_AUTH_KEY:'synthetic-internal-key-at-least-32-characters']
    }
    def claim() { new EmailOutbox.Claim(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),'PAYMENT_COMPLETED',125L,UUID.randomUUID(),1,null,null) }
    static byte[] bytes(String s) { s.getBytes(java.nio.charset.StandardCharsets.UTF_8) }
    static String body(HttpRequest request) {
        def future=new CompletableFuture<String>()
        def out=new ByteArrayOutputStream()
        request.bodyPublisher().get().subscribe(new Flow.Subscriber<ByteBuffer>() {
            void onSubscribe(Flow.Subscription s) { s.request(Long.MAX_VALUE) }
            void onNext(ByteBuffer b) { byte[] chunk=new byte[b.remaining()]; b.get(chunk); out.write(chunk) }
            void onError(Throwable e) { future.completeExceptionally(e) }
            void onComplete() { future.complete(out.toString('UTF-8')) }
        })
        future.get(2,TimeUnit.SECONDS)
    }
    def 'disabled by default needs no provider credentials and enabled configuration rejects unsafe values'() {
        expect:
        !new EmailConfiguration([:]).enabled
        when:
        new EmailConfiguration(env()+values)
        then:
        thrown(IllegalArgumentException)
        where:
        values << [[MAILTRAP_MODE:'other'],[MAILTRAP_INBOX_ID:'../send'],[MAILTRAP_FROM_EMAIL:'bad'],
                   [AUTH_BASE_URL:'http://user:password@auth'],[EMAIL_DELIVERY_ENABLED:'yes']]
    }
    def 'fixed endpoint authentication and minimal integer fils template in #mode'() {
        given:
        def requests=[]
        def delivery=new MailtrapDelivery(new EmailConfiguration(env(mode)), { HttpRequest req ->
            requests << req; new MailtrapDelivery.Response(200,bytes('{"success":true}'))
        } as MailtrapDelivery.Transport)
        def c=claim()
        expect:
        delivery.send(c,'recipient@example.test')==EmailDelivery.Result.ACCEPTED
        requests.first().uri().toString()==endpoint
        requests.first().headers().firstValue(header).get()==credential
        def payload=InternalHttp.JSON.readTree(body(requests.first()))
        payload.path('text').asText().contains('AED 1.25')
        payload.path('text').asText().contains(c.paymentId().toString())
        payload.path('text').asText().contains(c.id().toString())
        !payload.path('text').asText().contains('recipient@example.test')
        payload.path('to').size()==1
        payload.path('html').asText().contains('AED 1.25')
        payload.path('html').asText().contains(c.paymentId().toString())
        payload.path('html').asText().contains(c.id().toString())
        payload.path('html').asText().contains('Outgoing transfer')
        !payload.path('html').asText().contains('recipient@example.test')
        where:
        mode | endpoint | header | credential
        'sandbox' | 'https://sandbox.api.mailtrap.io/api/send/123' | 'Api-Token' | 'synthetic-token'
        'sending' | 'https://send.api.mailtrap.io/api/send' | 'Authorization' | 'Bearer synthetic-token'
    }
    def 'provider responses cannot fabricate accepted delivery'() {
        given:
        def delivery=new MailtrapDelivery(new EmailConfiguration(env()), { req ->
            new MailtrapDelivery.Response(status,bytes(json))
        } as MailtrapDelivery.Transport)
        expect:
        delivery.send(claim(),'recipient@example.test')==result
        where:
        status | json | result
        200 | '{"success":true}' | EmailDelivery.Result.ACCEPTED
        200 | '{"success":"true"}' | EmailDelivery.Result.TRANSIENT_FAILURE
        200 | '{}' | EmailDelivery.Result.TRANSIENT_FAILURE
        200 | '{"success":false}' | EmailDelivery.Result.TRANSIENT_FAILURE
        429 | '{}' | EmailDelivery.Result.RATE_LIMITED
        500 | '{}' | EmailDelivery.Result.TRANSIENT_FAILURE
        408 | '{}' | EmailDelivery.Result.TRANSIENT_FAILURE
        400 | '{}' | EmailDelivery.Result.PERMANENT_FAILURE
        401 | '{}' | EmailDelivery.Result.PERMANENT_FAILURE
        302 | '{}' | EmailDelivery.Result.PERMANENT_FAILURE
    }
    def 'malformed provider JSON is never accepted'() {
        given:
        def delivery=new MailtrapDelivery(new EmailConfiguration(env()), { req ->
            new MailtrapDelivery.Response(200,bytes('not json'))
        } as MailtrapDelivery.Transport)
        when:
        delivery.send(claim(),'recipient@example.test')
        then:
        thrown(IOException)
    }
    def 'identity lookup requires strict boolean verified and explicit email field'() {
        given:
        def delivery=new MailtrapDelivery(new EmailConfiguration(env()), { HttpRequest req ->
            assert req.uri().path=="/v1/internal/identities/"+owner+"/email"
            assert req.headers().firstValue('X-Service-Key').get()==env().INTERNAL_AUTH_KEY
            new MailtrapDelivery.Response(200,bytes(json))
        } as MailtrapDelivery.Transport)
        when:
        delivery.recipient(owner)
        then:
        thrown(IOException)
        where:
        json << ['{"email":"owner@example.test","verified":"true"}','{"email":"owner@example.test"}','{"verified":true}','{"email":7,"verified":true}']
        owner=UUID.randomUUID()
    }
    def 'identity valid metadata remains explicit including missing address'() {
        given:
        def delivery=new MailtrapDelivery(new EmailConfiguration(env()), { req ->
            new MailtrapDelivery.Response(200,bytes(json))
        } as MailtrapDelivery.Transport)
        expect:
        delivery.recipient(UUID.randomUUID())==new EmailDelivery.Recipient(address,verified)
        where:
        json | address | verified
        '{"email":"owner@example.test","verified":false}' | 'owner@example.test' | false
        '{"email":"owner@example.test","verified":true}' | 'owner@example.test' | true
        '{"email":null,"verified":false}' | null | false
    }
    def 'owner matched profile display name personalizes email without trusting profile contact fields'() {
        given:
        def owner=UUID.randomUUID()
        def requests=[]
        def profileName='Amina <Admin> & "Team"'
        def delivery=new MailtrapDelivery(new EmailConfiguration(env()), { HttpRequest req ->
            requests << req
            if (req.uri().path.endsWith('/email'))
                return new MailtrapDelivery.Response(200,bytes('{"email":"owner@example.test","verified":true}'))
            assert req.uri().toString()=='http://user-service:8080/v1/users/me'
            assert req.headers().firstValue('X-Service-Key').get()==env().INTERNAL_AUTH_KEY
            assert req.headers().firstValue('X-Identity-Id').get()==owner.toString()
            assert req.timeout().get()==Duration.ofSeconds(3)
            new MailtrapDelivery.Response(200,InternalHttp.JSON.writeValueAsBytes(
                [identity_id:owner.toString(),display_name:profileName,email:'other@example.test']))
        } as MailtrapDelivery.Transport)
        when:
        def recipient=delivery.recipient(owner)
        then:
        recipient==new EmailDelivery.Recipient('owner@example.test',true,profileName)
        requests.size()==2
    }

    def 'missing unavailable and untrusted profile metadata falls back without disabling mail'() {
        given:
        def owner=UUID.randomUUID()
        def delivery=new MailtrapDelivery(new EmailConfiguration(env()), { HttpRequest req ->
            if (req.uri().path.endsWith('/email'))
                return new MailtrapDelivery.Response(200,bytes('{"email":"owner@example.test","verified":false}'))
            if (failure) throw new java.net.http.HttpTimeoutException('synthetic')
            String response=profile=='malformed' ? 'not json' : InternalHttp.JSON.writeValueAsString(
                [identity_id:wrongOwner ? UUID.randomUUID().toString() : owner.toString(),display_name:profile])
            new MailtrapDelivery.Response(status,bytes(response))
        } as MailtrapDelivery.Transport)
        expect:
        delivery.recipient(owner)==new EmailDelivery.Recipient('owner@example.test',false,null)
        where:
        status | profile | wrongOwner | failure
        404 | 'Name' | false | false
        503 | 'Name' | false | false
        401 | 'Name' | false | false
        302 | 'Name' | false | false
        200 | 'Name' | true | false
        200 | null | false | false
        200 | 7 | false | false
        200 | '' | false | false
        200 | 'N' * 101 | false | false
        200 | 'malformed' | false | false
        200 | 'Name' | false | true
    }

    def 'real bounded HTTP transport does not follow redirect and rejects oversized response'() {
        given:
        def server=HttpServer.create(new InetSocketAddress('127.0.0.1',0),0)
        def redirected=new java.util.concurrent.atomic.AtomicInteger()
        server.createContext('/redirect') { x -> x.responseHeaders.add('Location','/target'); x.sendResponseHeaders(302,-1); x.close() }
        server.createContext('/target') { x -> redirected.incrementAndGet(); x.sendResponseHeaders(200,-1); x.close() }
        server.createContext('/large') { x -> def b=new byte[9000]; x.sendResponseHeaders(200,b.length); x.responseBody.write(b); x.close() }
        server.createContext('/slow') { x ->
            try { Thread.sleep(1000); x.sendResponseHeaders(200,-1) } finally { x.close() }
        }
        server.start()
        def transport=new BoundedEmailHttp()
        def base="http://127.0.0.1:"+server.address.port
        when:
        def response=transport.exchange(HttpRequest.newBuilder(URI.create(base+'/redirect')).timeout(Duration.ofSeconds(2)).GET().build())
        then:
        response.status()==302
        redirected.get()==0
        when:
        transport.exchange(HttpRequest.newBuilder(URI.create(base+'/large')).timeout(Duration.ofSeconds(2)).GET().build())
        then:
        thrown(IOException)
        when:
        transport.exchange(HttpRequest.newBuilder(URI.create(base+'/slow')).timeout(Duration.ofMillis(100)).GET().build())
        then:
        thrown(IOException)
        cleanup:
        transport?.close(); server?.stop(0)
    }
}
