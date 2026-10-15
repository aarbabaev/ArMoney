package com.arman.bank.sso;

import org.junit.jupiter.api.Test;
import org.keycloak.sessions.AuthenticationSessionModel;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

final class IssueCommandTest {
    private static final String KEY = "synthetic-issue-key-never-a-live-secret-123456";
    private static final String ENROLLMENT = "a1111111-1111-4111-8111-111111111111";
    private static final String CHALLENGE = "b1111111-1111-4111-8111-111111111111";
    private static final String ISSUE_NOTE = "action.issue";

    @Test void successfulSendThenCooldownRejectionCanSendAgainWithNewCommand() {
        var notes = new HashMap<String, String>(); var session = session(notes);
        var client = new BridgeContractTest.FixtureClient(201, "{\"challenge_id\":\"" + CHALLENGE + "\"}");
        var bridge = new Bridge(URI.create("http://auth-service:8081"), KEY, client);
        var action = new VerificationAction("REGISTRATION_EMAIL", "action");
        var first = new HashMap<String, Object>(); action.issue(session, bridge, ENROLLMENT, 1, first);
        assertNull(notes.get(ISSUE_NOTE)); assertEquals(CHALLENGE, notes.get("action.challenge"));
        client.status = 429;
        var rejected = new HashMap<String, Object>();
        assertThrows(Bridge.Rejected.class, () -> action.issue(session, bridge, ENROLLMENT, 1, rejected));
        assertNull(notes.get(ISSUE_NOTE));
        assertNotEquals(first.get("command_id"), rejected.get("command_id"));
        // Synthetic cooldown advances. Auth would replay the previous rejection if its command were reused.
        client.status = 201;
        var afterCooldown = new HashMap<String, Object>(); action.issue(session, bridge, ENROLLMENT, 1, afterCooldown);
        assertNotEquals(rejected.get("command_id"), afterCooldown.get("command_id"));
        assertNull(notes.get(ISSUE_NOTE)); assertEquals(CHALLENGE, notes.get("action.challenge"));
    }
    @Test void ambiguousTimeoutRetainsCommandForExactReplay() {
        var notes = new HashMap<String, String>(); var session = session(notes);
        var client = new BridgeContractTest.FixtureClient(201, "{\"challenge_id\":\"" + CHALLENGE + "\"}");
        client.timeout = true;
        var bridge = new Bridge(URI.create("http://auth-service:8081"), KEY, client);
        var action = new VerificationAction("REGISTRATION_EMAIL", "action");
        var timedOut = new HashMap<String, Object>();
        assertThrows(Bridge.Unavailable.class, () -> action.issue(session, bridge, ENROLLMENT, 1, timedOut));
        assertEquals(timedOut.get("command_id"), notes.get(ISSUE_NOTE));
        client.timeout = false;
        var retry = new HashMap<String, Object>(); action.issue(session, bridge, ENROLLMENT, 1, retry);
        assertEquals(timedOut.get("command_id"), retry.get("command_id")); assertNull(notes.get(ISSUE_NOTE));
    }
    private static AuthenticationSessionModel session(Map<String, String> notes) {
        return (AuthenticationSessionModel) Proxy.newProxyInstance(AuthenticationSessionModel.class.getClassLoader(),
                new Class<?>[]{AuthenticationSessionModel.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getAuthNote" -> notes.get((String) args[0]);
                    case "setAuthNote" -> { notes.put((String) args[0], (String) args[1]); yield null; }
                    case "removeAuthNote" -> { notes.remove((String) args[0]); yield null; }
                    default -> throw new AssertionError("Unexpected session operation: " + method.getName());
                });
    }
}
