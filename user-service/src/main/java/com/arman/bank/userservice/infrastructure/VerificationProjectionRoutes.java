package com.arman.bank.userservice.infrastructure;

import com.arman.bank.userservice.application.VerificationProjectionStore;
import com.arman.bank.userservice.domain.VerificationProjection;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.config.JavalinConfig;
import io.javalin.http.Context;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Semaphore;

/** Dedicated private protocol. Deliberately independent of business identity-header authorization. */
public final class VerificationProjectionRoutes {
    public static final String PATH = "/v1/internal/verification-projections";
    private static final String HEADER = "X-ArMoney-Verification-Projection-Key";
    private static final int LIMIT = 12 * 1024;
    private static final Set<String> FIELDS = Set.of("operation_id", "identity_id", "expected_version", "version",
        "canonical_phone", "phone_contact_version", "verification_epoch", "payload_hash");
    private static final ObjectMapper JSON = new ObjectMapper()
        .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final VerificationProjectionStore store;
    private final VerificationProjectionConfiguration configuration;
    private final Semaphore concurrent = new Semaphore(16);
    public VerificationProjectionRoutes(VerificationProjectionStore store, VerificationProjectionConfiguration configuration) {
        this.store = java.util.Objects.requireNonNull(store);
        this.configuration = java.util.Objects.requireNonNull(configuration);
    }
    public void configure(JavalinConfig config) {
        if (configuration.enabled()) config.routes.post(PATH, this::handle);
    }
    private void handle(Context ctx) {
        long originalStartedNanos=System.nanoTime();
        boolean acquired = false;
        ctx.header("Cache-Control", "no-store");
        try {
            String writer = authenticate(ctx);
            acquired = concurrent.tryAcquire();
            if (!acquired) throw new VerificationProjectionStore.Rejected(429);
            if (!store.admit(writer)) throw new VerificationProjectionStore.Rejected(429);
            String type = ctx.contentType();
            if (type == null || !type.split(";", 2)[0].strip().equalsIgnoreCase("application/json")) throw new IllegalArgumentException();
            byte[] bytes = LiveUserCurrentRoutes.readBody(ctx,originalStartedNanos,LIMIT);
            if (bytes.length > LIMIT) throw new IllegalArgumentException();
            VerificationProjection.Ack ack = store.commit(writer, parse(bytes),originalStartedNanos);
            reply(ctx, 200, Map.of("operation_id", ack.operationId().toString(), "identity_id", ack.identityId().toString(),
                "version", ack.version(), "phone_contact_version", ack.phoneContactVersion(),
                "verification_epoch", ack.verificationEpoch(), "payload_hash", ack.payloadHash()));
        } catch (VerificationProjectionStore.Rejected e) {
            error(ctx, e.status());
        } catch (IllegalArgumentException | com.fasterxml.jackson.core.JsonProcessingException e) {
            error(ctx, 400);
        } catch (Exception e) {
            error(ctx, 503);
        } finally { if (acquired) concurrent.release(); }
    }
    private String authenticate(Context ctx) {
        if (Collections.list(ctx.req().getHeaders(HEADER)).size() != 1) throw new VerificationProjectionStore.Rejected(401);
        String supplied = ctx.header(HEADER);
        if (supplied == null || !supplied.matches("[!-~]{32,256}")) throw new VerificationProjectionStore.Rejected(401);
        String writer = null;
        byte[] bytes = supplied.getBytes(StandardCharsets.US_ASCII);
        for (var entry : configuration.writers().entrySet()) {
            if (MessageDigest.isEqual(bytes, entry.getValue().getBytes(StandardCharsets.US_ASCII))) writer = entry.getKey();
        }
        if (writer == null) throw new VerificationProjectionStore.Rejected(401);
        return writer;
    }
    public static VerificationProjection parse(byte[] bytes) throws com.fasterxml.jackson.core.JsonProcessingException {
        if (bytes.length > LIMIT) throw new IllegalArgumentException();
        JsonNode node;
        try { node = JSON.readTree(bytes); }
        catch (java.io.IOException e) {
            if (e instanceof com.fasterxml.jackson.core.JsonProcessingException json) throw json;
            throw new IllegalArgumentException();
        }
        if (node == null || !node.isObject() || node.size() != FIELDS.size()) throw new IllegalArgumentException();
        var names = node.fieldNames();
        while (names.hasNext()) if (!FIELDS.contains(names.next())) throw new IllegalArgumentException();
        return new VerificationProjection(uuid(node,"operation_id"),uuid(node,"identity_id"),integer(node,"expected_version"),
            integer(node,"version"),text(node,"canonical_phone"),integer(node,"phone_contact_version"),
            longInteger(node,"verification_epoch"),text(node,"payload_hash"));
    }
    private static String text(JsonNode n, String field) {
        JsonNode value=n.get(field);
        if (value == null || !value.isTextual()) throw new IllegalArgumentException();
        return value.textValue();
    }
    private static UUID uuid(JsonNode n, String field) {
        String value=text(n,field);
        UUID id=UUID.fromString(value);
        if (!id.toString().equals(value)) throw new IllegalArgumentException();
        return id;
    }
    private static int integer(JsonNode n,String field) {
        JsonNode value=n.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToInt()) throw new IllegalArgumentException();
        return value.intValue();
    }
    private static long longInteger(JsonNode n,String field) {
        JsonNode value=n.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()) throw new IllegalArgumentException();
        return value.longValue();
    }
    private static void error(Context ctx,int status) {
        if (status == 429) ctx.header("Retry-After","60");
        reply(ctx,status,Map.of("error",switch(status) {
            case 400 -> "malformed"; case 401 -> "credential_rejected"; case 409 -> "conflict";
            case 410 -> "expired_operation"; case 429 -> "capacity"; default -> "unavailable";
        }));
    }
    private static void reply(Context ctx,int status,Object value) {
        try {
            byte[] bytes=JSON.writeValueAsBytes(value);
            if (bytes.length>LIMIT) throw new IllegalStateException();
            ctx.status(status).contentType("application/json").result(bytes);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) { throw new IllegalStateException("Serialization unavailable"); }
    }
}
