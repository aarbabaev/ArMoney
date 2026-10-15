package com.arman.bank.sso;

import org.junit.jupiter.api.Test;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import static org.junit.jupiter.api.Assertions.*;

final class EnrollmentBindingTest {
    private static final String KEY = "synthetic-binding-key-for-isolated-fixtures";
    @Test void pinsDomainLengthPrefixOrderAndInternalClientId() throws Exception {
        String[] fields = {"internal-realm", "root", "tab", "internal-client"};
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(KEY.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        mac.update("armoney-provider-binding-v1".getBytes(StandardCharsets.US_ASCII));
        for (String field : fields) {
            byte[] bytes = field.getBytes(StandardCharsets.UTF_8);
            mac.update(ByteBuffer.allocate(4).putInt(bytes.length).array()); mac.update(bytes);
        }
        String expected = Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal());
        assertEquals(expected, SessionBinding.derive(KEY, fields));
        assertTrue(SessionBinding.matches(expected, expected));
        assertFalse(SessionBinding.matches(expected, SessionBinding.derive(KEY,"internal-realm","root","tab","armoney-web")));
        assertFalse(SessionBinding.matches(expected, SessionBinding.derive(KEY,"internal-realm","root","other-tab","internal-client")));
        assertNotEquals(SessionBinding.derive(KEY,"ab","c"), SessionBinding.derive(KEY,"a","bc"));
    }
    @Test void rejectsAlternateEncodingsAndInvalidKeys() {
        String digest = SessionBinding.derive(KEY,"realm","root","tab","client");
        for (String bad : new String[]{"", digest + "=", digest.substring(1), "!".repeat(43), "A".repeat(42)+"B"})
            assertThrows(IllegalArgumentException.class, () -> SessionBinding.decode(bad));
        assertThrows(IllegalArgumentException.class, () -> SessionBinding.derive("short","realm"));
    }
}
