package com.arman.bank.sso;

import com.fasterxml.jackson.databind.JsonNode;
import org.keycloak.util.JsonSerialization;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

/** Disposable-only transport. No production endpoint or credential is repurposed. */
public final class CodeIntentFixtureTransport implements AutoCloseable {
    enum Call {
        PREPARE("/fixture/code-intent-authority/prepare",202), STATE("/fixture/code-intent-authority/state",200),
        READINESS("/fixture/code-intent-authority/readiness",200), CANCEL("/fixture/code-intent-authority/cancel",204),
        MEMBERSHIP("/sso/realms/armoney/armoney-code-intent-fixture/membership",200);
        final String path; final int status; Call(String path,int status){this.path=path;this.status=status;}
    }
    private final HttpClient client;
    private final String providerKey,peerKey;
    private volatile boolean closed;
    public CodeIntentFixtureTransport(String providerKey,String peerKey) {
        this(checked(providerKey,peerKey),peerKey,HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(2))
                .followRedirects(HttpClient.Redirect.NEVER).proxy(new ProxySelector(){
                    public List<Proxy> select(URI uri){return List.of(Proxy.NO_PROXY);}
                    public void connectFailed(URI uri,SocketAddress address,java.io.IOException failure){}
                }).build());
    }
    CodeIntentFixtureTransport(String providerKey,String peerKey,HttpClient client) {
        CodeIntentFixtureSupport.credential(providerKey);CodeIntentFixtureSupport.credential(peerKey);
        if(providerKey.equals(peerKey))throw new CodeIntentFixtureSupport.Rejected(503);
        this.providerKey=providerKey;this.peerKey=peerKey;this.client=Objects.requireNonNull(client);
    }
    private static String checked(String providerKey,String peerKey){CodeIntentFixtureSupport.credential(providerKey);CodeIntentFixtureSupport.credential(peerKey);if(providerKey.equals(peerKey))throw new CodeIntentFixtureSupport.Rejected(503);return providerKey;}
    JsonNode call(Call call,Map<String,Object> payload,InvocationDeadline deadline) {
        CompletableFuture<HttpResponse<byte[]>> future=null;InvocationDeadline.Registration exchange=null;
        var body=new AtomicReference<InvocationDeadline.Registration>();boolean completed=false;
        try {
            deadline.requireOpen();if(closed)throw new IllegalStateException();
            byte[] bytes=JsonSerialization.writeValueAsBytes(payload);if(bytes.length>12288)throw new IllegalArgumentException();
            boolean peer=call==Call.MEMBERSHIP;
            URI destination=URI.create((peer?"http://sso-service:8080":"http://auth-code-intent-fixture:8091")+call.path);
            var request=HttpRequest.newBuilder(destination).version(HttpClient.Version.HTTP_1_1).timeout(deadline.remaining())
                    .header("Content-Type","application/json").header(peer?"X-ArMoney-Fixture-Peer-Key":"X-ArMoney-Code-Intent-Fixture-Provider-Key",peer?peerKey:providerKey)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(bytes)).build();
            deadline.requireOpen();
            future=client.sendAsync(request,info->{var subscriber=new Bridge.LimitedBody();
                try{body.set(deadline.registerBody(subscriber::cancel));}catch(RuntimeException expired){subscriber.cancel();}return subscriber;});
            exchange=deadline.registerExchange(future);
            var response=future.get(deadline.remaining().toNanos(),TimeUnit.NANOSECONDS);deadline.requireOpen();
            if(closed||response.statusCode()!=call.status||response.version()!=HttpClient.Version.HTTP_1_1
                    ||!response.headers().allValues("Cache-Control").equals(List.of("no-store"))||response.body().length>12288)throw new IllegalStateException();
            if(call==Call.CANCEL){if(response.body().length!=0)throw new IllegalStateException();completed=true;return null;}
            var types=response.headers().allValues("Content-Type");
            if(types.size()!=1||!types.getFirst().split(";",2)[0].strip().equalsIgnoreCase("application/json"))throw new IllegalStateException();
            JsonNode result=CodeIntentFixtureResource.strictUtf8(response.body());deadline.requireOpen();completed=true;return result;
        }catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new CodeIntentFixtureSupport.Rejected(503);}
        catch(Exception unavailable){throw new CodeIntentFixtureSupport.Rejected(503);}
        finally{if(!completed){deadline.cancelOwnedExchange();if(future!=null)future.cancel(true);}if(exchange!=null)exchange.close();var registration=body.get();if(registration!=null)registration.close();}
    }
    @Override public synchronized void close(){if(closed)return;closed=true;client.shutdownNow();}
}
