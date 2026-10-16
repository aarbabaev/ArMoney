package com.arman.bank.sso;

import org.junit.jupiter.api.Test;
import jakarta.ws.rs.core.Response;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

@org.junit.jupiter.api.parallel.ResourceLock("keycloak-profile")
final class CodeSessionBoundaryTest {
    private static boolean ownsProfile;
    @org.junit.jupiter.api.BeforeAll static void initializePinnedProfile() {
        ownsProfile=org.keycloak.common.Profile.getInstance()==null;
        if(ownsProfile) org.keycloak.common.Profile.defaults();
        assertNotNull(org.keycloak.common.Profile.getInstance());
        assertTrue(org.keycloak.common.Profile.isFeatureEnabled(org.keycloak.common.Profile.Feature.PERSISTENT_USER_SESSIONS));
    }
    @org.junit.jupiter.api.AfterAll static void releasePinnedProfile() {
        if(ownsProfile) org.keycloak.common.Profile.reset();
    }
    private static final String KEY="synthetic-code-session-status-key-123456789";
    @Test void authenticationAndDisabledGatePrecedeParsingAndSessionAccess() {
        var disabled=new StatusResource(null,()->KEY,()->null,()->false,()->false);
        check(disabled.codeSession("wrong",new byte[13000]),401);
        check(disabled.codeSession(KEY,"not-json".getBytes(StandardCharsets.UTF_8)),503);
        var enabled=new StatusResource(null,()->KEY,()->null,()->false,()->true);
        check(enabled.codeSession(KEY,new byte[13000]),400);
        check(enabled.codeSession(KEY,"{\"issuer\":1}".getBytes(StandardCharsets.UTF_8)),400);
        check(enabled.codeSession(KEY,"{\"issuer\":\"a\",\"issuer\":\"b\"}".getBytes(StandardCharsets.UTF_8)),400);
        check(new StatusResource(null,()->null,()->null,()->false,()->true).codeSession(KEY,null),503);
    }
    @Test void newRouteHasExplicitPostDiscoveryAndJsonContract() throws Exception {
        var route=StatusResource.class.getMethod("codeSession",String.class,byte[].class);
        assertTrue(route.isAnnotationPresent(jakarta.ws.rs.POST.class));
        assertEquals("code-session",route.getAnnotation(jakarta.ws.rs.Path.class).value());
        assertEquals("application/json",route.getAnnotation(jakarta.ws.rs.Produces.class).value()[0]);
    }
    @Test void successfulDisabledObservationHasExactlyTwelveSerializedFieldsAndPinnedHeaders() throws Exception {
        var fixture=new CodeSessionObservationTest.Fixture(); fixture.user.put("isEnabled",false);
        var resource=new StatusResource(fixture.session(),()->KEY,()->null,()->false,()->true);
        byte[] request="{\"issuer\":\"https://issuer.example/realms/armoney\",\"subject\":\"subject\",\"client_id\":\"armoney-ios\",\"user_session_id\":\"session\"}".getBytes(StandardCharsets.UTF_8);
        try(var response=resource.codeSession(KEY,request)) {
            assertEquals(200,response.getStatus()); assertEquals("no-store",response.getHeaderString("Cache-Control"));
            assertDoesNotThrow(()->StatusBoundaryTest.applyPinnedSecurityHeaders(response));
            var json=Bridge.strictJson(org.keycloak.util.JsonSerialization.writeValueAsBytes(response.getEntity()));
            assertEquals(12,json.size()); assertFalse(json.path("enabled").booleanValue());
            assertTrue(json.get("email").isNull()); assertTrue(json.get("phone_number").isNull());
            assertTrue(json.path("user_session_active").booleanValue()); assertTrue(json.path("client_session_active").booleanValue());
        }
    }
    private static void check(Response response,int expected) {
        try(response) {
            assertEquals(expected,response.getStatus()); assertNull(response.getEntity());
            assertEquals("no-store",response.getHeaderString("Cache-Control"));
            assertEquals(jakarta.ws.rs.core.MediaType.APPLICATION_JSON_TYPE,response.getMediaType());
            assertDoesNotThrow(()->StatusBoundaryTest.applyPinnedSecurityHeaders(response));
        }
    }
}
