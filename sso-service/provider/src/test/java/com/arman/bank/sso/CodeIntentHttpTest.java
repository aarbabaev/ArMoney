package com.arman.bank.sso;

import org.junit.jupiter.api.Test;
import java.net.*;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import javax.net.ssl.*;
import static org.junit.jupiter.api.Assertions.*;

class CodeIntentHttpTest {
    @Test void failedStageDiagnosticsContainOnlyClosedCategoriesAndNeverChangeRequests(){
        var records=new ArrayList<java.util.logging.LogRecord>();
        var logger=java.util.logging.Logger.getLogger(CodeIntentHttp.class.getName());
        var handler=new java.util.logging.Handler(){
            public void publish(java.util.logging.LogRecord record){records.add(record);}
            public void flush(){} public void close(){}
        };
        logger.addHandler(handler);
        try {
            for(int status:List.of(400,401,409,410,429,503,502)){
                records.clear();var fake=new Fake();fake.status=status;fake.body="private response must never be logged";
                var failure=assertThrows(CodeIntentHttp.Failure.class,()->new CodeIntentHttp(BASE,fake).post(CodeIntentHttp.Operation.STAGE,Map.of("private","request-secret"),KEY,new InvocationDeadline(()->0L)));
                assertNull(failure.getCause());assertEquals("Code intent unavailable",failure.getMessage());assertEquals(1,fake.calls);
                assertEquals(1,records.size());assertNull(records.getFirst().getThrown());
                assertEquals("armoney_code_intent_stage_failure category="+(status==502?"HTTP_OTHER":"HTTP_"+status),records.getFirst().getMessage());
            }
            records.clear();var malformed=new Fake();malformed.body="private invalid json";
            assertThrows(CodeIntentHttp.Failure.class,()->new CodeIntentHttp(BASE,malformed).post(CodeIntentHttp.Operation.STAGE,Map.of(),KEY,new InvocationDeadline(()->0L)));
            assertEquals(1,malformed.calls);assertEquals("armoney_code_intent_stage_failure category=PROTOCOL",records.getFirst().getMessage());
            for(boolean timeout:List.of(false,true)){
                records.clear();var transport=new Fake();transport.transportFailure=timeout?new HttpTimeoutException("private timeout"):new java.io.IOException("private transport");
                assertThrows(CodeIntentHttp.Failure.class,()->new CodeIntentHttp(BASE,transport).post(CodeIntentHttp.Operation.STAGE,Map.of(),KEY,new InvocationDeadline(()->0L)));
                assertEquals(1,transport.calls);assertEquals(1,records.size());assertNull(records.getFirst().getThrown());
                assertEquals("armoney_code_intent_stage_failure category="+(timeout?"TIMEOUT":"TRANSPORT"),records.getFirst().getMessage());
            }
            records.clear();var success=new Fake();
            assertTrue(new CodeIntentHttp(BASE,success).post(CodeIntentHttp.Operation.STAGE,Map.of(),KEY,new InvocationDeadline(()->0L)).isObject());
            assertEquals(1,success.calls);assertTrue(records.isEmpty());
            var keyFailure=new Fake();keyFailure.status=503;
            assertThrows(CodeIntentHttp.Failure.class,()->new CodeIntentHttp(BASE,keyFailure).post(CodeIntentHttp.Operation.KEY,Map.of(),KEY,new InvocationDeadline(()->0L)));
            assertEquals(1,keyFailure.calls);assertTrue(records.isEmpty());
        }finally{logger.removeHandler(handler);}
    }
    private static final URI BASE=URI.create("http://auth-service:8081");
    private static final String KEY="synthetic-code-intent-key-123456789";
    @Test void exactPrivatePathsHeadersAndRemainingTimeoutAreUsed() {
        for(var op:CodeIntentHttp.Operation.values()) {
            var http=new Fake(); var nanos=new java.util.concurrent.atomic.AtomicLong(); var deadline=new InvocationDeadline(nanos::get);
            nanos.set(1_000_000_000L);
            assertTrue(new CodeIntentHttp(BASE,http).post(op,Map.of(),KEY,deadline).isObject());
            assertEquals(op.path,http.request.uri().getPath()); assertEquals(KEY,http.request.headers().firstValue(op.header).orElseThrow());
            assertEquals(Duration.ofSeconds(3),http.request.timeout().orElseThrow()); deadline.close();
        }
    }
    @Test void statusRedirectCacheTypeMalformedAndOversizedBodiesFailClosed() {
        for(String fault:List.of("redirect","status","cache","type","json","duplicate","oversize")) {
            var fake=new Fake();
            switch(fault) {
                case "redirect" -> fake.status=302;
                case "status" -> fake.status=503;
                case "cache" -> fake.cache="public";
                case "type" -> fake.type="text/plain";
                case "json" -> fake.body="private not json";
                case "duplicate" -> fake.body="{\"x\":1,\"x\":2}";
                case "oversize" -> fake.body="x".repeat(12289);
            }
            var failure=assertThrows(CodeIntentHttp.Failure.class,()->new CodeIntentHttp(BASE,fake).post(CodeIntentHttp.Operation.KEY,Map.of(),KEY,new InvocationDeadline(()->0L)),fault);
            assertNull(failure.getCause()); assertEquals("Code intent unavailable",failure.getMessage()); assertEquals(1,fake.calls);
        }
    }
    @Test void requestOversizeAndSpentDeadlineNeverSend() {
        var fake=new Fake(); var transport=new CodeIntentHttp(BASE,fake);
        assertThrows(CodeIntentHttp.Failure.class,()->transport.post(CodeIntentHttp.Operation.STAGE,Map.of("large","x".repeat(12289)),KEY,new InvocationDeadline(()->0L)));
        assertEquals(0,fake.calls);
        var nanos=new java.util.concurrent.atomic.AtomicLong(); var deadline=new InvocationDeadline(nanos::get); nanos.set(4_000_000_000L);
        assertThrows(CodeIntentHttp.Failure.class,()->transport.post(CodeIntentHttp.Operation.CONFIRM,Map.of(),KEY,deadline)); assertEquals(0,fake.calls);
    }
    @Test void expiryAfterSendCancelsActualFutureAndBodySubscription() {
        var fake=new Fake(); var nanos=new java.util.concurrent.atomic.AtomicLong(); fake.stall=true; fake.onSend=()->nanos.set(4_000_000_000L);
        assertThrows(CodeIntentHttp.Failure.class,()->new CodeIntentHttp(BASE,fake).post(CodeIntentHttp.Operation.KEY,Map.of(),KEY,new InvocationDeadline(nanos::get)));
        assertTrue(fake.cancelled); assertTrue(fake.future.isCancelled());
    }
    @Test void lifecycleShutsDownOnlyOwnedClientAndRejectsAfterClose() {
        var owned=new Fake(); var transport=new CodeIntentHttp(BASE,owned,true);
        transport.close(); transport.close(); assertEquals(1,owned.shutdowns);
        assertThrows(CodeIntentHttp.Failure.class,()->transport.post(CodeIntentHttp.Operation.KEY,Map.of(),KEY,new InvocationDeadline(()->0L)));
        assertEquals(0,owned.calls);
        var shared=new Fake(); var injected=new CodeIntentHttp(BASE,shared);
        injected.close(); assertEquals(0,shared.shutdowns);
        // Another consumer of the injected client still works.
        assertTrue(new CodeIntentHttp(BASE,shared).post(CodeIntentHttp.Operation.KEY,Map.of(),KEY,new InvocationDeadline(()->0L)).isObject());
    }
    @Test void failureCanBeReleasedAndOwnedShutdownDuringSendWithholdsResponse() {
        var failed=new Fake(); failed.body="invalid";
        var transport=new CodeIntentHttp(BASE,failed,true);
        assertThrows(CodeIntentHttp.Failure.class,()->transport.post(CodeIntentHttp.Operation.KEY,Map.of(),KEY,new InvocationDeadline(()->0L)));
        transport.close(); assertEquals(1,failed.shutdowns);
        var active=new Fake(); active.stall=true;
        var closing=new CodeIntentHttp(BASE,active,true); active.onSend=closing::close;
        assertThrows(CodeIntentHttp.Failure.class,()->closing.post(CodeIntentHttp.Operation.KEY,Map.of(),KEY,new InvocationDeadline(()->0L)));
        assertTrue(active.future.isCancelled()); assertTrue(active.cancelled); assertEquals(1,active.shutdowns);
    }
    static final class Fake extends HttpClient {
        int status=200,calls,shutdowns; String body="{}",cache="no-store",type="application/json";
        @Override public void shutdownNow() { shutdowns++; if(future!=null) future.cancel(true); }
        boolean stall,cancelled; Throwable transportFailure;Runnable onSend=()->{}; HttpRequest request; CompletableFuture<?> future;
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request,HttpResponse.BodyHandler<T> handler) {
            calls++; this.request=request;
            var headers=HttpHeaders.of(Map.of("Cache-Control",List.of(cache),"Content-Type",List.of(type)),(a,b)->true);
            var subscriber=handler.apply(new HttpResponse.ResponseInfo() { public int statusCode(){return status;} public HttpHeaders headers(){return headers;} public Version version(){return Version.HTTP_1_1;} });
            subscriber.onSubscribe(new Flow.Subscription(){public void request(long n){} public void cancel(){cancelled=true;}});
            CompletableFuture<HttpResponse<T>> result=new CompletableFuture<>(); future=result;
            if(transportFailure!=null)result.completeExceptionally(transportFailure);
            else if(!stall) {
                subscriber.onNext(List.of(ByteBuffer.wrap(body.getBytes(java.nio.charset.StandardCharsets.UTF_8)))); subscriber.onComplete();
                subscriber.getBody().whenComplete((value,error)->{
                    if(error!=null) result.completeExceptionally(error);
                    else result.complete(new HttpResponse<>() {
                        public int statusCode(){return status;} public HttpRequest request(){return request;} public Optional<HttpResponse<T>> previousResponse(){return Optional.empty();}
                        public HttpHeaders headers(){return headers;} public T body(){return value;} public Optional<SSLSession> sslSession(){return Optional.empty();}
                        public URI uri(){return request.uri();} public Version version(){return Version.HTTP_1_1;}
                    });
                });
            }
            onSend.run(); return result;
        }
        public Optional<CookieHandler> cookieHandler(){return Optional.empty();} public Optional<Duration> connectTimeout(){return Optional.of(Duration.ofSeconds(2));}
        public Redirect followRedirects(){return Redirect.NEVER;} public Optional<ProxySelector> proxy(){return Optional.empty();}
        public SSLContext sslContext(){throw new UnsupportedOperationException();} public SSLParameters sslParameters(){return new SSLParameters();}
        public Optional<Authenticator> authenticator(){return Optional.empty();} public Version version(){return Version.HTTP_1_1;} public Optional<Executor> executor(){return Optional.empty();}
        public <T> HttpResponse<T> send(HttpRequest r,HttpResponse.BodyHandler<T> h){throw new UnsupportedOperationException();}
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest r,HttpResponse.BodyHandler<T> h,HttpResponse.PushPromiseHandler<T> p){throw new UnsupportedOperationException();}
    }
}
