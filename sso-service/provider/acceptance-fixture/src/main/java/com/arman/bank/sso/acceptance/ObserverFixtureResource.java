package com.arman.bank.sso.acceptance;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.keycloak.common.util.Time;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.utils.KeycloakModelUtils;
import org.keycloak.util.JsonSerialization;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/** Destructive session controls ONLY for a separately packaged disposable acceptance image. */
@jakarta.ws.rs.ext.Provider
public final class ObserverFixtureResource {
    private static final Set<String> FIELDS = Set.of("client_id", "provider_root_session_id", "provider_tab_id");
    private static final Set<String> CLIENTS = Set.of("armoney-ios", "armoney-android", "armoney-web");
    private final KeycloakSession requestSession;
    private final BooleanSupplier enabled;
    private final Supplier<String> key;

    ObserverFixtureResource(KeycloakSession session) {
        this(session, () -> "true".equals(System.getenv("OBSERVER_FIXTURE_ENABLED")),
                () -> System.getenv("OBSERVER_FIXTURE_CONTROL_KEY"));
    }
    ObserverFixtureResource(KeycloakSession session, BooleanSupplier enabled, Supplier<String> key) {
        this.requestSession = session; this.enabled = enabled; this.key = key;
    }
    @POST @Path("inspect") @Consumes(MediaType.APPLICATION_JSON) @Produces(MediaType.APPLICATION_JSON)
    public Response inspect(@HeaderParam("X-ArMoney-Observer-Fixture-Key") String supplied, byte[] body) {
        return execute(supplied, body, Operation.INSPECT);
    }
    @POST @Path("delete-tab") @Consumes(MediaType.APPLICATION_JSON)
    public Response deleteTab(@HeaderParam("X-ArMoney-Observer-Fixture-Key") String supplied, byte[] body) {
        return execute(supplied, body, Operation.DELETE_TAB);
    }
    @POST @Path("delete-root") @Consumes(MediaType.APPLICATION_JSON)
    public Response deleteRoot(@HeaderParam("X-ArMoney-Observer-Fixture-Key") String supplied, byte[] body) {
        return execute(supplied, body, Operation.DELETE_ROOT);
    }
    private Response execute(String supplied, byte[] body, Operation operation) {
        if (!enabled.getAsBoolean()) return empty(503);
        String configured = key.get();
        if (configured == null || configured.length() < 32 || configured.length() > 512
                || configured.chars().anyMatch(c -> c < 33 || c > 126)) return empty(503);
        if (supplied == null || supplied.length() > 512 || !MessageDigest.isEqual(
                configured.getBytes(StandardCharsets.UTF_8), supplied.getBytes(StandardCharsets.UTF_8))) return empty(401);
        final Tuple tuple;
        try { tuple = parse(body); }
        catch (IllegalArgumentException | java.io.IOException invalid) { return empty(400); }
        try {
            String realmId = requestSession.getContext().getRealm().getId();
            // The fresh session excludes request-local/uncommitted models. The helper
            // commits before returning; commit exceptions cannot produce a 204/200.
            Map<String, Object> observed = KeycloakModelUtils.runJobInTransactionWithResult(requestSession.getKeycloakSessionFactory(), tx -> {
                var realm = tx.realms().getRealm(realmId);
                if (realm == null) throw new IllegalStateException();
                tx.getContext().setRealm(realm);
                var client = realm.getClientByClientId(tuple.client());
                if (client == null) throw new IllegalStateException();
                var provider = tx.authenticationSessions();
                var root = provider.getRootAuthenticationSession(realm, tuple.root());
                var tab = root == null ? null : root.getAuthenticationSession(client, tuple.tab());
                if (operation == Operation.DELETE_ROOT) {
                    if (root != null) provider.removeRootAuthenticationSession(realm, root);
                    return null;
                }
                if (operation == Operation.DELETE_TAB) {
                    if (tab != null) root.removeAuthenticationSessionByTabId(tuple.tab());
                    return null;
                }
                return inspection(root != null, tab != null, root == null ? null : root.getTimestamp(),
                        tab == null ? null : tab.getAction(), tab != null && tab.getAuthenticatedUser() != null,
                        Time.currentTime());
            });
            return operation == Operation.INSPECT ? Response.ok(observed).header("Cache-Control", "no-store").build() : empty(204);
        } catch (RuntimeException unavailable) { return empty(503); }
    }
    static Map<String, Object> inspection(boolean rootPresent, boolean tabPresent, Integer timestamp,
                                           String action, boolean userPresent, int serverTime) {
        var result = new LinkedHashMap<String, Object>();
        result.put("root_present", rootPresent); result.put("tab_present", tabPresent);
        // Keycloak's NON_NULL mapper suppresses Java null map values. Explicit
        // JSON null nodes preserve the frozen six-field absence observation.
        result.put("root_timestamp", timestamp == null ? com.fasterxml.jackson.databind.node.NullNode.getInstance() : timestamp);
        result.put("action", action == null ? com.fasterxml.jackson.databind.node.NullNode.getInstance() : action);
        result.put("authenticated_user_present", userPresent); result.put("server_time", serverTime);
        return result;
    }
    static Tuple parse(byte[] body) throws java.io.IOException {
        if (body == null || body.length == 0 || body.length > 4096) throw new IllegalArgumentException();
        JsonNode input = JsonSerialization.mapper.copy().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION).readTree(body);
        if (input == null || !input.isObject() || input.size() != FIELDS.size()) throw new IllegalArgumentException();
        input.fieldNames().forEachRemaining(name -> { if (!FIELDS.contains(name)) throw new IllegalArgumentException(); });
        String client = text(input, "client_id");
        if (!CLIENTS.contains(client)) throw new IllegalArgumentException();
        return new Tuple(client, text(input, "provider_root_session_id"), text(input, "provider_tab_id"));
    }
    private static String text(JsonNode input, String field) {
        var value = input.get(field);
        if (value == null || !value.isTextual() || value.textValue().isEmpty() || value.textValue().length() > 255
                || value.textValue().chars().anyMatch(c -> c <= 32 || c >= 127)) throw new IllegalArgumentException();
        return value.textValue();
    }
    private static Response empty(int status) { return Response.status(status).header("Cache-Control", "no-store").build(); }
    record Tuple(String client, String root, String tab) { }
    private enum Operation { INSPECT, DELETE_TAB, DELETE_ROOT }
}
