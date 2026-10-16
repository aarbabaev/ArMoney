package com.arman.bank.sso;

import org.junit.jupiter.api.Test;
import org.keycloak.models.*;
import org.keycloak.sessions.*;
import org.keycloak.common.util.Time;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Synthetic model tests establish boundaries, not committed-state/cluster visibility. */
final class EnrollmentObservationTest {
    private static final String KEY = "synthetic-binding-key-for-isolated-fixtures";
    private static final String ISSUER = "https://issuer.example/realms/armoney";
    private static final String RESULT = "{\"enrollment_id\":\"00000000-0000-0000-0000-000000000001\",\"version\":9,\"state\":\"PROJECTED\",\"email_contact_version\":2,\"phone_contact_version\":3,\"email_verified\":true,\"phone_verified\":true}";

    @Test void firstChallengeOnlyRendersWithoutReadingAuthenticationSessionOrCallingBridge() {
        var rendered = new java.util.concurrent.atomic.AtomicBoolean();
        var form = (org.keycloak.forms.login.LoginFormsProvider)Proxy.newProxyInstance(
                getClass().getClassLoader(),new Class<?>[]{org.keycloak.forms.login.LoginFormsProvider.class},(proxy,method,args)-> {
                    if (method.getName().equals("setAttribute")) return proxy;
                    if (method.getName().equals("createForm")) return jakarta.ws.rs.core.Response.ok().build();
                    throw new AssertionError(method.getName());
                });
        var context = model(org.keycloak.authentication.RequiredActionContext.class,Map.of("form",form,
                "challenge",(Call)(args,type)-> { rendered.set(true); ((jakarta.ws.rs.core.Response)args[0]).close(); return null; }));
        new VerificationAction("REGISTRATION_EMAIL","email",()->true).requiredActionChallenge(context);
        assertTrue(rendered.get());
    }

    @Test void exactSevenResultFieldsPreserveIndependentVersionsAndDenyActive() throws Exception {
        var result = EnrollmentObservation.enrollment(json(RESULT));
        assertEquals(9,result.get("version").intValue());
        assertEquals(2,result.get("email_contact_version").intValue());
        assertEquals(3,result.get("phone_contact_version").intValue());
        for (String bad : List.of(RESULT.replace("PROJECTED","ACTIVE"), RESULT.replace("9,","0,"),
                RESULT.replace("9,","9.0,"), RESULT.replace("true","\"true\""),
                RESULT.replace("\"version\":9,",""), RESULT.replace("9,","2147483648,"),
                RESULT.replace("\"version\":9,","\"epoch\":1,\"version\":9,")))
            assertThrows(Bridge.Unavailable.class, () -> EnrollmentObservation.enrollment(json(bad)));
    }
    @Test void strictSixContextRejectsUnknownNullAndNoncanonicalHmac() throws Exception {
        String body = org.keycloak.util.JsonSerialization.writeValueAsString(binding().fields());
        assertEquals(binding(),EnrollmentObservation.parse(json(body)));
        for (String bad : List.of(body.replace("\"subject\":\"subject\"","\"subject\":null"),
                body.replace("\"subject\":\"subject\"","\"subject\":\"with space\""),
                body.replace("\"subject\":\"subject\"","\"subject\":\"subject\",\"epoch\":1"),
                body.replace(binding().digest(),binding().digest()+"=")))
            assertThrows(IllegalArgumentException.class, () -> EnrollmentObservation.parse(json(bad)));
        assertThrows(java.io.IOException.class, () -> json(body.replace("\"subject\":\"subject\"",
                "\"subject\":\"subject\",\"subject\":\"other\"")));
    }
    @Test void userDeadlineIsStrictAndCannotUseBroaderCacheLifespan() {
        assertEquals(130,EnrollmentObservation.deadline(100,120,30,1000));
        assertEquals(125,EnrollmentObservation.deadline(100,120,30,125));
        for (long[] args : new long[][]{{100,130,30,1000},{121,120,30,1000},{0,120,30,1000},
                {100,120,0,1000},{100,120,30,100},{Long.MAX_VALUE-1,Long.MAX_VALUE-1,5,Long.MAX_VALUE}})
            assertThrows(Bridge.Unavailable.class, () -> EnrollmentObservation.deadline(args[0],args[1],args[2],args[3]));
    }
    @Test void observedTwelveFieldsRequireCurrentRootTabClientUserAndReadOnlyUserPhase() {
        Fixture fixture = new Fixture();
        var result = EnrollmentObservation.observe(fixture.session(),binding(),KEY);
        assertEquals(12,result.size()); assertEquals(true,result.get("authentication_session_active"));
        assertEquals("+971501234567",result.get("phone_number"));
        assertEquals(Instant.ofEpochSecond(fixture.timestamp+120).toString(),result.get("authentication_session_expires_at"));
        assertFalse(result.containsKey("email_verified")); assertFalse(result.containsKey("verification_epoch"));
        for (String bad : List.of("missing-root","missing-tab","wrong-user","wrong-client","wrong-phase","disabled-user",
                "disabled-client","direct-grant-only","noncanonical-phone","noncanonical-email","expired","future")) {
            fixture.fault = bad;
            assertThrows(Bridge.Unavailable.class, () -> EnrollmentObservation.observe(fixture.session(),binding(),KEY),bad);
        }
        fixture.fault = "";
        assertThrows(Bridge.Unavailable.class, () -> EnrollmentObservation.observe(fixture.session(),binding(),KEY+"other"));
        var wrongIssuer = new EnrollmentObservation.Binding("https://other.example/realms/armoney","subject","armoney-web","root","tab",binding().digest());
        assertThrows(Bridge.Unavailable.class, () -> EnrollmentObservation.observe(fixture.session(),wrongIssuer,KEY));
        var wrongClient = new EnrollmentObservation.Binding(ISSUER,"subject","other-client","root","tab",binding().digest());
        assertThrows(Bridge.Unavailable.class, () -> EnrollmentObservation.observe(fixture.session(),wrongClient,KEY));
    }
    private static EnrollmentObservation.Binding binding() {
        return new EnrollmentObservation.Binding(ISSUER,"subject","armoney-web","root","tab",
                SessionBinding.derive(KEY,"realm-internal","root","tab","client-internal"));
    }
    @Test void enrollmentResourceReturnsEmptyUncacheable503ForCanonicalWrongHmac() throws Exception {
        Fixture fixture = new Fixture();
        var resource = new StatusResource(fixture.session(), () -> KEY, () -> KEY, () -> true);
        var request = binding().fields();
        // The real CI negative uses a canonical 32-byte all-zero digest. It must
        // pass syntax parsing, fail server HMAC comparison, and reach the HTTP
        // boundary's RuntimeException catch rather than escape to Keycloak.
        request.put("authentication_session_id", "A".repeat(43));
        assertFalse(SessionBinding.matches(binding().digest(), (String) request.get("authentication_session_id")));
        try (var response = resource.enrollment(KEY, org.keycloak.util.JsonSerialization.writeValueAsBytes(request))) {
            assertEquals(503, response.getStatus());
            assertNull(response.getEntity());
            assertEquals("no-store", response.getHeaderString("Cache-Control"));
            assertDoesNotThrow(() -> StatusBoundaryTest.applyPinnedSecurityHeaders(response));
        }
    }
    private static com.fasterxml.jackson.databind.JsonNode json(String input) throws java.io.IOException {
        return Bridge.strictJson(input.getBytes(StandardCharsets.UTF_8));
    }
    private static final class Fixture {
        final int timestamp = Time.currentTime()-10;
        String fault = "";
        KeycloakSession session() {
            return model(KeycloakSession.class, Map.of("getContext", (Call)(args,type) -> model(type,Map.of(
                    "getRealm",(Call)(a,t)->realm(), "getUri",(Call)(a,t)->uri())),
                    "authenticationSessions", (Call)(args,type)->model(type,Map.of("getRootAuthenticationSession",(Call)(a,t)->fault.equals("missing-root")?null:root()))));
        }
        KeycloakUriInfo uri() {
            var hostname = model(org.keycloak.urls.HostnameProvider.class,Map.of("getBaseUri",(Call)(a,t)->URI.create("https://issuer.example/")));
            var owner = model(KeycloakSession.class,Map.of("getProvider",hostname));
            return new KeycloakUriInfo(owner,org.keycloak.urls.UrlType.FRONTEND,null);
        }
        RealmModel realm() { return model(RealmModel.class,Map.of("getName","armoney","getId","realm-internal",
                "getClientByClientId",(Call)(a,t)->client(),"getAccessCodeLifespanUserAction",120,
                "getAccessCodeLifespanLogin",600,"getAccessCodeLifespan",60)); }
        ClientModel client() { return model(ClientModel.class,Map.of("getId",fault.equals("wrong-client")?"other":"client-internal",
                "isEnabled",!fault.equals("disabled-client"),"isStandardFlowEnabled",!fault.equals("direct-grant-only"))); }
        RootAuthenticationSessionModel root() { return model(RootAuthenticationSessionModel.class,Map.of("getId","root",
                "getTimestamp",fault.equals("expired")?timestamp-1000:fault.equals("future")?timestamp+1000:timestamp,
                "getAuthenticationSession",(Call)(a,t)->fault.equals("missing-tab")?null:auth())); }
        AuthenticationSessionModel auth() { return model(AuthenticationSessionModel.class,Map.of("getTabId","tab",
                "getClient",(Call)(a,t)->client(),"getParentSession",(Call)(a,t)->root(),
                "getAction",fault.equals("wrong-phase")?"AUTHENTICATE":"REQUIRED_ACTIONS",
                "getAuthenticatedUser",(Call)(a,t)->model(UserModel.class,Map.of("getId",fault.equals("wrong-user")?"other":"subject",
                        "isEnabled",!fault.equals("disabled-user"),"getUsername",fault.equals("noncanonical-phone")?"legacy":"+971501234567",
                        "getEmail",fault.equals("noncanonical-email")?"Upper@example.test":"fixture@example.test")))); }
    }
    private interface Call { Object invoke(Object[] args, Class<?> returnType); }
    @SuppressWarnings("unchecked") private static <T> T model(Class<T> type, Map<String,Object> methods) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},(proxy,method,args)-> {
            if (!methods.containsKey(method.getName())) throw new AssertionError("Unexpected provider operation: "+method.getName());
            Object value=methods.get(method.getName());
            return value instanceof Call call?call.invoke(args,method.getReturnType()):value;
        });
    }
}
