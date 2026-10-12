package com.arman.bank.paymentservice.infrastructure;
import java.io.IOException;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.Flow;

public final class BoundedEmailHttp implements AutoCloseable {
    private final ExecutorService executor = new ThreadPoolExecutor(2,2,0,TimeUnit.MILLISECONDS,
        new ArrayBlockingQueue<>(32),new ThreadPoolExecutor.AbortPolicy());
    private final HttpClient client=HttpClient.newBuilder().executor(executor).connectTimeout(Duration.ofSeconds(2))
        .followRedirects(HttpClient.Redirect.NEVER).build();
    public MailtrapDelivery.Response exchange(HttpRequest request) throws Exception {
        var future=client.sendAsync(request, info -> new LimitedBody());
        try {
            var response=future.get(6,TimeUnit.SECONDS);
            return new MailtrapDelivery.Response(response.statusCode(),response.body());
        } catch (InterruptedException e) { future.cancel(true); Thread.currentThread().interrupt(); throw e; }
          catch (Exception e) { future.cancel(true); throw new IOException("Email HTTP unavailable"); }
    }
    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final HttpResponse.BodySubscriber<byte[]> delegate = HttpResponse.BodySubscribers.ofByteArray();
        private Flow.Subscription subscription;
        private int size;
        private boolean failed;
        public CompletionStage<byte[]> getBody() { return delegate.getBody(); }
        public void onSubscribe(Flow.Subscription subscription) { this.subscription = subscription; delegate.onSubscribe(subscription); }
        public void onNext(List<ByteBuffer> items) {
            if (failed) return;
            for (var item : items) {
                if (item.remaining() > 8192 - size) {
                    failed = true; subscription.cancel(); delegate.onError(new IOException("Peer response exceeds limit")); return;
                }
                size += item.remaining();
            }
            delegate.onNext(items);
        }
        public void onError(Throwable error) { if (!failed) delegate.onError(error); }
        public void onComplete() { if (!failed) delegate.onComplete(); }
    }
    @Override public void close() {
        client.shutdownNow(); executor.shutdownNow();
        try { executor.awaitTermination(5,TimeUnit.SECONDS); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
