package com.arman.bank.paymentservice.infrastructure;

import com.arman.bank.paymentservice.application.*;
import com.arman.bank.paymentservice.domain.*;
import com.arman.bank.runtime.InternalHttp;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.Flow;

public final class HttpPaymentPeers implements PaymentPeers, AutoCloseable {
    private final URI users, wallets, ledger;
    private final String key;
    private final ExecutorService executor = new ThreadPoolExecutor(4, 4, 0, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(128), new ThreadPoolExecutor.AbortPolicy());
    private final Semaphore requests = new Semaphore(9);
    private final HttpClient client;

    public HttpPaymentPeers(String users, String wallets, String ledger, String key) {
        this.users = origin(users); this.wallets = origin(wallets); this.ledger = origin(ledger);
        if (key == null || key.length() < 32 || key.length() > 256) throw new IllegalArgumentException("Invalid internal key");
        this.key = key;
        client = HttpClient.newBuilder().executor(executor).connectTimeout(Duration.ofSeconds(2))
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }
    private static URI origin(String value) {
        var uri = URI.create(value);
        if (!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme())) || uri.getHost() == null
                || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                || !(uri.getPath().isEmpty() || uri.getPath().equals("/"))) throw new IllegalArgumentException("Invalid peer origin");
        return uri;
    }

    @Override public Mapping resolve(UUID requester, PaymentRequest request) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        var recipient = call(users.resolve("/v1/users/resolve-phone"), requester,
                Map.of("phone_number", request.recipientPhone()), deadline);
        accepted(recipient);
        var json = recipient.json();
        if (!request.recipientPhone().equals(text(json, "phone_number")) || !json.path("display_name").isTextual())
            throw new IOException("Invalid recipient response");
        if (!request.recipientId().equals(uuid(json, "identity_id"))) throw new PaymentFailure(409, "recipient_changed");
        var source = call(wallets.resolve("/v1/internal/wallets/" + request.sourceWalletId()), requester, null, deadline);
        accepted(source);
        var debit = wallet(source.json(), requester, request.sourceWalletId(), request.currency());
        var destination = call(wallets.resolve("/v1/internal/wallets/by-owner/" + request.recipientId() + "/currency/" + request.currency()), requester, null, deadline);
        accepted(destination);
        UUID destinationId = uuid(destination.json(), "id");
        UUID credit = wallet(destination.json(), request.recipientId(), destinationId, request.currency());
        if (destinationId.equals(request.sourceWalletId()) || credit.equals(debit)) throw new IOException("Invalid account mapping");
        return new Mapping(destinationId, debit, credit);
    }
    private static void accepted(Response response) throws IOException {
        if (response.status() == 404) throw new PaymentFailure(404, "not_found");
        if (response.status() == 409) throw new PaymentFailure(409, "wallet_ineligible");
        if (response.status() != 200) throw new IOException("Peer unavailable");
    }
    private static UUID wallet(JsonNode json, UUID owner, UUID id, String currency) throws IOException {
        if (!id.equals(uuid(json, "id")) || !owner.equals(uuid(json, "owner_id")))
            throw new PaymentFailure(404, "not_found");
        if (!currency.equals(text(json, "currency"))) throw new PaymentFailure(409, "wallet_ineligible");
        if (!"ACTIVE".equals(text(json, "status")) || !"READY".equals(text(json, "provisioning_status")))
            throw new PaymentFailure(409, "wallet_ineligible");
        return uuid(json, "ledger_account_id");
    }
    @Override public Outcome post(Payment payment) throws Exception {
        var request = payment.request();
        var response = call(ledger.resolve("/v1/ledger/transfers"), payment.requesterId(), Map.of(
                "payment_id", payment.id().toString(), "debit_account_id", payment.debitAccountId().toString(),
                "credit_account_id", payment.creditAccountId().toString(), "currency", request.currency(),
                "amount_minor", request.amountMinor()), System.nanoTime() + TimeUnit.SECONDS.toNanos(6));
        if (response.status() != 200 && response.status() != 409) throw new IOException("Ledger unavailable");
        var json = response.json();
        if (!payment.id().equals(uuid(json, "payment_id")) || !payment.debitAccountId().equals(uuid(json, "debit_account_id"))
                || !payment.creditAccountId().equals(uuid(json, "credit_account_id")) || !request.currency().equals(text(json, "currency"))
                || !json.path("amount_minor").isIntegralNumber() || !json.path("amount_minor").canConvertToLong()
                || request.amountMinor() != json.path("amount_minor").longValue()) throw new IOException("Ledger result mismatch");
        final Outcome outcome;
        try { outcome = Outcome.valueOf(text(json, "outcome")); }
        catch (IllegalArgumentException e) { throw new IOException("Unknown ledger outcome"); }
        if ((outcome == Outcome.POSTED) != (response.status() == 200)) throw new IOException("Invalid ledger status");
        return outcome;
    }
    private record Response(int status, JsonNode json) {}
    private Response call(URI uri, UUID owner, Object body, long deadline) throws Exception {
        if (!requests.tryAcquire()) throw new IOException("Peer capacity exceeded");
        try {
            long remaining = Math.min(TimeUnit.SECONDS.toNanos(6), deadline - System.nanoTime());
            if (remaining <= 0) throw new IOException("Peer deadline exceeded");
            var builder = HttpRequest.newBuilder(uri).timeout(Duration.ofNanos(remaining))
                    .header("X-Service-Key", key).header("X-Identity-Id", owner.toString()).header("Accept", "application/json");
            if (body == null) builder.GET();
            else builder.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(InternalHttp.JSON.writeValueAsString(body)));
            var future = client.sendAsync(builder.build(), response -> new LimitedBody());
            final HttpResponse<byte[]> response;
            try { response = future.get(remaining, TimeUnit.NANOSECONDS); }
            catch (InterruptedException | ExecutionException | TimeoutException e) { future.cancel(true); throw e; }
            if (!response.headers().firstValue("Content-Type").orElse("").split(";", 2)[0].strip().equalsIgnoreCase("application/json"))
                throw new IOException("Invalid peer content type");
            var json = InternalHttp.JSON.readTree(response.body());
            if (json == null || !json.isObject()) throw new IOException("Invalid peer response");
            return new Response(response.statusCode(), json);
        } finally { requests.release(); }
    }
    private static String text(JsonNode json, String field) throws IOException {
        if (!json.path(field).isTextual()) throw new IOException("Invalid peer field");
        return json.path(field).textValue();
    }
    private static UUID uuid(JsonNode json, String field) throws IOException {
        String value = text(json, field);
        try {
            var id = UUID.fromString(value);
            if (!id.toString().equalsIgnoreCase(value)) throw new IllegalArgumentException();
            return id;
        } catch (IllegalArgumentException e) { throw new IOException("Invalid peer UUID"); }
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
        client.shutdownNow();
        executor.shutdownNow();
        try { executor.awaitTermination(5, TimeUnit.SECONDS); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
