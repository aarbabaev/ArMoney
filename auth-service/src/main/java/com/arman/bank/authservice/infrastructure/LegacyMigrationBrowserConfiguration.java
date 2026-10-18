package com.arman.bank.authservice.infrastructure;

import java.net.URI;
import java.util.Set;

/** Trusted constructor settings only; no environment activation or caller URLs. */
public record LegacyMigrationBrowserConfiguration(boolean enabled, URI origin, URI legacyCallback, URI managedCallback,
    URI legacyAuthorizationEndpoint, String legacyClientId, URI managedAuthorizationEndpoint, String managedClientId) {
    public static final String FORM = "/migration/legacy";
    public static final String PASSWORD = "/migration/legacy/password";
    public static final String EMAIL_CANDIDATE = "/migration/legacy/email";
    public static final String LEGACY_START = "/migration/legacy/account-oidc/start";
    public static final String LEGACY_CALLBACK = "/migration/legacy/account-oidc/callback";
    public static final String MANAGED_START = "/migration/managed/start";
    public static final String MANAGED_CALLBACK = "/migration/managed/callback";
    public static final Set<String> GET_PATHS = Set.of(FORM, LEGACY_CALLBACK, MANAGED_CALLBACK, "/migration/legacy/verify/status");
    public static final Set<String> POST_PATHS = Set.of(PASSWORD, EMAIL_CANDIDATE, LEGACY_START, MANAGED_START,
        "/migration/legacy/verify/stage", "/migration/legacy/otp/issue", "/migration/legacy/otp/consume",
        "/migration/legacy/verify/decision", "/migration/legacy/verify/publication", "/migration/legacy/verify/final-command", "/migration/legacy/verify/final");

    public LegacyMigrationBrowserConfiguration {
        if (!enabled) {
            if (origin != null || legacyCallback != null || managedCallback != null || legacyAuthorizationEndpoint != null
                || legacyClientId != null || managedAuthorizationEndpoint != null || managedClientId != null) throw rejected();
        } else {
            if (!canonicalHttps(origin) || !origin.getRawPath().isEmpty()) throw rejected();
            if (!exactCallback(legacyCallback, origin.resolve(LEGACY_CALLBACK))
                || !exactCallback(managedCallback, origin.resolve(MANAGED_CALLBACK))) throw rejected();
            if (!canonicalHttps(legacyAuthorizationEndpoint) || legacyAuthorizationEndpoint.getRawPath().isEmpty()
                || !canonicalHttps(managedAuthorizationEndpoint) || managedAuthorizationEndpoint.getRawPath().isEmpty()
                || !client(legacyClientId) || !client(managedClientId)
                || legacyAuthorizationEndpoint.equals(managedAuthorizationEndpoint) && legacyClientId.equals(managedClientId)) throw rejected();
        }
    }
    public static LegacyMigrationBrowserConfiguration disabled() {
        return new LegacyMigrationBrowserConfiguration(false, null, null, null, null, null, null, null);
    }
    private static boolean canonicalHttps(URI value) {
        return value != null && value.isAbsolute() && "https".equals(value.getScheme())
            && value.getHost() != null && value.getHost().length() <= 253 && value.getHost().matches("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?(?:\\.[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)*")
            && value.getRawUserInfo() == null && value.getRawQuery() == null && value.getRawFragment() == null
            && (value.getPort() == -1 || value.getPort() >= 1 && value.getPort() <= 65535)
            && value.toASCIIString().equals("https://" + value.getHost() + (value.getPort() == -1 ? "" : ":" + value.getPort()) + value.getRawPath());
    }
    private static boolean exactCallback(URI actual, URI expected) {
        return canonicalHttps(actual) && actual.toASCIIString().equals(expected.toASCIIString());
    }
    private static boolean client(String value) { return value != null && value.matches("[A-Za-z0-9._-]{1,128}"); }
    /** Called only on producer-created redirects, never a caller-selected URL. */
    void requireAuthorization(URI location, boolean managed) {
        if (!enabled || location == null || location.getRawFragment() != null || location.getRawUserInfo() != null) throw rejected();
        URI endpoint = managed ? managedAuthorizationEndpoint : legacyAuthorizationEndpoint;
        if (location.getRawQuery() == null || !location.toASCIIString().startsWith(endpoint.toASCIIString() + "?")) throw rejected();
        var fields = LegacyMigrationBrowserRoutes.authorizationQuery(location.getRawQuery()).values();
        if (!fields.get("client_id").equals(managed ? managedClientId : legacyClientId)
            || !fields.get("redirect_uri").equals((managed ? managedCallback : legacyCallback).toASCIIString())
            || !fields.get("response_type").equals("code") || !fields.get("scope").equals("openid")
            || !fields.get("code_challenge_method").equals("S256") || !fields.get("prompt").equals("login")
            || !fields.get("max_age").equals("0")) throw rejected();
        for (String name : Set.of("state", "nonce", "code_challenge"))
            if (!LegacyMigrationBrowserRoutes.token(fields.get(name))) throw rejected();
    }
    /** Observed trusted connector fields only; never pass caller Forwarded headers. */
    void requireRequest(String scheme, String serverName, int serverPort, String method, String path, String requestOrigin) {
        if (!enabled || !"https".equals(scheme) || !origin.getHost().equals(serverName)
            || serverPort != (origin.getPort() == -1 ? 443 : origin.getPort())) throw rejected();
        requireBrowserRequest(method, path, requestOrigin);
    }
    /** Only after the dedicated Gateway credential/origin assertion is authenticated. */
    void requireBrowserRequest(String method, String path, String requestOrigin) {
        if (!enabled) throw rejected();
        if (requestOrigin != null && !origin.toASCIIString().equals(requestOrigin)) throw rejected();
        if ("GET".equals(method)) {
            if (!GET_PATHS.contains(path)) throw rejected();
        } else if ("POST".equals(method)) {
            if (!POST_PATHS.contains(path) || !origin.toASCIIString().equals(requestOrigin)) throw rejected();
        } else throw rejected();
    }
    private static IllegalArgumentException rejected() { return new IllegalArgumentException("Migration browser configuration rejected"); }
    @Override public String toString() { return "LegacyMigrationBrowserConfiguration[enabled=" + enabled + "]"; }
}
