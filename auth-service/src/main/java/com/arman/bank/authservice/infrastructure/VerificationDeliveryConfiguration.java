package com.arman.bank.authservice.infrastructure;

import com.arman.bank.runtime.Database;
import java.net.URI;
import java.time.Clock;
import java.util.*;

/** Independent opt-in delivery; never changes bridge, notices, proof or eligibility flags. */
public final class VerificationDeliveryConfiguration {
    public final boolean enabled;
    final EmailConfiguration email;
    final URI smsBase;
    final String smsToken;
    private final VerificationCrypto crypto;
    public VerificationDeliveryConfiguration(Map<String, String> environment) {
        String flag = environment.getOrDefault("AUTH_VERIFICATION_DELIVERY_ENABLED", "false");
        if (!Set.of("true", "false").contains(flag)) throw new IllegalArgumentException("Invalid verification delivery flag");
        enabled = flag.equals("true");
        if (!enabled) { email = null; smsBase = null; smsToken = null; crypto = null; return; }
        // A private configuration copy reuses sandbox validation without enabling login notices.
        var smtp = new HashMap<>(environment);
        smtp.put("LOGIN_NOTIFICATIONS_ENABLED", "true");
        email = new EmailConfiguration(smtp);
        smsBase = smsOrigin(environment.getOrDefault("VERIFICATION_SMS_BASE_URL", "http://sms-sandbox:8080"));
        smsToken = required(environment, "VERIFICATION_SMS_SEND_TOKEN");
        if (!smsToken.matches("[A-Za-z0-9_-]{32,128}")) throw new IllegalArgumentException("Invalid verification SMS sender token");
        for (String name : List.of("SMS_SANDBOX_READ_TOKEN", "SSO_VERIFICATION_BRIDGE_KEY", "AUTH_TO_SSO_STATUS_KEY",
                "SSO_LOGIN_PROOF_WRITE_KEY", "SSO_LOGIN_PROOF_RESOLVE_KEY", "INTERNAL_AUTH_KEY", "SSO_CLIENT_SECRET",
                "ARMONEY_PROVIDER_BINDING_KEY", "VERIFICATION_OTP_HMAC_KEY", "VERIFICATION_DELIVERY_ENCRYPTION_KEY", "MAILTRAP_SMTP_PASSWORD"))
            if (smsToken.equals(environment.get(name))) throw new IllegalArgumentException("Verification SMS requires a dedicated sender token");
        String id = required(environment, "VERIFICATION_KEY_ID");
        crypto = new VerificationCrypto(id, Map.of(id, key(environment, "VERIFICATION_OTP_HMAC_KEY")),
            Map.of(id, key(environment, "VERIFICATION_DELIVERY_ENCRYPTION_KEY")));
    }
    static URI smsOrigin(String value) {
        try {
            URI uri = URI.create(value);
            // The approved private Compose service only. Loopback fixtures use a package-private transport seam.
            if (!"http".equals(uri.getScheme()) || !"sms-sandbox".equals(uri.getHost()) || uri.getPort() != 8080
                    || uri.getUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null
                    || !(uri.getRawPath().isEmpty() || "/".equals(uri.getRawPath()))) throw new IllegalArgumentException();
            return uri;
        } catch (RuntimeException invalid) { throw new IllegalArgumentException("Invalid private verification SMS origin"); }
    }
    public VerificationDeliveryWorker worker(Database database) {
        if (!enabled) return null;
        var store = new PostgresVerificationStore(database, crypto);
        var transport = new VerificationDeliveryTransport(email, smsBase, smsToken);
        try { return new VerificationDeliveryWorker(store, crypto, transport, Clock.systemUTC()); }
        catch (RuntimeException failure) { transport.close(); throw failure; }
    }
    private static String required(Map<String, String> environment, String name) {
        String value = environment.get(name);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing " + name);
        return value;
    }
    private static byte[] key(Map<String, String> environment, String name) {
        try {
            String encoded = required(environment, name);
            byte[] value = Base64.getDecoder().decode(encoded);
            if (value.length != 32 || !Base64.getEncoder().encodeToString(value).equals(encoded)) throw new IllegalArgumentException();
            return value;
        } catch (RuntimeException invalid) { throw new IllegalArgumentException("Invalid " + name); }
    }
    @Override public String toString() { return "VerificationDeliveryConfiguration[redacted]"; }
}
