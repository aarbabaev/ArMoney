package com.arman.bank.appgateway;

import com.arman.bank.runtime.InternalHttp;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.javalin.config.JavalinConfig;
import io.javalin.http.Context;
import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.*;

/** Fixed read delegation boundary; no bearer or general service credential reaches the assistant. */
public final class AssistantProxy implements AutoCloseable {
    private final URI auth, wallets, payments, assistant;
    private final String internalKey, outboundKey, inboundKey;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2))
        .followRedirects(HttpClient.Redirect.NEVER).build();
    private final Semaphore turns = new Semaphore(4), reads = new Semaphore(16);
    private final AssistantDelegations delegations = new AssistantDelegations();
    private final AssistantRateLimit quota = new AssistantRateLimit(Clock.systemUTC());

    public AssistantProxy(URI auth, URI wallets, URI payments, String internalKey,
                          URI assistant, String outboundKey, String inboundKey) {
        this.auth = origin(auth); this.wallets = origin(wallets); this.payments = origin(payments);
        this.internalKey = key(internalKey);
        this.assistant = assistant == null ? null : origin(assistant);
        this.outboundKey = assistant == null ? null : key(outboundKey);
        this.inboundKey = assistant == null ? null : key(inboundKey);
        if (assistant != null && (internalKey.equals(outboundKey) || internalKey.equals(inboundKey) || outboundKey.equals(inboundKey)))
            throw new IllegalArgumentException("Assistant credentials must be distinct");
    }
    private static String key(String value) {
        if (value == null || value.length() < 32 || value.length() > 256 || !value.matches("[!-~]+"))
            throw new IllegalArgumentException("A bounded service credential is required");
        return value;
    }
    private static URI origin(URI uri) {
        if (uri == null || !("http".equals(uri.getScheme()) || "https".equals(uri.getScheme())) || uri.getHost() == null
            || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
            || !(uri.getPath().isEmpty() || uri.getPath().equals("/"))) throw new IllegalArgumentException("Expected HTTP origin");
        return uri;
    }
    public void configure(JavalinConfig config) {
        config.routes.post("/v1/assistant/messages", this::message);
        config.routes.get("/v1/internal/assistant/wallets", ctx -> tool(ctx, "wallets"));
        config.routes.get("/v1/internal/assistant/wallets/{id}/balance", ctx -> tool(ctx, "balance"));
        config.routes.get("/v1/internal/assistant/payments/page", ctx -> tool(ctx, "payments"));
    }
    int activeDelegations() { return delegations.size(); }
    private void message(Context ctx) {
        ctx.header("Cache-Control", "no-store");
        AssistantDelegations.Turn turn = null;
        boolean admitted = false;
        long deadline = System.nanoTime() + Duration.ofSeconds(35).toNanos();
        try {
            String bearer = single(ctx, "Authorization");
            if (bearer == null || !bearer.matches("(?i:Bearer) [A-Za-z0-9_-]{43}")) throw failure(401);
            if (ctx.bodyAsBytes().length > 4096) throw failure(413);
            if (ctx.queryString() != null || !jsonType(ctx.contentType())) throw failure(400);
            JsonNode body;
            try { body = InternalHttp.JSON.readTree(ctx.bodyAsBytes()); exact(body, "message"); text(body.get("message"), 1, 2000); }
            catch (Exception e) { throw failure(400); }
            if (body.get("message").textValue().isBlank()) throw failure(400);
            UUID owner = identity(bearer, deadline);
            if (assistant == null) throw failure(503);
            if (!quota.allow(owner)) { ctx.header("Retry-After", "60"); throw failure(429); }
            if (!turns.tryAcquire()) throw failure(429);
            admitted = true;
            turn = delegations.create(bearer, owner, deadline);
            HttpRequest request = HttpRequest.newBuilder(assistant.resolve("/v1/assistant/messages"))
                .header("Content-Type", "application/json").header("X-Gateway-Assistant-Key", outboundKey)
                .header("X-Assistant-Delegation", turn.handle)
                .timeout(remaining(deadline, 30)).POST(HttpRequest.BodyPublishers.ofByteArray(ctx.bodyAsBytes())).build();
            var response = send(request, 32768, deadline, 30);
            UUID finalOwner = identity(bearer, deadline);
            if (!owner.equals(finalOwner)) throw failure(503);
            if (response.statusCode() != 200) {
                int status = response.statusCode();
                // Service-side tool revocation is confirmed by the final auth check above.
                if (status == 429) throw failure(429);
                throw failure(503);
            }
            JsonNode result = parse(response);
            validateReply(result, turn);
            InternalHttp.reply(ctx, 200, result);
        } catch (Rejected e) { error(ctx, e.status); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); error(ctx, 503); }
        catch (Exception e) { error(ctx, 503); }
        finally { if (turn != null) delegations.remove(turn); if (admitted) turns.release(); }
    }
    private void tool(Context ctx, String kind) {
        ctx.header("Cache-Control", "no-store");
        boolean admitted = false;
        try {
            if (assistant == null || !credential(single(ctx, "X-Assistant-Gateway-Key"), inboundKey)) throw failure(401);
            String handle = single(ctx, "X-Assistant-Delegation");
            if (handle == null || !handle.matches("[A-Za-z0-9_-]{43}")) throw failure(401);
            if (Collections.list(ctx.req().getHeaders("Authorization")).size() != 0) throw failure(400);
            if (ctx.bodyAsBytes().length != 0) throw failure(400);
            if (kind.equals("payments") ? !"limit=50".equals(ctx.queryString()) : ctx.queryString() != null) throw failure(400);
            String id = kind.equals("balance") ? uuid(ctx.pathParam("id")).toString() : null;
            var turn = delegations.acquire(handle);
            if (turn == null) throw failure(401);
            if (!reads.tryAcquire()) throw failure(429);
            admitted = true;
            UUID owner = identity(turn.bearer, turn.deadline);
            if (!owner.equals(turn.owner) || !turn.open) throw failure(503);
            String path = switch (kind) {
                case "wallets" -> "/v1/wallets";
                case "balance" -> "/v1/wallets/" + id + "/balance";
                default -> "/v1/payments/page?limit=50";
            };
            URI destination = kind.equals("payments") ? payments : wallets;
            var response = send(HttpRequest.newBuilder(destination.resolve(path)).timeout(remaining(turn.deadline, 8))
                .header("X-Service-Key", internalKey).header("X-Identity-Id", owner.toString()).GET().build(), 131072, turn.deadline, 9);
            if (!turn.open || System.nanoTime() >= turn.deadline) throw failure(401);
            if (response.statusCode() != 200) {
                int status = response.statusCode();
                throw failure(status == 400 || status == 404 || status == 409 || status == 429 ? status : 503);
            }
            JsonNode data = parse(response);
            ObjectNode projected = switch (kind) {
                case "wallets" -> projectWallets(data, owner);
                case "balance" -> projectBalance(data, id, turn);
                default -> projectPayments(data, owner, turn);
            };
            if (!turn.open || System.nanoTime() >= turn.deadline) throw failure(401);
            InternalHttp.reply(ctx, 200, projected);
        } catch (Rejected e) { error(ctx, e.status); }
        catch (IllegalArgumentException e) { error(ctx, 400); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); error(ctx, 503); }
        catch (Exception e) { error(ctx, 503); }
        finally { if (admitted) reads.release(); }
    }
    private UUID identity(String bearer, long deadline) throws Exception {
        var response = send(HttpRequest.newBuilder(auth.resolve("/v1/auth/me")).timeout(remaining(deadline, 5))
            .header("X-Service-Key", internalKey).header("Authorization", bearer).GET().build(), 4096, deadline, 6);
        if (response.statusCode() == 401) throw failure(401);
        if (response.statusCode() != 200) throw failure(503);
        JsonNode data = parse(response);
        if (!data.isObject() || !data.path("id").isTextual()) throw failure(503);
        UUID id;
        try { id = uuid(data.get("id").textValue()); } catch (Exception e) { throw failure(503); }
        var email = data.get("email");
        if (email != null && !email.isNull()) {
            if (!email.isTextual()) throw failure(503);
            String value = email.textValue().strip().toLowerCase(Locale.ROOT);
            if (value.length() > 254 || !value.matches("[a-z0-9.!#$%&'*+/=?^_`{|}~-]+@[a-z0-9](?:[a-z0-9-]*[a-z0-9])?(?:\\.[a-z0-9](?:[a-z0-9-]*[a-z0-9])?)+")) throw failure(503);
        }
        return id;
    }
    private static ObjectNode projectWallets(JsonNode data, UUID owner) {
        exact(data, "wallets"); array(data.get("wallets"), 10);
        ObjectNode result = InternalHttp.JSON.createObjectNode(); var rows = result.putArray("wallets");
        Set<String> ids = new HashSet<>();
        for (JsonNode row : data.get("wallets")) {
            exact(row, "id", "owner_id", "currency", "status", "provisioning_status", "ledger_account_id");
            String id = uuidText(row.get("id"));
            if (!owner.toString().equalsIgnoreCase(uuidText(row.get("owner_id"))) || !ids.add(id)) throw failure(503);
            choice(row.get("currency"), "AED"); choice(row.get("status"), "ACTIVE", "CLOSED");
            choice(row.get("provisioning_status"), "PENDING", "READY");
            if (row.get("provisioning_status").textValue().equals("READY")) uuidText(row.get("ledger_account_id"));
            else if (!row.get("ledger_account_id").isNull()) throw failure(503);
            ObjectNode projected = rows.addObject(); projected.put("id", id);
            for (String field : List.of("currency", "status", "provisioning_status")) projected.set(field, row.get(field));
        }
        return result;
    }
    private static ObjectNode projectBalance(JsonNode data, String id, AssistantDelegations.Turn turn) {
        exact(data, "wallet_id", "currency", "balance_minor");
        if (!id.equalsIgnoreCase(uuidText(data.get("wallet_id")))) throw failure(503);
        choice(data.get("currency"), "AED"); amount(data.get("balance_minor"), 0);
        ObjectNode result = (ObjectNode) data.deepCopy(); result.put("wallet_id", id);
        turn.balances.put(id, result.deepCopy()); return result;
    }
    private static ObjectNode projectPayments(JsonNode data, UUID owner, AssistantDelegations.Turn turn) {
        exact(data, "payments", "next_cursor"); array(data.get("payments"), 50);
        JsonNode cursor = data.get("next_cursor");
        if (!cursor.isNull() && (!cursor.isTextual() || !cursor.textValue().matches("[A-Za-z0-9_-]{1,512}"))) throw failure(503);
        ObjectNode result = InternalHttp.JSON.createObjectNode(); var rows = result.putArray("payments");
        OffsetDateTime previous = null; String previousId = null; Set<String> ids = new HashSet<>();
        for (JsonNode row : data.get("payments")) {
            exact(row, "id", "requester_id", "recipient_id", "source_wallet_id", "destination_wallet_id", "recipient_phone",
                "currency", "amount_minor", "status", "rejection_reason", "created_at", "updated_at");
            String id = uuidText(row.get("id")), sender = uuidText(row.get("requester_id")), recipient = uuidText(row.get("recipient_id"));
            uuidText(row.get("source_wallet_id")); uuidText(row.get("destination_wallet_id")); text(row.get("recipient_phone"), 1, 32);
            choice(row.get("currency"), "AED"); amount(row.get("amount_minor"), 1); choice(row.get("status"), "PENDING", "COMPLETED", "REJECTED");
            if (!row.get("rejection_reason").isNull()) choice(row.get("rejection_reason"), "INSUFFICIENT_FUNDS", "INVALID_ACCOUNT", "BALANCE_LIMIT");
            OffsetDateTime created = timestamp(row.get("created_at")); timestamp(row.get("updated_at"));
            boolean outgoing = owner.toString().equalsIgnoreCase(sender);
            if (!outgoing && (!owner.toString().equalsIgnoreCase(recipient) || !row.get("status").textValue().equals("COMPLETED"))) throw failure(503);
            if (!ids.add(id) || previous != null && (created.toInstant().isAfter(previous.toInstant())
                || created.toInstant().equals(previous.toInstant()) && id.compareTo(previousId) >= 0)) throw failure(503);
            ObjectNode projected = rows.addObject(); projected.put("id", id);
            for (String field : List.of("created_at", "currency", "amount_minor", "status")) projected.set(field, row.get(field));
            projected.put("direction", outgoing ? "outgoing" : "incoming"); previous = created; previousId = id;
        }
        result.set("next_cursor", cursor); turn.historyRead = true;
        turn.latestPayment = rows.isEmpty() ? null : rows.get(0).deepCopy(); return result;
    }
    private static void validateReply(JsonNode data, AssistantDelegations.Turn turn) {
        exact(data, "reply", "outcome", "citations", "facts", "provenance");
        text(data.get("reply"), 1, 8000); if (data.get("reply").textValue().isBlank()) throw failure(503);
        choice(data.get("outcome"), "answered", "insufficient_evidence", "empty_history", "conflicting_evidence");
        array(data.get("citations"), 6);
        for (JsonNode citation : data.get("citations")) {
            exact(citation, "document_id", "version", "section", "excerpt"); text(citation.get("document_id"), 1, 128);
            text(citation.get("version"), 1, 64); text(citation.get("section"), 1, 256); text(citation.get("excerpt"), 1, 1000);
        }
        JsonNode facts = data.get("facts"); exact(facts, "wallets", "last_transaction");
        if (!facts.get("wallets").isNull()) {
            array(facts.get("wallets"), 10); Set<String> ids = new HashSet<>();
            for (JsonNode row : facts.get("wallets")) {
                exact(row, "wallet_id", "currency", "balance_minor"); String id = uuidText(row.get("wallet_id"));
                choice(row.get("currency"), "AED"); amount(row.get("balance_minor"), 0);
                if (!ids.add(id) || !row.equals(turn.balances.get(id))) throw failure(503);
            }
        }
        JsonNode payment = facts.get("last_transaction");
        if (!payment.isNull()) {
            exact(payment, "id", "created_at", "currency", "amount_minor", "direction", "status"); uuidText(payment.get("id"));
            timestamp(payment.get("created_at")); choice(payment.get("currency"), "AED"); amount(payment.get("amount_minor"), 1);
            choice(payment.get("direction"), "incoming", "outgoing"); choice(payment.get("status"), "PENDING", "COMPLETED", "REJECTED");
            if (!turn.historyRead || !payment.equals(turn.latestPayment)) throw failure(503);
        }
        if (data.get("outcome").textValue().equals("empty_history") && (!turn.historyRead || turn.latestPayment != null)) throw failure(503);
        array(data.get("provenance"), 3); Set<String> sources = new HashSet<>();
        for (JsonNode row : data.get("provenance")) {
            exact(row, "source", "fetched_at", "scope"); choice(row.get("source"), "wallet-service", "payment-service", "policy-index");
            if (!sources.add(row.get("source").textValue())) throw failure(503);
            timestamp(row.get("fetched_at")); text(row.get("scope"), 1, 128);
        }
    }
    private static JsonNode parse(HttpResponse<byte[]> response) throws IOException {
        if (!jsonType(response.headers().firstValue("Content-Type").orElse(null))) throw failure(503);
        JsonNode data = InternalHttp.JSON.readTree(response.body()); if (data == null) throw failure(503); return data;
    }
    private static boolean jsonType(String value) { return value != null && value.split(";", 2)[0].strip().equalsIgnoreCase("application/json"); }
    private static String single(Context ctx, String name) {
        var values = Collections.list(ctx.req().getHeaders(name)); return values.size() == 1 ? values.getFirst() : null;
    }
    private static boolean credential(String supplied, String expected) {
        return supplied != null && supplied.length() <= 256 && MessageDigest.isEqual(supplied.getBytes(StandardCharsets.UTF_8), expected.getBytes(StandardCharsets.UTF_8));
    }
    private static UUID uuid(String value) {
        UUID id = UUID.fromString(value); if (!id.toString().equalsIgnoreCase(value)) throw new IllegalArgumentException("Invalid UUID"); return id;
    }
    private static String uuidText(JsonNode node) {
        try { text(node, 36, 36); return uuid(node.textValue()).toString(); } catch (Exception e) { throw failure(503); }
    }
    private static void exact(JsonNode node, String... fields) {
        if (node == null || !node.isObject() || node.size() != fields.length) throw failure(503);
        for (String field : fields) if (!node.has(field)) throw failure(503);
    }
    private static void text(JsonNode node, int min, int max) {
        if (node == null || !node.isTextual()) throw failure(503);
        String value = node.textValue(); int count = value.codePointCount(0, value.length());
        if (count < min || count > max) throw failure(503);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isHighSurrogate(c)) { if (++i >= value.length() || !Character.isLowSurrogate(value.charAt(i))) throw failure(503); }
            else if (Character.isLowSurrogate(c)) throw failure(503);
        }
    }
    private static void choice(JsonNode node, String... values) {
        if (node == null || !node.isTextual() || !List.of(values).contains(node.textValue())) throw failure(503);
    }
    private static void amount(JsonNode node, long min) {
        if (node == null || !node.isIntegralNumber() || !node.canConvertToLong() || node.longValue() < min) throw failure(503);
    }
    private static void array(JsonNode node, int max) { if (node == null || !node.isArray() || node.size() > max) throw failure(503); }
    private static OffsetDateTime timestamp(JsonNode node) {
        try { text(node, 1, 64); return OffsetDateTime.parse(node.textValue()); } catch (Exception e) { throw failure(503); }
    }
    private static Duration remaining(long deadline, int maxSeconds) throws IOException {
        long nanos = Math.min(deadline - System.nanoTime(), Duration.ofSeconds(maxSeconds).toNanos());
        if (nanos <= 0) throw new IOException("Deadline exceeded"); return Duration.ofNanos(nanos);
    }
    private HttpResponse<byte[]> send(HttpRequest request, int limit, long deadline, int seconds) throws IOException, InterruptedException {
        var future = client.sendAsync(request, response -> new LimitedBody(limit));
        try { return future.get(remaining(deadline, seconds).toNanos(), TimeUnit.NANOSECONDS); }
        catch (ExecutionException | TimeoutException e) { future.cancel(true); throw new IOException("Upstream unavailable"); }
        catch (IOException | InterruptedException e) { future.cancel(true); throw e; }
    }
    private static Rejected failure(int status) { return new Rejected(status); }
    private static final class Rejected extends RuntimeException { final int status; Rejected(int status) { this.status = status; } }
    private static void error(Context ctx, int status) {
        if (status == 429 && ctx.res().getHeader("Retry-After") == null) ctx.header("Retry-After", "1");
        InternalHttp.reply(ctx, status, Map.of("error", switch (status) {
            case 400 -> "invalid_request"; case 401 -> "invalid_credentials"; case 413 -> "request_too_large";
            case 429 -> "server_busy"; case 404, 409 -> "request_rejected"; default -> "service_unavailable";
        }));
    }
    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final HttpResponse.BodySubscriber<byte[]> delegate = HttpResponse.BodySubscribers.ofByteArray();
        private final int limit; private Flow.Subscription subscription; private int size; private boolean failed;
        LimitedBody(int limit) { this.limit = limit; }
        public CompletionStage<byte[]> getBody() { return delegate.getBody(); }
        public void onSubscribe(Flow.Subscription value) { subscription = value; delegate.onSubscribe(value); }
        public void onNext(List<ByteBuffer> items) {
            if (failed) return;
            for (var item : items) {
                if (item.remaining() > limit - size) { failed = true; subscription.cancel(); delegate.onError(new IOException("Response exceeds limit")); return; }
                size += item.remaining();
            }
            delegate.onNext(items);
        }
        public void onError(Throwable error) { if (!failed) delegate.onError(error); }
        public void onComplete() { if (!failed) delegate.onComplete(); }
    }
    @Override public void close() { delegations.clear(); quota.clear(); client.close(); }
}
