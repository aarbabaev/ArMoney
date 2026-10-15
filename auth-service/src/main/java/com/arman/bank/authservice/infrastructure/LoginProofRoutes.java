package com.arman.bank.authservice.infrastructure;

import com.arman.bank.authservice.application.LoginProofStore;
import com.arman.bank.authservice.domain.LoginProof;
import com.arman.bank.authservice.domain.LoginProof.*;
import com.arman.bank.authservice.domain.LoginProof.Error;
import com.fasterxml.jackson.databind.*;
import io.javalin.config.JavalinConfig;
import io.javalin.http.Context;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.Semaphore;

/** Private directional proof transport. Caller identity headers, contacts and eligibility fields have no authority. */
public final class LoginProofRoutes {
    private static final ObjectMapper JSON = new ObjectMapper()
        .enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final Set<String> RESOLVE = Set.of("code_digest", "code_key_id", "issuer", "subject", "client_id", "user_session_id");
    private final LoginProofStore store;
    private final Policy policy;
    private final String writer;
    private final byte[] writeKey, resolveKey;
    private final Semaphore concurrent = new Semaphore(16);
    public LoginProofRoutes(LoginProofStore store, Policy policy, String writer, String writeKey, String resolveKey) {
        this.store = Objects.requireNonNull(store); this.policy = Objects.requireNonNull(policy);
        if (!policy.writers().contains(writer)) throw new IllegalArgumentException("Invalid proof writer");
        this.writer = writer; this.writeKey = credential(writeKey); this.resolveKey = credential(resolveKey);
        if (MessageDigest.isEqual(this.writeKey, this.resolveKey)) throw new IllegalArgumentException("Proof credentials must differ");
    }
    static byte[] credential(String value) {
        if (value == null || value.length() < 32 || value.length() > 256 || value.chars().anyMatch(c -> c < 33 || c > 126))
            throw new IllegalArgumentException("Invalid proof credential");
        return value.getBytes(StandardCharsets.US_ASCII);
    }
    public void configure(JavalinConfig config) {
        config.routes.post("/internal/sso-login-proofs", ctx -> handle(ctx, true));
        config.routes.post("/internal/sso-login-proofs/resolve", ctx -> handle(ctx, false));
    }
    private void handle(Context ctx, boolean write) {
        ctx.header("Cache-Control", "no-store");
        boolean acquired = false;
        try {
            var headers = ctx.req().getHeaders(write ? "X-ArMoney-Login-Proof-Write-Key" : "X-ArMoney-Login-Proof-Resolve-Key");
            String supplied = headers.hasMoreElements() ? headers.nextElement() : null;
            if (supplied == null || headers.hasMoreElements() || supplied.length() > 256
                    || !MessageDigest.isEqual(write ? writeKey : resolveKey, supplied.getBytes(StandardCharsets.UTF_8))) throw new Failure(Error.UNAUTHORIZED);
            if (!concurrent.tryAcquire()) throw new Failure(Error.RATE_LIMITED);
            acquired = true;
            store.admit(writer);
            if (!ctx.queryParamMap().isEmpty()) throw new Failure(Error.BAD_INPUT);
            JsonNode body = body(ctx, write);
            Snapshot result;
            if (write) {
                Kind kind = switch (text(body, "kind")) { case "interactive" -> Kind.INTERACTIVE; case "reuse" -> Kind.REUSE; default -> throw new Failure(Error.BAD_INPUT); };
                Write input = new Write(uuid(body, "command_id"), text(body, "code_digest"), text(body, "code_key_id"), text(body, "issuer"),
                    text(body, "subject"), text(body, "client_id"), text(body, "user_session_id"), text(body, "authentication_session_id"),
                    time(body, "issued_at"), time(body, "expires_at"), kind, body.get("event_id").isNull() ? null : uuid(body, "event_id"),
                    body.get("password_authenticated_at").isNull() ? null : time(body, "password_authenticated_at"));
                LoginProof.validate(input, writer, policy); result = store.write(writer, input);
            } else {
                Resolve input = new Resolve(text(body, "code_digest"), text(body, "code_key_id"), text(body, "issuer"), text(body, "subject"), text(body, "client_id"), text(body, "user_session_id"));
                LoginProof.validate(input, policy); result = store.resolve(writer, input);
            }
            if (result == null || result.proofId() == null || result.kind() == null || result.issuedAt() == null || result.expiresAt() == null
                    || (result.kind() == Kind.INTERACTIVE && (result.eventId() == null || !result.loginNoticeEligible()))
                    || (result.kind() == Kind.REUSE && (result.eventId() != null || result.loginNoticeEligible()))) throw new Failure(Error.UNAVAILABLE);
            var response = new LinkedHashMap<String, Object>();
            response.put("proof_id", result.proofId()); response.put("event_id", result.eventId()); response.put("kind", result.kind().wire());
            response.put("login_notice_eligible", result.loginNoticeEligible()); response.put("issued_at", result.issuedAt().toString()); response.put("expires_at", result.expiresAt().toString());
            if (write) { response.put("code_key_id", result.codeKeyId()); response.put("state", result.state()); }
            reply(ctx, 200, response);
        } catch (Failure failure) {
            int status = switch (failure.error()) {
                case BAD_INPUT -> 400; case UNAUTHORIZED -> 401; case CONFLICT -> 409; case EXPIRED -> 410;
                case MISSING -> write ? 503 : 404; case RATE_LIMITED -> 429; case UNAVAILABLE -> 503;
            };
            if (status == 429) ctx.header("Retry-After", "60");
            reply(ctx, status, Map.of("error", "login_proof_request_rejected"));
        } catch (Exception unavailable) { reply(ctx, 503, Map.of("error", "login_proof_request_rejected")); }
        finally { if (acquired) concurrent.release(); }
    }
    private static JsonNode body(Context ctx, boolean write) {
        String type = ctx.contentType();
        if (type == null || !type.split(";", 2)[0].strip().equalsIgnoreCase("application/json")) throw new Failure(Error.BAD_INPUT);
        try {
            byte[] bytes = ctx.req().getInputStream().readNBytes(12289);
            if (bytes.length > 12288) throw new Failure(Error.BAD_INPUT);
            JsonNode body = JSON.readTree(bytes);
            Set<String> fields = new HashSet<>(RESOLVE);
            if (write) Collections.addAll(fields, "command_id", "authentication_session_id", "issued_at", "expires_at", "kind", "event_id", "password_authenticated_at");
            if (body == null || !body.isObject() || body.size() != fields.size()) throw new Failure(Error.BAD_INPUT);
            var names = body.fieldNames();
            while (names.hasNext()) if (!fields.contains(names.next())) throw new Failure(Error.BAD_INPUT);
            return body;
        } catch (java.io.IOException invalid) { throw new Failure(Error.BAD_INPUT); }
    }
    private static String text(JsonNode body, String field) {
        var value = body.get(field);
        if (value == null || !value.isTextual()) throw new Failure(Error.BAD_INPUT);
        return value.textValue();
    }
    private static UUID uuid(JsonNode body, String field) {
        String value = text(body, field);
        if (!value.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")) throw new Failure(Error.BAD_INPUT);
        return UUID.fromString(value);
    }
    private static Instant time(JsonNode body, String field) {
        String value = text(body, field);
        if (!value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z")) throw new Failure(Error.BAD_INPUT);
        try {
            Instant time = Instant.parse(value);
            if (!time.toString().equals(value)) throw new Failure(Error.BAD_INPUT);
            return time;
        } catch (java.time.DateTimeException invalid) { throw new Failure(Error.BAD_INPUT); }
    }
    private static void reply(Context ctx, int status, Map<String, ?> body) {
        try { ctx.status(status).contentType("application/json").result(JSON.writeValueAsString(body)); }
        catch (java.io.IOException invalid) { throw new IllegalStateException("Proof serialization unavailable"); }
    }
}
