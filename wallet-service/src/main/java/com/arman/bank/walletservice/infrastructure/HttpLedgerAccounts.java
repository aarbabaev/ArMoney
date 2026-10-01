package com.arman.bank.walletservice.infrastructure;
import com.arman.bank.runtime.InternalHttp;
import com.arman.bank.walletservice.application.LedgerAccounts;
import com.arman.bank.walletservice.application.LedgerBalances;
import com.arman.bank.walletservice.domain.Wallet;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.Flow;

/** A bounded private client. Configuration, never request data, selects the destination. */
public final class HttpLedgerAccounts implements LedgerAccounts, LedgerBalances, AutoCloseable {
    private final URI endpoint;
    private final String key;
    private final HttpClient client;
    private final Semaphore balanceSlots = new Semaphore(16);
    public HttpLedgerAccounts(String baseUrl, String key) {
        var base = URI.create(baseUrl);
        if (!("http".equals(base.getScheme()) || "https".equals(base.getScheme())) || base.getHost() == null
                || base.getUserInfo() != null || base.getQuery() != null || base.getFragment() != null
                || !(base.getPath().isEmpty() || base.getPath().equals("/")))
            throw new IllegalArgumentException("Invalid LEDGER_BASE_URL origin");
        if (key == null || key.length() < 32) throw new IllegalArgumentException("Invalid internal key");
        endpoint = base.resolve("/v1/ledger/accounts");
        this.key = key;
        client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).followRedirects(HttpClient.Redirect.NEVER).build();
    }
    @Override public UUID provision(Wallet wallet) throws Exception {
        var body = InternalHttp.JSON.writeValueAsString(Map.of("wallet_id", wallet.id().toString(), "currency", wallet.currency()));
        var request = HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(5))
            .header("Content-Type", "application/json").header("X-Service-Key", key)
            .header("X-Identity-Id", wallet.ownerId().toString()).POST(HttpRequest.BodyPublishers.ofString(body)).build();
        var future = client.sendAsync(request, response -> new LimitedBody());
        final HttpResponse<byte[]> response;
        try { response = future.get(6, TimeUnit.SECONDS); }
        catch (InterruptedException | ExecutionException | TimeoutException e) { future.cancel(true); throw e; }
        if (response.statusCode() != 200) throw new IOException("Ledger provisioning unavailable");
        if (!response.headers().firstValue("Content-Type").orElse("").split(";", 2)[0].strip().equalsIgnoreCase("application/json"))
            throw new IOException("Invalid ledger content type");
        JsonNode json = InternalHttp.JSON.readTree(response.body());
        if (json == null || !json.isObject() || !wallet.id().equals(uuid(json, "wallet_id"))
                || !wallet.ownerId().equals(uuid(json, "owner_id"))
                || !json.path("currency").isTextual() || !wallet.currency().equals(json.path("currency").textValue()))
            throw new IOException("Ledger account mapping mismatch");
        // Replay may return a funded account: balance is not wallet's authority or a readiness condition.
        return uuid(json, "id");
    }
    private static UUID uuid(JsonNode json, String field) throws IOException {
        var value = json.get(field);
        if (value == null || !value.isTextual()) throw new IOException("Invalid ledger UUID");
        try {
            var id = UUID.fromString(value.textValue());
            if (!id.toString().equalsIgnoreCase(value.textValue())) throw new IllegalArgumentException();
            return id;
        } catch (IllegalArgumentException e) { throw new IOException("Invalid ledger UUID"); }
    }
    @Override public long balance(Wallet wallet) throws Exception {
        if (!"ACTIVE".equals(wallet.status()) || !"READY".equals(wallet.provisioningStatus())) throw new IOException("Wallet not ready");
        if (!balanceSlots.tryAcquire()) throw new IOException("Ledger client busy");
        try {
            var request = HttpRequest.newBuilder(URI.create(endpoint.toString() + "/" + wallet.ledgerAccountId()))
                .timeout(Duration.ofSeconds(5)).header("X-Service-Key", key)
                .header("X-Identity-Id", wallet.ownerId().toString()).GET().build();
            var future = client.sendAsync(request, response -> new LimitedBody());
            final HttpResponse<byte[]> response;
            try { response = future.get(6, TimeUnit.SECONDS); }
            catch (InterruptedException | ExecutionException | TimeoutException e) { future.cancel(true); throw e; }
            if (response.statusCode() != 200 || !response.headers().firstValue("Content-Type").orElse("").split(";", 2)[0].strip().equalsIgnoreCase("application/json"))
                throw new IOException("Ledger balance unavailable");
            var json = InternalHttp.JSON.readTree(response.body());
            if (json == null || !json.isObject() || !wallet.ledgerAccountId().equals(uuid(json, "id"))
                    || !wallet.id().equals(uuid(json, "wallet_id")) || !wallet.ownerId().equals(uuid(json, "owner_id"))
                    || !json.path("currency").isTextual() || !wallet.currency().equals(json.path("currency").textValue())
                    || !json.path("balance_minor").isIntegralNumber() || !json.path("balance_minor").canConvertToLong()
                    || json.path("balance_minor").longValue() < 0)
                throw new IOException("Invalid ledger balance");
            return json.path("balance_minor").longValue();
        } finally { balanceSlots.release(); }
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
                if (item.remaining() > 4096 - size) {
                    failed = true;
                    subscription.cancel();
                    delegate.onError(new IOException("Ledger response exceeds limit"));
                    return;
                }
                size += item.remaining();
            }
            delegate.onNext(items);
        }
        public void onError(Throwable error) { if (!failed) delegate.onError(error); }
        public void onComplete() { if (!failed) delegate.onComplete(); }
    }
    @Override public void close() { client.close(); }
}
