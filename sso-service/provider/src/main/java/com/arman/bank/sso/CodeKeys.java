package com.arman.bank.sso;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;

final class CodeKeys {
    record Key(String id, byte[] bytes) {
        Key {
            if (id == null || !id.matches("[A-Za-z0-9_-]{1,32}") || bytes == null || bytes.length != 32) LoginProof.invalid();
            bytes = bytes.clone();
        }
        @Override public byte[] bytes() { return bytes.clone(); }
        @Override public String toString() { return "CodeKey[REDACTED]"; }
        static Key fromBase64(String id, String value) {
            byte[] decoded = Base64.getDecoder().decode(value);
            if (!Base64.getEncoder().encodeToString(decoded).equals(value)) LoginProof.invalid();
            return new Key(id, decoded);
        }
    }
    private final List<Key> keys;
    CodeKeys(Key current, Key previous) {
        if (current == null || previous != null && current.id.equals(previous.id)) LoginProof.invalid();
        keys = previous == null ? List.of(current) : List.of(current, previous);
    }
    List<Key> keys() { return keys; }
    String digest(Key key, LoginProof.Binding binding, String codeId) {
        LoginProof.uuid(codeId);
        try {
            Mac mac = Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(key.bytes, "HmacSHA256"));
            for (String field : List.of("armoney-login-code-v1", key.id, binding.issuer(), binding.subject(), binding.client(), binding.session(), codeId)) {
                byte[] bytes = field.getBytes(StandardCharsets.UTF_8);
                mac.update(ByteBuffer.allocate(4).putInt(bytes.length).array()); mac.update(bytes);
            }
            return HexFormat.of().formatHex(mac.doFinal());
        } catch (java.security.GeneralSecurityException impossible) { throw new IllegalStateException("Proof digest unavailable"); }
    }
}
