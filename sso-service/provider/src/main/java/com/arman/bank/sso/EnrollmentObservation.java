package com.arman.bank.sso;

import com.fasterxml.jackson.databind.JsonNode;
import org.keycloak.models.*;
import org.keycloak.sessions.AuthenticationSessionModel;
import org.keycloak.services.Urls;
import org.keycloak.services.managers.ClientSessionCode;
import org.keycloak.models.utils.SessionExpiration;
import org.keycloak.common.util.Time;
import java.time.Instant;
import java.util.*;

/** Read-only pre-token observations. Neither this DTO nor a provider attribute is a receipt. */
final class EnrollmentObservation {
    private static final Set<String> CONTEXT = Set.of("issuer", "subject", "client_id",
            "provider_root_session_id", "provider_tab_id", "authentication_session_id");
    private static final Set<String> RESULT = Set.of("enrollment_id", "version", "state",
            "email_contact_version", "phone_contact_version", "email_verified", "phone_verified");
    private static final Set<String> STATES = Set.of("PENDING_CONTACTS", "PROJECTION_PENDING", "PROJECTED",
            "SUSPENDED", "BLOCKED_MIGRATION");
    private static final Set<String> CLIENTS = Set.of("armoney-ios", "armoney-android", "armoney-web");

    record Binding(String issuer, String subject, String clientId, String rootId, String tabId, String digest) {
        Binding {
            identifier(issuer, 512); identifier(subject, 255); identifier(clientId, 255);
            identifier(rootId, 255); identifier(tabId, 255);
            var uri = java.net.URI.create(issuer);
            if (!uri.isAbsolute() || uri.getHost() == null || uri.getUserInfo() != null
                    || uri.getQuery() != null || uri.getFragment() != null
                    || !("https".equals(uri.getScheme()) || "http".equals(uri.getScheme()))) throw new IllegalArgumentException();
            SessionBinding.decode(digest);
        }
        Map<String, Object> fields() {
            var result = new LinkedHashMap<String, Object>();
            result.put("issuer", issuer); result.put("subject", subject); result.put("client_id", clientId);
            result.put("provider_root_session_id", rootId); result.put("provider_tab_id", tabId);
            result.put("authentication_session_id", digest); return result;
        }
    }

    static Binding parse(JsonNode input) {
        exact(input, CONTEXT);
        return new Binding(text(input,"issuer",512), text(input,"subject",255), text(input,"client_id",255),
                text(input,"provider_root_session_id",255), text(input,"provider_tab_id",255),
                text(input,"authentication_session_id",43));
    }

    static JsonNode enrollment(JsonNode input) {
        try {
            exact(input, RESULT);
            String id = text(input,"enrollment_id",36);
            if (!UUID.fromString(id).toString().equals(id)) throw new IllegalArgumentException();
            for (String field : List.of("version", "email_contact_version", "phone_contact_version")) {
                JsonNode value = input.get(field);
                if (!value.isIntegralNumber() || !value.canConvertToInt() || value.intValue() < 1)
                    throw new IllegalArgumentException();
            }
            if (!STATES.contains(text(input,"state",32)) || !input.get("email_verified").isBoolean()
                    || !input.get("phone_verified").isBoolean()) throw new IllegalArgumentException();
            return input;
        } catch (IllegalArgumentException invalid) { throw new Bridge.Unavailable(); }
    }

    static Map<String, Object> observe(KeycloakSession session, Binding binding, String key) {
        RealmModel realm = session.getContext().getRealm();
        String issuer = Urls.realmIssuer(session.getContext().getUri().getBaseUri(), realm.getName());
        if (!issuer.equals(binding.issuer()) || !CLIENTS.contains(binding.clientId())) throw new Bridge.Unavailable();
        ClientModel client = realm.getClientByClientId(binding.clientId());
        if (client == null || !client.isEnabled() || !client.isStandardFlowEnabled()) throw new Bridge.Unavailable();
        var root = session.authenticationSessions().getRootAuthenticationSession(realm, binding.rootId());
        if (root == null || !binding.rootId().equals(root.getId())) throw new Bridge.Unavailable();
        AuthenticationSessionModel auth = root.getAuthenticationSession(client, binding.tabId());
        if (auth == null || !binding.tabId().equals(auth.getTabId()) || auth.getClient() == null
                || !client.getId().equals(auth.getClient().getId())
                || !"REQUIRED_ACTIONS".equals(auth.getAction())) throw new Bridge.Unavailable();
        UserModel user = auth.getAuthenticatedUser();
        if (user == null || !binding.subject().equals(user.getId()) || !user.isEnabled()) throw new Bridge.Unavailable();
        String expected = SessionBinding.derive(key, realm.getId(), root.getId(), auth.getTabId(), client.getId());
        if (!SessionBinding.matches(expected, binding.digest())) throw new Bridge.Unavailable();
        String phone = user.getUsername(), email = user.getEmail();
        if (!canonicalPhone(phone) || !canonicalEmail(email)) throw new Bridge.Unavailable();
        int timestamp = root.getTimestamp();
        long now = Time.currentTime();
        long deadline = deadline(timestamp, now, realm.getAccessCodeLifespanUserAction(),
                SessionExpiration.getAuthSessionExpiration(realm, timestamp));
        if (!new ClientSessionCode<AuthenticationSessionModel>(session, realm, auth)
                .isActionActive(ClientSessionCode.ActionType.USER)) throw new Bridge.Unavailable();
        var result = binding.fields();
        result.put("username", phone); result.put("email", email); result.put("phone_number", phone);
        result.put("enabled", true); result.put("authentication_session_active", true);
        result.put("authentication_session_expires_at", Instant.ofEpochSecond(deadline).toString());
        return result;
    }

    static long deadline(long timestamp, long now, long userLifespan, long cacheExpiry) {
        try {
            if (timestamp <= 0 || timestamp > now || userLifespan <= 0 || cacheExpiry <= timestamp)
                throw new Bridge.Unavailable();
            long deadline = Math.min(cacheExpiry, Math.addExact(timestamp, userLifespan));
            if (now >= deadline) throw new Bridge.Unavailable();
            return deadline;
        } catch (ArithmeticException invalid) { throw new Bridge.Unavailable(); }
    }

    static boolean canonicalPhone(String value) { return value != null && value.matches("\\+9715[024568][0-9]{7}"); }
    static boolean canonicalEmail(String value) {
        return value != null && value.length() <= 254 && value.equals(value.strip())
                && value.equals(value.toLowerCase(Locale.ROOT))
                && value.matches("[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]+@[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?(?:\\.[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?)+");
    }
    private static void exact(JsonNode input, Set<String> fields) {
        if (input == null || !input.isObject() || input.size() != fields.size()) throw new IllegalArgumentException();
        input.fieldNames().forEachRemaining(name -> { if (!fields.contains(name)) throw new IllegalArgumentException(); });
    }
    private static String text(JsonNode input, String field, int limit) {
        JsonNode value = input.get(field);
        if (value == null || !value.isTextual()) throw new IllegalArgumentException();
        return identifier(value.textValue(), limit);
    }
    private static String identifier(String value, int limit) {
        if (value == null || value.isBlank() || value.length() > limit
                || value.chars().anyMatch(c -> c <= 32 || c >= 127)) throw new IllegalArgumentException();
        return value;
    }
    private EnrollmentObservation() { }
}
