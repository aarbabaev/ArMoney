package com.arman.bank.sso;

import org.junit.jupiter.api.Test;
import jakarta.ws.rs.core.Response;
import java.nio.charset.StandardCharsets;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

final class StatusBoundaryTest {
    private static final String KEY = "synthetic-status-key-never-a-live-secret-123456";
    @Test void missingOrMalformedConfigurationFailsClosedBeforeProviderAccess() {
        for (String value : new String[]{null, "short", KEY + "\r\n", "x".repeat(513)})
            assertEmpty(new StatusResource(null, () -> value).status(KEY, json("{}")), 503);
    }
    @Test void dedicatedCredentialIsRequiredBeforeParsingBody() {
        var resource = new StatusResource(null, () -> KEY);
        for (String supplied : new String[]{null, "wrong", "x".repeat(513)})
            assertEmpty(resource.status(supplied, new byte[20000]), 401);
    }
    @Test void boundedStrictBodyRejectsAmbiguityAndCallerIdentityOmissions() {
        var resource = new StatusResource(null, () -> KEY);
        assertEmpty(resource.status(KEY, null), 400);
        assertEmpty(resource.status(KEY, new byte[12289]), 400);
        for (String body : List.of("null", "[]", "{}", "{}{}",
                "{\"issuer\":\"x\",\"subject\":\"s\",\"subject\":\"other\",\"user_session_id\":\"a\"}",
                "{\"issuer\":\"x\",\"subject\":\"s\",\"user_session_id\":\"a\",\"enabled\":true}",
                "{\"issuer\":\"x\",\"subject\":\"s\",\"user_session_id\":null}",
                "{\"issuer\":\"x\",\"subject\":\"s\",\"user_session_id\":\"with space\"}"))
            assertEmpty(resource.status(KEY, json(body)), 400);
    }
    private static byte[] json(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    private static void assertEmpty(Response response, int status) {
        try (response) {
            assertEquals(status, response.getStatus()); assertNull(response.getEntity());
            assertEquals("no-store", response.getHeaderString("Cache-Control"));
        }
    }
}
