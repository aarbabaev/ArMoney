package com.arman.bank.sso;

import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.keycloak.sessions.AuthenticationSessionModel;
import org.keycloak.sessions.RootAuthenticationSessionModel;
import java.lang.reflect.Proxy;
import static org.junit.jupiter.api.Assertions.*;

@ResourceLock("code-intent-fixture-install")
class CodeIntentFixtureLoginProtocolTest {
    @Test void absentFixtureNeverReleasesAnOrdinaryRedirect() {
        assertUnavailable(new CodeIntentFixtureLoginProtocol(null).buildRedirectUri(null, null, null, null));
    }

    @Test void installedButUnboundFixtureNeverReleasesAnOrdinaryRedirect() {
        try (var h = new CodeIntentFixtureSupportTest.Harness()) {
            CodeIntentFixtureSupport.install(h.support);
            h.support.arm("subject", "armoney-ios");
            assertUnavailable(new CodeIntentFixtureLoginProtocol(null).buildRedirectUri(null, null, null, null));
            assertEquals(0, h.peer.calls);
            assertEquals(0, h.production.calls);
        }
    }

    @Test void matchingFlowWithoutStagePermissionFailsClosedAtGenuineGenerator() {
        try (var h = new CodeIntentFixtureSupportTest.Harness()) {
            CodeIntentFixtureSupport.install(h.support);
            String handle = h.ready();
            var root = (RootAuthenticationSessionModel) Proxy.newProxyInstance(
                    RootAuthenticationSessionModel.class.getClassLoader(), new Class<?>[]{RootAuthenticationSessionModel.class},
                    (p, method, args) -> {
                        if (method.getName().equals("getId")) return "root";
                        throw new AssertionError(method.getName());
                    });
            var auth = (AuthenticationSessionModel) Proxy.newProxyInstance(
                    AuthenticationSessionModel.class.getClassLoader(), new Class<?>[]{AuthenticationSessionModel.class},
                    (p, method, args) -> switch (method.getName()) {
                        case "getParentSession" -> root;
                        case "getTabId" -> "tab";
                        default -> throw new AssertionError(method.getName());
                    });
            assertTrue(CodeIntentFixtureSupport.handles(auth));
            assertUnavailable(new CodeIntentFixtureLoginProtocol(null).buildRedirectUri(null, auth, null, null));
            assertEquals("READY", h.support.state(handle));
            assertEquals(0, h.peer.calls);
            assertEquals(0, h.production.calls);
        }
    }

    private static void assertUnavailable(Response response) {
        try (response) {
            assertEquals(503, response.getStatus());
            assertEquals("no-store", response.getHeaderString("Cache-Control"));
            assertNull(response.getLocation());
        }
    }
}
