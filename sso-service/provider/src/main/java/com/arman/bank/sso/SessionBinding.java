package com.arman.bank.sso;

import org.keycloak.sessions.AuthenticationSessionModel;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

final class SessionBinding {
    static String derive(String key, String... fields) {
        if (key == null || key.length() < 32) throw new IllegalArgumentException("Invalid binding configuration");
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            mac.update("armoney-provider-binding-v1".getBytes(StandardCharsets.US_ASCII));
            for (String field : fields) {
                if (field == null || field.isBlank() || field.length() > 512) throw new IllegalArgumentException("Invalid binding");
                byte[] bytes = field.getBytes(StandardCharsets.UTF_8);
                mac.update(ByteBuffer.allocate(4).putInt(bytes.length).array()); mac.update(bytes);
            }
            return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal());
        } catch (java.security.GeneralSecurityException impossible) { throw new IllegalStateException("Binding unavailable"); }
    }
    static String of(String realmId, AuthenticationSessionModel session) {
        return derive(Bridge.required("ARMONEY_PROVIDER_BINDING_KEY"), realmId,
                session.getParentSession().getId(), session.getTabId(), session.getClient().getId());
    }
    private SessionBinding() { }
}
