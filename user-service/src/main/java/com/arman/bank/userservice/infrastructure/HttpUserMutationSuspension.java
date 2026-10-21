package com.arman.bank.userservice.infrastructure;
import com.arman.bank.userservice.application.*;
import com.arman.bank.userservice.domain.LiveUserRevision;
import java.net.URI;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.io.ByteArrayOutputStream;
public final class HttpUserMutationSuspension implements UserMutationSuspensionPort,AutoCloseable {
    public static final String PATH="/v1/internal/migrations/legacy/user-mutation-suspend",HEADER="X-ArMoney-Migration-User-Mutation-Key";
    private final LiveUserRevisionConfiguration configuration;private final URI endpoint;private final HttpClient http;private final Semaphore slots=new Semaphore(16);private volatile boolean closed;
    private final Set<CompletableFuture<?>> pendingRequests=ConcurrentHashMap.newKeySet();private final Set<BoundedBody> bodies=ConcurrentHashMap.newKeySet();
    public HttpUserMutationSuspension(LiveUserRevisionConfiguration configuration){if(!configuration.enabled())throw new IllegalArgumentException("Live User disabled");this.configuration=configuration;endpoint=configuration.authOrigin().resolve(PATH);http=HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(2)).followRedirects(HttpClient.Redirect.NEVER).build();}
    @Override public Map<String,Object> suspend(String writer,Map<String,Object> input,long started){
        if(closed||!configuration.mutationWriter().equals(writer)||!slots.tryAcquire())throw unavailable();CompletableFuture<HttpResponse<byte[]>> pending=null;var body=new BoundedBody();
        try{var request=LiveUserRevision.suspensionRequest(writer,input);long left=remaining(started);bodies.add(body);if(closed)throw unavailable();pending=http.sendAsync(HttpRequest.newBuilder(endpoint).timeout(Duration.ofNanos(left)).header("Content-Type","application/json").header(HEADER,configuration.mutationKey()).POST(HttpRequest.BodyPublishers.ofByteArray(LiveUserCurrentRoutes.encode(request))).build(),info->body);pendingRequests.add(pending);body.attach(pending);if(closed)throw unavailable();
            var response=pending.get(remaining(started),TimeUnit.NANOSECONDS);remaining(started);
            if(response.statusCode()!=200||!response.headers().firstValue("Content-Type").orElse("").split(";",2)[0].strip().equalsIgnoreCase("application/json"))throw unavailable();
            return LiveUserRevision.suspensionResponse(request,LiveUserCurrentRoutes.parse(response.body()));
        }catch(Exception e){if(e instanceof InterruptedException)Thread.currentThread().interrupt();throw unavailable();}finally{if(pending!=null)pending.cancel(true);body.abort();bodies.remove(body);if(pending!=null)pendingRequests.remove(pending);slots.release();}
    }
    static long remaining(long start){long elapsed=System.nanoTime()-start;if(elapsed<0||elapsed>=4_000_000_000L)throw unavailable();return 4_000_000_000L-elapsed;}
    private static LiveUserCurrentStore.Rejected unavailable(){return new LiveUserCurrentStore.Rejected(503);}
    private static final class BoundedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> result=new CompletableFuture<>();private final ByteArrayOutputStream bytes=new ByteArrayOutputStream();private Flow.Subscription subscription;private CompletableFuture<?> transport;private boolean ended,failed;
        @Override public CompletionStage<byte[]> getBody(){return result;}
        synchronized void attach(CompletableFuture<?> future){transport=future;if(failed){transport.cancel(true);result.completeExceptionally(unavailable());}}
        @Override public synchronized void onSubscribe(Flow.Subscription s){if(ended||subscription!=null){s.cancel();return;}subscription=s;s.request(1);}
        @Override public synchronized void onNext(List<ByteBuffer> items){if(ended)return;try{for(ByteBuffer item:items){if(item.remaining()>4096-bytes.size()){abort();return;}byte[] part=new byte[item.remaining()];item.get(part);bytes.writeBytes(part);}subscription.request(1);}catch(RuntimeException e){abort();}}
        @Override public synchronized void onError(Throwable ignored){abort();}@Override public synchronized void onComplete(){if(!ended){ended=true;result.complete(bytes.toByteArray());bytes.reset();}}
        synchronized void abort(){if(ended)return;ended=true;failed=true;bytes.reset();if(subscription!=null)subscription.cancel();if(transport!=null){transport.cancel(true);result.completeExceptionally(unavailable());}}
    }
    @Override public void close(){closed=true;pendingRequests.forEach(f->f.cancel(true));bodies.forEach(BoundedBody::abort);http.shutdownNow();try{if(!http.awaitTermination(Duration.ofSeconds(2)))throw new IllegalStateException("Live User client termination unavailable");}catch(InterruptedException e){Thread.currentThread().interrupt();}}
    @Override public String toString(){return "HttpUserMutationSuspension[credentials=redacted]";}
}
