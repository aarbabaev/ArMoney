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
    private final java.util.function.BooleanSupplier enabled;
    private final java.util.function.Predicate<RequiredActionContext> registration;
    private final java.util.function.Consumer<RequiredActionContext> continuation;
    private final java.util.function.BiFunction<RequiredActionContext,String,JsonNode> routingStatus;
    private final java.util.function.Supplier<String> routingKey;
    VerificationAction(String purpose, String id) { this(purpose, id, Bridge::enabled,CodeIntentRegistrationGuard::permits); }
    VerificationAction(String purpose, String id, java.util.function.BooleanSupplier enabled) {
        this(purpose,id,enabled,CodeIntentRegistrationGuard::permits);
    }
    /** Explicit synthetic guard seam; production factories always use the genuine creation guard. */
    VerificationAction(String purpose,String id,java.util.function.BooleanSupplier enabled,java.util.function.Predicate<RequiredActionContext> registration){
        this(purpose,id,enabled,registration,CodeIntentRegistrationContinuation::show);
    }
    VerificationAction(String purpose,String id,java.util.function.BooleanSupplier enabled,java.util.function.Predicate<RequiredActionContext> registration,java.util.function.Consumer<RequiredActionContext> continuation){
        this(purpose,id,enabled,registration,continuation,null,()->Bridge.required("ARMONEY_PROVIDER_BINDING_KEY"));
    }
    /** Synthetic collaborators exercise the same routing codec and fresh-version checks. */
    VerificationAction(String purpose,String id,java.util.function.BooleanSupplier enabled,java.util.function.Predicate<RequiredActionContext> registration,java.util.function.Consumer<RequiredActionContext> continuation,
            java.util.function.BiFunction<RequiredActionContext,String,JsonNode> routingStatus,java.util.function.Supplier<String> routingKey){
        this.purpose = purpose; this.id = id; this.enabled = enabled;this.registration=registration;this.continuation=continuation;this.routingStatus=routingStatus;this.routingKey=routingKey;
    }
    @Override public void evaluateTriggers(RequiredActionContext context) {
        if (enabled.getAsBoolean()&&registration.test(context)&&!completedEmailRoute(context)) context.getAuthenticationSession().addRequiredAction(id);
    }
    @Override public void requiredActionChallenge(RequiredActionContext context) {
        if (!enabled.getAsBoolean()||!registration.test(context)) { context.failure(); return; }
        // A separate private HTTP request can only observe committed state. Do not
        // bootstrap authority inside the request which first renders this action.
        render(context, null);
    }

    @Override public void processAction(RequiredActionContext context) {
        if (!enabled.getAsBoolean()||!registration.test(context)) { context.failure(); return; }
        String operation=null;
        try {
            operation=operation(context);
            if("status".equals(operation)){projectionStatus(context);return;}
            var session = context.getAuthenticationSession();
            var bridge = Bridge.configured();
            // Re-resolve against current server contacts on every submission. UUID alone grants nothing.
            JsonNode enrollment = enroll(context, bridge);
            String enrollmentId = uuid(enrollment, "enrollment_id");
            if (complete(context, status(context, bridge, enrollmentId))) return;
            int version = version(enrollment);
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
            JsonNode current=status(context,bridge,enrollmentId);
            if (!complete(context,current)){
                rememberProjection(context,current);
                renderPending(context);
            }
        } catch (Bridge.Rejected rejected) { if("verify".equals(operation))context.getAuthenticationSession().removeAuthNote(note("consume")); render(context, "Code not accepted. Request a new code or try again."); }
        catch (RuntimeException unavailable) { render(context, "Verification is unavailable. Please try again."); }
    }
    static String operation(RequiredActionContext context){
        var values=context.getHttpRequest().getDecodedFormParameters().get("operation");
        if(values==null||values.size()!=1||!Set.of("send","verify","status").contains(values.getFirst()))throw new Bridge.Rejected();
        return values.getFirst();
    }
    /** Written only after actual PHONE CONSUMED acknowledgement and a fresh dual-receipt status. */
    void rememberProjection(RequiredActionContext context,JsonNode input){
        JsonNode current=projectionFacts(input);
        if(!registration.test(context)||!"PROJECTION_PENDING".equals(current.path("state").textValue()))throw new Bridge.Unavailable();
        String enrollment=uuid(current,"enrollment_id");int email=current.path("email_contact_version").intValue(),phone=current.path("phone_contact_version").intValue();
        try{
            context.getAuthenticationSession().setAuthNote(note("projection-pending"),org.keycloak.util.JsonSerialization.writeValueAsString(Map.of(
                    "enrollment_id",enrollment,"email_version",email,"phone_version",phone,"binding",projectionBinding(context,enrollment,email,phone))));
        }catch(Exception unavailable){throw new Bridge.Unavailable();}
    }
    private void projectionStatus(RequiredActionContext context){
        try{
            String pending=context.getAuthenticationSession().getAuthNote(note("projection-pending"));
            if(pending==null||pending.length()>2048)throw new Bridge.Unavailable();
            JsonNode marker=Bridge.strictJson(pending.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            LoginProof.fields(marker,Set.of("enrollment_id","email_version","phone_version","binding"));
            String enrollment=LoginProof.uuid(LoginProof.text(marker,"enrollment_id")).toString();
            int email=markerVersion(marker,"email_version"),phone=markerVersion(marker,"phone_version");
            if(!SessionBinding.matches(projectionBinding(context,enrollment,email,phone),LoginProof.text(marker,"binding")))throw new Bridge.Unavailable();
            JsonNode observed;
            try{
                observed=routingStatus==null?Bridge.configured().post("/internal/enrollments/"+enrollment+"/status",bindingFields(context)):routingStatus.apply(context,enrollment);
            }catch(Bridge.Unavailable unavailable){
                // Only the already validated original routing marker permits retry UI.
                // No current authority is inferred from an unavailable observation.
                render(context,"Status is temporarily unavailable. Check status to try again.",true);return;
            }
            JsonNode current=projectionFacts(observed);
            if(!enrollment.equals(uuid(current,"enrollment_id"))||email!=current.path("email_contact_version").intValue()||phone!=current.path("phone_contact_version").intValue())throw new Bridge.Unavailable();
            if(!complete(context,current))renderPending(context);
        }catch(Bridge.Rejected|Bridge.Unavailable refusal){throw refusal;}
        catch(Exception unavailable){throw new Bridge.Unavailable();}
    }
    private JsonNode projectionFacts(JsonNode input){
        JsonNode current=EnrollmentObservation.enrollment(input);
        if(!"REGISTRATION_PHONE".equals(purpose)||!current.path("email_verified").booleanValue()||!current.path("phone_verified").booleanValue()
                ||!Set.of("PROJECTION_PENDING","PROJECTED").contains(current.path("state").textValue()))throw new Bridge.Unavailable();
        return current;
    }
    private static int markerVersion(JsonNode marker,String name){JsonNode version=marker.get(name);if(version==null||!version.isIntegralNumber()||!version.canConvertToInt()||version.intValue()<1)throw new Bridge.Unavailable();return version.intValue();}
    private String projectionBinding(RequiredActionContext context,String enrollment,int email,int phone){
        var auth=context.getAuthenticationSession();var user=context.getUser();
        if(!EnrollmentObservation.canonicalEmail(user.getEmail())||!EnrollmentObservation.canonicalPhone(user.getUsername()))throw new Bridge.Unavailable();
        return SessionBinding.derive(routingKey.get(),"registration-projection-route-v1",purpose,context.getRealm().getId(),auth.getParentSession().getId(),auth.getTabId(),auth.getClient().getId(),user.getId(),user.getEmail(),user.getUsername(),Integer.toString(email),Integer.toString(phone),enrollment);
    }
    private void renderPending(RequiredActionContext context){render(context,"Contact verified. Account activation is pending; check status to continue.",true);}

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
        return bridge.enrollment("/internal/enrollments/" + enrollment + "/status", body);
    }
    boolean complete(RequiredActionContext context, JsonNode status) {
        boolean done = permitsCompletion(purpose, status);
        if(done){
            if(!registration.test(context))throw new Bridge.Unavailable();
            if("REGISTRATION_PHONE".equals(purpose))continuation.accept(context);else{
                rememberEmailRoute(context,status);
                context.success();
            }
        }
        return done;
    }
    /** UI routing only. A retained marker cannot replace a fresh authoritative status/version. */
    private boolean completedEmailRoute(RequiredActionContext context){
        if(!"REGISTRATION_EMAIL".equals(purpose))return false;
        var auth=context.getAuthenticationSession();String pending=auth.getAuthNote(note("routing-complete"));
        if(pending==null)return false; // Initial registration never performs remote I/O in evaluateTriggers.
        try{
            if(pending.length()>2048)throw new Bridge.Unavailable();
            JsonNode marker=Bridge.strictJson(pending.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            LoginProof.fields(marker,Set.of("enrollment_id","contact_version","binding"));
            String enrollment=LoginProof.uuid(LoginProof.text(marker,"enrollment_id")).toString();
            JsonNode encodedVersion=marker.get("contact_version");
            if(!encodedVersion.isIntegralNumber()||!encodedVersion.canConvertToInt()||encodedVersion.intValue()<1)throw new Bridge.Unavailable();
            int version=encodedVersion.intValue();
            if(!SessionBinding.matches(routeBinding(context,enrollment,version),LoginProof.text(marker,"binding")))throw new Bridge.Unavailable();
            JsonNode current=routingStatus==null?status(context,Bridge.configured(),enrollment):routingStatus.apply(context,enrollment);
            current=EnrollmentObservation.enrollment(current);
            if(!enrollment.equals(uuid(current,"enrollment_id"))||version!=current.path("email_contact_version").intValue()
                    ||!permitsCompletion(purpose,current))throw new Bridge.Unavailable();
            return true;
        }catch(Exception unavailable){auth.removeAuthNote(note("routing-complete"));return false;}
    }
    private void rememberEmailRoute(RequiredActionContext context,JsonNode status){
        if(!"REGISTRATION_EMAIL".equals(purpose))throw new Bridge.Unavailable();
        JsonNode current=EnrollmentObservation.enrollment(status);
        String enrollment=uuid(current,"enrollment_id");int version=current.path("email_contact_version").intValue();
        try{
            String encoded=org.keycloak.util.JsonSerialization.writeValueAsString(Map.of("enrollment_id",enrollment,"contact_version",version,"binding",routeBinding(context,enrollment,version)));
            context.getAuthenticationSession().setAuthNote(note("routing-complete"),encoded);
        }catch(Exception unavailable){throw new Bridge.Unavailable();}
    }
    private String routeBinding(RequiredActionContext context,String enrollment,int version){
        var auth=context.getAuthenticationSession();
        return SessionBinding.derive(routingKey.get(),"registration-purpose-route-v1",purpose,context.getRealm().getId(),auth.getParentSession().getId(),auth.getTabId(),auth.getClient().getId(),
                context.getUser().getId(),context.getUser().getEmail(),Integer.toString(version),enrollment);
    }
    static boolean permitsCompletion(String purpose, JsonNode status) {
        boolean email = status.path("email_verified").isBoolean() && status.path("email_verified").booleanValue();
        boolean phone = status.path("phone_verified").isBoolean() && status.path("phone_verified").booleanValue();
        return purpose.equals("REGISTRATION_EMAIL") ? email
                : purpose.equals("REGISTRATION_PHONE") && email && phone && "PROJECTED".equals(status.path("state").asText());
    }

    private JsonNode enroll(RequiredActionContext context, Bridge bridge) {
        if (!EnrollmentObservation.canonicalPhone(context.getUser().getUsername())
                || !EnrollmentObservation.canonicalEmail(context.getUser().getEmail()))
            throw new Bridge.Rejected();
        var body = command(context, "enroll");
        body.put("phone_number", context.getUser().getUsername()); body.put("email", context.getUser().getEmail());
        return bridge.enrollment("/internal/enrollments", body);
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
        body.putAll(bindingFields(context));return body;
    }
    private Map<String,Object> bindingFields(RequiredActionContext context){
        var session=context.getAuthenticationSession();var body=new LinkedHashMap<String,Object>();
        body.put("issuer", Urls.realmIssuer(context.getUriInfo().getBaseUri(), context.getRealm().getName()));
        body.put("subject", context.getUser().getId());
        body.put("client_id", session.getClient().getClientId());
        body.put("provider_root_session_id", session.getParentSession().getId());
        body.put("provider_tab_id", session.getTabId());
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
        render(context,message,false);
    }
    private void render(RequiredActionContext context,String message,boolean pending){
        context.challenge(context.form().setAttribute("verificationKind", purpose.equals("REGISTRATION_EMAIL") ? "email" : "phone")
                .setAttribute("verificationMessage", message == null ? "Verify your contact before continuing." : message)
                .setAttribute("verificationPending",pending)
                .createForm("armoney-code.ftl"));
    }
    @Override public void close() { }
}
