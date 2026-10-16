package com.arman.bank.sso;

import org.junit.jupiter.api.Test;
import jakarta.ws.rs.core.Response;
import java.nio.charset.StandardCharsets;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

final class StatusBoundaryTest {
    @Test void packagedResourceMeetsPinnedKeycloakDiscoveryPrerequisites() throws Exception {
        // Keycloak's custom REST SPI requires an empty archive marker and @Provider.
        // Quarkus 3.33.3.2 excludes archives declaring discovery-mode="none".
        // This tests packaging prerequisites; only the two-node run proves routing.
        try (var marker = StatusResource.class.getResourceAsStream("/META-INF/beans.xml")) {
            assertNotNull(marker, "Custom REST archive marker must be packaged");
            assertEquals(0, marker.readAllBytes().length, "Use the supported empty marker, never discovery-mode none");
        }
        assertTrue(StatusResource.class.isAnnotationPresent(jakarta.ws.rs.ext.Provider.class));
        var enrollment = StatusResource.class.getMethod("enrollment", String.class, byte[].class);
        assertTrue(enrollment.isAnnotationPresent(jakarta.ws.rs.POST.class));
        assertEquals("enrollment", enrollment.getAnnotation(jakarta.ws.rs.Path.class).value());
    }
    private static final String KEY = "synthetic-status-key-never-a-live-secret-123456";
    @Test void pinnedSecurityFilterRejectsUntyped503ButPreservesTypedEmptyDenial() {
        try (var original = Response.status(503).header("Cache-Control", "no-store").build()) {
            assertNull(original.getMediaType());
            assertThrows(jakarta.ws.rs.InternalServerErrorException.class, () -> applyPinnedSecurityHeaders(original));
        }
        var resource = new StatusResource(null, () -> KEY, () -> KEY, () -> false);
        try (var fixed = resource.enrollment(KEY, json("{}"))) {
            assertDoesNotThrow(() -> applyPinnedSecurityHeaders(fixed));
            assertEquals(503, fixed.getStatus());
            assertNull(fixed.getEntity());
            assertEquals(jakarta.ws.rs.core.MediaType.APPLICATION_JSON_TYPE, fixed.getMediaType());
            assertEquals("no-store", fixed.getHeaderString("Cache-Control"));
        }
    }

    static void applyPinnedSecurityHeaders(Response response) {
        var hostname = model(org.keycloak.urls.HostnameProvider.class,
                name -> name.equals("getBaseUri") ? java.net.URI.create("https://fixture.example/") : unexpected(name));
        var hostnameSession = model(org.keycloak.models.KeycloakSession.class,
                name -> name.equals("getProvider") ? hostname : unexpected(name));
        var delegate = model(jakarta.ws.rs.core.UriInfo.class,
                name -> name.equals("getRequestUri") ? java.net.URI.create("https://fixture.example/realms/armoney/armoney-status/enrollment") : unexpected(name));
        var uri = new org.keycloak.models.KeycloakUriInfo(hostnameSession, org.keycloak.urls.UrlType.FRONTEND, delegate);
        var realm = model(org.keycloak.models.RealmModel.class,
                name -> name.equals("getBrowserSecurityHeaders") ? java.util.Map.of() : unexpected(name));
        var context = model(org.keycloak.models.KeycloakContext.class, name -> switch (name) {
            case "getRealm" -> realm;
            case "getUri" -> uri;
            default -> unexpected(name);
        });
        var session = model(org.keycloak.models.KeycloakSession.class,
                name -> name.equals("getContext") ? context : unexpected(name));
        var request = model(jakarta.ws.rs.container.ContainerRequestContext.class, name -> switch (name) {
            case "getMethod" -> "POST";
            case "getMediaType" -> jakarta.ws.rs.core.MediaType.APPLICATION_JSON_TYPE;
            default -> unexpected(name);
        });
        var outgoing = model(jakarta.ws.rs.container.ContainerResponseContext.class, name -> switch (name) {
            case "getMediaType" -> response.getMediaType();
            case "getStatus" -> response.getStatus();
            case "hasEntity" -> response.hasEntity();
            case "getHeaders" -> response.getHeaders();
            default -> unexpected(name);
        });
        new org.keycloak.headers.DefaultSecurityHeadersProvider(session).addHeaders(request, outgoing);
    }
    private static Object unexpected(String name) { throw new AssertionError("Unexpected fixture operation: " + name); }
    @SuppressWarnings("unchecked")
    private static <T> T model(Class<T> type, java.util.function.Function<String, Object> behavior) {
        return (T) java.lang.reflect.Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                (proxy, method, args) -> behavior.apply(method.getName()));
    }
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
    @Test void enrollmentObservationIsDefaultOffAndUsesIndependentCredentialFirst() {
        var resource = new StatusResource(null, () -> KEY, () -> { throw new AssertionError("Binding key accessed"); }, () -> false);
        assertEmpty(resource.enrollment("wrong", new byte[20000]),401);
        assertEmpty(resource.enrollment(KEY, json("{}")),503);
        assertEmpty(new StatusResource(null, () -> null).enrollment(KEY,json("{}")),503);
    }
    @Test void enrollmentObservationRejectsMalformedOrPostTokenShapeBeforeProviderAccess() {
        var resource = new StatusResource(null, () -> KEY, () -> KEY, () -> true);
        assertEmpty(resource.enrollment(KEY,null),400);
        assertEmpty(resource.enrollment(KEY,new byte[12289]),400);
        for (String body : List.of("null","[]","{}","{}{}",
                "{\"issuer\":\"https://issuer.example/realms/armoney\",\"subject\":\"s\",\"user_session_id\":\"sid\"}"))
            assertEmpty(resource.enrollment(KEY,json(body)),400);
    }
    private static byte[] json(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    private static void assertEmpty(Response response, int status) {
        try (response) {
            assertEquals(status, response.getStatus()); assertNull(response.getEntity());
            assertEquals(jakarta.ws.rs.core.MediaType.APPLICATION_JSON_TYPE, response.getMediaType());
            assertEquals("no-store", response.getHeaderString("Cache-Control"));
        }
    }
}
