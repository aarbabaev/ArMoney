package com.arman.bank.authservice.infrastructure;
import com.arman.bank.authservice.application.LoginNotificationProfiles;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.core.JsonParser;
import java.net.URI;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
/** Existing private owner profile read, optional presentation only; no recipient authority. */
public final class HttpLoginNotificationProfiles implements LoginNotificationProfiles,AutoCloseable {
    private static final ObjectMapper JSON=new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final URI endpoint;private final String key;private final HttpClient client;private volatile boolean closed;
    private final Set<CompletableFuture<?>> pending=ConcurrentHashMap.newKeySet();
    public HttpLoginNotificationProfiles(URI origin,String key){this(origin,key,HttpCodeSessionObservations.privateClient());}
    HttpLoginNotificationProfiles(URI origin,String key,HttpClient client){
        if(origin==null||!Set.of("http","https").contains(origin.getScheme())||origin.getHost()==null||origin.getRawUserInfo()!=null||origin.getRawQuery()!=null||origin.getRawFragment()!=null||!Set.of("","/").contains(origin.getRawPath())||key==null||!key.matches("[!-~]{32,256}")||client.followRedirects()!=HttpClient.Redirect.NEVER)throw new IllegalArgumentException("Invalid notice profile configuration");
        endpoint=origin.resolve("/v1/users/me");this.key=key;this.client=client;
    }
    @Override public String displayName(UUID owner)throws InterruptedException{
        if(owner==null||closed)return null;if(Thread.currentThread().isInterrupted())throw new InterruptedException();
        CompletableFuture<HttpResponse<byte[]>> future=null;byte[] bytes=null;Body body=new Body();
        try{
            var request=HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(3)).header("X-Service-Key",key).header("X-Identity-Id",owner.toString()).GET().build();
            future=client.sendAsync(request,info->body);pending.add(future);if(closed)future.cancel(true);
            var reply=future.get(3,TimeUnit.SECONDS);bytes=reply.body();
            var types=reply.headers().allValues("Content-Type");if(reply.statusCode()!=200||types.size()!=1||!types.getFirst().matches("(?i)application/json(?:\\s*;\\s*charset=utf-8)?"))return null;
            var json=JSON.readTree(bytes);if(json==null||!json.isObject()||!json.path("identity_id").isTextual()||!owner.toString().equalsIgnoreCase(json.path("identity_id").textValue())||!json.path("display_name").isTextual())return null;
            return closed?null:LoginEmail.name(json.path("display_name").textValue());
        }catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw interrupted;}
        catch(Exception unavailable){return null;}
        finally{body.cancel();if(future!=null){pending.remove(future);future.cancel(true);}if(bytes!=null)Arrays.fill(bytes,(byte)0);}
    }
    private static final class Body implements HttpResponse.BodySubscriber<byte[]>{
        private final CompletableFuture<byte[]> result=new CompletableFuture<>();private final byte[] bytes=new byte[16384];private int count;private java.util.concurrent.Flow.Subscription subscription;
        public CompletionStage<byte[]> getBody(){return result;}
        public synchronized void onSubscribe(java.util.concurrent.Flow.Subscription value){if(subscription!=null||result.isDone()){value.cancel();return;}subscription=value;value.request(1);}
        public synchronized void onNext(List<ByteBuffer> buffers){if(result.isDone())return;for(var buffer:buffers){int length=buffer.remaining();if(length>bytes.length-count){cancel();return;}buffer.get(bytes,count,length);count+=length;}subscription.request(1);}
        public synchronized void onError(Throwable ignored){cancel();}
        public synchronized void onComplete(){result.complete(Arrays.copyOf(bytes,count));Arrays.fill(bytes,(byte)0);}
        synchronized void cancel(){if(subscription!=null)subscription.cancel();result.completeExceptionally(new IllegalStateException("Profile unavailable"));Arrays.fill(bytes,(byte)0);}
    }
    @Override public void close(){closed=true;pending.forEach(future->future.cancel(true));client.shutdownNow();}
    @Override public String toString(){return "LoginNotificationProfiles[redacted]";}
}
