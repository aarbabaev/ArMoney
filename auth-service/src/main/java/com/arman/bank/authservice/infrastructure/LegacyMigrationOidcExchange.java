package com.arman.bank.authservice.infrastructure;

import java.io.ByteArrayOutputStream;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.Flow;

/** Owns cancellation of the pending exchange AND the bounded body subscription. */
final class LegacyMigrationOidcExchange {
    private final CompletableFuture<HttpResponse<byte[]>> future;
    private final Body body;
    LegacyMigrationOidcExchange(CompletableFuture<HttpResponse<byte[]>> future,Body body){this.future=future;this.body=body;}
    void cancel(){body.cancel();future.cancel(true);}
    static final class Body implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> result=new CompletableFuture<>();
        private final ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        private Flow.Subscription subscription;
        private boolean cancelled;
        public CompletionStage<byte[]> getBody(){return result;}
        public synchronized void onSubscribe(Flow.Subscription value){
            if(cancelled||subscription!=null){value.cancel();return;}subscription=value;value.request(1);
        }
        public synchronized void onNext(List<ByteBuffer> values){
            if(cancelled)return;
            for(ByteBuffer value:values){
                if(value.remaining()>65536-bytes.size()){cancel();return;}
                byte[] chunk=new byte[value.remaining()];value.get(chunk);bytes.writeBytes(chunk);
            }
            if(subscription!=null)subscription.request(1);
        }
        public synchronized void onError(Throwable ignored){cancel();}
        public synchronized void onComplete(){if(!cancelled){result.complete(bytes.toByteArray());bytes.reset();}}
        synchronized void cancel(){cancelled=true;if(subscription!=null)subscription.cancel();bytes.reset();result.completeExceptionally(new LegacyMigrationOidcClient.Unavailable());}
    }
}
