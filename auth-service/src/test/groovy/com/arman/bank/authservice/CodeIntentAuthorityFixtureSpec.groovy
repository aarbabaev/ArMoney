package com.arman.bank.authservice.infrastructure

import com.arman.bank.authservice.domain.IdentityVerification.*
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import spock.lang.Specification
import java.net.http.*
import java.time.*
import java.util.concurrent.*
import java.util.concurrent.atomic.*
import java.util.function.LongSupplier

/** Fixture mechanics ONLY. Scripted Work/fixture-only monotonic clock are not Auth grants,
 * provider observations, projection ACKs, actual password proof or joint acceptance. */
class CodeIntentAuthorityFixtureSpec extends Specification {
    static final String KEY='synthetic-private-authority-fixture-provider-key'
    static final String ISSUER='https://issuer.example/sso/realms/armoney'
    static final String HANDLE=Base64.urlEncoder.withoutPadding().encodeToString(([7]*32) as byte[])
    AtomicLong clock=new AtomicLong(1_000_000)
    AtomicInteger opened=new AtomicInteger()
    Probe work=new Probe()
    CodeIntentAuthorityFixture fixture
    HttpClient client
    UUID prepareCommand=UUID.randomUUID()
    Flow flow=new Flow(ISSUER,'subject','armoney-web','root','tab',Base64.urlEncoder.withoutPadding().encodeToString(([8]*32) as byte[]))
    def setup(){
        fixture=new CodeIntentAuthorityFixture(KEY,Policy.defaults([ISSUER] as Set,['armoney-web'] as Set,['sso-provider'] as Set),
            {opened.incrementAndGet();work} as CodeIntentAuthorityFixture.Factory,clock.&get as LongSupplier)
        fixture.start('127.0.0.1',0)
        client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build()
    }
    def cleanup(){work.release.countDown();fixture?.close();client?.close()}
    static class Probe implements CodeIntentAuthorityFixture.Work {
        AtomicInteger prepared=new AtomicInteger()
        AtomicInteger observed=new AtomicInteger()
        AtomicInteger closes=new AtomicInteger()
        CountDownLatch entered=new CountDownLatch(1)
        CountDownLatch release=new CountDownLatch(1)
        AtomicBoolean closed=new AtomicBoolean()
        boolean block
        boolean failPreparation
        boolean failReadiness
        Closure afterReadiness={}
        @Override void prepare(Flow flow,CodeIntentAuthorityFixture.Check check){
            prepared.incrementAndGet();entered.countDown()
            if(block&&!release.await(3,TimeUnit.SECONDS))throw new IllegalStateException('bounded scripted preparation')
            check.check();if(failPreparation)throw new IllegalStateException('scripted preparation unavailable')
        }
        @Override CodeIntentAuthorityFixture.Bound readiness(Flow flow,CodeIntentAuthorityFixture.Check check){
            observed.incrementAndGet();check.check();afterReadiness.call();if(failReadiness)throw new IllegalStateException('scripted readiness unavailable')
            def now=Instant.now();new CodeIntentAuthorityFixture.Bound(now,now.plusSeconds(60))
        }
        @Override void close(){if(closed.compareAndSet(false,true)){closes.incrementAndGet();release.countDown()}}
    }
    Map prepareBody(){[experiment_handle:HANDLE,command_id:prepareCommand.toString(),issuer:flow.issuer(),subject:flow.subject(),client_id:flow.clientId(),
        provider_root_session_id:flow.rootSessionId(),provider_tab_id:flow.tabId(),authentication_session_id:flow.authenticationSessionId()]}
    Map control(UUID command=prepareCommand){[experiment_handle:HANDLE,command_id:command.toString()]}
    def post(String operation,Object body,String key=KEY){
        byte[] bytes=body instanceof byte[]?(byte[])body:(body instanceof String?body:JsonOutput.toJson(body)).getBytes('UTF-8')
        def builder=HttpRequest.newBuilder(URI.create('http://127.0.0.1:'+fixture.port()+CodeIntentAuthorityFixture.PREFIX+operation))
            .timeout(Duration.ofSeconds(6)).header('Content-Type','application/json')
        if(key!=null)builder.header(CodeIntentAuthorityFixture.HEADER,key)
        client.send(builder.POST(HttpRequest.BodyPublishers.ofByteArray(bytes)).build(),HttpResponse.BodyHandlers.ofString())
    }
    boolean awaitState(String expected,String current=HANDLE){
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(4)
        while(fixture.state(current,prepareCommand).state!=expected&&System.nanoTime()<end)Thread.sleep(10)
        if(fixture.state(current,prepareCommand).state!=expected)throw new AssertionError('bounded fixture state transition missing')
        true
    }
    static boolean requireSafe(boolean value){if(!value)throw new AssertionError('private fixture invariant failed');true}
    def 'credential precedes malformed body and all resource allocation #key'(){
        when:def response=post('prepare','not-json',key)
        then:response.statusCode()==401;response.body()=='';opened.get()==0;work.prepared.get()==0
        where:key << [null,'synthetic-wrong-provider-fixture-key']
    }
    def 'strict body rejects #kind without preparation resources'(){
        given:
        String valid=JsonOutput.toJson(prepareBody())
        Object body=switch(kind){
            case 'duplicate'->valid.substring(0,valid.length()-1)+',"subject":"other"}'
            case 'extra'->JsonOutput.toJson(prepareBody()+[email_verified:true])
            case 'null'->JsonOutput.toJson(prepareBody()+[subject:null])
            case 'wrong-type'->JsonOutput.toJson(prepareBody()+[subject:42])
            case 'unicode-space'->JsonOutput.toJson(prepareBody()+[subject:'bad'+(char)0x2007+'id'])
            case 'surrogate'->valid.replace('"subject":"subject"','"subject":"\\ud800"')
            case 'trailing'->valid+' {}'
            case 'oversized'->'x'*12289
            case 'utf16'->valid.getBytes(java.nio.charset.StandardCharsets.UTF_16)
        }
        when:def response=post('prepare',body)
        then:response.statusCode()==400;response.body()=='';opened.get()==0
        response.headers().firstValue('Cache-Control').orElse('')=='no-store'
        where:kind << ['duplicate','extra','null','wrong-type','unicode-space','surrogate','trailing','oversized','utf16']
    }
    def 'prepare is immutable one-shot and pure state does not perform readiness'(){
        given:work.block=true
        when:def accepted=post('prepare',prepareBody())
        then:accepted.statusCode()==202;work.entered.await(2,TimeUnit.SECONDS)
        when:def repeated=post('prepare',prepareBody());def state=post('state',control());def notReady=post('readiness',control(UUID.randomUUID()))
        then:repeated.statusCode()==202;state.body()=='{"state":"PREPARING"}';notReady.statusCode()==503
        opened.get()==1;work.prepared.get()==1;work.observed.get()==0
        when:def conflict=post('prepare',prepareBody()+[provider_tab_id:'different']);def newCommand=post('prepare',prepareBody()+[command_id:UUID.randomUUID().toString()])
        then:conflict.statusCode()==409;newCommand.statusCode()==409;opened.get()==1
    }
    def 'readiness exact replay revalidates and never renews original stage handle'(){
        given:post('prepare',prepareBody());awaitState('READY');UUID command=UUID.randomUUID()
        when:def first=post('readiness',control(command));def again=post('readiness',control(command))
        then:first.statusCode()==200;again.statusCode()==200;requireSafe(first.body()==again.body());work.observed.get()==2
        when:clock.addAndGet(TimeUnit.SECONDS.toNanos(31));def expired=post('readiness',control(command))
        then:expired.statusCode()==410;expired.body()=='';work.observed.get()==2
    }
    def 'known fresh-readiness failure fences experiment instead of returning cached success'(){
        given:post('prepare',prepareBody());awaitState('READY');UUID command=UUID.randomUUID();requireSafe(post('readiness',control(command)).statusCode()==200)
        when:work.failReadiness=true;def failed=post('readiness',control(command));def repeated=post('readiness',control(command))
        then:failed.statusCode()==503;repeated.statusCode()==503;work.observed.get()==2;fixture.state(HANDLE,prepareCommand).state=='FAILED'
    }
    def 'late successful preparation cannot cross original setup deadline'(){
        given:work.block=true;post('prepare',prepareBody());assert work.entered.await(2,TimeUnit.SECONDS)
        when:clock.addAndGet(TimeUnit.SECONDS.toNanos(91));work.release.countDown()
        then:awaitState('FAILED');post('readiness',control()).statusCode()==503;work.observed.get()==0
    }
    def 'late readiness cannot publish a handle after original experiment deadline'(){
        given:post('prepare',prepareBody());awaitState('READY')
        work.afterReadiness={clock.addAndGet(TimeUnit.SECONDS.toNanos(121))}
        when:def result=post('readiness',control(UUID.randomUUID()))
        then:result.statusCode()==410;result.body()=='';fixture.state(HANDLE,prepareCommand).state=='EXPIRED';work.observed.get()==1
    }
    def 'cancel linearizes against blocked preparation and cannot rearm'(){
        given:work.block=true;post('prepare',prepareBody());assert work.entered.await(2,TimeUnit.SECONDS);UUID cancel=UUID.randomUUID()
        when:def stopped=post('cancel',control(cancel));def replay=post('cancel',control(cancel));work.release.countDown()
        then:stopped.statusCode()==204;replay.statusCode()==204;awaitState('CANCELLED');work.closes.get()==1
        post('readiness',control()).statusCode()==410;post('prepare',prepareBody()).statusCode()==410;opened.get()==1
    }
    def 'failed preparation does not retry its original command'(){
        given:work.failPreparation=true
        when:post('prepare',prepareBody())
        then:awaitState('FAILED');post('prepare',prepareBody()).statusCode()==503;opened.get()==1
    }
    def 'command capacity reserves cancellation and never allocates unbounded records'(){
        given:fixture.prepare(HANDLE,prepareCommand,flow);awaitState('READY')
        when:14.times{fixture.readiness(HANDLE,UUID.randomUUID())}
        then:opened.get()==1
        when:fixture.readiness(HANDLE,UUID.randomUUID())
        then:def failure=thrown(CodeIntentAuthorityFixture.Denied);failure.status==429
        when:fixture.cancel(HANDLE,UUID.randomUUID())
        then:fixture.state(HANDLE,prepareCommand).state=='CANCELLED'
    }
    def 'terminal record capacity is bounded and original retention never slides'(){
        given:
        // Fixture-only terminal metadata; no Auth authority, proof or database seed.
        def field=fixture.getClass().getDeclaredField('experiments');field.accessible=true
        Map records=(Map)field.get(fixture)
        List<String> handles=(1..17).collect{Base64.urlEncoder.withoutPadding().encodeToString(([it]*32) as byte[])}
        16.times{
            def terminal=new CodeIntentAuthorityFixture.Experiment(handles[it],flow,clock.get())
            terminal.state=CodeIntentAuthorityFixture.State.FAILED
            records[handles[it]]=terminal
        }
        when:fixture.prepare(handles[16],UUID.randomUUID(),flow)
        then:def failure=thrown(CodeIntentAuthorityFixture.Denied);failure.status==429;opened.get()==0
        when:clock.addAndGet(TimeUnit.SECONDS.toNanos(151));fixture.prepare(handles[16],UUID.randomUUID(),flow)
        then:awaitState('READY',handles[16]);opened.get()==1
    }
    static class SubscriptionProbe implements java.util.concurrent.Flow.Subscription {
        long requested
        int cancelled
        @Override void request(long amount){requested+=amount}
        @Override void cancel(){cancelled++}
    }
    def 'body cancellation before subscription never requests or copies late data'(){
        given:
        def body=new CodeIntentAuthorityFixture.Bytes(8)
        def incoming=new SubscriptionProbe()
        def late=java.nio.ByteBuffer.wrap(([1,2] as byte[]))
        when:body.cancel();body.cancel();body.onSubscribe(incoming);body.onNext([late]);body.onComplete();body.onError(new IOException('synthetic late error'))
        then:incoming.requested==0;incoming.cancelled==1;late.position()==0;body.body.toCompletableFuture().isCompletedExceptionally()
    }
    def 'body cancellation after subscription is terminal and idempotent'(){
        given:
        def body=new CodeIntentAuthorityFixture.Bytes(8)
        def active=new SubscriptionProbe()
        def duplicate=new SubscriptionProbe()
        def late=java.nio.ByteBuffer.wrap(([1,2] as byte[]))
        when:body.onSubscribe(active);body.cancel();body.cancel();body.onSubscribe(duplicate);body.onNext([late]);body.onComplete()
        then:active.requested==1;active.cancelled==1;duplicate.requested==0;duplicate.cancelled==1
        late.position()==0;body.body.toCompletableFuture().isCompletedExceptionally()
    }
    def 'successful body completion ignores all duplicate or late callbacks'(){
        given:
        def body=new CodeIntentAuthorityFixture.Bytes(8)
        def active=new SubscriptionProbe()
        def duplicate=new SubscriptionProbe()
        def late=new SubscriptionProbe()
        def input=java.nio.ByteBuffer.wrap(([1,2] as byte[]))
        def ignored=java.nio.ByteBuffer.wrap(([3] as byte[]))
        when:body.onSubscribe(active);body.onSubscribe(duplicate);body.onNext([input]);body.onComplete()
        body.onNext([ignored]);body.onComplete();body.onError(new IOException('synthetic late error'));body.cancel();body.onSubscribe(late)
        then:active.requested==2;duplicate.cancelled==1;duplicate.requested==0;late.cancelled==1;late.requested==0
        ignored.position()==0;body.body.toCompletableFuture().join().toList()==[1,2]
    }
    def 'body capacity failure cannot copy or request further late data'(){
        given:
        def body=new CodeIntentAuthorityFixture.Bytes(2)
        def active=new SubscriptionProbe()
        def oversized=java.nio.ByteBuffer.wrap(([1,2,3] as byte[]))
        def late=java.nio.ByteBuffer.wrap(([4] as byte[]))
        when:body.onSubscribe(active);body.onNext([oversized]);body.onNext([late]);body.onComplete();body.cancel()
        then:active.requested==1;active.cancelled==1;oversized.position()==0;late.position()==0
        body.body.toCompletableFuture().isCompletedExceptionally()
    }
    static int permits(CodeIntentAuthorityFixture value){def field=value.getClass().getDeclaredField('controls');field.accessible=true;((Semaphore)field.get(value)).availablePermits()}
    def 'private listener has no Write or production route'(){
        when:def response=client.send(HttpRequest.newBuilder(URI.create('http://127.0.0.1:'+fixture.port()+'/internal/sso-code-intents')).timeout(Duration.ofSeconds(2)).POST(HttpRequest.BodyPublishers.ofString('{}')).build(),HttpResponse.BodyHandlers.discarding())
        then:response.statusCode()==404;opened.get()==0
        response.headers().firstValue('Cache-Control').orElse('')=='no-store'
    }
    def 'authenticated stalled or drip body physically terminates without allocating work #drip'(){
        given:
        def socket=new Socket('127.0.0.1',fixture.port())
        socket.soTimeout=7000
        String headers='POST '+CodeIntentAuthorityFixture.PREFIX+'prepare HTTP/1.1\r\nHost: localhost\r\nContent-Type: application/json\r\nContent-Length: 100\r\n'+CodeIntentAuthorityFixture.HEADER+': '+KEY+'\r\n\r\n'
        socket.outputStream.write(headers.getBytes('US-ASCII'));socket.outputStream.flush()
        long started=System.nanoTime()
        boolean closed=false
        when:
        if(drip){
            for(int n=0;n<27;n++){
                try{socket.outputStream.write(32);socket.outputStream.flush();Thread.sleep(200)}catch(IOException transportClosed){break}
            }
        }
        try{closed=socket.inputStream.read()==-1}catch(IOException transportClosed){closed=true}
        then:closed;TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started)>=3000
        TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started)<6500;opened.get()==0
        permits(fixture)==16
        cleanup:socket?.close()
        where:drip << [false,true]
    }
}
