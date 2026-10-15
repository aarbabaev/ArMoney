package com.arman.bank.userservice.infrastructure;

import java.util.Map;
import java.util.Objects;

/** Explicit opt-in only; construction does not register routes or start workers. */
public record VerificationProjectionConfiguration(boolean enabled, Map<String, String> writers) {
    public VerificationProjectionConfiguration {
        writers = Map.copyOf(Objects.requireNonNull(writers));
        if (writers.size() > 16 || (enabled && writers.isEmpty())) throw new IllegalArgumentException("Invalid writer configuration");
        for (var entry : writers.entrySet()) {
            if (!entry.getKey().matches("[A-Za-z0-9._-]{1,64}") || !validKey(entry.getValue()))
                throw new IllegalArgumentException("Invalid writer configuration");
        }
        if (writers.values().stream().distinct().count() != writers.size()) throw new IllegalArgumentException("Ambiguous writer credential");
    }
    private static boolean validKey(String key) { return key != null && key.matches("[!-~]{32,256}"); }
    public static VerificationProjectionConfiguration from(Map<String, String> env) {
        String flag = env.getOrDefault("USER_VERIFICATION_PROJECTION_ENABLED", "false");
        if (!flag.equals("true") && !flag.equals("false")) throw new IllegalArgumentException("Invalid projection enabled flag");
        if (flag.equals("false")) return new VerificationProjectionConfiguration(false, Map.of());
        String key = env.get("AUTH_TO_USER_VERIFICATION_PROJECTION_KEY");
        String writer = env.get("USER_VERIFICATION_PROJECTION_WRITER_ID");
        if (!validKey(key) || writer == null) throw new IllegalArgumentException("Missing projection configuration");
        // Fail closed when the dedicated transport key reuses any configured secret.
        for (var entry : env.entrySet()) {
            String name = entry.getKey();
            if (!name.equals("AUTH_TO_USER_VERIFICATION_PROJECTION_KEY")
                    && (name.contains("KEY") || name.contains("SECRET") || name.contains("PASSWORD"))
                    && key.equals(entry.getValue())) throw new IllegalArgumentException("Projection credential must be distinct");
        }
        return new VerificationProjectionConfiguration(true, Map.of(writer, key));
    }
    @Override public String toString() { return "VerificationProjectionConfiguration[enabled=" + enabled + ",credentials=redacted]"; }
}
