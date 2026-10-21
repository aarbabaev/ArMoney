package com.arman.bank.appgateway;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Validated, default-off migration ingress settings. Credentials are never rendered. */
public final class MigrationBrowserConfiguration {
    final URI authOrigin;
    final String publicOrigin;
    final String edgeKey;
    final String authKey;
    private final boolean enabled;
    private Map<String,String> redirectSettings = Map.of();

    private MigrationBrowserConfiguration(boolean enabled, URI authOrigin, String origin, String edge, String auth) {
        this.enabled = enabled; this.authOrigin = authOrigin; this.publicOrigin = origin; this.edgeKey = edge; this.authKey = auth;
    }
    public boolean enabled() { return enabled; }
    public static MigrationBrowserConfiguration from(Map<String,String> env, URI authOrigin) {
        String flag = env.getOrDefault("GATEWAY_MIGRATION_BROWSER_ENABLED", "false");
        if (!Set.of("true", "false").contains(flag)) throw rejected();
        var names = List.of("MIGRATION_PUBLIC_ORIGIN", "MIGRATION_EDGE_TO_GATEWAY_KEY", "MIGRATION_GATEWAY_TO_AUTH_KEY",
            "MIGRATION_LEGACY_AUTHORIZATION_ENDPOINT", "MIGRATION_LEGACY_CLIENT_ID", "MIGRATION_MANAGED_AUTHORIZATION_ENDPOINT", "MIGRATION_MANAGED_CLIENT_ID");
        if (flag.equals("false")) {
            if (names.stream().anyMatch(env::containsKey)) throw rejected();
            return new MigrationBrowserConfiguration(false, null, null, null, null);
        }
        String origin = env.get(names.get(0)), edge = env.get(names.get(1)), auth = env.get(names.get(2));
        URI publicUri;
        try { publicUri = URI.create(origin); } catch (Exception invalid) { throw rejected(); }
        if (!origin(publicUri, true) || !origin(authOrigin, false) || !key(edge) || !key(auth) || aliases(edge, auth)) throw rejected();
        for (var entry : env.entrySet()) {
            String name = entry.getKey().toUpperCase(Locale.ROOT);
            if (names.contains(entry.getKey())) continue;
            if ((name.contains("KEY") || name.contains("SECRET") || name.contains("TOKEN") || name.contains("PASSWORD"))
                && (aliases(edge, entry.getValue()) || aliases(auth, entry.getValue()))) throw rejected();
        }
        var result = new MigrationBrowserConfiguration(true, authOrigin, origin, edge, auth);
        var redirects = new HashMap<String,String>();
        for (String mode : List.of("LEGACY", "MANAGED")) {
            String endpoint = env.get("MIGRATION_" + mode + "_AUTHORIZATION_ENDPOINT");
            String client = env.get("MIGRATION_" + mode + "_CLIENT_ID");
            URI uri;
            try { uri = URI.create(endpoint); } catch (Exception invalid) { throw rejected(); }
            if (!"https".equals(uri.getScheme()) || uri.getHost() == null || uri.getRawPath().isEmpty()
                || uri.getRawQuery() != null || uri.getRawFragment() != null || uri.getRawUserInfo() != null
                || !uri.normalize().equals(uri)
                || !origin(URI.create("https://" + uri.getRawAuthority()), true)
                || !uri.toASCIIString().equals("https://" + uri.getRawAuthority() + uri.getRawPath())
                || client == null || !client.matches("[A-Za-z0-9._-]{1,128}")) throw rejected();
            redirects.put(mode + "_ENDPOINT", endpoint); redirects.put(mode + "_CLIENT", client);
        }
        result.redirectSettings = Map.copyOf(redirects);
        return result;
    }
    void requireLocation(String location, String path, int status) {
        boolean managed = path.equals("/migration/managed/start");
        if (!(managed || path.equals("/migration/legacy/account-oidc/start")) || status != 303) throw rejected();
        String mode = managed ? "MANAGED" : "LEGACY", prefix = redirectSettings.get(mode + "_ENDPOINT") + "?";
        if (location == null || location.length() > 4096 || !location.startsWith(prefix) || location.indexOf('#') >= 0) throw rejected();
        var fields = new HashMap<String,String>();
        for (String pair : location.substring(prefix.length()).split("&", -1)) {
            int index = pair.indexOf('='); if (index <= 0) throw rejected();
            String name = java.net.URLDecoder.decode(pair.substring(0,index), StandardCharsets.UTF_8);
            String value = java.net.URLDecoder.decode(pair.substring(index+1), StandardCharsets.UTF_8);
            if (fields.putIfAbsent(name,value) != null) throw rejected();
        }
        if (!fields.keySet().equals(Set.of("client_id", "redirect_uri", "response_type", "scope", "state", "nonce", "code_challenge", "code_challenge_method", "prompt", "max_age"))
            || !redirectSettings.get(mode + "_CLIENT").equals(fields.get("client_id"))
            || !(publicOrigin + (managed ? "/migration/managed/callback" : "/migration/legacy/account-oidc/callback")).equals(fields.get("redirect_uri"))
            || !"code".equals(fields.get("response_type")) || !"openid".equals(fields.get("scope"))
            || !"S256".equals(fields.get("code_challenge_method")) || !"login".equals(fields.get("prompt")) || !"0".equals(fields.get("max_age"))) throw rejected();
        for (String name : List.of("state", "nonce", "code_challenge")) {
            String token = fields.get(name);
            if (!token.matches("[A-Za-z0-9_-]{43}") || !Base64.getUrlEncoder().withoutPadding().encodeToString(Base64.getUrlDecoder().decode(token)).equals(token)) throw rejected();
        }
    }
    private static boolean origin(URI uri, boolean https) {
        return uri != null && ("https".equals(uri.getScheme()) || !https && "http".equals(uri.getScheme()))
            && uri.getHost() != null && uri.getHost().length() <= 253
            && uri.getHost().matches("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?(?:\\.[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)*")
            && uri.getRawUserInfo() == null && uri.getRawQuery() == null && uri.getRawFragment() == null
            && uri.getRawPath().isEmpty() && (uri.getPort() == -1 || uri.getPort() > 0 && uri.getPort() <= 65535)
            && uri.toASCIIString().equals(uri.getScheme() + "://" + uri.getHost() + (uri.getPort() == -1 ? "" : ":" + uri.getPort()));
    }
    private static boolean key(String value) { return value != null && value.matches("[A-Za-z0-9_-]{43,128}"); }
    private static Set<String> forms(String value) {
        var values = new HashSet<String>();
        if (value == null) return values;
        values.add(value);
        for (var decoder : List.of(Base64.getDecoder(), Base64.getUrlDecoder())) {
            try { values.add(new String(decoder.decode(value), StandardCharsets.ISO_8859_1)); } catch (IllegalArgumentException ignored) { }
        }
        return values;
    }
    private static boolean aliases(String first, String second) {
        var values = forms(first); values.retainAll(forms(second)); return !values.isEmpty();
    }
    boolean accepts(String supplied) {
        return supplied != null && MessageDigest.isEqual(edgeKey.getBytes(StandardCharsets.US_ASCII), supplied.getBytes(StandardCharsets.US_ASCII));
    }
    private static IllegalArgumentException rejected() { return new IllegalArgumentException("Migration browser configuration rejected"); }
    @Override public String toString() { return "MigrationBrowserConfiguration[enabled=" + enabled + "]"; }
}
