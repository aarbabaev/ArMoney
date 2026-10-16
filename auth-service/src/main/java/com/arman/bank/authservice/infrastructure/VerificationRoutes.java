package com.arman.bank.authservice.infrastructure;

import com.arman.bank.authservice.application.VerificationService;
import com.arman.bank.authservice.application.IdentityVerificationService;
import com.arman.bank.authservice.domain.IdentityVerification;
import com.arman.bank.authservice.domain.Verification.*;
import com.arman.bank.authservice.domain.Verification.Error;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.*;
import io.javalin.config.JavalinConfig;
import io.javalin.http.Context;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** No gateway exposure. Only the authenticated provider server may select contacts/bindings. */
public final class VerificationRoutes {
    private static final ObjectMapper JSON = new ObjectMapper()
        .enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final Set<String> BINDING = Set.of("issuer", "subject", "authentication_session_id");
    private static final Set<String> AUTHORITY_BINDING = Set.of("issuer", "subject", "client_id", "provider_root_session_id", "provider_tab_id", "authentication_session_id");
    private final VerificationService service;
    private final IdentityVerificationService authority;
    private final byte[] bridgeKey;
    public VerificationRoutes(VerificationService service, String bridgeKey) {
        this(Objects.requireNonNull(service),null,bridgeKey);
    }
    public VerificationRoutes(IdentityVerificationService authority,String bridgeKey){
        this(null,Objects.requireNonNull(authority),bridgeKey);
    }
    private VerificationRoutes(VerificationService service,IdentityVerificationService authority,String bridgeKey){
        this.service=service;this.authority=authority;
        if (bridgeKey == null || bridgeKey.length() < 32 || bridgeKey.length() > 256
                || bridgeKey.chars().anyMatch(c -> c < 33 || c > 126))
            throw new IllegalArgumentException("Invalid SSO_VERIFICATION_BRIDGE_KEY");
        this.bridgeKey = bridgeKey.getBytes(StandardCharsets.UTF_8);
    }
    public void configure(JavalinConfig config) {
        config.http.maxRequestSize = 12288;
        // Each handler authenticates before reading the body; unrelated auth handlers retain their errors.
        config.routes.post("/internal/enrollments", ctx -> handle(ctx, () -> {
            var body = body(ctx, "command_id", "phone_number", "email");
            String phone=text(body,"phone_number",13),email=text(body,"email",254);
            if(authority!=null&&(!VerificationService.canonicalPhone(phone)||!email.equals(VerificationService.normalizeEmail(email))))throw new Failure(Error.BAD_INPUT);
            return enrollment(authority==null?service.enroll(new EnrollmentCommand(uuid(body,"command_id"),binding(body),phone,email))
                :authority.enroll("sso-provider",new IdentityVerification.Enrollment(uuid(body,"command_id"),flow(body),phone,email)));
        }));
        config.routes.post("/internal/enrollments/{id}/status", ctx -> handle(ctx, () -> {
            var body = body(ctx);
            return enrollment(authority==null?service.status(uuid(ctx.pathParam("id")),binding(body))
                :authority.status("sso-provider",uuid(ctx.pathParam("id")),flow(body)));
        }));
        config.routes.post("/internal/enrollments/{id}/challenges", ctx -> handle(ctx, () -> {
            var body = body(ctx, "command_id", "purpose", "contact_version");
            Result result = authority==null?service.issue(new ChallengeCommand(uuid(body,"command_id"),uuid(ctx.pathParam("id")),binding(body),purpose(body),version(body)))
                :authority.issue("sso-provider",new IdentityVerification.Issue(uuid(body,"command_id"),uuid(ctx.pathParam("id")),flow(body),purpose(body),version(body)));
            if(result.challengeId()==null||!"PENDING_DELIVERY".equals(result.state()))throw new Failure(Error.UNAVAILABLE);
            var response = new LinkedHashMap<String, Object>();
            response.put("challenge_id", result.challengeId()); response.put("state", safeState(result.state()));
            response.put("expires_at", result.expiresAt() == null ? null : result.expiresAt().toString());
            response.put("resend_at", result.resendAt() == null ? null : result.resendAt().toString());
            response.put("attempts_remaining", result.attemptsRemaining());
            return response;
        }));
        config.routes.post("/internal/enrollments/{id}/challenges/{challenge}/consume", ctx -> handle(ctx, () -> {
            var body = body(ctx, "command_id", "purpose", "contact_version", "code");
            Result result = authority==null?service.consume(new ConsumeCommand(uuid(body,"command_id"),uuid(ctx.pathParam("id")),uuid(ctx.pathParam("challenge")),binding(body),purpose(body),version(body),text(body,"code",6)))
                :authority.consume("sso-provider",new IdentityVerification.Consume(uuid(body,"command_id"),uuid(ctx.pathParam("id")),uuid(ctx.pathParam("challenge")),flow(body),purpose(body),version(body),text(body,"code",6)));
            if(result.receiptId()==null||!"CONSUMED".equals(result.state())||result.contactVersion()!=version(body))throw new Failure(Error.UNAVAILABLE);
            return Map.of("receipt_id", result.receiptId(), "contact_version", result.contactVersion(), "state", safeState(result.state()));
        }));
    }
    @FunctionalInterface private interface Action { Map<String, Object> run(); }
    private void handle(Context ctx, Action action) {
        ctx.header("Cache-Control", "no-store");
        try {
            var supplied = ctx.header("X-ArMoney-SSO-Bridge-Key");
            if (supplied == null || supplied.length() > 256 || !MessageDigest.isEqual(bridgeKey, supplied.getBytes(StandardCharsets.UTF_8))) {
                respond(ctx, 401, Map.of("error", "verification_request_rejected")); return;
            }
            // Binding and codes belong in the bounded body, never in URLs or caller-selected identity headers.
            if (!ctx.queryParamMap().isEmpty()) throw new Failure(Error.BAD_INPUT);
            respond(ctx, 200, action.run());
        } catch (Failure failure) {
            int status = switch (failure.error()) {
                case BAD_INPUT -> 400;
                case BINDING -> 401;
                case CONFLICT -> 409;
                case RATE_LIMITED -> 429;
                case UNAVAILABLE -> 503;
            };
            if (status == 429) ctx.header("Retry-After", "60");
            respond(ctx, status, Map.of("error", "verification_request_rejected"));
        } catch (io.javalin.http.HttpResponseException failure) {
            respond(ctx, failure.getStatus() == 413 ? 413 : 400, Map.of("error", "verification_request_rejected"));
        } catch (RuntimeException unavailable) {
            // No exception/body/credential logging and no fallback success.
            respond(ctx, 503, Map.of("error", "verification_unavailable"));
        }
    }
    private JsonNode body(Context ctx, String... extra) {
        if (ctx.bodyAsBytes().length > 12288) throw new io.javalin.http.HttpResponseException(413, "Request too large");
        String type = ctx.contentType();
        if (type == null || !type.split(";", 2)[0].strip().equalsIgnoreCase("application/json")) throw new Failure(Error.BAD_INPUT);
        try {
            JsonNode body = JSON.readTree(ctx.body());
            Set<String> allowed = new HashSet<>(authority==null?BINDING:AUTHORITY_BINDING); Collections.addAll(allowed, extra);
            if (body == null || !body.isObject() || body.size() != allowed.size()) throw new Failure(Error.BAD_INPUT);
            var names = body.fieldNames();
            while (names.hasNext()) if (!allowed.contains(names.next())) throw new Failure(Error.BAD_INPUT);
            for (String name : allowed) if (!body.hasNonNull(name)) throw new Failure(Error.BAD_INPUT);
            return body;
        } catch (JsonProcessingException invalid) { throw new Failure(Error.BAD_INPUT); }
    }
    private static Binding binding(JsonNode body) {
        return new Binding(text(body, "issuer", 512), text(body, "subject", 255), text(body, "authentication_session_id", 255));
    }
    private static IdentityVerification.Flow flow(JsonNode body){return new IdentityVerification.Flow(text(body,"issuer",512),text(body,"subject",255),text(body,"client_id",128),text(body,"provider_root_session_id",255),text(body,"provider_tab_id",255),text(body,"authentication_session_id",43));}
    private static String text(JsonNode body, String name, int max) {
        JsonNode value = body.get(name);
        if (value == null || !value.isTextual() || value.textValue().isBlank() || value.textValue().length() > max)
            throw new Failure(Error.BAD_INPUT);
        return value.textValue();
    }
    private static UUID uuid(JsonNode body, String name) { return uuid(text(body, name, 36)); }
    private static UUID uuid(String value) {
        if (value == null || !value.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")) throw new Failure(Error.BAD_INPUT);
        return UUID.fromString(value);
    }
    private static Purpose purpose(JsonNode body) {
        try { return Purpose.valueOf(text(body, "purpose", 32)); }
        catch (IllegalArgumentException invalid) { throw new Failure(Error.BAD_INPUT); }
    }
    private static int version(JsonNode body) {
        JsonNode value = body.get("contact_version");
        if (value == null || !value.isIntegralNumber() || !value.canConvertToInt() || value.intValue() < 1) throw new Failure(Error.BAD_INPUT);
        return value.intValue();
    }
    private Map<String, Object> enrollment(Result result) {
        Set<String> states=authority==null?Set.of("PENDING_CONTACTS","VERIFIED","BLOCKED_MIGRATION")
            :Set.of("PENDING_CONTACTS","PROJECTION_PENDING","PROJECTED","SUSPENDED","BLOCKED_MIGRATION");
        if(result.enrollmentId()==null||result.version()<1||result.emailContactVersion()<1||result.phoneContactVersion()<1||!states.contains(result.state()))throw new Failure(Error.UNAVAILABLE);
        var response = new LinkedHashMap<String, Object>();
        response.put("enrollment_id", result.enrollmentId()); response.put("version", result.version());
        response.put("state", result.state()); response.put("email_contact_version", result.emailContactVersion());
        response.put("phone_contact_version", result.phoneContactVersion()); response.put("email_verified", result.emailVerified());
        response.put("phone_verified", result.phoneVerified());
        return response;
    }
    private static String safeState(String state) {
        // Activation is outside this slice; even an erroneous port result cannot advertise it.
        if (!Set.of("PENDING_CONTACTS", "VERIFIED", "BLOCKED_MIGRATION", "PENDING_DELIVERY", "CONSUMED").contains(state))
            throw new Failure(Error.UNAVAILABLE);
        return state;
    }
    private static void respond(Context ctx, int status, Map<String, ?> body) {
        try { ctx.status(status).contentType("application/json").result(JSON.writeValueAsString(body)); }
        catch (JsonProcessingException invalid) { throw new IllegalStateException("Verification serialization failed"); }
    }
}
