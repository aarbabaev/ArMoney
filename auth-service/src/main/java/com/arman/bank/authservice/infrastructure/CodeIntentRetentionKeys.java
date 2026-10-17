package com.arman.bank.authservice.infrastructure;

import com.arman.bank.authservice.domain.LoginProof;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Bounded retention-key ring. Old records always name their original key; no fallback search. */
public final class CodeIntentRetentionKeys {
    private final String current;
    private final Map<String, byte[]> keys;
    public CodeIntentRetentionKeys(String current, Map<String, String> encoded) {
        if (current == null || encoded == null || encoded.isEmpty() || encoded.size() > 8 || !encoded.containsKey(current))
            throw new IllegalArgumentException("Invalid intent retention key ring");
        var copy = new HashMap<String, byte[]>();
        encoded.forEach((id, value) -> {
            if (id == null || !id.matches("[A-Za-z0-9_-]{1,32}") || value == null)
                throw new IllegalArgumentException("Invalid intent retention key ring");
            byte[] bytes;
            try { bytes = Base64.getDecoder().decode(value); }
            catch (IllegalArgumentException invalid) { throw new IllegalArgumentException("Invalid intent retention key ring"); }
            if (bytes.length != 32 || !Base64.getEncoder().encodeToString(bytes).equals(value))
                throw new IllegalArgumentException("Invalid intent retention key ring");
            copy.put(id, bytes);
        });
        this.current = current; this.keys = Map.copyOf(copy);
    }
    public String currentId() { return current; }
    public byte[] hash(String id, String domain, String... values) {
        byte[] key = keys.get(id);
        if (key == null) throw new LoginProof.Failure(LoginProof.Error.UNAVAILABLE);
        if (!Set.of("armoney-intent-payload-v1", "armoney-intent-binding-v1", "armoney-intent-authority-v1", "armoney-intent-actor-v1",
                "armoney-code-confirmation-v1", "armoney-confirmation-binding-v1",
                "armoney-confirmation-anchor-actor-v1", "armoney-confirmation-authority-v1").contains(domain))
            throw new IllegalArgumentException("Invalid intent retention domain");
        try {
            var bytes = new ByteArrayOutputStream(); var out = new DataOutputStream(bytes);
            var fields = new ArrayList<String>(); fields.add(domain); fields.addAll(Arrays.asList(values));
            for (String field : fields) { byte[] value = Objects.requireNonNull(field).getBytes(StandardCharsets.UTF_8); out.writeInt(value.length); out.write(value); }
            var mac = Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(bytes.toByteArray());
        } catch (java.io.IOException | java.security.GeneralSecurityException unavailable) {
            throw new LoginProof.Failure(LoginProof.Error.UNAVAILABLE);
        }
    }
    @Override public String toString() { return "CodeIntentRetentionKeys[redacted]"; }
}
