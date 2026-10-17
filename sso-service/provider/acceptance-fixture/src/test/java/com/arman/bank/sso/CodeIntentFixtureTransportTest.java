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

class CodeIntentFixtureTransportTest {
    static final String PROVIDER="p".repeat(40),PEER="r".repeat(40);
    @Test void membershipAndAuthUseOnlyFixedDestinationsAndDifferentHeaders(){
        var fake=new Fake();try(var transport=new CodeIntentFixtureTransport(PROVIDER,PEER,fake)){
            try(var deadline=new InvocationDeadline(()->0L)){transport.call(CodeIntentFixtureTransport.Call.MEMBERSHIP,Map.of(),deadline);}
            assertEquals("http://sso-service:8080/sso/realms/armoney/armoney-code-intent-fixture/membership",fake.request.uri().toString());
            assertEquals(PEER,fake.request.headers().firstValue("X-ArMoney-Fixture-Peer-Key").orElseThrow());
            try(var deadline=new InvocationDeadline(()->0L)){transport.call(CodeIntentFixtureTransport.Call.READINESS,Map.of(),deadline);}
            assertEquals("http://auth-code-intent-fixture:8091/fixture/code-intent-authority/readiness",fake.request.uri().toString());
            assertEquals(PROVIDER,fake.request.headers().firstValue("X-ArMoney-Code-Intent-Fixture-Provider-Key").orElseThrow());
        }
        assertEquals(1,fake.shutdowns);
    }
    @Test void malformedOversizeRedirectAndExpiredWaitCancelWithoutPrivateException(){
        for(String fault:List.of("json","large","redirect","expired")){
            var fake=new Fake();var nanos=new java.util.concurrent.atomic.AtomicLong();
            switch(fault){case "json"->fake.body="private-body";case "large"->fake.body="x".repeat(12289);case "redirect"->fake.status=302;case "expired"->{fake.stall=true;fake.onSend=()->nanos.set(4_000_000_000L);}}
            try(var transport=new CodeIntentFixtureTransport(PROVIDER,PEER,fake)){
                var failure=assertThrows(CodeIntentFixtureSupport.Rejected.class,()->transport.call(CodeIntentFixtureTransport.Call.MEMBERSHIP,Map.of(),new InvocationDeadline(nanos::get)));
                assertNull(failure.getCause());assertFalse(failure.toString().contains("private-body"));assertEquals(1,fake.calls);
                if(fault.equals("expired")){assertTrue(fake.cancelled);assertTrue(fake.future.isCancelled());}
            }
        }
    }
    @Test void utf16Utf32AndUtf8BomResponsesReject(){
        for(byte[] bytes:List.of("{}".getBytes(java.nio.charset.StandardCharsets.UTF_16),new byte[]{0,0,0,123,0,0,0,125},new byte[]{(byte)239,(byte)187,(byte)191,123,125})){
            var fake=new Fake();fake.rawBody=bytes;try(var transport=new CodeIntentFixtureTransport(PROVIDER,PEER,fake)){
                assertThrows(CodeIntentFixtureSupport.Rejected.class,()->transport.call(CodeIntentFixtureTransport.Call.MEMBERSHIP,Map.of(),new InvocationDeadline(()->0L)));
            }
        }
    }
    static final class Fake extends HttpClient {
        int status=200,calls,shutdowns;String body="{}";byte[] rawBody;boolean stall,cancelled;Runnable onSend=()->{};java.util.function.Consumer<HttpRequest> beforeSend=r->{};HttpRequest request;CompletableFuture<?> future;
        @Override public void shutdownNow(){shutdowns++;if(future!=null)future.cancel(true);}
        public <T>CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request,HttpResponse.BodyHandler<T> handler){
            calls++;this.request=request;beforeSend.accept(request);var headers=HttpHeaders.of(Map.of("Cache-Control",List.of("no-store"),"Content-Type",List.of("application/json")),(a,b)->true);
            var sub=handler.apply(new HttpResponse.ResponseInfo(){public int statusCode(){return status;}public HttpHeaders headers(){return headers;}public Version version(){return Version.HTTP_1_1;}});
            sub.onSubscribe(new Flow.Subscription(){public void request(long n){}public void cancel(){cancelled=true;}});
            var result=new CompletableFuture<HttpResponse<T>>();future=result;
            if(!stall){sub.onNext(List.of(ByteBuffer.wrap(rawBody==null?body.getBytes(java.nio.charset.StandardCharsets.UTF_8):rawBody)));sub.onComplete();sub.getBody().whenComplete((value,error)->{
                if(error!=null)result.completeExceptionally(error);else result.complete(new HttpResponse<>(){
                    public int statusCode(){return status;}public HttpRequest request(){return request;}public Optional<HttpResponse<T>> previousResponse(){return Optional.empty();}public HttpHeaders headers(){return headers;}public T body(){return value;}public Optional<SSLSession> sslSession(){return Optional.empty();}public URI uri(){return request.uri();}public Version version(){return Version.HTTP_1_1;}
                });});}
            onSend.run();return result;
        }
        public Optional<CookieHandler> cookieHandler(){return Optional.empty();}public Optional<Duration> connectTimeout(){return Optional.of(Duration.ofSeconds(2));}public Redirect followRedirects(){return Redirect.NEVER;}public Optional<ProxySelector> proxy(){return Optional.empty();}public SSLContext sslContext(){throw new UnsupportedOperationException();}public SSLParameters sslParameters(){return new SSLParameters();}public Optional<Authenticator> authenticator(){return Optional.empty();}public Version version(){return Version.HTTP_1_1;}public Optional<Executor> executor(){return Optional.empty();}
        public <T>HttpResponse<T>send(HttpRequest request,HttpResponse.BodyHandler<T>handler){throw new UnsupportedOperationException();}public <T>CompletableFuture<HttpResponse<T>>sendAsync(HttpRequest request,HttpResponse.BodyHandler<T>handler,HttpResponse.PushPromiseHandler<T>push){throw new UnsupportedOperationException();}
    }
}
