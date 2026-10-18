package com.arman.bank.authservice.infrastructure;

import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Private Gateway hop ONLY. No public-TLS fallback, scheme override or identity assertion. */
public final class LegacyMigrationBrowserIngressConfiguration {
    public static final String KEY_HEADER = "X-ArMoney-Migration-Auth-Key";
    public static final String ORIGIN_HEADER = "X-ArMoney-Migration-Origin";
    private static final String FLAG = "AUTH_MIGRATION_BROWSER_ENABLED";
    private static final String ORIGIN = "MIGRATION_PUBLIC_ORIGIN";
    private static final String KEY = "MIGRATION_GATEWAY_TO_AUTH_KEY";
    private final boolean enabled;
    private final URI publicOrigin;
    private final byte[] key;
    private LegacyMigrationBrowserIngressConfiguration(boolean enabled, URI origin, String key) {
        this.enabled = enabled; this.publicOrigin = origin;
        this.key = key == null ? null : key.getBytes(StandardCharsets.US_ASCII);
    }
    public static LegacyMigrationBrowserIngressConfiguration disabled() {
        return new LegacyMigrationBrowserIngressConfiguration(false, null, null);
    }
    public static LegacyMigrationBrowserIngressConfiguration from(Map<String,String> env) {
        Objects.requireNonNull(env);
        require(env.keySet().stream().allMatch(Objects::nonNull));
        String flag = env.getOrDefault(FLAG, "false");
        require("true".equals(flag) || "false".equals(flag));
        if (flag.equals("false")) {
            require(!env.containsKey(ORIGIN) && !env.containsKey(KEY));
            return disabled();
        }
        String secret = env.get(KEY), origin = env.get(ORIGIN);
        require(key(secret));
        URI uri;
        try { uri = URI.create(origin); } catch (Exception malformed) { throw rejected(); }
        require(canonicalOrigin(uri));
        for (var entry : env.entrySet()) {
            if (entry.getKey().equals(KEY)) continue;
            String name = entry.getKey().toUpperCase(Locale.ROOT);
            if (name.contains("KEY") || name.contains("SECRET") || name.contains("TOKEN") || name.contains("PASSWORD")) {
                var common = forms(secret); common.retainAll(forms(entry.getValue())); require(common.isEmpty());
            }
        }
        return new LegacyMigrationBrowserIngressConfiguration(true, uri, secret);
    }
    public boolean enabled() { return enabled; }
    public URI publicOrigin() { return publicOrigin; }
    private static boolean key(String value) { return value != null && value.matches("[A-Za-z0-9_-]{43,128}"); }
    private static boolean canonicalOrigin(URI uri) {
        return uri != null && "https".equals(uri.getScheme()) && uri.getHost() != null && uri.getHost().length() <= 253
            && uri.getHost().matches("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?(?:\\.[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)*")
            && uri.getRawUserInfo() == null && uri.getRawQuery() == null && uri.getRawFragment() == null
            && uri.getRawPath().isEmpty() && (uri.getPort() == -1 || uri.getPort() > 0 && uri.getPort() <= 65535)
            && uri.toASCIIString().equals("https://" + uri.getHost() + (uri.getPort() == -1 ? "" : ":" + uri.getPort()));
    }
    private static Set<String> forms(String value) {
        var result = new HashSet<String>();
        if (value == null) return result;
        result.add(value);
        for (var decoder : List.of(Base64.getDecoder(), Base64.getUrlDecoder())) {
            try { result.add(new String(decoder.decode(value), StandardCharsets.ISO_8859_1)); }
            catch (IllegalArgumentException notEncoded) { /* Raw alias remains checked. */ }
        }
        return result;
    }
    /** Authenticate before body/cookie/CSRF/facade. Does not authenticate a user. */
    void requireGateway(HttpServletRequest request) {
        require(enabled && request != null);
        var credentials = Collections.list(request.getHeaders(KEY_HEADER));
        var assertions = Collections.list(request.getHeaders(ORIGIN_HEADER));
        require(credentials.size() == 1 && assertions.size() == 1);
        String supplied = credentials.getFirst(); require(key(supplied));
        require(MessageDigest.isEqual(key, supplied.getBytes(StandardCharsets.US_ASCII)));
        require(publicOrigin.toASCIIString().equals(assertions.getFirst()));
        var names = Collections.list(request.getHeaderNames());
        int total = 0;
        for (String name : names) {
            require(name != null && name.length() <= 128);
            String lower = name.toLowerCase(Locale.ROOT);
            require(!lower.equals("forwarded") && !lower.startsWith("x-forwarded-")
                && !lower.startsWith("x-identity-") && !lower.equals("x-service-key")
                && !lower.equals("authorization") && !lower.equals("x-user-id")
                && !lower.equals("x-authenticated-user")
                && (!lower.startsWith("x-armoney-migration-")
                    || lower.equals(KEY_HEADER.toLowerCase(Locale.ROOT)) || lower.equals(ORIGIN_HEADER.toLowerCase(Locale.ROOT))));
            for (String value : Collections.list(request.getHeaders(name))) {
                require(value != null && value.length() <= 8192 && value.chars().allMatch(c -> c >= 32 && c <= 126));
                total = Math.addExact(total, name.length() + value.length()); require(total <= 16384);
            }
        }

    }
    private static void require(boolean ok) { if (!ok) throw rejected(); }
    private static IllegalArgumentException rejected() { return new IllegalArgumentException("Migration ingress rejected"); }
    @Override public String toString() { return "LegacyMigrationBrowserIngressConfiguration[enabled=" + enabled + ",credentials=redacted]"; }
}
