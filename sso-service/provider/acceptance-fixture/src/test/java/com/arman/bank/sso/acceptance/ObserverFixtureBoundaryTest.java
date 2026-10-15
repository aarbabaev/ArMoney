package com.arman.bank.sso.acceptance;

import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.util.List;
import jakarta.ws.rs.core.Response;
import static org.junit.jupiter.api.Assertions.*;

final class ObserverFixtureBoundaryTest {
    private static final String KEY = "synthetic-fixture-key-not-for-any-live-stack";
    private static final String BODY = "{\"client_id\":\"armoney-web\",\"provider_root_session_id\":\"root\",\"provider_tab_id\":\"tab\"}";
    @Test void strictTupleRejectsAmbiguityAndAnyContactOrIdentityAuthority() throws Exception {
        assertEquals(new ObserverFixtureResource.Tuple("armoney-web","root","tab"),ObserverFixtureResource.parse(bytes(BODY)));
        for (String bad : List.of("null","[]","{}",BODY.replace("armoney-web","other-client"),
                BODY.replace("\"root\"","null"),BODY.replace("\"root\"","\"with space\""),
                BODY.replace("\"root\"","\"root\",\"email_verified\":true")))
            assertThrows(IllegalArgumentException.class, () -> ObserverFixtureResource.parse(bytes(bad)));
        assertThrows(java.io.IOException.class, () -> ObserverFixtureResource.parse(bytes("{}{}")));
        assertThrows(java.io.IOException.class, () -> ObserverFixtureResource.parse(bytes(BODY.replace("\"root\"",
                "\"root\",\"provider_root_session_id\":\"other\""))));
        assertThrows(IllegalArgumentException.class, () -> ObserverFixtureResource.parse(new byte[4097]));
        assertThrows(IllegalArgumentException.class, () -> ObserverFixtureResource.parse(null));
    }
    @Test void disabledOrUnconfiguredFixtureCannotTouchProviderModels() {
        var disabled = new ObserverFixtureResource(null,()->false,()-> {throw new AssertionError("Key must not be read");});
        check(disabled.inspect(KEY,bytes(BODY)),503); check(disabled.deleteTab(KEY,bytes(BODY)),503); check(disabled.deleteRoot(KEY,bytes(BODY)),503);
        for (String invalid : new String[]{null,"short",KEY+"\r\n","x".repeat(513)})
            check(new ObserverFixtureResource(null,()->true,()->invalid).deleteRoot(KEY,bytes(BODY)),503);
    }
    @Test void credentialPrecedesBodyParsingAndAllControlsAreProtected() {
        var resource = new ObserverFixtureResource(null,()->true,()->KEY);
        check(resource.inspect(null,new byte[5000]),401);
        check(resource.deleteTab("wrong",bytes(BODY)),401);
        check(resource.deleteRoot("x".repeat(513),bytes(BODY)),401);
        check(resource.inspect(KEY,bytes("{}")),400);
        check(resource.deleteTab(KEY,bytes("{}")),400);
        check(resource.deleteRoot(KEY,new byte[4097]),400);
    }
    private static byte[] bytes(String body) { return body.getBytes(StandardCharsets.UTF_8); }
    private static void check(Response response,int expected) {
        try(response) { assertEquals(expected,response.getStatus()); assertNull(response.getEntity()); assertEquals("no-store",response.getHeaderString("Cache-Control")); }
    }
}
