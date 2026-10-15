package com.arman.bank.sso;

import com.fasterxml.jackson.databind.JsonNode;
import org.keycloak.authentication.RequiredActionContext;
import org.keycloak.authentication.RequiredActionProvider;
import org.keycloak.services.Urls;
import org.keycloak.sessions.AuthenticationSessionModel;
import java.util.*;

/** OTP authority and delivery remain in auth. Auth notes are flow state, never receipts. */
final class VerificationAction implements RequiredActionProvider {
    private final String purpose;
    private final String id;
    VerificationAction(String purpose, String id) { this.purpose = purpose; this.id = id; }
    @Override public void evaluateTriggers(RequiredActionContext context) {
        if (Bridge.enabled()) context.getAuthenticationSession().addRequiredAction(id);
    }
    @Override public void requiredActionChallenge(RequiredActionContext context) {
        if (!Bridge.enabled()) { context.failure(); return; }
        try {
            var bridge = Bridge.configured();
            JsonNode enrollment = enroll(context, bridge);
            JsonNode status = status(context, bridge, uuid(enrollment, "enrollment_id"));
            if (!complete(context, status)) render(context, null);
        } catch (RuntimeException unavailable) { render(context, "Verification is unavailable. Please try again."); }
    }

    @Override public void processAction(RequiredActionContext context) {
        if (!Bridge.enabled()) { context.failure(); return; }
        try {
            var session = context.getAuthenticationSession();
            var bridge = Bridge.configured();
            // Re-resolve against current server contacts on every submission. UUID alone grants nothing.
            JsonNode enrollment = enroll(context, bridge);
            String enrollmentId = uuid(enrollment, "enrollment_id");
            if (complete(context, status(context, bridge, enrollmentId))) return;
            int version = version(enrollment);
            String operation = context.getHttpRequest().getDecodedFormParameters().getFirst("operation");
            if ("send".equals(operation)) {
                var body = command(context, "issue");
                body.put("purpose", purpose); body.put("contact_version", version);
                issue(session, bridge, enrollmentId, version, body);
                render(context, "If delivery is available, enter the six-digit code."); return;
            }
            String code = context.getHttpRequest().getDecodedFormParameters().getFirst("code");
            String challenge = session.getAuthNote(note("challenge"));
            if (code == null || !code.matches("[0-9]{6}") || challenge == null
                    || !Integer.toString(version).equals(session.getAuthNote(note("version")))) throw new Bridge.Rejected();
            var body = command(context, "consume");
            body.put("purpose", purpose); body.put("contact_version", version); body.put("code", code);
            JsonNode receipt = bridge.post("/internal/enrollments/" + enrollmentId + "/challenges/" + UUID.fromString(challenge) + "/consume", body);
            uuid(receipt, "receipt_id");
            if (!"CONSUMED".equals(receipt.path("state").asText())
                    || receipt.path("contact_version").asInt(-1) != version) throw new Bridge.Unavailable();
            session.removeAuthNote(note("challenge")); session.removeAuthNote(note("consume"));
            if (!complete(context, status(context, bridge, enrollmentId)))
                render(context, "Contact verified. Account activation is pending; try again shortly.");
        } catch (Bridge.Rejected rejected) { context.getAuthenticationSession().removeAuthNote(note("consume")); render(context, "Code not accepted. Request a new code or try again."); }
        catch (RuntimeException unavailable) { render(context, "Verification is unavailable. Please try again."); }
    }

    void issue(AuthenticationSessionModel session, Bridge bridge, String enrollmentId, int version, Map<String, Object> body) {
        body.put("command_id", stable(session, note("issue")));
        JsonNode challenge;
        try {
            challenge = bridge.post("/internal/enrollments/" + enrollmentId + "/challenges", body);
        } catch (Bridge.Rejected definitive) {
            // Auth durably replays rejected commands too. A later resend must get a fresh command.
            session.removeAuthNote(note("issue"));
            throw definitive;
        }
        session.setAuthNote(note("challenge"), uuid(challenge, "challenge_id"));
        session.setAuthNote(note("version"), Integer.toString(version));
        // Retire only an acknowledged valid result. An uncertain transport/body keeps the command.
        session.removeAuthNote(note("issue"));
    }

    private JsonNode status(RequiredActionContext context, Bridge bridge, String enrollment) {
        var body = command(context, "status"); body.remove("command_id");
        return bridge.post("/internal/enrollments/" + enrollment + "/status", body);
    }
    private boolean complete(RequiredActionContext context, JsonNode status) {
        boolean done = permitsCompletion(purpose, status);
        if (done) context.success();
        return done;
    }
    static boolean permitsCompletion(String purpose, JsonNode status) {
        boolean email = status.path("email_verified").isBoolean() && status.path("email_verified").booleanValue();
        boolean phone = status.path("phone_verified").isBoolean() && status.path("phone_verified").booleanValue();
        return purpose.equals("REGISTRATION_EMAIL") ? email
                : purpose.equals("REGISTRATION_PHONE") && email && phone && "ACTIVE".equals(status.path("state").asText());
    }

    private JsonNode enroll(RequiredActionContext context, Bridge bridge) {
        if (context.getUser().getUsername() == null || !context.getUser().getUsername().matches("\\+9715[024568][0-9]{7}")
                || context.getUser().getEmail() == null || context.getUser().getEmail().length() > 254)
            throw new Bridge.Rejected();
        var body = command(context, "enroll");
        body.put("phone_number", context.getUser().getUsername()); body.put("email", context.getUser().getEmail());
        return bridge.post("/internal/enrollments", body);
    }
    private int version(JsonNode enrollment) {
        int version = enrollment.path(purpose.equals("REGISTRATION_EMAIL") ? "email_contact_version" : "phone_contact_version").asInt(-1);
        if (version < 1) throw new Bridge.Unavailable();
        return version;
    }
    private Map<String, Object> command(RequiredActionContext context, String operation) {
        var session = context.getAuthenticationSession();
        var body = new LinkedHashMap<String, Object>();
        body.put("command_id", stable(session, note(operation)));
        body.put("issuer", Urls.realmIssuer(context.getUriInfo().getBaseUri(), context.getRealm().getName()));
        body.put("subject", context.getUser().getId());
        body.put("authentication_session_id", SessionBinding.of(context.getRealm().getId(), session));
        return body;
    }
    private static String stable(AuthenticationSessionModel session, String key) {
        String value = session.getAuthNote(key);
        if (value == null) { value = UUID.randomUUID().toString(); session.setAuthNote(key, value); }
        return value;
    }
    private String note(String suffix) { return id + "." + suffix; }
    private static String uuid(JsonNode value, String field) {
        try { return UUID.fromString(value.path(field).asText()).toString(); }
        catch (IllegalArgumentException invalid) { throw new Bridge.Unavailable(); }
    }
    private void render(RequiredActionContext context, String message) {
        context.challenge(context.form().setAttribute("verificationKind", purpose.equals("REGISTRATION_EMAIL") ? "email" : "phone")
                .setAttribute("verificationMessage", message == null ? "Verify your contact before continuing." : message)
                .createForm("armoney-code.ftl"));
    }
    @Override public void close() { }
}
