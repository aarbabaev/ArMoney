package com.arman.bank.sso;

import jakarta.ws.rs.*;
import jakarta.ws.rs.core.*;
import org.keycloak.models.*;
import org.keycloak.services.Urls;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;

/** Private contact observations, never an eligibility or verification authority. */
@jakarta.ws.rs.ext.Provider
public final class StatusResource {
    enum EnrollmentRejection { STATUS_KEY_CONFIGURATION, STATUS_AUTHENTICATION, FEATURE_DISABLED, BODY_BOUND, REQUEST_SCHEMA, OBSERVATION_UNAVAILABLE }
    private static final org.jboss.logging.Logger LOG=org.jboss.logging.Logger.getLogger(StatusResource.class);
    private static Response enrollmentRejected(EnrollmentRejection reason,int status){LOG.warn(reason.name());return empty(status);}
    private final KeycloakSession session;
    private final java.util.function.Supplier<String> statusKey;
    private final java.util.function.Supplier<String> bindingKey;
    private final java.util.function.BooleanSupplier enrollmentEnabled;
    private final java.util.function.BooleanSupplier codeSessionEnabled;
    StatusResource(KeycloakSession session) { this(session, () -> System.getenv("AUTH_TO_SSO_STATUS_KEY")); }
    StatusResource(KeycloakSession session, java.util.function.Supplier<String> statusKey) {
        this(session, statusKey, () -> Bridge.required("ARMONEY_PROVIDER_BINDING_KEY"), Bridge::enabled);
    }
    StatusResource(KeycloakSession session, java.util.function.Supplier<String> statusKey,
                   java.util.function.Supplier<String> bindingKey, java.util.function.BooleanSupplier enrollmentEnabled) {
        this(session,statusKey,bindingKey,enrollmentEnabled,()->"true".equals(System.getenv("ARMONEY_CODE_SESSION_OBSERVATION_ENABLED")));
    }
    StatusResource(KeycloakSession session, java.util.function.Supplier<String> statusKey,
                   java.util.function.Supplier<String> bindingKey, java.util.function.BooleanSupplier enrollmentEnabled,
                   java.util.function.BooleanSupplier codeSessionEnabled) {
        this.session = session; this.statusKey = statusKey; this.bindingKey = bindingKey;
        this.enrollmentEnabled = enrollmentEnabled;
        this.codeSessionEnabled=codeSessionEnabled;
    }

    @POST @Path("code-session") @Consumes(MediaType.APPLICATION_JSON) @Produces(MediaType.APPLICATION_JSON)
    public Response codeSession(@HeaderParam("X-ArMoney-Auth-Status-Key") String supplied,byte[] body) {
        String configured=statusKey.get();
        if(configured==null || configured.length()<32 || configured.length()>256 || configured.chars().anyMatch(c->c<33||c>126)) return empty(503);
        if(supplied==null || supplied.length()>256 || !MessageDigest.isEqual(configured.getBytes(StandardCharsets.UTF_8),supplied.getBytes(StandardCharsets.UTF_8))) return empty(401);
        if(!codeSessionEnabled.getAsBoolean()) return empty(503);
        if(body==null || body.length>12288) return empty(400);
        final CodeSessionObservation.Binding binding;
        try { binding=CodeSessionObservation.parse(Bridge.strictJson(body)); }
        catch(IllegalArgumentException | java.io.IOException invalid) { return empty(400); }
        try { return Response.ok(CodeSessionObservation.observe(session,binding,java.time.Clock.systemUTC())).type(MediaType.APPLICATION_JSON_TYPE).header("Cache-Control","no-store").build(); }
        catch(RuntimeException unavailable) { return empty(503); }
    }

    @POST @Path("enrollment") @Consumes(MediaType.APPLICATION_JSON) @Produces(MediaType.APPLICATION_JSON)
    public Response enrollment(@HeaderParam("X-ArMoney-Auth-Status-Key") String supplied, byte[] body) {
        String configured = statusKey.get();
        if (configured == null || configured.length() < 32 || configured.length() > 512
                || configured.chars().anyMatch(c -> c < 33 || c > 126)) return enrollmentRejected(EnrollmentRejection.STATUS_KEY_CONFIGURATION,503);
        if (supplied == null || supplied.length() > 512 || !MessageDigest.isEqual(
                configured.getBytes(StandardCharsets.UTF_8), supplied.getBytes(StandardCharsets.UTF_8))) return enrollmentRejected(EnrollmentRejection.STATUS_AUTHENTICATION,401);
        if (!enrollmentEnabled.getAsBoolean()) return enrollmentRejected(EnrollmentRejection.FEATURE_DISABLED,503);
        if (body == null || body.length > 12288) return enrollmentRejected(EnrollmentRejection.BODY_BOUND,400);
        final EnrollmentObservation.Binding binding;
        try { binding = EnrollmentObservation.parse(Bridge.strictJson(body)); }
        catch (IllegalArgumentException | java.io.IOException invalid) { return enrollmentRejected(EnrollmentRejection.REQUEST_SCHEMA,400); }
        try {
            return Response.ok(EnrollmentObservation.observe(session, binding, bindingKey.get()))
                    .header("Cache-Control", "no-store").build();
        } catch (RuntimeException unavailable) { return enrollmentRejected(EnrollmentRejection.OBSERVATION_UNAVAILABLE,503); }
    }

    @POST @Consumes(MediaType.APPLICATION_JSON) @Produces(MediaType.APPLICATION_JSON)
    public Response status(@HeaderParam("X-ArMoney-Auth-Status-Key") String supplied, byte[] body) {
        String configured = statusKey.get();
        if (configured == null || configured.length() < 32 || configured.length() > 512
                || configured.chars().anyMatch(c -> c < 33 || c > 126)) return empty(503);
        if (supplied == null || supplied.length() > 512 || !MessageDigest.isEqual(
                configured.getBytes(StandardCharsets.UTF_8), supplied.getBytes(StandardCharsets.UTF_8))) return empty(401);
        if (body == null || body.length > 12288) return empty(400);
        try {
            var input = Bridge.strictJson(body);
            if (input == null || !input.isObject() || input.size() != 3) return empty(400);
            String issuer = text(input, "issuer", 512), subject = text(input, "subject", 255), sid = text(input, "user_session_id", 255);
            RealmModel realm = session.getContext().getRealm();
            String expected = Urls.realmIssuer(session.getContext().getUri().getBaseUri(), realm.getName());
            if (!expected.equals(issuer)) return empty(401);
            UserModel user = session.users().getUserById(realm, subject);
            UserSessionModel userSession = session.sessions().getUserSession(realm, sid);
            boolean active = userSession != null && user != null && userSession.getUser().getId().equals(subject)
                    && org.keycloak.services.managers.AuthenticationManager.isSessionValid(realm, userSession);
            if (user == null || !active) return empty(401);
            var result = new LinkedHashMap<String, Object>();
            result.put("issuer", expected); result.put("subject", user.getId()); result.put("user_session_id", sid);
            result.put("username", user.getUsername()); result.put("email", user.getEmail());
            String phone = user.getUsername();
            result.put("phone_number", phone != null && phone.matches("\\+9715[024568][0-9]{7}") ? phone : null);
            result.put("enabled", user.isEnabled()); result.put("session_active", true);
            return Response.ok(result).header("Cache-Control", "no-store").build();
        } catch (IllegalArgumentException | java.io.IOException invalid) { return empty(400); }
        catch (RuntimeException unavailable) { return empty(503); }
    }
    private static String text(com.fasterxml.jackson.databind.JsonNode node, String field, int maximum) {
        var value = node.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank() || value.asText().length() > maximum
                || value.asText().chars().anyMatch(c -> c < 33 || c == 127)) throw new IllegalArgumentException();
        return value.asText();
    }
    private static Response empty(int status) {
        // Keycloak's security-header filter rejects POST 503 responses without a
        // media type, even when @Produces is present and the entity is empty.
        return Response.status(status).type(MediaType.APPLICATION_JSON_TYPE).header("Cache-Control", "no-store").build();
    }
}
