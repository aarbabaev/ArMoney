package com.arman.bank.sso;

import java.io.IOException;
import java.net.http.*;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Deadline includes headers and body. No helper executor or blocking orphan task is created. */
final class DeadlineHttp {
    static HttpResponse<byte[]> send(HttpClient client, HttpRequest request) throws IOException, InterruptedException {
        Duration timeout=request.timeout().orElseThrow(()->new IllegalArgumentException("Missing total deadline"));
        if (timeout.isZero() || timeout.isNegative() || timeout.compareTo(Duration.ofSeconds(4))>0)
            throw new IllegalArgumentException("Invalid total deadline");
        long deadline=System.nanoTime()+timeout.toNanos();
        var aborted=new AtomicBoolean(); var body=new AtomicReference<Bridge.LimitedBody>();
        CompletableFuture<HttpResponse<byte[]>> exchange=null;
        boolean completed=false;
        try {
            exchange=client.sendAsync(request,info->{
                var subscriber=new Bridge.LimitedBody(); body.set(subscriber);
                if(aborted.get()) subscriber.cancel();
                return subscriber;
            });
            long remaining=deadline-System.nanoTime();
            if(remaining<=0) throw new TimeoutException();
            HttpResponse<byte[]> response=exchange.get(remaining,TimeUnit.NANOSECONDS);
            completed=true; return response;
        } catch(TimeoutException timeoutFailure) {
            throw new HttpTimeoutException("Private HTTP deadline exceeded");
        } catch(ExecutionException failure) {
            if(failure.getCause() instanceof InterruptedException interrupted) throw interrupted;
            throw new IOException("Private HTTP exchange failed");
        } finally {
            if(!completed) {
                aborted.set(true);
                var subscriber=body.get(); if(subscriber!=null) subscriber.cancel();
                if(exchange!=null) exchange.cancel(true);
            }
        }
    }
    private DeadlineHttp() { }
}
