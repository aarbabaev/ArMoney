package com.arman.bank.sso;

import org.junit.jupiter.api.Test;
import org.keycloak.models.*;
import org.keycloak.sessions.AuthenticationSessionModel;
import org.keycloak.protocol.oidc.utils.*;
import org.keycloak.services.clientpolicy.*;
import org.keycloak.services.clientpolicy.context.TokenResponseContext;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.*;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Exercises actual pinned override methods with synthetic provider collaborators; no runtime claim. */
final class ProofHookTest {
    @Test void redirectWaitsForAcknowledgementAndRetriesSameCommandAndKeyAfterUncertainty() throws Exception {
        var fixture=new Fixture();
        var transport=new LoginProofTest.Transport(new LoginProofTest.Reply(503,"{}"),new LoginProofTest.Reply(200,LoginProofTest.reply(true)));
        var protocol=fixture.protocol(true,LoginProofTest.client(transport));
        var failure=assertThrows(WebApplicationException.class,()->protocol.buildRedirectUri(fixture.builder(),fixture.auth,fixture.user,fixture.context));
        assertEquals(503,failure.getResponse().getStatus());
        assertNull(failure.getResponse().getEntity());
        assertEquals("no-store",failure.getResponse().getHeaderString("Cache-Control"));
        assertEquals(MediaType.APPLICATION_JSON_TYPE,failure.getResponse().getMediaType());
        assertDoesNotThrow(()->StatusBoundaryTest.applyPinnedSecurityHeaders(failure.getResponse()));
        String pending=fixture.notes.get("armoney.code-proof."+LoginProofTest.CODE); assertNotNull(pending);
        var original=LoginProof.write(Bridge.strictJson(pending.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        try(var response=protocol.buildRedirectUri(fixture.builder(),fixture.auth,fixture.user,fixture.context)) {
            assertEquals(302,response.getStatus()); assertEquals(fixture.location,response.getLocation());
        }
        assertEquals(pending,fixture.notes.get("armoney.code-proof."+LoginProofTest.CODE));
        assertEquals("current",original.keyId()); assertEquals(LoginProofTest.NOW.minusSeconds(4),original.passwordTime());
        assertEquals(2,transport.requests.size()); assertEquals("code:"+LoginProofTest.CODE,fixture.readCodeKey);
    }
    @Test void reusePendingWriteRetainsExplicitNullsAndRetriesIdenticalHttpPayload() throws Exception {
        var fixture=new Fixture();
        fixture.notes.remove(PasswordProofFactory.NOTE); fixture.notes.remove(PasswordProofFactory.TIME_NOTE);
        var transport=new LoginProofTest.Transport(new LoginProofTest.Reply(503,"{}"),
                new LoginProofTest.Reply(200,LoginProofTest.reuseReply()));
        var protocol=fixture.protocol(true,LoginProofTest.client(transport));
        var failure=assertThrows(WebApplicationException.class,
                ()->protocol.buildRedirectUri(fixture.builder(),fixture.auth,fixture.user,fixture.context));
        try(var response=failure.getResponse()) {
            assertEquals(503,response.getStatus()); assertNull(response.getEntity());
            assertEquals("no-store",response.getHeaderString("Cache-Control"));
            assertDoesNotThrow(()->StatusBoundaryTest.applyPinnedSecurityHeaders(response));
        }
        String pending=fixture.notes.get("armoney.code-proof."+LoginProofTest.CODE);
        assertNotNull(pending);
        var stored=Bridge.strictJson(pending.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertEquals(13,stored.size());
        assertTrue(stored.has("event_id") && stored.get("event_id").isNull());
        assertTrue(stored.has("password_authenticated_at") && stored.get("password_authenticated_at").isNull());
        var original=LoginProof.write(stored);
        assertEquals("reuse",original.kind()); assertNull(original.event()); assertNull(original.passwordTime());
        try(var response=protocol.buildRedirectUri(fixture.builder(),fixture.auth,fixture.user,fixture.context)) {
            assertEquals(302,response.getStatus()); assertEquals(fixture.location,response.getLocation());
        }
        assertEquals(pending,fixture.notes.get("armoney.code-proof."+LoginProofTest.CODE));
        assertEquals(2,transport.requests.size());
        assertArrayEquals(LoginProofTest.requestBody(transport.requests.getFirst()),
                LoginProofTest.requestBody(transport.requests.getLast()));
        assertEquals(original,LoginProof.write(Bridge.strictJson(LoginProofTest.requestBody(transport.requests.getLast()))));
    }
    @Test void mismatchedOrMissingPersistedCodeNeverWritesProofOrReleasesRedirect() {
        for(boolean missing:List.of(false,true)) {
            var fixture=new Fixture(); fixture.persisted=missing?null:new OAuth2Code(LoginProofTest.CODE,"different-client",
                    (int)LoginProofTest.EXPIRY.getEpochSecond(),null,"openid",null,null,null,null,null,"session-a").serializeCode();
            var transport=new LoginProofTest.Transport(); var protocol=fixture.protocol(true,LoginProofTest.client(transport));
            assertThrows(WebApplicationException.class,()->protocol.buildRedirectUri(fixture.builder(),fixture.auth,fixture.user,fixture.context));
            assertTrue(transport.requests.isEmpty()); assertFalse(fixture.notes.containsKey("armoney.code-proof."+LoginProofTest.CODE));
        }
    }
    @Test void disabledRedirectHookDelegatesWithoutProofAccess() {
        var fixture=new Fixture(); var protocol=fixture.protocol(false,null);
        try(var response=protocol.buildRedirectUri(fixture.builder(),fixture.auth,fixture.user,fixture.context)) { assertEquals(302,response.getStatus()); }
        assertNull(fixture.readCodeKey);
    }
    @Test void nonCodeContextsAndDisabledPolicyDoNotResolveOrMintProof() throws Exception {
        var policy=new ProofTokenPolicy(null,null,null,Clock.systemUTC(),true);
        for(var event:List.of(ClientPolicyEvent.TOKEN_REFRESH_RESPONSE,ClientPolicyEvent.TOKEN_EXCHANGE_REQUEST,
                ClientPolicyEvent.TOKEN_INTROSPECT,ClientPolicyEvent.SERVICE_ACCOUNT_TOKEN_RESPONSE,ClientPolicyEvent.IMPLICIT_HYBRID_TOKEN_RESPONSE))
            policy.executeOnEvent(()->event);
        var invalid=new TokenResponseContext(null,null,null,null);
        new ProofTokenPolicy(null,null,null,Clock.systemUTC(),false).executeOnEvent(invalid);
        assertThrows(ClientPolicyException.class,()->policy.executeOnEvent(invalid));
    }
    static final class Fixture {
        final Map<String,String> notes=new HashMap<>(Map.of(PasswordProofFactory.NOTE,LoginProofTest.EVENT,
                PasswordProofFactory.TIME_NOTE,LoginProofTest.NOW.minusSeconds(4).toString()));
        final URI location=URI.create("https://callback.example/oauth?state=unchanged&code="+LoginProofTest.CODE+".session-a.client-internal");
        Map<String,String> persisted=new OAuth2Code(LoginProofTest.CODE,"client-internal",(int)LoginProofTest.EXPIRY.getEpochSecond(),
                "preserved-nonce","openid",null,"https://callback.example/oauth","challenge","S256",null,"session-a").serializeCode();
        String readCodeKey;
        final RealmModel realm=proxy(RealmModel.class,(name,args)->switch(name){case "getId"->"realm-id";case "getName"->"armoney";case "getAccessTokenLifespan"->900;default->unexpected(name);});
        final UserModel owner=proxy(UserModel.class,(name,args)->name.equals("getId")?"subject-a":unexpected(name));
        final ClientModel client=proxy(ClientModel.class,(name,args)->switch(name){case "getId"->"client-internal";case "getClientId"->"armoney-web";case "getAttribute"->null;default->unexpected(name);});
        final UserSessionModel user=proxy(UserSessionModel.class,(name,args)->switch(name){case "getId"->"session-a";case "getUser"->owner;case "getRealm"->realm;default->unexpected(name);});
        final AuthenticatedClientSessionModel clientSession=proxy(AuthenticatedClientSessionModel.class,(name,args)->switch(name){case "getClient"->client;case "getUserSession"->user;default->unexpected(name);});
        final ClientSessionContext context=proxy(ClientSessionContext.class,(name,args)->name.equals("getClientSession")?clientSession:unexpected(name));
        final AuthenticationSessionModel auth=proxy(AuthenticationSessionModel.class,(name,args)->switch(name){
            case "getClient"->client;
            case "getClientNote"->args[0].equals("response_type")?"code":null;
            case "getAuthNote"->notes.get(args[0]);
            case "setAuthNote"->{notes.put((String)args[0],(String)args[1]);yield null;}
            default->unexpected(name);
        });
        final SingleUseObjectProvider codeStore=proxy(SingleUseObjectProvider.class,(name,args)->{
            if(!name.equals("get")) return unexpected(name); readCodeKey=(String)args[0];return persisted;
        });
        final KeycloakSession session=proxy(KeycloakSession.class,(name,args)->name.equals("singleUseObjects")?codeStore:unexpected(name));
        ProofLoginProtocol protocol(boolean enabled,ProofClient proofClient) {
            var protocol=new ProofLoginProtocol(null,enabled,proofClient,LoginProofTest.keys(),Clock.fixed(LoginProofTest.NOW,ZoneOffset.UTC),(id,auth)->"opaque-binding");
            protocol.setSession(session).setRealm(realm).setUriInfo(proxy(UriInfo.class,(name,args)->name.equals("getBaseUri")?URI.create("https://identity.example/sso/"):unexpected(name)));
            return protocol;
        }
        OIDCRedirectUriBuilder builder() {
            return new OIDCRedirectUriBuilder(null) {
                public OIDCRedirectUriBuilder addParam(String key,String value){throw new AssertionError("Parent-created response must stay unchanged");}
                public Response build(){return Response.status(302).location(location).build();}
            };
        }
    }
    interface Invocation { Object call(String name,Object[] args); }
    @SuppressWarnings("unchecked") static <T> T proxy(Class<T> type,Invocation invocation) {
        return (T)Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},(proxy,method,args)->invocation.call(method.getName(),args));
    }
    static Object unexpected(String name){throw new AssertionError("Unexpected provider call: "+name);}
}
