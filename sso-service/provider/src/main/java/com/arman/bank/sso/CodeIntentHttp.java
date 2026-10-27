package com.arman.bank.sso;

import com.fasterxml.jackson.databind.JsonNode;
import org.keycloak.util.JsonSerialization;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

/** Unwired private transport. Cancellation bounds the authorizing wait, not physical DNS work. */
final class CodeIntentHttp implements AutoCloseable {
    private static final org.jboss.logging.Logger LOG=org.jboss.logging.Logger.getLogger(CodeIntentHttp.class);
    enum FailureCategory { PRE_REQUEST,TRANSPORT,TIMEOUT,INTERRUPTED,PROTOCOL,HTTP_400,HTTP_401,HTTP_409,HTTP_410,HTTP_429,HTTP_503,HTTP_OTHER }
    static FailureCategory statusCategory(int status){return switch(status){
        case 400->FailureCategory.HTTP_400;case 401->FailureCategory.HTTP_401;case 409->FailureCategory.HTTP_409;
        case 410->FailureCategory.HTTP_410;case 429->FailureCategory.HTTP_429;case 503->FailureCategory.HTTP_503;
        default->FailureCategory.HTTP_OTHER;
    };}
    enum Operation {
        STAGE("/internal/sso-code-intents","X-ArMoney-Login-Proof-Write-Key"),
        KEY("/internal/sso-code-intents/key","X-ArMoney-Code-Intent-Lookup-Key"),
        CONFIRM("/internal/sso-code-intents/confirm","X-ArMoney-Login-Proof-Confirm-Key");
        final String path,header;
        Operation(String path,String header) { this.path=path; this.header=header; }
    }
    private final URI base;
    private final HttpClient client;
    private final boolean ownsClient;
    private final java.util.concurrent.atomic.AtomicBoolean closed=new java.util.concurrent.atomic.AtomicBoolean();
    CodeIntentHttp(URI base) { this(checkedBase(base),HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(2)).followRedirects(HttpClient.Redirect.NEVER)
            .proxy(new ProxySelector() {
                @Override public List<Proxy> select(URI ignored) { return List.of(Proxy.NO_PROXY); }
                @Override public void connectFailed(URI ignored,SocketAddress address,java.io.IOException failure) { }
            }).build(),true); }
    CodeIntentHttp(URI base,HttpClient client) {
        this(base,client,false);
    }
    CodeIntentHttp(URI base,HttpClient client,boolean ownsClient) {
        checkedBase(base);
        this.base=base; this.client=Objects.requireNonNull(client); this.ownsClient=ownsClient;
        if(client.followRedirects()!=HttpClient.Redirect.NEVER||client.version()!=HttpClient.Version.HTTP_1_1
                ||client.connectTimeout().isEmpty()||client.connectTimeout().get().compareTo(Duration.ofSeconds(2))>0) {
            if(ownsClient) client.shutdownNow(); throw new Failure();
        }
    }
    private static URI checkedBase(URI base) {
        if(base==null||base.getHost()==null||base.getUserInfo()!=null||base.getQuery()!=null||base.getFragment()!=null
                ||!(base.getPath().isEmpty()||base.getPath().equals("/"))||base.getPort()==0||base.getPort() < -1||base.getPort()>65535
                ||!("https".equals(base.getScheme())||"http".equals(base.getScheme())&&"auth-service".equals(base.getHost()))) throw new Failure();
        return base;
    }
    JsonNode post(Operation operation,Map<String,Object> fields,String credential,InvocationDeadline deadline) {
        CompletableFuture<HttpResponse<byte[]>> future=null;
        InvocationDeadline.Registration exchange=null;
        var bodyRegistration=new AtomicReference<InvocationDeadline.Registration>();
        boolean complete=false;FailureCategory failureCategory=FailureCategory.PRE_REQUEST;
        try {
            if(closed.get()) throw new Failure();
            deadline.requireOpen();
            byte[] bytes=JsonSerialization.writeValueAsBytes(fields);
            if(bytes.length>12288) throw new Failure();
            var request=HttpRequest.newBuilder(base.resolve(operation.path)).version(HttpClient.Version.HTTP_1_1)
                    .timeout(deadline.remaining()).header("Content-Type","application/json").header(operation.header,credential)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(bytes)).build();
            deadline.requireOpen();
            if(closed.get()) throw new Failure();
            failureCategory=FailureCategory.TRANSPORT;
            future=client.sendAsync(request,info->{
                var subscriber=new Bridge.LimitedBody();
                try { bodyRegistration.set(deadline.registerBody(subscriber::cancel)); }
                catch(RuntimeException unavailable) { subscriber.cancel(); }
                return subscriber;
            });
            exchange=deadline.registerExchange(future);
            var response=future.get(deadline.remaining().toNanos(),TimeUnit.NANOSECONDS);
            failureCategory=response.statusCode()==200?FailureCategory.PROTOCOL:statusCategory(response.statusCode());
            deadline.requireOpen();
            if(closed.get()||response.version()!=HttpClient.Version.HTTP_1_1||response.statusCode()!=200||response.body()==null||response.body().length>12288
                    ||!single(response,"Cache-Control","no-store")||!jsonType(response)) throw new Failure();
            JsonNode result=Bridge.strictJson(response.body());
            deadline.requireOpen(); if(closed.get()) throw new Failure(); complete=true; return result;
        } catch(InterruptedException interrupted) { failureCategory=FailureCategory.INTERRUPTED;Thread.currentThread().interrupt(); throw new Failure(); }
        catch(Exception unavailable) {
            if(unavailable instanceof TimeoutException||unavailable instanceof HttpTimeoutException
                    ||unavailable instanceof ExecutionException execution&&execution.getCause() instanceof HttpTimeoutException)failureCategory=FailureCategory.TIMEOUT;
            throw new Failure();
        }
        finally {
            if(!complete&&operation==Operation.STAGE)LOG.warnf("armoney_code_intent_stage_failure category=%s",failureCategory);
            if(!complete) { deadline.cancelOwnedExchange(); if(future!=null) future.cancel(true); }
            if(exchange!=null) exchange.close();
            var registered=bodyRegistration.get(); if(registered!=null) registered.close();
        }
    }
    @Override public void close() { if(closed.compareAndSet(false,true)&&ownsClient) client.shutdownNow(); }
    private static boolean single(HttpResponse<?> response,String header,String expected) {
        var values=response.headers().allValues(header); return values.size()==1&&values.getFirst().equals(expected);
    }
    private static boolean jsonType(HttpResponse<?> response) {
        var values=response.headers().allValues("Content-Type");
        return values.size()==1&&values.getFirst().split(";",2)[0].strip().equalsIgnoreCase("application/json");
    }
    static final class Failure extends IllegalStateException { Failure() { super("Code intent unavailable"); } }
}
